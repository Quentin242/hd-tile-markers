package com.hdtilemarkers;

import java.awt.Color;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ExternalMarksTest
{
    private Client client;
    private WorldView wv;
    private ExternalMarks marks;

    @Before public void setup()
    {
        client = mock(Client.class); wv = mock(WorldView.class);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        when(wv.getId()).thenReturn(-1);
        when(wv.getBaseX()).thenReturn(3200); when(wv.getBaseY()).thenReturn(3200);
        when(wv.getSizeX()).thenReturn(104); when(wv.getSizeY()).thenReturn(104);
        when(wv.getPlane()).thenReturn(0);
        when(wv.contains(any(WorldPoint.class))).thenReturn(true);
        marks = new ExternalMarks(client);
    }

    private static Map<String, Object> data(Object... kv)
    {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) { m.put((String) kv[i], kv[i + 1]); }
        return m;
    }

    private List<Marker> tiles()
    {
        List<Marker> out = new ArrayList<>();
        marks.collect(out, new ArrayList<>());
        return out;
    }

    @Test public void tilesFromAnotherPluginAreDrawnUntilCleared()
    {
        marks.onPluginMessage(new PluginMessage(ExternalMarks.NAMESPACE, "tiles", data("owner", "my-plugin", "tiles", Arrays.asList(
            data("point", new WorldPoint(3210, 3210, 0), "color", Color.CYAN, "width", 3, "label", "Here"),
            data("x", 3212, "y", 3210, "plane", 0, "color", 0x80FF0000, "size", 2)))));
        List<Marker> out = tiles();
        assertEquals(2, out.size());
        assertEquals(Color.CYAN, out.get(0).color);
        assertEquals(3, out.get(0).borderWidth, 0);
        assertEquals("Here", out.get(0).label);
        // A 2x2 area has its south-west tile on the point.
        assertEquals(2, out.get(1).width);
        assertEquals(12 * 128 + 128, out.get(1).point.getX());
        assertEquals(0x80, out.get(1).color.getAlpha());
        // Another plugin's clear leaves these alone; the owner's own clear removes them.
        marks.onPluginMessage(new PluginMessage(ExternalMarks.NAMESPACE, "clear", data("owner", "other")));
        assertEquals(2, tiles().size());
        marks.onPluginMessage(new PluginMessage(ExternalMarks.NAMESPACE, "clear", data("owner", "my-plugin")));
        assertTrue(tiles().isEmpty());
    }

    @Test public void sessionTransitionsClearMarkersButSceneLoadingKeepsThem()
    {
        for (GameState state : new GameState[]{GameState.LOGIN_SCREEN, GameState.HOPPING, GameState.CONNECTION_LOST})
        {
            marks.onPluginMessage(new PluginMessage(ExternalMarks.NAMESPACE, "tiles", data("owner", "p", "tiles",
                Collections.singletonList(data("point", new WorldPoint(3210, 3210, 0), "color", Color.CYAN)))));
            net.runelite.api.events.GameStateChanged event = new net.runelite.api.events.GameStateChanged();
            event.setGameState(GameState.LOADING);
            marks.onGameStateChanged(event);
            assertEquals(1, tiles().size());
            event.setGameState(state);
            marks.onGameStateChanged(event);
            assertTrue(tiles().isEmpty());
        }
        marks.onPluginMessage(new PluginMessage(ExternalMarks.NAMESPACE, "tiles", data("owner", "p", "tiles",
            Collections.singletonList(data("point", new WorldPoint(3210, 3210, 0), "color", Color.CYAN)))));
        marks.onProfileChanged(new net.runelite.client.events.ProfileChanged());
        assertTrue(tiles().isEmpty());
    }

    @Test public void malformedMessagesAreIgnored()
    {
        marks.onPluginMessage(new PluginMessage("someone-else", "tiles", data("owner", "x", "tiles",
            Collections.singletonList(data("point", new WorldPoint(3210, 3210, 0), "color", Color.CYAN)))));
        marks.onPluginMessage(new PluginMessage(ExternalMarks.NAMESPACE, "tiles", data("tiles", "not a list")));
        marks.onPluginMessage(new PluginMessage(ExternalMarks.NAMESPACE, "tiles", data("owner", "x", "tiles",
            Arrays.asList("text", data("point", new WorldPoint(3210, 3210, 0))))));
        assertTrue(tiles().isEmpty());
    }

    @Test public void npcStylesBecomeTilesAndModelTargets()
    {
        NPC npc = mock(NPC.class);
        when(npc.getWorldView()).thenReturn(wv);
        when(npc.getIndex()).thenReturn(7);
        when(npc.getLocalLocation()).thenReturn(new net.runelite.api.coords.LocalPoint(1344, 1344, -1));
        @SuppressWarnings("unchecked") IndexedObjectSet<NPC> set = mock(IndexedObjectSet.class);
        when(set.byIndex(7)).thenReturn(npc);
        doReturn(set).when(wv).npcs();
        marks.onPluginMessage(new PluginMessage(ExternalMarks.NAMESPACE, "npcs", data("owner", "p", "npcs", Arrays.asList(
            data("index", 7, "style", "tile", "color", Color.RED),
            data("npc", npc, "style", "hull", "color", Color.RED),
            data("npc", npc, "style", "outline", "color", Color.RED)))));
        List<Marker> tiles = new ArrayList<>();
        List<ModelTarget> models = new ArrayList<>();
        marks.collect(tiles, models);
        assertEquals(1, tiles.size());
        assertEquals(2, models.size());
        assertTrue(models.get(1).outline);
    }
}
