package com.groupironmencompanion.bank;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.PanelComponent;

/**
 * A tooltip body that keeps the background colour it was given.
 * <p>
 * {@code TooltipOverlay} replaces the background of any {@link PanelComponent} tooltip with
 * the client's configured overlay colour, so setting one directly has no effect. Wrapping the
 * panel in a different type sidesteps that: the overlay sees something it does not recognise
 * as a panel, leaves it alone, and renders it as-is.
 * <p>
 * The colour it is given still follows RuneLite's overlay colour when the interface theme is
 * being matched (see {@code PanelBackground}); this only stops it being swapped for a more
 * transparent version that is hard to read over the bank.
 */
class TintedTooltip implements LayoutableRenderableEntity
{
	private final PanelComponent panel;

	TintedTooltip(PanelComponent panel)
	{
		this.panel = panel;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		return panel.render(graphics);
	}

	@Override
	public Rectangle getBounds()
	{
		return panel.getBounds();
	}

	@Override
	public void setPreferredLocation(Point position)
	{
		panel.setPreferredLocation(position);
	}

	@Override
	public void setPreferredSize(Dimension dimension)
	{
		panel.setPreferredSize(dimension);
	}
}
