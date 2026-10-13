package com.groupironmencompanion.bank;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.IndexDataBase;
import net.runelite.api.SpritePixels;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * The game's own interface sprites, so the group bank can be drawn in the real bank's chrome.
 * <p>
 * Sprites are looked up the way the game looks them up when it draws an interface: the
 * client's sprite overrides first, then the game cache. Overrides are how RuneLite's Interface
 * Styles and the Resource Packs plugin reskin interfaces, so going through them keeps this
 * window matching the bank beside it. The overrides map is only ever read, never written.
 * <p>
 * Themes change while the game runs. Resource Packs applies its sprites late, after an
 * async download and again on login, and gives no event anyone else can hear, so
 * {@link #refresh()} compares the override behind every cached sprite with the one now in
 * place and starts again on any difference. The bank's separator colour is read from the
 * live bank the same way, since a colour pack recolours those lines rather than a sprite.
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

	/**
	 * How much of a tint colour's saturation a recoloured icon takes on. Below 1 so the
	 * metal of the helmet still reads as metal rather than flat paint.
	 */
	private static final float TINT_SATURATION = 0.85f;

	/**
	 * Config groups whose changes can reskin interfaces: Resource Packs, Interface Styles,
	 * and RuneLite's own, which holds every plugin's on/off switch.
	 */
	private static final Set<String> THEME_GROUPS = new HashSet<>(Arrays.asList(
		"resourcepacks", "interfaceStyles", "runelite"));

	@Inject
	private Client client;

	@Inject
	private GroupIronmenCompanionConfig config;

	private final Map<Integer, Sprite> sprites = new HashMap<>();
	private final Map<Integer, Long> missing = new HashMap<>();

	/** Icons fitted to a size and optionally recoloured, built once each from the sprites above. */
	private final Map<Long, BufferedImage> icons = new HashMap<>();

	/** Bumped whenever cached sprites are thrown away, so composed images can be rebuilt. */
	private int version;

	/** Set from any thread when a theme setting changes; acted on by the next refresh. */
	private volatile boolean invalidated;

	/** Whether the cached sprites were loaded with overrides honoured. */
	private boolean themed = true;

	/** The client tick of the last refresh, so it only runs once however many overlays ask. */
	private int refreshedCycle = -1;

	/** The bank's separator colour, as last seen on the bank itself. */
	private int lineRgb = BankStyle.SEPARATOR.getRGB() & 0xFFFFFF;
	private Color lineColour = BankStyle.SEPARATOR;

	/** A loaded sprite: its trimmed pixels plus the box they are placed in. */
	static final class Sprite
	{
		final BufferedImage image;
		final int offsetX;
		final int offsetY;
		final int maxWidth;
		final int maxHeight;

		/** The override it was drawn from, or null if it came from the cache. */
		@Nullable
		final SpritePixels override;

		private Sprite(SpritePixels pixels, @Nullable SpritePixels override)
		{
			this.override = override;
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

		SpritePixels override = themed ? overrideFor(id) : null;
		SpritePixels pixels = override != null ? override : fromCache(id);
		if (pixels == null || pixels.getWidth() <= 0 || pixels.getHeight() <= 0)
		{
			missing.put(id, now);
			return null;
		}

		missing.remove(id);
		sprite = new Sprite(pixels, override);
		sprites.put(id, sprite);
		return sprite;
	}

	/** The override in place for a sprite, if any. Only ever read from the client's map. */
	@Nullable
	private SpritePixels overrideFor(int id)
	{
		Map<Integer, SpritePixels> overrides = client.getSpriteOverrides();
		return overrides == null ? null : overrides.get(id);
	}

	@Nullable
	private SpritePixels fromCache(int id)
	{
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
		icons.clear();
		version++;
	}

	// ------------------------------------------------------------------
	// Following the theme
	// ------------------------------------------------------------------

	/**
	 * Brings the cache up to date with the current theme. Call at the start of every render
	 * that uses these sprites; the work is done at most once per client tick.
	 * <p>
	 * The check is an identity comparison per cached sprite, a few dozen map lookups, which
	 * is cheap enough to do continuously and is the only way to notice a pack being applied.
	 */
	void refresh()
	{
		int cycle = client.getGameCycle();
		if (cycle == refreshedCycle)
		{
			return;
		}
		refreshedCycle = cycle;

		boolean followTheme = config.matchInterfaceTheme();
		if (invalidated || followTheme != themed || (followTheme && overridesChanged()))
		{
			invalidated = false;
			themed = followTheme;
			clear();
		}

		readLineColour(followTheme);
	}

	private boolean overridesChanged()
	{
		Map<Integer, SpritePixels> overrides = client.getSpriteOverrides();
		for (Map.Entry<Integer, Sprite> entry : sprites.entrySet())
		{
			SpritePixels current = overrides == null ? null : overrides.get(entry.getKey());
			if (current != entry.getValue().override)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Takes the separator colour from the bank's own line under its tabs while the bank is
	 * open, and keeps the last one seen for while it is not. A colour pack recolours these
	 * lines directly, so this is the only place its colour can be found.
	 */
	private void readLineColour(boolean followTheme)
	{
		int rgb = BankStyle.SEPARATOR.getRGB() & 0xFFFFFF;
		if (followTheme)
		{
			Widget line = client.getWidget(InterfaceID.Bankmain.TABS_LINE0);
			if (line == null || line.isHidden())
			{
				return;
			}
			rgb = line.getTextColor() & 0xFFFFFF;
		}

		if (rgb != lineRgb)
		{
			lineRgb = rgb;
			lineColour = new Color(rgb);
		}
	}

	/** The colour for separator lines, matching the bank's. */
	Color getLineColour()
	{
		return lineColour;
	}

	/**
	 * Notes a config change that might have reskinned the interface, so the next refresh
	 * reloads everything. Safe to call from any thread.
	 */
	public void onConfigChanged(String group)
	{
		if (THEME_GROUPS.contains(group))
		{
			invalidated = true;
		}
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
	 * Draws a sprite stretched across a wider box. Both ends are drawn as they are, the given
	 * number of pixels each, and the middle is stretched between them, so a bevelled corner
	 * keeps its shape. Only the width changes; a box no wider than the sprite draws it as is.
	 */
	void drawStretched(Graphics2D graphics, int id, int x, int y, int width, int endCap)
	{
		Sprite sprite = get(id);
		if (sprite == null)
		{
			return;
		}

		BufferedImage image = sprite.image;
		int imageWidth = image.getWidth();
		int imageHeight = image.getHeight();
		int extra = width - sprite.maxWidth;
		if (extra <= 0 || imageWidth <= endCap * 2)
		{
			draw(graphics, id, x, y);
			return;
		}

		int left = x + sprite.offsetX;
		int right = left + imageWidth + extra;
		int top = y + sprite.offsetY;
		int bottom = top + imageHeight;
		graphics.drawImage(image, left, top, left + endCap, bottom, 0, 0, endCap, imageHeight, null);
		graphics.drawImage(image, left + endCap, top, right - endCap, bottom,
			endCap, 0, imageWidth - endCap, imageHeight, null);
		graphics.drawImage(image, right - endCap, top, right, bottom,
			imageWidth - endCap, 0, imageWidth, imageHeight, null);
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

	/**
	 * Draws a box from eight pieces: four corners, and four edges tiled between them. The
	 * middle is left alone, so the caller fills it first.
	 */
	void drawNineSlice(Graphics2D graphics, int topLeft, int top, int topRight, int left, int right,
		int bottomLeft, int bottom, int bottomRight, int x, int y, int width, int height)
	{
		int topLeftWidth = widthOf(topLeft, 0);
		int topLeftHeight = heightOf(topLeft, 0);
		int topRightWidth = widthOf(topRight, 0);
		int topRightHeight = heightOf(topRight, 0);
		int bottomLeftWidth = widthOf(bottomLeft, 0);
		int bottomLeftHeight = heightOf(bottomLeft, 0);
		int bottomRightWidth = widthOf(bottomRight, 0);
		int bottomRightHeight = heightOf(bottomRight, 0);

		int topHeight = heightOf(top, 0);
		int bottomHeight = heightOf(bottom, 0);
		int leftWidth = widthOf(left, 0);
		int rightWidth = widthOf(right, 0);

		tile(graphics, top, x + topLeftWidth, y, width - topLeftWidth - topRightWidth, topHeight);
		tile(graphics, bottom, x + bottomLeftWidth, y + height - bottomHeight,
			width - bottomLeftWidth - bottomRightWidth, bottomHeight);
		tile(graphics, left, x, y + topLeftHeight, leftWidth, height - topLeftHeight - bottomLeftHeight);
		tile(graphics, right, x + width - rightWidth, y + topRightHeight,
			rightWidth, height - topRightHeight - bottomRightHeight);

		draw(graphics, topLeft, x, y);
		draw(graphics, topRight, x + width - topRightWidth, y);
		draw(graphics, bottomLeft, x, y + height - bottomLeftHeight);
		draw(graphics, bottomRight, x + width - bottomRightWidth, y + height - bottomRightHeight);
	}

	/**
	 * A sprite's pixels, recoloured to a tint if one is given and scaled down to fit a box if
	 * they are larger than it. Built once per sprite, tint and size, and rebuilt along with
	 * everything else when the theme changes; a member's new colour simply asks for another.
	 */
	@Nullable
	BufferedImage icon(int id, @Nullable Color tint, int maxWidth, int maxHeight)
	{
		Sprite sprite = get(id);
		if (sprite == null)
		{
			return null;
		}

		long key = ((long) id << 42) | ((tint == null ? 0L : 1L) << 41)
			| ((long) (tint == null ? 0 : tint.getRGB() & 0xFFFFFF) << 17)
			| ((long) (maxWidth & 0xFF) << 9) | (maxHeight & 0x1FF);
		BufferedImage icon = icons.get(key);
		if (icon == null)
		{
			icon = fit(tint == null ? sprite.image : tint(sprite.image, tint), maxWidth, maxHeight);
			icons.put(key, icon);
		}
		return icon;
	}

	/**
	 * Recolours an image to a tint while keeping its shading: every opaque pixel takes the
	 * tint's hue and at least most of its saturation, and keeps its own brightness. Grey metal
	 * takes the colour, highlights stay light and the black outline stays black.
	 */
	private static BufferedImage tint(BufferedImage source, Color tint)
	{
		float[] target = Color.RGBtoHSB(tint.getRed(), tint.getGreen(), tint.getBlue(), null);
		float saturation = target[1] * TINT_SATURATION;
		float[] hsb = new float[3];

		BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < source.getHeight(); y++)
		{
			for (int x = 0; x < source.getWidth(); x++)
			{
				int argb = source.getRGB(x, y);
				int alpha = argb >>> 24;
				if (alpha == 0)
				{
					continue;
				}
				Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, hsb);
				int rgb = Color.HSBtoRGB(target[0], Math.max(hsb[1], saturation), hsb[2]);
				result.setRGB(x, y, (alpha << 24) | (rgb & 0xFFFFFF));
			}
		}
		return result;
	}

	/** Scales an image down, keeping its proportions, if it is larger than the box. */
	private static BufferedImage fit(BufferedImage image, int maxWidth, int maxHeight)
	{
		double scale = Math.min((double) maxWidth / image.getWidth(), (double) maxHeight / image.getHeight());
		if (scale >= 1)
		{
			return image;
		}

		int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
		BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = scaled.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.drawImage(image, 0, 0, width, height, null);
		}
		finally
		{
			graphics.dispose();
		}
		return scaled;
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
