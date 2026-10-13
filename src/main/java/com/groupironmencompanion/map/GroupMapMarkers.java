package com.groupironmencompanion.map;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupState;
import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

/**
 * Draws each group member on the world map.
 * <p>
 * The world map only, never the minimap and never the game scene: this is for finding your
 * team on a map you have deliberately opened, not for anything happening around you. The
 * markers carry a tooltip and nothing else, so no menu entry is created anywhere.
 */
@Singleton
public class GroupMapMarkers
{
	@Inject
	private WorldMapPointManager worldMapPointManager;

	@Inject
	private GroupState groupState;

	@Inject
	private GroupIronmenCompanionConfig config;

	@Inject
	private com.google.gson.Gson gson;

	/** Marker for each member's true position, keyed by member name. */
	private final Map<String, WorldMapPoint> positionMarkers = new HashMap<>();

	/** Extra marker at the surface entrance when a member is underground. */
	private final Map<String, WorldMapPoint> entranceMarkers = new HashMap<>();

	/** Colour each marker image was drawn with, so images are only redrawn when needed. */
	private final Map<String, Color> markerColours = new HashMap<>();

	/**
	 * The entrance last chosen for each member, and where they were when it was chosen.
	 * <p>
	 * Without this the surface marker moves with them: for an area the game cache does not
	 * describe, the entrance is derived from their position, so walking around underground
	 * slides the overworld marker about and can even flip it to a neighbouring dungeon's
	 * door. Holding the choice still until they genuinely leave the area keeps the marker
	 * parked on one entrance, which is the whole point of drawing it.
	 */
	private final Map<String, StickyEntrance> stickyEntrances = new HashMap<>();

	public void clear()
	{
		for (WorldMapPoint point : positionMarkers.values())
		{
			worldMapPointManager.remove(point);
		}
		for (WorldMapPoint point : entranceMarkers.values())
		{
			worldMapPointManager.remove(point);
		}
		positionMarkers.clear();
		entranceMarkers.clear();
		markerColours.clear();
		stickyEntrances.clear();
	}

	/** Rebuilds the markers from the current group state. Call on the client thread. */
	public void refresh()
	{
		if (!config.worldMapMarkers())
		{
			clear();
			return;
		}

		List<GroupMember> members = groupState.getMembers();
		Map<String, WorldMapPoint> keptPositions = new HashMap<>();
		Map<String, WorldMapPoint> keptEntrances = new HashMap<>();

		for (GroupMember member : members)
		{
			// Your own position is already on the map as the local player.
			if (groupState.isLocalPlayer(member))
			{
				continue;
			}
			if (!config.worldMapOffline() && !member.isOnline(config.offlineAfterMinutes()))
			{
				continue;
			}

			WorldPoint position = member.getWorldPoint();
			if (position == null)
			{
				continue;
			}

			String name = member.getName();
			Color colour = member.getColour();
			boolean recolour = !colour.equals(markerColours.get(name));
			markerColours.put(name, colour);

			WorldMapPoint marker = positionMarkers.remove(name);
			if (marker == null)
			{
				marker = new WorldMapPoint(position, MarkerImages.pin(colour, name));
				marker.setImagePoint(MarkerImages.TIP);
				marker.setJumpOnClick(false);
				marker.setSnapToEdge(false);
				worldMapPointManager.add(marker);
			}
			else if (recolour)
			{
				marker.setImage(MarkerImages.pin(colour, name));
			}
			marker.setWorldPoint(position);
			marker.setTooltip(describe(member, position));
			keptPositions.put(name, marker);

			MapAreas.Located located = config.worldMapEntrances() ? stickyLocate(name, position) : null;

			if (located != null && located.getEntrance() != null)
			{
				WorldMapPoint entranceMarker = entranceMarkers.remove(name);
				if (entranceMarker == null)
				{
					entranceMarker = new WorldMapPoint(located.getEntrance(), MarkerImages.entrance(colour, name));
					entranceMarker.setImagePoint(MarkerImages.TIP);
					entranceMarker.setJumpOnClick(false);
					entranceMarker.setSnapToEdge(false);
					worldMapPointManager.add(entranceMarker);
				}
				else if (recolour)
				{
					entranceMarker.setImage(MarkerImages.entrance(colour, name));
				}
				entranceMarker.setWorldPoint(located.getEntrance());
				entranceMarker.setTooltip(name + " is below, in " + located.describe());
				keptEntrances.put(name, entranceMarker);
			}
		}

		// Anything still in the old maps belongs to a member who left, went offline or
		// surfaced, so it is no longer wanted.
		for (WorldMapPoint stale : positionMarkers.values())
		{
			worldMapPointManager.remove(stale);
		}
		for (WorldMapPoint stale : entranceMarkers.values())
		{
			worldMapPointManager.remove(stale);
		}

		positionMarkers.clear();
		positionMarkers.putAll(keptPositions);
		entranceMarkers.clear();
		entranceMarkers.putAll(keptEntrances);
	}

	private MapAreas mapAreas()
	{
		return MapAreas.get(gson);
	}

	/** How far a member may move underground before their entrance is reconsidered. */
	private static final int STICKY_RADIUS = 192;

	/**
	 * The surface entrance currently drawn for a member, or null when they are on the
	 * overworld or nothing leads to where they are. Jumping to a member uses this so the map
	 * lands on the same pin it is showing.
	 * <p>
	 * Prefers the remembered choice so the jump agrees with the drawn marker exactly, and
	 * resolves fresh when entrance markers are switched off and nothing was remembered.
	 */
	@Nullable
	public WorldPoint getDrawnEntrance(String memberName, WorldPoint position)
	{
		StickyEntrance sticky = stickyEntrances.get(memberName);
		if (sticky != null)
		{
			return sticky.located.getEntrance();
		}

		MapAreas.Located located = mapAreas().locate(position);
		return located == null ? null : located.getEntrance();
	}

	/**
	 * Resolves a member's entrance, reusing the one already chosen while they stay in the
	 * same part of the world.
	 */
	@Nullable
	private MapAreas.Located stickyLocate(String name, WorldPoint position)
	{
		MapAreas.Located located = mapAreas().locate(position);
		if (located == null || located.getEntrance() == null)
		{
			// Back on the surface, or nowhere we can place: drop any remembered choice.
			stickyEntrances.remove(name);
			return located;
		}

		StickyEntrance sticky = stickyEntrances.get(name);
		if (sticky != null && sticky.anchor.distanceTo2D(position) <= STICKY_RADIUS)
		{
			return sticky.located;
		}

		stickyEntrances.put(name, new StickyEntrance(located, position));
		return located;
	}

	/** A remembered entrance choice, and the position that produced it. */
	private static final class StickyEntrance
	{
		private final MapAreas.Located located;
		private final WorldPoint anchor;

		StickyEntrance(MapAreas.Located located, WorldPoint anchor)
		{
			this.located = located;
			this.anchor = anchor;
		}
	}

	private String describe(GroupMember member, WorldPoint position)
	{
		StringBuilder text = new StringBuilder(member.getName());

		if (member.hasStats())
		{
			text.append("  |  ").append(member.getHitpoints()).append('/').append(member.getMaxHitpoints())
				.append(" hp  |  world ").append(member.getWorld());
		}

		MapAreas.Located located = mapAreas().locate(position);
		if (located != null)
		{
			text.append("  |  ").append(located.describe());
		}
		else if (position.getPlane() > 0)
		{
			text.append("  |  floor ").append(position.getPlane());
		}

		if (!member.isOnline(config.offlineAfterMinutes()))
		{
			text.append("  |  last seen ").append(member.getLastSeenText());
		}

		return text.toString();
	}
}
