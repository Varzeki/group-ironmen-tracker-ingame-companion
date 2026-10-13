package com.groupironmencompanion.bank;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.JagexColors;

/**
 * Text drawn the way the game draws it, for the parts of the plugin that stand in for game
 * interfaces: the group bank window and its Group tab.
 * <p>
 * The game's interface text is a 1-bit bitmap font with a 1px black drop shadow. RuneLite
 * ships the same fonts as {@link FontManager}'s RuneScape fonts, but overlays are drawn with
 * antialiasing on, which gives every glyph a grey smoothed edge that the game's own text never
 * has. Turning text antialiasing off for these overlays is what makes them read as part of the
 * game. {@code OverlayRenderer} restores the rendering hints after each overlay, so this never
 * leaks into anyone else's drawing.
 */
final class GameText
{
	/** The game's small font, used for stack sizes and footers. */
	static final Font SMALL = FontManager.getRunescapeSmallFont();

	/** The game's regular font, used for buttons, tabs and body text. */
	static final Font REGULAR = FontManager.getRunescapeFont();

	/** The game's bold font, used for interface titles. */
	static final Font BOLD = FontManager.getRunescapeBoldFont();

	/**
	 * The small font and progressively smaller sizes of it, largest first, for labels that
	 * must fit a fixed space such as a bank tab tile. The RuneScape fonts are drawn for one
	 * size, so the smaller ones are only used when a label would not fit otherwise.
	 */
	static final Font[] LABEL_SIZES = {
		SMALL, SMALL.deriveFont(14f), SMALL.deriveFont(13f), SMALL.deriveFont(12f), SMALL.deriveFont(11f),
	};

	/** The orange the game uses for interface titles, buttons and labels ({@code 0xff981f}). */
	static final Color ORANGE = JagexColors.DARK_ORANGE_INTERFACE_TEXT;

	/** What interface text turns to while the mouse is over it. */
	static final Color HOVER = Color.WHITE;

	private static final Color SHADOW = Color.BLACK;

	private GameText()
	{
	}

	/**
	 * Switches off text antialiasing, so the bitmap fonts render pixel for pixel like the
	 * game's. Call once at the start of an overlay's render.
	 */
	static void prepare(Graphics2D graphics)
	{
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
	}

	/** Draws text with the game's 1px black shadow below and to the right. */
	static void drawShadowed(Graphics2D graphics, String text, int x, int y, Color colour)
	{
		graphics.setColor(SHADOW);
		graphics.drawString(text, x + 1, y + 1);
		graphics.setColor(colour);
		graphics.drawString(text, x, y);
	}

	/** The baseline that centres a line of text vertically in a box of the given height. */
	static int centredBaseline(FontMetrics metrics, int top, int height)
	{
		return top + (height - metrics.getAscent() - metrics.getDescent()) / 2 + metrics.getAscent();
	}
}
