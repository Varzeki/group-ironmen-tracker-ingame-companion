package com.groupironmencompanion.overlay;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import com.groupironmencompanion.activity.Activity;
import com.groupironmencompanion.activity.ActivityTracker;
import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupState;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;

/**
 * A compact, always-on summary of the group: each member's name and world, their hitpoints,
 * prayer and run energy as bars, and a best guess at what they are doing.
 * <p>
 * Frameless by default, in the game's own fonts and bar colours, so it reads as part of the
 * game rather than as a panel on top of it. Alt-drag moves it like any RuneLite overlay, and
 * alt-dragging its edge sets its width; the height follows the members shown.
 * <p>
 * It reads only data your group members' own clients published to your shared server, and
 * draws nothing in the game scene or on the minimap.
 */
@Singleton
public class GroupStatusOverlay extends Overlay
{
	/** Width until the user resizes it: wide enough for "Fishing at Catherby". */
	static final int DEFAULT_WIDTH = 140;

	/** The narrowest it can be dragged, which still fits a name and a world. */
	static final int MIN_WIDTH = 100;

	/** Space between one member and the next. */
	static final int MEMBER_GAP = 4;

	/** Space between the name's baseline and the top of the bars. */
	static final int NAME_GAP = 3;

	/** Space above the activity line. */
	static final int ACTIVITY_GAP = 1;

	/** Bar heights without numbers: hitpoints stands out, prayer and run are slim. */
	static final int HP_BAR_HEIGHT = 5;
	static final int PRAYER_BAR_HEIGHT = 3;
	static final int RUN_BAR_HEIGHT = 3;

	/** Padding inside the translucent backdrop, when it is on. */
	static final int BACKDROP_PADDING = 4;

	/** Gap between a bar and its number when numbers are drawn beside the bars. */
	static final int VALUE_GAP = 3;

	/** Hitpoints colours as the game's own health bars and the opponent information overlay use. */
	private static final Color HP_FILL = new Color(0, 146, 54);
	private static final Color HP_EMPTY = new Color(102, 15, 16);

	/** The core status bars plugin's prayer colour, and the game's run orb yellow. */
	private static final Color PRAYER_FILL = new Color(50, 200, 200);
	private static final Color RUN_FILL = new Color(199, 174, 0);

	/** The empty part of the prayer and run bars: dark enough to read, distinct from the outline. */
	private static final Color BAR_EMPTY = new Color(40, 40, 40);

	private static final Color OUTLINE = Color.BLACK;
	private static final Color SHADOW = Color.BLACK;
	private static final Color WORLD = new Color(0xB0, 0xB0, 0xB0);
	private static final Color ACTIVITY = new Color(0xC8, 0xC8, 0xC8);
	private static final Color OFFLINE = new Color(0x80, 0x80, 0x80);
	private static final Color VALUE = Color.WHITE;

	private static final String ELLIPSIS = "...";

	private final GroupState groupState;
	private final ActivityTracker activityTracker;
	private final GroupIronmenCompanionConfig config;

	/** Height of a digit in the small font, measured once, to centre numbers on a bar. */
	private int digitHeight = -1;

	@Inject
	public GroupStatusOverlay(GroupState groupState, ActivityTracker activityTracker,
		GroupIronmenCompanionConfig config)
	{
		this.groupState = groupState;
		this.activityTracker = activityTracker;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setResizable(true);
		setMinimumSize(MIN_WIDTH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.statusOverlay())
		{
			return null;
		}

		List<GroupMember> shown = new ArrayList<>();
		for (GroupMember member : groupState.getMembers())
		{
			boolean online = member.isOnline(config.offlineAfterMinutes());
			if (!online && !config.statusShowOffline())
			{
				continue;
			}
			if (groupState.isLocalPlayer(member) && !config.statusShowSelf())
			{
				continue;
			}
			shown.add(member);
		}
		if (shown.isEmpty())
		{
			return null;
		}

		Dimension preferred = getPreferredSize();
		int width = preferred == null ? DEFAULT_WIDTH : Math.max(MIN_WIDTH, preferred.width);
		boolean backdrop = config.statusBackdrop() == GroupIronmenCompanionConfig.StatusBackdrop.TRANSLUCENT;
		int padding = backdrop ? BACKDROP_PADDING : 0;
		int inner = width - 2 * padding;
		Instant now = Instant.now();

		// Measure first, so the backdrop can go down before anything is drawn on it.
		int height = layout(graphics, shown, padding, inner, now, false) + padding;
		if (backdrop)
		{
			graphics.setColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
			graphics.fillRect(0, 0, width, height);
		}
		layout(graphics, shown, padding, inner, now, true);

		return new Dimension(width, height);
	}

	/** Lays out every member from the top, drawing only when asked; returns the bottom edge. */
	private int layout(Graphics2D graphics, List<GroupMember> members, int padding, int width, Instant now,
		boolean paint)
	{
		int y = padding;
		for (int i = 0; i < members.size(); i++)
		{
			if (i > 0)
			{
				y += MEMBER_GAP;
			}
			y = member(graphics, members.get(i), padding, y, width, now, paint);
		}
		return y;
	}

	/** One member, from their name down; returns the bottom edge. */
	private int member(Graphics2D graphics, GroupMember member, int x, int y, int width, Instant now, boolean paint)
	{
		boolean online = member.isOnline(config.offlineAfterMinutes());
		Font bold = FontManager.getRunescapeBoldFont();
		Font small = FontManager.getRunescapeSmallFont();
		FontMetrics boldMetrics = graphics.getFontMetrics(bold);
		FontMetrics smallMetrics = graphics.getFontMetrics(small);

		// Name, with the world, or how long ago an offline member was seen, on the right.
		String right = null;
		if (!online)
		{
			right = member.getLastSeenText();
		}
		else if (member.hasStats())
		{
			right = "W" + member.getWorld();
		}
		int baseline = y + boldMetrics.getAscent();
		if (paint)
		{
			int rightWidth = right == null ? 0 : smallMetrics.stringWidth(right) + VALUE_GAP;
			if (right != null)
			{
				text(graphics, small, right, x + width - smallMetrics.stringWidth(right), baseline,
					online ? WORLD : OFFLINE);
			}
			String name = ellipsise(member.getName(), boldMetrics, width - rightWidth);
			text(graphics, bold, name, x, baseline, online ? member.getColour() : OFFLINE);
		}
		y = baseline;

		// Offline members are one grey line: their bars and activity would only be stale.
		if (!online)
		{
			return y + boldMetrics.getDescent();
		}

		if (config.statusShowVitals() && member.hasStats())
		{
			y += NAME_GAP;
			y = bars(graphics, member, x, y, width, paint);
		}
		else
		{
			y += boldMetrics.getDescent();
		}

		Activity activity = config.statusShowActivity() ? activityTracker.get(member.getName()) : null;
		if (activity != null)
		{
			y += ACTIVITY_GAP;
			int activityBaseline = y + smallMetrics.getAscent();
			if (paint)
			{
				String line = ellipsise(activity.describe(now, true), smallMetrics, width);
				text(graphics, small, line, x, activityBaseline, ACTIVITY);
			}
			y = activityBaseline + smallMetrics.getDescent();
		}
		return y;
	}

	/** The three vitals bars, as the user asked for them; returns the bottom edge. */
	private int bars(Graphics2D graphics, GroupMember member, int x, int y, int width, boolean paint)
	{
		Font small = FontManager.getRunescapeSmallFont();
		FontMetrics metrics = graphics.getFontMetrics(small);

		Bar[] bars = {
			new Bar(member.getHitpoints(), member.getMaxHitpoints(), HP_FILL, HP_EMPTY, HP_BAR_HEIGHT,
				member.getHitpoints() + "/" + member.getMaxHitpoints()),
			new Bar(member.getPrayer(), member.getMaxPrayer(), PRAYER_FILL, BAR_EMPTY, PRAYER_BAR_HEIGHT,
				member.getPrayer() + "/" + member.getMaxPrayer()),
			new Bar(member.getRunEnergyPercent(), 100, RUN_FILL, BAR_EMPTY, RUN_BAR_HEIGHT,
				member.getRunEnergyPercent() + "%"),
		};

		switch (config.statusValues())
		{
			case ON_BARS:
			{
				// Tall enough to carry a number, all three stacked inside one outline.
				int barHeight = digitHeight(graphics, small) + 4;
				int total = 1 + bars.length * (barHeight + 1);
				if (paint)
				{
					graphics.setColor(OUTLINE);
					graphics.fillRect(x, y, width, total);
				}
				int top = y + 1;
				for (Bar bar : bars)
				{
					if (paint)
					{
						fill(graphics, bar, x + 1, top, width - 2, barHeight);
						int textX = x + (width - metrics.stringWidth(bar.label)) / 2;
						text(graphics, small, bar.label, textX, top + (barHeight + digitHeight) / 2, VALUE);
					}
					top += barHeight + 1;
				}
				return y + total;
			}

			case BESIDE:
			{
				// Each bar on its own row, its number right-aligned in a column beside it.
				int column = metrics.stringWidth("999/999");
				int barWidth = width - column - VALUE_GAP;
				int rowHeight = digitHeight(graphics, small) + 2;
				int top = y;
				for (Bar bar : bars)
				{
					if (paint)
					{
						int outlineHeight = bar.height + 2;
						int outlineTop = top + (rowHeight - outlineHeight) / 2;
						graphics.setColor(OUTLINE);
						graphics.fillRect(x, outlineTop, barWidth, outlineHeight);
						fill(graphics, bar, x + 1, outlineTop + 1, barWidth - 2, bar.height);
						text(graphics, small, bar.label, x + width - metrics.stringWidth(bar.label),
							top + (rowHeight + digitHeight) / 2, VALUE);
					}
					top += rowHeight + 1;
				}
				return top - 1;
			}

			default:
			{
				// Bars only, stacked inside one shared 1px outline.
				int total = 1;
				for (Bar bar : bars)
				{
					total += bar.height + 1;
				}
				if (paint)
				{
					graphics.setColor(OUTLINE);
					graphics.fillRect(x, y, width, total);
					int top = y + 1;
					for (Bar bar : bars)
					{
						fill(graphics, bar, x + 1, top, width - 2, bar.height);
						top += bar.height + 1;
					}
				}
				return y + total;
			}
		}
	}

	private static void fill(Graphics2D graphics, Bar bar, int x, int y, int width, int height)
	{
		double fraction = bar.max <= 0 || bar.current < 0 ? 0 : Math.min(1, (double) bar.current / bar.max);
		int filled = (int) Math.round(width * fraction);
		graphics.setColor(bar.empty);
		graphics.fillRect(x + filled, y, width - filled, height);
		graphics.setColor(bar.fill);
		graphics.fillRect(x, y, filled, height);
	}

	/** Text with the game's one-pixel drop shadow. */
	private static void text(Graphics2D graphics, Font font, String text, int x, int y, Color colour)
	{
		graphics.setFont(font);
		graphics.setColor(SHADOW);
		graphics.drawString(text, x + 1, y + 1);
		graphics.setColor(colour);
		graphics.drawString(text, x, y);
	}

	/** Shortens text to fit a width, marking the cut. */
	static String ellipsise(String text, FontMetrics metrics, int width)
	{
		if (metrics.stringWidth(text) <= width)
		{
			return text;
		}
		int room = width - metrics.stringWidth(ELLIPSIS);
		int end = text.length();
		while (end > 0 && metrics.stringWidth(text.substring(0, end)) > room)
		{
			end--;
		}
		return text.substring(0, end).trim() + ELLIPSIS;
	}

	/**
	 * How tall a digit is in the small font. The font's ascent includes room for accents that
	 * numbers never use, so centring on it would sit every number too low.
	 */
	private int digitHeight(Graphics2D graphics, Font font)
	{
		if (digitHeight < 0)
		{
			digitHeight = (int) Math.ceil(font.createGlyphVector(graphics.getFontRenderContext(), "0")
				.getVisualBounds().getHeight());
		}
		return digitHeight;
	}

	private static final class Bar
	{
		final int current;
		final int max;
		final Color fill;
		final Color empty;
		final int height;
		final String label;

		Bar(int current, int max, Color fill, Color empty, int height, String label)
		{
			this.current = current;
			this.max = max;
			this.fill = fill;
			this.empty = empty;
			this.height = height;
			this.label = label;
		}
	}
}
