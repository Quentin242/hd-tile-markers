package com.hdtilemarkers;

/**
 * Border and fill triangles for a closed canvas polygon, with a depth per
 * vertex. Arrays are reused between builds to avoid per-frame garbage.
 *
 * Vertices: outer ring 0..n-1, inner ring n..2n-1, center 2n. Faces
 * [0, borderFaces) are the border; the rest fan the inner ring to the center.
 * The polygon must be star-shaped around the center, which holds for convex
 * hulls and for projected tile footprints.
 */
final class ScreenOutline
{
    float[] x = new float[0], y = new float[0], depth = new float[0];
    int[] a = new int[0], b = new int[0], c = new int[0];
    int vertices, faces, borderFaces;
    float minX, minY, maxX, maxY;

    /**
     * Returns false for fewer than three points. The input may wind either
     * way; the border is centered on the edges with the given canvas width.
     */
    boolean build(float[] px, float[] py, float[] pd, int n, float cx, float cy, float cd, float width)
    {
        if (n < 3 || !(width > 0)) { return false; }
        vertices = 2 * n + 1;
        faces = 3 * n;
        borderFaces = 2 * n;
        if (x.length < vertices) { x = new float[vertices * 2]; y = new float[vertices * 2]; depth = new float[vertices * 2]; }
        if (a.length < faces) { a = new int[faces * 2]; b = new int[faces * 2]; c = new int[faces * 2]; }
        double area = 0;
        for (int i = 0; i < n; i++)
        {
            int j = (i + 1) % n;
            area += px[i] * py[j] - px[j] * py[i];
        }
        // With a positive signed area the outward normal of edge (dx, dy) is (dy, -dx).
        float sign = area >= 0 ? 1 : -1, half = width / 2f;
        minX = minY = Float.MAX_VALUE;
        maxX = maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++)
        {
            int prev = (i + n - 1) % n, next = (i + 1) % n;
            float n0x = sign * (py[i] - py[prev]), n0y = -sign * (px[i] - px[prev]);
            float n1x = sign * (py[next] - py[i]), n1y = -sign * (px[next] - px[i]);
            float l0 = (float) Math.hypot(n0x, n0y), l1 = (float) Math.hypot(n1x, n1y);
            if (l0 > 0) { n0x /= l0; n0y /= l0; }
            if (l1 > 0) { n1x /= l1; n1y /= l1; }
            float mx = n0x + n1x, my = n0y + n1y, ml = (float) Math.hypot(mx, my), offset = 0;
            if (ml > 1e-4f)
            {
                mx /= ml; my /= ml;
                // Miter, limited so sharp corners do not spike.
                offset = half / Math.max(0.25f, mx * n1x + my * n1y);
            }
            x[i] = px[i] + mx * offset; y[i] = py[i] + my * offset;
            x[n + i] = px[i] - mx * offset; y[n + i] = py[i] - my * offset;
            depth[i] = depth[n + i] = pd[i];
            minX = Math.min(minX, x[i]); maxX = Math.max(maxX, x[i]);
            minY = Math.min(minY, y[i]); maxY = Math.max(maxY, y[i]);
        }
        x[2 * n] = cx; y[2 * n] = cy; depth[2 * n] = cd;
        int f = 0;
        for (int i = 0; i < n; i++)
        {
            int j = (i + 1) % n;
            f = face(f, i, j, n + j);
            f = face(f, i, n + j, n + i);
        }
        for (int i = 0; i < n; i++) { f = face(f, n + i, n + (i + 1) % n, 2 * n); }
        return true;
    }

    /**
     * Border and fill for any simple polygon, such as RuneLite's rectilinear
     * clickboxes. The border ring is centred on the edges like build(); the fill
     * is an ear-clipping triangulation of the inner ring, so it needs no centre.
     * Vertices: outer ring 0..n-1, inner ring n..2n-1.
     */
    boolean buildPolygon(float[] px, float[] py, float[] pd, int n, float width)
    {
        return buildPolygon(px, py, pd, n, width, false);
    }

    /** As buildPolygon; outside puts the whole border outside the polygon, as RuneLite's outlines. */
    boolean buildPolygon(float[] px, float[] py, float[] pd, int n, float width, boolean outside)
    {
        if (n < 3 || !(width > 0)) { return false; }
        vertices = 2 * n;
        int maxFaces = 2 * n + (n - 2);
        if (x.length < vertices) { x = new float[vertices * 2]; y = new float[vertices * 2]; depth = new float[vertices * 2]; }
        if (a.length < maxFaces) { a = new int[maxFaces * 2]; b = new int[maxFaces * 2]; c = new int[maxFaces * 2]; }
        double area = 0;
        for (int i = 0; i < n; i++)
        {
            int j = (i + 1) % n;
            area += px[i] * py[j] - px[j] * py[i];
        }
        float sign = area >= 0 ? 1 : -1, half = width / 2f;
        minX = minY = Float.MAX_VALUE;
        maxX = maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++)
        {
            int prev = (i + n - 1) % n, next = (i + 1) % n;
            float n0x = sign * (py[i] - py[prev]), n0y = -sign * (px[i] - px[prev]);
            float n1x = sign * (py[next] - py[i]), n1y = -sign * (px[next] - px[i]);
            float l0 = (float) Math.hypot(n0x, n0y), l1 = (float) Math.hypot(n1x, n1y);
            if (l0 > 0) { n0x /= l0; n0y /= l0; }
            if (l1 > 0) { n1x /= l1; n1y /= l1; }
            float mx = n0x + n1x, my = n0y + n1y, ml = (float) Math.hypot(mx, my), offset = 0;
            if (ml > 1e-4f)
            {
                mx /= ml; my /= ml;
                offset = half / Math.max(0.25f, mx * n1x + my * n1y);
            }
            float outer = outside ? 2 * offset : offset, inner = outside ? 0 : offset;
            x[i] = px[i] + mx * outer; y[i] = py[i] + my * outer;
            x[n + i] = px[i] - mx * inner; y[n + i] = py[i] - my * inner;
            depth[i] = depth[n + i] = pd[i];
            minX = Math.min(minX, x[i]); maxX = Math.max(maxX, x[i]);
            minY = Math.min(minY, y[i]); maxY = Math.max(maxY, y[i]);
        }
        int f = 0;
        for (int i = 0; i < n; i++)
        {
            int j = (i + 1) % n;
            f = face(f, i, j, n + j);
            f = face(f, i, n + j, n + i);
        }
        borderFaces = f;
        f = earClip(n, sign, f);
        faces = f;
        return true;
    }

    /** Ear clipping of the inner ring (vertices n..2n-1), which winds with the given sign. */
    private int earClip(int n, float sign, int f)
    {
        int[] ring = new int[n];
        for (int i = 0; i < n; i++) { ring[i] = n + i; }
        int count = n, guard = 0;
        while (count > 3 && guard++ < n * n)
        {
            boolean clipped = false;
            for (int i = 0; i < count; i++)
            {
                int p = ring[(i + count - 1) % count], q = ring[i], r = ring[(i + 1) % count];
                float turn = (x[q] - x[p]) * (y[r] - y[p]) - (y[q] - y[p]) * (x[r] - x[p]);
                if (turn * sign <= 0) { continue; }
                boolean inside = false;
                for (int k = 0; k < count && !inside; k++)
                {
                    int v = ring[k];
                    if (v == p || v == q || v == r) { continue; }
                    inside = inTriangle(x[v], y[v], p, q, r);
                }
                if (inside) { continue; }
                f = face(f, p, q, r);
                System.arraycopy(ring, i + 1, ring, i, count - i - 1);
                count--;
                clipped = true;
                break;
            }
            // Degenerate input (collinear or self-touching): stop rather than loop.
            if (!clipped) { break; }
        }
        if (count == 3) { f = face(f, ring[0], ring[1], ring[2]); }
        return f;
    }

    private boolean inTriangle(float px, float py, int p, int q, int r)
    {
        float d1 = (px - x[q]) * (y[p] - y[q]) - (x[p] - x[q]) * (py - y[q]);
        float d2 = (px - x[r]) * (y[q] - y[r]) - (x[q] - x[r]) * (py - y[r]);
        float d3 = (px - x[p]) * (y[r] - y[p]) - (x[r] - x[p]) * (py - y[p]);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    /**
     * An open polyline of the given canvas width: border faces only, for tile
     * corners. Ends are square; inner vertices are mitred.
     */
    boolean buildStrip(float[] px, float[] py, float[] pd, int n, float width)
    {
        if (n < 2 || !(width > 0)) { return false; }
        vertices = 2 * n;
        faces = 2 * (n - 1);
        borderFaces = faces;
        if (x.length < vertices) { x = new float[vertices * 2]; y = new float[vertices * 2]; depth = new float[vertices * 2]; }
        if (a.length < faces) { a = new int[faces * 2]; b = new int[faces * 2]; c = new int[faces * 2]; }
        float half = width / 2f;
        minX = minY = Float.MAX_VALUE;
        maxX = maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++)
        {
            int prev = Math.max(0, i - 1), next = Math.min(n - 1, i + 1);
            float n0x = 0, n0y = 0, n1x = 0, n1y = 0;
            if (i > 0) { n0x = py[i] - py[prev]; n0y = -(px[i] - px[prev]); }
            if (i < n - 1) { n1x = py[next] - py[i]; n1y = -(px[next] - px[i]); }
            float l0 = (float) Math.hypot(n0x, n0y), l1 = (float) Math.hypot(n1x, n1y);
            if (l0 > 0) { n0x /= l0; n0y /= l0; }
            if (l1 > 0) { n1x /= l1; n1y /= l1; }
            float mx = n0x + n1x, my = n0y + n1y, ml = (float) Math.hypot(mx, my), offset = 0;
            if (ml > 1e-4f)
            {
                mx /= ml; my /= ml;
                float ref = l1 > 0 ? mx * n1x + my * n1y : mx * n0x + my * n0y;
                offset = half / Math.max(0.25f, ref);
            }
            x[i] = px[i] + mx * offset; y[i] = py[i] + my * offset;
            x[n + i] = px[i] - mx * offset; y[n + i] = py[i] - my * offset;
            depth[i] = depth[n + i] = pd[i];
            minX = Math.min(minX, Math.min(x[i], x[n + i])); maxX = Math.max(maxX, Math.max(x[i], x[n + i]));
            minY = Math.min(minY, Math.min(y[i], y[n + i])); maxY = Math.max(maxY, Math.max(y[i], y[n + i]));
        }
        int f = 0;
        for (int i = 0; i < n - 1; i++)
        {
            f = face(f, i, i + 1, n + i + 1);
            f = face(f, i, n + i + 1, n + i);
        }
        return true;
    }

    private int face(int f, int v1, int v2, int v3)
    {
        // The client draws a face when (a - b) x (c - b) > 0 in canvas space.
        float cross = (x[v1] - x[v2]) * (y[v3] - y[v2]) - (y[v1] - y[v2]) * (x[v3] - x[v2]);
        a[f] = v1;
        if (cross >= 0) { b[f] = v2; c[f] = v3; }
        else { b[f] = v3; c[f] = v2; }
        return f + 1;
    }
}
