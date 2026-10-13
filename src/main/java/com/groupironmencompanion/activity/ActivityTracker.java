package com.groupironmencompanion.activity;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import com.groupironmencompanion.api.MemberDto;
import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupState;
import com.groupironmencompanion.place.Place;
import com.groupironmencompanion.place.Whereabouts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;

/**
 * Keeps a short history per member and publishes a best guess at what each is doing.
 * <p>
 * Fed by {@link GroupState#applyUpdate} on the polling thread with every response, including
 * ones in which nothing changed, so time-limited guesses such as "Banking" expire on the next
 * poll. Readers on the client and Swing threads only ever see an immutable map of immutable
 * {@link Activity} objects, swapped in whole, so the render path takes no lock; the lock here
 * only keeps a reset from interleaving with an update.
 */
@Singleton
public class ActivityTracker
{
	/**
	 * A gap between polls longer than this makes the next poll a fresh baseline. Everything
	 * that changed meanwhile happened at some unknown time, so none of it counts as recent.
	 */
	private static final Duration BASELINE_GAP = ActivityRules.XP_WINDOW;

	private static final int IDLE_MINUTES = 2;
	private static final int DEFAULT_OFFLINE_MINUTES = 5;
	private static final int DEFAULT_POLL_SECONDS = 3;

	private final GroupIronmenCompanionConfig config;
	private final Function<WorldPoint, Place> places;

	/** Guarded by {@code this}. */
	private final Map<String, MemberHistory> histories = new HashMap<>();

	/** Guarded by {@code this}. */
	private Instant lastPoll;

	private volatile Map<String, Activity> published = Collections.emptyMap();

	/** Replaced in tests, so time can be stepped without waiting. */
	Clock clock = Clock.systemUTC();

	@Inject
	public ActivityTracker(GroupIronmenCompanionConfig config, Whereabouts whereabouts)
	{
		this(config, whereabouts::of);
	}

	/**
	 * @param config null to use the default thresholds
	 * @param places names a position, or returns null when it cannot
	 */
	public ActivityTracker(@Nullable GroupIronmenCompanionConfig config, Function<WorldPoint, Place> places)
	{
		this.config = config;
		this.places = places;
	}

	/** The latest guess for a member, or null when there is none yet. Safe from any thread. */
	@Nullable
	public Activity get(String memberName)
	{
		return published.get(memberName);
	}

	/**
	 * Takes in one {@code get-group-data} response, after {@link GroupState} has merged it.
	 *
	 * @param response the raw response, whose non-null fields are what changed
	 * @param members every current member by name, already updated from this response
	 */
	public synchronized void update(MemberDto[] response, Map<String, GroupMember> members)
	{
		Instant now = clock.instant();
		boolean baseline = lastPoll != null && Duration.between(lastPoll, now).compareTo(BASELINE_GAP) > 0;
		lastPoll = now;

		for (MemberDto dto : response)
		{
			if (dto == null || dto.name == null || GroupMember.SHARED_NAME.equals(dto.name))
			{
				continue;
			}
			GroupMember member = members.get(dto.name);
			if (member == null)
			{
				continue;
			}
			histories.computeIfAbsent(dto.name, name -> new MemberHistory())
				.observe(dto, member.getLastUpdated(), now, baseline);
		}
		histories.keySet().retainAll(members.keySet());

		ActivityRules.Context context = new ActivityRules.Context(IDLE_MINUTES, offlineMinutes(), pollSeconds(),
			name -> isOtherMember(name, members), places);

		Map<String, Activity> next = new HashMap<>();
		for (Map.Entry<String, MemberHistory> entry : histories.entrySet())
		{
			GroupMember member = members.get(entry.getKey());
			MemberHistory history = entry.getValue();
			Activity activity = ActivityRules.decide(member, history, context, now);
			if (activity == null)
			{
				continue;
			}
			// The same guess as last time keeps its start, so "since" means since it began.
			if (activity.sameAs(history.published) && activity.getKind() != Activity.Kind.IDLE)
			{
				activity = activity.startingAt(history.published.getSince());
			}
			history.published = activity;
			next.put(entry.getKey(), activity);
		}
		published = Collections.unmodifiableMap(next);
	}

	/** Forgets everything, for a new group or a plugin restart. */
	public synchronized void reset()
	{
		histories.clear();
		lastPoll = null;
		published = Collections.emptyMap();
	}

	private static boolean isOtherMember(String name, Map<String, GroupMember> members)
	{
		for (String member : members.keySet())
		{
			if (!GroupMember.SHARED_NAME.equals(member) && GroupState.namesMatch(member, name))
			{
				return true;
			}
		}
		return false;
	}

	private int offlineMinutes()
	{
		return config == null ? DEFAULT_OFFLINE_MINUTES : config.offlineAfterMinutes();
	}

	private int pollSeconds()
	{
		return config == null ? DEFAULT_POLL_SECONDS : config.pollSeconds();
	}
}
