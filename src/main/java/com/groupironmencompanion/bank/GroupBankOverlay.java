package com.groupironmencompanion.bank;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupState;
import com.groupironmencompanion.overlay.PanelBackground;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.SpriteID;
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
 * <p>
 * It is drawn from the bank's own sprites, laid out the way the bank lays them out, so it
 * reads as a sibling of the bank beside it. Every control also has a flat fallback with the
 * same geometry, used if the sprites cannot be loaded.
 */
@Singleton
public class GroupBankOverlay extends Overlay
{
	/**
	 * The real bank's item pitch: 36x32 sprites, 48 apart across and 36 down. Matching it
	 * makes the two windows read as the same thing when they sit side by side, at the cost of
	 * a window about 80px wider than the 40px pitch it replaced.
	 */
	static final int CELL_WIDTH = 48;
	static final int CELL_HEIGHT = 36;
	static final int VISIBLE_ROWS = 7;

	/** The size of an item sprite, which the game anchors stack quantities to. */
	private static final int ITEM_WIDTH = 36;
	private static final int ITEM_HEIGHT = 32;

	// The frame is the bank's own steelborder, and these place its pieces exactly as the bank
	// interface does (Bankmain.FRAME), so a window drawn with them lines up with the real one.

	/** How thick the visible border is on every side. */
	private static final int FRAME_EDGE = 6;

	/**
	 * The edge and divider sprites are 6px strips placed 15px into a 36px box, so the box is
	 * drawn 15px outside the window for the strip to land on its edge.
	 */
	private static final int EDGE_BOX = 36;
	private static final int EDGE_LEAD = 15;

	/** The divider under the title: its box starts here and is clipped to this height. */
	private static final int DIVIDER_TOP = 14;
	private static final int DIVIDER_BOX_HEIGHT = 26;

	/** The title text's box; the text is centred in it. */
	private static final int TITLE_TOP = 6;
	private static final int TITLE_HEIGHT = 24;

	/** Where the window's content starts, just below the title divider. */
	private static final int CONTENT_TOP = 35;

	/** The close button's box, measured from the window's top-right corner. */
	private static final int CLOSE_RIGHT = 29;
	private static final int CLOSE_TOP = 6;
	private static final int CLOSE_WIDTH = 26;
	private static final int CLOSE_HEIGHT = 23;

	/** Corner sizes, used only if a corner sprite somehow reports none. */
	private static final int CORNER_WIDTH = 25;
	private static final int CORNER_HEIGHT = 30;

	/** Space between the window's outer edge and the content, on the left and right. */
	private static final int PADDING = 8;

	// The tab strip. "All" and "Group Storage" are the bank's own 41x40 tab tiles with an
	// icon; member tabs carry a name, so they are the game's short text tabs, which stretch.

	/** A bank tab tile, and the space left between neighbouring tiles. */
	private static final int TILE_WIDTH = 41;
	private static final int TILE_HEIGHT = 40;
	private static final int TILE_GAP = 0;

	/** Space between the two tiles and the first member tab. */
	private static final int MEMBER_TABS_GAP = 4;

	/** A member tab's height (the sprite's own), its text padding and its widest label. */
	private static final int MEMBER_TAB_HEIGHT = 21;
	private static final int MEMBER_TAB_PADDING = 8;
	private static final int MEMBER_TAB_GAP = 1;
	private static final int MEMBER_LABEL_MAX_WIDTH = 96;

	/** The end piece of a member tab, used only if its sprite somehow reports no width. */
	private static final int MEMBER_TAB_CAP = 20;

	/** The search box below the tab strip, and the gap either side of it. */
	private static final int SEARCH_GAP = 3;
	private static final int SEARCH_HEIGHT = 22;
	private static final int SEARCH_TEXT_X = 7;

	/** Space between the search box and the first row of items. */
	private static final int GRID_GAP = 3;

	private static final int FOOTER_HEIGHT = 18;

	/** The bank's scrollbar: 16px wide, with 16x16 arrows and 5px thumb end caps. */
	private static final int SCROLLBAR_WIDTH = 16;
	private static final int SCROLLBAR_GAP = 4;
	private static final int SCROLL_ARROW_HEIGHT = 16;
	private static final int SCROLL_THUMB_END = 5;
	private static final int MIN_THUMB_HEIGHT = 12;

	/** Every sprite the window needs. If any is missing it is drawn flat instead. */
	private static final int[] SPRITES = {
		SpriteID.TRADEBACKING, SpriteID.TRADEBACKING_DARK,
		SpriteID.Steelborder.TOP_LEFT, SpriteID.Steelborder.TOP_RIGHT,
		SpriteID.Steelborder.BOTTOM_LEFT, SpriteID.Steelborder.BOTTOM_RIGHT,
		SpriteID.Steelborder2.EDGE_TOP, SpriteID.Steelborder2.EDGE_RIGHT,
		SpriteID.Miscgraphics.IRON_RIVETS_HORIZONTAL, SpriteID.Miscgraphics.IRON_RIVETS_VERTICAL,
		SpriteID.SteelborderDivider._0,
		SpriteID.CloseButtons.BUTTON, SpriteID.CloseButtons.HOVERED,
		SpriteID.Banktabs.TAB, SpriteID.Banktabs.HOVERED, SpriteID.Banktabs.SELECTED,
		SpriteID.BanktabIcons.ALL_ITEMS, SpriteID.BankStorage._4,
		SpriteID.TabsShort._0, SpriteID.TabsShort._1, SpriteID.TabsShort._2,
		SpriteID.TabsShort._3, SpriteID.TabsShort._4, SpriteID.TabsShort._5,
		SpriteID.V2StoneButton.TOP_LEFT, SpriteID.V2StoneButton.TOP_RIGHT,
		SpriteID.V2StoneButton.BOTTOM_LEFT, SpriteID.V2StoneButton.BOTTOM_RIGHT,
		SpriteID.V2StoneButton.LEFT, SpriteID.V2StoneButton.TOP,
		SpriteID.V2StoneButton.RIGHT, SpriteID.V2StoneButton.BOTTOM,
		SpriteID.ScrollbarV2.ARROW_UP, SpriteID.ScrollbarV2.ARROW_DOWN,
		SpriteID.ScrollbarDraggerV2.TOP, SpriteID.ScrollbarDraggerV2.MIDDLE,
		SpriteID.ScrollbarDraggerV2.BOTTOM, SpriteID.ScrollbarDraggerV2.TRACK,
	};

	/**
	 * The short text tab's pieces, as (end, middle) pairs per state. The game builds the
	 * right-hand end by mirroring the left one.
	 */
	private static final int MEMBER_TAB_ACTIVE_END = SpriteID.TabsShort._0;
	private static final int MEMBER_TAB_ACTIVE_MIDDLE = SpriteID.TabsShort._1;
	private static final int MEMBER_TAB_IDLE_END = SpriteID.TabsShort._2;
	private static final int MEMBER_TAB_IDLE_MIDDLE = SpriteID.TabsShort._3;
	private static final int MEMBER_TAB_HOVER_END = SpriteID.TabsShort._4;
	private static final int MEMBER_TAB_HOVER_MIDDLE = SpriteID.TabsShort._5;

	private static final String TITLE = "Group Bank (view only)";
	private static final String SEARCH_PROMPT = "Click here to search";

	/**
	 * Where the game draws a stack's quantity, relative to the top-left of its 36x32 item
	 * sprite: the small font's baseline sits 9px down at the sprite's left edge.
	 */
	private static final int QUANTITY_X = 0;
	private static final int QUANTITY_BASELINE = 9;

	private static final BasicStroke BORDER_STROKE = new BasicStroke(1f);

	/**
	 * Matches the skills hover panel, which is dark enough to stay readable over the bank.
	 * Used as is when not matching the interface theme, and as the minimum opacity when it is.
	 */
	private static final Color TOOLTIP_BACKGROUND = new Color(22, 19, 15, 235);

	/** Height for a window whose tab strip is this tall. */
	private static int heightFor(int stripHeight)
	{
		return CONTENT_TOP + stripHeight + SEARCH_GAP + SEARCH_HEIGHT + GRID_GAP
			+ VISIBLE_ROWS * CELL_HEIGHT + FOOTER_HEIGHT + FRAME_EDGE;
	}

	static final int WIDTH =
		PADDING * 2 + GroupBankViewer.COLUMNS * CELL_WIDTH + SCROLLBAR_GAP + SCROLLBAR_WIDTH;

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

	@Inject
	private GameSprites sprites;

	@Inject
	private PanelBackground panelBackground;

	/**
	 * The frame composed into one image, since it is a few hundred sprite draws and only
	 * changes when the window's size or the sprites themselves do.
	 */
	private BufferedImage frameImage;
	private int frameImageVersion = -1;

	/**
	 * The tab strip as last laid out, and the tab list it was laid out for. The viewer
	 * replaces its list whenever the tabs change, so comparing identity is enough to know
	 * when to lay them out again.
	 */
	private TabLayout tabLayout = TabLayout.EMPTY;
	private List<String> tabLayoutSource;

	/** How tall the tab strip was when it was last laid out, which sets the window's height. */
	private volatile int stripHeight = TILE_HEIGHT;

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
	private Rectangle frameScrollUp = new Rectangle();
	private Rectangle frameScrollDown = new Rectangle();

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
		return new Dimension(WIDTH, heightFor(stripHeight));
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
		sprites.refresh();

		// Tabs are laid out before anything is drawn, because how many rows they need
		// decides how tall the window is.
		List<String> tabs = viewer.getTabs();
		if (tabs != tabLayoutSource)
		{
			graphics.setFont(GameText.REGULAR);
			tabLayout = layoutTabs(graphics.getFontMetrics(), tabs);
			tabLayoutSource = tabs;
		}
		stripHeight = tabLayout.height;
		int height = heightFor(tabLayout.height);

		boolean skinned = sprites.hasAll(SPRITES);
		drawFrame(graphics, height, skinned);
		int y = drawTitle(graphics, origin, local, skinned);
		y = drawTabs(graphics, origin, y, local, skinned);
		y = drawSearch(graphics, origin, y, local, skinned);
		drawGrid(graphics, origin, y, local, skinned);
		drawFooter(graphics, height);

		hits = new HitBoxes(
			new Rectangle(origin.x, origin.y, WIDTH, height),
			frameClose, frameSearch, frameGrid, frameScrollbar, frameScrollUp, frameScrollDown,
			frameTabs);

		return new Dimension(WIDTH, height);
	}

	// ------------------------------------------------------------------
	// Frame and title
	// ------------------------------------------------------------------

	/**
	 * Draws the bank's steelborder frame, or a flat stand-in when its sprites are not
	 * available. Both have the same geometry, so nothing else changes between them.
	 */
	private void drawFrame(Graphics2D graphics, int height, boolean skinned)
	{
		if (skinned)
		{
			graphics.drawImage(frameImage(WIDTH, height), 0, 0, null);
			return;
		}

		graphics.setColor(BankStyle.BACKGROUND);
		graphics.fillRect(0, 0, WIDTH, height);
		graphics.setColor(BankStyle.TITLE_BACKGROUND);
		graphics.fillRect(1, 1, WIDTH - 2, CONTENT_TOP - 1);
		graphics.setColor(BankStyle.BORDER);
		graphics.setStroke(BORDER_STROKE);
		graphics.drawRect(0, 0, WIDTH - 1, height - 1);
		graphics.drawLine(1, CONTENT_TOP - 1, WIDTH - 2, CONTENT_TOP - 1);
	}

	private BufferedImage frameImage(int width, int height)
	{
		if (frameImage == null || frameImage.getWidth() != width || frameImage.getHeight() != height
			|| frameImageVersion != sprites.getVersion())
		{
			frameImage = composeFrame(width, height);
			frameImageVersion = sprites.getVersion();
		}
		return frameImage;
	}

	/** Lays out the steelborder pieces the way the bank interface positions them. */
	private BufferedImage composeFrame(int width, int height)
	{
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			int cornerWidth = sprites.widthOf(SpriteID.Steelborder.TOP_LEFT, CORNER_WIDTH);
			int cornerHeight = sprites.heightOf(SpriteID.Steelborder.TOP_LEFT, CORNER_HEIGHT);

			sprites.tile(g, SpriteID.TRADEBACKING, 0, 0, width, height);
			sprites.tile(g, SpriteID.SteelborderDivider._0,
				FRAME_EDGE, DIVIDER_TOP, width - FRAME_EDGE * 2, DIVIDER_BOX_HEIGHT);

			sprites.tile(g, SpriteID.Steelborder2.EDGE_TOP,
				cornerWidth, -EDGE_LEAD, width - cornerWidth * 2, EDGE_BOX);
			sprites.tile(g, SpriteID.Miscgraphics.IRON_RIVETS_HORIZONTAL,
				cornerWidth, height - FRAME_EDGE - EDGE_LEAD, width - cornerWidth * 2, EDGE_BOX);
			sprites.tile(g, SpriteID.Miscgraphics.IRON_RIVETS_VERTICAL,
				-EDGE_LEAD, cornerHeight, EDGE_BOX, height - cornerHeight * 2);
			sprites.tile(g, SpriteID.Steelborder2.EDGE_RIGHT,
				width - FRAME_EDGE - EDGE_LEAD, cornerHeight, EDGE_BOX, height - cornerHeight * 2);

			sprites.draw(g, SpriteID.Steelborder.TOP_LEFT, 0, 0);
			sprites.draw(g, SpriteID.Steelborder.TOP_RIGHT,
				width - sprites.widthOf(SpriteID.Steelborder.TOP_RIGHT, CORNER_WIDTH), 0);
			sprites.draw(g, SpriteID.Steelborder.BOTTOM_LEFT,
				0, height - sprites.heightOf(SpriteID.Steelborder.BOTTOM_LEFT, CORNER_HEIGHT));
			sprites.draw(g, SpriteID.Steelborder.BOTTOM_RIGHT,
				width - sprites.widthOf(SpriteID.Steelborder.BOTTOM_RIGHT, CORNER_WIDTH),
				height - sprites.heightOf(SpriteID.Steelborder.BOTTOM_RIGHT, CORNER_HEIGHT));
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	private int drawTitle(Graphics2D graphics, java.awt.Point origin, java.awt.Point local, boolean skinned)
	{
		// Centred across the whole window, as the bank centres its own title.
		graphics.setFont(GameText.BOLD);
		FontMetrics metrics = graphics.getFontMetrics();
		GameText.drawShadowed(graphics, TITLE, (WIDTH - metrics.stringWidth(TITLE)) / 2,
			GameText.centredBaseline(metrics, TITLE_TOP, TITLE_HEIGHT), GameText.ORANGE);

		Rectangle close = new Rectangle(WIDTH - CLOSE_RIGHT, CLOSE_TOP, CLOSE_WIDTH, CLOSE_HEIGHT);
		boolean hovered = close.contains(local);
		if (skinned)
		{
			sprites.draw(graphics, hovered ? SpriteID.CloseButtons.HOVERED : SpriteID.CloseButtons.BUTTON,
				close.x, close.y);
		}
		else
		{
			GameText.drawShadowed(graphics, "x", close.x + 9, close.y + 16,
				hovered ? GameText.HOVER : GameText.ORANGE);
		}

		frameClose = toCanvas(close, origin);
		return CONTENT_TOP;
	}

	// ------------------------------------------------------------------
	// Tabs
	// ------------------------------------------------------------------

	/**
	 * Arranges the tab strip: the All and Group Storage tiles first, then a tab per member,
	 * wrapping onto further rows when the names do not fit.
	 * <p>
	 * Tabs used to be drawn on a single strip, and anything past the right edge was simply
	 * dropped. With a full group that silently swallowed the last tab, which is how the
	 * group storage tab became unreachable. Wrapping keeps every tab clickable.
	 */
	private TabLayout layoutTabs(FontMetrics metrics, List<String> tabs)
	{
		List<TabSlot> slots = new ArrayList<>();
		if (tabs.isEmpty())
		{
			return TabLayout.EMPTY;
		}

		int tileX = PADDING;
		for (String tab : tabs)
		{
			if (isTileTab(tab))
			{
				slots.add(new TabSlot(tab, tab, true, new Rectangle(tileX, CONTENT_TOP, TILE_WIDTH, TILE_HEIGHT)));
				tileX += TILE_WIDTH + TILE_GAP;
			}
		}

		int left = tileX - TILE_GAP + MEMBER_TABS_GAP;
		int right = WIDTH - PADDING;
		int x = left;
		int row = 0;
		for (String tab : tabs)
		{
			if (isTileTab(tab))
			{
				continue;
			}

			String label = abbreviate(tab, metrics, MEMBER_LABEL_MAX_WIDTH);
			int width = Math.min(right - left, metrics.stringWidth(label) + MEMBER_TAB_PADDING * 2);
			if (x > left && x + width > right)
			{
				row++;
				x = left;
			}

			slots.add(new TabSlot(tab, label, false,
				new Rectangle(x, CONTENT_TOP + row * MEMBER_TAB_HEIGHT, width, MEMBER_TAB_HEIGHT)));
			x += width + MEMBER_TAB_GAP;
		}

		boolean anyMembers = slots.stream().anyMatch(slot -> !slot.tile);
		int membersHeight = anyMembers ? (row + 1) * MEMBER_TAB_HEIGHT : 0;
		return new TabLayout(Collections.unmodifiableList(slots), Math.max(TILE_HEIGHT, membersHeight));
	}

	private static boolean isTileTab(String tab)
	{
		return GroupBankViewer.TAB_ALL.equals(tab) || GroupBankViewer.TAB_SHARED.equals(tab);
	}

	private int drawTabs(Graphics2D graphics, java.awt.Point origin, int top, java.awt.Point local, boolean skinned)
	{
		graphics.setFont(GameText.REGULAR);
		FontMetrics metrics = graphics.getFontMetrics();

		for (TabSlot slot : tabLayout.slots)
		{
			Rectangle box = slot.box;
			boolean active = slot.tab.equals(viewer.getActiveTab());
			boolean hovered = box.contains(local);

			if (slot.tile)
			{
				drawTile(graphics, slot, active, hovered, skinned);
			}
			else
			{
				drawMemberTab(graphics, metrics, slot, active, hovered, skinned);
			}

			frameTabs.add(new TabHit(slot.tab, toCanvas(box, origin)));
		}

		// The line closing off the strip, as the bank draws under its own tabs.
		int bottom = top + tabLayout.height;
		graphics.setColor(sprites.getLineColour());
		graphics.drawLine(FRAME_EDGE, bottom - 1, WIDTH - FRAME_EDGE - 1, bottom - 1);
		return bottom;
	}

	/**
	 * One of the bank's own tab tiles, with its icon. Icons carry no text, so hovering one
	 * names it, as hovering a bank tab does.
	 */
	private void drawTile(Graphics2D graphics, TabSlot slot, boolean active, boolean hovered, boolean skinned)
	{
		Rectangle box = slot.box;
		boolean all = GroupBankViewer.TAB_ALL.equals(slot.tab);

		if (skinned)
		{
			int tile = active ? SpriteID.Banktabs.SELECTED
				: hovered ? SpriteID.Banktabs.HOVERED
				: SpriteID.Banktabs.TAB;
			sprites.draw(graphics, tile, box.x, box.y);
			sprites.drawCentred(graphics, all ? SpriteID.BanktabIcons.ALL_ITEMS : SpriteID.BankStorage._4,
				box.x, box.y, box.width, box.height);
		}
		else
		{
			drawFlatTab(graphics, box, active, hovered);
			graphics.setFont(GameText.SMALL);
			FontMetrics small = graphics.getFontMetrics();
			String label = all ? "All" : "Storage";
			GameText.drawShadowed(graphics, label, box.x + (box.width - small.stringWidth(label)) / 2,
				GameText.centredBaseline(small, box.y, box.height),
				active || hovered ? GameText.HOVER : GameText.ORANGE);
			graphics.setFont(GameText.REGULAR);
		}

		if (hovered)
		{
			tooltipManager.add(new Tooltip(all ? "All items" : GroupBankViewer.TAB_SHARED));
		}
	}

	/**
	 * A member's tab: the game's short text tab, built from an end piece, a middle that
	 * stretches to fit the name, and the end piece mirrored. On a tab narrower than both
	 * ends, each end gets half.
	 */
	private void drawMemberTab(Graphics2D graphics, FontMetrics metrics, TabSlot slot, boolean active,
		boolean hovered, boolean skinned)
	{
		Rectangle box = slot.box;

		if (skinned)
		{
			int end = active ? MEMBER_TAB_ACTIVE_END : hovered ? MEMBER_TAB_HOVER_END : MEMBER_TAB_IDLE_END;
			int middle = active ? MEMBER_TAB_ACTIVE_MIDDLE : hovered ? MEMBER_TAB_HOVER_MIDDLE : MEMBER_TAB_IDLE_MIDDLE;
			int cap = sprites.widthOf(end, MEMBER_TAB_CAP);
			int leftCap = Math.min(cap, box.width / 2);
			int rightCap = Math.min(cap, box.width - leftCap);

			sprites.tile(graphics, middle, box.x + leftCap, box.y, box.width - leftCap - rightCap, box.height);

			Shape clip = graphics.getClip();
			graphics.clipRect(box.x, box.y, leftCap, box.height);
			sprites.draw(graphics, end, box.x, box.y);
			graphics.setClip(clip);
			graphics.clipRect(box.x + box.width - rightCap, box.y, rightCap, box.height);
			sprites.drawMirrored(graphics, end, box.x + box.width - cap, box.y);
			graphics.setClip(clip);
		}
		else
		{
			drawFlatTab(graphics, box, active, hovered);
		}

		GameText.drawShadowed(graphics, slot.label, box.x + (box.width - metrics.stringWidth(slot.label)) / 2,
			GameText.centredBaseline(metrics, box.y, box.height),
			active || hovered ? GameText.HOVER : GameText.ORANGE);
	}

	private static void drawFlatTab(Graphics2D graphics, Rectangle box, boolean active, boolean hovered)
	{
		graphics.setColor(active ? BankStyle.TAB_ACTIVE : BankStyle.TAB_IDLE);
		graphics.fillRect(box.x, box.y + 1, box.width, box.height - 2);
		if (hovered && !active)
		{
			graphics.setColor(BankStyle.HOVER);
			graphics.fillRect(box.x, box.y + 1, box.width, box.height - 2);
		}
	}

	/** One tab's place in the strip, worked out before anything is drawn. */
	private static final class TabSlot
	{
		private final String tab;
		private final String label;
		private final boolean tile;
		private final Rectangle box;

		TabSlot(String tab, String label, boolean tile, Rectangle box)
		{
			this.tab = tab;
			this.label = label;
			this.tile = tile;
			this.box = box;
		}
	}

	/** The whole strip: every tab's place, and how tall the strip is. */
	private static final class TabLayout
	{
		private static final TabLayout EMPTY = new TabLayout(Collections.emptyList(), TILE_HEIGHT);

		private final List<TabSlot> slots;
		private final int height;

		TabLayout(List<TabSlot> slots, int height)
		{
			this.slots = slots;
			this.height = height;
		}
	}

	// ------------------------------------------------------------------
	// Search box
	// ------------------------------------------------------------------

	private int drawSearch(Graphics2D graphics, java.awt.Point origin, int top, java.awt.Point local, boolean skinned)
	{
		Rectangle rect = new Rectangle(PADDING, top + SEARCH_GAP, WIDTH - PADDING * 2, SEARCH_HEIGHT);
		boolean hovered = rect.contains(local);

		if (skinned)
		{
			// The game's stone button outline around the darker backing the game uses for
			// input fields.
			sprites.tile(graphics, SpriteID.TRADEBACKING_DARK, rect.x + 2, rect.y + 2, rect.width - 4, rect.height - 4);
			sprites.drawNineSlice(graphics,
				SpriteID.V2StoneButton.TOP_LEFT, SpriteID.V2StoneButton.TOP, SpriteID.V2StoneButton.TOP_RIGHT,
				SpriteID.V2StoneButton.LEFT, SpriteID.V2StoneButton.RIGHT,
				SpriteID.V2StoneButton.BOTTOM_LEFT, SpriteID.V2StoneButton.BOTTOM, SpriteID.V2StoneButton.BOTTOM_RIGHT,
				rect.x, rect.y, rect.width, rect.height);
		}
		else
		{
			graphics.setColor(BankStyle.SEARCH_BACKGROUND);
			graphics.fillRect(rect.x, rect.y, rect.width, rect.height);
			graphics.setColor(viewer.isSearchFocused() ? BankStyle.TEXT : BankStyle.BORDER);
			graphics.drawRect(rect.x, rect.y, rect.width - 1, rect.height - 1);
		}

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
			text = SEARCH_PROMPT;
			colour = hovered ? GameText.HOVER : GameText.ORANGE;
		}

		GameText.drawShadowed(graphics, text, rect.x + SEARCH_TEXT_X,
			GameText.centredBaseline(graphics.getFontMetrics(), rect.y, rect.height), colour);

		frameSearch = toCanvas(rect, origin);
		return rect.y + rect.height + GRID_GAP;
	}

	// ------------------------------------------------------------------
	// Grid and scrollbar
	// ------------------------------------------------------------------

	private void drawGrid(Graphics2D graphics, java.awt.Point origin, int top, java.awt.Point local, boolean skinned)
	{
		Rectangle area = new Rectangle(PADDING, top, GroupBankViewer.COLUMNS * CELL_WIDTH,
			VISIBLE_ROWS * CELL_HEIGHT);
		frameGrid = toCanvas(area, origin);

		List<GroupBankViewer.Row> rows = viewer.getRows();
		int first = Math.min(viewer.getScrollRow(), Math.max(0, rows.size() - 1));
		drawScrollbar(graphics, origin, area, rows.size(), first, skinned);

		if (rows.isEmpty())
		{
			graphics.setFont(GameText.REGULAR);
			String message = viewer.getFilter().isEmpty()
				? "No data yet. Check your group name and token."
				: "Nothing matches \"" + viewer.getFilter() + "\"";
			GameText.drawShadowed(graphics, message, area.x + 4, area.y + 18, GameText.ORANGE);
			return;
		}

		Shape clip = graphics.getClip();
		graphics.clipRect(area.x, area.y, area.width, area.height);

		int y = area.y;

		for (int index = first; index < rows.size() && y < area.y + area.height; index++)
		{
			GroupBankViewer.Row row = rows.get(index);

			if (row.isHeader())
			{
				graphics.setFont(GameText.REGULAR);
				GameText.drawShadowed(graphics, row.getHeader(), area.x + 2, y + 15, GameText.ORANGE);
				graphics.setColor(sprites.getLineColour());
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
	}

	private void drawCell(Graphics2D graphics, GroupBankViewer.Entry entry, int x, int y, java.awt.Point local)
	{
		boolean hovered = local.x >= x && local.x < x + CELL_WIDTH && local.y >= y && local.y < y + CELL_HEIGHT;

		if (hovered)
		{
			graphics.setColor(BankStyle.HOVER);
			graphics.fillRect(x, y, CELL_WIDTH, CELL_HEIGHT);
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

	/**
	 * The bank's scrollbar: arrows at either end, the track between them and a thumb
	 * stretched to show how much is in view. Drawn even when there is nothing to scroll, as
	 * the bank draws its own, but only clickable when there is.
	 */
	private void drawScrollbar(Graphics2D graphics, java.awt.Point origin, Rectangle area,
		int totalRows, int firstRow, boolean skinned)
	{
		int x = area.x + area.width + SCROLLBAR_GAP;
		Rectangle up = new Rectangle(x, area.y, SCROLLBAR_WIDTH, SCROLL_ARROW_HEIGHT);
		Rectangle down = new Rectangle(x, area.y + area.height - SCROLL_ARROW_HEIGHT,
			SCROLLBAR_WIDTH, SCROLL_ARROW_HEIGHT);
		Rectangle track = new Rectangle(x, up.y + up.height, SCROLLBAR_WIDTH, down.y - up.y - up.height);

		boolean scrollable = totalRows > VISIBLE_ROWS;
		int thumbHeight = scrollable ? thumbHeight(track.height, totalRows) : track.height;
		int travel = track.height - thumbHeight;
		int maxScroll = Math.max(1, totalRows - VISIBLE_ROWS);
		int thumbY = track.y + (scrollable ? (int) ((long) travel * Math.min(firstRow, maxScroll) / maxScroll) : 0);

		if (skinned)
		{
			sprites.tile(graphics, SpriteID.ScrollbarDraggerV2.TRACK, track.x, track.y, track.width, track.height);
			sprites.draw(graphics, SpriteID.ScrollbarV2.ARROW_UP, up.x, up.y);
			sprites.draw(graphics, SpriteID.ScrollbarV2.ARROW_DOWN, down.x, down.y);

			sprites.tile(graphics, SpriteID.ScrollbarDraggerV2.MIDDLE, x, thumbY + SCROLL_THUMB_END,
				SCROLLBAR_WIDTH, thumbHeight - SCROLL_THUMB_END * 2);
			sprites.draw(graphics, SpriteID.ScrollbarDraggerV2.TOP, x, thumbY);
			sprites.draw(graphics, SpriteID.ScrollbarDraggerV2.BOTTOM, x, thumbY + thumbHeight - SCROLL_THUMB_END);
		}
		else
		{
			graphics.setColor(BankStyle.SEARCH_BACKGROUND);
			graphics.fillRect(x, area.y, SCROLLBAR_WIDTH, area.height);
			graphics.setColor(BankStyle.TAB_IDLE);
			graphics.fillRect(up.x, up.y, up.width, up.height);
			graphics.fillRect(down.x, down.y, down.width, down.height);
			graphics.setColor(BankStyle.BORDER);
			graphics.fillRect(x, thumbY, SCROLLBAR_WIDTH, thumbHeight);
		}

		if (scrollable)
		{
			frameScrollbar = toCanvas(track, origin);
			frameScrollUp = toCanvas(up, origin);
			frameScrollDown = toCanvas(down, origin);
		}
		else
		{
			frameScrollbar = new Rectangle();
			frameScrollUp = new Rectangle();
			frameScrollDown = new Rectangle();
		}
	}

	private static int thumbHeight(int trackHeight, int totalRows)
	{
		return Math.min(trackHeight, Math.max(MIN_THUMB_HEIGHT, trackHeight * VISIBLE_ROWS / totalRows));
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
		int baseline = GameText.centredBaseline(graphics.getFontMetrics(),
			height - FRAME_EDGE - FOOTER_HEIGHT, FOOTER_HEIGHT);
		GameText.drawShadowed(graphics, text.toString(), PADDING, baseline, GameText.ORANGE);
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
		panel.setBackgroundColor(panelBackground.get(TOOLTIP_BACKGROUND));
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

		int thumbHeight = thumbHeight(track.height, totalRows);
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
			new Rectangle(), new Rectangle(), java.util.Collections.emptyList());

		final Rectangle window;
		final Rectangle close;
		final Rectangle search;
		final Rectangle grid;
		/** The scrollbar's track, between its arrows. Empty when there is nothing to scroll. */
		final Rectangle scrollbar;
		final Rectangle scrollUp;
		final Rectangle scrollDown;
		final List<TabHit> tabs;

		HitBoxes(Rectangle window, Rectangle close, Rectangle search, Rectangle grid,
			Rectangle scrollbar, Rectangle scrollUp, Rectangle scrollDown, List<TabHit> tabs)
		{
			this.window = window;
			this.close = close;
			this.search = search;
			this.grid = grid;
			this.scrollbar = scrollbar;
			this.scrollUp = scrollUp;
			this.scrollDown = scrollDown;
			this.tabs = java.util.Collections.unmodifiableList(tabs);
		}
	}

	private static Rectangle toCanvas(Rectangle local, java.awt.Point origin)
	{
		return new Rectangle(local.x + origin.x, local.y + origin.y, local.width, local.height);
	}

	/**
	 * Shortens text to fit, ending it with three dots. The game's fonts have no ellipsis
	 * character, so the single-glyph one would draw as a missing-glyph box.
	 */
	private static String abbreviate(String text, FontMetrics metrics, int maxWidth)
	{
		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		String result = text;
		while (result.length() > 1 && metrics.stringWidth(result + "...") > maxWidth)
		{
			result = result.substring(0, result.length() - 1);
		}
		return result + "...";
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
