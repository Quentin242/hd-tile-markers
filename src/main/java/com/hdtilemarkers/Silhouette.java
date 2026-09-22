package com.hdtilemarkers;

import java.util.*;

/**
 * The on-screen silhouette of a projected model, as closed polygons in canvas
 * coordinates. Faces are rasterized onto a grid finer than a pixel, the
 * boundary between covered and empty cells is traced into loops, and the
 * resulting staircases are simplified back into straight edges. Used for
 * outlines, which RuneLite otherwise draws pixel by pixel in 2D.
 */
final class Silhouette
{
    /** Grid cells per canvas pixel. */
    static final int CELLS_PER_PIXEL = 2;
    static final int MAX_CELLS = 1 << 20;
    private static final long MAX_RASTER_WORK = 8L * MAX_CELLS;

    private Silhouette() { }

    static final class Scratch
    {
        boolean[] grid = new boolean[0];
        final float[] intersections = new float[3];
    }

    /**
     * @param x, y projected vertices; faces a/b/c index them; hidden[f] skips a face
     */
    static List<float[]> trace(float[] x, float[] y, int[] a, int[] b, int[] c, int faces, boolean[] hidden)
    {
        return trace(x, y, a, b, c, faces, hidden, new Scratch());
    }

    static List<float[]> trace(float[] x, float[] y, int[] a, int[] b, int[] c, int faces, boolean[] hidden, Scratch scratch)
    {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int f = 0; f < faces; f++)
        {
            if (hidden != null && hidden[f]) { continue; }
            int i = a[f], j = b[f], k = c[f];
            if (!Float.isFinite(x[i]) || !Float.isFinite(y[i]) || !Float.isFinite(x[j])
                || !Float.isFinite(y[j]) || !Float.isFinite(x[k]) || !Float.isFinite(y[k]))
            { return Collections.emptyList(); }
            minX = Math.min(minX, Math.min(x[i], Math.min(x[j], x[k])));
            maxX = Math.max(maxX, Math.max(x[i], Math.max(x[j], x[k])));
            minY = Math.min(minY, Math.min(y[i], Math.min(y[j], y[k])));
            maxY = Math.max(maxY, Math.max(y[i], Math.max(y[j], y[k])));
        }
        if (minX > maxX) { return Collections.emptyList(); }
        float scale = CELLS_PER_PIXEL;
        // Coarser for very large shapes, to bound the work per frame.
        double width = (double) maxX - minX, height = (double) maxY - minY;
        while (paddedCells(width, height, scale) > MAX_CELLS && scale > 0.25f) { scale /= 2; }
        // Oversized/elongated projections use the source's 2D outline instead. Check the
        // padded dimensions before narrowing to ints or allocating any raster memory.
        if (paddedCells(width, height, scale) > MAX_CELLS) { return Collections.emptyList(); }
        // One empty cell of padding on every side, so every loop is closed.
        int w = (int) Math.ceil(width * scale) + 2, h = (int) Math.ceil(height * scale) + 2;
        float ox = minX - 1 / scale, oy = minY - 1 / scale;
        // Repeated overlapping faces must not multiply raster work without a limit.
        double work = 0;
        for (int f = 0; f < faces; f++)
        {
            if (hidden != null && hidden[f]) { continue; }
            int i = a[f], j = b[f], k = c[f];
            double fw = (double) Math.max(x[i], Math.max(x[j], x[k])) - Math.min(x[i], Math.min(x[j], x[k]));
            double fh = (double) Math.max(y[i], Math.max(y[j], y[k])) - Math.min(y[i], Math.min(y[j], y[k]));
            work += paddedCells(fw, fh, scale);
            if (work > MAX_RASTER_WORK) { return Collections.emptyList(); }
        }
        int cells = Math.multiplyExact(w, h);
        if (scratch.grid.length < cells) { scratch.grid = new boolean[cells]; }
        else { Arrays.fill(scratch.grid, 0, cells, false); }
        boolean[] grid = scratch.grid;
        for (int f = 0; f < faces; f++)
        {
            if (hidden != null && hidden[f]) { continue; }
            fill(grid, w, h, (x[a[f]] - ox) * scale, (y[a[f]] - oy) * scale, (x[b[f]] - ox) * scale, (y[b[f]] - oy) * scale,
                (x[c[f]] - ox) * scale, (y[c[f]] - oy) * scale, scratch.intersections);
        }
        List<float[]> loops = new ArrayList<>();
        for (int[] loop : boundaries(grid, w, h))
        {
            float[] simple = simplify(loop, 1.0);
            if (simple.length < 6) { continue; }
            for (int i = 0; i < simple.length; i += 2) { simple[i] = ox + simple[i] / scale; simple[i + 1] = oy + simple[i + 1] / scale; }
            loops.add(simple);
        }
        return loops;
    }

    private static double paddedCells(double width, double height, float scale)
    {
        return (Math.ceil(width * scale) + 2) * (Math.ceil(height * scale) + 2);
    }

    /** Marks cells whose centres lie inside the triangle (grid coordinates). */
    private static void fill(boolean[] grid, int w, int h, float x0, float y0, float x1, float y1, float x2, float y2, float[] xs)
    {
        int top = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2)) - 0.5f));
        int bottom = Math.min(h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2)) - 0.5f));
        for (int row = top; row <= bottom; row++)
        {
            float sy = row + 0.5f;
            int k = 0;
            k = cross(x0, y0, x1, y1, sy, xs, k);
            k = cross(x1, y1, x2, y2, sy, xs, k);
            k = cross(x2, y2, x0, y0, sy, xs, k);
            if (k < 2) { continue; }
            float left = Math.min(xs[0], xs[1]), right = Math.max(xs[0], xs[1]);
            if (k == 3) { left = Math.min(left, xs[2]); right = Math.max(right, xs[2]); }
            int from = Math.max(0, (int) Math.ceil(left - 0.5f)), to = Math.min(w - 1, (int) Math.floor(right - 0.5f));
            for (int col = from; col <= to; col++) { grid[row * w + col] = true; }
        }
    }

    private static int cross(float xa, float ya, float xb, float yb, float sy, float[] xs, int k)
    {
        if ((ya <= sy && yb > sy) || (yb <= sy && ya > sy))
        {
            xs[k++] = xa + (sy - ya) / (yb - ya) * (xb - xa);
        }
        return k;
    }

    /**
     * Loops along cell edges between covered and empty cells, as lattice corner
     * coordinates {x0, y0, x1, y1, ...}. Each directed edge keeps the covered
     * cell on the same side, so edges chain into closed loops.
     */
    private static List<int[]> boundaries(boolean[] grid, int w, int h)
    {
        // Directed edges keyed by their start corner; a corner can start two edges at a diagonal touch.
        Map<Long, List<long[]>> starts = new HashMap<>();
        int edges = 0;
        for (int row = 0; row < h; row++)
        {
            for (int col = 0; col < w; col++)
            {
                if (!grid[row * w + col]) { continue; }
                if (row == 0 || !grid[(row - 1) * w + col]) { edges += add(starts, col, row, col + 1, row); }
                if (col == w - 1 || !grid[row * w + col + 1]) { edges += add(starts, col + 1, row, col + 1, row + 1); }
                if (row == h - 1 || !grid[(row + 1) * w + col]) { edges += add(starts, col + 1, row + 1, col, row + 1); }
                if (col == 0 || !grid[row * w + col - 1]) { edges += add(starts, col, row + 1, col, row); }
            }
        }
        List<int[]> loops = new ArrayList<>();
        while (edges > 0)
        {
            Map.Entry<Long, List<long[]>> first = starts.entrySet().iterator().next();
            long[] edge = first.getValue().remove(first.getValue().size() - 1);
            if (first.getValue().isEmpty()) { starts.remove(first.getKey()); }
            edges--;
            int[] loop = new int[16];
            int size = 0;
            long start = key((int) edge[0], (int) edge[1]);
            while (true)
            {
                if (size + 2 > loop.length) { loop = Arrays.copyOf(loop, loop.length * 2); }
                loop[size++] = (int) edge[0];
                loop[size++] = (int) edge[1];
                long next = key((int) edge[2], (int) edge[3]);
                if (next == start) { break; }
                List<long[]> out = starts.get(next);
                if (out == null) { break; }
                edge = out.remove(out.size() - 1);
                if (out.isEmpty()) { starts.remove(next); }
                edges--;
            }
            if (size >= 6) { loops.add(Arrays.copyOf(loop, size)); }
        }
        return loops;
    }

    private static int add(Map<Long, List<long[]>> starts, int x0, int y0, int x1, int y1)
    {
        starts.computeIfAbsent(key(x0, y0), k -> new ArrayList<>(2)).add(new long[]{x0, y0, x1, y1});
        return 1;
    }

    private static long key(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }

    /** Douglas-Peucker on a closed loop, after dropping collinear points; tolerance in grid cells. */
    static float[] simplify(int[] loop, double tolerance)
    {
        int n = loop.length / 2;
        // Split the loop at its two farthest-apart corners and simplify both halves.
        int far = 0;
        long best = -1;
        for (int i = 1; i < n; i++)
        {
            long dx = loop[i * 2] - loop[0], dy = loop[i * 2 + 1] - loop[1], d = dx * dx + dy * dy;
            if (d > best) { best = d; far = i; }
        }
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[far] = true;
        mark(loop, 0, far, tolerance, keep);
        mark(loop, far, n, tolerance, keep);
        // The two split points are always kept; drop them too when they lie on a straight edge.
        for (int pass = 0; pass < 2; pass++)
        {
            for (int i : new int[]{0, far})
            {
                if (!keep[i]) { continue; }
                int prev = i, next = i;
                do { prev = (prev + n - 1) % n; } while (!keep[prev] && prev != i);
                do { next = (next + 1) % n; } while (!keep[next] && next != i);
                if (prev == i || next == i || prev == next) { continue; }
                if (distance(loop, i, prev, next) <= tolerance) { keep[i] = false; }
            }
        }
        int count = 0;
        for (boolean k : keep) { if (k) { count++; } }
        float[] out = new float[count * 2];
        int j = 0;
        for (int i = 0; i < n; i++)
        {
            if (keep[i]) { out[j++] = loop[i * 2]; out[j++] = loop[i * 2 + 1]; }
        }
        return out;
    }

    private static double distance(int[] loop, int i, int a, int b)
    {
        double ax = loop[a * 2], ay = loop[a * 2 + 1], dx = loop[b * 2] - ax, dy = loop[b * 2 + 1] - ay;
        double px = loop[i * 2] - ax, py = loop[i * 2 + 1] - ay, length = Math.hypot(dx, dy);
        return length > 0 ? Math.abs(px * dy - py * dx) / length : Math.hypot(px, py);
    }

    /** Keeps points between from and to (to == n wraps to 0) farther than tolerance from the chord. */
    private static void mark(int[] loop, int from, int to, double tolerance, boolean[] keep)
    {
        int n = loop.length / 2;
        int end = to % n;
        double ax = loop[from * 2], ay = loop[from * 2 + 1], bx = loop[end * 2], by = loop[end * 2 + 1];
        double dx = bx - ax, dy = by - ay, length = Math.hypot(dx, dy);
        int index = -1;
        double max = tolerance;
        for (int i = from + 1; i < to; i++)
        {
            double px = loop[i * 2] - ax, py = loop[i * 2 + 1] - ay;
            double d = length > 0 ? Math.abs(px * dy - py * dx) / length : Math.hypot(px, py);
            if (d > max) { max = d; index = i; }
        }
        if (index < 0) { return; }
        keep[index] = true;
        mark(loop, from, index, tolerance, keep);
        mark(loop, index, to, tolerance, keep);
    }
}
