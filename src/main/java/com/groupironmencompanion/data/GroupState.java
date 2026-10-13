package com.groupironmencompanion.data;

import com.groupironmencompanion.api.MemberDto;
import java.awt.Color;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * The client-side mirror of the group's data.
 * <p>
 * Written by the polling thread, read by the client and Swing threads. Members live in a
 * concurrent map and each member publishes its own fields through volatile references, so
 * readers always see a consistent-enough snapshot without holding a lock.
 */
@Slf4j
@Singleton
public class GroupState
{
	/** The epoch value the server expects on the very first poll. */
	private static final String EPOCH = "1970-01-01T00:00:00Z";

	private final Map<String, GroupMember> members = new ConcurrentHashMap<>();
	private final AtomicReference<String> fromTime = new AtomicReference<>(EPOCH);
	private final AtomicReference<Instant> fromInstant = new AtomicReference<>(Instant.EPOCH);
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	/**
	 * The sorted member list, rebuilt only when membership changes.
	 * <p>
	 * Four overlays ask for this every frame, and rebuilding plus sorting a list on each call
	 * was pure churn. Immutable so it can be shared safely; callers that need to reorder it
	 * take their own copy.
	 */
	private volatile List<GroupMember> sortedMembers = Collections.emptyList();

	private volatile Color[] palette = new Color[0];
	private volatile String localPlayerName;

	/**
	 * Bumped by every {@link #reset}. A poll notes it before its request goes out, so a
	 * response that was already in flight when the state was reset, for a group that is no
	 * longer configured, can be recognised and dropped instead of being merged back in.
	 */
	private volatile int generation;

	/** Seconds after which a member's interaction no longer counts as current. */
	private volatile int interactionStaleSeconds = 10;

	public void addListener(Runnable listener)
	{
		listeners.add(listener);
	}

	public void removeListener(Runnable listener)
	{
		listeners.remove(listener);
	}

	public String getFromTime()
	{
		return fromTime.get();
	}

	/** Read before a request is made, and handed back with its response to {@link #applyUpdate}. */
	public int getGeneration()
	{
		return generation;
	}

	public synchronized void reset()
	{
		generation++;
		members.clear();
		rebuildMemberList();
		fromTime.set(EPOCH);
		fromInstant.set(Instant.EPOCH);
		fireChanged();
	}

	/**
	 * Matches interaction staleness to the refresh rate. An interaction can only be as fresh
	 * as the last poll, so at a slow refresh rate a fixed 10 seconds hid every one of them
	 * the moment it arrived. Applies to interactions received from now on.
	 */
	public void setPollSeconds(int pollSeconds)
	{
		interactionStaleSeconds = Math.max(10, pollSeconds + 5);
	}

	public void setLocalPlayerName(String name)
	{
		this.localPlayerName = name;
	}

	@Nullable
	public String getLocalPlayerName()
	{
		return localPlayerName;
	}

	public boolean isLocalPlayer(GroupMember member)
	{
		String local = localPlayerName;
		return local != null && namesMatch(local, member.getName());
	}

	/**
	 * Display names arrive from the server with whatever whitespace the player's name uses,
	 * while the client reports non-breaking spaces. Compare them leniently.
	 */
	public static boolean namesMatch(String a, String b)
	{
		if (a == null || b == null)
		{
			return false;
		}
		return normalise(a).equals(normalise(b));
	}

	private static String normalise(String name)
	{
		return name.replace(' ', ' ').trim().toLowerCase(Locale.ROOT);
	}

	// ------------------------------------------------------------------
	// Reading
	// ------------------------------------------------------------------

	/**
	 * Real members, alphabetical, excluding the shared-bank pseudo member.
	 * <p>
	 * The returned list is immutable and shared. Copy it before reordering.
	 */
	public List<GroupMember> getMembers()
	{
		return sortedMembers;
	}

	private void rebuildMemberList()
	{
		List<GroupMember> result = new ArrayList<>(members.size());
		for (GroupMember member : members.values())
		{
			if (!member.isShared())
			{
				result.add(member);
			}
		}
		result.sort(Comparator.comparing(GroupMember::getName, String.CASE_INSENSITIVE_ORDER));
		sortedMembers = Collections.unmodifiableList(result);
	}

	@Nullable
	public GroupMember getSharedBank()
	{
		return members.get(GroupMember.SHARED_NAME);
	}

	@Nullable
	public GroupMember getMember(String name)
	{
		for (GroupMember member : members.values())
		{
			if (namesMatch(member.getName(), name))
			{
				return member;
			}
		}
		return null;
	}

	public boolean isEmpty()
	{
		return members.isEmpty();
	}

	/**
	 * Total quantity of every item the group owns, keyed by item id, summed over each
	 * member's bank, inventory, equipment, rune pouch, seed vault and quiver plus the
	 * shared bank. Iteration order is insertion order; callers sort as they need.
	 * <p>
	 * Group storage is one of the entries in {@code members}, so it is counted here like any
	 * other member. Callers must not add {@link #getSharedBank()} on top of this.
	 */
	public Map<Integer, Long> getGroupTotals()
	{
		Map<Integer, Long> totals = new LinkedHashMap<>();
		for (GroupMember member : members.values())
		{
			accumulate(totals, member.getBank());
			accumulate(totals, member.getInventory());
			accumulate(totals, member.getEquipment());
			accumulate(totals, member.getRunePouch());
			accumulate(totals, member.getSeedVault());
			accumulate(totals, member.getQuiver());
		}
		return totals;
	}

	private static void accumulate(Map<Integer, Long> totals, List<ItemStack> items)
	{
		for (ItemStack item : items)
		{
			if (!item.isEmpty())
			{
				totals.merge(item.getId(), (long) item.getQuantity(), Long::sum);
			}
		}
	}

	// ------------------------------------------------------------------
	// Writing
	// ------------------------------------------------------------------

	/** Merges a response to a request made under the current generation. */
	public boolean applyUpdate(MemberDto[] response)
	{
		return applyUpdate(response, generation);
	}

	/**
	 * Merges one {@code get-group-data} response and advances the poll cursor.
	 * <p>
	 * Synchronised with {@link #reset} so a reset cannot land halfway through a merge.
	 *
	 * @param requestGeneration {@link #getGeneration} as it was when the request was made; a
	 *                          response from before the last reset is ignored
	 * @return true when anything changed and the UI should redraw
	 */
	public synchronized boolean applyUpdate(MemberDto[] response, int requestGeneration)
	{
		if (response == null || requestGeneration != generation)
		{
			return false;
		}

		boolean changed = false;
		// Tracked separately from field changes: a poll can add or drop a member while every
		// field comes back null, and the member list still has to be rebuilt for that.
		boolean membershipChanged = false;
		List<String> seen = new ArrayList<>(response.length);
		Instant newest = fromInstant.get();

		for (MemberDto dto : response)
		{
			if (dto == null || dto.name == null || dto.name.isEmpty())
			{
				continue;
			}
			seen.add(dto.name);

			if (!members.containsKey(dto.name))
			{
				membershipChanged = true;
			}
			GroupMember member = members.computeIfAbsent(dto.name, GroupMember::new);
			Instant updated = parseInstant(dto.last_updated);

			// from_time is inclusive, so the newest member's latest fields come back on every
			// poll. Their update time says whether there is anything new: the response that
			// set it carried every field up to it. Merging the repeat anyway made every poll a
			// change, and every listener rebuilt the bank, panel and map each refresh.
			boolean repeat = updated != null && updated.equals(member.getLastUpdated());
			if (!repeat)
			{
				changed |= merge(member, dto);
			}

			if (updated != null)
			{
				member.setLastUpdated(updated);
				if (updated.isAfter(newest))
				{
					newest = updated;
				}
			}
		}

		// The server always returns every current member, so anyone missing has been
		// removed from the group on the website.
		if (!seen.isEmpty())
		{
			membershipChanged |= members.keySet().retainAll(seen);
		}

		// Never move the cursor backwards; a poll that returned no changes must not reset it.
		Instant previous = fromInstant.get();
		if (newest.isAfter(previous))
		{
			fromInstant.set(newest);
			fromTime.set(newest.toString());
		}

		if (membershipChanged)
		{
			rebuildMemberList();
		}
		if (changed || membershipChanged)
		{
			assignColours();
			fireChanged();
		}
		return changed || membershipChanged;
	}

	private boolean merge(GroupMember member, MemberDto dto)
	{
		boolean changed = false;

		if (dto.skills != null)
		{
			member.setSkillXp(dto.skills);
			changed = true;
		}
		if (dto.stats != null)
		{
			member.setStats(dto.stats);
			changed = true;
		}
		if (dto.coordinates != null)
		{
			member.setCoordinates(dto.coordinates);
			changed = true;
		}
		if (dto.quests != null)
		{
			member.setQuests(dto.quests);
			changed = true;
		}
		if (dto.diary_vars != null)
		{
			member.setDiaryVars(dto.diary_vars);
			changed = true;
		}
		if (dto.inventory != null)
		{
			member.setInventory(toItems(dto.inventory));
			changed = true;
		}
		if (dto.equipment != null)
		{
			member.setEquipment(toItems(dto.equipment));
			changed = true;
		}
		if (dto.bank != null)
		{
			member.setBank(toItems(dto.bank));
			changed = true;
		}
		if (dto.shared_bank != null)
		{
			// Only ever populated on the @SHARED pseudo member, but tolerate it anywhere.
			member.setBank(toItems(dto.shared_bank));
			changed = true;
		}
		if (dto.rune_pouch != null)
		{
			member.setRunePouch(toItems(dto.rune_pouch));
			changed = true;
		}
		if (dto.seed_vault != null)
		{
			member.setSeedVault(toItems(dto.seed_vault));
			changed = true;
		}
		if (dto.quiver != null)
		{
			member.setQuiver(toItems(dto.quiver));
			changed = true;
		}
		if (dto.potion_storage != null)
		{
			member.setPotionStorage(toItems(dto.potion_storage));
			changed = true;
		}
		if (dto.interacting != null)
		{
			member.setInteraction(toInteraction(dto.interacting));
			changed = true;
		}

		return changed;
	}

	private GroupMember.Interaction toInteraction(MemberDto.InteractingDto dto)
	{
		WorldPoint point = null;
		if (dto.location != null)
		{
			point = new WorldPoint(dto.location.x, dto.location.y, dto.location.plane);
		}
		Instant seen = parseInstant(dto.last_updated);
		return new GroupMember.Interaction(dto.name, dto.ratio, dto.scale, point,
			seen != null ? seen : Instant.now(), interactionStaleSeconds);
	}

	/** Expands the server's flat [id, quantity, ...] encoding. */
	public static List<ItemStack> toItems(int[] flat)
	{
		if (flat == null || flat.length < 2)
		{
			return Collections.emptyList();
		}
		List<ItemStack> items = new ArrayList<>(flat.length / 2);
		for (int i = 0; i + 1 < flat.length; i += 2)
		{
			items.add(new ItemStack(flat[i], flat[i + 1]));
		}
		return Collections.unmodifiableList(items);
	}

	@Nullable
	private static Instant parseInstant(String value)
	{
		if (value == null || value.isEmpty())
		{
			return null;
		}
		try
		{
			return Instant.parse(value);
		}
		catch (DateTimeParseException e)
		{
			log.debug("Unparseable timestamp from server: {}", value);
			return null;
		}
	}

	// ------------------------------------------------------------------
	// Colours
	// ------------------------------------------------------------------

	public void setPalette(Color[] palette)
	{
		this.palette = palette;
		assignColours();
		fireChanged();
	}

	private void assignColours()
	{
		Color[] colours = palette;
		if (colours.length == 0)
		{
			return;
		}
		List<GroupMember> sorted = getMembers();
		for (int i = 0; i < sorted.size(); i++)
		{
			sorted.get(i).setColour(colours[i % colours.length]);
		}
		GroupMember shared = getSharedBank();
		if (shared != null)
		{
			shared.setColour(Color.LIGHT_GRAY);
		}
	}

	private void fireChanged()
	{
		for (Runnable listener : listeners)
		{
			try
			{
				listener.run();
			}
			catch (Exception e)
			{
				log.warn("Group state listener failed", e);
			}
		}
	}
}
