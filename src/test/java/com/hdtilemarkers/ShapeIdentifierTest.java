package com.hdtilemarkers;

import java.awt.Rectangle;
import java.util.Arrays;
import net.runelite.api.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ShapeIdentifierTest
{
    private static GameObject object(int x, int y, Rectangle clickbox, Rectangle hull)
    {
        GameObject o = mock(GameObject.class);
        when(o.getCanvasLocation()).thenReturn(new net.runelite.api.Point(x, y));
        when(o.getClickbox()).thenReturn(clickbox);
        when(o.getConvexHull()).thenReturn(hull == null ? null : new java.awt.Polygon(
            new int[]{hull.x, hull.x + hull.width, hull.x + hull.width, hull.x},
            new int[]{hull.y, hull.y, hull.y + hull.height, hull.y + hull.height}, 4));
        return o;
    }

    /** The object whose own clickbox (or hull) has exactly the drawn bounds is found; a like object beside it is not. */
    @Test public void findsTheObjectWithTheseExactBounds()
    {
        ShapeIdentifier identifier = new ShapeIdentifier(mock(Client.class), "t:");
        WorldView wv = mock(WorldView.class);
        Rectangle drawn = new Rectangle(100, 100, 40, 60);
        GameObject beside = object(150, 160, new Rectangle(130, 100, 40, 60), null);
        GameObject target = object(120, 160, new Rectangle(100, 100, 40, 60), null);
        ShapeIdentifier.Found f = identifier.identifyAmong(drawn, Arrays.asList(beside, target), wv);
        assertSame(target, f.target);
        assertFalse(f.hull);
        GameObject hulled = object(120, 160, new Rectangle(90, 90, 70, 80), drawn);
        f = identifier.identifyAmong(drawn, Arrays.asList(beside, hulled), wv);
        assertSame(hulled, f.target);
        assertTrue(f.hull);
        GameObject far = object(400, 400, new Rectangle(100, 100, 40, 60), null);
        assertNull(identifier.identifyAmong(drawn, Arrays.asList(beside, far), wv));
    }
}
