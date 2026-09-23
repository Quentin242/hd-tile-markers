package com.hdtilemarkers;

import com.google.gson.Gson;
import com.google.inject.Guice;
import com.google.inject.Injector;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.groundmarkers.GroundMarkerPlugin;
import net.runelite.client.plugins.npchighlight.NpcIndicatorsPlugin;
import net.runelite.client.plugins.objectindicators.ObjectIndicatorsPlugin;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Builds the plugin the way RuneLite does: a child injector created from the plugin instance. */
public class PluginInjectionTest
{
    @Test public void pluginInjectsAndStartsWithoutCycles() throws Exception
    {
        Client client = mock(Client.class);
        ConfigManager configs = mock(ConfigManager.class);
        when(configs.getConfig(HdTileMarkersConfig.class))
            .thenReturn(mock(HdTileMarkersConfig.class, CALLS_REAL_METHODS));
        when(configs.getConfig(com.hdtilemarkers.betternpc.BetterNpcHighlightConfig.class))
            .thenReturn(mock(com.hdtilemarkers.betternpc.BetterNpcHighlightConfig.class, CALLS_REAL_METHODS));
        EventBus eventBus = mock(EventBus.class);
        OverlayManager overlays = mock(OverlayManager.class);
        KeyManager keys = mock(KeyManager.class);
        MouseManager mouse = mock(MouseManager.class);
        Injector parent = Guice.createInjector(binder -> {
            binder.bind(Client.class).toInstance(client);
            binder.bind(ClientThread.class).toInstance(mock(ClientThread.class));
            binder.bind(ConfigManager.class).toInstance(configs);
            binder.bind(EventBus.class).toInstance(eventBus);
            binder.bind(OverlayManager.class).toInstance(overlays);
            binder.bind(PluginManager.class).toInstance(mock(PluginManager.class));
            // A provider, so Guice does not inject the mock's own fields.
            GroundMarkerPlugin ground = mock(GroundMarkerPlugin.class);
            binder.bind(GroundMarkerPlugin.class).toProvider(() -> ground);
            ObjectIndicatorsPlugin objects = mock(ObjectIndicatorsPlugin.class);
            binder.bind(ObjectIndicatorsPlugin.class).toProvider(() -> objects);
            NpcIndicatorsPlugin npcs = mock(NpcIndicatorsPlugin.class);
            binder.bind(NpcIndicatorsPlugin.class).toProvider(() -> npcs);
            net.runelite.client.plugins.slayer.SlayerPlugin slayer = mock(net.runelite.client.plugins.slayer.SlayerPlugin.class);
            binder.bind(net.runelite.client.plugins.slayer.SlayerPlugin.class).toProvider(() -> slayer);
            net.runelite.client.plugins.slayer.SlayerPluginService slayerService = mock(net.runelite.client.plugins.slayer.SlayerPluginService.class);
            binder.bind(net.runelite.client.plugins.slayer.SlayerPluginService.class).toProvider(() -> slayerService);
            net.runelite.client.plugins.npcunaggroarea.NpcAggroAreaPlugin aggro = mock(net.runelite.client.plugins.npcunaggroarea.NpcAggroAreaPlugin.class);
            binder.bind(net.runelite.client.plugins.npcunaggroarea.NpcAggroAreaPlugin.class).toProvider(() -> aggro);
            binder.bind(net.runelite.client.game.SkillIconManager.class).toInstance(mock(net.runelite.client.game.SkillIconManager.class));
            net.runelite.client.plugins.agility.AgilityPlugin agilityPlugin = mock(net.runelite.client.plugins.agility.AgilityPlugin.class);
            binder.bind(net.runelite.client.plugins.agility.AgilityPlugin.class).toProvider(() -> agilityPlugin);
            binder.bind(net.runelite.client.chat.ChatMessageManager.class).toInstance(mock(net.runelite.client.chat.ChatMessageManager.class));
            binder.bind(KeyManager.class).toInstance(keys);
            binder.bind(MouseManager.class).toInstance(mouse);
            binder.bind(net.runelite.client.game.SpriteManager.class).toInstance(mock(net.runelite.client.game.SpriteManager.class));
            binder.bind(Gson.class).toInstance(new Gson());
        });
        HdTileMarkersPlugin plugin = new HdTileMarkersPlugin();
        // As PluginManager does: bind the instance and install the plugin as a module.
        com.google.inject.Injector child = parent.createChildInjector(binder -> {
            binder.bind(HdTileMarkersPlugin.class).toInstance(plugin);
            binder.install(plugin);
        });
        // PluginManager takes a Config binding of the plugin's injector as its settings panel and fills in its
        // defaults: only HD Tile Markers' own config may be bound, never Better NPC Highlight's.
        java.util.List<Class<?>> bound = new java.util.ArrayList<>();
        for (com.google.inject.Key<?> key : child.getBindings().keySet())
        {
            Class<?> type = key.getTypeLiteral().getRawType();
            if (net.runelite.client.config.Config.class.isAssignableFrom(type)) { bound.add(type); }
        }
        assertEquals(java.util.Collections.singletonList(HdTileMarkersConfig.class), bound);
        plugin.startUp();
        verify(eventBus).register(any(com.hdtilemarkers.pathmarker.PathMarker.class));
        verify(eventBus).register(any(com.hdtilemarkers.betternpc.BetterNpcEvents.class));
        verify(keys).registerKeyListener(any());
        verify(mouse).registerMouseListener(any());
        verify(overlays, times(2)).add(any());
        // HD Tile Markers must never write another plugin's settings, including Better NPC Highlight's.
        verify(configs, never()).setConfiguration(anyString(), anyString(), any(Object.class));
        verify(configs, never()).setConfiguration(anyString(), anyString(), anyString());
        plugin.shutDown();
        verify(keys).unregisterKeyListener(any());
    }

    /**
     * PluginManager binds each dependency into this plugin's injector and injects its fields there,
     * without its own dependencies: those must be declared here too, or the plugin fails to load.
     */
    @Test public void dependenciesOfDependenciesAreDeclared()
    {
        java.util.Set<Class<?>> declared = new java.util.HashSet<>();
        for (net.runelite.client.plugins.PluginDependency d : HdTileMarkersPlugin.class.getAnnotationsByType(net.runelite.client.plugins.PluginDependency.class))
        {
            declared.add(d.value());
        }
        for (Class<?> dependency : declared)
        {
            for (net.runelite.client.plugins.PluginDependency d : dependency.getAnnotationsByType(net.runelite.client.plugins.PluginDependency.class))
            {
                org.junit.Assert.assertTrue(dependency.getSimpleName() + " needs " + d.value().getSimpleName(), declared.contains(d.value()));
            }
        }
    }
}
