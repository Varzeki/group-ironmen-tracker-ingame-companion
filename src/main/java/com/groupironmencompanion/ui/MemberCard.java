package com.groupironmencompanion.ui;

import com.groupironmencompanion.activity.Activity;
import com.groupironmencompanion.data.GroupMember;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.Instant;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.ThinProgressBar;

/**
 * One group member's live vitals: hitpoints, prayer, run energy, world, how long ago their
 * client last reported, and optionally a best guess at what they are doing.
 */
class MemberCard extends JPanel
{
	private static final Color HP_COLOUR = new Color(199, 51, 51);
	private static final Color PRAYER_COLOUR = new Color(87, 160, 230);
	private static final Color RUN_COLOUR = new Color(224, 190, 62);
	private static final Color OFFLINE_COLOUR = new Color(120, 120, 120);

	private final JLabel nameLabel = new JLabel();
	private final JLabel statusLabel = new JLabel();
	private final JLabel activityLabel = new JLabel();

	private final ThinProgressBar hitpoints = new ThinProgressBar();
	private final ThinProgressBar prayer = new ThinProgressBar();
	private final ThinProgressBar run = new ThinProgressBar();

	private final JLabel hitpointsText = new JLabel();
	private final JLabel prayerText = new JLabel();
	private final JLabel runText = new JLabel();

	MemberCard()
	{
		setLayout(new BorderLayout(0, 2));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 6, 6, 6)));

		nameLabel.setFont(FontManager.getRunescapeBoldFont());

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setHorizontalAlignment(SwingConstants.RIGHT);

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.add(nameLabel, BorderLayout.WEST);
		header.add(statusLabel, BorderLayout.EAST);

		JPanel bars = new JPanel();
		bars.setLayout(new BoxLayout(bars, BoxLayout.Y_AXIS));
		bars.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bars.add(bar("HP", hitpoints, hitpointsText, HP_COLOUR));
		bars.add(bar("Pray", prayer, prayerText, PRAYER_COLOUR));
		bars.add(bar("Run", run, runText, RUN_COLOUR));

		activityLabel.setFont(FontManager.getRunescapeSmallFont());
		activityLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		activityLabel.setToolTipText("A best guess from the experience, position, items, quests and "
			+ "interactions their tracker shares. It can be wrong.");

		add(header, BorderLayout.NORTH);
		add(bars, BorderLayout.CENTER);
		add(activityLabel, BorderLayout.SOUTH);
	}

	private JPanel bar(String caption, ThinProgressBar progress, JLabel value, Color colour)
	{
		progress.setForeground(colour);
		progress.setMaximumValue(100);
		progress.setValue(0);
		progress.setPreferredSize(new Dimension(0, 5));

		JLabel captionLabel = new JLabel(caption);
		captionLabel.setFont(FontManager.getRunescapeSmallFont());
		captionLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		value.setHorizontalAlignment(SwingConstants.RIGHT);

		JPanel labels = new JPanel(new GridLayout(1, 2));
		labels.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		labels.add(captionLabel);
		labels.add(value);

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrapper.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
		wrapper.add(labels, BorderLayout.NORTH);
		wrapper.add(progress, BorderLayout.SOUTH);
		return wrapper;
	}

	/**
	 * @param activity what they seem to be doing, or null to hide the line
	 * @param now the time to count an idle spell to
	 */
	void update(GroupMember member, boolean online, Activity activity, Instant now, boolean isYou)
	{
		nameLabel.setText(isYou ? member.getName() + " (you)" : member.getName());
		nameLabel.setForeground(online ? member.getColour() : OFFLINE_COLOUR);

		if (online && member.hasStats())
		{
			statusLabel.setText("w" + member.getWorld());
		}
		else
		{
			statusLabel.setText(member.getLastSeenText());
		}

		if (member.hasStats())
		{
			setBar(hitpoints, hitpointsText, member.getHitpoints(), member.getMaxHitpoints(), online, false);
			setBar(prayer, prayerText, member.getPrayer(), member.getMaxPrayer(), online, false);
			setBar(run, runText, member.getRunEnergyPercent(), 100, online, true);
		}
		else
		{
			setBar(hitpoints, hitpointsText, -1, -1, online, false);
			setBar(prayer, prayerText, -1, -1, online, false);
			setBar(run, runText, -1, -1, online, true);
		}

		if (activity != null)
		{
			activityLabel.setText(activity.describe(now, true));
			activityLabel.setForeground(online ? ColorScheme.LIGHT_GRAY_COLOR : OFFLINE_COLOUR);
			activityLabel.setVisible(true);
		}
		else
		{
			activityLabel.setVisible(false);
		}
	}

	private void setBar(ThinProgressBar bar, JLabel text, int current, int max, boolean online, boolean asPercent)
	{
		if (current < 0 || max <= 0)
		{
			bar.setValue(0);
			text.setText("-");
			return;
		}

		bar.setMaximumValue(max);
		bar.setValue(online ? current : 0);
		text.setText(asPercent ? current + "%" : current + "/" + max);
	}
}
