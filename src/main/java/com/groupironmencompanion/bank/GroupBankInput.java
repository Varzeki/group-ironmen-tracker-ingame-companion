package com.groupironmencompanion.bank;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Mouse and keyboard handling for the group bank window.
 * <p>
 * Input is only ever consumed while the window is open and the pointer is inside it, or on
 * the small Group tab. Every action it can take is client-side: opening, closing and
 * resizing the plugin's own overlay, switching tab, scrolling, and typing into the plugin's
 * own search box. No input is translated into anything the game sends to the server.
 */
@Singleton
public class GroupBankInput extends MouseAdapter implements MouseWheelListener
{
	private static final int SCROLL_ROWS = 3;

	/**
	 * How far in from the window's edge a press starts a resize: the width of the frame's
	 * border, so the border itself is the handle and nothing inside it is covered.
	 */
	private static final int RESIZE_MARGIN = 6;

	private static final int EDGE_LEFT = 1;
	private static final int EDGE_RIGHT = 2;
	private static final int EDGE_TOP = 4;
	private static final int EDGE_BOTTOM = 8;

	/** The edges being dragged, or 0 when not resizing. */
	private int resizeEdges;

	/** The pointer, window and stored location when the resize started. */
	private java.awt.Point resizeStartPoint;
	private Rectangle resizeStartWindow;
	private java.awt.Point resizeStartLocation;

	/** Whether the cursor currently shows a resize arrow that this handler put there. */
	private boolean resizeCursorShown;

	@Inject
	private GroupBankViewer viewer;

	@Inject
	private GroupBankOverlay overlay;

	@Inject
	private GroupBankTabOverlay tabOverlay;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClientUI clientUI;

	@Inject
	private GroupIronmenCompanionConfig config;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	/** Set while the scrollbar is being dragged, so movement keeps scrolling after the press. */
	private boolean draggingScrollbar;

	// ------------------------------------------------------------------
	// Mouse
	// ------------------------------------------------------------------

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (!config.bankViewer())
		{
			return event;
		}

		// Alt is RuneLite's overlay drag modifier; leave those clicks alone so the window
		// stays repositionable like any other overlay.
		if (event.isAltDown())
		{
			return event;
		}

		java.awt.Point point = event.getPoint();

		Rectangle tab = tabOverlay.getHit();
		if (tab != null && tab.contains(point))
		{
			if (!viewer.isOpen())
			{
				openBesideBank();
			}
			else
			{
				viewer.setOpen(false);
			}
			event.consume();
			return event;
		}

		if (!viewer.isOpen())
		{
			return event;
		}

		// One snapshot for the whole decision, so every test below sees the same frame.
		GroupBankOverlay.HitBoxes hits = overlay.getHits();

		if (!hits.window.contains(point))
		{
			// A click elsewhere dismisses the search prompt but still reaches the game.
			clientThread.invoke(this::closeSearch);
			return event;
		}

		// Only the left button resizes, so a middle-button camera drag that happens to start
		// on the border still turns the camera.
		int edges = SwingUtilities.isLeftMouseButton(event) ? edgesAt(hits.window, point) : 0;
		if (edges != 0)
		{
			startResize(edges, point, hits.window);
			event.consume();
			return event;
		}

		if (hits.scrollbar.contains(point))
		{
			draggingScrollbar = true;
			viewer.setScrollRow(overlay.scrollRowForY(point.y));
			event.consume();
			return event;
		}

		if (hits.scrollUp.contains(point) || hits.scrollDown.contains(point))
		{
			int rows = hits.scrollUp.contains(point) ? -1 : 1;
			clientThread.invoke(() -> scrollBy(rows));
			event.consume();
			return event;
		}

		if (hits.close.contains(point))
		{
			clientThread.invoke(() ->
			{
				closeSearch();
				viewer.setOpen(false);
			});
			event.consume();
			return event;
		}

		for (GroupBankOverlay.TabHit hit : hits.tabs)
		{
			if (hit.bounds.contains(point))
			{
				viewer.setActiveTab(hit.tab);
				clientThread.invoke(this::closeSearch);
				event.consume();
				return event;
			}
		}

		boolean onSearchBox = hits.search.contains(point);
		clientThread.invoke(() ->
		{
			if (onSearchBox)
			{
				openSearch();
			}
			else
			{
				closeSearch();
			}
		});

		// Swallow anything else inside the window so clicks do not fall through to the game.
		event.consume();
		return event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		return swallowInsideWindow(event);
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		draggingScrollbar = false;

		// A resize only ever starts from a left press this handler consumed, so only that
		// button's release ends it and is consumed with it.
		if (resizeEdges != 0 && SwingUtilities.isLeftMouseButton(event))
		{
			finishResize(event.getPoint());
			event.consume();
			return event;
		}
		return swallowInsideWindow(event);
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent event)
	{
		if (resizeEdges != 0)
		{
			resizeTo(event.getPoint());
			event.consume();
			return event;
		}

		if (!draggingScrollbar)
		{
			return event;
		}
		if (!config.bankViewer() || !viewer.isOpen())
		{
			draggingScrollbar = false;
			return event;
		}

		// Track the pointer even once it leaves the bar, which is how scrollbars behave
		// everywhere else; the row is clamped, so dragging past either end just pins it.
		viewer.setScrollRow(overlay.scrollRowForY(event.getY()));
		event.consume();
		return event;
	}

	private MouseEvent swallowInsideWindow(MouseEvent event)
	{
		if (config.bankViewer() && viewer.isOpen() && !event.isAltDown()
			&& overlay.getHits().window.contains(event.getPoint()))
		{
			event.consume();
		}
		return event;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
	{
		if (!config.bankViewer() || !viewer.isOpen())
		{
			return event;
		}
		if (!overlay.getHits().grid.contains(event.getPoint()))
		{
			return event;
		}

		int maxScroll = overlay.maxScrollRow();
		int next = viewer.getScrollRow() + (event.getWheelRotation() > 0 ? SCROLL_ROWS : -SCROLL_ROWS);
		viewer.setScrollRow(Math.max(0, Math.min(maxScroll, next)));

		event.consume();
		return event;
	}

	/** Scrolls by whole rows, as the scrollbar's arrows do in the bank. Client thread only. */
	private void scrollBy(int rows)
	{
		viewer.setScrollRow(Math.max(0, Math.min(overlay.maxScrollRow(), viewer.getScrollRow() + rows)));
	}

	// ------------------------------------------------------------------
	// Resizing
	// ------------------------------------------------------------------

	/**
	 * Shows a resize arrow while the pointer is over the window's border, as RuneLite does
	 * for its own resizable overlays. Only a cursor this handler set is ever put back, so a
	 * custom cursor from another plugin is left alone the rest of the time.
	 */
	@Override
	public MouseEvent mouseMoved(MouseEvent event)
	{
		if (resizeEdges == 0)
		{
			boolean enabled = config.bankViewer() && viewer.isOpen() && !event.isAltDown();
			showResizeCursor(enabled ? edgesAt(overlay.getHits().window, event.getPoint()) : 0);
		}
		return event;
	}

	/**
	 * Which of the window's edges a point is on, as flags, or 0 for none. Holding Alt is left
	 * to RuneLite, which moves the window instead.
	 */
	private static int edgesAt(Rectangle window, java.awt.Point point)
	{
		if (!window.contains(point))
		{
			return 0;
		}

		int edges = 0;
		if (point.x < window.x + RESIZE_MARGIN)
		{
			edges |= EDGE_LEFT;
		}
		else if (point.x >= window.x + window.width - RESIZE_MARGIN)
		{
			edges |= EDGE_RIGHT;
		}
		if (point.y < window.y + RESIZE_MARGIN)
		{
			edges |= EDGE_TOP;
		}
		else if (point.y >= window.y + window.height - RESIZE_MARGIN)
		{
			edges |= EDGE_BOTTOM;
		}
		return edges;
	}

	private void startResize(int edges, java.awt.Point point, Rectangle window)
	{
		resizeEdges = edges;
		resizeStartPoint = point;
		resizeStartWindow = new Rectangle(window);
		java.awt.Point location = overlay.getPreferredLocation();
		resizeStartLocation = location != null ? new java.awt.Point(location) : window.getLocation();
		showResizeCursor(edges);
	}

	/**
	 * Resizes to follow the pointer to the pixel, within the window's minimum and the canvas.
	 * Dragging the left or top edge moves the window by however much the size changed, so the
	 * opposite edge stays exactly where it was even when the size stops at a limit.
	 */
	private void resizeTo(java.awt.Point point)
	{
		int dx = point.x - resizeStartPoint.x;
		int dy = point.y - resizeStartPoint.y;
		Rectangle start = resizeStartWindow;

		int width = start.width
			+ ((resizeEdges & EDGE_RIGHT) != 0 ? dx : 0)
			- ((resizeEdges & EDGE_LEFT) != 0 ? dx : 0);
		int height = start.height
			+ ((resizeEdges & EDGE_BOTTOM) != 0 ? dy : 0)
			- ((resizeEdges & EDGE_TOP) != 0 ? dy : 0);
		java.awt.Dimension size = overlay.clampSize(width, height);

		overlay.setPreferredSize(size);
		if ((resizeEdges & (EDGE_LEFT | EDGE_TOP)) != 0)
		{
			int moveX = (resizeEdges & EDGE_LEFT) != 0 ? start.width - size.width : 0;
			int moveY = (resizeEdges & EDGE_TOP) != 0 ? start.height - size.height : 0;
			overlay.setPreferredLocation(new java.awt.Point(
				resizeStartLocation.x + moveX, resizeStartLocation.y + moveY));
		}
	}

	/** Ends a resize and stores the size, the way RuneLite stores an overlay it resized. */
	private void finishResize(java.awt.Point point)
	{
		resizeTo(point);
		resizeEdges = 0;
		overlayManager.saveOverlay(overlay);
		showResizeCursor(edgesAt(overlay.getHits().window, point));
	}

	private void showResizeCursor(int edges)
	{
		if (edges != 0)
		{
			clientUI.setCursor(Cursor.getPredefinedCursor(cursorFor(edges)));
			resizeCursorShown = true;
		}
		else if (resizeCursorShown)
		{
			clientUI.setCursor(clientUI.getDefaultCursor());
			resizeCursorShown = false;
		}
	}

	private static int cursorFor(int edges)
	{
		switch (edges)
		{
			case EDGE_LEFT | EDGE_TOP:
				return Cursor.NW_RESIZE_CURSOR;
			case EDGE_RIGHT | EDGE_TOP:
				return Cursor.NE_RESIZE_CURSOR;
			case EDGE_LEFT | EDGE_BOTTOM:
				return Cursor.SW_RESIZE_CURSOR;
			case EDGE_RIGHT | EDGE_BOTTOM:
				return Cursor.SE_RESIZE_CURSOR;
			case EDGE_LEFT:
				return Cursor.W_RESIZE_CURSOR;
			case EDGE_RIGHT:
				return Cursor.E_RESIZE_CURSOR;
			case EDGE_TOP:
				return Cursor.N_RESIZE_CURSOR;
			default:
				return Cursor.S_RESIZE_CURSOR;
		}
	}

	// ------------------------------------------------------------------
	// Searching
	// ------------------------------------------------------------------

	/**
	 * Opens the game's own chatbox text input for the search box.
	 * <p>
	 * Capturing keystrokes directly does not work here. RuneLite's key remapping consumes
	 * W, A, S and D for camera movement before any other listener sees them, and KeyManager
	 * dispatches in registration order with no way to get in front of it. Anyone using WASD
	 * camera could not type those letters into the search.
	 * <p>
	 * Going through the chatbox solves it at the source: key remapping already stands down
	 * while the chatbox is taking input, so every letter arrives, and camera keys keep
	 * working normally the rest of the time because nothing is being intercepted at all.
	 */
	private void openSearch()
	{
		viewer.setSearchFocused(true);
		chatboxPanelManager.openTextInput("Search group bank")
			.value(viewer.getFilter())
			.onChanged(value -> viewer.setFilter(value.trim()))
			.onClose(() -> viewer.setSearchFocused(false))
			.build();
	}

	/** Closes the search prompt if it is the thing currently open. */
	private void closeSearch()
	{
		if (viewer.isSearchFocused())
		{
			viewer.setSearchFocused(false);
			chatboxPanelManager.close();
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/**
	 * Closes the window and any search prompt with it. Safe to call from any thread, so the
	 * hotkey and the game events that dismiss the window can share one path.
	 */
	public void closeAll()
	{
		clientThread.invoke(() ->
		{
			closeSearch();
			viewer.setOpen(false);
		});
	}

	/**
	 * Opens the window. Where it goes is decided as it is drawn: a window with no stored
	 * position is placed beside the bank then (see {@link GroupBankOverlay}), which is on the
	 * client thread where the bank widget can be read. Doing that inline here was once why
	 * the hotkey and the tab appeared to do nothing, since this is called from the AWT thread.
	 * <p>
	 * A remembered position always wins. RuneLite persists it when you alt-drag the overlay
	 * and restores it on startup, so re-docking on every open would throw that away.
	 */
	public void openBesideBank()
	{
		viewer.setOpen(true);
	}
}
