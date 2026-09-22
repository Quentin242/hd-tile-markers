package com.hdtilemarkers;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StealingArtefactsSourceTest
{
    private Client client;
    private ConfigManager configs;
    private StealingArtefactsSource source;
    private WorldView wv;

    @Before public void setup()
    {
        client = mock(Client.class); configs = mock(ConfigManager.class); wv = mock(WorldView.class);
        Player player = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(player);
        when(player.getWorldView()).thenReturn(wv);
        when(player.getWorldLocation()).thenReturn(new WorldPoint(1770, 3740, 0));
        source = new StealingArtefactsSource(client, configs);
    }

    private GameObject object(int id, WorldPoint at)
    {
        GameObject object = mock(GameObject.class);
        when(object.getId()).thenReturn(id); when(object.getWorldLocation()).thenReturn(at);
        when(object.getHash()).thenReturn((long) id);
        return object;
    }

    private NPC npc(int id, WorldPoint at, int orientation)
    {
        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(id); when(npc.getWorldLocation()).thenReturn(at);
        when(npc.getCurrentOrientation()).thenReturn(orientation); when(npc.getWorldView()).thenReturn(wv);
        return npc;
    }

    private List<String> keys()
    {
        List<ModelTarget> out = new ArrayList<>();
        source.collect(out);
        List<String> keys = new ArrayList<>();
        for (ModelTarget t : out) { keys.add(t.key); }
        return keys;
    }

    @Test public void marksOnlyTheTargetHousesDrawersAndLadder()
    {
        when(client.getVarbitValue(4903)).thenReturn(2);
        source.add(object(27771, new WorldPoint(1767, 3751, 0)));
        source.add(object(27772, new WorldPoint(1774, 3728, 0)));
        source.add(object(27634, new WorldPoint(1776, 3730, 0)));
        source.add(object(27634, new WorldPoint(1768, 3733, 0)));
        assertEquals(Arrays.asList("sa:object:27772", "sa:object:27634").size(), keys().size());
        assertTrue(keys().contains("sa:object:27772"));
        // Ladder highlighting follows Stealing Artefacts' own option.
        when(configs.getConfiguration("stealingartefacts", "highlightLadders")).thenReturn("false");
        assertEquals(Collections.singletonList("sa:object:27772"), keys());
    }

    @Test public void khaledOnlyWithoutATaskAndNothingOutsidePiscarilius()
    {
        source.add(npc(6971, new WorldPoint(1770, 3740, 0), 0));
        when(client.getVarbitValue(4903)).thenReturn(0);
        assertEquals(Collections.singletonList("sa:khaled"), keys());
        when(client.getVarbitValue(4903)).thenReturn(3);
        assertTrue(keys().isEmpty());
        when(client.getVarbitValue(4903)).thenReturn(0);
        when(client.getLocalPlayer().getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
        assertTrue(keys().isEmpty());
    }

    @Test public void patrolsAndLures()
    {
        assertTrue(StealingArtefactsSource.lured(npc(6980, new WorldPoint(1777, 3746, 0), 0)));
        assertFalse(StealingArtefactsSource.lured(npc(6980, new WorldPoint(1777, 3746, 0), 512)));
        assertTrue(StealingArtefactsSource.lured(npc(6980, new WorldPoint(1780, 3731, 0), 512)));
        NPC guard = npc(6975, new WorldPoint(1760, 3740, 0), 0);
        when(guard.getIndex()).thenReturn(4);
        source.add(guard);
        assertEquals(Collections.singletonList("sa:patrol:4"), keys());
        assertEquals(Collections.singletonList(guard), source.facingArrows());
        when(configs.getConfiguration("stealingartefacts", "highlightPatrols")).thenReturn("false");
        assertTrue(keys().isEmpty());
    }

    @Test public void piscariliusBounds()
    {
        assertTrue(StealingArtefactsSource.inPisc(new WorldPoint(1739, 3675, 0)));
        assertTrue(StealingArtefactsSource.inPisc(new WorldPoint(1860, 3803, 0)));
        assertFalse(StealingArtefactsSource.inPisc(new WorldPoint(1738, 3700, 0)));
    }
}
