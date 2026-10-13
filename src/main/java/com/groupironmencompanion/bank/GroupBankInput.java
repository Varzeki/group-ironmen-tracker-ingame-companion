package com.groupironmencompanion.bank;

import com.groupironmencompanion.ClientInputState;
import com.groupironmencompanion.GroupIronmenCompanionConfig;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextInput;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseWheelListener;

/**
 * Mouse and keyboard handling for the group bank window.
 * <p>
 * Input is only ever consumed while the window is open and the pointer is inside it, or on
 * the small Group tab. Every action it can take is client-side: opening and closing the
 * plugin's own overlay, switching tab, scrolling, and typing into the plugin's own search
 * box. No input is translated into anything the game sends to the server.
 */
@Singleton
public class GroupBankInput extends MouseAdapter implements MouseWheelListener
{
	private static final int SCROLL_ROWS = 3;

	@Inject
	private GroupBankViewer viewer;

	@Inject
	private GroupBankOverlay overlay;

	@Inject
	private GroupBankTabOverlay tabOverlay;

	@Inject
	private GroupIronmenCompanionConfig config;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private ClientInputState inputState;

	/** Set while the scrollbar is being dragged, so movement keeps scrolling after the press. */
	private boolean draggingScrollbar;

	/** The search prompt this window opened, so closing it never closes anyone else's. */
	private ChatboxTextInput searchInput;

	// ------------------------------------------------------------------
	// Mouse
	// ------------------------------------------------------------------

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (!config.bankViewer() || !inputState.isLoggedIn())
		{
			return event;
		}

		// Alt is RuneLite's overlay drag modifier; leave those clicks alone so the window
		// stays repositionable like any other overlay. A click while the game's right-click
		// menu is open is picking an option from it, which is drawn over the window.
		if (event.isAltDown() || inputState.isMenuOpen())
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
				closeAll();
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

		if (hits.scrollbar.contains(point))
		{
			draggingScrollbar = true;
			int row = overlay.scrollRowForY(point.y);
			clientThread.invoke(() -> viewer.setScrollRow(row));
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
				clientThread.invoke(() ->
				{
					viewer.setActiveTab(hit.tab);
					closeSearch();
				});
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
		return swallowInsideWindow(event);
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent event)
	{
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
		int row = overlay.scrollRowForY(event.getY());
		clientThread.invoke(() -> viewer.setScrollRow(row));
		event.consume();
		return event;
	}

	private MouseEvent swallowInsideWindow(MouseEvent event)
	{
		if (config.bankViewer() && viewer.isOpen() && !event.isAltDown() && inputState.isLoggedIn()
			&& !inputState.isMenuOpen() && overlay.getHits().window.contains(event.getPoint()))
		{
			event.consume();
		}
		return event;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
	{
		if (!config.bankViewer() || !viewer.isOpen() || !inputState.isLoggedIn())
		{
			return event;
		}
		// Anywhere in the window, not only over the grid: a turn over the title, the tabs or
		// the search box otherwise fell through to the game and zoomed the camera.
		if (!overlay.getHits().window.contains(event.getPoint()))
		{
			return event;
		}

		int step = event.getWheelRotation() > 0 ? SCROLL_ROWS : -SCROLL_ROWS;
		clientThread.invoke(() ->
		{
			int rows = Math.max(1, viewer.getRows().size());
			int maxScroll = Math.max(0, rows - GroupBankOverlay.VISIBLE_ROWS);
			viewer.setScrollRow(Math.max(0, Math.min(maxScroll, viewer.getScrollRow() + step)));
		});

		event.consume();
		return event;
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
		searchInput = chatboxPanelManager.openTextInput("Search group bank")
			.value(viewer.getFilter())
			.onChanged(value -> viewer.setFilter(value.trim()))
			.onClose(() -> viewer.setSearchFocused(false))
			.build();
	}

	/**
	 * Closes the search prompt if it is the thing currently open. The chatbox may have been
	 * taken over since, by a Withdraw-X amount, the bank's own search or another plugin's
	 * prompt, and closing that would throw away whatever the player was typing.
	 */
	private void closeSearch()
	{
		viewer.setSearchFocused(false);
		if (searchInput != null && chatboxPanelManager.getCurrentInput() == searchInput)
		{
			chatboxPanelManager.close();
		}
		searchInput = null;
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
	 * Opens the window, placing it only if the user has never moved it.
	 * <p>
	 * This is called from the AWT thread (the hotkey and the Group tab) as well as from game
	 * events, so all of it hops to the client thread. The viewer's state is only changed
	 * there, where it is drawn from, and working out where to put the window means reading
	 * the bank widget, which is only legal there. Doing that inline was why the hotkey and
	 * the tab once appeared to do nothing: the client call threw before the window opened.
	 * <p>
	 * A remembered position always wins. RuneLite persists it when you alt-drag the overlay
	 * and restores it on startup, so re-docking on every open would throw that away.
	 */
	public void openBesideBank()
	{
		clientThread.invoke(() ->
		{
			viewer.setOpen(true);

			if (overlay.getPreferredLocation() == null)
			{
				overlay.setPreferredLocation(tabOverlay.defaultLocation(
					GroupBankOverlay.WIDTH, overlay.getWindowSize().height));
			}
		});
	}
}
