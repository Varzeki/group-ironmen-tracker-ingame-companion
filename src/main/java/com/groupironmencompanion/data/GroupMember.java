package com.groupironmencompanion.data;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.Experience;
import net.runelite.api.coords.WorldPoint;

/**
 * A group member's most recent known state.
 * <p>
 * The server sends deltas: a field that has not changed since the last poll comes back null,
 * so every setter here is only called when there is genuinely new data and old values persist.
 * Fields are volatile and are always replaced wholesale rather than mutated in place, which
 * safely publishes them from the polling thread to the client thread.
 */
public class GroupMember
{
	/** Pseudo-member the server uses to hold the group's shared bank. */
	public static final String SHARED_NAME = "@SHARED";

	private final String name;

	private volatile Color colour = Color.WHITE;
	private volatile int[] skillXp;
	private volatile int[] stats;
	private volatile int[] coordinates;
	private volatile int[] quests;
	private volatile int[] diaryVars;

	private volatile List<ItemStack> inventory = Collections.emptyList();
	private volatile List<ItemStack> equipment = Collections.emptyList();
	private volatile List<ItemStack> bank = Collections.emptyList();
	private volatile List<ItemStack> runePouch = Collections.emptyList();
	private volatile List<ItemStack> seedVault = Collections.emptyList();
	private volatile List<ItemStack> quiver = Collections.emptyList();
	private volatile List<ItemStack> potionStorage = Collections.emptyList();

	private volatile Interaction interaction;
	private volatile Instant lastUpdated;

	public GroupMember(String name)
	{
		this.name = name;
	}

	public String getName()
	{
		return name;
	}

	public boolean isShared()
	{
		return SHARED_NAME.equals(name);
	}

	public Color getColour()
	{
		return colour;
	}

	public void setColour(Color colour)
	{
		this.colour = colour;
	}

	// ------------------------------------------------------------------
	// Skills
	// ------------------------------------------------------------------

	public void setSkillXp(int[] skillXp)
	{
		this.skillXp = skillXp;
	}

	public boolean hasSkills()
	{
		return skillXp != null;
	}

	/** Experience in a skill, or -1 when this member has never reported it. */
	public int getXp(GroupSkill skill)
	{
		int[] xp = skillXp;
		if (xp == null || skill.ordinal() >= xp.length)
		{
			return -1;
		}
		return xp[skill.ordinal()];
	}

	public int getLevel(GroupSkill skill)
	{
		int xp = getXp(skill);
		return xp < 0 ? -1 : Experience.getLevelForXp(xp);
	}

	/**
	 * Experience still needed to reach the next level, 0 once the virtual level cap is hit.
	 * Returns -1 when this member has never reported the skill.
	 */
	public int getXpToNextLevel(GroupSkill skill)
	{
		int xp = getXp(skill);
		if (xp < 0)
		{
			return -1;
		}
		int level = Experience.getLevelForXp(xp);
		if (level >= Experience.MAX_VIRT_LEVEL)
		{
			return 0;
		}
		return Experience.getXpForLevel(level + 1) - xp;
	}

	public int getTotalLevel()
	{
		int[] xp = skillXp;
		if (xp == null)
		{
			return -1;
		}
		int total = 0;
		for (GroupSkill skill : GroupSkill.VALUES)
		{
			int level = getLevel(skill);
			total += level < 0 ? 1 : Math.min(level, Experience.MAX_REAL_LEVEL);
		}
		return total;
	}

	public long getTotalXp()
	{
		int[] xp = skillXp;
		if (xp == null)
		{
			return -1;
		}
		long total = 0;
		for (int value : xp)
		{
			total += Math.max(0, value);
		}
		return total;
	}

	// ------------------------------------------------------------------
	// Vitals
	// ------------------------------------------------------------------

	public void setStats(int[] stats)
	{
		this.stats = stats;
	}

	public boolean hasStats()
	{
		return stats != null && stats.length >= 7;
	}

	public int getHitpoints()
	{
		return stat(0);
	}

	public int getMaxHitpoints()
	{
		return stat(1);
	}

	public int getPrayer()
	{
		return stat(2);
	}

	public int getMaxPrayer()
	{
		return stat(3);
	}

	/**
	 * Run energy as a whole percentage. The tracker sends run energy in hundredths of a
	 * percent while still reporting a maximum of 100, so the raw maximum is ignored here,
	 * exactly as the groupiron.men site does.
	 */
	public int getRunEnergyPercent()
	{
		int raw = stat(4);
		return raw < 0 ? -1 : Math.min(100, raw / 100);
	}

	public int getWorld()
	{
		return stat(6);
	}

	private int stat(int index)
	{
		int[] s = stats;
		return s == null || index >= s.length ? -1 : s[index];
	}

	// ------------------------------------------------------------------
	// Position
	// ------------------------------------------------------------------

	public void setCoordinates(int[] coordinates)
	{
		this.coordinates = coordinates;
	}

	@Nullable
	public WorldPoint getWorldPoint()
	{
		int[] c = coordinates;
		if (c == null || c.length < 3)
		{
			return null;
		}
		return new WorldPoint(c[0], c[1], c[2]);
	}

	public boolean isOnBoat()
	{
		int[] c = coordinates;
		return c != null && c.length >= 4 && c[3] != 0;
	}

	// ------------------------------------------------------------------
	// Containers
	// ------------------------------------------------------------------

	public List<ItemStack> getInventory()
	{
		return inventory;
	}

	public void setInventory(List<ItemStack> inventory)
	{
		this.inventory = inventory;
	}

	public List<ItemStack> getEquipment()
	{
		return equipment;
	}

	public void setEquipment(List<ItemStack> equipment)
	{
		this.equipment = equipment;
	}

	public List<ItemStack> getBank()
	{
		return bank;
	}

	public void setBank(List<ItemStack> bank)
	{
		this.bank = bank;
	}

	public List<ItemStack> getRunePouch()
	{
		return runePouch;
	}

	public void setRunePouch(List<ItemStack> runePouch)
	{
		this.runePouch = runePouch;
	}

	public List<ItemStack> getSeedVault()
	{
		return seedVault;
	}

	public void setSeedVault(List<ItemStack> seedVault)
	{
		this.seedVault = seedVault;
	}

	public List<ItemStack> getQuiver()
	{
		return quiver;
	}

	public void setQuiver(List<ItemStack> quiver)
	{
		this.quiver = quiver;
	}

	public List<ItemStack> getPotionStorage()
	{
		return potionStorage;
	}

	public void setPotionStorage(List<ItemStack> potionStorage)
	{
		this.potionStorage = potionStorage;
	}

	// ------------------------------------------------------------------
	// Progress
	// ------------------------------------------------------------------

	public int[] getQuests()
	{
		return quests;
	}

	public void setQuests(int[] quests)
	{
		this.quests = quests;
	}

	public int[] getDiaryVars()
	{
		return diaryVars;
	}

	public void setDiaryVars(int[] diaryVars)
	{
		this.diaryVars = diaryVars;
	}

	// ------------------------------------------------------------------
	// Interaction
	// ------------------------------------------------------------------

	@Nullable
	public Interaction getInteraction()
	{
		return interaction;
	}

	public void setInteraction(Interaction interaction)
	{
		this.interaction = interaction;
	}

	// ------------------------------------------------------------------
	// Freshness
	// ------------------------------------------------------------------

	@Nullable
	public Instant getLastUpdated()
	{
		return lastUpdated;
	}

	public void setLastUpdated(Instant lastUpdated)
	{
		this.lastUpdated = lastUpdated;
	}

	public boolean isOnline(int offlineAfterMinutes)
	{
		Instant seen = lastUpdated;
		if (seen == null)
		{
			return false;
		}
		return Duration.between(seen, Instant.now()).toMinutes() < offlineAfterMinutes;
	}

	/** Human readable "3m ago", or "never" when this member has never reported. */
	public String getLastSeenText()
	{
		Instant seen = lastUpdated;
		if (seen == null)
		{
			return "never";
		}
		long seconds = Math.max(0, Duration.between(seen, Instant.now()).getSeconds());
		if (seconds < 60)
		{
			return seconds + "s ago";
		}
		if (seconds < 3600)
		{
			return (seconds / 60) + "m ago";
		}
		if (seconds < 86_400)
		{
			return (seconds / 3600) + "h ago";
		}
		return (seconds / 86_400) + "d ago";
	}

	/** What a member is currently interacting with, as last reported by their own client. */
	public static final class Interaction
	{
		private final String name;
		private final int healthRatio;
		private final int healthScale;
		private final WorldPoint location;
		private final Instant seen;
		private final int staleAfterSeconds;

		public Interaction(String name, int healthRatio, int healthScale, WorldPoint location, Instant seen,
			int staleAfterSeconds)
		{
			this.name = name;
			this.healthRatio = healthRatio;
			this.healthScale = healthScale;
			this.location = location;
			this.seen = seen;
			this.staleAfterSeconds = staleAfterSeconds;
		}

		public String getName()
		{
			return name;
		}

		public WorldPoint getLocation()
		{
			return location;
		}

		public Instant getSeen()
		{
			return seen;
		}

		/** Remaining health as a percentage, or -1 when the target has no health bar. */
		public int getHealthPercent()
		{
			if (healthScale <= 0 || healthRatio < 0)
			{
				return -1;
			}
			return Math.min(100, (int) Math.round(100.0 * healthRatio / healthScale));
		}

		public boolean isStale()
		{
			return seen == null || Duration.between(seen, Instant.now()).getSeconds() > staleAfterSeconds;
		}
	}
}
