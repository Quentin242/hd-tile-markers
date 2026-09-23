package com.hdtilemarkers;

import java.awt.Color;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.SkillIconManager;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GauntletSourceTest
{
    private Client client;
    private ConfigManager configs;
    private GauntletSource source;

    @Before public void setup()
    {
        client = mock(Client.class); configs = mock(ConfigManager.class);
        source = new GauntletSource(client, configs, mock(SkillIconManager.class));
        when(configs.getConfiguration(GauntletSource.GROUP, "overlayResources")).thenReturn("true");
        when(configs.getConfiguration(GauntletSource.GROUP, "resourceTracker")).thenReturn("true");
        when(configs.getConfiguration(GauntletSource.GROUP, "resourceRemoveOutlineOnceAcquired")).thenReturn("true");
        when(configs.getConfiguration(GauntletSource.GROUP, "resourceOre")).thenReturn("2");
    }

    private void startRun(int region)
    {
        when(client.getMapRegions()).thenReturn(new int[]{region});
        WidgetLoaded loaded = new WidgetLoaded();
        loaded.setGroupId(637);
        source.onWidgetLoaded(loaded);
    }

    private GameObject object(int id)
    {
        GameObject o = mock(GameObject.class);
        when(o.getId()).thenReturn(id); when(o.getHash()).thenReturn((long) id);
        when(o.getLocalLocation()).thenReturn(new LocalPoint(1344, 1344, -1));
        when(o.getRenderable()).thenReturn(mock(Model.class));
        return o;
    }

    private int drawn()
    {
        List<Marker> tiles = new ArrayList<>();
        List<ModelTarget> models = new ArrayList<>();
        source.collect(tiles, models);
        return tiles.size() + models.size();
    }

    @Test public void oreDepositsDisappearOnceEnoughOreIsMined()
    {
        startRun(7512);
        source.add(object(36064));
        // Outline and tile, in The Gauntlet's default widths.
        assertEquals(2, drawn());
        source.chat("You manage to mine some ore.");
        assertEquals(2, drawn());
        source.chat("You manage to mine some ore.");
        assertEquals(0, drawn());
    }

    @Test public void corruptedDropsCountOnlyInTheCorruptedGauntlet()
    {
        startRun(7768);
        source.add(object(35967));
        source.chat("<col=005f00>Untradeable drop: 2 x Crystal ore");
        assertEquals(2, drawn());
        source.chat("<col=005f00>Untradeable drop: 2 x Corrupted ore");
        assertEquals(0, drawn());
    }

    @Test public void itsOwnTogglesAndColoursApply()
    {
        startRun(7512);
        source.add(object(36070));
        when(configs.getConfiguration(GauntletSource.GROUP, "grymRootOutlineColor", Color.class)).thenReturn(Color.PINK);
        List<Marker> tiles = new ArrayList<>();
        source.collect(tiles, new ArrayList<>());
        assertEquals(Color.PINK, tiles.get(0).color);
        when(configs.getConfiguration(GauntletSource.GROUP, "overlayGrymRoot")).thenReturn("false");
        assertEquals(0, drawn());
        when(configs.getConfiguration(GauntletSource.GROUP, "overlayGrymRoot")).thenReturn("true");
        when(configs.getConfiguration(GauntletSource.GROUP, "overlayResources")).thenReturn("false");
        assertEquals(0, drawn());
    }

    @Test public void utilitiesOnlyWithTheirOption()
    {
        source.add(object(35980));
        assertEquals(0, drawn());
        when(configs.getConfiguration(GauntletSource.GROUP, "utilitiesOutline")).thenReturn("true");
        assertEquals(1, drawn());
    }
}
