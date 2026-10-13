package com.groupironmencompanion.place;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * What a place is called, so a member's whereabouts can be said in words.
 * <p>
 * Two sources, built offline into one bundled file. A region's curated name is used first:
 * those name a whole 64-tile region and never name a monster. Where there is none (anywhere
 * added to the game since that list was written) the world map's own labels answer instead:
 * the nearest one within a few dozen tiles, preferring the ones the game draws largest, so a
 * region's name beats the name of a monster standing in it.
 * <p>
 * Region names come from the Location Display plugin by trinhc2 (BSD 2-Clause), the map labels
 * from the game cache; see the README. Ported from Golems Don't Die, where the same file is
 * built by {@code dev-tools/BuildPlaceNames.java}.
 * <p>
 * The file is read lazily on first use, from the plugin jar only, and is immutable afterwards,
 * so lookups are safe from any thread.
 */
@Slf4j
@Singleton
public class PlaceNames
{
	private static final String RESOURCE = "/com/groupironmencompanion/places.gz";

	/** Bumped by the generator whenever the format changes, so an older file is never misread. */
	private static final int VERSION = 2;

	/** How far a label may be from a tile and still name it, in tiles. */
	private static final int LABEL_REACH = 48;

	/** How much a larger label is favoured over a nearer one, in tiles per step of size. */
	private static final int SIZE_WORTH = 24;

	private volatile Data data;

	/** What to call this tile, or null when nothing is known nearby. */
	@Nullable
	public String nameFor(WorldPoint at)
	{
		return at == null ? null : nameFor(at.getX(), at.getY(), at.getPlane());
	}

	@Nullable
	public String nameFor(int x, int y, int plane)
	{
		Data d = data();
		String region = d.regions.get(regionOf(x, y));
		if (region != null)
		{
			return region;
		}

		String nearest = null;
		int best = Integer.MAX_VALUE;
		for (int i = 0; i < d.labels.length; i++)
		{
			int[] label = d.labels[i];
			int distance = span(label[0] - x, label[1] - y);
			if (distance > LABEL_REACH)
			{
				continue;
			}
			// A big label a little further off is the better answer: "Ape Atoll" over
			// "Scimitar monkeys", a region over whatever stands on its shore.
			int score = distance - label[3] * SIZE_WORTH + (label[2] == plane ? 0 : LABEL_REACH);
			if (score < best)
			{
				best = score;
				nearest = d.labelNames[i];
			}
		}
		return nearest;
	}

	/**
	 * The nearest map label however far off, for somewhere nothing names. "Near" it is still
	 * an answer a player can go looking with. A label on the same floor is preferred.
	 */
	@Nullable
	public String nearest(int x, int y, int plane)
	{
		Data d = data();
		String nearest = null;
		int best = Integer.MAX_VALUE;
		for (int i = 0; i < d.labels.length; i++)
		{
			int[] label = d.labels[i];
			int score = span(label[0] - x, label[1] - y) + (label[2] == plane ? 0 : LABEL_REACH);
			if (score < best)
			{
				best = score;
				nearest = d.labelNames[i];
			}
		}
		return nearest;
	}

	static int regionOf(int x, int y)
	{
		return (x >> 6) << 8 | (y >> 6);
	}

	/** Chebyshev distance: how many tiles apart two points are when diagonals count as one. */
	private static int span(int dx, int dy)
	{
		return Math.max(Math.abs(dx), Math.abs(dy));
	}

	private Data data()
	{
		Data local = data;
		if (local == null)
		{
			synchronized (this)
			{
				local = data;
				if (local == null)
				{
					data = local = load();
				}
			}
		}
		return local;
	}

	private static Data load()
	{
		try (InputStream in = PlaceNames.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("Group Ironmen Tracker In-Game Companion: {} is missing from the plugin jar", RESOURCE);
				return Data.EMPTY;
			}
			try (DataInputStream stream = new DataInputStream(new GZIPInputStream(in)))
			{
				int version = stream.readInt();
				if (version != VERSION)
				{
					log.warn("Group Ironmen Tracker In-Game Companion: place names are version {}, expected {}",
						version, VERSION);
					return Data.EMPTY;
				}

				int count = stream.readInt();
				int[][] labels = new int[count][];
				String[] labelNames = new String[count];
				for (int i = 0; i < count; i++)
				{
					labelNames[i] = stream.readUTF();
					labels[i] = new int[]{stream.readUnsignedShort(), stream.readUnsignedShort(),
						stream.readByte(), stream.readUnsignedByte()};
				}

				int named = stream.readInt();
				Map<Integer, String> regions = new HashMap<>(named * 2);
				for (int i = 0; i < named; i++)
				{
					String name = stream.readUTF();
					regions.put(stream.readUnsignedByte() << 8 | stream.readUnsignedByte(), name);
				}
				return new Data(labels, labelNames, Collections.unmodifiableMap(regions));
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Group Ironmen Tracker In-Game Companion: could not read {}", RESOURCE, e);
			return Data.EMPTY;
		}
	}

	/** Labels as {x, y, plane, size} with their names in step, and curated names by region id. */
	private static final class Data
	{
		static final Data EMPTY = new Data(new int[0][], new String[0], Collections.emptyMap());

		final int[][] labels;
		final String[] labelNames;
		final Map<Integer, String> regions;

		Data(int[][] labels, String[] labelNames, Map<Integer, String> regions)
		{
			this.labels = labels;
			this.labelNames = labelNames;
			this.regions = regions;
		}
	}
}
