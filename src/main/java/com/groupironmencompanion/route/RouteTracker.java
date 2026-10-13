package com.groupironmencompanion.route;

import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupState;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/**
 * Keeps a Shortest Path route pointed at a moving group member.
 * <p>
 * The target is stored as a member name rather than a fixed point, so as their position
 * updates the route is re-issued to follow them. It is retargeted only when they have
 * actually moved a meaningful distance, because re-pathing costs a search and their
 * coordinates jitter by a tile as they walk.
 * <p>
 * Arrival is judged against the member's <em>true</em> position, including plane, not the
 * surface entrance drawn on the overworld map: following someone into a dungeon should not
 * count as done the moment you reach the ladder.
 */
@Singleton
public class RouteTracker
{
	/** How far the target must move before the route is recalculated, in tiles. */
	private static final int RETARGET_DISTANCE = 8;

	/** How close counts as having arrived, in tiles. */
	private static final int ARRIVAL_DISTANCE = 3;

	@Inject
	private Client client;

	@Inject
	private GroupState groupState;

	@Inject
	private ShortestPathBridge bridge;

	private volatile String targetName;
	private WorldPoint lastRouted;

	/** The member currently being routed to, or null when no route is active. */
	@Nullable
	public String getTargetName()
	{
		return targetName;
	}

	public boolean isRoutingTo(GroupMember member)
	{
		return member != null && GroupState.namesMatch(targetName, member.getName());
	}

	/** Starts routing to a member, or stops if they were already the target. */
	public void toggle(GroupMember member)
	{
		if (member == null)
		{
			return;
		}
		if (isRoutingTo(member))
		{
			stop();
			return;
		}

		targetName = member.getName();
		lastRouted = null;
		update();
	}

	public void stop()
	{
		if (targetName == null)
		{
			return;
		}
		targetName = null;
		lastRouted = null;
		bridge.clear();
	}

	/**
	 * Re-points the route at the target's latest position, and ends it on arrival. Safe to
	 * call every tick; it only talks to Shortest Path when something has actually changed.
	 * Must run on the client thread.
	 */
	public void update()
	{
		String name = targetName;
		if (name == null)
		{
			return;
		}

		GroupMember member = groupState.getMember(name);
		if (member == null)
		{
			// They left the group, so there is nothing left to follow.
			stop();
			return;
		}

		WorldPoint target = member.getWorldPoint();
		if (target == null)
		{
			return;
		}

		// Members upload real coordinates, so compare against ours, not instance ones.
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : WorldPoint.fromLocalInstance(client, local.getLocalLocation());
		if (here != null && here.getPlane() == target.getPlane()
			&& here.distanceTo2D(target) <= ARRIVAL_DISTANCE)
		{
			stop();
			return;
		}

		if (lastRouted == null || lastRouted.distanceTo2D(target) > RETARGET_DISTANCE
			|| lastRouted.getPlane() != target.getPlane())
		{
			lastRouted = target;
			bridge.pathTo(target);
		}
	}
}
