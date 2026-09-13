package com.groupironmencompanion.ui;

import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupState;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Searches everything the group owns and shows who is holding it.
 * <p>
 * Read-only: results are drawn from item ids and quantities the group's own clients reported
 * to your shared server. Nothing here can move an item.
 */
class ItemSearchView extends JPanel
{
	private static final int MAX_RESULTS = 60;
	private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance(Locale.ENGLISH);

	private final GroupState groupState;
	private final ItemManager itemManager;
	private final ClientThread clientThread;

	/**
	 * Increments on every search. A lookup hops to the client thread and back, so two quick
	 * keystrokes can return out of order; results from anything but the latest are dropped.
	 */
	private final java.util.concurrent.atomic.AtomicLong searchGeneration = new java.util.concurrent.atomic.AtomicLong();

	private final IconTextField search = new IconTextField();
	private final JPanel results = new JPanel();
	private final JLabel emptyLabel = new JLabel();

	ItemSearchView(GroupState groupState, ItemManager itemManager, ClientThread clientThread)
	{
		this.groupState = groupState;
		this.itemManager = itemManager;
		this.clientThread = clientThread;

		setLayout(new BorderLayout(0, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		search.setIcon(IconTextField.Icon.SEARCH);
		search.setPreferredSize(new Dimension(0, 26));
		search.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		search.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		search.addActionListener(e -> refresh());
		search.addKeyListener(new java.awt.event.KeyAdapter()
		{
			@Override
			public void keyReleased(java.awt.event.KeyEvent e)
			{
				refresh();
			}
		});

		results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
		results.setBackground(ColorScheme.DARK_GRAY_COLOR);

		emptyLabel.setFont(FontManager.getRunescapeSmallFont());
		emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
		emptyLabel.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

		add(search, BorderLayout.NORTH);
		add(results, BorderLayout.CENTER);
		add(emptyLabel, BorderLayout.SOUTH);
	}

	/**
	 * Rebuilds the result list. Item names and icons can only be read on the client thread,
	 * so the lookup hops there and the rows are added back on the Swing thread.
	 */
	void refresh()
	{
		final long generation = searchGeneration.incrementAndGet();

		String query = search.getText().trim().toLowerCase(Locale.ROOT);
		if (query.length() < 2)
		{
			results.removeAll();
			emptyLabel.setText("Type at least two letters");
			revalidate();
			repaint();
			return;
		}

		// Includes group storage already, so the per-owner breakdown below adds up to it.
		Map<Integer, Long> totals = groupState.getGroupTotals();

		clientThread.invoke(() ->
		{
			if (searchGeneration.get() != generation)
			{
				return;
			}

			List<Result> matches = new ArrayList<>();
			for (Map.Entry<Integer, Long> entry : totals.entrySet())
			{
				String name;
				try
				{
					name = itemManager.getItemComposition(entry.getKey()).getName();
				}
				catch (Exception e)
				{
					continue;
				}
				if (name == null || !name.toLowerCase(Locale.ROOT).contains(query))
				{
					continue;
				}
				matches.add(new Result(entry.getKey(), name, entry.getValue()));
			}

			matches.sort(Comparator.comparingLong((Result r) -> r.quantity).reversed());
			int total = matches.size();
			List<Result> capped = new ArrayList<>(matches.subList(0, Math.min(MAX_RESULTS, total)));

			// Rows are built on the Swing thread; item images are safe to request from
			// there because AsyncBufferedImage fills itself in once the game thread has it.
			SwingUtilities.invokeLater(() ->
			{
				if (searchGeneration.get() == generation)
				{
					show(capped, total);
				}
			});
		});
	}

	private JPanel buildRow(Result match)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(4, 4, 4, 4)));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		// Quantity is part of ItemManager's 128-entry image cache key, so the plain sprite is
		// requested and the total is shown as text below instead of baked into the icon.
		AsyncBufferedImage image = itemManager.getImage(match.itemId);
		if (image != null)
		{
			image.addTo(icon);
		}

		JLabel name = new JLabel(match.name);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.TEXT_COLOR);

		JLabel quantity = new JLabel(NUMBERS.format(match.quantity) + " total");
		quantity.setFont(FontManager.getRunescapeSmallFont());
		quantity.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		text.add(name);
		text.add(quantity);

		for (Map.Entry<String, Long> owner : ownersOf(match.itemId).entrySet())
		{
			GroupMember member = groupState.getMember(owner.getKey());

			JLabel who = new JLabel(owner.getKey());
			who.setFont(FontManager.getRunescapeSmallFont());
			who.setForeground(member != null ? member.getColour() : ColorScheme.LIGHT_GRAY_COLOR);

			JLabel count = new JLabel(NUMBERS.format(owner.getValue()));
			count.setFont(FontManager.getRunescapeSmallFont());
			count.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			count.setHorizontalAlignment(SwingConstants.RIGHT);

			JPanel line = new JPanel(new GridLayout(1, 2));
			line.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			line.add(who);
			line.add(count);
			text.add(line);
		}

		row.add(icon, BorderLayout.WEST);
		row.add(text, BorderLayout.CENTER);
		return row;
	}

	private Map<String, Long> ownersOf(int itemId)
	{
		java.util.LinkedHashMap<String, Long> owners = new java.util.LinkedHashMap<>();
		for (GroupMember member : groupState.getMembers())
		{
			long quantity = 0;
			quantity += count(member, itemId);
			if (quantity > 0)
			{
				owners.put(member.getName(), quantity);
			}
		}

		GroupMember shared = groupState.getSharedBank();
		if (shared != null)
		{
			long quantity = 0;
			for (com.groupironmencompanion.data.ItemStack item : shared.getBank())
			{
				if (item.getId() == itemId)
				{
					quantity += item.getQuantity();
				}
			}
			if (quantity > 0)
			{
				owners.put("Shared", quantity);
			}
		}
		return owners;
	}

	private static long count(GroupMember member, int itemId)
	{
		long total = 0;
		total += countIn(member.getBank(), itemId);
		total += countIn(member.getInventory(), itemId);
		total += countIn(member.getEquipment(), itemId);
		total += countIn(member.getRunePouch(), itemId);
		total += countIn(member.getSeedVault(), itemId);
		total += countIn(member.getQuiver(), itemId);
		return total;
	}

	private static long countIn(List<com.groupironmencompanion.data.ItemStack> items, int itemId)
	{
		long total = 0;
		for (com.groupironmencompanion.data.ItemStack item : items)
		{
			if (item.getId() == itemId)
			{
				total += item.getQuantity();
			}
		}
		return total;
	}

	private void show(List<Result> matches, int total)
	{
		results.removeAll();
		for (Result match : matches)
		{
			results.add(buildRow(match));
		}

		if (matches.isEmpty())
		{
			emptyLabel.setText("Nothing in the group matches");
		}
		else if (total > matches.size())
		{
			emptyLabel.setText("Showing " + matches.size() + " of " + total + " matches");
		}
		else
		{
			emptyLabel.setText(" ");
		}

		revalidate();
		repaint();
	}

	private static final class Result
	{
		private final int itemId;
		private final String name;
		private final long quantity;

		Result(int itemId, String name, long quantity)
		{
			this.itemId = itemId;
			this.name = name;
			this.quantity = quantity;
		}
	}
}
