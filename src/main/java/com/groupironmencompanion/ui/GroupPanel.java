package com.groupironmencompanion.ui;

import com.groupironmencompanion.GroupIronmenCompanionConfig;
import com.groupironmencompanion.data.GroupMember;
import com.groupironmencompanion.data.GroupState;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

/**
 * The sidebar dashboard: connection status, member vitals, a group-wide item search, and
 * skill, quest and diary comparisons.
 * <p>
 * Everything shown is read from your group's server. The panel has no controls that affect
 * the game.
 */
public class GroupPanel extends PluginPanel
{
	private final GroupState groupState;
	private final GroupIronmenCompanionConfig config;

	private final JLabel statusLabel = new JLabel();
	private final JPanel membersView = new JPanel();
	private final ItemSearchView itemsView;
	private final SkillsView skillsView;
	private final QuestsView questsView;
	private final DiariesView diariesView;

	private final Map<String, MemberCard> cards = new HashMap<>();

	/**
	 * Rebuild for whichever tab is on screen.
	 * <p>
	 * A refresh runs on every poll, and rebuilding all five views each time meant decoding
	 * every quest and all 492 diary tasks for every member several times a minute for panels
	 * nobody was looking at. Only the visible one is rebuilt; the rest catch up when selected.
	 */
	private Runnable activeView = () ->
	{
	};

	/** The Group tab's rebuild, held once so {@link #refreshMembersIfShown} can recognise it. */
	private final Runnable membersRefresh = this::refreshMembers;

	public GroupPanel(GroupState groupState, GroupIronmenCompanionConfig config,
		ItemManager itemManager, ClientThread clientThread)
	{
		super(true);
		this.groupState = groupState;
		this.config = config;

		this.itemsView = new ItemSearchView(groupState, itemManager, clientThread);
		this.skillsView = new SkillsView(groupState);
		this.questsView = new QuestsView(groupState);
		this.diariesView = new DiariesView(groupState);

		setLayout(new BorderLayout(0, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

		membersView.setLayout(new BoxLayout(membersView, BoxLayout.Y_AXIS));
		membersView.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel display = new JPanel(new BorderLayout());
		display.setBackground(ColorScheme.DARK_GRAY_COLOR);

		MaterialTabGroup tabs = new MaterialTabGroup(display);
		// MaterialTabGroup defaults to a FlowLayout, which wraps extra tabs onto a second row
		// that BorderLayout.NORTH then clips to nothing. A grid keeps every tab visible and
		// reports an honest preferred height.
		tabs.setLayout(new GridLayout(0, 3, 4, 2));
		MaterialTab groupTab = new MaterialTab("Group", tabs, membersView);
		MaterialTab itemsTab = new MaterialTab("Items", tabs, itemsView);
		MaterialTab skillsTab = new MaterialTab("Skills", tabs, skillsView);
		MaterialTab questsTab = new MaterialTab("Quests", tabs, questsView);
		MaterialTab diariesTab = new MaterialTab("Diaries", tabs, diariesView);

		groupTab.setOnSelectEvent(() -> select(membersRefresh));
		itemsTab.setOnSelectEvent(() -> select(itemsView::refresh));
		skillsTab.setOnSelectEvent(() -> select(skillsView::refresh));
		questsTab.setOnSelectEvent(() -> select(questsView::refresh));
		diariesTab.setOnSelectEvent(() -> select(diariesView::refresh));

		tabs.addTab(groupTab);
		tabs.addTab(itemsTab);
		tabs.addTab(skillsTab);
		tabs.addTab(questsTab);
		tabs.addTab(diariesTab);
		tabs.select(groupTab);
		activeView = membersRefresh;

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.add(statusLabel, BorderLayout.NORTH);
		header.add(tabs, BorderLayout.SOUTH);

		add(header, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);
	}

	/** Safe to call from any thread. */
	public void refresh()
	{
		SwingUtilities.invokeLater(this::refreshOnEdt);
	}

	/**
	 * Redraws the Group tab if it is the one on screen. Online state, "last seen" and the
	 * activity line all age with the clock, so they need redrawing even when no new data
	 * arrives. The other tabs only change with the data. Safe to call from any thread.
	 */
	public void refreshMembersIfShown()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (activeView == membersRefresh)
			{
				refreshMembers();
			}
		});
	}

	public void setStatus(String status)
	{
		SwingUtilities.invokeLater(() -> statusLabel.setText("<html>" + escape(status) + "</html>"));
	}

	/** Remembers which view is on screen and rebuilds it once, on selection. */
	private boolean select(Runnable view)
	{
		activeView = view;
		view.run();
		return true;
	}

	private void refreshOnEdt()
	{
		activeView.run();
	}

	private void refreshMembers()
	{
		List<GroupMember> members = groupState.getMembers();

		// Reuse a card per member so the panel does not flicker on every poll.
		List<String> present = new ArrayList<>(members.size());
		membersView.removeAll();

		int shown = 0;
		for (GroupMember member : members)
		{
			boolean online = member.isOnline(config.offlineAfterMinutes());
			if (!online && !config.panelShowOffline())
			{
				continue;
			}
			if (groupState.isLocalPlayer(member) && !config.panelShowSelf())
			{
				continue;
			}

			present.add(member.getName());
			MemberCard card = cards.computeIfAbsent(member.getName(), name -> new MemberCard());
			card.update(member, online, config.panelShowActivity(), groupState.isLocalPlayer(member));
			membersView.add(card);
			shown++;
		}

		cards.keySet().retainAll(present);

		if (shown == 0)
		{
			JLabel empty = new JLabel(members.isEmpty()
				? "<html>No group data yet.<br>Check your group name and token in the plugin settings.</html>"
				: "<html>Every member is hidden by the<br>side panel settings.</html>");
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			membersView.add(empty);
		}

		membersView.revalidate();
		membersView.repaint();
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
