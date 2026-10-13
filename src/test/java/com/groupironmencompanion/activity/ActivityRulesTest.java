package com.groupironmencompanion.activity;

import com.groupironmencompanion.api.MemberDto;
import com.groupironmencompanion.data.GroupSkill;
import com.groupironmencompanion.data.GroupState;
import com.groupironmencompanion.data.Quests;
import com.groupironmencompanion.place.Place;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

/**
 * One case per inference rule, driven through {@link GroupState#applyUpdate} exactly as the
 * polling thread drives it, with a clock the test steps by hand.
 */
public class ActivityRulesTest
{
	private static final Instant START = Instant.parse("2026-10-13T12:00:00Z");
	private static final int SKILLS = GroupSkill.VALUES.length;

	/** Catherby to the west of x 3000, Varrock to the east. */
	private static Place placeOf(net.runelite.api.coords.WorldPoint point)
	{
		return Place.at(point.getX() < 3000 ? "Catherby" : "Varrock");
	}

	private ActivityTracker tracker;
	private GroupState state;
	private Instant now;
	private int interactionStamp;

	@Before
	public void setUp()
	{
		tracker = new ActivityTracker(null, ActivityRulesTest::placeOf);
		state = new GroupState(tracker);
		now = START;
	}

	// ------------------------------------------------------------------
	// Driving the tracker
	// ------------------------------------------------------------------

	/** An update from alice, which the poll stamps as uploaded "now" by the server's clock. */
	private static MemberDto alice()
	{
		MemberDto dto = new MemberDto();
		dto.name = "alice";
		return dto;
	}

	private void poll(MemberDto... dtos)
	{
		for (MemberDto dto : dtos)
		{
			if (dto.last_updated == null)
			{
				dto.last_updated = now.toString();
			}
		}
		tracker.clock = Clock.fixed(now, ZoneOffset.UTC);
		state.applyUpdate(dtos);
	}

	private void after(int seconds, MemberDto... dtos)
	{
		now = now.plusSeconds(seconds);
		poll(dtos);
	}

	/** A member with no changes this poll: the server lists everyone, every time. */
	private MemberDto unchanged(Instant lastUpdated)
	{
		MemberDto dto = new MemberDto();
		dto.name = "alice";
		dto.last_updated = lastUpdated.toString();
		return dto;
	}

	private static int[] skills(int length)
	{
		int[] xp = new int[length];
		Arrays.fill(xp, 100_000);
		return xp;
	}

	private static int[] gained(int[] base, GroupSkill skill, int amount)
	{
		int[] xp = base.clone();
		xp[skill.ordinal()] += amount;
		return xp;
	}

	private MemberDto withSkills(int[] xp)
	{
		MemberDto dto = alice();
		dto.skills = xp;
		return dto;
	}

	private MemberDto at(int x, int y, int plane, boolean onBoat)
	{
		MemberDto dto = alice();
		dto.coordinates = new int[]{x, y, plane, onBoat ? 1 : 0};
		return dto;
	}

	private MemberDto interacting(String name, int ratio, int scale)
	{
		MemberDto dto = alice();
		dto.interacting = new MemberDto.InteractingDto();
		dto.interacting.name = name;
		dto.interacting.ratio = ratio;
		dto.interacting.scale = scale;
		// The server stamps every re-upload afresh; a distinct stamp per call stands in for that.
		dto.interacting.last_updated = now.plusMillis(++interactionStamp).toString();
		return dto;
	}

	/** Alice standing in Catherby with known skills, as the first poll of a session sees her. */
	private void baseline()
	{
		MemberDto dto = at(2813, 3440, 0, false);
		dto.skills = skills(SKILLS);
		dto.stats = new int[]{99, 99, 70, 70, 10000, 10000, 302};
		poll(dto);
	}

	private Activity activity()
	{
		Activity activity = tracker.get("alice");
		assertNotNull(activity);
		return activity;
	}

	private String text()
	{
		return activity().describe(now, true);
	}

	// ------------------------------------------------------------------
	// Baselines and duplicates
	// ------------------------------------------------------------------

	@Test
	public void theFirstPollIsOnlyABaseline()
	{
		// The first poll returns everything ever uploaded; none of it is a gain.
		baseline();
		assertEquals(Activity.Kind.ACTIVE, activity().getKind());
		assertEquals("In Catherby", text());
	}

	@Test
	public void aRepeatedResponseChangesNothing()
	{
		// from_time is inclusive, so the newest update comes back again on the next poll.
		baseline();
		after(3, withSkills(skills(SKILLS)));
		assertEquals(Activity.Kind.ACTIVE, activity().getKind());

		after(3, withSkills(gained(skills(SKILLS), GroupSkill.FISHING, 50)));
		Instant began = activity().getSince();
		after(3, withSkills(gained(skills(SKILLS), GroupSkill.FISHING, 50)));
		assertEquals("Fishing at Catherby", text());
		assertEquals(began, activity().getSince());
	}

	@Test
	public void aLongGapBetweenPollsIsANewBaseline()
	{
		baseline();
		after(600, withSkills(gained(skills(SKILLS), GroupSkill.MINING, 5000)));
		assertNotEquals(Activity.Kind.SKILLING, activity().getKind());
	}

	@Test
	public void olderTrackersSendTwentyThreeSkills()
	{
		MemberDto first = at(2813, 3440, 0, false);
		first.skills = skills(SKILLS - 1);
		poll(first);

		after(3, withSkills(gained(skills(SKILLS - 1), GroupSkill.WOODCUTTING, 25)));
		assertEquals("Woodcutting at Catherby", text());

		// The tracker updates mid-session and starts sending Sailing: that is not a gain.
		after(120, withSkills(gained(skills(SKILLS - 1), GroupSkill.WOODCUTTING, 25)));
		int[] withSailing = Arrays.copyOf(gained(skills(SKILLS - 1), GroupSkill.WOODCUTTING, 25), SKILLS);
		withSailing[GroupSkill.SAILING.ordinal()] = 2_000_000;
		after(3, withSkills(withSailing));
		assertNotEquals(Activity.Kind.SKILLING, activity().getKind());
	}

	// ------------------------------------------------------------------
	// Rules, in order
	// ------------------------------------------------------------------

	@Test
	public void offlineMembersAreLastSeenSomewhere()
	{
		baseline();
		now = now.plusSeconds(10 * 60);
		poll(unchanged(START));
		assertEquals(Activity.Kind.OFFLINE, activity().getKind());
		assertEquals("Last seen in Catherby", text());
		assertEquals("Offline", activity().describe(now, false));
	}

	@Test
	public void hitpointsAtZeroIsADeath()
	{
		baseline();
		MemberDto dead = alice();
		dead.stats = new int[]{0, 99, 70, 70, 10000, 10000, 302};
		after(3, dead);
		assertEquals("Died at Catherby", text());

		// Respawning far away in the next poll still reads as the death, where it happened.
		MemberDto respawned = at(3222, 3218, 0, false);
		respawned.stats = new int[]{99, 99, 70, 70, 10000, 10000, 302};
		after(3, respawned);
		assertEquals("Died at Catherby", text());
	}

	@Test
	public void aTargetWithAHealthBarIsAFight()
	{
		baseline();
		after(3, interacting("Goblin", 13, 30));
		assertEquals("Fighting Goblin (43%)", text());
	}

	@Test
	public void combatExperienceMakesAnyInteractionAFight()
	{
		baseline();
		MemberDto dto = interacting("Goblin", -1, -1);
		dto.skills = gained(gained(skills(SKILLS), GroupSkill.HITPOINTS, 13), GroupSkill.STRENGTH, 40);
		after(3, dto);
		assertEquals("Fighting Goblin", text());
	}

	@Test
	public void aFishingSpotIsFishing()
	{
		baseline();
		after(3, interacting("Rod Fishing spot", -1, -1));
		assertEquals("Fishing at Catherby", text());
	}

	@Test
	public void thievingExperienceWhileInteractingIsPickpocketing()
	{
		baseline();
		MemberDto dto = interacting("Master Farmer", -1, -1);
		dto.skills = gained(skills(SKILLS), GroupSkill.THIEVING, 43);
		after(3, dto);
		assertEquals("Pickpocketing Master Farmer", text());
	}

	@Test
	public void interactingWithAnotherMemberIsBeingWithThem()
	{
		MemberDto bob = new MemberDto();
		bob.name = "Bob";
		baseline();
		after(3, interacting("Bob", -1, -1), bob);
		assertEquals("With Bob", text());
	}

	@Test
	public void anyOtherInteractionIsTalking()
	{
		baseline();
		after(3, interacting("Banker", -1, -1));
		assertEquals("Talking to Banker", text());
	}

	@Test
	public void aStaleInteractionIsIgnored()
	{
		baseline();
		after(3, interacting("Banker", -1, -1));
		assertEquals(Activity.Kind.TALKING, activity().getKind());

		// It is never cleared on the server, only no longer refreshed.
		Instant lastUpload = now;
		now = now.plusSeconds(30);
		poll(unchanged(lastUpload));
		assertNotEquals(Activity.Kind.TALKING, activity().getKind());
	}

	@Test
	public void anInteractionFromTheFirstPollIsNotLive()
	{
		MemberDto first = interacting("Banker", -1, -1);
		first.coordinates = new int[]{2813, 3440, 0, 0};
		poll(first);
		assertNotEquals(Activity.Kind.TALKING, activity().getKind());
	}

	@Test
	public void anInteractionEndsWhenTheMemberGoesOnUploadingWithoutIt()
	{
		baseline();
		MemberDto talk = interacting("Banker", -1, -1);
		after(3, talk);
		// They walk off: the position keeps updating, the interaction's stamp does not.
		now = now.plusSeconds(8);
		MemberDto walked = at(2820, 3440, 0, false);
		poll(walked);
		assertNotEquals(Activity.Kind.TALKING, activity().getKind());
	}

	@Test
	public void theBoatFlagIsSailing()
	{
		baseline();
		after(3, at(2700, 3400, 0, true));
		assertEquals("Sailing near Catherby", text());
	}

	@Test
	public void sailingSomewhereUnnamedIsAtSea()
	{
		tracker = new ActivityTracker(null, point -> null);
		state = new GroupState(tracker);
		baseline();
		after(3, at(2300, 2700, 0, true));
		assertEquals("At sea", text());
	}

	@Test
	public void combatSkillsCountTogetherAndSlayerIsATask()
	{
		baseline();
		int[] xp = gained(gained(skills(SKILLS), GroupSkill.HITPOINTS, 20), GroupSkill.RANGED, 60);
		after(3, withSkills(gained(xp, GroupSkill.SLAYER, 45)));
		assertEquals("Fighting at Catherby (Slayer task)", text());
	}

	@Test
	public void magicOnItsOwnIsNotAFight()
	{
		baseline();
		after(3, withSkills(gained(skills(SKILLS), GroupSkill.MAGIC, 65)));
		assertEquals("Magic at Catherby", text());
	}

	@Test
	public void experienceExpiresAfterTheWindow()
	{
		baseline();
		after(3, withSkills(gained(skills(SKILLS), GroupSkill.COOKING, 30)));
		assertEquals("Cooking at Catherby", text());
		Instant lastUpload = now;
		for (int i = 0; i < 4; i++)
		{
			now = now.plusSeconds(30);
			poll(unchanged(lastUpload));
		}
		assertNotEquals(Activity.Kind.SKILLING, activity().getKind());
	}

	@Test
	public void questStartedWhileWatchingIsBeingDone()
	{
		// The wire stores QuestState ordinals: IN_PROGRESS is 0, NOT_STARTED 1, FINISHED 2.
		int index = 3;
		String quest = Quests.all()[index].getName();
		int[] states = new int[Quests.all().length];
		Arrays.fill(states, 1);

		baseline();
		MemberDto first = alice();
		first.quests = states.clone();
		after(3, first);

		states[index] = 0;
		MemberDto started = alice();
		started.quests = states.clone();
		after(3, started);
		assertEquals("Doing " + quest, text());

		states[index] = 2;
		MemberDto finished = alice();
		finished.quests = states.clone();
		after(3, finished);
		assertEquals("Completed " + quest, text());

		// Anything they go on to do afterwards wins.
		after(3, withSkills(gained(skills(SKILLS), GroupSkill.AGILITY, 30)));
		assertEquals("Agility at Catherby", text());
	}

	@Test
	public void aBankChangeIsBanking()
	{
		baseline();
		MemberDto first = alice();
		first.bank = new int[]{995, 1000};
		after(3, first);
		MemberDto deposit = alice();
		deposit.bank = new int[]{995, 1000, 532, 27};
		after(3, deposit);
		assertEquals("Banking at Catherby", text());
	}

	@Test
	public void aJumpNoOneCouldWalkIsATeleport()
	{
		baseline();
		after(3, at(3212, 3424, 0, false));
		assertEquals("Teleported to Varrock", text());
	}

	@Test
	public void climbingIntoACaveIsNotATeleport()
	{
		baseline();
		after(3, at(2813, 3440 + 6400, 0, false));
		assertEquals(Activity.Kind.ACTIVE, activity().getKind());
	}

	@Test
	public void aSlowUploaderWalkingFarIsNotATeleport()
	{
		// Members whose companion uploads once a minute move a long way between updates.
		baseline();
		after(60, at(2813 + 150, 3440, 0, false));
		assertEquals(Activity.Kind.ACTIVE, activity().getKind());
	}

	@Test
	public void nothingChangingForTheIdleThresholdIsIdle()
	{
		baseline();
		after(3, at(2814, 3440, 0, false));
		Instant lastUpload = now;
		now = now.plusSeconds(150);
		poll(unchanged(lastUpload));
		assertEquals("Idle at Catherby (2m)", text());
		assertEquals("Idle (2m)", activity().describe(now, false));

		// The count keeps going between polls.
		assertEquals("Idle at Catherby (3m)", activity().describe(now.plusSeconds(40), true));
	}
}
