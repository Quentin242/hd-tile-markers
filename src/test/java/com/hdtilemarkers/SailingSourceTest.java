package com.hdtilemarkers;

import java.awt.Color;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SailingSourceTest
{
    private Client client;
    private ConfigManager configs;
    private SailingSource source;
    private WorldView top, boat;

    @Before public void setup()
    {
        client = mock(Client.class); configs = mock(ConfigManager.class);
        top = mock(WorldView.class); boat = mock(WorldView.class);
        when(top.isTopLevel()).thenReturn(true); when(top.getId()).thenReturn(-1);
        when(boat.isTopLevel()).thenReturn(false); when(boat.getId()).thenReturn(3);
        when(client.getTopLevelWorldView()).thenReturn(top);
        Player player = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(player);
        when(player.getWorldView()).thenReturn(boat);
        source = new SailingSource(client, configs);
    }

    private GameObject object(int id, WorldView wv)
    {
        GameObject o = mock(GameObject.class);
        when(o.getId()).thenReturn(id); when(o.getWorldView()).thenReturn(wv); when(o.getHash()).thenReturn((long) id);
        when(o.getLocalLocation()).thenReturn(new LocalPoint(1344, 1344, -1));
        when(o.getSceneMinLocation()).thenReturn(new Point(10, 10)); when(o.getSceneMaxLocation()).thenReturn(new Point(11, 11));
        ObjectComposition def = mock(ObjectComposition.class);
        when(def.getId()).thenReturn(id);
        when(client.getObjectDefinition(id)).thenReturn(def);
        return o;
    }

    private List<Marker> collect()
    {
        List<Marker> out = new ArrayList<>();
        source.collect(out);
        return out;
    }

    @Test public void nothingWhileNotSailing()
    {
        when(client.getLocalPlayer().getWorldView()).thenReturn(top);
        source.add(object(ObjectID.SAILING_RAPIDS, top));
        assertTrue(collect().isEmpty());
    }

    @Test public void rapidsFollowTheHelmTier()
    {
        source.add(object(ObjectID.SAILING_RAPIDS_STRONG, top));
        // No helm known: unknown colour, over the rapid's 2x2 footprint.
        Marker m = collect().get(0);
        assertEquals(Color.YELLOW, m.color);
        assertEquals(2, m.width);
        assertEquals(SailingSource.FILL, m.fill);
        source.add(object(ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_OAK, boat));
        assertEquals(Color.RED, collect().get(0).color);
        source.add(object(ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_MAHOGANY, boat));
        assertEquals(Color.CYAN, collect().get(0).color);
        when(configs.getConfiguration("sailing", "highlightRapids")).thenReturn("false");
        assertTrue(collect().isEmpty());
    }

    @Test public void wrecksByLevelAndOption()
    {
        when(client.getBoostedSkillLevel(Skill.SAILING)).thenReturn(30);
        source.add(object(ObjectID.SAILING_SMALL_SHIPWRECK, top));
        source.add(object(ObjectID.SAILING_LARGE_SHIPWRECK_STUMP, top));
        List<Marker> marks = collect();
        // The high-level stump is hidden by default; the active wreck covers the 15x15 salvage area.
        assertEquals(1, marks.size());
        assertEquals(15, marks.get(0).width);
        assertEquals(Color.GREEN, marks.get(0).color);
        when(configs.getConfiguration("sailing", "salvagingHideHighLevelWrecks")).thenReturn("true");
        assertEquals(2, collect().size());
    }

    @Test public void lostCratesAreFiveByFive()
    {
        assertTrue(SailingSource.lostCrate(ObjectID.SAILING_BT_JUBBLY_JIVE_COLLECTABLE_30));
        assertFalse(SailingSource.lostCrate(ObjectID.SAILING_RAPIDS));
        source.add(object(ObjectID.SAILING_BT_TEMPOR_TANTRUM_COLLECTABLE_1, top));
        assertEquals(5, collect().get(0).width);
    }

    @Test public void boatAreaRotatesAsModelToCanvas()
    {
        WorldEntityConfig wec = mock(WorldEntityConfig.class);
        when(wec.getBoundsX()).thenReturn(0); when(wec.getBoundsY()).thenReturn(64);
        when(wec.getBoundsWidth()).thenReturn(256); when(wec.getBoundsHeight()).thenReturn(512);
        LocalPoint center = new LocalPoint(5000, 6000, -1);
        Marker m = SailingSource.boatArea("t", wec, center, 0, 0, Color.CYAN);
        assertArrayEquals(new int[]{5128, 5128, 4872, 4872}, m.quadX);
        assertArrayEquals(new int[]{5808, 6320, 6320, 5808}, m.quadY);
        // A quarter turn: (x, y) -> (y, -x).
        Marker r = SailingSource.boatArea("t", wec, center, 512, 0, Color.CYAN);
        assertArrayEquals(new int[]{5000 - 192, 5000 + 320, 5000 + 320, 5000 - 192}, r.quadX);
        assertArrayEquals(new int[]{6000 - 128, 6000 - 128, 6000 + 128, 6000 + 128}, r.quadY);
    }

    static class RapidsOverlay extends Overlay { @Override public java.awt.Dimension render(java.awt.Graphics2D g) { return null; } }

    @Test public void heldOverlaysComeBackOnlyWhenStillWanted()
    {
        OverlayManager overlays = mock(OverlayManager.class);
        PluginManager plugins = mock(PluginManager.class);
        Plugin sailingPlugin = mock(Plugin.class);
        when(plugins.getPlugins()).thenReturn(Collections.singletonList(sailingPlugin));
        when(plugins.isPluginActive(sailingPlugin)).thenReturn(true);
        Overlay rapids = new RapidsOverlay();
        when(overlays.anyMatch(any())).thenAnswer(i -> { ((java.util.function.Predicate<Overlay>) i.getArgument(0)).test(rapids); return false; });
        HeldOverlays held = new HeldOverlays(overlays, plugins, sailingPlugin.getClass().getName(), o -> o == rapids);
        held.update(true, o -> true);
        assertTrue(held.drawing());
        verify(overlays).remove(rapids);
        // Its own toggle went off meanwhile: not given back.
        held.update(false, o -> false);
        verify(overlays, never()).add(rapids);
        held.update(true, o -> true);
        held.update(false, o -> true);
        verify(overlays).add(rapids);
        // Plugin stopped: nothing to draw, nothing given back.
        when(plugins.isPluginActive(sailingPlugin)).thenReturn(false);
        held.update(true, o -> true);
        assertFalse(held.drawing());
    }
}
