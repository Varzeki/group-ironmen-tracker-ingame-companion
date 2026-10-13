package com.groupironmencompanion.place;

import com.google.gson.Gson;
import com.groupironmencompanion.map.MapAreas;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;

/**
 * Where a member is, in words: "Catherby", "Taverley Dungeon", "Beneath Falador".
 * <p>
 * Said the way a player would say it. The bundled {@link PlaceNames} answer almost
 * everywhere; the companion's own {@link MapAreas} names the detached areas and dungeons they
 * miss; a cave nobody named is described by the ground above it; and anywhere else is "near"
 * the closest name on the map, which a player can still go looking with.
 * <p>
 * Instances are uploaded as the coordinates of the template they were copied from, so raids
 * and boss rooms resolve like anywhere else. The few templates that sit somewhere meaningless,
 * such as the player-owned house's room palette, are named by {@link #INSTANCES} instead.
 */
@Singleton
public class Whereabouts
{
	/** Caves are laid out this many tiles north of the ground they run beneath. */
	private static final int CAVE_OFFSET = 6400;

	/**
	 * Instance templates and minigames whose map labels say the wrong thing, by region id.
	 * <p>
	 * The house's room palette sits in an empty corner of the map; raid rooms are labelled
	 * with their boss ("Verzik Vitur", "Great Olm"); the Nightmare Zone's arena is a copy of
	 * the King Black Dragon's lair. Region ids are the ones RuneLite's own Discord plugin uses
	 * for these places.
	 */
	private static final Map<Integer, String> INSTANCES;

	static
	{
		Map<Integer, String> instances = new HashMap<>();
		name(instances, "their house", 7534, 7535, 7790, 7791, 8046, 8047, 8302, 8303);
		name(instances, "Chambers of Xeric", 12889);
		name(instances, "Theatre of Blood", 12611, 12612, 12613, 12867, 12869, 13122, 13123, 13125, 13379);
		name(instances, "Tombs of Amascut", 14160, 14162, 14164, 14674, 14676, 15184, 15186, 15188, 15696,
			15698, 15700);
		name(instances, "Hallowed Sepulchre", 8797, 9051, 9052, 9053, 9054, 9309, 9563, 9565, 9821, 10074,
			10075, 10077);
		name(instances, "Motherlode Mine", 14679, 14680, 14681, 14935, 14936, 14937, 15191, 15192, 15193);
		name(instances, "Guardians of the Rift", 14484);
		name(instances, "Nightmare Zone", 9033);
		name(instances, "Fortis Colosseum", 7216);
		name(instances, "Castle Wars", 9620);
		INSTANCES = Collections.unmodifiableMap(instances);
	}

	private static void name(Map<Integer, String> instances, String name, int... regions)
	{
		for (int region : regions)
		{
			instances.put(region, name);
		}
	}

	private final PlaceNames names;
	private final Gson gson;

	@Inject
	public Whereabouts(PlaceNames names, @Nullable Gson gson)
	{
		this.names = names;
		this.gson = gson;
	}

	/** Where this tile is, or null when even the nearest map label is unknown. */
	@Nullable
	public Place of(@Nullable WorldPoint at)
	{
		if (at == null)
		{
			return null;
		}

		boolean upstairs = at.getPlane() > 0;

		String instance = INSTANCES.get(PlaceNames.regionOf(at.getX(), at.getY()));
		if (instance != null)
		{
			return Place.at(instance);
		}

		String named = names.nameFor(at);
		if (named != null)
		{
			return new Place(named, Place.Relation.AT, upstairs);
		}

		String area = mapArea(at);
		if (area != null)
		{
			return new Place(area, Place.Relation.AT, upstairs);
		}

		if (at.getY() >= CAVE_OFFSET)
		{
			int above = at.getY() - CAVE_OFFSET;
			String over = names.nameFor(at.getX(), above, 0);
			if (over == null)
			{
				over = names.nearest(at.getX(), above, 0);
			}
			if (over != null)
			{
				return new Place(over, Place.Relation.BENEATH, false);
			}
		}

		String near = names.nearest(at.getX(), at.getY(), at.getPlane());
		return near == null ? null : new Place(near, Place.Relation.NEAR, upstairs);
	}

	/**
	 * The game's own name for a dungeon or detached area, from the map data the world map
	 * markers already use, or null on the overworld or where it only knows "underground".
	 */
	@Nullable
	private String mapArea(WorldPoint at)
	{
		if (gson == null)
		{
			return null;
		}
		MapAreas.Located located = MapAreas.get(gson).locate(at);
		if (located == null)
		{
			return null;
		}
		if (located.getEntranceName() != null)
		{
			return located.getEntranceName();
		}
		return located.getAreaName();
	}
}
