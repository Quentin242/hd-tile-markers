package com.hdtilemarkers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostics (debug info only): time spent per part, per frame, summed over a few seconds and reported as the
 * average and the worst frame, so a slowdown can be traced to its part from the client log.
 */
final class FrameTimes
{
    private static final long PERIOD = 5_000_000_000L;
    /** Per part: {nanos this frame, total nanos, worst frame nanos}. */
    private final Map<String, long[]> parts = new LinkedHashMap<>();
    private int frames;
    private long since = System.nanoTime();

    void add(String part, long nanos) { parts.computeIfAbsent(part, k -> new long[3])[0] += nanos; }

    /** Ends a frame; every PERIOD returns the report (and starts a new one), else null. */
    String frame(String counts)
    {
        frames++;
        for (long[] p : parts.values())
        {
            p[1] += p[0];
            p[2] = Math.max(p[2], p[0]);
            p[0] = 0;
        }
        long now = System.nanoTime();
        if (now - since < PERIOD || frames == 0) { return null; }
        StringBuilder out = new StringBuilder("HD Tile Markers frame times over ").append(frames).append(" frames (avg / worst ms): ");
        for (Map.Entry<String, long[]> e : parts.entrySet())
        {
            long[] p = e.getValue();
            out.append(e.getKey()).append(' ').append(String.format("%.2f/%.2f", p[1] / 1e6 / frames, p[2] / 1e6)).append(", ");
        }
        out.append(counts);
        parts.clear();
        frames = 0;
        since = now;
        return out.toString();
    }
}
