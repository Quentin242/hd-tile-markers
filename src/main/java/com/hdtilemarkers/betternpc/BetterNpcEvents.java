/*
 * Copyright (c) 2022, Buchus <http://github.com/MoreBuchus>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
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
	private NameListContainer nameListContainer;

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
		nameListContainer.getNpcList().clear();
		nameListContainer.setCurrentTask("");
		nameListContainer.clearAll();
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
			nameListContainer.getNpcList().clear();
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
			nameListContainer.getNpcList().add(npcInfo);
			if (!client.getTopLevelWorldView().isInstance())
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
		nameListContainer.getNpcList().removeIf(n -> n.getNpc().getIndex() == npc.getIndex());
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
		nameListContainer.getNpcList().removeIf(n -> n.getNpc().getIndex() == npc.getIndex());
		NPCInfo npcInfo = configTransformManager.createNpcInfo(npc);
		if (npcInfo != null)
		{
			nameListContainer.getNpcList().add(npcInfo);
		}
	}

	@Subscribe(priority = -1)
	public void onGameTick(GameTick event)
	{
		if (slayerPluginIntegration.checkSlayerPluginEnabled() && !nameListContainer.getCurrentTask().equals(slayerPluginService.getTask()))
		{
			configTransformManager.recreateNPCInfoList();
		}
		respawnManager.onGameTick();
	}
}
