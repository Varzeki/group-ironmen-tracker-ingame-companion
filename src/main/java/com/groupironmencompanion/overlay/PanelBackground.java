package com.groupironmencompanion.overlay;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import java.awt.Color;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.RuneLiteConfig;

/**
 * The background colour for the plugin's RuneLite-style panels: the world map member list,
 * the skills hover panel and the group bank's item tooltip.
 * <p>
 * While "Match interface theme" is on, this is RuneLite's own overlay colour, which is also
 * what the Resource Packs plugin rewrites to suit a pack, so the panels change with
 * everything else. Each panel still sits over something busy (the world map, the game world,
 * the bank), so a theme colour more transparent than the panel's own default is made as
 * opaque as that default: the user's colour, kept legible. With the option off, each panel
 * keeps its own default.
 */
@Singleton
public class PanelBackground
{
	@Inject
	private RuneLiteConfig runeLiteConfig;

	@Inject
	private GroupIronmenCompanionConfig config;

	/** The last answer, so a colour is only built when the theme or the caller changes. */
	private Color lastTheme;
	private Color lastDefault;
	private Color lastResult;

	/** The background to use for a panel whose own default is the given colour. */
	public Color get(Color panelDefault)
	{
		if (!config.matchInterfaceTheme())
		{
			return panelDefault;
		}

		Color theme = runeLiteConfig.overlayBackgroundColor();
		if (theme == null)
		{
			return panelDefault;
		}

		if (!theme.equals(lastTheme) || !panelDefault.equals(lastDefault))
		{
			lastTheme = theme;
			lastDefault = panelDefault;
			lastResult = theme.getAlpha() >= panelDefault.getAlpha()
				? theme
				: new Color(theme.getRed(), theme.getGreen(), theme.getBlue(), panelDefault.getAlpha());
		}
		return lastResult;
	}
}
