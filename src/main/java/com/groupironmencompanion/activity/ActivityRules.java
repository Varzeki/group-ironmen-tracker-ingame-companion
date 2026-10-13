package com.groupironmencompanion.activity;

import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupSkill;
import com.groupironmencompanion.place.Place;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;

/**
 * Turns what we know about a member into a best guess at what they are doing.
 * <p>
 * The tracker shares no animation or activity field, and the server cannot take a new one,
 * so this works only from what changed: experience, position, the boat flag, quest states,
 * the bank, and the NPC they are interacting with. Rules are tried in order and the first
 * that matches wins, most specific first. Objects (trees, rocks, ranges) never show up as an
 * interaction, which is why most skilling is recognised by its experience instead.
 * <p>
 * A pure function of its inputs, so every rule can be tested without a client.
 */
final class ActivityRules
{
	/** How far back experience counts towards "what are they doing now". */
	static final Duration XP_WINDOW = Duration.ofSeconds(90);

	/**
	 * The least time an interaction stays live after we last saw it refreshed. The real
	 * window also grows with the poll interval, so a slow refresh rate does not make every
	 * interaction stale on arrival.
	 */
	static final Duration MIN_INTERACTION_WINDOW = Duration.ofSeconds(15);

	/**
	 * How far a member's last update may run past their interaction's before the interaction
	 * counts as over. While it lasts, both are refreshed by the same uploads.
	 */
	static final Duration INTERACTION_ENDED_GAP = Duration.ofSeconds(5);

	static final Duration DEATH_SHOWN = Duration.ofSeconds(60);
	static final Duration QUEST_COMPLETED_SHOWN = Duration.ofMinutes(2);

	/**
	 * How long a quest started while we watched is assumed to still be what they are doing.
	 * Steps within a quest are invisible, so nothing ever refreshes this.
	 */
	static final Duration QUEST_SHOWN = Duration.ofMinutes(30);

	static final Duration BANK_SHOWN = Duration.ofSeconds(30);
	static final Duration TELEPORT_SHOWN = Duration.ofSeconds(30);

	/** Skills whose experience means a fight. Magic only counts alongside one of these. */
	private static final Set<GroupSkill> COMBAT = EnumSet.of(GroupSkill.ATTACK, GroupSkill.STRENGTH,
		GroupSkill.DEFENCE, GroupSkill.RANGED, GroupSkill.HITPOINTS, GroupSkill.SLAYER);

	private static final GroupSkill[] COMBAT_ARRAY = COMBAT.toArray(new GroupSkill[0]);

	private ActivityRules()
	{
	}

	/** Everything the rules need besides the member and their history. */
	static final class Context
	{
		final int idleMinutes;
		final int offlineMinutes;
		final int pollSeconds;
		/** Whether a name belongs to someone else in the group. */
		final Predicate<String> isGroupMember;
		final Function<WorldPoint, Place> places;

		Context(int idleMinutes, int offlineMinutes, int pollSeconds, Predicate<String> isGroupMember,
			Function<WorldPoint, Place> places)
		{
			this.idleMinutes = idleMinutes;
			this.offlineMinutes = offlineMinutes;
			this.pollSeconds = pollSeconds;
			this.isGroupMember = isGroupMember;
			this.places = places;
		}

		Duration interactionWindow()
		{
			Duration scaled = Duration.ofSeconds(2L * pollSeconds + 5);
			return scaled.compareTo(MIN_INTERACTION_WINDOW) > 0 ? scaled : MIN_INTERACTION_WINDOW;
		}
	}

	/** The best guess for one member, or null when they have never reported anything useful. */
	@Nullable
	static Activity decide(GroupMember member, MemberHistory history, Context context, Instant now)
	{
		WorldPoint position = history.position != null ? history.position : member.getWorldPoint();
		Instant since = now;

		// 1. Offline. Idle and logged out look the same once nothing changes, so the offline
		// threshold is the only way to tell them apart.
		Instant updated = member.getLastUpdated();
		if (updated == null || Duration.between(updated, now).toMinutes() >= context.offlineMinutes)
		{
			return new Activity(Activity.Kind.OFFLINE, "Last seen", "Offline", "in",
				place(context, position), "", updated != null ? updated : now);
		}

		// 2. Died a moment ago.
		if (within(history.diedAt, DEATH_SHOWN, now) || member.hasStats() && member.getHitpoints() == 0
			&& member.getMaxHitpoints() > 0)
		{
			WorldPoint where = history.diedWhere != null ? history.diedWhere : position;
			return new Activity(Activity.Kind.DIED, "Died", "Died", "at", place(context, where), "",
				history.diedAt != null ? history.diedAt : now);
		}

		// 3. A live interaction, sharpened by whatever experience is coming in with it.
		GroupMember.Interaction interaction = member.getInteraction();
		Duration window = context.interactionWindow();
		if (interaction != null && interaction.getName() != null && isLive(interaction, member, history, window, now))
		{
			Activity live = interacting(interaction, history, context, position, now.minus(window), since);
			if (live != null)
			{
				return live;
			}
		}

		// A quest finished a moment ago beats the reward experience that came with it, but not
		// anything they went on to do afterwards.
		if (within(history.finishedAt, QUEST_COMPLETED_SHOWN, now) && history.finished != null
			&& !history.gainedAfter(history.finishedAt))
		{
			String name = history.finished.getName();
			return new Activity(Activity.Kind.QUEST_COMPLETED, "Completed " + name, "Completed " + name, null,
				null, "", history.finishedAt);
		}

		// 4. Aboard a boat.
		if (member.isOnBoat())
		{
			Place place = place(context, position);
			return new Activity(Activity.Kind.SAILING, "Sailing", place == null ? "At sea" : "Sailing", "near",
				place, "", since);
		}

		// 5. Experience in the last minute or so.
		Activity skilling = skilling(history, context, position, now, since);
		if (skilling != null)
		{
			return skilling;
		}

		boolean idle = history.lastActivity != null
			&& Duration.between(history.lastActivity, now).toMinutes() >= context.idleMinutes;

		// 6. A quest started while we watched, and nothing more specific going on.
		if (!idle && history.started != null && within(history.startedAt, QUEST_SHOWN, now)
			&& history.isStillStarted())
		{
			String name = history.started.getName();
			return new Activity(Activity.Kind.QUESTING, "Doing " + name, "Doing " + name, null, null, "",
				history.startedAt);
		}

		// 7. Their bank changed a moment ago.
		if (within(history.bankChangedAt, BANK_SHOWN, now))
		{
			return new Activity(Activity.Kind.BANKING, "Banking", "Banking", "at", place(context, position), "",
				history.bankChangedAt);
		}

		// 8. A jump no one could walk.
		if (within(history.jumpedAt, TELEPORT_SHOWN, now))
		{
			WorldPoint to = history.jumpedTo != null ? history.jumpedTo : position;
			return new Activity(Activity.Kind.TELEPORTED, "Teleported", "Teleported", "to", place(context, to), "",
				history.jumpedAt);
		}

		// 9. Nothing changing for a while.
		if (idle)
		{
			return new Activity(Activity.Kind.IDLE, "Idle", "Idle", "at", place(context, position), "",
				history.lastActivity);
		}

		return new Activity(Activity.Kind.ACTIVE, "", "Active", "in", place(context, position), "", since);
	}

	/**
	 * An interaction is live when a fresh copy of it arrived recently, and the member has not
	 * gone on to upload anything else since it was last refreshed.
	 */
	private static boolean isLive(GroupMember.Interaction interaction, GroupMember member, MemberHistory history,
		Duration window, Instant now)
	{
		if (!within(history.interactionSeenAt, window, now))
		{
			return false;
		}
		Instant updated = member.getLastUpdated();
		Instant refreshed = interaction.getSeen();
		return updated == null || refreshed == null
			|| Duration.between(refreshed, updated).compareTo(INTERACTION_ENDED_GAP) <= 0;
	}

	@Nullable
	private static Activity interacting(GroupMember.Interaction interaction, MemberHistory history, Context context,
		WorldPoint position, Instant recent, Instant since)
	{
		String target = interaction.getName();
		if (target.isEmpty())
		{
			return null;
		}

		if (context.isGroupMember.test(target))
		{
			return new Activity(Activity.Kind.WITH_MEMBER, "With " + target, "With " + target, null, null, "", since);
		}

		int health = interaction.getHealthPercent();
		if (health >= 0 || history.gainedSince(recent, COMBAT_ARRAY))
		{
			String suffix = health >= 0 ? " (" + health + "%)" : "";
			return new Activity(Activity.Kind.FIGHTING, "Fighting " + target, "Fighting " + target, null, null,
				suffix, since);
		}

		if (target.toLowerCase(Locale.ROOT).contains("fishing spot")
			|| history.gainedSince(recent, GroupSkill.FISHING))
		{
			return new Activity(Activity.Kind.FISHING, "Fishing", "Fishing", "at", place(context, position), "",
				since);
		}

		if (history.gainedSince(recent, GroupSkill.THIEVING))
		{
			return new Activity(Activity.Kind.PICKPOCKETING, "Pickpocketing " + target, "Pickpocketing " + target,
				null, null, "", since);
		}

		return new Activity(Activity.Kind.TALKING, "Talking to " + target, "Talking to " + target, null, null, "",
			since);
	}

	/**
	 * The skill behind the most recent experience. Combat skills, Hitpoints and Slayer count
	 * together as one fight; Magic only joins them when it comes in alongside one, so alching
	 * on its own still reads as Magic.
	 */
	@Nullable
	private static Activity skilling(MemberHistory history, Context context, WorldPoint position, Instant now,
		Instant since)
	{
		Deque<MemberHistory.XpGain> latest = history.latestGains(now.minus(XP_WINDOW));
		if (latest.isEmpty())
		{
			return null;
		}

		boolean combat = false;
		MemberHistory.XpGain biggest = null;
		for (MemberHistory.XpGain gain : latest)
		{
			combat |= COMBAT.contains(gain.skill);
			if (biggest == null || gain.amount > biggest.amount)
			{
				biggest = gain;
			}
		}

		Place place = place(context, position);
		if (combat)
		{
			String suffix = history.gainedSince(now.minus(XP_WINDOW), GroupSkill.SLAYER) ? " (Slayer task)" : "";
			return new Activity(Activity.Kind.SKILLING, "Fighting", "Fighting", "at", place, suffix, since);
		}

		String skill = biggest.skill.getDisplayName();
		return new Activity(Activity.Kind.SKILLING, skill, skill, "at", place, "", since);
	}

	@Nullable
	private static Place place(Context context, @Nullable WorldPoint at)
	{
		return at == null ? null : context.places.apply(at);
	}

	private static boolean within(@Nullable Instant event, Duration window, Instant now)
	{
		return event != null && Duration.between(event, now).compareTo(window) <= 0;
	}
}
