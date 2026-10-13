package com.groupironmencompanion.bank;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import com.groupironmencompanion.api.GroupApi;
import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupState;
import com.groupironmencompanion.overlay.PanelBackground;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
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
	 * makes the two windows read as the same thing when they sit side by side. A window
	 * resized to a size between whole cells spreads the spare pixels between the cells.
	 */
	static final int CELL_WIDTH = 48;
	static final int CELL_HEIGHT = 36;

	/**
	 * Rows of items shown until the window is resized, and the smallest window it can be
	 * resized to. The largest is whatever fits on the canvas.
	 */
	private static final int DEFAULT_ROWS = 7;
	private static final int MIN_COLUMNS = 4;
	private static final int MIN_ROWS = 3;

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

	// The tab strip: a row of the bank's own 41x40 tab tiles. Member tiles have a name under
	// their icon and stretch wider to fit it.

	/** A bank tab tile, and the space left between neighbouring tiles. */
	private static final int TILE_WIDTH = 41;
	private static final int TILE_HEIGHT = 40;
	private static final int TILE_GAP = 0;

	/**
	 * The box a tile's icon is fitted into, below the tile's top edge. Icons larger than this
	 * (the helmet and the group storage chest) are scaled down to fit above the label.
	 */
	private static final int TILE_ICON_TOP = 3;
	private static final int TILE_ICON_WIDTH = 30;
	private static final int TILE_ICON_HEIGHT = 24;

	/**
	 * A member label's baseline, measured up from the tile's bottom; the least space kept
	 * either side of it; and the space a tile is given either side of a name when there is
	 * room for the whole name.
	 */
	private static final int TILE_LABEL_BOTTOM = 4;
	private static final int TILE_LABEL_PADDING = 1;
	private static final int MEMBER_LABEL_PADDING = 5;

	/**
	 * How much of each end of a tab tile sprite is kept as it is when a tile is stretched
	 * wider, so its bevelled corner keeps its shape; the middle is stretched between them.
	 */
	private static final int TILE_END_CAP = 7;

	/** The size of the colour swatch standing in for a member's helmet without sprites. */
	private static final int FLAT_SWATCH = 14;

	/** The group ironman helmet, recoloured per member. */
	private static final int MEMBER_ICON = SpriteID.SideiconsInterface._32;

	/** The search box below the tab strip, and the gap either side of it. */
	private static final int SEARCH_GAP = 3;
	private static final int SEARCH_HEIGHT = 22;
	private static final int SEARCH_TEXT_X = 7;

	/** Space between the search box and the first row of items. */
	private static final int GRID_GAP = 3;

	/** How far down the grid the message for an empty tab starts. */
	private static final int EMPTY_MESSAGE_TOP = 24;

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
		SpriteID.BanktabIcons.ALL_ITEMS, SpriteID.BankStorage._4, MEMBER_ICON,
		SpriteID.V2StoneButton.TOP_LEFT, SpriteID.V2StoneButton.TOP_RIGHT,
		SpriteID.V2StoneButton.BOTTOM_LEFT, SpriteID.V2StoneButton.BOTTOM_RIGHT,
		SpriteID.V2StoneButton.LEFT, SpriteID.V2StoneButton.TOP,
		SpriteID.V2StoneButton.RIGHT, SpriteID.V2StoneButton.BOTTOM,
		SpriteID.ScrollbarV2.ARROW_UP, SpriteID.ScrollbarV2.ARROW_DOWN,
		SpriteID.ScrollbarDraggerV2.TOP, SpriteID.ScrollbarDraggerV2.MIDDLE,
		SpriteID.ScrollbarDraggerV2.BOTTOM, SpriteID.ScrollbarDraggerV2.TRACK,
	};

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

	/** Everything across the window that is not item columns. */
	private static final int CHROME_WIDTH = PADDING * 2 + SCROLLBAR_GAP + SCROLLBAR_WIDTH;

	/** Height for a window whose tab strip is this tall, showing this many rows of items. */
	private static int heightFor(int stripHeight, int rows)
	{
		return CONTENT_TOP + stripHeight + SEARCH_GAP + SEARCH_HEIGHT + GRID_GAP
			+ rows * CELL_HEIGHT + FOOTER_HEIGHT + FRAME_EDGE;
	}

	/** The narrowest the window can be: the minimum number of columns and no spare pixels. */
	private static final int MIN_WIDTH = CHROME_WIDTH + MIN_COLUMNS * CELL_WIDTH;

	/** The width before the window is ever resized. */
	private static final int DEFAULT_WIDTH = CHROME_WIDTH + GroupBankViewer.DEFAULT_COLUMNS * CELL_WIDTH;

	/** A width kept between the minimum and the canvas. */
	private static int clampWidth(int width, int canvasWidth)
	{
		return Math.max(MIN_WIDTH, Math.min(width, canvasWidth));
	}

	/**
	 * A height kept between the minimum and the canvas, for a window whose fixed parts (the
	 * title, tabs, search box and footer) take up the given height.
	 */
	private static int clampHeight(int height, int chrome, int canvasHeight)
	{
		return Math.max(chrome + MIN_ROWS * CELL_HEIGHT, Math.min(height, canvasHeight));
	}

	/**
	 * The window's size, and how many whole columns and rows of items it shows. Immutable,
	 * so it can be shared with AWT.
	 */
	private static final class Geometry
	{
		private final int columns;
		private final int rows;
		private final int width;
		private final int height;

		/** The height of everything that is not the grid, which depends on the tab rows. */
		private final int chrome;

		Geometry(int columns, int rows, int width, int height, int chrome)
		{
			this.columns = columns;
			this.rows = rows;
			this.width = width;
			this.height = height;
			this.chrome = chrome;
		}
	}

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

	@Inject
	private GroupApi api;

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
	private int tabLayoutWidth;

	/**
	 * The shape of the frame last drawn, and what it was worked out from. The input handler
	 * clamps a size being dragged against these on the AWT thread, where the client cannot be
	 * asked for the canvas size.
	 */
	private volatile Geometry geometry = new Geometry(GroupBankViewer.DEFAULT_COLUMNS, DEFAULT_ROWS,
		DEFAULT_WIDTH, heightFor(TILE_HEIGHT, DEFAULT_ROWS), heightFor(TILE_HEIGHT, 0));
	private volatile int canvasWidth = Integer.MAX_VALUE;
	private volatile int canvasHeight = Integer.MAX_VALUE;

	/** The frame being drawn's width and rows of items; client thread only. */
	private int frameWidth;
	private int frameRows;

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
		Geometry current = geometry;
		return new Dimension(current.width, current.height);
	}

	/**
	 * A size being dragged, kept between the minimum and the canvas. The window follows the
	 * pointer to the pixel; only the grid inside it moves in whole cells. Safe to call from
	 * any thread.
	 */
	Dimension clampSize(int width, int height)
	{
		return new Dimension(clampWidth(width, canvasWidth),
			clampHeight(height, geometry.chrome, canvasHeight));
	}

	/** How many rows of items are in view. */
	int getVisibleRows()
	{
		return geometry.rows;
	}

	/** The furthest the grid can be scrolled, in rows. */
	int maxScrollRow()
	{
		return Math.max(0, viewer.getRows().size() - geometry.rows);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.bankViewer() || !viewer.isOpen())
		{
			hits = HitBoxes.EMPTY;
			return null;
		}

		// The size the user dragged the window to (or the default) decides how many items
		// fit across, and that has to be known before the rows are laid out.
		canvasWidth = client.getCanvasWidth();
		canvasHeight = client.getCanvasHeight();
		Dimension preferred = getPreferredSize();
		frameWidth = clampWidth(preferred == null ? DEFAULT_WIDTH : preferred.width, canvasWidth);
		int columns = (frameWidth - CHROME_WIDTH) / CELL_WIDTH;
		viewer.setColumns(columns);
		viewer.rebuildIfNeeded();

		// The tabs are laid out next, because how many rows they need decides how much
		// height is left for the grid.
		GameText.prepare(graphics);
		List<String> tabs = viewer.getTabs();
		if (tabs != tabLayoutSource || frameWidth != tabLayoutWidth)
		{
			tabLayout = layoutTabs(graphics, tabs, frameWidth);
			tabLayoutSource = tabs;
			tabLayoutWidth = frameWidth;
		}

		int chrome = heightFor(tabLayout.height, 0);
		int height = clampHeight(preferred == null ? chrome + DEFAULT_ROWS * CELL_HEIGHT : preferred.height,
			chrome, canvasHeight);
		frameRows = (height - chrome) / CELL_HEIGHT;
		geometry = new Geometry(columns, frameRows, frameWidth, height, chrome);

		java.awt.Point origin = getBounds().getLocation();
		Point mouse = client.getMouseCanvasPosition();
		java.awt.Point local = mouse == null
			? new java.awt.Point(-1, -1)
			: new java.awt.Point(mouse.getX() - origin.x, mouse.getY() - origin.y);

		frameTabs = new ArrayList<>();
		sprites.refresh();

		boolean skinned = sprites.hasAll(SPRITES);
		drawFrame(graphics, height, skinned);
		int y = drawTitle(graphics, origin, local, skinned);
		y = drawTabs(graphics, origin, y, local, skinned);
		y = drawSearch(graphics, origin, y, local, skinned);
		drawGrid(graphics, origin, y, local, skinned);
		drawFooter(graphics, height);

		hits = new HitBoxes(
			new Rectangle(origin.x, origin.y, frameWidth, height),
			frameClose, frameSearch, frameGrid, frameScrollbar, frameScrollUp, frameScrollDown,
			frameTabs);

		return new Dimension(frameWidth, height);
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
			graphics.drawImage(frameImage(frameWidth, height), 0, 0, null);
			return;
		}

		graphics.setColor(BankStyle.BACKGROUND);
		graphics.fillRect(0, 0, frameWidth, height);
		graphics.setColor(BankStyle.TITLE_BACKGROUND);
		graphics.fillRect(1, 1, frameWidth - 2, CONTENT_TOP - 1);
		graphics.setColor(BankStyle.BORDER);
		graphics.setStroke(BORDER_STROKE);
		graphics.drawRect(0, 0, frameWidth - 1, height - 1);
		graphics.drawLine(1, CONTENT_TOP - 1, frameWidth - 2, CONTENT_TOP - 1);
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
		GameText.drawShadowed(graphics, TITLE, (frameWidth - metrics.stringWidth(TITLE)) / 2,
			GameText.centredBaseline(metrics, TITLE_TOP, TITLE_HEIGHT), GameText.ORANGE);

		Rectangle close = new Rectangle(frameWidth - CLOSE_RIGHT, CLOSE_TOP, CLOSE_WIDTH, CLOSE_HEIGHT);
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
	 * Arranges the tab strip: All and Group Storage first, then a tile per member, in one row
	 * of the bank's own tab tiles.
	 * <p>
	 * A member's tile is as wide as their name needs at the normal small font, never narrower
	 * than a bank tab. When the row is wider than the window, the widest member tiles give
	 * way first, back towards a bank tab's width, with their labels moving to smaller sizes
	 * of the font and then being shortened to fit. Only when even plain bank-tab-width tiles
	 * do not fit does the row wrap.
	 * <p>
	 * Tabs used to be drawn on a single strip, and anything past the right edge was simply
	 * dropped. With a full group that silently swallowed the last tab, which is how the
	 * group storage tab became unreachable. Wrapping keeps every tab clickable.
	 */
	private TabLayout layoutTabs(Graphics2D graphics, List<String> tabs, int width)
	{
		if (tabs.isEmpty())
		{
			return TabLayout.EMPTY;
		}

		List<String> fixed = new ArrayList<>();
		List<String> members = new ArrayList<>();
		for (String tab : tabs)
		{
			(isFixedTab(tab) ? fixed : members).add(tab);
		}

		FontMetrics metrics = graphics.getFontMetrics(GameText.SMALL);
		int[] natural = new int[members.size()];
		int cap = TILE_WIDTH;
		for (int i = 0; i < natural.length; i++)
		{
			natural[i] = Math.max(TILE_WIDTH, metrics.stringWidth(members.get(i)) + MEMBER_LABEL_PADDING * 2);
			cap = Math.max(cap, natural[i]);
		}

		int available = width - PADDING * 2;
		int memberRoom = available - fixed.size() * TILE_WIDTH - (tabs.size() - 1) * TILE_GAP;
		while (cap > TILE_WIDTH && cappedTotal(natural, cap) > memberRoom)
		{
			cap--;
		}

		List<TabSlot> slots = new ArrayList<>(tabs.size());
		if (cappedTotal(natural, cap) <= memberRoom)
		{
			int x = PADDING;
			for (String tab : fixed)
			{
				slots.add(new TabSlot(tab, null, null, new Rectangle(x, CONTENT_TOP, TILE_WIDTH, TILE_HEIGHT)));
				x += TILE_WIDTH + TILE_GAP;
			}
			for (int i = 0; i < members.size(); i++)
			{
				int tileWidth = Math.min(natural[i], cap);
				slots.add(memberSlot(graphics, members.get(i), new Rectangle(x, CONTENT_TOP, tileWidth, TILE_HEIGHT)));
				x += tileWidth + TILE_GAP;
			}
			return new TabLayout(Collections.unmodifiableList(slots), TILE_HEIGHT);
		}

		// Even bank-tab-width tiles do not fit across, so wrap them.
		List<String> ordered = new ArrayList<>(fixed);
		ordered.addAll(members);
		int perRow = Math.max(1, (available + TILE_GAP) / (TILE_WIDTH + TILE_GAP));
		for (int i = 0; i < ordered.size(); i++)
		{
			String tab = ordered.get(i);
			Rectangle box = new Rectangle(PADDING + (i % perRow) * (TILE_WIDTH + TILE_GAP),
				CONTENT_TOP + (i / perRow) * TILE_HEIGHT, TILE_WIDTH, TILE_HEIGHT);
			slots.add(isFixedTab(tab) ? new TabSlot(tab, null, null, box) : memberSlot(graphics, tab, box));
		}
		int rows = (ordered.size() + perRow - 1) / perRow;
		return new TabLayout(Collections.unmodifiableList(slots), rows * TILE_HEIGHT);
	}

	/** The total width of member tiles when none may be wider than the cap. */
	private static int cappedTotal(int[] natural, int cap)
	{
		int total = 0;
		for (int width : natural)
		{
			total += Math.min(width, cap);
		}
		return total;
	}

	/**
	 * A member's tile with its name fitted to it: the small font if it fits, then smaller
	 * sizes of it, and only then shortened.
	 */
	private static TabSlot memberSlot(Graphics2D graphics, String name, Rectangle box)
	{
		int room = box.width - TILE_LABEL_PADDING * 2;
		for (Font font : GameText.LABEL_SIZES)
		{
			if (graphics.getFontMetrics(font).stringWidth(name) <= room)
			{
				return new TabSlot(name, name, font, box);
			}
		}

		Font smallest = GameText.LABEL_SIZES[GameText.LABEL_SIZES.length - 1];
		return new TabSlot(name, abbreviate(name, graphics.getFontMetrics(smallest), room), smallest, box);
	}

	private static boolean isFixedTab(String tab)
	{
		return GroupBankViewer.TAB_ALL.equals(tab) || GroupBankViewer.TAB_SHARED.equals(tab);
	}

	private int drawTabs(Graphics2D graphics, java.awt.Point origin, int top, java.awt.Point local, boolean skinned)
	{
		for (TabSlot slot : tabLayout.slots)
		{
			Rectangle box = slot.box;
			boolean active = slot.tab.equals(viewer.getActiveTab());
			boolean hovered = box.contains(local);
			drawTile(graphics, slot, active, hovered, skinned);
			frameTabs.add(new TabHit(slot.tab, toCanvas(box, origin)));
		}

		// The line closing off the strip, as the bank draws under its own tabs.
		int bottom = top + tabLayout.height;
		graphics.setColor(sprites.getLineColour());
		graphics.drawLine(FRAME_EDGE, bottom - 1, frameWidth - FRAME_EDGE - 1, bottom - 1);
		return bottom;
	}

	/**
	 * One of the bank's own tab tiles. All and Group Storage carry just the bank's all-items
	 * and group storage icons, as the bank's tabs do. A member's tile carries the group
	 * ironman helmet in that member's colour, the colour their world map marker uses, over
	 * their name; it is stretched wider for a long name. Hovering any tile names it in full,
	 * as hovering a bank tab does.
	 */
	private void drawTile(Graphics2D graphics, TabSlot slot, boolean active, boolean hovered, boolean skinned)
	{
		Rectangle box = slot.box;
		boolean member = slot.label != null;
		int fixedIcon = GroupBankViewer.TAB_ALL.equals(slot.tab) ? SpriteID.BanktabIcons.ALL_ITEMS : SpriteID.BankStorage._4;
		int iconY = box.y + TILE_ICON_TOP;

		if (skinned)
		{
			int tile = active ? SpriteID.Banktabs.SELECTED
				: hovered ? SpriteID.Banktabs.HOVERED
				: SpriteID.Banktabs.TAB;
			sprites.drawStretched(graphics, tile, box.x, box.y, box.width, TILE_END_CAP);

			if (member)
			{
				BufferedImage image = sprites.icon(MEMBER_ICON, memberColour(slot.tab), TILE_ICON_WIDTH, TILE_ICON_HEIGHT);
				if (image != null)
				{
					graphics.drawImage(image, box.x + (box.width - image.getWidth()) / 2,
						iconY + (TILE_ICON_HEIGHT - image.getHeight()) / 2, null);
				}
			}
			else
			{
				sprites.drawCentred(graphics, fixedIcon, box.x, box.y, box.width, box.height);
			}
		}
		else
		{
			drawFlatTab(graphics, box, active, hovered);
			if (member)
			{
				graphics.setColor(memberColour(slot.tab));
				graphics.fillRect(box.x + (box.width - FLAT_SWATCH) / 2,
					iconY + (TILE_ICON_HEIGHT - FLAT_SWATCH) / 2, FLAT_SWATCH, FLAT_SWATCH);
			}
			else
			{
				// Without the icon there would be nothing to tell the two apart.
				graphics.setFont(GameText.SMALL);
				FontMetrics small = graphics.getFontMetrics();
				String label = GroupBankViewer.TAB_ALL.equals(slot.tab) ? "All" : "Storage";
				GameText.drawShadowed(graphics, label, box.x + (box.width - small.stringWidth(label)) / 2,
					GameText.centredBaseline(small, box.y, box.height),
					active || hovered ? GameText.HOVER : GameText.ORANGE);
			}
		}

		if (member)
		{
			graphics.setFont(slot.font);
			FontMetrics metrics = graphics.getFontMetrics();
			GameText.drawShadowed(graphics, slot.label, box.x + (box.width - metrics.stringWidth(slot.label)) / 2,
				box.y + box.height - TILE_LABEL_BOTTOM, active || hovered ? GameText.HOVER : GameText.ORANGE);
		}

		if (hovered)
		{
			tooltipManager.add(new Tooltip(GroupBankViewer.TAB_ALL.equals(slot.tab) ? "All items" : slot.tab));
		}
	}

	/** A member's colour, as used for their map marker and tooltip lines. */
	private Color memberColour(String name)
	{
		GroupMember member = groupState.getMember(name);
		return member != null ? member.getColour() : BankStyle.TEXT;
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

	/**
	 * One tab's place in the strip, worked out before anything is drawn. Member tabs also
	 * carry their fitted label and its font; the All and Group Storage tabs have neither.
	 */
	private static final class TabSlot
	{
		private final String tab;
		@Nullable
		private final String label;
		@Nullable
		private final Font font;
		private final Rectangle box;

		TabSlot(String tab, @Nullable String label, @Nullable Font font, Rectangle box)
		{
			this.tab = tab;
			this.label = label;
			this.font = font;
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
		Rectangle rect = new Rectangle(PADDING, top + SEARCH_GAP, frameWidth - PADDING * 2, SEARCH_HEIGHT);
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
		// The grid takes all the room left, and its whole columns and rows share out any
		// spare pixels between them, so a window between whole cells has no dead strip.
		Rectangle area = new Rectangle(PADDING, top, frameWidth - CHROME_WIDTH,
			geometry.height - top - FOOTER_HEIGHT - FRAME_EDGE);
		int columns = viewer.getColumns();
		frameGrid = toCanvas(area, origin);

		// Clamped to what is in view, so making the window taller never leaves the grid
		// scrolled past its last row.
		List<GroupBankViewer.Row> rows = viewer.getRows();
		int first = Math.max(0, Math.min(viewer.getScrollRow(), rows.size() - frameRows));
		drawScrollbar(graphics, origin, area, rows.size(), first, skinned);

		if (rows.isEmpty())
		{
			drawEmptyMessage(graphics, area);
			return;
		}

		Shape clip = graphics.getClip();
		graphics.clipRect(area.x, area.y, area.width, area.height);

		for (int line = 0; line < frameRows && first + line < rows.size(); line++)
		{
			GroupBankViewer.Row row = rows.get(first + line);
			int y = area.y + line * area.height / frameRows;
			int rowHeight = area.y + (line + 1) * area.height / frameRows - y;

			if (row.isHeader())
			{
				int textY = y + (rowHeight - CELL_HEIGHT) / 2;
				graphics.setFont(GameText.REGULAR);
				GameText.drawShadowed(graphics, row.getHeader(), area.x + 2, textY + 15, GameText.ORANGE);
				graphics.setColor(sprites.getLineColour());
				graphics.drawLine(area.x + 2, textY + 19, area.x + area.width - 4, textY + 19);
				continue;
			}

			int column = 0;
			for (GroupBankViewer.Entry entry : row.getItems())
			{
				int x = area.x + column * area.width / columns;
				int cellWidth = area.x + (column + 1) * area.width / columns - x;
				drawCell(graphics, entry, x, y, cellWidth, rowHeight, local);
				column++;
			}
		}

		graphics.setClip(clip);
	}

	/**
	 * Says why there is nothing to show. It used to blame the group name and token whenever a
	 * tab was empty, even for a member who had simply never synced while everyone else showed
	 * up fine, which sent people off checking credentials that worked.
	 */
	private void drawEmptyMessage(Graphics2D graphics, Rectangle area)
	{
		String[] lines;
		String tab = viewer.getActiveTab();
		if (!viewer.getFilter().isEmpty())
		{
			lines = new String[]{"Nothing matches \"" + viewer.getFilter() + "\"."};
		}
		else if (!api.hasCredentials())
		{
			lines = new String[]{"Enter your group name and token", "in the plugin's Connection settings."};
		}
		else if (groupState.isEmpty())
		{
			lines = new String[]{"No data from your group yet.", "The side panel shows the connection status."};
		}
		else if (GroupBankViewer.TAB_SHARED.equals(tab))
		{
			lines = new String[]{"Group Storage is empty."};
		}
		else if (GroupBankViewer.TAB_ALL.equals(tab))
		{
			lines = new String[]{"Nobody in the group has shared any items yet."};
		}
		else
		{
			lines = new String[]{tab + " has not shared any items yet."};
		}

		graphics.setFont(GameText.REGULAR);
		FontMetrics metrics = graphics.getFontMetrics();
		int y = area.y + EMPTY_MESSAGE_TOP + metrics.getAscent();
		for (String line : lines)
		{
			GameText.drawShadowed(graphics, line, area.x + (area.width - metrics.stringWidth(line)) / 2, y,
				GameText.ORANGE);
			y += metrics.getHeight();
		}
	}

	private void drawCell(Graphics2D graphics, GroupBankViewer.Entry entry, int x, int y, int cellWidth,
		int cellHeight, java.awt.Point local)
	{
		boolean hovered = local.x >= x && local.x < x + cellWidth && local.y >= y && local.y < y + cellHeight;

		if (hovered)
		{
			graphics.setColor(BankStyle.HOVER);
			graphics.fillRect(x, y, cellWidth, cellHeight);
		}

		long quantity = entry.getQuantity();

		// Always request the plain, quantity-1 sprite. Quantity is part of ItemManager's
		// image cache key and that cache holds only 128 entries, so asking for group-wide
		// totals would evict and re-render sprites every frame. The stack text is drawn
		// below instead, which also lets totals exceed what the game's own text renders.
		BufferedImage image = itemManager.getImage(entry.getItemId());
		if (image != null)
		{
			graphics.drawImage(image, x + (cellWidth - image.getWidth()) / 2,
				y + (cellHeight - image.getHeight()) / 2, null);
		}

		if (quantity > 1)
		{
			// Anchored to where the item sprite sits, the way the game anchors it, rather
			// than to the cell, so it stays put whatever the cell's padding.
			int imageX = x + (cellWidth - ITEM_WIDTH) / 2;
			int imageY = y + (cellHeight - ITEM_HEIGHT) / 2;
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

		boolean scrollable = totalRows > frameRows;
		int thumbHeight = scrollable ? thumbHeight(track.height, totalRows, frameRows) : track.height;
		int travel = track.height - thumbHeight;
		int maxScroll = Math.max(1, totalRows - frameRows);
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

	private static int thumbHeight(int trackHeight, int totalRows, int visibleRows)
	{
		return Math.min(trackHeight, Math.max(MIN_THUMB_HEIGHT, trackHeight * visibleRows / totalRows));
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
		int visibleRows = geometry.rows;
		int maxScroll = totalRows - visibleRows;
		if (track.height <= 0 || maxScroll <= 0)
		{
			return 0;
		}

		int thumbHeight = thumbHeight(track.height, totalRows, visibleRows);
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
