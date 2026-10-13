package com.groupironmencompanion.map;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.util.Locale;
import net.runelite.api.Point;

/**
 * Draws the world map markers. Everything is generated at runtime so the plugin ships no
 * image assets and the colours follow whatever the user has configured.
 */
final class MarkerImages
{
	private static final int WIDTH = 18;
	private static final int HEIGHT = 24;

	/**
	 * The tip of the pin, the point of the image that belongs on the member's tile. Without
	 * it the world map centres the image there, which puts the tip half a pin too low.
	 */
	static final Point TIP = new Point(WIDTH / 2, HEIGHT - 1);

	private MarkerImages()
	{
	}

	/** A solid pin with the member's initial, used for a member's true position. */
	static BufferedImage pin(Color colour, String memberName)
	{
		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			GeneralPath body = teardrop();

			g.setColor(new Color(0, 0, 0, 140));
			g.setStroke(new BasicStroke(3f));
			g.draw(body);

			g.setColor(colour);
			g.fill(body);

			g.setColor(Color.BLACK);
			g.setStroke(new BasicStroke(1f));
			g.draw(body);

			drawInitial(g, colour, memberName);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	/**
	 * A hollow ring with a downward chevron, used at a dungeon entrance to say "this member
	 * is somewhere below here" rather than "this member is standing here".
	 */
	static BufferedImage entrance(Color colour, String memberName)
	{
		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			GeneralPath body = teardrop();

			g.setColor(new Color(0, 0, 0, 140));
			g.setStroke(new BasicStroke(3f));
			g.draw(body);

			g.setColor(new Color(20, 20, 20, 190));
			g.fill(body);

			g.setColor(colour);
			g.setStroke(new BasicStroke(2f));
			g.draw(body);

			// Downward chevron, marking that they are on a layer below this point.
			g.setStroke(new BasicStroke(2f));
			g.drawLine(WIDTH / 2 - 3, 7, WIDTH / 2, 11);
			g.drawLine(WIDTH / 2 + 3, 7, WIDTH / 2, 11);

			drawInitial(g, colour, memberName);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	private static GeneralPath teardrop()
	{
		GeneralPath path = new GeneralPath();
		Ellipse2D head = new Ellipse2D.Float(1f, 1f, WIDTH - 2f, WIDTH - 2f);
		path.append(head, false);

		GeneralPath tail = new GeneralPath();
		tail.moveTo(WIDTH / 2f - 4f, WIDTH - 4f);
		tail.lineTo(WIDTH / 2f, HEIGHT - 1f);
		tail.lineTo(WIDTH / 2f + 4f, WIDTH - 4f);
		tail.closePath();
		path.append(tail, false);

		return path;
	}

	private static void drawInitial(Graphics2D g, Color colour, String memberName)
	{
		if (memberName == null || memberName.isEmpty())
		{
			return;
		}
		String initial = memberName.substring(0, 1).toUpperCase(Locale.ROOT);

		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
		int textWidth = g.getFontMetrics().stringWidth(initial);
		int x = (WIDTH - textWidth) / 2;
		int y = WIDTH / 2 + 4;

		g.setColor(contrasting(colour));
		g.drawString(initial, x, y);
	}

	/** Black on light markers, white on dark ones, so the initial stays readable. */
	private static Color contrasting(Color colour)
	{
		double luminance = (0.299 * colour.getRed() + 0.587 * colour.getGreen() + 0.114 * colour.getBlue()) / 255.0;
		return luminance > 0.55 ? Color.BLACK : Color.WHITE;
	}
}
