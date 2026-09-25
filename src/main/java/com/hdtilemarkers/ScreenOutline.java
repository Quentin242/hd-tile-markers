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

    /** Scratch for cutOut: the piece being split and its two halves, as x, y, depth per point. */
    private float[] piece = new float[3 * 64], inside = new float[3 * 64], outside = new float[3 * 64], part = new float[3 * 64];

    /**
     * Takes a convex canvas polygon (hx, hy, n points, either winding) out of the shape: every face is split along
     * its edges, the parts outside are kept (border faces first, as before) and the part inside is dropped. New points
     * get their depth interpolated in 1 / depth, as on a plane seen in perspective.
     */
    void cutOut(float[] hx, float[] hy, int n)
    {
        cut(hx, hy, n, null);
    }

    /**
     * Leaves the player uncovered: of every face, what lies outside the cut's box is kept, and inside it only what
     * lies in its pieces (the box minus the player's silhouette).
     */
    void cutOut(PlayerCut cut)
    {
        cut(new float[]{cut.minX, cut.maxX, cut.maxX, cut.minX}, new float[]{cut.minY, cut.minY, cut.maxY, cut.maxY}, 4, cut);
    }

    private void cut(float[] hx, float[] hy, int n, PlayerCut keep)
    {
        if (n < 3) { return; }
        float hMinX = Float.MAX_VALUE, hMinY = Float.MAX_VALUE, hMaxX = -Float.MAX_VALUE, hMaxY = -Float.MAX_VALUE;
        double area = 0;
        for (int i = 0; i < n; i++)
        {
            int j = (i + 1) % n;
            area += hx[i] * hy[j] - hx[j] * hy[i];
            hMinX = Math.min(hMinX, hx[i]); hMaxX = Math.max(hMaxX, hx[i]);
            hMinY = Math.min(hMinY, hy[i]); hMaxY = Math.max(hMaxY, hy[i]);
        }
        if (hMaxX < minX || hMinX > maxX || hMaxY < minY || hMinY > maxY || area == 0) { return; }
        float sign = area > 0 ? 1 : -1;
        int[] oa = java.util.Arrays.copyOf(a, faces), ob = java.util.Arrays.copyOf(b, faces), oc = java.util.Arrays.copyOf(c, faces);
        int oldFaces = faces, oldBorder = borderFaces;
        faces = 0;
        for (int f = 0; f < oldFaces; f++)
        {
            if (f == oldBorder) { borderFaces = faces; }
            int v1 = oa[f], v2 = ob[f], v3 = oc[f];
            float fMinX = Math.min(x[v1], Math.min(x[v2], x[v3])), fMaxX = Math.max(x[v1], Math.max(x[v2], x[v3]));
            float fMinY = Math.min(y[v1], Math.min(y[v2], y[v3])), fMaxY = Math.max(y[v1], Math.max(y[v2], y[v3]));
            if (fMaxX <= hMinX || fMinX >= hMaxX || fMaxY <= hMinY || fMinY >= hMaxY) { keep(v1, v2, v3); continue; }
            int count = 3;
            ensureScratch(count + n + 2);
            put(piece, 0, v1); put(piece, 1, v2); put(piece, 2, v3);
            for (int e = 0; e < n && count >= 3; e++)
            {
                // The part outside this edge is outside the polygon: kept. The rest goes on to the next edge.
                int out = split(piece, count, hx[e], hy[e], hx[(e + 1) % n], hy[(e + 1) % n], sign);
                if (out >= 3) { emit(outside, out); }
                float[] swap = piece; piece = inside; inside = swap;
                count = splitInside;
            }
            if (keep == null || count < 3) { continue; }
            // Inside the box: only the parts in the pieces around the silhouette.
            float pMinX = Float.MAX_VALUE, pMaxX = -Float.MAX_VALUE, pMinY = Float.MAX_VALUE, pMaxY = -Float.MAX_VALUE;
            for (int k = 0; k < count; k++)
            {
                pMinX = Math.min(pMinX, piece[k * 3]); pMaxX = Math.max(pMaxX, piece[k * 3]);
                pMinY = Math.min(pMinY, piece[k * 3 + 1]); pMaxY = Math.max(pMaxY, piece[k * 3 + 1]);
            }
            for (float[] q : keep.pieces)
            {
                if (q[1] >= pMaxY || q[5] <= pMinY || q[8] >= pMaxX || q[9] <= pMinX) { continue; }
                System.arraycopy(piece, 0, part, 0, count * 3);
                int m = count;
                for (int e = 0; e < 4 && m >= 3; e++)
                {
                    int ex = e * 2, nx = ((e + 1) % 4) * 2;
                    // The trapezoid runs clockwise on the canvas (y down): positive area in these coordinates.
                    split(part, m, q[ex], q[ex + 1], q[nx], q[nx + 1], 1);
                    float[] swap = part; part = inside; inside = swap;
                    m = splitInside;
                }
                if (m >= 3) { emit(part, m); }
            }
        }
        if (oldBorder >= oldFaces) { borderFaces = faces; }
    }

    /** Points of the last split's inside part (in inside); the outside part is in outside, its count returned. */
    private int splitInside;

    /** Splits the polygon src (count points) by the line from (ex, ey) to (fx, fy): inside is left of it for sign 1. */
    private int split(float[] src, int count, float ex, float ey, float fx, float fy, float sign)
    {
        float dx = fx - ex, dy = fy - ey;
        int in = 0, out = 0;
        for (int k = 0; k < count; k++)
        {
            int l = (k + 1) % count;
            float sk = sign * (dx * (src[k * 3 + 1] - ey) - dy * (src[k * 3] - ex));
            float sl = sign * (dx * (src[l * 3 + 1] - ey) - dy * (src[l * 3] - ex));
            if (sk >= 0) { copy(src, k, inside, in++); } else { copy(src, k, outside, out++); }
            if ((sk >= 0) != (sl >= 0))
            {
                float t = sk / (sk - sl);
                float d0 = src[k * 3 + 2], d1 = src[l * 3 + 2];
                float px = src[k * 3] + (src[l * 3] - src[k * 3]) * t;
                float py = src[k * 3 + 1] + (src[l * 3 + 1] - src[k * 3 + 1]) * t;
                float pd = 1f / (1f / d0 + (1f / d1 - 1f / d0) * t);
                set(inside, in++, px, py, pd);
                set(outside, out++, px, py, pd);
            }
        }
        splitInside = in;
        return out;
    }

    /** Room for polygons of up to points points in every scratch array. */
    private void ensureScratch(int points)
    {
        int room = (points + 8) * 3;
        if (piece.length < room) { piece = java.util.Arrays.copyOf(piece, room * 2); }
        if (inside.length < room) { inside = new float[room * 2]; }
        if (outside.length < room) { outside = new float[room * 2]; }
        if (part.length < room) { part = new float[room * 2]; }
    }

    private void put(float[] to, int k, int v)
    {
        to[k * 3] = x[v]; to[k * 3 + 1] = y[v]; to[k * 3 + 2] = depth[v];
    }

    private static void copy(float[] from, int k, float[] to, int l)
    {
        to[l * 3] = from[k * 3]; to[l * 3 + 1] = from[k * 3 + 1]; to[l * 3 + 2] = from[k * 3 + 2];
    }

    private static void set(float[] to, int l, float px, float py, float pd)
    {
        to[l * 3] = px; to[l * 3 + 1] = py; to[l * 3 + 2] = pd;
    }

    private void keep(int v1, int v2, int v3)
    {
        growFaces(faces + 1);
        a[faces] = v1; b[faces] = v2; c[faces] = v3;
        faces++;
    }

    /** A convex piece as a fan of new vertices. */
    private void emit(float[] p, int count)
    {
        if (x.length < vertices + count)
        {
            int size = (vertices + count) * 2;
            x = java.util.Arrays.copyOf(x, size); y = java.util.Arrays.copyOf(y, size); depth = java.util.Arrays.copyOf(depth, size);
        }
        int first = vertices;
        for (int k = 0; k < count; k++) { x[vertices] = p[k * 3]; y[vertices] = p[k * 3 + 1]; depth[vertices] = p[k * 3 + 2]; vertices++; }
        growFaces(faces + count - 2);
        for (int k = 1; k < count - 1; k++) { faces = face(faces, first, first + k, first + k + 1); }
    }

    private void growFaces(int n)
    {
        if (a.length < n)
        {
            int size = n * 2;
            a = java.util.Arrays.copyOf(a, size); b = java.util.Arrays.copyOf(b, size); c = java.util.Arrays.copyOf(c, size);
        }
    }

    /** Becomes a copy of another outline (its points, faces and bounds), reusing this one's arrays. */
    void copyFrom(ScreenOutline o)
    {
        if (x.length < o.vertices) { x = new float[o.x.length]; y = new float[o.x.length]; depth = new float[o.x.length]; }
        if (a.length < o.faces) { a = new int[o.a.length]; b = new int[o.a.length]; c = new int[o.a.length]; }
        System.arraycopy(o.x, 0, x, 0, o.vertices); System.arraycopy(o.y, 0, y, 0, o.vertices);
        System.arraycopy(o.depth, 0, depth, 0, o.vertices);
        System.arraycopy(o.a, 0, a, 0, o.faces); System.arraycopy(o.b, 0, b, 0, o.faces); System.arraycopy(o.c, 0, c, 0, o.faces);
        vertices = o.vertices; faces = o.faces; borderFaces = o.borderFaces;
        minX = o.minX; minY = o.minY; maxX = o.maxX; maxY = o.maxY;
    }
}
