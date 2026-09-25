package com.hdtilemarkers;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.blastfurnace.BlastFurnaceConfig;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class BlastFurnaceSourceTest
{
    private Client client;
    private BlastFurnaceSource source;
    private HdTileMarkersConfig hdConfig;

    @Before public void setup()
    {
        client = mock(Client.class);
        Player player = mock(Player.class);
        when(player.getLocalLocation()).thenReturn(new LocalPoint(1344, 1344, -1));
        when(client.getLocalPlayer()).thenReturn(player);
        ConfigManager configs = mock(ConfigManager.class);
        // Both clickboxes are off by default in the Blast Furnace plugin.
        BlastFurnaceConfig config = mock(BlastFurnaceConfig.class, CALLS_REAL_METHODS);
        doReturn(true).when(config).showConveyorBelt();
        doReturn(true).when(config).showBarDispenser();
        when(configs.getConfig(BlastFurnaceConfig.class)).thenReturn(config);
        hdConfig = mock(HdTileMarkersConfig.class, CALLS_REAL_METHODS);
        source = new BlastFurnaceSource(client, configs, hdConfig);
    }

    private GameObject spawn(int id, int x)
    {
        GameObject o = mock(GameObject.class);
        when(o.getId()).thenReturn(id);
        when(o.getLocalLocation()).thenReturn(new LocalPoint(x, 1344, -1));
        when(o.getRenderable()).thenReturn(mock(Model.class));
        GameObjectSpawned e = new GameObjectSpawned();
        e.setGameObject(o);
        source.onGameObjectSpawned(e);
        return o;
    }

    private List<ModelTarget> collect()
    {
        List<ModelTarget> models = new ArrayList<>();
        source.collect(models);
        return models;
    }

    /** As its overlay: the belt is red while bars are hot (state 1), the dispenser green once the bars are ready (state 3). */
    @Test public void coloursFollowTheDispenserState()
    {
        spawn(ObjectID.BLAST_FURNACE_CONVEYER_BELT_CLICKABLE, 1472);
        spawn(ObjectID.BLAST_FURNACE_DISPENSER, 1600);
        when(client.getVarbitValue(VarbitID.BLAST_FURNACE_BARS_HOT)).thenReturn(1);
        List<ModelTarget> models = collect();
        assertEquals(2, models.size());
        assertEquals(Color.RED, models.get(0).color);
        assertTrue(models.get(0).clickbox);
        assertEquals(20, models.get(0).fill.getAlpha());
        assertEquals(Color.RED, models.get(1).color);
        when(client.getVarbitValue(VarbitID.BLAST_FURNACE_BARS_HOT)).thenReturn(3);
        models = collect();
        assertEquals(Color.GREEN, models.get(0).color);
        assertEquals(Color.GREEN, models.get(1).color);
    }

    @Test public void despawnedAndDistantObjectsAreNotDrawn()
    {
        GameObject belt = spawn(ObjectID.BLAST_FURNACE_CONVEYER_BELT_CLICKABLE, 1344 + 20 * 128);
        assertTrue(collect().isEmpty());
        doReturn(true).when(hdConfig).extendRanges();
        assertEquals(1, collect().size());
        GameObjectDespawned e = new GameObjectDespawned();
        e.setGameObject(belt);
        source.onGameObjectDespawned(e);
        assertTrue(collect().isEmpty());
    }
}
