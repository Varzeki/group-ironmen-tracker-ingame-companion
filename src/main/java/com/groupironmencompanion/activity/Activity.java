package com.groupironmencompanion.activity;

import com.groupironmencompanion.place.Place;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A best guess at what a member is doing, worked out from the data their tracker shares.
 * <p>
 * Immutable, so the polling thread can publish it and the client and Swing threads read it
 * without locks. The words are kept in parts rather than as one finished string, so each view
 * can leave the place out, and so an idle member's "(3m)" keeps counting between polls.
 */
public final class Activity
{
	public enum Kind
	{
		/** Not updated for longer than the offline threshold. */
		OFFLINE,
		/** Hitpoints reached zero. */
		DIED,
		/** A live interaction with a health bar, or with combat experience coming in. */
		FIGHTING,
		/** At a fishing spot, or gaining Fishing experience while interacting. */
		FISHING,
		/** Gaining Thieving experience while interacting with someone. */
		PICKPOCKETING,
		/** Interacting with another member of the group. */
		WITH_MEMBER,
		/** Any other live interaction with an NPC. */
		TALKING,
		/** Aboard a boat. */
		SAILING,
		/** Gaining experience; combat skills together count as one. */
		SKILLING,
		/** Recently finished a quest. */
		QUEST_COMPLETED,
		/** Started a quest while we were watching and has not finished it. */
		QUESTING,
		/** Their bank changed a moment ago. */
		BANKING,
		/** Moved further in one update than anyone can walk. */
		TELEPORTED,
		/** Doing something we cannot see. */
		ACTIVE,
		/** Nothing has changed for a while. */
		IDLE
	}

	private final Kind kind;
	private final String lead;
	private final String bare;
	private final String preposition;
	private final Place place;
	private final String suffix;
	private final Instant since;

	/**
	 * @param lead the words before the place, e.g. "Fishing"; empty to start with the place
	 * @param bare what to say when the place is unknown or hidden, e.g. "Fishing"
	 * @param preposition joins the lead to the place, e.g. "at"; null when no place is said
	 * @param suffix said last whatever happens, e.g. " (43%)"
	 * @param since when this activity began; for {@link Kind#IDLE}, when they last did anything
	 */
	Activity(Kind kind, String lead, String bare, @Nullable String preposition, @Nullable Place place,
		String suffix, Instant since)
	{
		this.kind = kind;
		this.lead = lead;
		this.bare = bare;
		this.preposition = preposition;
		this.place = preposition == null ? null : place;
		this.suffix = suffix;
		this.since = since;
	}

	public Kind getKind()
	{
		return kind;
	}

	@Nullable
	public Place getPlace()
	{
		return place;
	}

	public Instant getSince()
	{
		return since;
	}

	/** The same activity, but starting when an earlier copy of it did. */
	Activity startingAt(Instant start)
	{
		return new Activity(kind, lead, bare, preposition, place, suffix, start);
	}

	/** Whether two activities say the same thing, ignoring when they started. */
	boolean sameAs(@Nullable Activity other)
	{
		return other != null && kind == other.kind && lead.equals(other.lead) && bare.equals(other.bare)
			&& Objects.equals(preposition, other.preposition) && Objects.equals(place, other.place)
			&& suffix.equals(other.suffix);
	}

	/**
	 * The activity as a line of text: "Fishing at Catherby", "Fighting Goblin (43%)",
	 * "Idle at Lumbridge (3m)".
	 *
	 * @param now the time to measure an idle spell to
	 * @param withPlace false to leave the place out: "Fishing", "Idle (3m)"
	 */
	public String describe(Instant now, boolean withPlace)
	{
		StringBuilder text = new StringBuilder();
		if (withPlace && place != null)
		{
			String phrase = place.phrase(preposition);
			if (lead.isEmpty())
			{
				text.append(Character.toUpperCase(phrase.charAt(0))).append(phrase, 1, phrase.length());
			}
			else
			{
				text.append(lead).append(' ').append(phrase);
			}
		}
		else
		{
			text.append(bare);
		}
		text.append(suffix);

		if (kind == Kind.IDLE)
		{
			text.append(" (").append(formatDuration(Duration.between(since, now))).append(')');
		}
		return text.toString();
	}

	/** "45s", "3m", "2h": the same coarse units the panel's "last seen" uses. */
	static String formatDuration(Duration duration)
	{
		long seconds = Math.max(0, duration.getSeconds());
		if (seconds < 60)
		{
			return seconds + "s";
		}
		if (seconds < 3600)
		{
			return (seconds / 60) + "m";
		}
		if (seconds < 86_400)
		{
			return (seconds / 3600) + "h";
		}
		return (seconds / 86_400) + "d";
	}

	@Override
	public String toString()
	{
		return kind + ": " + describe(since, true);
	}
}
