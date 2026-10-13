package com.groupironmencompanion;

import javax.inject.Singleton;

/**
 * What the mouse handlers need to know about the client, recorded on the client thread.
 * <p>
 * The handlers run on AWT, where calling into the client is not allowed, so the plugin
 * copies these here every client tick and the handlers read the copy. Being a tick behind
 * is harmless: nothing a click depends on changes faster than that.
 */
@Singleton
public class ClientInputState
{
	private volatile boolean menuOpen;

	/**
	 * True while the game's right-click menu is open. A click then belongs to the menu, even
	 * over one of the plugin's windows, because the menu is drawn on top of them.
	 */
	public boolean isMenuOpen()
	{
		return menuOpen;
	}

	void setMenuOpen(boolean menuOpen)
	{
		this.menuOpen = menuOpen;
	}
}
