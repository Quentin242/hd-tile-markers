package com.hdtilemarkers;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.agility.AgilityConfig;
import net.runelite.client.plugins.agility.AgilityPlugin;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AgilitySourceTest
{
    private AgilityPlugin plugin;
    private AgilityConfig config;
    private AgilitySource source;
    private WorldView wv;
    private final List<Tile> marks = new ArrayList<>();

    @Before public void setup()
    {
        Client client = mock(Client.class);
        plugin = mock(AgilityPlugin.class);
        config = mock(AgilityConfig.class, CALLS_REAL_METHODS);
        ConfigManager configs = mock(ConfigManager.class);
        when(configs.getConfig(AgilityConfig.class)).thenReturn(config);
        wv = mock(WorldView.class);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        CameraFocusableEntity focus = mock(CameraFocusableEntity.class);
        when(focus.getCameraFocus()).thenReturn(new LocalPoint(1344, 1344, -1));
        when(client.getCameraFocusEntity()).thenReturn(focus);
        when(plugin.getMarksOfGrace()).thenReturn(marks);
        when(plugin.getNpcs()).thenReturn(Collections.emptySet());
        source = new AgilitySource(client, plugin, configs);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void obstacles(TileObject... objects)
    {
        Map map = new HashMap();
        for (TileObject o : objects) { map.put(o, null); }
        doReturn(map).when(plugin).getObstacles();
    }

    private GameObject obstacle(int id)
    {
        GameObject o = mock(GameObject.class);
        when(o.getId()).thenReturn(id); when(o.getHash()).thenReturn((long) id);
        when(o.getLocalLocation()).thenReturn(new LocalPoint(1472, 1344, -1));
        when(o.getWorldLocation()).thenReturn(new WorldPoint(3210, 3210, 0));
        when(o.getRenderable()).thenReturn(mock(Model.class));
        return o;
    }

    @Test public void courseObstaclesAreClickboxesInItsColour()
    {
        obstacles(obstacle(ObjectID.CLIMBING_BRANCH));
        List<ModelTarget> models = new ArrayList<>();
        source.collect(new ArrayList<>(), models);
        assertEquals(1, models.size());
        assertTrue(models.get(0).clickbox);
        assertEquals(config.getOverlayColor(), models.get(0).color);
        // Its own "show clickboxes" option switches them off.
        when(config.showClickboxes()).thenReturn(false);
        models.clear();
        source.collect(new ArrayList<>(), models);
        assertTrue(models.isEmpty());
    }

    @Test public void marksOfGraceColourTheCourseAndGetATile()
    {
        obstacles(obstacle(ObjectID.CLIMBING_BRANCH));
        Tile mark = mock(Tile.class);
        when(mark.getLocalLocation()).thenReturn(new LocalPoint(1600, 1344, -1));
        when(mark.getItemLayer()).thenReturn(mock(ItemLayer.class));
        marks.add(mark);
        List<Marker> tiles = new ArrayList<>();
        List<ModelTarget> models = new ArrayList<>();
        source.collect(tiles, models);
        assertEquals(config.getMarkColor(), models.get(0).color);
        assertEquals(1, tiles.size());
        assertEquals(config.getMarkColor(), tiles.get(0).color);
    }
}
