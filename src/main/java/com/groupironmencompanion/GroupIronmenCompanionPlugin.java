package com.groupironmencompanion;

import com.google.inject.Provides;
import com.groupironmencompanion.api.GroupApi;
import com.groupironmencompanion.api.MemberDto;
import com.groupironmencompanion.bank.GroupBankInput;
import com.groupironmencompanion.bank.GroupBankOverlay;
import com.groupironmencompanion.bank.GroupBankTabOverlay;
import com.groupironmencompanion.bank.GroupBankViewer;
import com.groupironmencompanion.data.GroupState;
import com.groupironmencompanion.map.GroupMapInput;
import com.groupironmencompanion.map.GroupMapMarkers;
import com.groupironmencompanion.map.GroupMapOverlay;
import com.groupironmencompanion.overlay.GroupStatusOverlay;
import com.groupironmencompanion.overlay.SkillHoverOverlay;
import com.groupironmencompanion.route.RouteTracker;
import com.groupironmencompanion.ui.GroupPanel;
import com.groupironmencompanion.upload.GroupUploader;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;

/**
 * Brings the groupiron.men group ironman dashboard into the client.
 * <p>
 * <b>What this plugin does:</b> it reads the data your group already publishes to your
 * groupiron.men group (or your own self-hosted instance) and displays it in the client: a
 * read-only group bank, group levels on the Skills tab, member markers on the world map, and
 * a vitals panel. When the official Group Ironmen Tracker plugin is not running it also
 * publishes your own character data in the same format, so the plugin works standalone.
 * <p>
 * <b>What it deliberately does not do:</b> it adds no menu entries of any kind, so nothing
 * it draws can cause an action to be sent to the server. It never creates, modifies, hides,
 * unhides, reparents, moves or resizes any game widget or click zone; every view it provides
 * is its own drawing. It draws nothing in the game scene and nothing on the minimap, and it
 * offers no combat information about anyone outside your own opted-in group.
 */
@Slf4j
@PluginDescriptor(
	name = "Group Ironmen Tracker In-Game Companion",
	description = "View your group ironman team's banks, skills, stats and map positions in-game",
	tags = {"group", "ironman", "gim", "bank", "team", "map", "skills"}
)
public class GroupIronmenCompanionPlugin extends Plugin
{
	/** Game ticks between periodic world map marker rebuilds, about six seconds. */
	private static final int MAP_REFRESH_TICKS = 10;


	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private GroupIronmenCompanionConfig config;

	@Inject
	private GroupApi api;

	@Inject
	private GroupState groupState;

	@Inject
	private GroupUploader uploader;

	@Inject
	private TrackerDetector trackerDetector;

	@Inject
	private GroupMapMarkers mapMarkers;

	@Inject
	private GroupMapOverlay mapOverlay;

	@Inject
	private GroupMapInput mapInput;

	@Inject
	private RouteTracker routeTracker;

	@Inject
	private GroupBankViewer bankViewer;

	@Inject
	private GroupBankOverlay bankOverlay;

	@Inject
	private GroupBankTabOverlay bankTabOverlay;

	@Inject
	private GroupBankInput bankInput;

	@Inject
	private SkillHoverOverlay skillHoverOverlay;

	@Inject
	private GroupStatusOverlay statusOverlay;

	private GroupPanel panel;
	private NavigationButton navButton;

	/**
	 * Polling runs on the plugin's own thread rather than RuneLite's shared executor: the
	 * work is blocking network I/O, and a slow server should never delay another plugin's
	 * scheduled tasks.
	 */
	private ScheduledExecutorService pollExecutor;
	private ScheduledFuture<?> pollTask;

	private final Runnable stateListener = this::onGroupStateChanged;
	private volatile String localPlayerName;
	private volatile long lastPollMillis;
	private boolean varsChanged;
	private int ticksSinceMapRefresh;

	private final HotkeyListener bankHotkey = new HotkeyListener(() -> config.bankHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			if (config.bankViewer())
			{
				if (bankViewer.isOpen())
				{
					bankInput.closeAll();
				}
				else
				{
					bankInput.openBesideBank();
				}
			}
		}
	};

	@Provides
	GroupIronmenCompanionConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GroupIronmenCompanionConfig.class);
	}

	// ------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------

	@Override
	protected void startUp()
	{
		applyPalette();
		groupState.addListener(stateListener);

		overlayManager.add(bankOverlay);
		overlayManager.add(bankTabOverlay);
		overlayManager.add(skillHoverOverlay);
		overlayManager.add(statusOverlay);
		overlayManager.add(mapOverlay);

		keyManager.registerKeyListener(bankHotkey);
		mouseManager.registerMouseListener(bankInput);
		mouseManager.registerMouseWheelListener(bankInput);
		mouseManager.registerMouseListener(mapInput);

		if (config.showSidePanel())
		{
			addSidePanel();
		}

		lastPollMillis = 0;
		pollExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "group-ironmen-companion-poll");
			thread.setDaemon(true);
			return thread;
		});
		pollTask = pollExecutor.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		if (pollTask != null)
		{
			pollTask.cancel(false);
			pollTask = null;
		}
		if (pollExecutor != null)
		{
			pollExecutor.shutdownNow();
			pollExecutor = null;
		}

		groupState.removeListener(stateListener);

		overlayManager.remove(bankOverlay);
		overlayManager.remove(bankTabOverlay);
		overlayManager.remove(skillHoverOverlay);
		overlayManager.remove(statusOverlay);
		overlayManager.remove(mapOverlay);

		keyManager.unregisterKeyListener(bankHotkey);
		mouseManager.unregisterMouseListener(bankInput);
		mouseManager.unregisterMouseWheelListener(bankInput);
		mouseManager.unregisterMouseListener(mapInput);

		removeSidePanel();

		bankViewer.reset();
		uploader.reset();
		routeTracker.stop();
		clientThread.invoke(mapMarkers::clear);
		groupState.reset();
	}

	// ------------------------------------------------------------------
	// Polling
	// ------------------------------------------------------------------

	/** Runs off the client thread once a second, and fetches at the configured interval. */
	private void poll()
	{
		try
		{
			long now = System.currentTimeMillis();
			if (now - lastPollMillis < config.pollSeconds() * 1000L)
			{
				return;
			}
			lastPollMillis = now;

			if (!api.hasCredentials())
			{
				setStatus("Enter your group name and token in the plugin settings.");
				return;
			}

			try
			{
				MemberDto[] response = api.getGroupData(groupState.getFromTime());
				if (response != null)
				{
					groupState.applyUpdate(response);
					setStatus(describeConnection());
				}
			}
			catch (GroupApi.NotAuthorisedException e)
			{
				setStatus("The server rejected your group name or token.");
				groupState.reset();
			}

			uploader.flush(localPlayerName);
		}
		catch (Exception e)
		{
			log.warn("Group Ironmen Tracker In-Game Companion: poll failed", e);
		}
	}

	private String describeConnection()
	{
		StringBuilder status = new StringBuilder();
		status.append("Group ").append(api.getGroupName());

		if (trackerDetector.isTrackerActive())
		{
			status.append(" · reading only (tracker plugin is sharing your data)");
		}
		else if (uploader.shouldUpload())
		{
			status.append(" · ").append(uploader.getStatus().toLowerCase(java.util.Locale.ROOT));
		}
		else
		{
			status.append(" · read only");
		}
		return status.toString();
	}

	private void refreshPanel()
	{
		GroupPanel current = panel;
		if (current != null)
		{
			current.refresh();
		}
	}

	private void setStatus(String status)
	{
		GroupPanel current = panel;
		if (current != null)
		{
			current.setStatus(status);
		}
	}

	private void onGroupStateChanged()
	{
		refreshPanel();
		bankViewer.markDirty();
		clientThread.invoke(mapMarkers::refresh);
	}

	// ------------------------------------------------------------------
	// Game events
	// ------------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			localPlayerName = null;
			bankInput.closeAll();
			routeTracker.stop();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player local = client.getLocalPlayer();
		String name = local == null ? null : local.getName();
		if (name != null && !name.equals(localPlayerName))
		{
			// Reset before publishing the new name, or a flush in between could post what
			// was staged for the previous character under this one's name.
			uploader.reset();
			localPlayerName = name;
			groupState.setLocalPlayerName(name);
			uploader.snapshot();
		}

		uploader.onVitalsChanged();
		uploader.onPositionChanged();
		uploader.onGameTick();

		if (varsChanged)
		{
			varsChanged = false;
			uploader.onQuestsChanged();
			uploader.onDiaryChanged();
		}

		// Follows the routed member as they move, and ends the route once we reach them.
		if (config.mapPanelRouting())
		{
			routeTracker.update();
		}

		// Markers are rebuilt immediately whenever group data changes. This periodic pass
		// only exists so members ageing into the offline threshold drop off on their own.
		if (++ticksSinceMapRefresh >= MAP_REFRESH_TICKS)
		{
			ticksSinceMapRefresh = 0;
			mapMarkers.refresh();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		uploader.onSkillsChanged();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		uploader.onItemContainerChanged(event.getContainerId(), event.getItemContainer());
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// Quest and diary progress are both var-driven, but vars change many times a tick and
		// a quest scan walks every quest in the game. Flag it and do the work once per tick.
		varsChanged = true;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN && config.bankViewer() && config.bankAutoOpen())
		{
			bankInput.openBesideBank();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN && config.bankCloseWithBank())
		{
			bankInput.closeAll();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!GroupIronmenCompanionConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		switch (event.getKey())
		{
			case "groupName":
			case "groupToken":
			case "baseUrlOverride":
				groupState.reset();
				uploader.reset();
				lastPollMillis = 0;
				break;

			case "showSidePanel":
				if (config.showSidePanel())
				{
					addSidePanel();
				}
				else
				{
					removeSidePanel();
				}
				break;

			case "bankViewer":
				if (!config.bankViewer())
				{
					bankInput.closeAll();
				}
				break;

			case "worldMapMarkers":
			case "worldMapEntrances":
			case "worldMapOffline":
				clientThread.invoke(mapMarkers::refresh);
				break;

			case "offlineAfterMinutes":
				clientThread.invoke(mapMarkers::refresh);
				refreshPanel();
				break;

			case "panelShowActivity":
			case "panelShowOffline":
			case "panelShowSelf":
				refreshPanel();
				break;

			case "uploadMode":
				uploader.reset();
				break;

			case "mapPanel":
			case "mapPanelRouting":
				if (!config.mapPanel() || !config.mapPanelRouting())
				{
					clientThread.invoke(routeTracker::stop);
				}
				break;

			default:
				if (event.getKey().startsWith("colour"))
				{
					applyPalette();
				}
				break;
		}
	}

	// ------------------------------------------------------------------
	// Side panel
	// ------------------------------------------------------------------

	private void addSidePanel()
	{
		if (panel != null)
		{
			return;
		}

		panel = new GroupPanel(groupState, config, itemManager, clientThread);
		navButton = NavigationButton.builder()
			.tooltip("Group Ironmen Tracker In-Game Companion")
			.icon(buildIcon())
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		panel.refresh();
		panel.setStatus(api.hasCredentials()
			? describeConnection()
			: "Enter your group name and token in the plugin settings.");
	}

	private void removeSidePanel()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
	}

	private void applyPalette()
	{
		groupState.setPalette(new Color[]{
			config.colour1(), config.colour2(), config.colour3(), config.colour4(), config.colour5()
		});
	}

	/** The sidebar icon, drawn at runtime so the plugin ships no image assets. */
	private static BufferedImage buildIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			// Three overlapping heads, reading as a small group.
			g.setColor(new Color(0x4F, 0xC3, 0xF7));
			g.fillOval(0, 4, 8, 8);
			g.setColor(new Color(0xEF, 0x53, 0x50));
			g.fillOval(8, 4, 8, 8);
			g.setColor(new Color(0x66, 0xBB, 0x6A));
			g.fillOval(4, 0, 8, 8);

			g.setColor(new Color(0, 0, 0, 160));
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
			g.drawString("G", 6, 9);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}
}
