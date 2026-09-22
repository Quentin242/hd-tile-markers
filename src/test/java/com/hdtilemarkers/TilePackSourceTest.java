package com.hdtilemarkers;

import com.google.gson.Gson;
import java.awt.Color;
import java.util.List;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class TilePackSourceTest
{
    private static final Gson GSON = new com.google.gson.GsonBuilder()
        .registerTypeAdapter(Color.class, new net.runelite.http.api.gson.ColorTypeAdapter()).create();
    private ConfigManager configs;
    private WorldView wv;

    @Before public void setup()
    {
        configs = mock(ConfigManager.class);
        wv = mock(WorldView.class);
        when(wv.getId()).thenReturn(-1);
        // Abyssal Sire is pack 1 in the bundled list; its first tile is in region 11850.
        when(wv.getMapRegions()).thenReturn(new int[]{11850});
        int baseX = (11850 >> 8) << 6, baseY = (11850 & 255) << 6;
        when(wv.getBaseX()).thenReturn(baseX); when(wv.getBaseY()).thenReturn(baseY);
        when(wv.getSizeX()).thenReturn(104); when(wv.getSizeY()).thenReturn(104);
        when(wv.contains(any(WorldPoint.class))).thenReturn(true);
    }

    @Test public void onlyEnabledBundledPacksAreDrawnWithTheirColors()
    {
        TilePackSource source = new TilePackSource(configs, GSON);
        assertTrue(source.markers(wv).isEmpty());
        when(configs.getConfiguration(TilePackSource.DATA_GROUP, "packs")).thenReturn("[1]");
        source.clear();
        List<Marker> markers = source.markers(wv);
        assertFalse(markers.isEmpty());
        assertEquals(new Color(255, 255, 0), markers.get(0).color);
        assertEquals(2f, markers.get(0).borderWidth, 0);
        assertEquals(50, markers.get(0).fill.getAlpha());
        verify(configs, never()).setConfiguration(anyString(), anyString(), any(Object.class));
    }

    @Test public void customPacksAndOverrideColorFollowTilePacksSettings()
    {
        when(configs.getConfiguration(TilePackSource.DATA_GROUP, "packs")).thenReturn("[10000]");
        when(configs.getConfiguration(TilePackSource.DATA_GROUP, "customPacks")).thenReturn(
            "{\"10000\":{\"id\":10000,\"packName\":\"mine\",\"packTiles\":\"[{\\\"regionId\\\":11850,\\\"regionX\\\":5,\\\"regionY\\\":5,\\\"z\\\":0,\\\"color\\\":\\\"#FF00FF00\\\",\\\"label\\\":\\\"A\\\"}]\"}}");
        when(configs.getConfiguration(TilePackSource.SETTINGS_GROUP, "overrideColorActive")).thenReturn("true");
        when(configs.getConfiguration(TilePackSource.SETTINGS_GROUP, "overrideColor", Color.class)).thenReturn(Color.MAGENTA);
        when(configs.getConfiguration(TilePackSource.SETTINGS_GROUP, "borderWidth")).thenReturn("3.5");
        List<Marker> markers = new TilePackSource(configs, GSON).markers(wv);
        assertEquals(1, markers.size());
        assertEquals(Color.MAGENTA, markers.get(0).color);
        assertEquals(3.5f, markers.get(0).borderWidth, 0);
        assertEquals("A", markers.get(0).label);
    }
}
