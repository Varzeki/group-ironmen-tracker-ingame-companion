package com.groupironmencompanion.bank;

import java.awt.Color;
import java.text.NumberFormat;
import java.util.Locale;

/** Shared colours and number formatting for the group bank window. */
final class BankStyle
{
	static final Color BACKGROUND = new Color(45, 39, 30, 240);
	static final Color TITLE_BACKGROUND = new Color(30, 26, 20, 250);
	static final Color BORDER = new Color(94, 82, 62);
	static final Color TEXT = new Color(255, 217, 148);
	static final Color TEXT_DIM = new Color(170, 155, 125);
	static final Color TAB_ACTIVE = new Color(78, 68, 52, 255);
	static final Color TAB_IDLE = new Color(38, 33, 26, 255);
	static final Color HOVER = new Color(255, 255, 255, 40);
	static final Color SEARCH_BACKGROUND = new Color(24, 21, 16, 255);

	/** The bank's own separator lines, under its tabs and between its sections. */
	static final Color SEPARATOR = new Color(0x2e2b23);

	static final Color QUANTITY_LOW = new Color(255, 255, 0);
	static final Color QUANTITY_MID = Color.WHITE;
	static final Color QUANTITY_HIGH = new Color(0, 255, 128);

	private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance(Locale.ENGLISH);

	private BankStyle()
	{
	}

	static String commas(long value)
	{
		return NUMBERS.format(value);
	}

	/** The abbreviated quantity the game itself draws on a stack. */
	static String shortQuantity(long quantity)
	{
		if (quantity >= 10_000_000)
		{
			return (quantity / 1_000_000) + "M";
		}
		if (quantity >= 100_000)
		{
			return (quantity / 1000) + "K";
		}
		return Long.toString(quantity);
	}

	/** The colour the game uses for a stack size, by magnitude. */
	static Color quantityColour(long quantity)
	{
		if (quantity >= 10_000_000)
		{
			return QUANTITY_HIGH;
		}
		if (quantity >= 100_000)
		{
			return QUANTITY_MID;
		}
		return QUANTITY_LOW;
	}

	/** Compact gp, e.g. 4.2m. */
	static String gp(long value)
	{
		if (value < 0)
		{
			return "-" + gp(-value);
		}
		if (value >= 1_000_000_000L)
		{
			return String.format(Locale.ENGLISH, "%.1fb", value / 1_000_000_000.0);
		}
		if (value >= 1_000_000L)
		{
			return String.format(Locale.ENGLISH, "%.1fm", value / 1_000_000.0);
		}
		if (value >= 1000L)
		{
			return String.format(Locale.ENGLISH, "%.1fk", value / 1000.0);
		}
		return Long.toString(value);
	}
}
