package com.groupironmencompanion.data;

import com.groupironmencompanion.api.MemberDto;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
