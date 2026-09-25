package com.hdtilemarkers;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class FloatClickboxTest
{
    /** Each polygon as its set of corner points, for comparing shapes whatever their starting corner. */
    private static Set<List<Float>> points(List<float[]> polygons)
    {
        Set<List<Float>> out = new HashSet<>();
        for (float[] p : polygons) { for (int i = 0; i < p.length; i += 2) { out.add(Arrays.asList(p[i], p[i + 1])); } }
        return out;
    }

    /** Each polygon as its set of corner points. */
    private static Set<List<Integer>> intPoints(List<int[]> polygons)
    {
        Set<List<Integer>> out = new HashSet<>();
        for (int[] p : polygons) { for (int i = 0; i < p.length; i += 2) { out.add(Arrays.asList(p[i], p[i + 1])); } }
        return out;
    }

    /** The union is RuneLite's RectangleUnion, polygon for polygon and corner for corner. */
    @Test public void unionMatchesRuneLite()
    {
        Random random = new Random(7);
        for (int round = 0; round < 300; round++)
        {
            int n = 1 + random.nextInt(40);
            List<net.runelite.api.geometry.RectangleUnion.Rectangle> theirs = new ArrayList<>();
            int[] ours = new int[n * 4];
            for (int i = 0; i < n; i++)
            {
                int x1 = random.nextInt(80), y1 = random.nextInt(80), x2 = x1 + 1 + random.nextInt(30), y2 = y1 + 1 + random.nextInt(30);
                theirs.add(new net.runelite.api.geometry.RectangleUnion.Rectangle(x1, y1, x2, y2));
                ours[i * 4] = x1; ours[i * 4 + 1] = y1; ours[i * 4 + 2] = x2; ours[i * 4 + 3] = y2;
            }
            List<int[]> expected = new ArrayList<>();
            for (net.runelite.api.geometry.SimplePolygon p : net.runelite.api.geometry.RectangleUnion.union(theirs).getShapes())
            {
                int[] q = new int[p.size() * 2];
                for (int i = 0; i < p.size(); i++) { q[i * 2] = p.getX(i); q[i * 2 + 1] = p.getY(i); }
                expected.add(q);
            }
            List<int[]> actual = FloatClickbox.union(ours, n);
            assertEquals("round " + round, expected.size(), actual.size());
            assertEquals("round " + round, intPoints(expected), intPoints(actual));
        }
    }

    /** A model moved by a tenth of a pixel moves its clickbox by about that, not by a whole pixel or not at all. */
    @Test public void subpixelMovementMovesTheClickbox()
    {
        float[] x = {10, 30, 20}, y = {10, 10, 30};
        int[] a = {0}, b = {1}, c = {2};
        float left0 = Float.MAX_VALUE, left1 = Float.MAX_VALUE;
        for (float[] p : FloatClickbox.of(x, y, a, b, c, 1, null, null, 0, 0, 100, 100)) { for (int i = 0; i < p.length; i += 2) { left0 = Math.min(left0, p[i]); } }
        float[] moved = {10.1f, 30.1f, 20.1f};
        for (float[] p : FloatClickbox.of(moved, y, a, b, c, 1, null, null, 0, 0, 100, 100)) { for (int i = 0; i < p.length; i += 2) { left1 = Math.min(left1, p[i]); } }
        assertEquals(0.1, left1 - left0, 1.0 / FloatClickbox.SUBPIXELS);
    }

    /** Clipped to a convex polygon like SimplePolygon.intersectWithConvex; either winding of the convex polygon works. */
    @Test public void clipToConvexBounds()
    {
        float[] square = {0, 0, 100, 0, 100, 100, 0, 100};
        float[] triangle = {50, -20, 120, 120, -20, 120};
        float[] reversed = {-20, 120, 120, 120, 50, -20};
        float[] a = FloatClickbox.clip(square, triangle), b = FloatClickbox.clip(square, reversed);
        assertNotNull(a);
        assertEquals(points(Collections.singletonList(a)), points(Collections.singletonList(b)));
        // Nothing of the square outside the triangle's bounds.
        for (int i = 0; i < a.length; i += 2) { assertTrue(a[i] >= -0.01 && a[i] <= 100.01 && a[i + 1] >= -0.01 && a[i + 1] <= 100.01); }
        assertNull(FloatClickbox.clip(square, new float[]{200, 200, 300, 200, 250, 300}));
    }

    /** Hidden faces and faces off the viewport are left out; each face grows by 5 pixels as in calculate2DBounds. */
    @Test public void facesToClickbox()
    {
        float[] x = {10, 30, 20, 500, 520, 510}, y = {10, 10, 30, 10, 10, 30};
        int[] a = {0, 3}, b = {1, 4}, c = {2, 5};
        List<float[]> one = FloatClickbox.of(x, y, a, b, c, 2, null, null, 0, 0, 100, 100);
        assertEquals(1, one.size());
        assertEquals(new HashSet<>(Arrays.asList(Arrays.asList(5f, 5f), Arrays.asList(35f, 5f), Arrays.asList(35f, 35f), Arrays.asList(5f, 35f))),
            points(one));
        assertNull(FloatClickbox.of(x, y, a, b, c, 2, new boolean[]{true, true}, null, 0, 0, 1000, 100));
    }

    @Test public void tidyRemovesStepsTooSmallToSeeAndKeepsRealOnes()
    {
        // A left edge with a half-pixel step halfway (the doubled edge), a spur on the top and a real 20 pixel step.
        float[] p = {0, 0, 40, 0, 40, 20, 60, 20, 60, 60, 0.5f, 60, 0.5f, 30, 0, 30};
        float[] tidy = FloatClickbox.tidy(p);
        assertEquals(6 * 2, tidy.length);
        assertEquals(Math.abs(FloatClickbox.area(p)), Math.abs(FloatClickbox.area(tidy)), 20);
        float[] spur = {0, 0, 20, 0, 20, -0.5f, 20.2f, 0, 40, 0, 40, 40, 0, 40};
        assertEquals(4 * 2, FloatClickbox.tidy(spur).length);
        float[] square = {0, 0, 10, 0, 10, 10, 0, 10};
        assertSame(square, FloatClickbox.tidy(square));
    }

    @Test public void tidyKeepsCurvesAndDoesNotDependOnTheStart()
    {
        // A circle of radius 40 as a staircase in quarter pixels, as a union of face rectangles gives.
        java.util.List<Float> pts = new java.util.ArrayList<>();
        float lx = Float.NaN, ly = Float.NaN;
        for (int i = 0; i < 720; i++)
        {
            double t = 2 * Math.PI * i / 720;
            float x = Math.round(40 * Math.cos(t) * 4) / 4f, y = Math.round(40 * Math.sin(t) * 4) / 4f;
            if (x == lx && y == ly) { continue; }
            if (!Float.isNaN(lx) && x != lx && y != ly) { pts.add(x); pts.add(ly); }
            pts.add(x); pts.add(y);
            lx = x; ly = y;
        }
        float[] circle = new float[pts.size()];
        for (int i = 0; i < circle.length; i++) { circle[i] = pts.get(i); }
        double area = Math.abs(FloatClickbox.area(circle));
        double tidied = Math.abs(FloatClickbox.area(FloatClickbox.tidy(circle)));
        assertEquals(area, tidied, area * 0.02);
        // Rotating where the polygon starts gives the same shape.
        float[] rotated = new float[circle.length];
        int shift = 2 * 37;
        for (int i = 0; i < circle.length; i++) { rotated[i] = circle[(i + shift) % circle.length]; }
        assertEquals(tidied, Math.abs(FloatClickbox.area(FloatClickbox.tidy(rotated))), 1e-3);
    }
}
