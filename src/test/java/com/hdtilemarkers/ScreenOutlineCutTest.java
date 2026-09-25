package com.hdtilemarkers;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenOutlineCutTest
{
    private static double area(ScreenOutline o, int from, int to)
    {
        double sum = 0;
        for (int f = from; f < to; f++)
        {
            int a = o.a[f], b = o.b[f], c = o.c[f];
            sum += Math.abs((o.x[b] - o.x[a]) * (o.y[c] - o.y[a]) - (o.y[b] - o.y[a]) * (o.x[c] - o.x[a])) / 2;
        }
        return sum;
    }

    @Test public void theCutRegionIsLeftOutAndTheRestKept()
    {
        ScreenOutline o = new ScreenOutline();
        float[] x = {0, 100, 100, 0}, y = {0, 0, 100, 100}, d = {1000, 1000, 1000, 1000};
        assertTrue(o.build(x, y, d, 4, 50, 50, 1000, 2));
        double fill = area(o, o.borderFaces, o.faces), border = area(o, 0, o.borderFaces);
        // A player in the middle, and one over the right edge.
        o.cutOut(new float[]{40, 60, 60, 40}, new float[]{40, 40, 60, 60}, 4);
        assertEquals(fill - 400, area(o, o.borderFaces, o.faces), 1);
        assertEquals(border, area(o, 0, o.borderFaces), 1e-3);
        o.cutOut(new float[]{90, 110, 110, 90}, new float[]{60, 60, 30, 30}, 4);
        assertTrue(area(o, 0, o.borderFaces) < border - 20);
        for (int f = 0; f < o.faces; f++)
        {
            float cx = (o.x[o.a[f]] + o.x[o.b[f]] + o.x[o.c[f]]) / 3, cy = (o.y[o.a[f]] + o.y[o.b[f]] + o.y[o.c[f]]) / 3;
            assertFalse(cx > 40.01 && cx < 59.99 && cy > 40.01 && cy < 59.99);
            // Faces stay front-facing for the client: (a - b) x (c - b) >= 0.
            float cross = (o.x[o.a[f]] - o.x[o.b[f]]) * (o.y[o.c[f]] - o.y[o.b[f]]) - (o.y[o.a[f]] - o.y[o.b[f]]) * (o.x[o.c[f]] - o.x[o.b[f]]);
            assertTrue(cross >= -1e-3);
        }
    }

    @Test public void aCutAwayFromTheShapeChangesNothing()
    {
        ScreenOutline o = new ScreenOutline();
        float[] x = {0, 100, 100, 0}, y = {0, 0, 100, 100}, d = {1000, 1000, 1000, 1000};
        assertTrue(o.build(x, y, d, 4, 50, 50, 1000, 2));
        int faces = o.faces, vertices = o.vertices;
        o.cutOut(new float[]{400, 460, 460}, new float[]{400, 400, 460}, 3);
        assertEquals(faces, o.faces);
        assertEquals(vertices, o.vertices);
    }

    private static double quadArea(float[] q)
    {
        double sum = 0;
        for (int i = 0; i < 4; i++) { int j = (i + 1) % 4; sum += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]; }
        return sum / 2;
    }

    @Test public void playerCutPiecesCoverTheBoxOutsideTheSilhouette()
    {
        // An L-shaped silhouette with a hole, as a player's legs and arm leave gaps.
        float[] outer = {10, 10, 50, 10, 50, 20, 20, 20, 20, 60, 10, 60};
        float[] hole = {12, 30, 12, 40, 18, 40, 18, 30};
        PlayerCut cut = PlayerCut.of(java.util.Arrays.asList(outer, hole));
        double box = (cut.maxX - cut.minX) * (cut.maxY - cut.minY), silhouette = 40 * 10 + 10 * 40 - 6 * 10;
        double pieces = 0;
        for (float[] q : cut.pieces) { assertTrue(quadArea(q) >= -1e-3); pieces += quadArea(q); }
        assertEquals(box - silhouette, pieces, 0.5);
    }

    @Test public void aMarkKeepsOnlyWhatLiesOutsideThePlayer()
    {
        ScreenOutline o = new ScreenOutline();
        float[] x = {0, 100, 100, 0}, y = {0, 0, 100, 100}, d = {1000, 1000, 1000, 1000};
        assertTrue(o.build(x, y, d, 4, 50, 50, 1000, 2));
        double fill = area(o, o.borderFaces, o.faces);
        // A player standing on the middle: a 10 x 40 silhouette.
        o.cutOut(PlayerCut.of(java.util.Collections.singletonList(new float[]{45, 30, 55, 30, 55, 70, 45, 70})));
        assertEquals(fill - 400, area(o, o.borderFaces, o.faces), 1);
        for (int f = 0; f < o.faces; f++)
        {
            float cx = (o.x[o.a[f]] + o.x[o.b[f]] + o.x[o.c[f]]) / 3, cy = (o.y[o.a[f]] + o.y[o.b[f]] + o.y[o.c[f]]) / 3;
            assertFalse(cx > 45.01 && cx < 54.99 && cy > 30.01 && cy < 69.99);
        }
    }
}
