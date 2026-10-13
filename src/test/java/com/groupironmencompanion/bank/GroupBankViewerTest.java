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
	public void valueIsTheRawAlchValueWithCoinsAtFaceValue()
	{
		assertEquals(75_000, GroupBankViewer.stackValue(FIRE_RUNE, 3, 25_000));
		assertEquals(12_000, GroupBankViewer.stackValue(RUNE_SCIMITAR, 12_000, 1));
		// Coins alch for nothing, but they are still money.
		assertEquals(250_000, GroupBankViewer.stackValue(ItemID.COINS, 0, 250_000));
	}

	@Test
	public void profitCountsOnlyStacksThatAlchAtAProfit()
	{
		assertEquals(0, GroupBankViewer.stackProfit(FIRE_RUNE, -180, 25_000));
		assertEquals(11_820, GroupBankViewer.stackProfit(RUNE_SCIMITAR, 11_820, 1));
		assertEquals(0, GroupBankViewer.stackProfit(ItemID.COINS, -180, 250_000));
	}

	@Test
	public void bigCheapStacksNoLongerSinkBelowEverythingElse()
	{
		// Sorted by alch profit, 25k fire runes counted as -4.5m and 250k coins as -45m, both
		// below a single log. By value they rank by what they are worth.
		Map<Integer, Long> alch = new HashMap<>();
		alch.put(RUNE_SCIMITAR, 12_000L);
		alch.put(FIRE_RUNE, 3L);
		alch.put(LOGS, 2L);
		alch.put(ItemID.COINS, 0L);

		List<GroupBankViewer.Entry> entries = new ArrayList<>(Arrays.asList(
			GroupBankViewer.Entry.item(LOGS, 1, null),
			GroupBankViewer.Entry.item(FIRE_RUNE, 25_000, null),
			GroupBankViewer.Entry.item(RUNE_SCIMITAR, 1, null),
			GroupBankViewer.Entry.item(ItemID.COINS, 250_000, null)));

		GroupBankViewer.sortByValue(entries, alch::get);

		assertEquals(ItemID.COINS, entries.get(0).getItemId());
		assertEquals(FIRE_RUNE, entries.get(1).getItemId());
		assertEquals(RUNE_SCIMITAR, entries.get(2).getItemId());
		assertEquals(LOGS, entries.get(3).getItemId());
	}
}
