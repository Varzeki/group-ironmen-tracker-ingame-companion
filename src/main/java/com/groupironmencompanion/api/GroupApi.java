package com.groupironmencompanion.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.groupironmencompanion.GroupIronmenCompanionConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.config.ConfigManager;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Talks to the groupiron.men API, or to a self-hosted instance of it.
 * <p>
 * Every request goes to the base URL the user configured and carries the group token the
 * user supplied. Nothing is sent anywhere else, and no data leaves the client at all unless
 * the fallback uploader is explicitly active.
 * <p>
 * The shared RuneLite HTTP client is reused for its connection pool, but with redirects
 * disabled and a hard call timeout. Redirects matter because the base URL is user-supplied
 * and requests carry a bearer token: a redirect could otherwise hand that token to a host
 * the user never configured. The timeout matters because a hung server would otherwise pin
 * the polling thread indefinitely.
 */
@Slf4j
@Singleton
public class GroupApi
{
	public static final String PUBLIC_BASE_URL = "https://groupiron.men";

	/** Config group of the official tracker plugin, read so credentials need entering once. */
	public static final String TRACKER_CONFIG_GROUP = "GroupIronmenTracker";

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String USER_AGENT =
		"GroupIronmenCompanion/1.0.0 RuneLite/" + RuneLiteProperties.getVersion();

	private static final int CALL_TIMEOUT_SECONDS = 20;

	/** Guards against a hostile or broken server returning an unbounded body. */
	private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

	@Inject
	private OkHttpClient sharedClient;

	@Inject
	private Gson gson;

	@Inject
	private GroupIronmenCompanionConfig config;

	@Inject
	private ConfigManager configManager;

	private volatile OkHttpClient client;

	private OkHttpClient client()
	{
		OkHttpClient local = client;
		if (local == null)
		{
			synchronized (this)
			{
				local = client;
				if (local == null)
				{
					client = local = sharedClient.newBuilder()
						.followRedirects(false)
						.followSslRedirects(false)
						.callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.build();
				}
			}
		}
		return local;
	}

	// ------------------------------------------------------------------
	// Credentials
	// ------------------------------------------------------------------

	public String getBaseUrl()
	{
		String override = config.baseUrlOverride().trim();
		if (override.isEmpty())
		{
			// A group that self-hosts and only configured the official tracker plugin would
			// otherwise be sent to the public server, so inherit its override too.
			override = trackerConfig("baseUrlOverride");
		}
		if (override.isEmpty())
		{
			return PUBLIC_BASE_URL;
		}
		// Strip a trailing slash so URL building stays predictable.
		return override.endsWith("/") ? override.substring(0, override.length() - 1) : override;
	}

	/**
	 * The group name to use, preferring this plugin's own setting and falling back to the
	 * official tracker plugin's configuration so an existing setup works out of the box.
	 */
	public String getGroupName()
	{
		String own = config.groupName().trim();
		return own.isEmpty() ? trackerConfig("groupName") : own;
	}

	public String getGroupToken()
	{
		String own = config.groupToken().trim();
		return own.isEmpty() ? trackerConfig("groupToken") : own;
	}

	/**
	 * True when the official tracker plugin has both a group name and a token. Without them
	 * it uploads nothing, however long it is enabled.
	 */
	public boolean trackerHasCredentials()
	{
		return !trackerConfig("groupName").isEmpty() && !trackerConfig("groupToken").isEmpty();
	}

	private String trackerConfig(String key)
	{
		try
		{
			String value = configManager.getConfiguration(TRACKER_CONFIG_GROUP, key);
			return value == null ? "" : value.trim();
		}
		catch (Exception e)
		{
			log.debug("Could not read tracker plugin config key {}", key, e);
			return "";
		}
	}

	public boolean hasCredentials()
	{
		return !getGroupName().isEmpty() && !getGroupToken().isEmpty();
	}

	// ------------------------------------------------------------------
	// URL building
	// ------------------------------------------------------------------

	/**
	 * Builds a group endpoint URL. The group name goes in as a path segment rather than
	 * being concatenated, so names containing spaces or other reserved characters are
	 * encoded correctly and cannot alter the shape of the path.
	 */
	@Nullable
	private HttpUrl.Builder endpoint(String groupName, String action)
	{
		HttpUrl base = HttpUrl.parse(getBaseUrl());
		if (base == null)
		{
			log.warn("Group Ironmen Companion: the configured server URL is not a valid URL");
			return null;
		}
		return base.newBuilder()
			.addPathSegment("api")
			.addPathSegment("group")
			.addPathSegment(groupName)
			.addPathSegment(action);
	}

	private Request.Builder authorised(HttpUrl url, String token)
	{
		return new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.header("Accept", "application/json")
			.header("Authorization", token);
	}

	// ------------------------------------------------------------------
	// Requests
	// ------------------------------------------------------------------

	/**
	 * Fetches everything that changed since {@code fromTime}.
	 *
	 * @return the members array, or null when the request failed
	 * @throws NotAuthorisedException when the server rejected the token
	 */
	@Nullable
	public MemberDto[] getGroupData(String fromTime) throws NotAuthorisedException
	{
		String groupName = getGroupName();
		String token = getGroupToken();
		if (groupName.isEmpty() || token.isEmpty())
		{
			return null;
		}

		HttpUrl.Builder builder = endpoint(groupName, "get-group-data");
		if (builder == null)
		{
			return null;
		}
		HttpUrl url = builder.addQueryParameter("from_time", fromTime).build();

		try (Response response = client().newCall(authorised(url, token).get().build()).execute())
		{
			if (response.code() == 401 || response.code() == 403)
			{
				throw new NotAuthorisedException();
			}
			if (!response.isSuccessful())
			{
				log.debug("get-group-data returned {}", response.code());
				return null;
			}

			ResponseBody body = response.body();
			if (body == null)
			{
				return null;
			}
			// A declared length is only present when the server does not use chunked
			// encoding, and most do, so the stream itself has to be bounded rather than
			// trusting contentLength. Reading past the cap fails the parse instead of
			// letting an unbounded body allocate without limit.
			Reader reader = new InputStreamReader(
				new BoundedInputStream(body.byteStream(), MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
			return gson.fromJson(reader, MemberDto[].class);
		}
		catch (JsonSyntaxException e)
		{
			// Deliberately does not log the body: it is attacker-influenced and large.
			log.warn("Group Ironmen Companion: the server response was not valid JSON");
			return null;
		}
		catch (IOException e)
		{
			log.debug("get-group-data failed: {}", e.toString());
			return null;
		}
	}

	/** Checks that the given character is a member of the configured group. */
	public boolean isPlayerInGroup(String playerName)
	{
		String groupName = getGroupName();
		String token = getGroupToken();
		if (groupName.isEmpty() || token.isEmpty() || playerName == null)
		{
			return false;
		}

		HttpUrl.Builder builder = endpoint(groupName, "am-i-in-group");
		if (builder == null)
		{
			return false;
		}
		HttpUrl url = builder.addQueryParameter("member_name", playerName).build();

		try (Response response = client().newCall(authorised(url, token).get().build()).execute())
		{
			return response.isSuccessful();
		}
		catch (IOException e)
		{
			log.debug("am-i-in-group failed: {}", e.toString());
			return false;
		}
	}

	/**
	 * Posts a member update. Only ever called by the fallback uploader, and only when the
	 * official tracker plugin is not running.
	 *
	 * @return true when the server accepted the update
	 * @throws NotAuthorisedException when the server rejected the token
	 */
	public boolean updateGroupMember(Object payload) throws NotAuthorisedException
	{
		String groupName = getGroupName();
		String token = getGroupToken();
		if (groupName.isEmpty() || token.isEmpty())
		{
			return false;
		}

		HttpUrl.Builder builder = endpoint(groupName, "update-group-member");
		if (builder == null)
		{
			return false;
		}

		RequestBody body = RequestBody.create(JSON, gson.toJson(payload));
		try (Response response = client().newCall(authorised(builder.build(), token).post(body).build()).execute())
		{
			if (response.code() == 401 || response.code() == 403)
			{
				throw new NotAuthorisedException();
			}
			return response.isSuccessful();
		}
		catch (IOException e)
		{
			log.debug("update-group-member failed: {}", e.toString());
			return false;
		}
	}

	/** Fails the read once more than {@code limit} bytes have been consumed. */
	private static final class BoundedInputStream extends InputStream
	{
		private final InputStream delegate;
		private final long limit;
		private long read;

		BoundedInputStream(InputStream delegate, long limit)
		{
			this.delegate = delegate;
			this.limit = limit;
		}

		@Override
		public int read() throws IOException
		{
			int value = delegate.read();
			if (value >= 0)
			{
				count(1);
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException
		{
			int got = delegate.read(buffer, offset, length);
			if (got > 0)
			{
				count(got);
			}
			return got;
		}

		private void count(int bytes) throws IOException
		{
			read += bytes;
			if (read > limit)
			{
				throw new IOException("response exceeded " + limit + " bytes");
			}
		}

		@Override
		public void close() throws IOException
		{
			delegate.close();
		}
	}

	/** Raised when the server rejects the configured group token. */
	public static class NotAuthorisedException extends Exception
	{
		public NotAuthorisedException()
		{
			super("The group name or token was rejected by the server");
		}
	}
}
