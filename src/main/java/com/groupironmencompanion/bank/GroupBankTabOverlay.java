package com.groupironmencompanion.bank;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A small "Group" tab drawn alongside the bank while it is open, so the group bank viewer is
 * one click away without having to remember the hotkey.
 * <p>
 * The tab is drawn by the plugin outside the bank window's own rectangle. No widget is
 * created, reparented, resized or unhidden, and no menu entry is added: clicking it only
 * toggles a plugin overlay, so nothing is ever sent to the server.
 */
@Singleton
public class GroupBankTabOverlay extends Overlay
{
	private static final int TAB_WIDTH = 52;
	private static final int TAB_HEIGHT = 22;
	private static final int GAP = 3;

	/**
	 * Keeps the viewer clear of the top-left corner, where the client draws the click-to-move
	 * and menu-target text that would otherwise sit on top of it.
	 */
	private static final int TOP_MARGIN = 24;
	private static final int SIDE_MARGIN = 4;

	private static final BasicStroke BORDER_STROKE = new BasicStroke(1f);

	@Inject
	private Client client;

	@Inject
	private GroupBankViewer viewer;

	@Inject
	private GroupIronmenCompanionConfig config;

	/** Where the tab was last drawn, in canvas coordinates, or null when it is not shown. */
	private Rectangle hit;

	@Inject
	public GroupBankTabOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		hit = null;

		if (!config.bankViewer() || !config.bankShowTab())
		{
			return null;
		}

		Rectangle bankBounds = bankBounds();
		if (bankBounds == null)
		{
			return null;
		}

		Rectangle tab = place(bankBounds);
		hit = tab;

		Point mouse = client.getMouseCanvasPosition();
		boolean hovered = mouse != null && tab.contains(mouse.getX(), mouse.getY());
		boolean active = viewer.isOpen();

		graphics.setColor(active ? BankStyle.TAB_ACTIVE : BankStyle.TITLE_BACKGROUND);
		graphics.fillRect(tab.x, tab.y, tab.width, tab.height);

		if (hovered)
		{
			graphics.setColor(BankStyle.HOVER);
			graphics.fillRect(tab.x, tab.y, tab.width, tab.height);
		}

		graphics.setColor(BankStyle.BORDER);
		graphics.setStroke(BORDER_STROKE);
		graphics.drawRect(tab.x, tab.y, tab.width - 1, tab.height - 1);

		GameText.prepare(graphics);
		graphics.setFont(GameText.REGULAR);
		FontMetrics metrics = graphics.getFontMetrics();
		int textWidth = metrics.stringWidth("Group");
		GameText.drawShadowed(graphics, "Group", tab.x + (tab.width - textWidth) / 2,
			GameText.centredBaseline(metrics, tab.y, tab.height),
			active || hovered ? GameText.HOVER : GameText.ORANGE);

		return null;
	}

	/**
	 * Sits the tab just outside the bank window, on whichever side has room, so it never
	 * covers any part of the bank.
	 */
	private Rectangle place(Rectangle bank)
	{
		int y = bank.y + 4;

		int right = bank.x + bank.width + GAP;
		if (right + TAB_WIDTH <= client.getCanvasWidth())
		{
			return new Rectangle(right, y, TAB_WIDTH, TAB_HEIGHT);
		}

		int left = bank.x - GAP - TAB_WIDTH;
		if (left >= 0)
		{
			return new Rectangle(left, y, TAB_WIDTH, TAB_HEIGHT);
		}

		// Nowhere beside it fits, so sit directly above the bank instead.
		return new Rectangle(bank.x, Math.max(0, bank.y - GAP - TAB_HEIGHT), TAB_WIDTH, TAB_HEIGHT);
	}

	@Nullable
	private Rectangle bankBounds()
	{
		Widget bank = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (bank == null || bank.isHidden())
		{
			return null;
		}
		Rectangle bounds = bank.getBounds();
		return bounds == null || bounds.width <= 0 ? null : bounds;
	}

	/** The tab's canvas rectangle, or null when it is not currently drawn. */
	@Nullable
	Rectangle getHit()
	{
		return hit;
	}

	/**
	 * Where the viewer should appear the very first time it is opened, before the user has
	 * dragged it anywhere. Prefers the left of the bank, and pins to the left edge of the
	 * canvas when the bank is too wide to sit beside, which at least puts it somewhere
	 * predictable and out of the way of the bank's own controls.
	 * <p>
	 * Reads the bank widget, so this must be called on the client thread.
	 */
	java.awt.Point defaultLocation(int windowWidth, int windowHeight)
	{
		int canvasWidth = client.getCanvasWidth();
		int canvasHeight = client.getCanvasHeight();
		Rectangle bank = bankBounds();

		if (bank == null)
		{
			return clamp((canvasWidth - windowWidth) / 2, (canvasHeight - windowHeight) / 2,
				windowWidth, windowHeight, canvasWidth, canvasHeight);
		}

		int left = bank.x - GAP - windowWidth;
		int right = bank.x + bank.width + GAP;

		int x;
		if (left >= SIDE_MARGIN)
		{
			x = left;
		}
		else if (right + windowWidth + SIDE_MARGIN <= canvasWidth)
		{
			x = right;
		}
		else
		{
			// Neither side fits, which is normal in fixed mode where the bank fills the
			// screen. Pin to the left edge; the position is remembered once moved.
			x = SIDE_MARGIN;
		}

		return clamp(x, bank.y, windowWidth, windowHeight, canvasWidth, canvasHeight);
	}

	/** True while the player's own bank interface is open. Client thread only. */
	boolean isBankOpen()
	{
		return bankBounds() != null;
	}

	/** Keeps the window fully on the canvas and out of the top-left text area. */
	private static java.awt.Point clamp(int x, int y, int width, int height, int canvasWidth, int canvasHeight)
	{
		int clampedX = Math.max(SIDE_MARGIN, Math.min(x, canvasWidth - width - SIDE_MARGIN));
		int clampedY = Math.max(TOP_MARGIN, Math.min(y, canvasHeight - height - SIDE_MARGIN));
		return new java.awt.Point(clampedX, clampedY);
	}
}
