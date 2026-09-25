package com.hdtilemarkers;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModelShapesTest
{
    private static final ModelShapes.Camera CAMERA =
        new ModelShapes.Camera(6000, 6000, -900, 0.6f, 1.1f, 600, 4, 4, 1020, 700);

    @Test public void unprojectInvertsProjection()
    {
        float[] local = new float[3], p = new float[3];
        for (float[] screen : new float[][]{{514, 354, 900}, {40, 600, 2500}, {1000, 20, 300.5f}})
        {
            CAMERA.unproject(screen[0], screen[1], screen[2], local);
            CAMERA.project(local[0], local[1], local[2], p);
            assertArrayEquals(screen, p, 0.01f);
        }
    }

    @Test public void matchesClientProjectionFormula()
    {
        // Perspective.localToCanvasGpu, written out for a single point.
        float x = 6400 - 6000, y = 6300 - 6000, z = -200 + 900; // any point; the formula is compared, not its sign
        float ps = (float) Math.sin(0.6), pc = (float) Math.cos(0.6), ys = (float) Math.sin(1.1), yc = (float) Math.cos(1.1);
        float x1 = x * yc + y * ys, y1 = y * yc - x * ys, y2 = z * pc - y1 * ps, depth = y1 * pc + z * ps;
        float[] p = new float[3];
        CAMERA.project(6400, 6300, -200, p);
        assertEquals(4 + 510 + x1 * 600 / depth, p[0], 0.01f);
        assertEquals(4 + 350 + y2 * 600 / depth, p[1], 0.01f);
    }

    @Test public void modelBehindCameraIsRejected()
    {
        // Mirror a point in front of the camera through the camera position.
        float[] ahead = new float[3], out = new float[1];
        CAMERA.unproject(514, 354, 1000, ahead);
        int x = Math.round(2 * 6000 - ahead[0]), y = Math.round(2 * 6000 - ahead[1]), h = Math.round(2 * -900 - ahead[2]);
        float[] zero = {0};
        assertTrue(Float.isNaN(ModelShapes.projectModel(CAMERA, zero, zero, zero, 1, x, y, h, 0, out, out)));
        assertFalse(Float.isNaN(ModelShapes.projectModel(CAMERA, zero, zero, zero, 1,
            Math.round(ahead[0]), Math.round(ahead[1]), Math.round(ahead[2]), 0, out, out)));
    }

    @Test public void hullDropsInteriorPointsAndHasPositiveArea()
    {
        float[] xs = {0, 10, 10, 0, 5, 3, 7}, ys = {0, 0, 10, 10, 5, 2, 8};
        float[] hull = ModelShapes.convexHull(xs, ys, xs.length);
        assertEquals(8, hull.length);
        assertEquals(100, area(hull, 0, 4), 1e-4);
        assertEquals(0, ModelShapes.convexHull(new float[]{0, 1, 2}, new float[]{0, 1, 2}, 3).length);
    }

    @Test public void hullOfManyPointsIsConvexAndContainsThem()
    {
        // Many points in narrow columns, like a projected model: this zigzagged before.
        java.util.Random random = new java.util.Random(1);
        int n = 400;
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) { xs[i] = 300 + random.nextFloat() * 60; ys[i] = 250 + random.nextFloat() * 60; }
        float[] hull = ModelShapes.convexHull(xs, ys, n);
        int h = hull.length / 2;
        assertTrue(h >= 3 && h < 40);
        double turning = 0;
        for (int i = 0; i < h; i++)
        {
            int a = i, b = (i + 1) % h, c = (i + 2) % h;
            double cross = (hull[2 * b] - hull[2 * a]) * (hull[2 * c + 1] - hull[2 * a + 1]) - (hull[2 * b + 1] - hull[2 * a + 1]) * (hull[2 * c] - hull[2 * a]);
            assertTrue(cross > -0.5);
            double in = Math.atan2(hull[2 * b + 1] - hull[2 * a + 1], hull[2 * b] - hull[2 * a]);
            double out = Math.atan2(hull[2 * c + 1] - hull[2 * b + 1], hull[2 * c] - hull[2 * b]);
            double turn = out - in;
            while (turn <= -Math.PI) { turn += 2 * Math.PI; }
            while (turn > Math.PI) { turn -= 2 * Math.PI; }
            turning += turn;
        }
        // A simple convex polygon turns exactly once.
        assertEquals(2 * Math.PI, turning, 1e-3);
        for (int i = 0; i < n; i++)
        {
            for (int e = 0; e < h; e++)
            {
                int f = (e + 1) % h;
                double side = (hull[2 * f] - hull[2 * e]) * (ys[i] - hull[2 * e + 1]) - (hull[2 * f + 1] - hull[2 * e + 1]) * (xs[i] - hull[2 * e]);
                assertTrue("point outside hull", side > -1);
            }
        }
    }

    @Test public void hullIsBoundedForLargeModels()
    {
        int n = 2000;
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) { xs[i] = (float) Math.cos(i * 2 * Math.PI / n) * 500; ys[i] = (float) Math.sin(i * 2 * Math.PI / n) * 500; }
        float[] hull = ModelShapes.convexHull(xs, ys, n);
        assertTrue(hull.length / 2 <= ModelShapes.MAX_HULL && hull.length / 2 >= 3);
        assertTrue(area(hull, 0, hull.length / 2) > 0);
    }

    @Test public void outlineIsCenteredOnEdgesAndFrontFacingForEitherWinding()
    {
        for (boolean reversed : new boolean[]{false, true})
        {
            float[] x = {0, 10, 10, 0}, y = {0, 0, 10, 10}, d = {500, 500, 500, 500};
            if (reversed) { x = new float[]{0, 0, 10, 10}; y = new float[]{0, 10, 10, 0}; }
            ScreenOutline o = new ScreenOutline();
            assertTrue(o.build(x, y, d, 4, 5, 5, 500, 2));
            assertEquals(9, o.vertices);
            assertEquals(12, o.faces);
            assertEquals(8, o.borderFaces);
            float[] outer = new float[8], inner = new float[8];
            for (int i = 0; i < 4; i++)
            {
                outer[i * 2] = o.x[i]; outer[i * 2 + 1] = o.y[i];
                inner[i * 2] = o.x[4 + i]; inner[i * 2 + 1] = o.y[4 + i];
            }
            assertEquals(144, Math.abs(area(outer, 0, 4)), 1e-3);
            assertEquals(64, Math.abs(area(inner, 0, 4)), 1e-3);
            double covered = 0;
            for (int f = 0; f < o.faces; f++)
            {
                int a = o.a[f], b = o.b[f], c = o.c[f];
                double cross = (o.x[a] - o.x[b]) * (o.y[c] - o.y[b]) - (o.y[a] - o.y[b]) * (o.x[c] - o.x[b]);
                assertTrue(cross >= 0);
                covered += cross / 2;
            }
            // Border ring plus center fan cover the outer square exactly once.
            assertEquals(144, covered, 1e-3);
            assertEquals(-1, o.minX, 1e-4);
            assertEquals(11, o.maxY, 1e-4);
        }
    }

    @Test public void stripIsAnOpenLineOfTheGivenWidth()
    {
        ScreenOutline o = new ScreenOutline();
        // An L: (0,10) -> (0,0) -> (10,0), width 2.
        assertTrue(o.buildStrip(new float[]{0, 0, 10}, new float[]{10, 0, 0}, new float[]{5, 5, 5}, 3, 2));
        assertEquals(6, o.vertices);
        assertEquals(4, o.faces);
        assertEquals(o.faces, o.borderFaces);
        double area = 0;
        for (int f = 0; f < o.faces; f++)
        {
            int a = o.a[f], b = o.b[f], c = o.c[f];
            double cross = (o.x[a] - o.x[b]) * (o.y[c] - o.y[b]) - (o.y[a] - o.y[b]) * (o.x[c] - o.x[b]);
            assertTrue(cross >= 0);
            area += cross / 2;
        }
        // Two 10 x 2 bands meeting at a mitred corner: 10*2 + 10*2 exactly.
        assertEquals(40, area, 1e-3);
        assertEquals(5, o.depth[4], 0);
    }

    @Test public void outlineKeepsPerVertexDepth()
    {
        ScreenOutline o = new ScreenOutline();
        o.build(new float[]{0, 10, 10, 0}, new float[]{0, 0, 10, 10}, new float[]{100, 200, 300, 400}, 4, 5, 5, 250, 1);
        assertEquals(200, o.depth[1], 0);
        assertEquals(200, o.depth[5], 0);
        assertEquals(250, o.depth[8], 0);
    }

    private static double area(float[] p, int from, int count)
    {
        double sum = 0;
        for (int i = 0; i < count; i++)
        {
            int j = (i + 1) % count;
            sum += p[(from + i) * 2] * p[(from + j) * 2 + 1] - p[(from + j) * 2] * p[(from + i) * 2 + 1];
        }
        return sum / 2;
    }

    /**
     * A model partly behind the camera: with a partial near distance, those vertices are left out (NaN) and the rest
     * projected, instead of the whole model failing; their convex hull leaves the missing ones out.
     */
    @Test public void partlyBehindTheCameraKeepsTheOtherVertices()
    {
        // Looking along +y from (6400, 5000): the first vertex lies behind the camera.
        ModelShapes.Camera camera = new ModelShapes.Camera(6400, 5000, -600, 0.3f, 0, 600, 0, 0, 1000, 700);
        float[] vx = {0, -200, 200, 0}, vy = {0, 0, 0, -300}, vz = {0, 0, 0, 0};
        float[] ox = new float[4], oy = new float[4];
        assertTrue(Float.isNaN(ModelShapes.projectModel(camera, vx, vy, vz, 4, 6400, 4000, 0, 0, ox, oy)));
        float depth = ModelShapes.projectModel(camera, new float[]{0, -200, 200, 0}, vy, new float[]{0, 2000, 2000, 2000}, 4,
            6400, 4000, 0, 0, ox, oy, SceneShapeRenderer.PARTIAL_NEAR);
        assertFalse(Float.isNaN(depth));
        assertTrue(Float.isNaN(ox[0]));
        assertFalse(Float.isNaN(ox[1]) || Float.isNaN(ox[2]) || Float.isNaN(ox[3]));
        float[] hull = SceneShapeRenderer.hullOf(ox, oy, 4);
        assertNotNull(hull);
        assertEquals(3, hull.length / 2);
    }
}
