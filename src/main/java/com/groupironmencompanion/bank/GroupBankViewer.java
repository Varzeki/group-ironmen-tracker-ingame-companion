package com.groupironmencompanion.bank;

import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupState;
import com.groupironmencompanion.data.ItemStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntToLongFunction;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

/**
 * The state behind the read-only group bank window: which tab is open, what has been typed
 * into the search box, how far it is scrolled, and the laid-out rows to draw.
 * <p>
 * Nothing here can move an item. The plugin has no way to withdraw, deposit or otherwise act
 * on anything shown; it only reads item ids and quantities that your group members' own
 * clients published to your shared server.
 */
@Singleton
public class GroupBankViewer
{
	public static final String TAB_ALL = "All";
	public static final String TAB_SHARED = "Group Storage";

	/** Items per row in the grid. */
	public static final int COLUMNS = 8;

	@Inject
	private GroupState groupState;

	@Inject
	private ItemManager itemManager;

	@Inject
	private com.groupironmencompanion.GroupIronmenCompanionConfig config;

	@Getter
	private volatile boolean open;

	@Getter
	private String activeTab = TAB_ALL;

	@Getter
	private String filter = "";

	@Getter
	@Setter
	private int scrollRow;

	@Getter
	private boolean searchFocused;

	/** Bumped whenever something that affects the laid-out rows changes. */
	private volatile boolean dirty = true;

	@Getter
	private List<Row> rows = new ArrayList<>();

	@Getter
	private List<String> tabs = new ArrayList<>();

	@Getter
	private int visibleItemCount;

	/** The shown stacks' high alchemy value, before nature runes, with coins at face value. */
	@Getter
	private long visibleValue;

	/** What alching the shown stacks would make, counting only stacks that alch at a profit. */
	@Getter
	private long visibleProfit;

	// ------------------------------------------------------------------
	// Window state
	// ------------------------------------------------------------------

	public void setOpen(boolean open)
	{
		if (this.open != open)
		{
			this.open = open;
			// Focus means the search prompt is open, and opening the window does not open it.
			this.searchFocused = false;
			markDirty();
		}
	}

	public void toggle()
	{
		setOpen(!open);
	}

	public void setActiveTab(String tab)
	{
		if (!activeTab.equals(tab))
		{
			activeTab = tab;
			scrollRow = 0;
			markDirty();
		}
	}

	public void setFilter(String filter)
	{
		String next = filter == null ? "" : filter;
		if (!this.filter.equals(next))
		{
			this.filter = next;
			scrollRow = 0;
			markDirty();
		}
	}

	public void setSearchFocused(boolean focused)
	{
		this.searchFocused = focused;
	}

	public void markDirty()
	{
		dirty = true;
	}

	public void reset()
	{
		open = false;
		activeTab = TAB_ALL;
		filter = "";
		scrollRow = 0;
		searchFocused = false;
		rows = new ArrayList<>();
		tabs = new ArrayList<>();
		markDirty();
	}

	// ------------------------------------------------------------------
	// Layout
	// ------------------------------------------------------------------

	/**
	 * Recomputes the tab list and the laid-out rows if anything changed. Must run on the
	 * client thread because it reads item compositions.
	 */
	public void rebuildIfNeeded()
	{
		if (!dirty)
		{
			return;
		}
		dirty = false;

		List<GroupMember> members = groupState.getMembers();

		List<String> newTabs = new ArrayList<>();
		newTabs.add(TAB_ALL);
		for (GroupMember member : members)
		{
			newTabs.add(member.getName());
		}
		newTabs.add(TAB_SHARED);
		tabs = newTabs;

		if (!newTabs.contains(activeTab))
		{
			activeTab = TAB_ALL;
			scrollRow = 0;
		}

		List<Entry> entries = TAB_ALL.equals(activeTab)
			? aggregateEntries()
			: memberEntries(activeTab);

		rows = layout(entries);

		int count = 0;
		long value = 0;
		long profit = 0;
		for (Entry entry : entries)
		{
			if (!entry.header)
			{
				count++;
				value += stackValue(entry.itemId, alchValue(entry.itemId), entry.quantity);
				profit += stackProfit(entry.itemId, alchProfit(entry.itemId), entry.quantity);
			}
		}
		visibleItemCount = count;
		visibleValue = value;
		visibleProfit = profit;

		// The same limit the wheel and the scrollbar use. Clamping to the last row instead left
		// a shrunk list scrolled to a nearly empty page with the thumb off the end of its track.
		int maxScroll = Math.max(0, rows.size() - GroupBankOverlay.VISIBLE_ROWS);
		if (scrollRow > maxScroll)
		{
			scrollRow = maxScroll;
		}
	}

	/** Everything the whole group owns, summed per item and sorted by total value. */
	private List<Entry> aggregateEntries()
	{
		// Group storage is already part of the totals; adding it here counted it twice.
		Map<Integer, Long> totals = groupState.getGroupTotals();

		List<Entry> entries = new ArrayList<>(totals.size());
		for (Map.Entry<Integer, Long> total : totals.entrySet())
		{
			if (matches(total.getKey()))
			{
				entries.add(Entry.item(total.getKey(), total.getValue(), null));
			}
		}

		sortByValue(entries, this::alchValue);
		return entries;
	}

	/** Sorts stacks most valuable first, by {@link #stackValue}. */
	static void sortByValue(List<Entry> entries, IntToLongFunction alchEach)
	{
		// Value is looked up once per entry rather than on every comparison; a whole group's
		// bank can run to thousands of stacks.
		Map<Integer, Long> values = new HashMap<>(entries.size());
		for (Entry entry : entries)
		{
			values.put(entry.itemId, stackValue(entry.itemId, alchEach.applyAsLong(entry.itemId), entry.quantity));
		}
		entries.sort(Comparator.comparingLong((Entry e) -> values.getOrDefault(e.itemId, 0L)).reversed());
	}

	/**
	 * What a stack is worth, for sorting the All tab and for the footer's value: its high
	 * alchemy value before nature runes, with coins at face value since they alch for nothing
	 * but are plainly worth their amount.
	 * <p>
	 * Sorting by alch profit instead, as this once did, sank coins, runes and every big cheap
	 * stack below a single log, because each counted as a large loss.
	 */
	static long stackValue(int itemId, long alchEach, long quantity)
	{
		return itemId == ItemID.COINS ? quantity : alchEach * quantity;
	}

	/**
	 * What alching a stack would make after nature runes. A stack that would lose money counts
	 * as nothing rather than as a loss, since nobody alchs at a loss, and coins make nothing.
	 * The tooltip still shows a single item's loss.
	 */
	static long stackProfit(int itemId, long profitEach, long quantity)
	{
		return itemId == ItemID.COINS ? 0 : Math.max(0, profitEach) * quantity;
	}

	/** One member's holdings, split into the containers they came from. */
	private List<Entry> memberEntries(String tab)
	{
		GroupMember member = TAB_SHARED.equals(tab)
			? groupState.getSharedBank()
			: groupState.getMember(tab);

		if (member == null)
		{
			return new ArrayList<>();
		}

		Map<String, List<ItemStack>> sections = new LinkedHashMap<>();
		if (TAB_SHARED.equals(tab))
		{
			sections.put("Group storage", member.getBank());
		}
		else
		{
			// Bank goes last on purpose. It dwarfs everything else, so leading with it would
			// bury the small containers behind a long scroll.
			sections.put("Inventory", member.getInventory());
			sections.put("Equipment", member.getEquipment());
			sections.put("Rune pouch", member.getRunePouch());
			sections.put("Quiver", member.getQuiver());
			sections.put("Seed vault", member.getSeedVault());
			sections.put("Bank", member.getBank());
		}

		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<String, List<ItemStack>> section : sections.entrySet())
		{
			List<Entry> items = new ArrayList<>();
			for (ItemStack item : section.getValue())
			{
				if (!item.isEmpty() && matches(item.getId()))
				{
					items.add(Entry.item(item.getId(), item.getQuantity(), member.getName()));
				}
			}
			if (!items.isEmpty())
			{
				entries.add(Entry.header(section.getKey()));
				entries.addAll(items);
			}
		}
		return entries;
	}

	/** Packs entries into rows, with headers always taking a row of their own. */
	private List<Row> layout(List<Entry> entries)
	{
		List<Row> result = new ArrayList<>();
		List<Entry> current = new ArrayList<>();

		for (Entry entry : entries)
		{
			if (entry.header)
			{
				if (!current.isEmpty())
				{
					result.add(Row.items(current));
					current = new ArrayList<>();
				}
				result.add(Row.header(entry.label));
				continue;
			}

			current.add(entry);
			if (current.size() == COLUMNS)
			{
				result.add(Row.items(current));
				current = new ArrayList<>();
			}
		}

		if (!current.isEmpty())
		{
			result.add(Row.items(current));
		}
		return result;
	}

	private boolean matches(int itemId)
	{
		if (filter.isEmpty())
		{
			return true;
		}
		String name = itemName(itemId);
		return name != null && name.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
	}

	/**
	 * What an item alchs for. Group ironmen cannot use the Grand Exchange, so this is the
	 * number that actually means something to them.
	 */
	public long alchValue(int itemId)
	{
		try
		{
			return Math.max(0, itemManager.getItemComposition(itemId).getHaPrice());
		}
		catch (Exception e)
		{
			return 0;
		}
	}

	/**
	 * Profit from alching one of an item, after paying for the nature rune. Negative for
	 * anything that alchs for less than the rune costs, which is worth seeing rather than
	 * hiding. With the cost set to zero this is just the raw alch value.
	 */
	public long alchProfit(int itemId)
	{
		return alchValue(itemId) - Math.max(0, config.natureRuneCost());
	}

	@Nullable
	public String itemName(int itemId)
	{
		try
		{
			return itemManager.getItemComposition(itemId).getName();
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * Which members hold a given item and how many, used for the hover breakdown on the
	 * aggregate tab.
	 */
	public Map<String, Long> ownersOf(int itemId)
	{
		Map<String, Long> owners = new LinkedHashMap<>();
		for (GroupMember member : groupState.getMembers())
		{
			long quantity = 0;
			quantity += count(member.getBank(), itemId);
			quantity += count(member.getInventory(), itemId);
			quantity += count(member.getEquipment(), itemId);
			quantity += count(member.getRunePouch(), itemId);
			quantity += count(member.getSeedVault(), itemId);
			quantity += count(member.getQuiver(), itemId);
			if (quantity > 0)
			{
				owners.put(member.getName(), quantity);
			}
		}

		GroupMember shared = groupState.getSharedBank();
		if (shared != null)
		{
			long quantity = count(shared.getBank(), itemId);
			if (quantity > 0)
			{
				owners.put(TAB_SHARED, quantity);
			}
		}
		return owners;
	}

	private static long count(List<ItemStack> items, int itemId)
	{
		long total = 0;
		for (ItemStack item : items)
		{
			if (item.getId() == itemId)
			{
				total += item.getQuantity();
			}
		}
		return total;
	}

	// ------------------------------------------------------------------
	// Row and entry types
	// ------------------------------------------------------------------

	/** One item cell, or a section heading. */
	public static final class Entry
	{
		final boolean header;
		final String label;
		final int itemId;
		final long quantity;
		final String owner;

		private Entry(boolean header, String label, int itemId, long quantity, String owner)
		{
			this.header = header;
			this.label = label;
			this.itemId = itemId;
			this.quantity = quantity;
			this.owner = owner;
		}

		static Entry header(String label)
		{
			return new Entry(true, label, 0, 0, null);
		}

		static Entry item(int itemId, long quantity, String owner)
		{
			return new Entry(false, null, itemId, quantity, owner);
		}

		public int getItemId()
		{
			return itemId;
		}

		public long getQuantity()
		{
			return quantity;
		}

		@Nullable
		public String getOwner()
		{
			return owner;
		}
	}

	/** One drawn line: either a heading or a row of items. */
	public static final class Row
	{
		private final String header;
		private final List<Entry> items;

		private Row(String header, List<Entry> items)
		{
			this.header = header;
			this.items = items;
		}

		static Row header(String text)
		{
			return new Row(text, null);
		}

		static Row items(List<Entry> items)
		{
			return new Row(null, new ArrayList<>(items));
		}

		public boolean isHeader()
		{
			return header != null;
		}

		public String getHeader()
		{
			return header;
		}

		public List<Entry> getItems()
		{
			return items;
		}
	}
}
