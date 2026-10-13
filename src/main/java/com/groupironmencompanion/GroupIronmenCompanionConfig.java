package com.groupironmencompanion;

import java.awt.Color;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

/**
 * Every feature of the plugin can be switched off independently, and each section can be
 * disabled as a whole by its first toggle. Nothing here changes anything about the game; the
 * settings only decide what the plugin draws and whether it shares your own data.
 */
@ConfigGroup(GroupIronmenCompanionConfig.GROUP)
public interface GroupIronmenCompanionConfig extends Config
{
	String GROUP = "groupironmencompanion";

	@ConfigSection(
		name = "Connection",
		description = "Which group to read, and how often. This plugin contacts the server set below "
			+ "(groupiron.men unless you change it) and sends it your group name and group token in "
			+ "order to read your group's data. No other server is ever contacted.",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigSection(
		name = "Side panel",
		description = "The group dashboard in the RuneLite sidebar",
		position = 1
	)
	String panelSection = "panel";

	@ConfigSection(
		name = "Group bank",
		description = "The read-only, searchable view of your group's items",
		position = 2
	)
	String bankSection = "bank";

	@ConfigSection(
		name = "Skills tab",
		description = "Your group's levels on the in-game Skills tab",
		position = 3
	)
	String skillSection = "skills";

	@ConfigSection(
		name = "World map",
		description = "Group member markers on the world map",
		position = 4
	)
	String mapSection = "map";

	@ConfigSection(
		name = "Status overlay",
		description = "An always-on summary of the group's vitals",
		position = 5
	)
	String statusSection = "status";

	@ConfigSection(
		name = "Member colours",
		description = "Each member is drawn in their own colour, assigned in alphabetical order of member name",
		position = 6,
		closedByDefault = true
	)
	String colourSection = "colours";

	@ConfigSection(
		name = "Data sharing",
		description = "WARNING: when enabled, this uploads your own character data to the server set "
			+ "under Connection, so the rest of your group can see it. See the setting below for "
			+ "exactly what is sent. This only happens when the official Group Ironmen Tracker "
			+ "plugin is not already doing it.",
		position = 7,
		closedByDefault = true
	)
	String uploadSection = "upload";

	// ------------------------------------------------------------------
	// Connection
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "groupName",
		name = "Group name",
		description = "The group name you created on groupiron.men. Leave blank to reuse the credentials from the official Group Ironmen Tracker plugin if you have it installed.",
		position = 0,
		section = connectionSection
	)
	default String groupName()
	{
		return "";
	}

	@ConfigItem(
		keyName = "groupToken",
		name = "Group token",
		description = "The secret token for your group. Leave blank to reuse the token from the official Group Ironmen Tracker plugin if you have it installed.",
		secret = true,
		position = 1,
		section = connectionSection
	)
	default String groupToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "baseUrlOverride",
		name = "Server base URL",
		description = "Leave blank to use the public groupiron.men server, or to inherit a self-hosted URL from the official tracker plugin. Only set this if your group self-hosts and you are not running that plugin.",
		position = 2,
		section = connectionSection
	)
	default String baseUrlOverride()
	{
		return "";
	}

	@Range(min = 1, max = 60)
	@ConfigItem(
		keyName = "pollSeconds",
		name = "Refresh every (seconds)",
		description = "How often to fetch group updates from the server.",
		position = 3,
		section = connectionSection
	)
	default int pollSeconds()
	{
		return 3;
	}

	@Range(min = 1, max = 60)
	@ConfigItem(
		keyName = "offlineAfterMinutes",
		name = "Mark offline after (minutes)",
		description = "A member whose data has not changed for this long is shown as offline.",
		position = 4,
		section = connectionSection
	)
	default int offlineAfterMinutes()
	{
		return 5;
	}

	// ------------------------------------------------------------------
	// Side panel
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "showSidePanel",
		name = "Enable side panel",
		description = "Show the group dashboard in the RuneLite sidebar.",
		position = 0,
		section = panelSection
	)
	default boolean showSidePanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "panelShowActivity",
		name = "Show what members are doing",
		description = "Include the NPC each member is interacting with, and its remaining health. Informational only; never drawn in the game scene.",
		position = 1,
		section = panelSection
	)
	default boolean panelShowActivity()
	{
		return true;
	}

	@ConfigItem(
		keyName = "panelShowOffline",
		name = "Show offline members",
		description = "Keep members in the panel after they go offline, greyed out with their last known state.",
		position = 2,
		section = panelSection
	)
	default boolean panelShowOffline()
	{
		return true;
	}

	@ConfigItem(
		keyName = "panelShowSelf",
		name = "Include yourself",
		description = "Show your own character alongside the rest of the group.",
		position = 3,
		section = panelSection
	)
	default boolean panelShowSelf()
	{
		return true;
	}

	// ------------------------------------------------------------------
	// Group bank
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "bankViewer",
		name = "Enable group bank viewer",
		description = "A read-only, searchable view of your group's banks, inventories and equipment.",
		position = 0,
		section = bankSection
	)
	default boolean bankViewer()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bankHotkey",
		name = "Open/close hotkey",
		description = "Toggles the group bank viewer.",
		position = 1,
		section = bankSection
	)
	default Keybind bankHotkey()
	{
		return new Keybind(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "bankShowTab",
		name = "Show tab beside the bank",
		description = "While your own bank is open, draw a small Group tab beside it that opens the viewer. The tab is drawn by the plugin and does not modify the bank interface.",
		position = 2,
		section = bankSection
	)
	default boolean bankShowTab()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bankAutoOpen",
		name = "Open automatically with bank",
		description = "Open the group bank viewer whenever you open your own bank.",
		position = 3,
		section = bankSection
	)
	default boolean bankAutoOpen()
	{
		return false;
	}

	@ConfigItem(
		keyName = "bankCloseWithBank",
		name = "Close automatically with bank",
		description = "Close the group bank viewer when you close your own bank.",
		position = 4,
		section = bankSection
	)
	default boolean bankCloseWithBank()
	{
		return true;
	}

	@Range(min = 0, max = 10000)
	@ConfigItem(
		keyName = "natureRuneCost",
		name = "Nature rune cost",
		description = "Subtracted from every item's alch value so the figures shown are the profit "
			+ "from alching, not the raw value. The default is roughly what buying them from a rune "
			+ "shop works out at. Set this to 0 to show the raw alch value instead.",
		position = 6,
		section = bankSection
	)
	default int natureRuneCost()
	{
		return 184;
	}

	@ConfigItem(
		keyName = "bankShowPrices",
		name = "Show high alch values",
		description = "Show the high alchemy value of the hovered item and of the whole filtered view. Group ironmen cannot use the Grand Exchange, so what an item alchs for is the more useful number.",
		position = 5,
		section = bankSection
	)
	default boolean bankShowPrices()
	{
		return true;
	}

	// ------------------------------------------------------------------
	// Skills tab
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "skillHover",
		name = "Group levels on skill hover",
		description = "When you hover a skill in the Skills tab, show every group member's level, XP and XP to next level beside it.",
		position = 0,
		section = skillSection
	)
	default boolean skillHover()
	{
		return true;
	}

	@ConfigItem(
		keyName = "skillHoverPosition",
		name = "Panel position",
		description = "Where the hover panel is drawn. It is anchored to the Skills tab rather than following the mouse, so it never covers the game's own experience tooltip.",
		position = 1,
		section = skillSection
	)
	default SkillHoverPosition skillHoverPosition()
	{
		return SkillHoverPosition.AUTO;
	}

	@ConfigItem(
		keyName = "skillHoverIncludeSelf",
		name = "Include yourself",
		description = "Include your own live level and XP in the panel, so you can compare without switching panels.",
		position = 2,
		section = skillSection
	)
	default boolean skillHoverIncludeSelf()
	{
		return true;
	}

	@ConfigItem(
		keyName = "skillHoverOffline",
		name = "Include offline members",
		description = "Show the last known levels of members who are offline.",
		position = 3,
		section = skillSection
	)
	default boolean skillHoverOffline()
	{
		return true;
	}

	// ------------------------------------------------------------------
	// World map
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "worldMapMarkers",
		name = "Show members on the world map",
		description = "Draw a marker for each group member on the world map. The minimap and the game scene are never drawn on.",
		position = 0,
		section = mapSection
	)
	default boolean worldMapMarkers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "worldMapEntrances",
		name = "Mark dungeon entrances",
		description = "When a member is off the overworld, also draw a hollow marker at the surface entrance that leads to them.",
		position = 1,
		section = mapSection
	)
	default boolean worldMapEntrances()
	{
		return true;
	}

	@ConfigItem(
		keyName = "mapPanel",
		name = "Member list on the world map",
		description = "While the world map is open, draw a small list of online members. Click a "
			+ "name to jump the map to them.",
		position = 3,
		section = mapSection
	)
	default boolean mapPanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "mapPanelRouting",
		name = "Right-click a member to route to them",
		description = "Right-clicking a name in that list asks the Shortest Path plugin to route to "
			+ "that member, following them as they move and clearing once you reach them. Requires "
			+ "the Shortest Path plugin; without it this does nothing.",
		position = 4,
		section = mapSection
	)
	default boolean mapPanelRouting()
	{
		return true;
	}

	@ConfigItem(
		keyName = "worldMapOffline",
		name = "Include offline members",
		description = "Keep showing the last known position of members who have gone offline.",
		position = 2,
		section = mapSection
	)
	default boolean worldMapOffline()
	{
		return false;
	}

	// ------------------------------------------------------------------
	// Status overlay
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "statusOverlay",
		name = "Enable status overlay",
		description = "A compact always-on overlay listing each member's hitpoints, prayer, run energy and world. Drag it with alt like any RuneLite overlay.",
		position = 0,
		section = statusSection
	)
	default boolean statusOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "statusShowVitals",
		name = "Show hitpoints, prayer and run",
		description = "Include each member's vitals. Turn off for a compact list of just names and worlds.",
		position = 1,
		section = statusSection
	)
	default boolean statusShowVitals()
	{
		return true;
	}

	@ConfigItem(
		keyName = "statusShowActivity",
		name = "Show what members are doing",
		description = "Include the NPC each member is interacting with, and its remaining health. Informational only; never drawn in the game scene.",
		position = 2,
		section = statusSection
	)
	default boolean statusShowActivity()
	{
		return true;
	}

	@ConfigItem(
		keyName = "statusShowOffline",
		name = "Show offline members",
		description = "Keep offline members in the overlay, greyed out with how long ago they were last seen.",
		position = 3,
		section = statusSection
	)
	default boolean statusShowOffline()
	{
		return true;
	}

	@ConfigItem(
		keyName = "statusShowSelf",
		name = "Include yourself",
		description = "Show your own character in the overlay alongside the rest of the group.",
		position = 4,
		section = statusSection
	)
	default boolean statusShowSelf()
	{
		return false;
	}

	// ------------------------------------------------------------------
	// Colours
	// ------------------------------------------------------------------

	@ConfigItem(keyName = "colour1", name = "Member 1", description = "Colour for the first member alphabetically.", position = 0, section = colourSection)
	default Color colour1()
	{
		return new Color(0x4F, 0xC3, 0xF7);
	}

	@ConfigItem(keyName = "colour2", name = "Member 2", description = "Colour for the second member alphabetically.", position = 1, section = colourSection)
	default Color colour2()
	{
		return new Color(0xEF, 0x53, 0x50);
	}

	@ConfigItem(keyName = "colour3", name = "Member 3", description = "Colour for the third member alphabetically.", position = 2, section = colourSection)
	default Color colour3()
	{
		return new Color(0x66, 0xBB, 0x6A);
	}

	@ConfigItem(keyName = "colour4", name = "Member 4", description = "Colour for the fourth member alphabetically.", position = 3, section = colourSection)
	default Color colour4()
	{
		return new Color(0xFF, 0xCA, 0x28);
	}

	@ConfigItem(keyName = "colour5", name = "Member 5", description = "Colour for the fifth member alphabetically.", position = 4, section = colourSection)
	default Color colour5()
	{
		return new Color(0xAB, 0x47, 0xBC);
	}

	// ------------------------------------------------------------------
	// Data sharing
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "uploadMode",
		name = "Share my data",
		description = "WARNING: this sends your character data to the server configured under "
			+ "Connection (groupiron.men by default), where your group members can read it. "
			+ "What is sent: your character name, experience in every skill, hitpoints, prayer "
			+ "points, run energy, current world, world position, quest and achievement diary "
			+ "progress, whatever NPC you are interacting with, and the contents of your "
			+ "inventory, equipment, bank, rune pouch, seed vault, quiver and group storage. "
			+ "AUTO sends this only while the official Group Ironmen Tracker plugin is not "
			+ "running, so the two never upload competing copies. OFF never sends anything, and "
			+ "the plugin only reads.",
		position = 0,
		section = uploadSection
	)
	default UploadMode uploadMode()
	{
		return UploadMode.AUTO;
	}

	enum UploadMode
	{
		AUTO,
		OFF
	}

	/** Where the Skills tab hover panel is anchored. */
	enum SkillHoverPosition
	{
		/** Beside the Skills tab, preferring its left so it clears the right edge. */
		AUTO,
		LEFT,
		RIGHT,
		SCREEN_CENTRE
	}
}
