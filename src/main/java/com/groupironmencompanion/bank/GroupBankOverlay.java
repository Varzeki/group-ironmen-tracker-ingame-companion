package com.groupironmencompanion.bank;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupState;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Draws the read-only group bank window.
 * <p>
 * The window is entirely the plugin's own drawing. It is not a game interface, it never
 * modifies, hides, resizes or reparents any game widget, and it creates no menu entries. It
 * cannot move items: there is no withdraw, deposit, or any other action on anything shown,
 * because the plugin only ever has item ids and quantities read back from your group's
 * server.
 */
@Singleton
public class GroupBankOverlay extends Overlay
{
	static final int CELL_WIDTH = 40;
	static final int CELL_HEIGHT = 36;
	static final int VISIBLE_ROWS = 7;

	/** The size of an item sprite, which the game anchors stack quantities to. */
	private static final int ITEM_WIDTH = 36;
	private static final int ITEM_HEIGHT = 32;

	private static final int PADDING = 8;
	private static final int TITLE_HEIGHT = 22;
	private static final int TAB_HEIGHT = 20;
	private static final int SEARCH_HEIGHT = 20;
	private static final int FOOTER_HEIGHT = 18;
	private static final int SCROLLBAR_WIDTH = 6;

	/** Height for a window whose tab strip occupies the given number of rows. */
	private static int heightFor(int tabRows)
	{
		return TITLE_HEIGHT + tabRows * TAB_HEIGHT + SEARCH_HEIGHT
			+ VISIBLE_ROWS * CELL_HEIGHT + FOOTER_HEIGHT + PADDING;
	}

	/** Rows the tab strip needed when it was last laid out. */
	private volatile int tabRows = 1;

	static final int WIDTH =
		PADDING * 2 + GroupBankViewer.COLUMNS * CELL_WIDTH + SCROLLBAR_WIDTH;

	/**
	 * Where the game draws a stack's quantity, relative to the top-left of its 36x32 item
	 * sprite: the small font's baseline sits 9px down at the sprite's left edge.
	 */
	private static final int QUANTITY_X = 0;
	private static final int QUANTITY_BASELINE = 9;

	private static final BasicStroke BORDER_STROKE = new BasicStroke(1f);

	/** Matches the skills hover panel, which is dark enough to stay readable over the bank. */
	private static final Color TOOLTIP_BACKGROUND = new Color(22, 19, 15, 235);

	@Inject
	private Client client;

	@Inject
	private GroupBankViewer viewer;

	@Inject
	private GroupState groupState;

	@Inject
	private ItemManager itemManager;

	@Inject
	private GroupIronmenCompanionConfig config;

	@Inject
	private TooltipManager tooltipManager;

	/**
	 * Hit boxes in canvas coordinates, rebuilt on the client thread every frame and read on
	 * the AWT thread by {@link GroupBankInput}. Published as one immutable snapshot so the
	 * input handler can never observe a half-built frame or iterate a list being rewritten.
	 */
	private volatile HitBoxes hits = HitBoxes.EMPTY;

	/** Accumulates this frame's hit boxes before they are published. */
	private List<TabHit> frameTabs = new ArrayList<>();
	private Rectangle frameClose = new Rectangle();
	private Rectangle frameSearch = new Rectangle();
	private Rectangle frameGrid = new Rectangle();
	private Rectangle frameScrollbar = new Rectangle();

	@Inject
	public GroupBankOverlay()
	{
		// DYNAMIC positions the window purely from its preferred location; setMovable must
		// come after setPosition, which resets it.
		setPosition(OverlayPosition.DYNAMIC);
		setMovable(true);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	public Dimension getWindowSize()
	{
		return new Dimension(WIDTH, heightFor(tabRows));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.bankViewer() || !viewer.isOpen())
		{
			hits = HitBoxes.EMPTY;
			return null;
		}

		viewer.rebuildIfNeeded();

		java.awt.Point origin = getBounds().getLocation();
		Point mouse = client.getMouseCanvasPosition();
		java.awt.Point local = mouse == null
			? new java.awt.Point(-1, -1)
			: new java.awt.Point(mouse.getX() - origin.x, mouse.getY() - origin.y);

		frameTabs = new ArrayList<>();
		GameText.prepare(graphics);

		// Tabs are laid out before anything is drawn, because how many rows they need
		// decides how tall the window is.
		graphics.setFont(GameText.REGULAR);
		List<List<TabSlot>> layout = layoutTabs(graphics.getFontMetrics(), viewer.getTabs());
		tabRows = Math.max(1, layout.size());
		int height = heightFor(tabRows);

		drawFrame(graphics, height);
		int y = drawTitle(graphics, origin, local);
		y = drawTabs(graphics, origin, y, local, layout);
		y = drawSearch(graphics, origin, y, local);
		drawGrid(graphics, origin, y, local);
		drawFooter(graphics, height);

		hits = new HitBoxes(
			new Rectangle(origin.x, origin.y, WIDTH, height),
			frameClose, frameSearch, frameGrid, frameScrollbar, frameTabs);

		return new Dimension(WIDTH, height);
	}

	// ------------------------------------------------------------------
	// Drawing
	// ------------------------------------------------------------------

	private void drawFrame(Graphics2D graphics, int height)
	{
		graphics.setColor(BankStyle.BACKGROUND);
		graphics.fillRect(0, 0, WIDTH, height);
		graphics.setColor(BankStyle.BORDER);
		graphics.setStroke(BORDER_STROKE);
		graphics.drawRect(0, 0, WIDTH - 1, height - 1);
	}

	private int drawTitle(Graphics2D graphics, java.awt.Point origin, java.awt.Point local)
	{
		graphics.setColor(BankStyle.TITLE_BACKGROUND);
		graphics.fillRect(1, 1, WIDTH - 2, TITLE_HEIGHT - 1);

		graphics.setFont(GameText.BOLD);
		GameText.drawShadowed(graphics, "Group Bank (view only)", PADDING, 16, GameText.ORANGE);

		Rectangle close = new Rectangle(WIDTH - 20, 4, 14, 14);
		boolean hovered = close.contains(local);
		GameText.drawShadowed(graphics, "x", close.x + 4, close.y + 12,
			hovered ? GameText.HOVER : GameText.ORANGE);

		frameClose = toCanvas(close, origin);
		return TITLE_HEIGHT;
	}

	/**
	 * Arranges the tabs into rows that fit the window's width.
	 * <p>
	 * They used to be drawn on a single strip, and anything past the right edge was simply
	 * dropped. With a full group that silently swallowed the last tab, which is how the
	 * group storage tab became unreachable. Wrapping keeps every tab clickable.
	 */
	private List<List<TabSlot>> layoutTabs(FontMetrics metrics, List<String> tabs)
	{
		List<List<TabSlot>> rows = new ArrayList<>();
		if (tabs.isEmpty())
		{
			return rows;
		}

		int available = WIDTH - PADDING * 2;
		List<TabSlot> row = new ArrayList<>();
		int x = PADDING;

		for (String tab : tabs)
		{
			String label = abbreviate(tab, metrics, 96);
			int width = Math.min(available, metrics.stringWidth(label) + 12);

			if (x > PADDING && x + width > available + PADDING)
			{
				rows.add(row);
				row = new ArrayList<>();
				x = PADDING;
			}

			row.add(new TabSlot(tab, label, x, width));
			x += width + 2;
		}

		if (!row.isEmpty())
		{
			rows.add(row);
		}
		return rows;
	}

	private int drawTabs(Graphics2D graphics, java.awt.Point origin, int top,
		java.awt.Point local, List<List<TabSlot>> layout)
	{
		graphics.setFont(GameText.REGULAR);

		int y = top;
		for (List<TabSlot> row : layout)
		{
			for (TabSlot slot : row)
			{
				Rectangle rect = new Rectangle(slot.x, y + 2, slot.width, TAB_HEIGHT - 4);
				boolean active = slot.tab.equals(viewer.getActiveTab());

				graphics.setColor(active ? BankStyle.TAB_ACTIVE : BankStyle.TAB_IDLE);
				graphics.fillRect(rect.x, rect.y, rect.width, rect.height);

				if (rect.contains(local) && !active)
				{
					graphics.setColor(BankStyle.HOVER);
					graphics.fillRect(rect.x, rect.y, rect.width, rect.height);
				}

				boolean lit = active || rect.contains(local);
				GameText.drawShadowed(graphics, slot.label, rect.x + 6, rect.y + rect.height - 3,
					lit ? GameText.HOVER : GameText.ORANGE);

				frameTabs.add(new TabHit(slot.tab, toCanvas(rect, origin)));
			}
			y += TAB_HEIGHT;
		}

		return Math.max(y, top + TAB_HEIGHT);
	}

	/** One tab's place on its row, worked out before anything is drawn. */
	private static final class TabSlot
	{
		private final String tab;
		private final String label;
		private final int x;
		private final int width;

		TabSlot(String tab, String label, int x, int width)
		{
			this.tab = tab;
			this.label = label;
			this.x = x;
			this.width = width;
		}
	}

	private int drawSearch(Graphics2D graphics, java.awt.Point origin, int top, java.awt.Point local)
	{
		Rectangle rect = new Rectangle(PADDING, top + 1, WIDTH - PADDING * 2, SEARCH_HEIGHT - 4);

		graphics.setColor(BankStyle.SEARCH_BACKGROUND);
		graphics.fillRect(rect.x, rect.y, rect.width, rect.height);
		graphics.setColor(viewer.isSearchFocused() ? BankStyle.TEXT : BankStyle.BORDER);
		graphics.drawRect(rect.x, rect.y, rect.width, rect.height);

		graphics.setFont(GameText.REGULAR);
		String filter = viewer.getFilter();
		String text;
		Color colour;
		if (!filter.isEmpty())
		{
			text = filter + (viewer.isSearchFocused() && blink() ? "|" : "");
			colour = Color.WHITE;
		}
		else if (viewer.isSearchFocused())
		{
			text = blink() ? "|" : "";
			colour = Color.WHITE;
		}
		else
		{
			text = "Click here to search";
			colour = rect.contains(local) ? GameText.HOVER : GameText.ORANGE;
		}

		GameText.drawShadowed(graphics, text, rect.x + 5, rect.y + rect.height - 3, colour);

		frameSearch = toCanvas(rect, origin);
		return top + SEARCH_HEIGHT;
	}

	private void drawGrid(Graphics2D graphics, java.awt.Point origin, int top, java.awt.Point local)
	{
		Rectangle area = new Rectangle(PADDING, top, GroupBankViewer.COLUMNS * CELL_WIDTH,
			VISIBLE_ROWS * CELL_HEIGHT);
		frameGrid = toCanvas(area, origin);

		List<GroupBankViewer.Row> rows = viewer.getRows();
		if (rows.isEmpty())
		{
			graphics.setFont(GameText.REGULAR);
			String message = viewer.getFilter().isEmpty()
				? "No data yet. Check your group name and token."
				: "Nothing matches \"" + viewer.getFilter() + "\"";
			GameText.drawShadowed(graphics, message, area.x + 4, area.y + 18, GameText.ORANGE);
			return;
		}

		java.awt.Shape clip = graphics.getClip();
		graphics.clipRect(area.x, area.y, area.width, area.height);

		int first = Math.min(viewer.getScrollRow(), Math.max(0, rows.size() - 1));
		int y = area.y;

		for (int index = first; index < rows.size() && y < area.y + area.height; index++)
		{
			GroupBankViewer.Row row = rows.get(index);

			if (row.isHeader())
			{
				graphics.setFont(GameText.REGULAR);
				GameText.drawShadowed(graphics, row.getHeader(), area.x + 2, y + 15, GameText.ORANGE);
				graphics.setColor(BankStyle.BORDER);
				graphics.drawLine(area.x + 2, y + 19, area.x + area.width - 4, y + 19);
				y += CELL_HEIGHT;
				continue;
			}

			int x = area.x;
			for (GroupBankViewer.Entry entry : row.getItems())
			{
				drawCell(graphics, entry, x, y, local);
				x += CELL_WIDTH;
			}
			y += CELL_HEIGHT;
		}

		graphics.setClip(clip);
		drawScrollbar(graphics, origin, area, rows.size(), first);
	}

	private void drawCell(Graphics2D graphics, GroupBankViewer.Entry entry, int x, int y, java.awt.Point local)
	{
		Rectangle cell = new Rectangle(x, y, CELL_WIDTH, CELL_HEIGHT);
		boolean hovered = cell.contains(local);

		if (hovered)
		{
			graphics.setColor(BankStyle.HOVER);
			graphics.fillRect(cell.x, cell.y, cell.width, cell.height);
		}

		long quantity = entry.getQuantity();

		// Always request the plain, quantity-1 sprite. Quantity is part of ItemManager's
		// image cache key and that cache holds only 128 entries, so asking for group-wide
		// totals would evict and re-render sprites every frame. The stack text is drawn
		// below instead, which also lets totals exceed what the game's own text renders.
		BufferedImage image = itemManager.getImage(entry.getItemId());
		if (image != null)
		{
			graphics.drawImage(image, x + (CELL_WIDTH - image.getWidth()) / 2,
				y + (CELL_HEIGHT - image.getHeight()) / 2, null);
		}

		if (quantity > 1)
		{
			// Anchored to where the item sprite sits, the way the game anchors it, rather
			// than to the cell, so it stays put whatever the cell's padding.
			int imageX = x + (CELL_WIDTH - ITEM_WIDTH) / 2;
			int imageY = y + (CELL_HEIGHT - ITEM_HEIGHT) / 2;
			graphics.setFont(GameText.SMALL);
			GameText.drawShadowed(graphics, BankStyle.shortQuantity(quantity),
				imageX + QUANTITY_X, imageY + QUANTITY_BASELINE, BankStyle.quantityColour(quantity));
		}

		if (hovered)
		{
			addTooltip(graphics, entry);
		}
	}

	private void drawScrollbar(Graphics2D graphics, java.awt.Point origin, Rectangle area,
		int totalRows, int firstRow)
	{
		if (totalRows <= VISIBLE_ROWS)
		{
			frameScrollbar = new Rectangle();
			return;
		}

		int x = area.x + area.width + 1;
		frameScrollbar = toCanvas(new Rectangle(x, area.y, SCROLLBAR_WIDTH - 2, area.height), origin);
		graphics.setColor(BankStyle.SEARCH_BACKGROUND);
		graphics.fillRect(x, area.y, SCROLLBAR_WIDTH - 2, area.height);

		int thumbHeight = Math.max(12, area.height * VISIBLE_ROWS / totalRows);
		int travel = area.height - thumbHeight;
		int maxScroll = Math.max(1, totalRows - VISIBLE_ROWS);
		int thumbY = area.y + (int) ((long) travel * Math.min(firstRow, maxScroll) / maxScroll);

		graphics.setColor(BankStyle.BORDER);
		graphics.fillRect(x, thumbY, SCROLLBAR_WIDTH - 2, thumbHeight);
	}

	private void drawFooter(Graphics2D graphics, int height)
	{
		graphics.setFont(GameText.SMALL);

		StringBuilder text = new StringBuilder();
		text.append(viewer.getVisibleItemCount()).append(" stacks");
		if (config.bankShowPrices())
		{
			text.append("  ·  ").append(BankStyle.gp(viewer.getVisibleValue()))
				.append(config.natureRuneCost() > 0 ? " alch profit" : " alch");
		}
		GameText.drawShadowed(graphics, text.toString(), PADDING, height - 6, GameText.ORANGE);
	}

	// ------------------------------------------------------------------
	// Tooltip
	// ------------------------------------------------------------------

	private void addTooltip(Graphics2D graphics, GroupBankViewer.Entry entry)
	{
		String name = viewer.itemName(entry.getItemId());
		if (name == null)
		{
			return;
		}

		PanelComponent panel = new PanelComponent();
		panel.setPreferredSize(new Dimension(190, 0));
		panel.setBackgroundColor(TOOLTIP_BACKGROUND);
		panel.getChildren().add(TitleComponent.builder().text(name).color(Color.WHITE).build());

		panel.getChildren().add(LineComponent.builder()
			.left("Quantity")
			.leftColor(BankStyle.TEXT_DIM)
			.right(BankStyle.commas(entry.getQuantity()))
			.rightColor(BankStyle.quantityColour(entry.getQuantity()))
			.build());

		if (config.bankShowPrices())
		{
			long each = viewer.alchValue(entry.getItemId());
			panel.getChildren().add(LineComponent.builder()
				.left("Alchs for")
				.leftColor(BankStyle.TEXT_DIM)
				.right(BankStyle.commas(each) + " ea")
				.rightColor(BankStyle.TEXT_DIM)
				.build());

			long profit = viewer.alchProfit(entry.getItemId()) * entry.getQuantity();
			panel.getChildren().add(LineComponent.builder()
				.left(config.natureRuneCost() > 0 ? "Alch profit" : "Alch value")
				.leftColor(BankStyle.TEXT_DIM)
				.right(BankStyle.gp(profit) + " gp")
				.rightColor(profit < 0 ? BankStyle.QUANTITY_LOW : BankStyle.TEXT_DIM)
				.build());
		}

		if (GroupBankViewer.TAB_ALL.equals(viewer.getActiveTab()))
		{
			Map<String, Long> owners = viewer.ownersOf(entry.getItemId());
			for (Map.Entry<String, Long> owner : owners.entrySet())
			{
				GroupMember member = groupState.getMember(owner.getKey());
				panel.getChildren().add(LineComponent.builder()
					.left(owner.getKey())
					.leftColor(member != null ? member.getColour() : BankStyle.TEXT_DIM)
					.right(BankStyle.commas(owner.getValue()))
					.rightColor(BankStyle.TEXT_DIM)
					.build());
			}
		}

		// PanelComponent sizes its background from the child dimensions measured during the
		// previous render, and this panel is built fresh every frame, so on its only render
		// that size is zero and the background never appears. Rendering once against a
		// scratch context clipped to nothing measures the children without drawing anything,
		// leaving the real render with a correctly sized background.
		Graphics2D scratch = (Graphics2D) graphics.create();
		try
		{
			scratch.setClip(0, 0, 0, 0);
			panel.render(scratch);
		}
		finally
		{
			scratch.dispose();
		}

		tooltipManager.add(new Tooltip(new TintedTooltip(panel)));
	}

	// ------------------------------------------------------------------
	// Hit testing, used by GroupBankInput
	// ------------------------------------------------------------------

	/**
	 * The scroll row a click at this height on the scrollbar corresponds to.
	 * <p>
	 * The thumb is centred on the pointer, so grabbing it anywhere and dragging tracks the
	 * mouse instead of jumping. Kept beside {@link #drawScrollbar} because the two share the
	 * same thumb geometry.
	 */
	int scrollRowForY(int canvasY)
	{
		Rectangle track = hits.scrollbar;
		int totalRows = viewer.getRows().size();
		int maxScroll = totalRows - VISIBLE_ROWS;
		if (track.height <= 0 || maxScroll <= 0)
		{
			return 0;
		}

		int thumbHeight = Math.max(12, track.height * VISIBLE_ROWS / totalRows);
		int travel = Math.max(1, track.height - thumbHeight);
		int offset = canvasY - track.y - thumbHeight / 2;

		long row = Math.round((double) offset / travel * maxScroll);
		return (int) Math.max(0, Math.min(maxScroll, row));
	}

	/** The most recently drawn frame's hit boxes. Never null. */
	HitBoxes getHits()
	{
		return hits;
	}

	/**
	 * One frame's clickable regions, in canvas coordinates. Immutable once constructed so it
	 * can be handed to the AWT thread without locking.
	 */
	static final class HitBoxes
	{
		static final HitBoxes EMPTY = new HitBoxes(
			new Rectangle(), new Rectangle(), new Rectangle(), new Rectangle(), new Rectangle(),
			java.util.Collections.emptyList());

		final Rectangle window;
		final Rectangle close;
		final Rectangle search;
		final Rectangle grid;
		final Rectangle scrollbar;
		final List<TabHit> tabs;

		HitBoxes(Rectangle window, Rectangle close, Rectangle search, Rectangle grid,
			Rectangle scrollbar, List<TabHit> tabs)
		{
			this.window = window;
			this.close = close;
			this.search = search;
			this.grid = grid;
			this.scrollbar = scrollbar;
			this.tabs = java.util.Collections.unmodifiableList(tabs);
		}
	}

	private static Rectangle toCanvas(Rectangle local, java.awt.Point origin)
	{
		return new Rectangle(local.x + origin.x, local.y + origin.y, local.width, local.height);
	}

	private static String abbreviate(String text, FontMetrics metrics, int maxWidth)
	{
		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		String result = text;
		while (result.length() > 1 && metrics.stringWidth(result + "…") > maxWidth)
		{
			result = result.substring(0, result.length() - 1);
		}
		return result + "…";
	}

	private static boolean blink()
	{
		return (System.currentTimeMillis() / 500) % 2 == 0;
	}

	static final class TabHit
	{
		final String tab;
		final Rectangle bounds;

		TabHit(String tab, Rectangle bounds)
		{
			this.tab = tab;
			this.bounds = bounds;
		}
	}
}
