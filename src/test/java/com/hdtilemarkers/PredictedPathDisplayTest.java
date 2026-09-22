package com.hdtilemarkers;

import com.hdtilemarkers.pathmarker.PathMarker;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@RunWith(MockitoJUnitRunner.Silent.class)
public class PredictedPathDisplayTest
{
    @Mock private Client client;
    @Mock private PathMarker pathMarker;
    @Mock private HdTileMarkersConfig config;
    @InjectMocks private HdTileMarkersPlugin plugin;
    private final WorldPoint target = new WorldPoint(3400, 3200, 0);

    @Before public void setup() throws Exception
    {
        when(client.getLocalPlayer()).thenReturn(mock(Player.class));
        when(pathMarker.sceneTiles()).thenReturn(Collections.emptyList());
        MarkerSources sources = new MarkerSources(client,
            mock(net.runelite.client.config.ConfigManager.class), new com.google.gson.Gson(), config, null, null);
        // Test fixture wiring only; production code does not use reflection.
        java.lang.reflect.Field field = HdTileMarkersPlugin.class.getDeclaredField("sources");
        field.setAccessible(true);
        field.set(plugin, sources);
        when(config.predictWalk()).thenReturn(true);
        sources.walkedTo(target);
        when(config.activePathDrawLocations()).thenReturn(HdTileMarkersConfig.DrawLocations.GAME_WORLD);
        when(config.activePathDrawMode()).thenReturn(HdTileMarkersConfig.DrawMode.TARGET_TILE);
    }

    @Test public void neverHidesPredictedPathEvenIfKeyStateIsSet()
    {
        when(config.activePathDisplaySetting()).thenReturn(HdTileMarkersConfig.PathDisplaySetting.NEVER);
        when(pathMarker.isKeyDisplayActivePath()).thenReturn(true);
        assertTrue(plugin.pathTiles().isEmpty());
    }

    @Test public void keyControlledPredictionsRespectVisibility()
    {
        for (HdTileMarkersConfig.PathDisplaySetting setting : new HdTileMarkersConfig.PathDisplaySetting[]{
            HdTileMarkersConfig.PathDisplaySetting.WHILE_KEY_PRESSED, HdTileMarkersConfig.PathDisplaySetting.TOGGLE_ON_KEYPRESS})
        {
            when(config.activePathDisplaySetting()).thenReturn(setting);
            when(pathMarker.isKeyDisplayActivePath()).thenReturn(false);
            assertTrue(plugin.pathTiles().isEmpty());
            when(pathMarker.isKeyDisplayActivePath()).thenReturn(true);
            assertEquals(1, plugin.pathTiles().size());
        }
    }

    @Test public void targetOnlyDrawsTheDestinationWithoutIntermediateTiles()
    {
        when(config.activePathDisplaySetting()).thenReturn(HdTileMarkersConfig.PathDisplaySetting.ALWAYS);
        List<PathMarker.SceneTile> tiles = plugin.pathTiles();
        assertEquals(1, tiles.size());
        assertEquals(target, tiles.get(0).point);
        when(config.activePathDrawLocations()).thenReturn(HdTileMarkersConfig.DrawLocations.MINIMAP);
        assertTrue(plugin.pathTiles().isEmpty());
    }
}
