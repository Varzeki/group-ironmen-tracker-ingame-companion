package com.groupironmencompanion.upload;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

/** Covers the wire shapes the server and the website expect. */
public class UploadPayloadsTest
{
	@Test
	public void coordinatesCarryTheBoatFlagAsAFourthValue()
	{
		// Matches the official tracker, which sends 1 as the fourth value while sailing.
		WorldPoint point = new WorldPoint(3043, 3195, 0);

		assertArrayEquals(new int[]{3043, 3195, 0, 1}, UploadPayloads.coordinates(point, true));
		assertArrayEquals(new int[]{3043, 3195, 0, 0}, UploadPayloads.coordinates(point, false));
	}
}
