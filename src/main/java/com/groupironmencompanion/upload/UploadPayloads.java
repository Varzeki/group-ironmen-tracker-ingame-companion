package com.groupironmencompanion.upload;

import net.runelite.api.coords.WorldPoint;

/**
 * Wire shapes for the fields the fallback uploader posts to
 * {@code /api/group/{group}/update-group-member}.
 * <p>
 * The server validates these with {@code deny_unknown_fields}, so these classes must carry
 * exactly the fields the server expects and nothing more.
 */
public final class UploadPayloads
{
	private UploadPayloads()
	{
	}

	/**
	 * The member's own position, sent as a flat {@code [x, y, plane, onBoat]}. The fourth
	 * value is the official tracker's Sailing flag, 1 while aboard a boat.
	 */
	static int[] coordinates(WorldPoint point, boolean onBoat)
	{
		return new int[]{point.getX(), point.getY(), point.getPlane(), onBoat ? 1 : 0};
	}

	public static class Coordinates
	{
		public int x;
		public int y;
		public int plane;

		public Coordinates(int x, int y, int plane)
		{
			this.x = x;
			this.y = y;
			this.plane = plane;
		}
	}

	public static class Interacting
	{
		public String name;
		public int scale;
		public int ratio;
		public Coordinates location;

		public Interacting(String name, int scale, int ratio, Coordinates location)
		{
			this.name = name;
			this.scale = scale;
			this.ratio = ratio;
			this.location = location;
		}
	}
}
