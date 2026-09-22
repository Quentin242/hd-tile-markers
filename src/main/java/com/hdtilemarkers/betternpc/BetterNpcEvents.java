/*
 * Adapted from BetterNpcHighlightPlugin (Better NPC Highlight, https://github.com/riktenx/better-npc-highlight,
 * commit bf59bfb9a616897e9ffcd14d0d2b543e4c119b09). Copyright (c) 2022, Buchus. BSD 2-Clause License, see
 * META-INF/LICENSE-better-npc-highlight and THIRD_PARTY_NOTICES.md. Changes for HD Tile Markers:
 * only the event handling that keeps the highlight list current; no overlays, menus, entity hider,
 * config migration or Slayer plugin enabling. Better NPC Highlight itself stays responsible for those.
 */
package com.hdtilemarkers.betternpc;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.slayer.SlayerPluginService;

/** Keeps Better NPC Highlight's NPC list up to date, as its plugin class does. */
@Singleton
public class BetterNpcEvents
{
	@Inject
	private Client client;

	@Inject
	private SlayerPluginService slayerPluginService;

	@Inject
	private SlayerPluginManager slayerPluginIntegration;

	@Inject
	private ConfigTransformManager configTransformManager;

	@Inject
	private NameAndIdContainer nameAndIdContainer;

	@Inject
	private RespawnManager respawnManager;

	/** Call on the client thread. */
	public void startUp()
	{
		reset();
		configTransformManager.reloadLists();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			configTransformManager.recreateNPCInfoList();
		}
	}

	public void shutDown()
	{
		reset();
	}

	private void reset()
	{
		nameAndIdContainer.getNpcList().clear();
		nameAndIdContainer.setCurrentTask("");
		nameAndIdContainer.clearAll();
		respawnManager.reset();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals(BetterNpcHighlightConfig.CONFIG_GROUP))
		{
			configTransformManager.updateConfig(event);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			nameAndIdContainer.getNpcList().clear();
			respawnManager.onGameStateChanged();
		}
	}

	@Subscribe(priority = -1)
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		NPCInfo npcInfo = configTransformManager.createNpcInfo(npc);
		if (npcInfo != null)
		{
			nameAndIdContainer.getNpcList().add(npcInfo);
			if (!client.isInInstancedRegion())
			{
				respawnManager.onNpcSpawned(npc);
			}
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		respawnManager.onNpcDespawned(npc);
		nameAndIdContainer.getNpcList().removeIf(n -> n.getNpc().getIndex() == npc.getIndex());
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		respawnManager.onGraphicsObjectCreated(event);
	}

	@Subscribe(priority = -1)
	public void onNpcChanged(NpcChanged event)
	{
		NPC npc = event.getNpc();
		nameAndIdContainer.getNpcList().removeIf(n -> n.getNpc().getIndex() == npc.getIndex());
		NPCInfo npcInfo = configTransformManager.createNpcInfo(npc);
		if (npcInfo != null)
		{
			nameAndIdContainer.getNpcList().add(npcInfo);
		}
	}

	@Subscribe(priority = -1)
	public void onGameTick(GameTick event)
	{
		if (slayerPluginIntegration.checkSlayerPluginEnabled() && !nameAndIdContainer.getCurrentTask().equals(slayerPluginService.getTask()))
		{
			configTransformManager.recreateNPCInfoList();
		}
		respawnManager.onGameTick();
	}
}
