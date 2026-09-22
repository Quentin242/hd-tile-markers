package com.hdtilemarkers;

import com.hdtilemarkers.pathmarker.PathMarker;
import java.awt.Color;
import java.util.List;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WalkPredictorTest
{
    private WorldView wv;

    @Before public void setup()
    {
        wv = mock(WorldView.class);
        when(wv.getSizeX()).thenReturn(104); when(wv.getSizeY()).thenReturn(104);
        when(wv.getBaseX()).thenReturn(3200); when(wv.getBaseY()).thenReturn(3200);
        when(wv.getTileHeights()).thenReturn(new int[4][105][105]);
        when(wv.getTileSettings()).thenReturn(new byte[4][104][104]);
    }

    @Test public void rayMeetsFlatGroundInsideTheLoadedArea()
    {
        ModelShapes.Camera camera = new ModelShapes.Camera(6400, 6400 - 1500, -1200, 0.7f, 0, 600, 0, 0, 1000, 700);
        WorldPoint hit = WalkPredictor.raycast(camera, 500, 350, wv, 0);
        assertNotNull(hit);
        // Check against the exact crossing of the centre ray with height 0.
        float[] p = new float[3];
        float low = ModelShapes.NEAR, high = 40000;
        for (int i = 0; i < 60; i++) { float mid = (low + high) / 2; camera.unproject(500, 350, mid, p); if (p[2] >= 0) { high = mid; } else { low = mid; } }
        camera.unproject(500, 350, high, p);
        assertEquals(3200 + (int) Math.floor(p[0] / 128), hit.getX());
        assertEquals(3200 + (int) Math.floor(p[1] / 128), hit.getY());
    }

    @Test public void rayBeyondTheLoadedAreaUsesTheEdgeHeight()
    {
        // Camera near the north edge looking north, towards terrain that is not loaded.
        ModelShapes.Camera camera = new ModelShapes.Camera(6400, 103 * 128, -900, 0.25f, 0, 600, 0, 0, 1000, 700);
        WorldPoint hit = WalkPredictor.raycast(camera, 500, 330, wv, 0);
        assertNotNull(hit);
        assertTrue(MarkerSources.outside(wv, hit));
        assertTrue(hit.getY() >= 3200 + 104);
    }

    @Test public void pathWithoutPathfinderStepsDiagonallyThenStraight()
    {
        Player player = mock(Player.class);
        when(player.getWorldView()).thenReturn(wv);
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3210, 3210, 0));
        List<PathMarker.SceneTile> tiles = WalkPredictor.path(player, null, new WorldPoint(3215, 3230, 0), Color.RED, Color.RED, Color.RED, Color.RED, false, false);
        assertEquals(20, tiles.size());
        assertEquals(new WorldPoint(3211, 3211, 0), tiles.get(0).point);
        assertEquals(new WorldPoint(3215, 3215, 0), tiles.get(4).point);
        assertEquals(new WorldPoint(3215, 3230, 0), tiles.get(19).point);
    }

    @Test public void distantRunningPathUsesSecondaryColorsAndPrimaryDestination()
    {
        Player player = mock(Player.class);
        when(player.getWorldView()).thenReturn(wv);
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3300, 3210, 0));
        List<PathMarker.SceneTile> tiles = WalkPredictor.path(player, null, new WorldPoint(3305, 3210, 0),
            Color.RED, Color.PINK, Color.YELLOW, Color.GREEN, false, true);
        assertEquals(5, tiles.size());
        assertEquals(Color.YELLOW, tiles.get(0).stroke);
        assertEquals(Color.GREEN, tiles.get(0).fill);
        assertEquals(Color.RED, tiles.get(1).stroke);
        assertEquals(Color.YELLOW, tiles.get(2).stroke);
        assertEquals(Color.RED, tiles.get(4).stroke);
    }

    @Test public void clickboxPolygonsLoseDuplicatesCollinearPointsAndSpurs()
    {
        // A square with a repeated corner, a mid-edge point, and a spur on the top edge.
        float[] p = {0, 0, 0, 0, 5, 0, 10, 0, 10, 10, 6, 10, 6, 13, 6, 10, 0, 10};
        float[] clean = SceneShapeRenderer.clean(p);
        assertEquals(8, clean.length);
    }
}
