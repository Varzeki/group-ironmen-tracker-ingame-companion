package com.groupironmencompanion.data;

import com.groupironmencompanion.api.MemberDto;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Covers the delta merge and the shared member list handed to the render paths. */
public class GroupStateTest
{
	private static final int BIG_BONES = 532;

	private static MemberDto member(String name, int[] skills)
	{
		MemberDto dto = new MemberDto();
		dto.name = name;
		dto.skills = skills;
		return dto;
	}

	@Test
	public void sortsMembersAndExcludesTheSharedPseudoMember()
	{
		GroupState state = new GroupState();
		state.applyUpdate(new MemberDto[]{
			member("Zed", null), member("@SHARED", null), member("alice", null)});

		List<GroupMember> members = state.getMembers();
		assertEquals(2, members.size());
		assertEquals("alice", members.get(0).getName());
		assertEquals("Zed", members.get(1).getName());
		assertNotNull(state.getSharedBank());
	}

	private static MemberDto withBank(String name, int[] bank)
	{
		MemberDto dto = new MemberDto();
		dto.name = name;
		dto.bank = bank;
		return dto;
	}

	@Test
	public void groupStorageIsCountedOnceInTheGroupTotals()
	{
		// Group storage arrives as its own member, so a caller that added getSharedBank() on
		// top of the totals counted every shared stack twice.
		GroupState state = new GroupState();
		state.applyUpdate(new MemberDto[]{
			withBank("alice", new int[]{BIG_BONES, 2700}),
			withBank("@SHARED", new int[]{BIG_BONES, 1400})});

		assertEquals(Long.valueOf(4100), state.getGroupTotals().get(BIG_BONES));
	}

	@Test
	public void handsOutTheSameListUntilMembershipChanges()
	{
		// Four overlays call this every frame, so it must not rebuild per call.
		GroupState state = new GroupState();
		state.applyUpdate(new MemberDto[]{member("alice", null)});

		assertSame(state.getMembers(), state.getMembers());
	}

	@Test
	public void theSharedMemberListCannotBeMutatedByCallers()
	{
		GroupState state = new GroupState();
		state.applyUpdate(new MemberDto[]{member("alice", null)});

		try
		{
			state.getMembers().clear();
			org.junit.Assert.fail("the shared list must be immutable");
		}
		catch (UnsupportedOperationException expected)
		{
			// A caller reordering this in place would corrupt every other view.
		}
	}

	@Test
	public void aFieldMissingFromAnUpdateLeavesTheOldValueAlone()
	{
		// The server sends deltas: an unchanged field comes back null and must not clear.
		GroupState state = new GroupState();
		state.applyUpdate(new MemberDto[]{member("alice", new int[]{100, 200})});
		state.applyUpdate(new MemberDto[]{member("alice", null)});

		GroupMember alice = state.getMember("alice");
		assertNotNull(alice);
		assertEquals(100, alice.getXp(GroupSkill.AGILITY));
	}

	@Test
	public void matchesNamesAcrossTheNonBreakingSpaceTheClientUses()
	{
		assertTrue(GroupState.namesMatch("Rat von Hat", "Rat\u00a0von\u00a0Hat"));
		assertTrue(GroupState.namesMatch("skreech king", "Skreech King"));
	}

	private static MemberDto updatedAt(String name, String lastUpdated)
	{
		MemberDto dto = member(name, null);
		dto.last_updated = lastUpdated;
		return dto;
	}

	@Test
	public void aResponseFromBeforeAResetIsDropped()
	{
		// A poll still in flight when the credentials change must not bring the old group
		// back, nor move the cursor so that the new group's first fetch skips its history.
		GroupState state = new GroupState();
		int before = state.getGeneration();
		state.reset();

		assertFalse(state.applyUpdate(
			new MemberDto[]{updatedAt("alice", "2026-10-13T12:00:00Z")}, before));
		assertTrue(state.isEmpty());
		assertEquals("1970-01-01T00:00:00Z", state.getFromTime());
	}

	@Test
	public void aResponseFromTheCurrentGenerationIsMerged()
	{
		GroupState state = new GroupState();
		state.reset();

		assertTrue(state.applyUpdate(new MemberDto[]{member("alice", null)}, state.getGeneration()));
		assertNotNull(state.getMember("alice"));
	}

	@Test
	public void theNewestMemberRepeatedByTheInclusiveCursorIsNotAChange()
	{
		GroupState state = new GroupState();
		MemberDto first = updatedAt("Alice", "2026-10-13T11:24:54.063221Z");
		first.skills = new int[]{1, 2, 3};
		assertTrue(state.applyUpdate(new MemberDto[]{first}));

		// from_time is inclusive, so the next poll hands back the same fields again.
		MemberDto again = updatedAt("Alice", "2026-10-13T11:24:54.063221Z");
		again.skills = new int[]{1, 2, 3};
		assertFalse(state.applyUpdate(new MemberDto[]{again}));

		MemberDto newer = updatedAt("Alice", "2026-10-13T11:25:00Z");
		newer.skills = new int[]{1, 2, 4};
		assertTrue(state.applyUpdate(new MemberDto[]{newer}));
	}

	@Test
	public void theCursorNeverMovesBackwards()
	{
		GroupState state = new GroupState();
		state.applyUpdate(new MemberDto[]{updatedAt("alice", "2026-10-13T12:00:05Z")});
		assertEquals("2026-10-13T12:00:05Z", state.getFromTime());

		// An older timestamp, or none at all, leaves the cursor where it is.
		state.applyUpdate(new MemberDto[]{updatedAt("alice", "2026-10-13T12:00:01Z")});
		assertEquals("2026-10-13T12:00:05Z", state.getFromTime());
		state.applyUpdate(new MemberDto[]{member("alice", null)});
		assertEquals("2026-10-13T12:00:05Z", state.getFromTime());

		state.applyUpdate(new MemberDto[]{updatedAt("alice", "2026-10-13T12:00:09Z")});
		assertEquals("2026-10-13T12:00:09Z", state.getFromTime());
	}

	private static GroupMember.Interaction interactionSecondsAgo(GroupState state, long seconds)
	{
		MemberDto dto = member("alice", null);
		dto.interacting = new MemberDto.InteractingDto();
		dto.interacting.name = "Goblin";
		dto.interacting.last_updated = Instant.now().minusSeconds(seconds).toString();
		state.applyUpdate(new MemberDto[]{dto});
		return state.getMember("alice").getInteraction();
	}

	@Test
	public void interactionsStayCurrentForLongerAtSlowRefreshRates()
	{
		// At a 30 second refresh an interaction is often 20 seconds old when it arrives.
		GroupState state = new GroupState();
		assertTrue(interactionSecondsAgo(state, 20).isStale());

		state.setPollSeconds(30);
		assertFalse(interactionSecondsAgo(state, 20).isStale());
		assertTrue(interactionSecondsAgo(state, 40).isStale());
	}

	@Test
	public void dropsMembersTheServerNoLongerLists()
	{
		GroupState state = new GroupState();
		state.applyUpdate(new MemberDto[]{member("alice", null), member("bob", null)});
		assertEquals(2, state.getMembers().size());

		state.applyUpdate(new MemberDto[]{member("alice", null)});
		assertEquals(1, state.getMembers().size());
	}
}
