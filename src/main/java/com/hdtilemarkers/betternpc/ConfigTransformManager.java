/*
 * Adapted from Better NPC Highlight, https://github.com/riktenx/better-npc-highlight,
 * commit bf59bfb9a616897e9ffcd14d0d2b543e4c119b09. BSD 2-Clause License, see
 * META-INF/LICENSE-better-npc-highlight and THIRD_PARTY_NOTICES.md. Changes for Better
 * Indicator Renderer: package; config-writing methods (menu tagging) removed, Slayer plugin is not enabled.
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
	private NameAndIdContainer nameAndIdContainer;

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
	 * Populates all parsed name/id lists from the current config values.
	 */
	public void reloadLists() {
		nameAndIdContainer.setTileNames(configReaderService.parseList(config.tileNames()));
		nameAndIdContainer.setTileIds(configReaderService.parseList(config.tileIds()));
		nameAndIdContainer.setTrueTileNames(configReaderService.parseList(config.trueTileNames()));
		nameAndIdContainer.setTrueTileIds(configReaderService.parseList(config.trueTileIds()));
		nameAndIdContainer.setSwTileNames(configReaderService.parseList(config.swTileNames()));
		nameAndIdContainer.setSwTileIds(configReaderService.parseList(config.swTileIds()));
		nameAndIdContainer.setSwTrueTileNames(configReaderService.parseList(config.swTrueTileNames()));
		nameAndIdContainer.setSwTrueTileIds(configReaderService.parseList(config.swTrueTileIds()));
		nameAndIdContainer.setHullNames(configReaderService.parseList(config.hullNames()));
		nameAndIdContainer.setHullIds(configReaderService.parseList(config.hullIds()));
		nameAndIdContainer.setAreaNames(configReaderService.parseList(config.areaNames()));
		nameAndIdContainer.setAreaIds(configReaderService.parseList(config.areaIds()));
		nameAndIdContainer.setOutlineNames(configReaderService.parseList(config.outlineNames()));
		nameAndIdContainer.setOutlineIds(configReaderService.parseList(config.outlineIds()));
		nameAndIdContainer.setClickboxNames(configReaderService.parseList(config.clickboxNames()));
		nameAndIdContainer.setClickboxIds(configReaderService.parseList(config.clickboxIds()));

		nameAndIdContainer.setNamesToDisplay(configReaderService.parseList(config.displayName()));
		nameAndIdContainer.setIgnoreDeadExclusionList(configReaderService.parseList(config.ignoreDeadExclusion()));
		nameAndIdContainer.setIgnoreDeadExclusionIDList(configReaderService.parseList(config.ignoreDeadExclusionID()));
	}

	public void updateConfig(ConfigChanged event) {
		switch (event.getKey()) {
		case "tileNames":
			nameAndIdContainer.setTileNames(configReaderService.parseList(config.tileNames()));
			recreateNPCInfoList();
			break;
		case "tileIds":
			nameAndIdContainer.setTileIds(configReaderService.parseList(config.tileIds()));
			recreateNPCInfoList();
			break;
		case "trueTileNames":
			nameAndIdContainer.setTrueTileNames(configReaderService.parseList(config.trueTileNames()));
			recreateNPCInfoList();
			break;
		case "trueTileIds":
			nameAndIdContainer.setTrueTileIds(configReaderService.parseList(config.trueTileIds()));
			recreateNPCInfoList();
			break;
		case "swTileNames":
			nameAndIdContainer.setSwTileNames(configReaderService.parseList(config.swTileNames()));
			recreateNPCInfoList();
			break;
		case "swTileIds":
			nameAndIdContainer.setSwTileIds(configReaderService.parseList(config.swTileIds()));
			recreateNPCInfoList();
			break;
		case "swTrueTileNames":
			nameAndIdContainer.setSwTrueTileNames(configReaderService.parseList(config.swTrueTileNames()));
			recreateNPCInfoList();
			break;
		case "swTrueTileIds":
			nameAndIdContainer.setSwTrueTileIds(configReaderService.parseList(config.swTrueTileIds()));
			recreateNPCInfoList();
			break;
		case "hullNames":
			nameAndIdContainer.setHullNames(configReaderService.parseList(config.hullNames()));
			recreateNPCInfoList();
			break;
		case "hullIds":
			nameAndIdContainer.setHullIds(configReaderService.parseList(config.hullIds()));
			recreateNPCInfoList();
			break;
		case "areaNames":
			nameAndIdContainer.setAreaNames(configReaderService.parseList(config.areaNames()));
			recreateNPCInfoList();
			break;
		case "areaIds":
			nameAndIdContainer.setAreaIds(configReaderService.parseList(config.areaIds()));
			recreateNPCInfoList();
			break;
		case "outlineNames":
			nameAndIdContainer.setOutlineNames(configReaderService.parseList(config.outlineNames()));
			recreateNPCInfoList();
			break;
		case "outlineIds":
			nameAndIdContainer.setOutlineIds(configReaderService.parseList(config.outlineIds()));
			recreateNPCInfoList();
			break;
		case "clickboxNames":
			nameAndIdContainer.setClickboxNames(configReaderService.parseList(config.clickboxNames()));
			recreateNPCInfoList();
			break;
		case "clickboxIds":
			nameAndIdContainer.setClickboxIds(configReaderService.parseList(config.clickboxIds()));
			recreateNPCInfoList();
			break;
		case "displayName":
			nameAndIdContainer.setNamesToDisplay(configReaderService.parseList(config.displayName()));
			break;
		case "ignoreDeadExclusion":
			nameAndIdContainer.setIgnoreDeadExclusionList(configReaderService.parseList(config.ignoreDeadExclusion()));
			recreateNPCInfoList();
			break;
		case "ignoreDeadExclusionID":
			nameAndIdContainer.setIgnoreDeadExclusionIDList(configReaderService.parseList(config.ignoreDeadExclusionID()));
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
				nameAndIdContainer.getNpcList().clear();

				recreateNPCInfoListForWorldView(client.getTopLevelWorldView());

				nameAndIdContainer.setCurrentTask(slayerPluginService.getTask() == null ? "" : slayerPluginService.getTask());
			}
		});
	}

	private void recreateNPCInfoListForWorldView(WorldView wv) {
		for (NPC npc : wv.npcs())
		{
			NPCInfo npcInfo = createNpcInfo(npc);
			if (npcInfo != null)
			{
				nameAndIdContainer.getNpcList().add(npcInfo);

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

		HighlightColor tile = resolveHighlightColor(nameAndIdContainer.getTileNames(), nameAndIdContainer.getTileIds(), npc,
				coalesceColor(globalTileColor, config.tileColor()), coalesceColor(globalFillColor, config.tileFillColor()));
		HighlightColor trueTile = resolveHighlightColor(nameAndIdContainer.getTrueTileNames(), nameAndIdContainer.getTrueTileIds(), npc,
				coalesceColor(globalTileColor, config.trueTileColor()), coalesceColor(globalFillColor, config.trueTileFillColor()));
		HighlightColor swTile = resolveHighlightColor(nameAndIdContainer.getSwTileNames(), nameAndIdContainer.getSwTileIds(), npc,
				coalesceColor(globalTileColor, config.swTileColor()), coalesceColor(globalFillColor, config.swTileFillColor()));
		HighlightColor swTrueTile = resolveHighlightColor(nameAndIdContainer.getSwTrueTileNames(), nameAndIdContainer.getSwTrueTileIds(), npc,
				coalesceColor(globalTileColor, config.swTrueTileColor()), coalesceColor(globalFillColor, config.swTrueTileFillColor()));
		HighlightColor hull = resolveHighlightColor(nameAndIdContainer.getHullNames(), nameAndIdContainer.getHullIds(), npc, config.hullColor(),
				config.hullFillColor());
		HighlightColor area = resolveHighlightColor(nameAndIdContainer.getAreaNames(), nameAndIdContainer.getAreaIds(), npc, config.areaColor(),
				null);
		HighlightColor outline = resolveHighlightColor(nameAndIdContainer.getOutlineNames(), nameAndIdContainer.getOutlineIds(), npc,
				config.outlineColor(), null);
		HighlightColor clickbox = resolveHighlightColor(nameAndIdContainer.getClickboxNames(), nameAndIdContainer.getClickboxIds(), npc,
				config.clickboxColor(), config.clickboxFillColor());

		boolean isTask = slayerPluginIntegration.checkSlayerPluginEnabled() && slayerPluginService != null
				&& slayerPluginService.getTargets().contains(npc);
		boolean ignoreDead = isInSpecificNameList(nameAndIdContainer.getIgnoreDeadExclusionList(), npc)
				|| isInSpecificIdList(nameAndIdContainer.getIgnoreDeadExclusionIDList(), npc);

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

	public HighlightColor resolveHighlightColor(List<String> strList, List<String> idList, NPC npc, Color configColor,
			Color configFillColor) {
		for (String entry : idList)
		{
			int id = -1;
			String preset = "";
			if (entry.contains(":"))
			{
				String[] strArr = entry.split(":");
				if (configReaderService.isNumeric(strArr[0]))
				{
					id = Integer.parseInt(strArr[0]);
				}
				preset = strArr[1];
			}
			else if (configReaderService.isNumeric(entry))
			{
				id = Integer.parseInt(entry);
			}

			if (id == npc.getId())
			{
				return new HighlightColor(true, colorManager.getHighlightColor(preset, configColor),
						colorManager.getHighlightFillColor(preset, configFillColor));
			}
		}

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

	public boolean isInSpecificIdList(List<String> strList, NPC npc) {
		int id = npc.getId();
		for (String entry : strList)
		{
			String idStr = entry;
			if (entry.contains(":"))
			{
				String[] strArr = entry.split(":");
				idStr = strArr[0];
			}

			if (configReaderService.isNumeric(idStr) && Integer.parseInt(idStr) == id)
			{
				return true;
			}
		}
		return false;
	}

}