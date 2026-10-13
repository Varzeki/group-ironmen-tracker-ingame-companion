package com.groupironmencompanion.activity;

import com.groupironmencompanion.api.MemberDto;
import com.groupironmencompanion.data.GroupSkill;
import com.groupironmencompanion.data.Quests;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import javax.annotation.Nullable;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.coords.WorldPoint;

/**
 * What has changed for one member recently, which is all activity can be inferred from.
 * <p>
 * The server only says what a field is now, not how it got there, so this remembers the
 * previous value of every field that matters and turns each difference into an event with
 * the time we saw it. Times are this machine's, taken when a poll arrived: the server's
 * timestamps are only compared with each other, so a skewed clock cannot make an event look
 * older or newer than it is.
 * <p>
 * The first poll is only a baseline. It returns everything a member has ever uploaded, and
 * treating that as a change would show every member as having just gained millions of
 * experience. A long gap between polls is treated the same way.
 * <p>
 * Confined to the polling thread, under {@link ActivityTracker}'s lock.
 */
final class MemberHistory
{
	/** How long experience gains are kept, comfortably longer than any rule looks back. */
	static final Duration XP_RING = Duration.ofMinutes(5);

	/**
	 * Tiles a member may cover per second without it counting as a teleport. Running is two
	 * tiles a tick, 3.3 a second; this leaves room for the odd shortcut.
	 */
	private static final double TILES_PER_SECOND = 4;

	/** A jump must also be at least this long, so a short agility shortcut is never a teleport. */
	private static final int MIN_JUMP_TILES = 24;

	/** Caves are laid out this many tiles north of the ground they run beneath. */
	private static final int CAVE_OFFSET = 6400;

	/** How far a ladder or a cave entrance may move a member sideways. */
	private static final int LADDER_REACH = 64;

	/** One experience gain: when we saw it, in which skill, and how much. */
	static final class XpGain
	{
		final Instant time;
		final GroupSkill skill;
		final int amount;

		XpGain(Instant time, GroupSkill skill, int amount)
		{
			this.time = time;
			this.skill = skill;
			this.amount = amount;
		}
	}

	final Deque<XpGain> gains = new ArrayDeque<>();
	private int[] xp;

	@Nullable
	WorldPoint position;
	/** The member's server-side update time when the current position arrived. */
	private Instant positionStamp;
	@Nullable
	Instant jumpedAt;
	@Nullable
	WorldPoint jumpedTo;

	private int[] quests;
	@Nullable
	Quest started;
	@Nullable
	Instant startedAt;
	@Nullable
	Quest finished;
	@Nullable
	Instant finishedAt;

	private int[] bank;
	@Nullable
	Instant bankChangedAt;
	private int[] inventory;
	private int[] equipment;

	private int world = -1;
	private int hitpoints = -1;
	@Nullable
	Instant diedAt;
	@Nullable
	WorldPoint diedWhere;

	/** The interaction's own server timestamp and target, to tell a fresh one from a repeat. */
	private String interactionStamp;
	@Nullable
	Instant interactionSeenAt;

	/** When anything last changed that a member only changes by doing something. */
	@Nullable
	Instant lastActivity;

	/** False until the first poll that included this member has been taken in. */
	private boolean observed;

	/** What was last published, so an unchanged activity keeps its start time. */
	@Nullable
	Activity published;

	/**
	 * Takes in one member's part of a poll.
	 *
	 * @param dto the fields that changed since the last poll; null fields are unchanged
	 * @param lastUpdated the member's own server-side update time, after this poll
	 * @param now when the poll arrived, on this machine's clock
	 * @param baseline true to record values without treating any difference as an event, for a
	 * poll after a long gap
	 */
	void observe(MemberDto dto, @Nullable Instant lastUpdated, Instant now, boolean baseline)
	{
		// Everything in the first poll is old news, including an interaction that may have
		// ended hours ago. After that, a bank, container or interaction we have never seen
		// before is a change; skills, quests and position have nothing to compare against
		// until their second value, so their first is always a baseline.
		boolean first = !observed;
		observed = true;

		if (lastActivity == null)
		{
			// We cannot know what they did before we started watching. Their last upload is the
			// best guess, but never later than now, whatever the two clocks say.
			lastActivity = lastUpdated != null && lastUpdated.isBefore(now) ? lastUpdated : now;
		}

		observeSkills(dto.skills, now, baseline);
		observeStats(dto.stats, now, baseline);
		observePosition(dto.coordinates, lastUpdated, now, baseline);
		observeQuests(dto.quests, now, baseline);
		observeItems(dto, now, baseline || first);
		observeInteraction(dto.interacting, now, baseline || first);

		Instant cutoff = now.minus(XP_RING);
		for (Iterator<XpGain> it = gains.iterator(); it.hasNext(); )
		{
			if (it.next().time.isBefore(cutoff))
			{
				it.remove();
			}
		}
	}

	private void observeSkills(@Nullable int[] skills, Instant now, boolean baseline)
	{
		if (skills == null)
		{
			return;
		}
		int[] previous = xp;
		xp = skills.clone();
		if (previous == null || baseline)
		{
			return;
		}

		// Older trackers send 23 skills, newer ones 24. Only compare what both have, so a
		// member whose tracker updates mid-session does not "gain" their whole Sailing total.
		int shared = Math.min(Math.min(previous.length, skills.length), GroupSkill.VALUES.length);
		for (int i = 0; i < shared; i++)
		{
			int gained = skills[i] - previous[i];
			if (gained > 0)
			{
				gains.addLast(new XpGain(now, GroupSkill.VALUES[i], gained));
				lastActivity = now;
			}
		}
	}

	private void observePosition(@Nullable int[] coordinates, @Nullable Instant lastUpdated, Instant now,
		boolean baseline)
	{
		if (coordinates == null || coordinates.length < 3)
		{
			return;
		}
		WorldPoint previous = position;
		Instant previousStamp = positionStamp;
		WorldPoint current = new WorldPoint(coordinates[0], coordinates[1], coordinates[2]);
		boolean onBoat = coordinates.length >= 4 && coordinates[3] != 0;
		position = current;
		positionStamp = lastUpdated;

		if (previous == null || baseline || previous.equals(current))
		{
			return;
		}
		lastActivity = now;

		// A boat outruns anyone on foot, so sailing is never a teleport.
		if (!onBoat && isJump(previous, current, previousStamp, lastUpdated))
		{
			jumpedAt = now;
			jumpedTo = current;
		}
	}

	/**
	 * Whether a move is too far to have been walked in the time between the two updates. The
	 * time comes from the server's own update stamps, so a member whose plugin uploads only
	 * once a minute does not look like they teleport every minute.
	 */
	static boolean isJump(WorldPoint from, WorldPoint to, @Nullable Instant fromStamp, @Nullable Instant toStamp)
	{
		int dx = Math.abs(to.getX() - from.getX());
		int dy = Math.abs(to.getY() - from.getY());

		// Climbing down into a cave moves a member 6400 tiles north and almost nowhere else.
		if (dx <= LADDER_REACH && Math.abs(dy - CAVE_OFFSET) <= LADDER_REACH)
		{
			return false;
		}

		long seconds = fromStamp == null || toStamp == null ? 0
			: Math.max(0, Duration.between(fromStamp, toStamp).getSeconds());
		double reach = MIN_JUMP_TILES + seconds * TILES_PER_SECOND;
		return Math.max(dx, dy) > reach;
	}

	private void observeQuests(@Nullable int[] states, Instant now, boolean baseline)
	{
		if (states == null)
		{
			return;
		}
		int[] previous = quests;
		quests = states.clone();
		if (previous == null || baseline)
		{
			return;
		}

		Quest[] all = Quests.all();
		int shared = Math.min(Math.min(previous.length, states.length), all.length);
		for (int i = 0; i < shared; i++)
		{
			if (previous[i] == states[i])
			{
				continue;
			}
			QuestState before = state(previous[i]);
			QuestState after = state(states[i]);
			lastActivity = now;
			if (after == QuestState.FINISHED && before != QuestState.FINISHED)
			{
				finished = all[i];
				finishedAt = now;
				if (all[i] == started)
				{
					started = null;
					startedAt = null;
				}
			}
			else if (after == QuestState.IN_PROGRESS && before == QuestState.NOT_STARTED)
			{
				started = all[i];
				startedAt = now;
			}
		}
	}

	/**
	 * The server stores each quest as the ordinal of {@link QuestState}, whose order is
	 * IN_PROGRESS, NOT_STARTED, FINISHED: zero means started, not "nothing".
	 */
	@Nullable
	private static QuestState state(int ordinal)
	{
		QuestState[] values = QuestState.values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
	}

	/** Whether the quest started this session is still in progress by the latest data. */
	boolean isStillStarted()
	{
		if (started == null || quests == null)
		{
			return false;
		}
		Quest[] all = Quests.all();
		for (int i = 0; i < all.length && i < quests.length; i++)
		{
			if (all[i] == started)
			{
				return state(quests[i]) == QuestState.IN_PROGRESS;
			}
		}
		return false;
	}

	private void observeStats(@Nullable int[] stats, Instant now, boolean baseline)
	{
		if (stats == null || stats.length < 7)
		{
			return;
		}
		int previousHitpoints = hitpoints;
		int previousWorld = world;
		hitpoints = stats[0];
		world = stats[6];

		// Hitpoints at zero is a death however we came to see it, even on the first poll: a
		// member cannot log out while dead, so it is never an old value. Called before the
		// position is taken in, so a respawn in the same poll does not move where they died.
		if (stats[0] == 0 && stats[1] > 0 && previousHitpoints != 0)
		{
			diedAt = now;
			diedWhere = position;
		}

		if (!baseline && previousWorld >= 0 && previousWorld != world)
		{
			lastActivity = now;
		}
	}

	private void observeItems(MemberDto dto, Instant now, boolean baseline)
	{
		int[] previousBank = bank;
		if (dto.bank != null)
		{
			bank = dto.bank.clone();
			if (!baseline && !Arrays.equals(previousBank, dto.bank))
			{
				bankChangedAt = now;
				lastActivity = now;
			}
		}

		int[] previousInventory = inventory;
		if (dto.inventory != null)
		{
			inventory = dto.inventory.clone();
			if (!baseline && !Arrays.equals(previousInventory, dto.inventory))
			{
				lastActivity = now;
			}
		}

		int[] previousEquipment = equipment;
		if (dto.equipment != null)
		{
			equipment = dto.equipment.clone();
			if (!baseline && !Arrays.equals(previousEquipment, dto.equipment))
			{
				lastActivity = now;
			}
		}
	}

	/**
	 * An interaction is re-uploaded every second while it lasts, each time with a new server
	 * timestamp, and simply stops being re-uploaded when it ends. So a new stamp means it was
	 * still going on when the member last uploaded, and the same stamp again is only the
	 * server repeating itself.
	 */
	private void observeInteraction(@Nullable MemberDto.InteractingDto interacting, Instant now, boolean baseline)
	{
		if (interacting == null)
		{
			return;
		}
		String stamp = interacting.last_updated + '|' + interacting.name;
		String previous = interactionStamp;
		interactionStamp = stamp;
		if (baseline || stamp.equals(previous))
		{
			return;
		}
		interactionSeenAt = now;
		lastActivity = now;
	}

	/** The latest gains, all from the same poll, or none. */
	Deque<XpGain> latestGains(Instant notBefore)
	{
		Deque<XpGain> latest = new ArrayDeque<>();
		XpGain newest = gains.peekLast();
		if (newest == null || newest.time.isBefore(notBefore))
		{
			return latest;
		}
		for (Iterator<XpGain> it = gains.descendingIterator(); it.hasNext(); )
		{
			XpGain gain = it.next();
			if (!gain.time.equals(newest.time))
			{
				break;
			}
			latest.addFirst(gain);
		}
		return latest;
	}

	/** Whether any of the given skills gained experience at or after a time. */
	boolean gainedSince(Instant notBefore, GroupSkill... skills)
	{
		for (Iterator<XpGain> it = gains.descendingIterator(); it.hasNext(); )
		{
			XpGain gain = it.next();
			if (gain.time.isBefore(notBefore))
			{
				return false;
			}
			for (GroupSkill skill : skills)
			{
				if (gain.skill == skill)
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Whether any experience at all came in after a time. */
	boolean gainedAfter(Instant after)
	{
		XpGain newest = gains.peekLast();
		return newest != null && newest.time.isAfter(after);
	}
}
