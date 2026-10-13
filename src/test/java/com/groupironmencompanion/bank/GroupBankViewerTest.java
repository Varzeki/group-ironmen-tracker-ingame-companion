package com.groupironmencompanion.bank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Covers how the All tab ranks stacks and what they add to the footer total. */
public class GroupBankViewerTest
{
	private static final int RUNE_SCIMITAR = 1333;
	private static final int FIRE_RUNE = 554;
	private static final int LOGS = 1511;

	@Test
	public void aStackThatLosesMoneyToAlchCountsAsNothing()
	{
		assertEquals(0, GroupBankViewer.stackValue(FIRE_RUNE, -180, 25_000));
		assertEquals(12_000, GroupBankViewer.stackValue(RUNE_SCIMITAR, 12_000, 1));
	}

	@Test
	public void coinsCountAtFaceValue()
	{
		// Coins alch for nothing, so their profit is minus the rune, but they are still money.
		assertEquals(250_000, GroupBankViewer.stackValue(ItemID.COINS, -180, 250_000));
	}

	@Test
	public void bigCheapStacksNoLongerSinkBelowEverythingElse()
	{
		// Before, 25k fire runes counted as -4.5m and 250k coins as -45m, both below a single log.
		Map<Integer, Long> profit = new HashMap<>();
		profit.put(RUNE_SCIMITAR, 12_000L);
		profit.put(FIRE_RUNE, -180L);
		profit.put(LOGS, -178L);
		profit.put(ItemID.COINS, -180L);

		List<GroupBankViewer.Entry> entries = new ArrayList<>(Arrays.asList(
			GroupBankViewer.Entry.item(FIRE_RUNE, 25_000, null),
			GroupBankViewer.Entry.item(RUNE_SCIMITAR, 1, null),
			GroupBankViewer.Entry.item(ItemID.COINS, 250_000, null),
			GroupBankViewer.Entry.item(LOGS, 1, null)));

		GroupBankViewer.sortByValue(entries, profit::get);

		assertEquals(ItemID.COINS, entries.get(0).getItemId());
		assertEquals(RUNE_SCIMITAR, entries.get(1).getItemId());
		// Both worth nothing now; the stable sort keeps them in their original order.
		assertEquals(FIRE_RUNE, entries.get(2).getItemId());
		assertEquals(LOGS, entries.get(3).getItemId());
	}
}
