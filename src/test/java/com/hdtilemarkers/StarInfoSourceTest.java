package com.hdtilemarkers;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.*;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StarInfoSourceTest
{
    /** As Star Info: the star's hull, red below its tier's Mining level (tier x 10), green from it. */
    @Test public void starHullByMiningLevel()
    {
        Client client = mock(Client.class);
        ConfigManager configs = mock(ConfigManager.class);
        StarInfoSource source = new StarInfoSource(client, configs);
        GameObject star = mock(GameObject.class);
        when(star.getId()).thenReturn(41225);
        when(star.getRenderable()).thenReturn(mock(Model.class));
        assertEquals(5, StarInfoSource.tier(41225));
        GameObjectSpawned spawned = new GameObjectSpawned();
        spawned.setGameObject(star);
        source.onGameObjectSpawned(spawned);
        when(client.getBoostedSkillLevel(Skill.MINING)).thenReturn(49);
        List<ModelTarget> models = new ArrayList<>();
        source.collect(models);
        assertEquals(Color.RED, models.get(0).color);
        when(client.getBoostedSkillLevel(Skill.MINING)).thenReturn(50);
        models.clear();
        source.collect(models);
        assertEquals(Color.GREEN, models.get(0).color);
        when(configs.getConfiguration(StarInfoSource.GROUP, "colorStar")).thenReturn("false");
        models.clear();
        source.collect(models);
        assertTrue(models.isEmpty());
        GameObjectDespawned despawned = new GameObjectDespawned();
        despawned.setGameObject(star);
        source.onGameObjectDespawned(despawned);
        when(configs.getConfiguration(StarInfoSource.GROUP, "colorStar")).thenReturn(null);
        source.collect(models);
        assertTrue(models.isEmpty());
    }
}
