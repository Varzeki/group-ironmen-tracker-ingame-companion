package com.groupironmencompanion.map;

import com.groupironmencompanion.ClientInputState;
import com.groupironmencompanion.GroupIronmenCompanionConfig;
import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupState;
import com.groupironmencompanion.route.RouteTracker;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseAdapter;

/**
 * Clicks on the world map's group list.
 * <p>
 * Left-click jumps the map to that member, right-click starts or stops routing to them.
 * Input is only consumed when the pointer is genuinely on one of the panel's rows, so the
 * rest of the world map keeps behaving normally.
 * <p>
 * Everything that touches the client is dispatched to the client thread. Handlers here run
 * on AWT, where calling into the client is illegal and fails silently.
 */
@Singleton
public class GroupMapInput extends MouseAdapter
{
	/** The mouse buttons whose press was swallowed here, one bit per button. */
	private int swallowedButtons;

	@Inject
	private GroupMapOverlay overlay;

	@Inject
	private GroupState groupState;

	@Inject
	private GroupIronmenCompanionConfig config;

	@Inject
	private RouteTracker routeTracker;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientInputState inputState;

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		// While the game's right-click menu is open, a click is picking one of its options.
		if (!config.mapPanel() || event.isAltDown() || !inputState.isLoggedIn() || inputState.isMenuOpen())
		{
			return event;
		}

		String name = overlay.memberAt(event.getPoint());
		if (name == null)
		{
			return event;
		}

		boolean rightClick = javax.swing.SwingUtilities.isRightMouseButton(event);
		if (rightClick && !config.mapPanelRouting())
		{
			return event;
		}

		clientThread.invoke(() ->
		{
			GroupMember member = groupState.getMember(name);
			if (member == null)
			{
				return;
			}

			if (rightClick)
			{
				routeTracker.toggle(member);
				return;
			}

			overlay.jumpTo(member);
		});

		swallowedButtons |= buttonBit(event);
		event.consume();
		return event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		if ((swallowedButtons & buttonBit(event)) != 0)
		{
			event.consume();
		}
		return event;
	}

	/**
	 * Only a release whose press was swallowed here is swallowed too: one that began on the
	 * game, such as the end of a middle-button camera drag, must reach the game wherever it
	 * lands, or the game never hears the button come up.
	 */
	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		if ((swallowedButtons & buttonBit(event)) != 0)
		{
			swallowedButtons &= ~buttonBit(event);
			event.consume();
		}
		return event;
	}

	private static int buttonBit(MouseEvent event)
	{
		return 1 << Math.max(0, Math.min(event.getButton(), 30));
	}
}
