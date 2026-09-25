package com.hdtilemarkers;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.runecraft.RunecraftConfig;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AbyssSourceTest
{
    private Player player;
    private RunecraftConfig config;
    private AbyssSource source;

    @Before public void setup()
    {
        Client client = mock(Client.class);
        player = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(player);
        ConfigManager configs = mock(ConfigManager.class);
        config = mock(RunecraftConfig.class, CALLS_REAL_METHODS);
        when(configs.getConfig(RunecraftConfig.class)).thenReturn(config);
        source = new AbyssSource(client, configs);
    }

    private void spawn(int id)
    {
        DecorativeObject rift = mock(DecorativeObject.class);
        when(rift.getId()).thenReturn(id);
        when(rift.getHash()).thenReturn((long) id);
        when(rift.getRenderable()).thenReturn(mock(Model.class));
        DecorativeObjectSpawned e = new DecorativeObjectSpawned();
        e.setDecorativeObject(rift);
        source.onDecorativeObjectSpawned(e);
    }

    private List<ModelTarget> collect()
    {
        List<ModelTarget> models = new ArrayList<>();
        source.collect(models);
        return models;
    }

    /** Magenta clickboxes of the rifts the Runecraft plugin shows, only inside the Abyss. */
    @Test public void riftsFollowTheRunecraftOptions()
    {
        spawn(ObjectID.ABYSS_EXIT_TO_NATURE);
        spawn(ObjectID.ABYSS_EXIT_TO_LAW);
        spawn(1234);
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
        assertTrue(collect().isEmpty());
        // Region 12107.
        when(player.getWorldLocation()).thenReturn(new WorldPoint((12107 >> 8) << 6, (12107 & 255) << 6, 0));
        List<ModelTarget> models = collect();
        assertEquals(2, models.size());
        assertEquals(Color.MAGENTA, models.get(0).color);
        assertTrue(models.get(0).clickbox);
        doReturn(false).when(config).showLaw();
        assertEquals(1, collect().size());
    }
}
