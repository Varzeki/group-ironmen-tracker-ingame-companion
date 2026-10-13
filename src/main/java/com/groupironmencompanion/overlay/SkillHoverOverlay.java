package com.groupironmencompanion.overlay;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupSkill;
import com.groupironmencompanion.data.GroupState;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Adds the group's levels to the Skills tab.
 * <p>
 * Hovering a skill draws a panel listing every member's level, experience and experience to
 * the next level, each in that member's colour.
 * <p>
 * The panel is deliberately <em>not</em> a mouse-following tooltip. It is anchored beside the
 * Skills tab so it never covers the game's own experience tooltip, never runs off the edge of
 * the screen, and sits in the same place every time. The skill widgets are only read for
 * their on-screen bounds, to work out which one the mouse is over; nothing about the
 * interface is modified, hidden, resized or given a new click zone.
 */
@Singleton
public class SkillHoverOverlay extends Overlay
{
	private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance(Locale.ENGLISH);
	private static final Color DIM = new Color(0xB0, 0xB0, 0xB0);

	/** Nearly opaque so the panel stays legible over the game world behind it. */
	private static final Color BACKGROUND = new Color(22, 19, 15, 235);

	private static final int PANEL_WIDTH = 200;
	private static final int MIN_PANEL_WIDTH = 128;
	private static final int GAP = 4;

	/** Keeps the panel clear of the click-to-move text the client draws top-left. */
	private static final int TOP_MARGIN = 24;
	private static final int SIDE_MARGIN = 4;

	@Inject
	private Client client;

	@Inject
	private GroupState groupState;

	@Inject
	private GroupIronmenCompanionConfig config;

	@Inject
	private RuneLiteConfig runeLiteConfig;

	@Inject
	public SkillHoverOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.skillHover() || groupState.isEmpty())
		{
			return null;
		}

		// The union of the visible skill tiles is the real Skills grid. The interface's own
		// root component covers far more than the grid, so anchoring to it put the panel on
		// top of the very thing it is meant to sit beside.
		Rectangle grid = new Rectangle();
		GroupSkill hovered = scanSkills(grid);
		if (hovered == null || grid.isEmpty())
		{
			return null;
		}

		Placement placement = choosePlacement(grid);

		// A RuneLite panel, not a game interface, so it uses RuneLite's interface font: the
		// one the user picked for overlay panels. Being a dynamic overlay it would otherwise
		// get the font meant for text drawn over the game world.
		graphics.setFont(runeLiteConfig.interfaceFont().getFont());

		PanelComponent panel = buildPanel(hovered, placement.width);
		if (panel == null)
		{
			return null;
		}

		// The panel's height depends on how its text lays out, and the position depends on
		// the height, so measure first. Measuring means rendering, so it is done against a
		// scratch context clipped to nothing: same font and metrics, no pixels touched.
		Dimension size;
		Graphics2D scratch = (Graphics2D) graphics.create();
		try
		{
			scratch.setClip(0, 0, 0, 0);
			panel.setPreferredLocation(new Point(0, 0));
			size = panel.render(scratch);
		}
		finally
		{
			scratch.dispose();
		}

		if (size == null)
		{
			return null;
		}

		int canvasHeight = client.getCanvasHeight();
		int y = Math.max(TOP_MARGIN, Math.min(grid.y, canvasHeight - size.height - 4));
		panel.setPreferredLocation(new Point(placement.x, y));
		panel.render(graphics);

		return null;
	}

	/**
	 * Chooses which side of the Skills grid to sit on, and how wide to be.
	 * <p>
	 * The panel is sized to the gap it is going into rather than being a fixed width, because
	 * on a narrow client neither side has room for a full-width panel and the old behaviour
	 * was to give up and draw straight over the skills. Covering the game world is always
	 * preferable to covering the interface being read.
	 */
	private Placement choosePlacement(Rectangle grid)
	{
		int canvasWidth = client.getCanvasWidth();

		int leftSpace = grid.x - GAP - SIDE_MARGIN;
		int rightSpace = canvasWidth - (grid.x + grid.width) - GAP - SIDE_MARGIN;

		boolean useLeft;
		switch (config.skillHoverPosition())
		{
			case LEFT:
				useLeft = true;
				break;

			case RIGHT:
				useLeft = false;
				break;

			case SCREEN_CENTRE:
				int centred = Math.min(PANEL_WIDTH, canvasWidth - SIDE_MARGIN * 2);
				return new Placement(Math.max(SIDE_MARGIN, (canvasWidth - centred) / 2), centred);

			case AUTO:
			default:
				useLeft = leftSpace >= rightSpace;
				break;
		}

		int space = useLeft ? leftSpace : rightSpace;
		int width = Math.max(MIN_PANEL_WIDTH, Math.min(PANEL_WIDTH, space));

		int x = useLeft
			? grid.x - GAP - width
			: grid.x + grid.width + GAP;

		// Only clamp to the canvas, never back across the grid.
		x = Math.max(SIDE_MARGIN, Math.min(x, canvasWidth - width - SIDE_MARGIN));
		return new Placement(x, width);
	}

	private static final class Placement
	{
		private final int x;
		private final int width;

		Placement(int x, int width)
		{
			this.x = x;
			this.width = width;
		}
	}

	@Nullable
	private PanelComponent buildPanel(GroupSkill hovered, int width)
	{
		List<GroupMember> members = groupState.getMembers();
		if (members.isEmpty())
		{
			return null;
		}

		PanelComponent panel = new PanelComponent();
		panel.setPreferredSize(new Dimension(width, 0));
		panel.setBackgroundColor(BACKGROUND);
		panel.getChildren().add(TitleComponent.builder()
			.text(hovered.getDisplayName())
			.color(Color.WHITE)
			.build());

		boolean anyData = false;
		for (GroupMember member : members)
		{
			if (groupState.isLocalPlayer(member) && !config.skillHoverIncludeSelf())
			{
				continue;
			}
			if (!config.skillHoverOffline() && !member.isOnline(config.offlineAfterMinutes()))
			{
				continue;
			}
			if (addMember(panel, member, hovered))
			{
				anyData = true;
			}
		}

		return anyData ? panel : null;
	}

	/**
	 * Adds two lines for one member: name and level, then experience and the gap to the next
	 * level. Returns false when this member has never reported the skill.
	 */
	private boolean addMember(PanelComponent panel, GroupMember member, GroupSkill skill)
	{
		boolean isLocal = groupState.isLocalPlayer(member);

		int xp;
		int level;
		if (isLocal)
		{
			// Read your own values live so they stay correct between server polls.
			xp = client.getSkillExperience(skill.getSkill());
			level = Experience.getLevelForXp(xp);
		}
		else
		{
			xp = member.getXp(skill);
			level = member.getLevel(skill);
		}

		if (xp < 0 || level < 0)
		{
			return false;
		}

		String name = isLocal ? member.getName() + " (you)" : member.getName();
		panel.getChildren().add(LineComponent.builder()
			.left(name)
			.leftColor(member.getColour())
			.right("Lv " + level)
			.rightColor(member.getColour())
			.build());

		String toNext;
		if (level >= Experience.MAX_VIRT_LEVEL)
		{
			toNext = "maxed";
		}
		else
		{
			int remaining = Experience.getXpForLevel(level + 1) - xp;
			toNext = NUMBERS.format(remaining) + " to " + (level + 1);
		}

		panel.getChildren().add(LineComponent.builder()
			.left("  " + NUMBERS.format(xp) + " xp")
			.leftColor(DIM)
			.right(toNext)
			.rightColor(DIM)
			.build());

		return true;
	}

	/**
	 * Walks the skill tiles once, accumulating their union into {@code grid} and returning
	 * whichever one the mouse is over. Widgets are only read, never modified.
	 */
	@Nullable
	private GroupSkill scanSkills(Rectangle grid)
	{
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (mouse == null)
		{
			return null;
		}

		GroupSkill hovered = null;
		for (GroupSkill skill : GroupSkill.VALUES)
		{
			Widget widget = client.getWidget(skill.getComponentId());
			if (widget == null || widget.isHidden())
			{
				continue;
			}
			Rectangle bounds = widget.getBounds();
			if (bounds == null || bounds.isEmpty())
			{
				continue;
			}

			if (grid.isEmpty())
			{
				grid.setBounds(bounds);
			}
			else
			{
				grid.add(bounds);
			}

			if (bounds.contains(mouse.getX(), mouse.getY()))
			{
				hovered = skill;
			}
		}
		return hovered;
	}
}
