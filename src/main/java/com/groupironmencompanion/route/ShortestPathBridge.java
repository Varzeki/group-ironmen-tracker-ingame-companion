package com.groupironmencompanion.route;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

/**
 * Drives the Shortest Path plugin.
 * <p>
 * Hub plugins each get their own classloader, so this cannot compile against
 * {@code shortestpath.ShortestPathPlugin} or cast it, and reflection is forbidden.
 * RuneLite's answer is {@link PluginMessage}: a namespaced event carrying a plain data map.
 * Everything crossing the boundary is either a JDK type or {@link WorldPoint}, which lives in
 * runelite-api on the shared parent classloader, so both sides agree on it. No dependency is
 * added to the build.
 * <p>
 * Contract, from Shortest Path's {@code onPluginMessage}:
 * <pre>
 *   namespace "shortestpath"
 *   name      "path"   data { "start": WorldPoint|Integer|null,
 *                             "target": WorldPoint|Integer|Set,
 *                             "config": Map&lt;String,Object&gt; }
 *   name      "clear"  data {}
 *   name      "getTarget"      data { "id": Object }
 *     answered by "currentTarget" data { "id": Object, "set": Boolean,
 *                                      "target": List&lt;WorldPoint&gt; when set }
 * </pre>
 * If Shortest Path is not installed then nothing subscribes to the event and the post is a
 * harmless no-op, so this degrades gracefully by construction.
 */
@Slf4j
@Singleton
public class ShortestPathBridge
{
	private static final String NAMESPACE = "shortestpath";
	private static final String MESSAGE_PATH = "path";
	private static final String MESSAGE_CLEAR = "clear";
	private static final String MESSAGE_GET_TARGET = "getTarget";
	private static final String MESSAGE_CURRENT_TARGET = "currentTarget";

	private static final String KEY_START = "start";
	private static final String KEY_TARGET = "target";
	private static final String KEY_ID = "id";
	private static final String KEY_SET = "set";

	/** Marks this plugin's target requests, so answers to anyone else's are ignored. */
	private static final String REQUEST_ID = "group-ironmen-companion";

	private final EventBus eventBus;

	@Inject
	public ShortestPathBridge(EventBus eventBus)
	{
		this.eventBus = eventBus;
	}

	/**
	 * Paths from the player's current position to {@code target}. A null start lets Shortest
	 * Path use wherever the player happens to be.
	 */
	public void pathTo(WorldPoint target)
	{
		if (target == null)
		{
			return;
		}

		Map<String, Object> data = new HashMap<>();
		data.put(KEY_START, null);
		data.put(KEY_TARGET, target);

		log.debug("Requesting path to {}", target);
		eventBus.post(new PluginMessage(NAMESPACE, MESSAGE_PATH, data));
	}

	/** Clears any path currently drawn. */
	public void clear()
	{
		eventBus.post(new PluginMessage(NAMESPACE, MESSAGE_CLEAR));
	}

	/** Asks Shortest Path what it is pathing to. The answer arrives later as a message. */
	public void requestTarget()
	{
		Map<String, Object> data = new HashMap<>();
		data.put(KEY_ID, REQUEST_ID);
		eventBus.post(new PluginMessage(NAMESPACE, MESSAGE_GET_TARGET, data));
	}

	/**
	 * Reads Shortest Path's answer to {@link #requestTarget}.
	 *
	 * @return whether it is still pathing to {@code target}, or null when the message is not
	 *         an answer to this plugin's request
	 */
	@Nullable
	public Boolean isPathingTo(PluginMessage message, WorldPoint target)
	{
		if (!NAMESPACE.equals(message.getNamespace()) || !MESSAGE_CURRENT_TARGET.equals(message.getName()))
		{
			return null;
		}
		Map<String, Object> data = message.getData();
		if (data == null || !REQUEST_ID.equals(data.get(KEY_ID)))
		{
			return null;
		}

		Object targets = data.get(KEY_TARGET);
		return Boolean.TRUE.equals(data.get(KEY_SET))
			&& targets instanceof Collection
			&& ((Collection<?>) targets).contains(target);
	}
}
