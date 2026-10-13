package com.groupironmencompanion;

import com.groupironmencompanion.api.GroupApi;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * Detects whether the official Group Ironmen Tracker plugin is installed, running and set
 * up to upload.
 * <p>
 * When it is, this plugin stays purely a reader: the tracker owns uploading and we would
 * only duplicate its traffic. When it is not, our own uploader takes over so the plugin
 * works standalone.
 * <p>
 * The check compares plugin class names, which is plain {@code Object.getClass()} and not
 * reflection: nothing is loaded, invoked or made accessible.
 * <p>
 * The answer is cached briefly because it is consulted several times per game tick and
 * scanning the whole plugin list that often is wasteful. The window is short enough that
 * enabling or disabling the tracker plugin takes effect almost immediately.
 */
@Singleton
public class TrackerDetector
{
	private static final String TRACKER_CLASS = "men.groupiron.GroupIronmenTrackerPlugin";
	private static final long CACHE_MILLIS = 5000;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private GroupApi api;

	private volatile long checkedAt;
	private volatile boolean active;
	private volatile boolean installed;

	/**
	 * True when the official tracker plugin is running and has a group name and token, which
	 * is when it uploads. Enabled is not enough: a plugin that failed to start, or one with
	 * no credentials, uploads nothing, and then this plugin has to.
	 */
	public boolean isTrackerActive()
	{
		refreshIfStale();
		return active;
	}

	/** True when the tracker plugin is installed, whether or not it is currently enabled. */
	public boolean isTrackerInstalled()
	{
		refreshIfStale();
		return installed;
	}

	/** Forces the next query to re-scan, used when plugin state is known to have changed. */
	public void invalidate()
	{
		checkedAt = 0;
	}

	private void refreshIfStale()
	{
		long now = System.currentTimeMillis();
		if (now - checkedAt < CACHE_MILLIS)
		{
			return;
		}

		boolean foundInstalled = false;
		boolean foundActive = false;
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (TRACKER_CLASS.equals(plugin.getClass().getName()))
			{
				foundInstalled = true;
				foundActive = pluginManager.isPluginActive(plugin) && api.trackerHasCredentials();
				break;
			}
		}

		installed = foundInstalled;
		active = foundActive;
		checkedAt = now;
	}
}
