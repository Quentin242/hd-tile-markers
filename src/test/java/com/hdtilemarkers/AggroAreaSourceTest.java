package com.hdtilemarkers;

import java.awt.Color;
import java.awt.geom.GeneralPath;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class AggroAreaSourceTest
{
    /** A square of tile edges, as NpcAggroAreaPlugin builds it: one-tile segments in local coordinates. */
    private static GeneralPath square(int x0, int y0, int tiles)
    {
        GeneralPath path = new GeneralPath();
        path.moveTo(x0, y0);
        for (int i = 1; i <= tiles; i++) { path.lineTo(x0 + i * 128, y0); }
        for (int i = 1; i <= tiles; i++) { path.lineTo(x0 + tiles * 128, y0 + i * 128); }
        for (int i = 1; i <= tiles; i++) { path.lineTo(x0 + (tiles - i) * 128, y0 + tiles * 128); }
        for (int i = 1; i <= tiles; i++) { path.lineTo(x0, y0 + (tiles - i) * 128); }
        path.closePath();
        return path;
    }

    @Test public void areaBecomesShortConnectedLines()
    {
        List<Marker> out = new ArrayList<>();
        AggroAreaSource.lines(square(1280, 1280, 21), new LocalPoint(2560, 2560, -1), 0, Color.YELLOW, -1, AggroAreaSource.MAX_LOCAL_DRAW_LENGTH, out);
        int edges = 0;
        for (Marker m : out)
        {
            assertNotNull(m.lineX);
            assertTrue(m.lineX.length >= 2 && m.lineX.length <= 7);
            assertEquals(Color.YELLOW, m.color);
            edges += m.lineX.length - 1;
        }
        // All 84 tile edges of the 21x21 area, each once.
        assertEquals(84, edges);
    }

    @Test public void closePathDrawsTheLastEdge()
    {
        GeneralPath path = new GeneralPath();
        path.moveTo(1280, 1280);
        path.lineTo(1408, 1280);
        path.lineTo(1408, 1408);
        path.lineTo(1280, 1408);
        path.closePath();
        List<Marker> out = new ArrayList<>();
        AggroAreaSource.lines(path, new LocalPoint(1344, 1344, -1), 0, Color.YELLOW, -1, AggroAreaSource.MAX_LOCAL_DRAW_LENGTH, out);
        assertEquals(1, out.size());
        assertEquals(5, out.get(0).lineX.length);
        assertEquals(1280, out.get(0).lineX[4]);
        assertEquals(1280, out.get(0).lineY[4]);
    }

    @Test public void onlyLinesNearThePlayer()
    {
        List<Marker> out = new ArrayList<>();
        // The player stands 30 tiles east of the area: nothing lies within 20 tiles.
        AggroAreaSource.lines(square(1280, 1280, 5), new LocalPoint(1280 + 35 * 128, 1280, -1), 0, Color.YELLOW, -1, AggroAreaSource.MAX_LOCAL_DRAW_LENGTH, out);
        assertTrue(out.isEmpty());
    }
}
