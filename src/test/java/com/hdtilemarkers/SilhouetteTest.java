package com.hdtilemarkers;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SilhouetteTest
{
    @Test public void twoTrianglesOfASquareGiveOneRectangle()
    {
        float[] x = {100, 140, 140, 100}, y = {100, 100, 130, 130};
        List<float[]> loops = Silhouette.trace(x, y, new int[]{0, 0}, new int[]{1, 2}, new int[]{2, 3}, 2, null);
        assertEquals(1, loops.size());
        float[] loop = loops.get(0);
        // The staircase of cell edges simplifies back to the four corners.
        assertEquals(8, loop.length);
        assertEquals(40 * 30, Math.abs(area(loop)), 40 * 30 * 0.05);
    }

    @Test public void separateShapesAndHiddenFaces()
    {
        // Two triangles far apart; the second is hidden.
        float[] x = {0, 30, 0, 200, 230, 200}, y = {0, 0, 30, 0, 0, 30};
        int[] a = {0, 3}, b = {1, 4}, c = {2, 5};
        assertEquals(2, Silhouette.trace(x, y, a, b, c, 2, null).size());
        assertEquals(1, Silhouette.trace(x, y, a, b, c, 2, new boolean[]{false, true}).size());
    }

    @Test public void aRingHasAnOuterLoopAndAHole()
    {
        // A square frame made of four rectangles (eight triangles) around an empty centre.
        float[] x = {0, 60, 60, 0, 0, 60, 60, 0, 0, 20, 20, 0, 40, 60, 60, 40};
        float[] y = {0, 0, 20, 20, 40, 40, 60, 60, 0, 0, 60, 60, 0, 0, 60, 60};
        int[] a = {0, 0, 4, 4, 8, 8, 12, 12}, b = {1, 2, 5, 6, 9, 10, 13, 14}, c = {2, 3, 6, 7, 10, 11, 14, 15};
        List<float[]> loops = Silhouette.trace(x, y, a, b, c, 8, null);
        assertEquals(2, loops.size());
        double first = area(loops.get(0)), second = area(loops.get(1));
        // Opposite windings: outline offsets then point away from the covered area on both.
        assertTrue(first * second < 0);
        assertEquals(3600 + 400, Math.abs(first) + Math.abs(second), 250);
    }

    @Test public void reusedRasterDoesNotRetainPreviousCoverage()
    {
        Silhouette.Scratch scratch = new Silhouette.Scratch();
        int[] a = {0}, b = {1}, c = {2};
        Silhouette.trace(new float[]{0, 100, 0}, new float[]{0, 0, 100}, a, b, c, 1, null, scratch);
        long[] buffer = scratch.bits;
        float[] x = {20, 30, 20}, y = {40, 40, 50};
        List<float[]> expected = Silhouette.trace(x, y, a, b, c, 1, null);
        List<float[]> actual = Silhouette.trace(x, y, a, b, c, 1, null, scratch);
        assertSame(buffer, scratch.bits);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) { assertArrayEquals(expected.get(i), actual.get(i), 0); }
    }

    @Test public void oversizedElongatedAndInvalidProjectionsUseFallbackWithoutAllocating()
    {
        for (float[] dimensions : new float[][]{{5000, 5000}, {10_000_000, 1},
            {Float.MAX_VALUE, Float.MAX_VALUE}, {Float.POSITIVE_INFINITY, 100}, {Float.NaN, 100}})
        {
            Silhouette.Scratch scratch = new Silhouette.Scratch();
            assertTrue(Silhouette.trace(new float[]{0, dimensions[0], 0}, new float[]{0, 0, dimensions[1]},
                new int[]{0}, new int[]{1}, new int[]{2}, 1, null, scratch).isEmpty());
            assertEquals(0, scratch.bits.length);
        }
    }

    @Test public void cellBudgetIncludesPadding()
    {
        Silhouette.Scratch scratch = new Silhouette.Scratch();
        assertFalse(Silhouette.trace(new float[]{0, 512, 0}, new float[]{0, 0, 512},
            new int[]{0}, new int[]{1}, new int[]{2}, 1, null, scratch).isEmpty());
        assertTrue(scratch.bits.length <= Silhouette.MAX_CELLS);
    }

    @Test public void repeatedOverlappingFacesHaveABoundedRasterBudget()
    {
        int[] a = new int[100], b = new int[100], c = new int[100];
        java.util.Arrays.fill(b, 1);
        java.util.Arrays.fill(c, 2);
        Silhouette.Scratch scratch = new Silhouette.Scratch();
        assertTrue(Silhouette.trace(new float[]{0, 500, 0}, new float[]{0, 0, 500},
            a, b, c, 100, null, scratch).isEmpty());
        assertEquals(0, scratch.bits.length);
    }

    private static double area(float[] p)
    {
        double sum = 0;
        int n = p.length / 2;
        for (int i = 0; i < n; i++) { int j = (i + 1) % n; sum += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]; }
        return sum / 2;
    }
}
