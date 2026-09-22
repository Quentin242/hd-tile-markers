/*
 * Camera.project uses the same math as RuneLite's Perspective.localToCanvasGpu and modelToCanvas
 * (https://github.com/runelite/runelite), BSD 2-Clause License, copyright the RuneLite contributors;
 * see META-INF/LICENSE-runelite and THIRD_PARTY_NOTICES.md. Unproject and the convex hull are HD Tile Markers' own.
 */
package com.hdtilemarkers;

/**
 * Camera projection matching the client, and convex hulls in canvas space.
 */
final class ModelShapes
{
    static final float NEAR = 50f;
    static final int MAX_HULL = 256;

    private ModelShapes() { }

    /** Camera state in the same units and conventions as Perspective's GPU projection. */
    static final class Camera
    {
        final float x, y, z, pitchSin, pitchCos, yawSin, yawCos, scale, centerX, centerY;

        Camera(float x, float y, float z, float pitch, float yaw, int scale,
            int viewportX, int viewportY, int viewportWidth, int viewportHeight)
        {
            this.x = x; this.y = y; this.z = z;
            pitchSin = (float) Math.sin(pitch); pitchCos = (float) Math.cos(pitch);
            yawSin = (float) Math.sin(yaw); yawCos = (float) Math.cos(yaw);
            this.scale = scale;
            centerX = viewportX + viewportWidth / 2f;
            centerY = viewportY + viewportHeight / 2f;
        }

        /** Writes canvas x, canvas y and depth into out. Local x/y are horizontal, z is height (down positive). */
        void project(float lx, float ly, float lz, float[] out)
        {
            float dx = lx - x, dy = ly - y, dz = lz - z;
            float x1 = dx * yawCos + dy * yawSin;
            float y1 = dy * yawCos - dx * yawSin;
            float y2 = dz * pitchCos - y1 * pitchSin;
            float depth = y1 * pitchCos + dz * pitchSin;
            out[0] = centerX + x1 * scale / depth;
            out[1] = centerY + y2 * scale / depth;
            out[2] = depth;
        }

        /** Inverse of project for a canvas point at the given depth. Writes local x, y and z into out. */
        void unproject(float sx, float sy, float depth, float[] out)
        {
            float x1 = (sx - centerX) * depth / scale;
            float y2 = (sy - centerY) * depth / scale;
            float y1 = depth * pitchCos - y2 * pitchSin;
            float dz = y2 * pitchCos + depth * pitchSin;
            out[0] = x + x1 * yawCos - y1 * yawSin;
            out[1] = y + x1 * yawSin + y1 * yawCos;
            out[2] = z + dz;
        }
    }

    /**
     * Convex hull of the first n points (Andrew's monotone chain). Returns hull
     * points as {x0, y0, x1, y1, ...} with a positive signed area, at most
     * MAX_HULL points, or an empty array for a degenerate shape.
     */
    static float[] convexHull(float[] xs, float[] ys, int n)
    {
        // Sort by x then y without boxing: coordinates quantized to 1/16 pixel
        // and the vertex index packed into one long. The turn tests below use
        // the same quantized values: with exact floats, points in one 1/16 px
        // column would be out of order and the chain would zigzag.
        long[] keys = new long[n];
        int[] qx = new int[n], qy = new int[n];
        for (int i = 0; i < n; i++)
        {
            qx[i] = Math.max(0, Math.min((1 << 21) - 1, Math.round(xs[i] * 16) + (1 << 20)));
            qy[i] = Math.max(0, Math.min((1 << 21) - 1, Math.round(ys[i] * 16) + (1 << 20)));
            keys[i] = (long) qx[i] << 42 | (long) qy[i] << 21 | i;
        }
        java.util.Arrays.sort(keys);
        int[] order = new int[n];
        for (int i = 0; i < n; i++) { order[i] = (int) (keys[i] & ((1 << 21) - 1)); }
        int[] hull = new int[2 * n + 1];
        int k = 0;
        for (int i = 0; i < n; i++)
        {
            int p = order[i];
            while (k >= 2 && cross(qx, qy, hull[k - 2], hull[k - 1], p) <= 0) { k--; }
            hull[k++] = p;
        }
        for (int i = n - 2, lower = k + 1; i >= 0; i--)
        {
            int p = order[i];
            while (k >= lower && cross(qx, qy, hull[k - 2], hull[k - 1], p) <= 0) { k--; }
            hull[k++] = p;
        }
        int count = k - 1;
        if (count < 3) { return new float[0]; }
        // Keep the shape bounded for the carrier model. Dropping points from a
        // convex polygon keeps it convex.
        int step = (count + MAX_HULL - 1) / MAX_HULL;
        int kept = (count + step - 1) / step;
        float[] result = new float[kept * 2];
        for (int i = 0; i < kept; i++)
        {
            result[i * 2] = xs[hull[i * step]];
            result[i * 2 + 1] = ys[hull[i * step]];
        }
        return result;
    }

    private static long cross(int[] xs, int[] ys, int o, int a, int b)
    {
        return (long) (xs[a] - xs[o]) * (ys[b] - ys[o]) - (long) (ys[a] - ys[o]) * (xs[b] - xs[o]);
    }

    /**
     * Projects model vertices placed at local (x, y), base height and
     * orientation, like Perspective.modelToCanvas. Returns the nearest depth,
     * or NaN if any vertex is behind the near plane.
     */
    static float projectModel(Camera camera, float[] vx, float[] vy, float[] vz, int n,
        int localX, int localY, int height, int orientation, float[] outX, float[] outY)
    {
        double angle = (orientation & 2047) * Math.PI / 1024;
        float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
        float[] p = new float[3];
        float nearest = Float.MAX_VALUE;
        for (int i = 0; i < n; i++)
        {
            float rx = vz[i] * sin + vx[i] * cos;
            float rz = vz[i] * cos - vx[i] * sin;
            camera.project(localX + rx, localY + rz, height + vy[i], p);
            if (!(p[2] >= NEAR)) { return Float.NaN; }
            outX[i] = p[0]; outY[i] = p[1];
            nearest = Math.min(nearest, p[2]);
        }
        return nearest;
    }
}
