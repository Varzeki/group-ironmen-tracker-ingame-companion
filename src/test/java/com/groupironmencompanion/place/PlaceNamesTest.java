package com.groupironmencompanion.place;

import com.google.gson.Gson;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Guards the bundled place names and the way a position is put into words. If the resource
 * goes missing or its format shifts, these break rather than every activity quietly losing
 * its "at Catherby".
 */
public class PlaceNamesTest
{
	private final PlaceNames names = new PlaceNames();
	private final Whereabouts whereabouts = new Whereabouts(names, new Gson());

	@Test
	public void namesATownOnTheSurface()
	{
		assertEquals("Catherby", names.nameFor(new WorldPoint(2813, 3440, 0)));
	}

	@Test
	public void namesADungeonByItsRegionRatherThanItsMonsters()
	{
		// The map labels alone would call this "Black demons" or similar.
		assertEquals("Taverley Dungeon", names.nameFor(new WorldPoint(2884, 9798, 0)));
	}

	@Test
	public void saysUpstairsAboveTheGroundFloor()
	{
		Place place = whereabouts.of(new WorldPoint(2965, 3380, 1));
		assertNotNull(place);
		assertEquals("Falador, upstairs", place.toString());
		assertEquals("at Falador, upstairs", place.phrase("at"));
	}

	@Test
	public void namesInstanceTemplatesThatTheMapLabelsGetWrong()
	{
		// The house's room palette is nowhere near anything a map label would name, and a raid
		// room is labelled with its boss.
		assertEquals("Their house", String.valueOf(whereabouts.of(new WorldPoint(1888, 7072, 0))));
		assertEquals("Theatre of Blood", String.valueOf(whereabouts.of(new WorldPoint(3168, 4320, 0))));
		assertEquals("Nightmare Zone", String.valueOf(whereabouts.of(new WorldPoint(2272, 4704, 0))));
	}

	@Test
	public void somewhereUnnamedIsNearTheClosestLabel()
	{
		Place place = whereabouts.of(new WorldPoint(2300, 2700, 0));
		assertNotNull(place);
		assertEquals(Place.Relation.NEAR, place.getRelation());
		assertEquals("near " + place.getName(), place.phrase("at"));
	}

	@Test
	public void aPlaceNearOrBeneathIgnoresTheActivitysPreposition()
	{
		assertEquals("beneath Falador", new Place("Falador", Place.Relation.BENEATH, false).phrase("at"));
		assertEquals("in Catherby", Place.at("Catherby").phrase("in"));
	}

	@Test
	public void noPositionHasNoPlace()
	{
		assertNull(whereabouts.of(null));
	}
}
