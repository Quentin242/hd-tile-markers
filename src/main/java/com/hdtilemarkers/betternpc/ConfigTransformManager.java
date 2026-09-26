/*
 * Adapted from Better NPC Highlight, https://github.com/riktenx/better-npc-highlight,
 * commit bf59bfb9a616897e9ffcd14d0d2b543e4c119b09. BSD 2-Clause License, see
 * META-INF/LICENSE-better-npc-highlight and THIRD_PARTY_NOTICES.md. Changes for HD
 * Tile Markers: package; config-writing methods (menu tagging) removed, Slayer plugin is not enabled,
 * NPC ID lists not read (names only).
 */
package com.hdtilemarkers.betternpc;


import java.awt.Color;
import java.util.List;

import javax.inject.Inject;


import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import net.runelite.client.util.WildcardMatcher;

@Slf4j
public class ConfigTransformManager {
	@Inject
	private ClientThread clientThread;

	@Inject
	private Client client;

	@Inject
	private SlayerPluginService slayerPluginService;

	@Inject
	private SlayerPluginManager slayerPluginIntegration;

	@Inject
	private NameListContainer nameListContainer;

	private BetterNpcHighlightConfig config;

	// Read from ConfigManager, not bound in HD Tile Markers' injector: RuneLite takes a plugin's Config bindings as
	// its own settings panel and fills in their defaults, which must never touch Better NPC Highlight's.
	@Inject
	void readConfig(net.runelite.client.config.ConfigManager configManager)
	{
		config = configManager.getConfig(BetterNpcHighlightConfig.class);
	}

	@Inject
	private ColorManager colorManager;

	@Inject
	private ConfigReaderService configReaderService;

	@Inject
	private RespawnManager respawnManager;

	/**
	 * Populates all parsed name lists from the current config values.
	 */
	public void reloadLists() {
		nameListContainer.setTileNames(configReaderService.parseList(config.tileNames()));
		nameListContainer.setTrueTileNames(configReaderService.parseList(config.trueTileNames()));
		nameListContainer.setSwTileNames(configReaderService.parseList(config.swTileNames()));
		nameListContainer.setSwTrueTileNames(configReaderService.parseList(config.swTrueTileNames()));
		nameListContainer.setHullNames(configReaderService.parseList(config.hullNames()));
		nameListContainer.setAreaNames(configReaderService.parseList(config.areaNames()));
		nameListContainer.setOutlineNames(configReaderService.parseList(config.outlineNames()));
		nameListContainer.setClickboxNames(configReaderService.parseList(config.clickboxNames()));

		nameListContainer.setNamesToDisplay(configReaderService.parseList(config.displayName()));
		nameListContainer.setIgnoreDeadExclusionList(configReaderService.parseList(config.ignoreDeadExclusion()));
	}

	public void updateConfig(ConfigChanged event) {
		switch (event.getKey()) {
		case "tileNames":
			nameListContainer.setTileNames(configReaderService.parseList(config.tileNames()));
			recreateNPCInfoList();
			break;
		case "trueTileNames":
			nameListContainer.setTrueTileNames(configReaderService.parseList(config.trueTileNames()));
			recreateNPCInfoList();
			break;
		case "swTileNames":
			nameListContainer.setSwTileNames(configReaderService.parseList(config.swTileNames()));
			recreateNPCInfoList();
			break;
		case "swTrueTileNames":
			nameListContainer.setSwTrueTileNames(configReaderService.parseList(config.swTrueTileNames()));
			recreateNPCInfoList();
			break;
		case "hullNames":
			nameListContainer.setHullNames(configReaderService.parseList(config.hullNames()));
			recreateNPCInfoList();
			break;
		case "areaNames":
			nameListContainer.setAreaNames(configReaderService.parseList(config.areaNames()));
			recreateNPCInfoList();
			break;
		case "outlineNames":
			nameListContainer.setOutlineNames(configReaderService.parseList(config.outlineNames()));
			recreateNPCInfoList();
			break;
		case "clickboxNames":
			nameListContainer.setClickboxNames(configReaderService.parseList(config.clickboxNames()));
			recreateNPCInfoList();
			break;
		case "displayName":
			nameListContainer.setNamesToDisplay(configReaderService.parseList(config.displayName()));
			break;
		case "ignoreDeadExclusion":
			nameListContainer.setIgnoreDeadExclusionList(configReaderService.parseList(config.ignoreDeadExclusion()));
			recreateNPCInfoList();
			break;
		case "slayerHighlight":
			// HD Tile Markers does not enable the Slayer plugin; Better NPC Highlight does that itself.
			break;
		case "tileColor":
		case "tileFillColor":
		case "trueTileColor":
		case "trueTileFillColor":
		case "swTileColor":
		case "swTileFillColor":
		case "swTrueTileColor":
		case "swTrueTileFillColor":
		case "hullColor":
		case "hullFillColor":
		case "areaColor":
		case "outlineColor":
		case "clickboxColor":
		case "clickboxFillColor":
		case "taskColor":
		case "taskFillColor":
		case "presetColor1":
		case "presetFillColor1":
		case "presetColor2":
		case "presetFillColor2":
		case "presetColor3":
		case "presetFillColor3":
		case "presetColor4":
		case "presetFillColor4":
		case "presetColor5":
		case "presetFillColor5":
		case "useGlobalTileColor":
		case "globalTileColor":
		case "globalFillColor":
			recreateNPCInfoList();
			break;
		}
	}

	public void recreateNPCInfoList() {
		clientThread.invokeLater(() -> {
			if (client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null
					&& client.getLocalPlayer().getPlayerComposition() != null)
			{
				nameListContainer.getNpcList().clear();

				recreateNPCInfoListForWorldView(client.getTopLevelWorldView());

				nameListContainer.setCurrentTask(slayerPluginService.getTask() == null ? "" : slayerPluginService.getTask());
			}
		});
	}

	private void recreateNPCInfoListForWorldView(WorldView wv) {
		for (NPC npc : wv.npcs())
		{
			NPCInfo npcInfo = createNpcInfo(npc);
			if (npcInfo != null)
			{
				nameListContainer.getNpcList().add(npcInfo);

				if (!wv.isInstance())
				{
					respawnManager.memorizeNpc(npc);
				}
			}
			else
			{
				respawnManager.forgetNpc(npc.getIndex());
			}
		}

		for (WorldView subWv : wv.worldViews())
		{
			recreateNPCInfoListForWorldView(subWv);
		}
	}

	/**
	 * Builds an NPCInfo for the given NPC, or null when it should not be highlighted.
	 */
	public NPCInfo createNpcInfo(NPC npc) {
		Color globalTileColor = config.useGlobalTileColor() ? config.globalTileColor() : null;
		Color globalFillColor = config.useGlobalTileColor() ? config.globalFillColor() : null;

		HighlightColor tile = resolveHighlightColor(nameListContainer.getTileNames(), npc,
				coalesceColor(globalTileColor, config.tileColor()), coalesceColor(globalFillColor, config.tileFillColor()));
		HighlightColor trueTile = resolveHighlightColor(nameListContainer.getTrueTileNames(), npc,
				coalesceColor(globalTileColor, config.trueTileColor()), coalesceColor(globalFillColor, config.trueTileFillColor()));
		HighlightColor swTile = resolveHighlightColor(nameListContainer.getSwTileNames(), npc,
				coalesceColor(globalTileColor, config.swTileColor()), coalesceColor(globalFillColor, config.swTileFillColor()));
		HighlightColor swTrueTile = resolveHighlightColor(nameListContainer.getSwTrueTileNames(), npc,
				coalesceColor(globalTileColor, config.swTrueTileColor()), coalesceColor(globalFillColor, config.swTrueTileFillColor()));
		HighlightColor hull = resolveHighlightColor(nameListContainer.getHullNames(), npc, config.hullColor(),
				config.hullFillColor());
		HighlightColor area = resolveHighlightColor(nameListContainer.getAreaNames(), npc, config.areaColor(),
				null);
		HighlightColor outline = resolveHighlightColor(nameListContainer.getOutlineNames(), npc,
				config.outlineColor(), null);
		HighlightColor clickbox = resolveHighlightColor(nameListContainer.getClickboxNames(), npc,
				config.clickboxColor(), config.clickboxFillColor());

		boolean isTask = slayerPluginIntegration.checkSlayerPluginEnabled() && slayerPluginService != null
				&& slayerPluginService.getTargets().contains(npc);
		boolean ignoreDead = isInSpecificNameList(nameListContainer.getIgnoreDeadExclusionList(), npc);

		if (!tile.isHighlight() && !trueTile.isHighlight() && !swTile.isHighlight() && !swTrueTile.isHighlight()
				&& !hull.isHighlight() && !area.isHighlight() && !outline.isHighlight() && !clickbox.isHighlight() && !isTask)
		{
			return null;
		}

		return new NPCInfo(npc, tile, trueTile, swTile, swTrueTile, hull, area, outline, clickbox, isTask, ignoreDead);
	}

	private Color coalesceColor(Color color, Color defaultColor) {
		return color != null ? color : defaultColor;
	}

	/** Better NPC Highlight's name entries only: its NPC ID lists are not read (Plugin Hub rule on player-provided IDs). */
	public HighlightColor resolveHighlightColor(List<String> strList, NPC npc, Color configColor,
			Color configFillColor) {
		if (npc.getName() != null)
		{
			String name = npc.getName().toLowerCase();
			for (String entry : strList)
			{
				String nameStr = entry;
				String preset = "";
				if (entry.contains(":"))
				{
					String[] strArr = entry.split(":");
					nameStr = strArr[0];
					preset = strArr[1];
				}

				if (WildcardMatcher.matches(nameStr, name))
				{
					return new HighlightColor(true, colorManager.getHighlightColor(preset, configColor),
							colorManager.getHighlightFillColor(preset, configFillColor));
				}
			}
		}
		return new HighlightColor(false, configColor, configFillColor);
	}

	public boolean isInSpecificNameList(List<String> strList, NPC npc) {
		if (npc.getName() != null)
		{
			String name = npc.getName().toLowerCase();
			for (String entry : strList)
			{
				String nameStr = entry;
				if (entry.contains(":"))
				{
					String[] strArr = entry.split(":");
					nameStr = strArr[0];
				}

				if (WildcardMatcher.matches(nameStr, name))
				{
					return true;
				}
			}
		}
		return false;
	}

}