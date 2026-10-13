package com.groupironmencompanion.upload;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import com.groupironmencompanion.TrackerDetector;
import com.groupironmencompanion.api.GroupApi;
import com.groupironmencompanion.data.GroupSkill;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;

/**
 * The standalone data source, used only when the official Group Ironmen Tracker plugin is
 * not running. It gathers the same fields in the same wire format as the tracker, so a group
 * can mix and match the two plugins freely and the website keeps working either way.
 * <p>
 * Nothing here runs unless the user has entered their own group credentials and the tracker
 * is absent, and a single config switch turns it off entirely. Collection log progress is
 * deliberately not collected; the tracker remains the way to populate that.
 */
@Slf4j
@Singleton
public class GroupUploader
{
	/** Interface group of the group storage loading screen, used to detect a committed save. */
	private static final int GROUP_STORAGE_LOADER = 293;

	/**
	 * Least time between quest scans. A scan asks the game for the state of every quest, a
	 * script run each, so it is not repeated on every tick that vars change. The official
	 * tracker scans on the same interval.
	 */
	private static final long QUEST_SCAN_MILLIS = 60_000;

	/** How long to wait after a failed check or post before trying again. */
	private static final long BACKOFF_MILLIS = 10_000;

	private static final int INVENTORY_SIZE = 28;
	private static final int EQUIPMENT_SIZE = 14;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private GroupApi api;

	@Inject
	private GroupIronmenCompanionConfig config;

	@Inject
	private TrackerDetector trackerDetector;

	/** Fields whose value has changed since the last successful post. */
	private final Map<String, Object> pending = new LinkedHashMap<>();

	/**
	 * The last value successfully sent for each field, used to suppress no-op posts.
	 * Written from the client thread by {@link #stage} and from the polling thread by
	 * {@link #restore}, so it has to be concurrent.
	 */
	private final Map<String, String> lastSent = new ConcurrentHashMap<>();

	private int[] sortedQuestIds;
	private boolean questsChanged = true;
	private long lastQuestScan;
	private int[] pendingSharedBank;
	private boolean membershipConfirmed;
	private String membershipCheckedFor;
	private volatile long backoffUntil;

	@Getter
	private volatile boolean active;

	@Getter
	private volatile String status = "Idle";

	public void reset()
	{
		synchronized (pending)
		{
			pending.clear();
		}
		lastSent.clear();
		pendingSharedBank = null;
		// Scan quests on the next tick, such as the first after a login, then at most once a minute.
		questsChanged = true;
		lastQuestScan = 0;
		membershipConfirmed = false;
		membershipCheckedFor = null;
		backoffUntil = 0;
		active = false;
		status = "Idle";
	}

	/**
	 * Whether this plugin should be the one uploading. False whenever the official tracker
	 * plugin is running, so the two never post competing snapshots.
	 */
	public boolean shouldUpload()
	{
		return config.uploadMode() == GroupIronmenCompanionConfig.UploadMode.AUTO
			&& !trackerDetector.isTrackerActive()
			&& api.hasCredentials();
	}

	// ------------------------------------------------------------------
	// Collection, all called on the client thread
	// ------------------------------------------------------------------

	public void onSkillsChanged()
	{
		if (!collectable())
		{
			return;
		}
		int[] xp = new int[GroupSkill.VALUES.length];
		for (GroupSkill groupSkill : GroupSkill.VALUES)
		{
			xp[groupSkill.ordinal()] = client.getSkillExperience(groupSkill.getSkill());
		}
		stage("skills", xp);
	}

	public void onVitalsChanged()
	{
		if (!collectable())
		{
			return;
		}
		stage("stats", new int[]{
			client.getBoostedSkillLevel(Skill.HITPOINTS),
			client.getRealSkillLevel(Skill.HITPOINTS),
			client.getBoostedSkillLevel(Skill.PRAYER),
			client.getRealSkillLevel(Skill.PRAYER),
			client.getEnergy(),
			100,
			client.getWorld()
		});
	}

	public void onPositionChanged()
	{
		if (!collectable())
		{
			return;
		}
		Player local = client.getLocalPlayer();
		WorldPoint point = realLocation(local);
		if (point == null)
		{
			return;
		}
		stage("coordinates", UploadPayloads.coordinates(point, isOnBoat(local)));
	}

	/**
	 * Stages skills, inventory and equipment as they are now. Used after a login or hop: the
	 * reset that follows a character change throws away whatever the login burst staged, and
	 * these fields would otherwise stay missing until they next change.
	 */
	public void snapshot()
	{
		onSkillsChanged();
		onItemContainerChanged(InventoryID.INV, client.getItemContainer(InventoryID.INV));
		onItemContainerChanged(InventoryID.WORN, client.getItemContainer(InventoryID.WORN));
	}

	public void onItemContainerChanged(int containerId, @Nullable ItemContainer container)
	{
		if (!collectable() || container == null)
		{
			return;
		}

		if (containerId == InventoryID.BANK)
		{
			stage("bank", flatten(container, -1));
		}
		else if (containerId == InventoryID.SEED_VAULT)
		{
			stage("seed_vault", flatten(container, -1));
		}
		else if (containerId == InventoryID.INV)
		{
			stage("inventory", flatten(container, INVENTORY_SIZE));
		}
		else if (containerId == InventoryID.WORN)
		{
			stage("equipment", flatten(container, EQUIPMENT_SIZE));
		}
		else if (containerId == InventoryID.INV_GROUP_TEMP)
		{
			// The shared bank is edited locally and only written to the server when the
			// player confirms; hold the snapshot until the save is observed.
			pendingSharedBank = flatten(container, -1);
		}
	}

	/**
	 * Called each tick. Commits a buffered shared bank snapshot once the group storage
	 * interface reports that it is saving, matching the official tracker's behaviour.
	 */
	public void onGameTick()
	{
		if (!collectable())
		{
			return;
		}

		if (pendingSharedBank != null)
		{
			net.runelite.api.widgets.Widget loader = client.getWidget(GROUP_STORAGE_LOADER, 1);
			if (loader != null && "saving...".equalsIgnoreCase(loader.getText()))
			{
				stage("shared_bank", pendingSharedBank);
				pendingSharedBank = null;
			}
		}

		stageRunePouch();
		stageInteracting();
		stageQuests();
	}

	/** Notes that quest progress may have changed. The scan itself runs from {@link #onGameTick}. */
	public void onQuestsChanged()
	{
		questsChanged = true;
	}

	private void stageQuests()
	{
		long now = System.currentTimeMillis();
		if (!questsChanged || (lastQuestScan != 0 && now - lastQuestScan < QUEST_SCAN_MILLIS))
		{
			return;
		}
		questsChanged = false;
		lastQuestScan = now;

		if (sortedQuestIds == null)
		{
			sortedQuestIds = Arrays.stream(Quest.values())
				.map(Quest::getId)
				.sorted()
				.mapToInt(Integer::intValue)
				.toArray();
		}

		Map<Integer, QuestState> states = new HashMap<>();
		for (Quest quest : Quest.values())
		{
			states.put(quest.getId(), quest.getState(client));
		}

		int[] result = new int[sortedQuestIds.length];
		for (int i = 0; i < sortedQuestIds.length; i++)
		{
			QuestState state = states.get(sortedQuestIds[i]);
			result[i] = state == null ? 0 : state.ordinal();
		}
		stage("quests", result);
	}

	public void onDiaryChanged()
	{
		if (!collectable())
		{
			return;
		}
		int[] values = new int[DiaryVars.VARPS.length + DiaryVars.VARBITS.length];
		for (int i = 0; i < DiaryVars.VARPS.length; i++)
		{
			values[i] = client.getVarpValue(DiaryVars.VARPS[i]);
		}
		for (int i = 0; i < DiaryVars.VARBITS.length; i++)
		{
			values[i + DiaryVars.VARPS.length] = client.getVarbitValue(DiaryVars.VARBITS[i]);
		}
		stage("diary_vars", values);
	}

	private void stageRunePouch()
	{
		EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		if (runes == null)
		{
			return;
		}
		int[] types = {
			VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2,
			VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_TYPE_4
		};
		int[] quantities = {
			VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2,
			VarbitID.RUNE_POUCH_QUANTITY_3, VarbitID.RUNE_POUCH_QUANTITY_4
		};

		int[] flat = new int[types.length * 2];
		for (int i = 0; i < types.length; i++)
		{
			int runeType = client.getVarbitValue(types[i]);
			flat[i * 2] = runes.getIntValue(runeType);
			flat[i * 2 + 1] = client.getVarbitValue(quantities[i]);
		}
		stage("rune_pouch", flat);
	}

	private void stageInteracting()
	{
		Player local = client.getLocalPlayer();
		Actor target = local == null ? null : local.getInteracting();
		if (target == null || target.getName() == null)
		{
			return;
		}

		WorldPoint point = realLocation(target);
		UploadPayloads.Coordinates location = point == null
			? new UploadPayloads.Coordinates(0, 0, 0)
			: new UploadPayloads.Coordinates(point.getX(), point.getY(), point.getPlane());

		// Interactions are re-sent every tick while they last so viewers can tell a live
		// fight from a stale one, so this bypasses the change filter.
		synchronized (pending)
		{
			pending.put("interacting", new UploadPayloads.Interacting(
				target.getName(), target.getHealthScale(), target.getHealthRatio(), location));
		}
	}

	// ------------------------------------------------------------------
	// Sending, called off the client thread
	// ------------------------------------------------------------------

	/** Posts everything staged since the last call. Must not run on the client thread. */
	/**
	 * Drops what is staged and what was last sent, and nothing else. Safe from the polling
	 * thread: the rest of {@link #reset} is client thread state, such as a group storage
	 * save in progress, which must not be cleared from under it every poll.
	 */
	private void discardStaged()
	{
		synchronized (pending)
		{
			pending.clear();
		}
		lastSent.clear();
		membershipConfirmed = false;
		active = false;
	}

	public void flush(String playerName)
	{
		if (!shouldUpload())
		{
			// Nothing new is staged while the tracker uploads, but what was staged before it
			// took over is stale, and the tracker may since have posted values that lastSent
			// would wrongly suppress. Start clean whenever this plugin takes over again.
			discardStaged();
			return;
		}
		if (playerName == null)
		{
			active = false;
			return;
		}

		// Timed rather than counted in flushes, which happen once per refresh interval, so
		// the wait no longer stretches from seconds to minutes as that interval grows.
		if (System.currentTimeMillis() < backoffUntil)
		{
			return;
		}

		if (!membershipConfirmed || !playerName.equals(membershipCheckedFor))
		{
			Boolean member;
			try
			{
				member = api.isPlayerInGroup(playerName);
			}
			catch (GroupApi.NotAuthorisedException e)
			{
				status = "The server rejected the group token";
				active = false;
				backOff();
				return;
			}
			if (member == null)
			{
				status = "Could not reach the server, retrying shortly";
				active = false;
				backOff();
				return;
			}
			if (!member)
			{
				status = "This character is not a member of the configured group";
				active = false;
				backOff();
				return;
			}
			membershipConfirmed = true;
			membershipCheckedFor = playerName;
		}

		Map<String, Object> payload;
		synchronized (pending)
		{
			if (pending.isEmpty())
			{
				active = true;
				return;
			}
			payload = new LinkedHashMap<>(pending);
			pending.clear();
		}
		payload.put("name", playerName);

		try
		{
			if (api.updateGroupMember(payload))
			{
				active = true;
				status = "Sharing your data (the official tracker plugin is not running)";
			}
			else
			{
				// Put the fields back so nothing is lost, without clobbering newer values.
				restore(payload);
				backOff();
			}
		}
		catch (GroupApi.NotAuthorisedException e)
		{
			membershipConfirmed = false;
			status = "The server rejected the group token";
			active = false;
			backOff();
		}
	}

	private void backOff()
	{
		backoffUntil = System.currentTimeMillis() + BACKOFF_MILLIS;
	}

	private void restore(Map<String, Object> payload)
	{
		payload.remove("name");
		synchronized (pending)
		{
			for (Map.Entry<String, Object> entry : payload.entrySet())
			{
				pending.putIfAbsent(entry.getKey(), entry.getValue());
			}
		}
		for (String key : payload.keySet())
		{
			lastSent.remove(key);
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/** True while the actor stands on a boat, which has a world view of its own. */
	private static boolean isOnBoat(Actor actor)
	{
		WorldView view = actor.getWorldView();
		return view != null && !view.isTopLevel();
	}

	/**
	 * Where an actor is in the real world.
	 * <p>
	 * A boat is its own world view, built from a template somewhere else in the world, so
	 * converting a position on deck gives a tile nowhere near the sea. While aboard, the
	 * boat's own position in the top-level world is the real one. This is the same
	 * conversion the official tracker plugin makes.
	 */
	@Nullable
	private WorldPoint realLocation(Actor actor)
	{
		if (!isOnBoat(actor))
		{
			return WorldPoint.fromLocalInstance(client, actor.getLocalLocation());
		}
		WorldEntity boat = client.getTopLevelWorldView().worldEntities().byIndex(actor.getWorldView().getId());
		return boat == null ? null : WorldPoint.fromLocalInstance(client, boat.getLocalLocation());
	}

	private boolean collectable()
	{
		if (!shouldUpload() || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return false;
		}
		return !isBadWorld();
	}

	/** Beta and tournament worlds carry throwaway data that would corrupt the group's view. */
	private boolean isBadWorld()
	{
		for (WorldType type : client.getWorldType())
		{
			if (type == WorldType.SEASONAL
				|| type == WorldType.DEADMAN
				|| type == WorldType.TOURNAMENT_WORLD
				|| type == WorldType.PVP_ARENA
				|| type == WorldType.BETA_WORLD
				|| type == WorldType.QUEST_SPEEDRUNNING)
			{
				return true;
			}
		}
		return false;
	}

	private void stage(String field, int[] value)
	{
		String fingerprint = Arrays.toString(value);
		if (fingerprint.equals(lastSent.get(field)))
		{
			return;
		}
		lastSent.put(field, fingerprint);
		synchronized (pending)
		{
			pending.put(field, value);
		}
	}

	/**
	 * Flattens a container into the server's [id, quantity, ...] encoding. When
	 * {@code fixedSize} is positive every slot is emitted in order, including empty ones,
	 * which is what the site needs to draw an inventory or equipment layout faithfully.
	 */
	private int[] flatten(ItemContainer container, int fixedSize)
	{
		if (fixedSize > 0)
		{
			int[] flat = new int[fixedSize * 2];
			for (int slot = 0; slot < fixedSize; slot++)
			{
				Item item = container.getItem(slot);
				if (isReal(item))
				{
					flat[slot * 2] = itemManager.canonicalize(item.getId());
					flat[slot * 2 + 1] = item.getQuantity();
				}
			}
			return flat;
		}

		Item[] contents = container.getItems();
		int count = 0;
		for (Item item : contents)
		{
			if (isReal(item))
			{
				count++;
			}
		}

		int[] flat = new int[count * 2];
		int index = 0;
		for (Item item : contents)
		{
			if (isReal(item))
			{
				flat[index++] = itemManager.canonicalize(item.getId());
				flat[index++] = item.getQuantity();
			}
		}
		return flat;
	}

	/** Placeholders are bank UI artefacts, not owned items, so they are never reported. */
	private boolean isReal(@Nullable Item item)
	{
		if (item == null || item.getId() < 0 || item.getQuantity() <= 0)
		{
			return false;
		}
		return itemManager.getItemComposition(item.getId()).getPlaceholderTemplateId() == -1;
	}
}
