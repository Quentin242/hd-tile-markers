package com.hdtilemarkers.pathmarker;

import java.util.List;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PathfinderTest
{
    @Test public void routePointsDoNotNeedTheScenesTiles()
    {
        Client client = mock(Client.class);
        WorldView wv = mock(WorldView.class);
        Player player = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(player);
        when(player.getWorldView()).thenReturn(wv);
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3210, 3210, 0));
        when(wv.getBaseX()).thenReturn(3200); when(wv.getBaseY()).thenReturn(3200);
        when(wv.getSizeX()).thenReturn(104); when(wv.getSizeY()).thenReturn(104);
        // Open ground inside the blocked border the client keeps around a scene.
        int[][] flags = new int[104][104];
        for (int x = 0; x < 104; x++) { for (int y = 0; y < 104; y++) { if (x == 0 || y == 0 || x >= 99 || y >= 99) { flags[x][y] = 0xFFFFFF; } } }
        CollisionData collision = mock(CollisionData.class);
        when(collision.getFlags()).thenReturn(flags);
        when(wv.getCollisionMaps()).thenReturn(new CollisionData[]{collision, collision, collision, collision});
        // A walkable route can cross tiles the scene holds as null; the original threw on them.
        Scene scene = mock(Scene.class);
        when(scene.getTiles()).thenReturn(new Tile[4][104][104]);
        when(wv.getScene()).thenReturn(scene);
        Pair<List<WorldPoint>, Boolean> route = new Pathfinder(client, mock(PathMarker.class)).pathTo(30, 25, 1, 1, -1, -1);
        assertTrue(route.getRight());
        assertFalse(route.getLeft().isEmpty());
        assertEquals(new WorldPoint(3230, 3225, 0), route.getLeft().get(route.getLeft().size() - 1));
    }
}
