/*
 * Adapted from Better NPC Highlight, https://github.com/riktenx/better-npc-highlight,
 * commit bf59bfb9a616897e9ffcd14d0d2b543e4c119b09. BSD 2-Clause License, see
 * META-INF/LICENSE-better-npc-highlight and THIRD_PARTY_NOTICES.md. Changes for HD
 * Tile Markers: package; enableSlayerPlugin removed (HD Tile Markers never changes other plugins).
 */
package com.hdtilemarkers.betternpc;

import java.util.Optional;

import javax.inject.Inject;


import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

@Slf4j
public class SlayerPluginManager {
	@Inject
	private PluginManager pluginManager;

	private BetterNpcHighlightConfig config;

	// Read from ConfigManager, not bound in HD Tile Markers' injector: RuneLite takes a plugin's Config bindings as
	// its own settings panel and fills in their defaults, which must never touch Better NPC Highlight's.
	@Inject
	void readConfig(net.runelite.client.config.ConfigManager configManager)
	{
		config = configManager.getConfig(BetterNpcHighlightConfig.class);
	}

	public boolean checkSlayerPluginEnabled() {
		final Optional<Plugin> slayerPlugin = pluginManager.getPlugins().stream().filter(p -> p.getName().equals("Slayer")).findFirst();
		return slayerPlugin.isPresent() && pluginManager.isPluginEnabled(slayerPlugin.get());
	}
}
