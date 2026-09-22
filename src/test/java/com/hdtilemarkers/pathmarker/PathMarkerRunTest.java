package com.hdtilemarkers.pathmarker;

import com.hdtilemarkers.HdTileMarkersConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.*;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.mockito.junit.MockitoJUnitRunner;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class PathMarkerRunTest
{
    @Mock private Client client;
    @Mock private HdTileMarkersConfig config;
    @Mock private OverlayManager overlays;
    @Mock private KeyManager keys;
    @Mock private MouseManager mouse;
    @InjectMocks private PathMarker marker;

    @Before public void setup()
    {
        WorldView wv = mock(WorldView.class);
        Player player = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(player);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        when(player.getWorldView()).thenReturn(wv);
        when(player.getWorldArea()).thenReturn(new WorldArea(3210, 3210, 1, 1, 0));
        when(wv.getBaseX()).thenReturn(3200);
        when(wv.getBaseY()).thenReturn(3200);
        when(wv.getSizeX()).thenReturn(104);
        when(wv.getSizeY()).thenReturn(104);
        CollisionData collision = mock(CollisionData.class);
        when(collision.getFlags()).thenReturn(new int[104][104]);
        when(wv.getCollisionMaps()).thenReturn(new CollisionData[]{collision});
        marker.startUp();
    }

    @Test public void incompleteRouteStillAlternatesRunningTiles()
    {
        List<WorldPoint> main = new ArrayList<>(), middle = new ArrayList<>();
        marker.pathFromCheckpointTiles(Collections.singletonList(new WorldPoint(3215, 3210, 0)), true, middle, main, false);
        assertEquals(java.util.Arrays.asList(new WorldPoint(3212, 3210, 0), new WorldPoint(3214, 3210, 0), new WorldPoint(3215, 3210, 0)), main);
        assertEquals(java.util.Arrays.asList(new WorldPoint(3211, 3210, 0), new WorldPoint(3213, 3210, 0)), middle);
        List<WorldPoint> completeMain = new ArrayList<>(), completeMiddle = new ArrayList<>();
        marker.pathFromCheckpointTiles(Collections.singletonList(new WorldPoint(3215, 3210, 0)), true, completeMiddle, completeMain, true);
        assertEquals(completeMain, main);
        assertEquals(completeMiddle, middle);
    }

    @Test public void incompleteWalkingRouteKeepsEveryTilePrimary()
    {
        List<WorldPoint> main = new ArrayList<>(), middle = new ArrayList<>();
        marker.pathFromCheckpointTiles(Collections.singletonList(new WorldPoint(3215, 3210, 0)), false, middle, main, false);
        assertEquals(5, main.size());
        assertTrue(middle.isEmpty());
    }
}
