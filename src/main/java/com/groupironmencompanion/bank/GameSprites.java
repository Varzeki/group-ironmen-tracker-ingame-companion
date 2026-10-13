package com.groupironmencompanion.bank;

import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.IndexDataBase;
import net.runelite.api.SpritePixels;

/**
 * The game's own interface sprites, so the group bank can be drawn in the real bank's chrome.
 * <p>
 * Sprites are looked up the way the game looks them up when it draws an interface: the
 * client's sprite overrides first, then the game cache. Overrides are how RuneLite's Interface
 * Styles and the Resource Packs plugin reskin interfaces, so going through them keeps this
 * window matching the bank beside it. The overrides map is only ever read, never written.
 * <p>
 * Every sprite keeps the offsets and maximum size it was authored with. The game positions a
 * sprite by its maximum box and draws the trimmed pixels at an offset inside it, and tiles by
 * that box too; the frame edges, for example, are 6px strips sitting 15px into a 36px box.
 * Honouring that is what makes the pieces line up exactly as they do in the bank.
 * <p>
 * Client thread only. The overlays that use it render there.
 */
@Singleton
public class GameSprites
{
	/** How long to wait before asking again for a sprite the cache could not provide. */
	private static final long MISSING_RETRY_MILLIS = 2000;

	@Inject
	private Client client;

	private final Map<Integer, Sprite> sprites = new HashMap<>();
	private final Map<Integer, Long> missing = new HashMap<>();

	/** Bumped whenever cached sprites are thrown away, so composed images can be rebuilt. */
	private int version;

	/** A loaded sprite: its trimmed pixels plus the box they are placed in. */
	static final class Sprite
	{
		final BufferedImage image;
		final int offsetX;
		final int offsetY;
		final int maxWidth;
		final int maxHeight;

		private Sprite(SpritePixels pixels)
		{
			image = pixels.toBufferedImage();
			offsetX = Math.max(0, pixels.getOffsetX());
			offsetY = Math.max(0, pixels.getOffsetY());
			// Sprites built from plain images can report no maximum size; the trimmed
			// pixels at their offset are then the whole box.
			maxWidth = Math.max(1, Math.max(pixels.getMaxWidth(), offsetX + image.getWidth()));
			maxHeight = Math.max(1, Math.max(pixels.getMaxHeight(), offsetY + image.getHeight()));
		}
	}

	/** The sprite with this id, or null when neither the overrides nor the cache have it. */
	@Nullable
	Sprite get(int id)
	{
		Sprite sprite = sprites.get(id);
		if (sprite != null)
		{
			return sprite;
		}

		Long missedAt = missing.get(id);
		long now = System.currentTimeMillis();
		if (missedAt != null && now - missedAt < MISSING_RETRY_MILLIS)
		{
			return null;
		}

		SpritePixels pixels = load(id);
		if (pixels == null || pixels.getWidth() <= 0 || pixels.getHeight() <= 0)
		{
			missing.put(id, now);
			return null;
		}

		missing.remove(id);
		sprite = new Sprite(pixels);
		sprites.put(id, sprite);
		return sprite;
	}

	@Nullable
	private SpritePixels load(int id)
	{
		Map<Integer, SpritePixels> overrides = client.getSpriteOverrides();
		SpritePixels override = overrides == null ? null : overrides.get(id);
		if (override != null)
		{
			return override;
		}

		IndexDataBase index = client.getIndexSprites();
		if (index == null)
		{
			return null;
		}
		SpritePixels[] frames = client.getSprites(index, id, 0);
		return frames == null || frames.length == 0 ? null : frames[0];
	}

	/** True when every one of these sprites can be drawn. */
	boolean hasAll(int... ids)
	{
		for (int id : ids)
		{
			if (get(id) == null)
			{
				return false;
			}
		}
		return true;
	}

	/** Changes whenever the cached sprites are replaced. */
	int getVersion()
	{
		return version;
	}

	/** Throws away every cached sprite, so the next draw loads them afresh. */
	void clear()
	{
		sprites.clear();
		missing.clear();
		version++;
	}

	// ------------------------------------------------------------------
	// Drawing
	// ------------------------------------------------------------------

	/** Draws a sprite with its box's top-left corner at the given point. */
	void draw(Graphics2D graphics, int id, int x, int y)
	{
		Sprite sprite = get(id);
		if (sprite != null)
		{
			graphics.drawImage(sprite.image, x + sprite.offsetX, y + sprite.offsetY, null);
		}
	}

	/** Draws a sprite with its box centred in the given rectangle, as icons are on buttons. */
	void drawCentred(Graphics2D graphics, int id, int x, int y, int width, int height)
	{
		Sprite sprite = get(id);
		if (sprite != null)
		{
			draw(graphics, id, x + (width - sprite.maxWidth) / 2, y + (height - sprite.maxHeight) / 2);
		}
	}

	/**
	 * Draws a sprite flipped left to right, with its box's top-left corner at the given point.
	 * The game builds symmetric pieces such as the right end of a tab from the left end this
	 * way, so there is no separate sprite for it.
	 */
	void drawMirrored(Graphics2D graphics, int id, int x, int y)
	{
		Sprite sprite = get(id);
		if (sprite == null)
		{
			return;
		}
		int width = sprite.image.getWidth();
		int height = sprite.image.getHeight();
		int left = x + sprite.maxWidth - sprite.offsetX - width;
		int top = y + sprite.offsetY;
		graphics.drawImage(sprite.image, left, top, left + width, top + height,
			width, 0, 0, height, null);
	}

	/**
	 * Repeats a sprite across a rectangle, clipped to it, stepping by the sprite's box the way
	 * the game tiles a sprite component.
	 */
	void tile(Graphics2D graphics, int id, int x, int y, int width, int height)
	{
		Sprite sprite = get(id);
		if (sprite == null || width <= 0 || height <= 0)
		{
			return;
		}

		Shape clip = graphics.getClip();
		graphics.clipRect(x, y, width, height);
		for (int tileY = y; tileY < y + height; tileY += sprite.maxHeight)
		{
			for (int tileX = x; tileX < x + width; tileX += sprite.maxWidth)
			{
				graphics.drawImage(sprite.image, tileX + sprite.offsetX, tileY + sprite.offsetY, null);
			}
		}
		graphics.setClip(clip);
	}

	/** The width of a sprite's box, or the fallback when it is missing. */
	int widthOf(int id, int fallback)
	{
		Sprite sprite = get(id);
		return sprite == null ? fallback : sprite.maxWidth;
	}

	/** The height of a sprite's box, or the fallback when it is missing. */
	int heightOf(int id, int fallback)
	{
		Sprite sprite = get(id);
		return sprite == null ? fallback : sprite.maxHeight;
	}
}
