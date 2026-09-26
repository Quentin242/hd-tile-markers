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
    private HdTileMarkersConfig hdConfig;
    private WorldView wv;
    private Client client;
    private final List<Tile> marks = new ArrayList<>();

    @Before public void setup()
    {
        client = mock(Client.class);
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
        hdConfig = mock(HdTileMarkersConfig.class, CALLS_REAL_METHODS);
        source = new AgilitySource(client, plugin, configs, hdConfig);
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

    @Test public void unavailableClickboxDoesNotStopObstaclesAndRecoversNextTick()
    {
        GameObject changing = obstacle(ObjectID.CLIMBING_BRANCH);
        GameObject other = obstacle(AgilityObstacles.OBSTACLE_IDS.iterator().next());
        obstacles(changing, other);
        when(client.getMouseCanvasPosition()).thenReturn(new Point(100, 100));
        when(changing.getCanvasLocation()).thenReturn(new Point(100, 100));
        when(changing.getClickbox()).thenThrow(new NullPointerException("client model unavailable"))
            .thenReturn(new java.awt.Rectangle(90, 90, 20, 20));
        List<ModelTarget> models = new ArrayList<>();
        source.collect(new ArrayList<>(), models);
        assertEquals(2, models.size());
        assertEquals(config.getOverlayColor(), models.stream().filter(t -> t.object == changing).findFirst().get().color);
        models.clear();
        source.collect(new ArrayList<>(), models);
        assertEquals(2, models.size());
        assertEquals(config.getOverlayColor().darker(), models.stream().filter(t -> t.object == changing).findFirst().get().color);
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

    /** By default obstacles keep the plugin's own 2350 range; with extended ranges, as far as the draw distance. */
    @Test public void obstaclesFollowTheDrawDistanceWhenExtended()
    {
        when(wv.getPlane()).thenReturn(0);
        GameObject far = obstacle(AgilityObstacles.OBSTACLE_IDS.iterator().next());
        when(far.getLocalLocation()).thenReturn(new LocalPoint(1344 + 40 * 128, 1344, -1));
        obstacles(far);
        List<ModelTarget> models = new ArrayList<>();
        source.collect(new ArrayList<>(), models);
        assertTrue(models.isEmpty());
        doReturn(true).when(hdConfig).extendRanges();
        source.collect(new ArrayList<>(), models);
        assertEquals(1, models.size());
        doReturn(20).when(hdConfig).distance();
        models.clear();
        source.collect(new ArrayList<>(), models);
        assertTrue(models.isEmpty());
    }

    /** An obstacle Rooftop Agility Improved highlights is left to it: one highlight, not both. */
    @Test public void obstaclesClaimedByRooftopsAreLeftOut()
    {
        when(wv.getPlane()).thenReturn(0);
        GameObject obstacle = obstacle(AgilityObstacles.OBSTACLE_IDS.iterator().next());
        obstacles(obstacle);
        List<ModelTarget> models = new ArrayList<>();
        source.collect(new ArrayList<>(), models, java.util.Collections.singleton(obstacle), java.util.Collections.emptySet());
        assertTrue(models.isEmpty());
        source.collect(new ArrayList<>(), models, java.util.Collections.emptySet(), java.util.Collections.emptySet());
        assertEquals(1, models.size());
    }
}
