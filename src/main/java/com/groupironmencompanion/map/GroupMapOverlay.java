package com.groupironmencompanion.map;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupState;
import com.groupironmencompanion.overlay.PanelBackground;
import com.groupironmencompanion.route.RouteTracker;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.api.worldmap.WorldMapData;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A small list of online group members, drawn inside the world map while it is open.
 * <p>
 * Left-clicking a name jumps the map to that member. Right-clicking asks the Shortest Path
 * plugin to route to them. Both are the plugin's own drawing and its own click handling: no
 * menu entry is created, no game widget is touched, and jumping the map view is a client-side
 * call that sends nothing to the server.
 */
@Slf4j
@Singleton
public class GroupMapOverlay extends Overlay
{
	private static final Color BACKGROUND = new Color(22, 19, 15, 225);
	private static final Color BORDER = new Color(94, 82, 62);
	private static final Color TEXT_DIM = new Color(170, 155, 125);
	private static final Color HOVER = new Color(255, 255, 255, 38);
	private static final Color ROUTING = new Color(0x55, 0xC5, 0x5A);

	/**
	 * The hint line is secondary, so it uses the small font whatever the interface font is.
	 * This replaces a 9pt system font that was hard to read and looked nothing like the
	 * rest of the client.
	 */
	private static final Font HINT_FONT = FontManager.getRunescapeSmallFont();

	private static final BasicStroke BORDER_STROKE = new BasicStroke(1f);

	private static final int WIDTH = 150;
	private static final int PADDING = 6;
	private static final int MARGIN = 8;

	/** Extra space added to the font's own line height, for the rows and the title. */
	private static final int ROW_SPACING = 2;
	private static final int TITLE_SPACING = 4;

	@Inject
	private Client client;

	@Inject
	private GroupState groupState;

	@Inject
	private GroupIronmenCompanionConfig config;

	@Inject
	private RouteTracker routeTracker;

	@Inject
	private GroupMapMarkers markers;

	@Inject
	private RuneLiteConfig runeLiteConfig;

	@Inject
	private PanelBackground panelBackground;

	/** Rows of the last drawn frame, published for the AWT-side input handler. */
	private volatile List<Row> rows = Collections.emptyList();

	/**
	 * The overworld map, remembered while it is the one on screen.
	 * <p>
	 * There is no way to ask the client for a particular map, only for the one currently
	 * loaded, so this is captured whenever the overworld happens to be showing. That is the
	 * state the map opens in, so in practice it is captured almost immediately.
	 */
	private volatile WorldMapData surfaceMap;

	@Inject
	public GroupMapOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.mapPanel())
		{
			rows = Collections.emptyList();
			return null;
		}

		Rectangle map = mapBounds();
		if (map == null)
		{
			rows = Collections.emptyList();
			return null;
		}

		rememberSurfaceMap();

		List<GroupMember> members = new ArrayList<>();
		for (GroupMember member : groupState.getMembers())
		{
			if (groupState.isLocalPlayer(member) || member.getWorldPoint() == null)
			{
				continue;
			}
			if (!member.isOnline(config.offlineAfterMinutes()))
			{
				continue;
			}
			members.add(member);
		}

		if (members.isEmpty())
		{
			rows = Collections.emptyList();
			return null;
		}

		// The list is a RuneLite panel rather than a game interface, so it uses RuneLite's
		// interface font, the one the user picked for overlay panels, and sizes its rows to it.
		Font font = runeLiteConfig.interfaceFont().getFont();
		graphics.setFont(font);
		FontMetrics metrics = graphics.getFontMetrics();
		FontMetrics hintMetrics = graphics.getFontMetrics(HINT_FONT);
		int rowHeight = metrics.getHeight() + ROW_SPACING;
		int titleHeight = metrics.getHeight() + TITLE_SPACING;
		int hintHeight = hintMetrics.getHeight();

		boolean routing = config.mapPanelRouting();
		int height = titleHeight + members.size() * rowHeight + PADDING
			+ (routing ? hintHeight : 0);
		int x = map.x + MARGIN;
		int y = map.y + MARGIN;

		graphics.setColor(panelBackground.get(BACKGROUND));
		graphics.fillRect(x, y, WIDTH, height);
		graphics.setColor(BORDER);
		graphics.setStroke(BORDER_STROKE);
		graphics.drawRect(x, y, WIDTH - 1, height - 1);

		drawShadowed(graphics, "Group", x + PADDING,
			y + (titleHeight - metrics.getHeight()) / 2 + metrics.getAscent(), TEXT_DIM);

		Point mouse = client.getMouseCanvasPosition();
		List<Row> drawn = new ArrayList<>(members.size());

		int rowY = y + titleHeight;

		for (GroupMember member : members)
		{
			Rectangle bounds = new Rectangle(x + 1, rowY, WIDTH - 2, rowHeight);
			boolean hovered = mouse != null && bounds.contains(mouse.getX(), mouse.getY());

			if (hovered)
			{
				graphics.setColor(HOVER);
				graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
			}

			boolean isTarget = routing && routeTracker.isRoutingTo(member);
			if (isTarget)
			{
				graphics.setColor(ROUTING);
				graphics.fillRect(bounds.x, bounds.y + 3, 3, rowHeight - 6);
			}

			drawShadowed(graphics, abbreviate(member.getName(), metrics, WIDTH - PADDING * 2 - 10),
				x + PADDING + 4, rowY + (rowHeight - metrics.getHeight()) / 2 + metrics.getAscent(),
				member.getColour());

			drawn.add(new Row(member.getName(), bounds));
			rowY += rowHeight;
		}

		if (routing)
		{
			graphics.setFont(HINT_FONT);
			drawShadowed(graphics, "click: jump  ·  right: route", x + PADDING,
				rowY + hintMetrics.getAscent(), TEXT_DIM);
		}

		rows = Collections.unmodifiableList(drawn);
		return null;
	}

	/**
	 * Notes the overworld map while it is on screen, so it can be returned to later.
	 * Lumbridge is unambiguously on the overworld, so a map containing it is that map.
	 */
	private void rememberSurfaceMap()
	{
		WorldMap worldMap = client.getWorldMap();
		WorldMapData data = worldMap == null ? null : worldMap.getWorldMapData();
		if (data != null && data.surfaceContainsPosition(3222, 3218))
		{
			surfaceMap = data;
		}
	}

	/**
	 * Centres the world map on a member. Client thread only.
	 * <p>
	 * The map only moves to somewhere the map currently on screen actually covers, so this
	 * works out what is reachable from the present view before asking:
	 * <ol>
	 * <li>their true position, when the displayed map covers it, which is the case on the
	 * overworld and inside the dungeon they are actually in;</li>
	 * <li>otherwise the surface entrance leading to them, which is where their pin is when
	 * looking at the overworld;</li>
	 * <li>and if neither is on the displayed map, which happens while looking at some other
	 * dungeon, back to the overworld first and then to the entrance.</li>
	 * </ol>
	 * Without that last step, clicking a member from inside an unrelated dungeon map simply
	 * did nothing, because every candidate position lay outside the map being drawn.
	 */
	void jumpTo(GroupMember member)
	{
		WorldPoint truePosition = member == null ? null : member.getWorldPoint();
		if (truePosition == null)
		{
			return;
		}

		WorldMap worldMap = client.getWorldMap();
		if (worldMap == null)
		{
			return;
		}

		WorldMapData data = worldMap.getWorldMapData();
		if (data == null || data.surfaceContainsPosition(truePosition.getX(), truePosition.getY()))
		{
			worldMap.setWorldMapPositionTarget(truePosition);
			return;
		}

		// Underground members have an entrance on the surface; members already on the surface
		// are their own target.
		WorldPoint entrance = markers.getDrawnEntrance(member.getName(), truePosition);
		WorldPoint surfaceTarget = entrance != null ? entrance : truePosition;

		if (data.surfaceContainsPosition(surfaceTarget.getX(), surfaceTarget.getY()))
		{
			worldMap.setWorldMapPositionTarget(surfaceTarget);
			return;
		}

		WorldMapData surface = surfaceMap;
		if (surface == null)
		{
			// The overworld has not been seen this session, so there is nothing to return to.
			return;
		}

		try
		{
			worldMap.initializeWorldMap(surface);
			worldMap.setWorldMapPositionTarget(surfaceTarget);
		}
		catch (RuntimeException e)
		{
			log.debug("Could not switch the world map back to the overworld", e);
		}
	}

	/** The member whose row contains this canvas point, or null. */
	@Nullable
	String memberAt(java.awt.Point point)
	{
		for (Row row : rows)
		{
			if (row.bounds.contains(point))
			{
				return row.member;
			}
		}
		return null;
	}

	/** True while the world map is open and the panel is being drawn. */
	boolean isShowing()
	{
		return !rows.isEmpty();
	}

	@Nullable
	private Rectangle mapBounds()
	{
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null || map.isHidden())
		{
			return null;
		}
		Rectangle bounds = map.getBounds();
		return bounds == null || bounds.width <= 0 ? null : bounds;
	}

	/** Text with the 1px shadow RuneLite's own panels give their text. */
	private static void drawShadowed(Graphics2D graphics, String text, int x, int y, Color colour)
	{
		graphics.setColor(Color.BLACK);
		graphics.drawString(text, x + 1, y + 1);
		graphics.setColor(colour);
		graphics.drawString(text, x, y);
	}

	/**
	 * Shortens text to fit, ending it with three dots, since the RuneScape fonts have no
	 * single ellipsis character.
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

	/** One drawn row, in canvas coordinates. */
	private static final class Row
	{
		private final String member;
		private final Rectangle bounds;

		Row(String member, Rectangle bounds)
		{
			this.member = member;
			this.bounds = bounds;
		}
	}
}
