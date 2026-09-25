package com.hdtilemarkers;

import java.awt.*;
import java.awt.geom.Line2D;
import java.util.*;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.Overlay;

/**
 * Shortest Path's path tiles and lines, drawn by HD Tile Markers. Shortest Path offers no way to read its
 * path, and HD Tile Markers does not reference its classes: IndicatorOverlay runs its held path overlay once
 * per frame on {@link CapturingGraphics} ({@link #render}). Each tile polygon it fills and line it draws is
 * matched back to the scene tile it came from and drawn in the scene from the next frame; its text, and any
 * shape that matches no tile, is drawn in 2D as usual.
 */
@Singleton
final class ShortestPathSource
{
    static final String PLUGIN = "shortestpath.ShortestPathPlugin";
    static final String OVERLAY = "shortestpath.PathTileOverlay";
    static final String GROUP = "shortestpath";
    /** Consecutive failures of its overlay after which it draws itself again, until the next rebuild. */
    private static final int MAX_FAILURES = 3;
    /** Canvas cell size of the tile index. */
    private static final int CELL = 32;

    private final Client client;
    private final ConfigManager configs;
    /** Marks of the last frame's shapes, for the scene of the next frame. */
    private List<Marker> captured = Collections.emptyList();
    private int failures;
    /** The tile matched last: consecutive path steps are neighbours, so the next is searched around it first. */
    private LocalPoint last;
    /** The first tile matched in the last frame: the path's start, where the next frame's search begins. */
    private LocalPoint first;
    /**
     * Fallback for shapes that are no neighbour of the last one (a teleport, the first tile off the player's):
     * every scene tile by the canvas cell its centre projects to, built when first needed for a camera and scene.
     * No ray is cast, so terrain between the camera and a tile does not matter.
     */
    private final Map<Long, List<Projected>> tileIndex = new HashMap<>();
    private boolean indexed;
    /**
     * The index was built for another camera: kept, as a tile's centre moves only a little between frames. Looked up
     * with STALE_MARGIN and every candidate checked against the current camera; rebuilt (projecting every scene tile)
     * only when that finds nothing, at most once a frame. It used to be rebuilt every frame the camera moved.
     */
    private boolean stale, rebuiltThisFrame;
    private static final int STALE_MARGIN = 96;
    private final int[] indexedScene = new int[5], indexedCamera = new int[6];

    /** A scene tile and where its centre projects on the canvas. */
    private static final class Projected
    {
        final LocalPoint tile;
        final int x, y;

        Projected(LocalPoint tile, int x, int y) { this.tile = tile; this.x = x; this.y = y; }
    }

    @Inject
    ShortestPathSource(Client client, ConfigManager configs) { this.client = client; this.configs = configs; }

    /**
     * Whether HD Tile Markers can draw its path: not with its debug overlays (transports, collision map) on,
     * which are no path, nor after its overlay failed repeatedly when run here.
     */
    boolean usable()
    {
        return failures < MAX_FAILURES && !"true".equals(configs.getConfiguration(GROUP, "drawTransports"))
            && !"true".equals(configs.getConfiguration(GROUP, "drawCollisionMap"));
    }

    /** Client thread only (rebuild, shutdown): forgets captured marks, the index and failures. */
    void clear()
    {
        captured = Collections.emptyList();
        tileIndex.clear();
        indexed = false;
        stale = false;
        last = null;
        first = null;
        failures = 0;
    }

    /** The marks captured in the last frame. */
    void collect(List<Marker> out) { out.addAll(captured); }

    /**
     * Runs the held overlays once on g (IndicatorOverlay): tile fills and lines that match a scene tile become
     * marks for the scene and are not drawn in 2D; everything else is drawn on g.
     */
    void render(List<Overlay> held, Graphics2D g)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null || held.isEmpty()) { captured = Collections.emptyList(); return; }
        int plane = wv.getPlane();
        if (!sameScene(wv, plane)) { tileIndex.clear(); indexed = false; }
        else if (!sameCamera() && indexed) { stale = true; }
        rebuiltThisFrame = false;
        Player player = client.getLocalPlayer();
        last = first != null ? first : player == null ? null : player.getLocalLocation();
        List<Marker> out = new ArrayList<>();
        LocalPoint[] firstThisFrame = {null};
        CapturingGraphics.Sink sink = new CapturingGraphics.Sink()
        {
            @Override public boolean fill(Shape shape, Color color)
            {
                if (!(shape instanceof Polygon) || color == null) { return false; }
                LocalPoint tile = tileOf((Polygon) shape, wv, plane);
                if (tile == null) { return false; }
                out.add(tile("shortestpath:" + out.size(), tile, plane, color));
                if (out.size() == 1) { firstThisFrame[0] = tile; }
                return true;
            }

            @Override public boolean draw(Shape shape, Color color, Stroke stroke)
            {
                if (!(shape instanceof Line2D) || color == null) { return false; }
                Line2D line = (Line2D) shape;
                LocalPoint a = centerOf(line.getX1(), line.getY1(), wv, plane);
                LocalPoint b = a == null ? null : centerOf(line.getX2(), line.getY2(), wv, plane);
                if (b == null) { return false; }
                float width = stroke instanceof BasicStroke ? ((BasicStroke) stroke).getLineWidth() : 1;
                out.add(line("shortestpath:" + out.size(), a, b, plane, color, width));
                if (out.size() == 1) { firstThisFrame[0] = a; }
                return true;
            }
        };
        boolean ok = true;
        for (Overlay overlay : held)
        {
            Graphics2D capture = new CapturingGraphics((Graphics2D) g.create(), sink);
            try { overlay.render(capture); }
            catch (RuntimeException ex) { ok = false; }
            finally { capture.dispose(); }
        }
        // A one-off failure (its path replaced while drawn) is skipped; repeated ones hand the drawing back.
        failures = ok ? 0 : failures + 1;
        captured = out;
        first = firstThisFrame[0];
    }

    /** A filled tile without border, as the overlay's graphics.fill(poly). */
    static Marker tile(String key, LocalPoint point, int plane, Color color)
    {
        Marker m = new Marker(key, point, plane, 1, 1, Marker.NO_FILL, color, 0, null, false);
        m.layer = Marker.EXTERNAL;
        return m;
    }

    /** A line between two tile centres, as the overlay's graphics.draw(line). */
    static Marker line(String key, LocalPoint a, LocalPoint b, int plane, Color color, float width)
    {
        Marker m = new Marker(key, a, plane, 1, 1, color, Marker.NO_FILL, width, null, false);
        m.lineX = new int[]{a.getX(), b.getX()};
        m.lineY = new int[]{a.getY(), b.getY()};
        m.layer = Marker.EXTERNAL;
        return m;
    }

    static boolean samePolygon(Polygon a, Polygon b)
    {
        if (a.npoints != b.npoints) { return false; }
        for (int i = 0; i < a.npoints; i++) { if (a.xpoints[i] != b.xpoints[i] || a.ypoints[i] != b.ypoints[i]) { return false; } }
        return true;
    }

    /** The scene tile whose canvas polygon (Perspective.getCanvasTilePoly) this is, or null. */
    private LocalPoint tileOf(Polygon p, WorldView wv, int plane)
    {
        if (p.npoints != 4) { return null; }
        // Around the last tile first: two rings of neighbours.
        if (last != null)
        {
            for (int r = 0; r <= 2; r++)
            {
                for (LocalPoint lp : ring(last, r, wv))
                {
                    Polygon q = Perspective.getCanvasTilePoly(client, lp);
                    if (q != null && samePolygon(p, q)) { return last = lp; }
                }
            }
        }
        // A tile's centre projects inside its polygon: every tile whose centre lies in the bounds is a candidate.
        Rectangle bounds = p.getBounds();
        for (int attempt = 0; attempt < 2; attempt++)
        {
            int margin = stale ? STALE_MARGIN : 1;
            for (LocalPoint lp : indexed(bounds.x - margin, bounds.y - margin, bounds.x + bounds.width + margin, bounds.y + bounds.height + margin, wv, plane))
            {
                Polygon q = Perspective.getCanvasTilePoly(client, lp);
                if (q != null && samePolygon(p, q)) { return last = lp; }
            }
            if (!rebuildIfStale(wv, plane)) { break; }
        }
        return null;
    }

    /** The scene tile whose centre projects to this canvas point (Perspective.localToCanvas at tile height), or null. */
    private LocalPoint centerOf(double x, double y, WorldView wv, int plane)
    {
        int cx = (int) Math.round(x), cy = (int) Math.round(y);
        if (last != null)
        {
            for (int r = 0; r <= 2; r++)
            {
                for (LocalPoint lp : ring(last, r, wv))
                {
                    net.runelite.api.Point c = Perspective.localToCanvas(client, lp, plane);
                    if (c != null && c.getX() == cx && c.getY() == cy) { return last = lp; }
                }
            }
        }
        for (int attempt = 0; attempt < 2; attempt++)
        {
            if (!stale)
            {
                List<LocalPoint> hits = indexed(cx, cy, cx, cy, wv, plane);
                return hits.isEmpty() ? null : (last = hits.get(0));
            }
            // A stale index only narrows the search: each candidate's centre is projected with the current camera.
            for (LocalPoint lp : indexed(cx - STALE_MARGIN, cy - STALE_MARGIN, cx + STALE_MARGIN, cy + STALE_MARGIN, wv, plane))
            {
                net.runelite.api.Point c = Perspective.localToCanvas(client, lp, plane);
                if (c != null && c.getX() == cx && c.getY() == cy) { return last = lp; }
            }
            if (!rebuildIfStale(wv, plane)) { break; }
        }
        return null;
    }

    /** The scene tiles at Chebyshev distance r from the tile of p. */
    private static List<LocalPoint> ring(LocalPoint p, int r, WorldView wv)
    {
        int tx = p.getX() >> 7, ty = p.getY() >> 7;
        List<LocalPoint> result = new ArrayList<>(Math.max(1, 8 * r));
        for (int dx = -r; dx <= r; dx++)
        {
            for (int dy = -r; dy <= r; dy++)
            {
                if (Math.max(Math.abs(dx), Math.abs(dy)) != r) { continue; }
                int x = tx + dx, y = ty + dy;
                if (x < 0 || y < 0 || x >= wv.getSizeX() || y >= wv.getSizeY()) { continue; }
                result.add(new LocalPoint(x * 128 + 64, y * 128 + 64, wv));
            }
        }
        return result;
    }

    /** Whether the viewport, plane and loaded scene are those the index was built for; remembers them. */
    private boolean sameScene(WorldView wv, int plane)
    {
        int[] view = {client.getViewportWidth(), client.getViewportHeight(), plane, wv.getBaseX(), wv.getBaseY()};
        if (Arrays.equals(view, indexedScene)) { return true; }
        System.arraycopy(view, 0, indexedScene, 0, view.length);
        return false;
    }

    /** Whether the camera is the one last seen; remembers it. */
    private boolean sameCamera()
    {
        int[] view = {client.getCameraX(), client.getCameraY(), client.getCameraZ(), client.getCameraPitch(), client.getCameraYaw(), client.getScale()};
        if (Arrays.equals(view, indexedCamera)) { return true; }
        System.arraycopy(view, 0, indexedCamera, 0, view.length);
        return false;
    }

    /** Rebuilds a stale index for the current camera, once a frame; whether it did. */
    private boolean rebuildIfStale(WorldView wv, int plane)
    {
        if (!stale || rebuiltThisFrame) { return false; }
        index(wv, plane);
        rebuiltThisFrame = true;
        return true;
    }

    /** Tiles whose centre projects within these canvas bounds, from the tile index. */
    private List<LocalPoint> indexed(int x0, int y0, int x1, int y1, WorldView wv, int plane)
    {
        if (!indexed) { index(wv, plane); }
        List<LocalPoint> result = new ArrayList<>();
        for (int cx = Math.floorDiv(x0, CELL); cx <= Math.floorDiv(x1, CELL); cx++)
        {
            for (int cy = Math.floorDiv(y0, CELL); cy <= Math.floorDiv(y1, CELL); cy++)
            {
                List<Projected> tiles = tileIndex.get(cell(cx, cy));
                if (tiles == null) { continue; }
                for (Projected t : tiles)
                {
                    if (t.x >= x0 && t.x <= x1 && t.y >= y0 && t.y <= y1) { result.add(t.tile); }
                }
            }
        }
        return result;
    }

    private void index(WorldView wv, int plane)
    {
        tileIndex.clear();
        for (int x = 0; x < wv.getSizeX(); x++)
        {
            for (int y = 0; y < wv.getSizeY(); y++)
            {
                LocalPoint lp = new LocalPoint(x * 128 + 64, y * 128 + 64, wv);
                net.runelite.api.Point c = Perspective.localToCanvas(client, lp, plane);
                if (c == null) { continue; }
                tileIndex.computeIfAbsent(cell(Math.floorDiv(c.getX(), CELL), Math.floorDiv(c.getY(), CELL)), k -> new ArrayList<>())
                    .add(new Projected(lp, c.getX(), c.getY()));
            }
        }
        indexed = true;
        stale = false;
    }

    private static long cell(int cx, int cy) { return ((long) cx << 32) | (cy & 0xffffffffL); }
}
