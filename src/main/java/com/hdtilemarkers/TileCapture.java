package com.hdtilemarkers;

import java.awt.*;
import java.util.*;
import java.util.List;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;

/**
 * The tile shapes of another plugin's held overlays, drawn by HD Tile Markers. IndicatorOverlay runs the
 * overlays once per frame on {@link CapturingGraphics} ({@link #render}): every polygon they fill or outline
 * that is exactly a tile (or square area up to 5x5) polygon of the scene becomes a mark for the scene from the next frame;
 * text, icons, timers, clickboxes and any polygon that matches no tile are drawn in 2D as the overlay drew them.
 * The overlay's own rules (its settings, colours, distances) are thereby kept exactly.
 */
final class TileCapture
{
    /** Consecutive failures of its overlays after which they draw themselves again, until the next rebuild. */
    private static final int MAX_FAILURES = 3;
    /**
     * Area sizes tried (getCanvasTileAreaPoly): single tiles up to 5x5, such as NPC footprints. Odd sizes are centred
     * on a tile, even sizes on a tile corner.
     */
    private static final int MAX_SIZE = 5;

    private final Client client;
    private final String prefix;
    /**
     * Shapes that are no tile are dropped instead of drawn in 2D: HD Tile Markers draws them itself (clickboxes and
     * hulls of a source that follows the plugin's rules), so only the overlay's text, images and bars remain.
     */
    private final boolean dropOthers;
    /**
     * Lines (Line2D) between two points on the ground become scene lines: each end is traced back to where it meets
     * the ground. Only for overlays that draw lines on the ground (Quest Helper's world lines); elsewhere a line can
     * be a screen-space arrow.
     */
    private final boolean captureLines;
    /** Depth layer of its marks: above the other marks for a plugin whose highlights are drawn over theirs. */
    int markLayer = Marker.EXTERNAL, modelLayer = SceneShapeRenderer.HULL_LAYER;
    /** Clickboxes and hulls whose object or NPC is found (ShapeIdentifier) are drawn live in the scene; null: not tried. */
    private final ShapeIdentifier identifier;
    private List<Marker> captured = Collections.emptyList();
    private int failures;
    /** The tile matched per shape order last frame: overlays draw in a stable order, so it is tried first. */
    private List<LocalPoint> guesses = new ArrayList<>();
    /** The NPC matched per NPC tile order last frame, tried first the same way. */
    private List<Actor> npcGuesses = new ArrayList<>();
    /**
     * Four-point polygons that were no tile (such as a box drawn around text), by their exact points, and the frame
     * until which they are not looked for again: each costs up to 90 tile polygons and a pass over the NPCs.
     */
    private final Map<Long, Integer> notTiles = new HashMap<>();
    private int frame;
    private static final int SKIP_FRAMES = 10;

    /** A tile's border and fill as captured this frame: OverlayUtil.renderPolygon draws and fills the same polygon. */
    private static final class Shape
    {
        final LocalPoint tile;
        final int size;
        /** The walking NPC whose tile this is (NPC.getCanvasTilePoly), followed every frame; null for a scene tile. */
        final Actor npc;
        Color border = Marker.NO_FILL, fill = Marker.NO_FILL;
        float width;

        Shape(LocalPoint tile, int size) { this(tile, size, null); }

        Shape(LocalPoint tile, int size, Actor npc) { this.tile = tile; this.size = size; this.npc = npc; }
    }

    TileCapture(Client client, String prefix) { this(client, prefix, false); }

    TileCapture(Client client, String prefix, boolean dropOthers) { this(client, prefix, dropOthers, false); }

    TileCapture(Client client, String prefix, boolean dropOthers, boolean captureLines)
    {
        this(client, prefix, dropOthers, captureLines, false);
    }

    TileCapture(Client client, String prefix, boolean dropOthers, boolean captureLines, boolean identify)
    {
        this.client = client; this.prefix = prefix; this.dropOthers = dropOthers; this.captureLines = captureLines;
        identifier = identify ? new ShapeIdentifier(client, prefix) : null;
    }

    /** Whether HD Tile Markers can draw its tiles: not after its overlays failed repeatedly when run here. */
    boolean usable() { return failures < MAX_FAILURES; }

    /** Client thread only (rebuild, shutdown). */
    void clear()
    {
        captured = Collections.emptyList();
        guesses = new ArrayList<>();
        npcGuesses = new ArrayList<>();
        notTiles.clear();
        failures = 0;
        if (identifier != null) { identifier.clear(); }
    }

    /** The objects this plugin highlighted last frame (identified clickboxes and hulls). */
    void objects(Set<net.runelite.api.TileObject> out) { if (identifier != null) { identifier.objects(out); } }

    /** The scene tiles (local x << 32 | y) this plugin highlighted last frame. */
    void tiles(Set<Long> out) { for (Marker m : captured) { if (m.follow == null && m.lineX == null) { out.add((long) m.point.getX() << 32 | m.point.getY()); } } }

    /** Diagnostics: identified shapes found/tried last frame and the last miss, or null without identification. */
    String identifierStatus() { return identifier == null ? null : identifier.status(client.getTopLevelWorldView()); }

    /** The objects and NPCs identified in the last frame, drawn live with the colours their overlay used. */
    void collect(List<Marker> tiles, List<ModelTarget> models)
    {
        tiles.addAll(captured);
        if (identifier == null) { return; }
        int first = models.size();
        identifier.collect(models);
        for (int i = first; i < models.size(); i++) { models.get(i).layer = modelLayer; }
    }

    /** The marks captured in the last frame. */
    void collect(List<Marker> out) { out.addAll(captured); }

    /** Runs the held overlays once on g: tile polygons become marks for the scene, everything else is drawn on g. */
    void render(List<Overlay> held, Graphics2D g)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null || held.isEmpty()) { captured = Collections.emptyList(); if (identifier != null) { identifier.clear(); } return; }
        frame++;
        if (!notTiles.isEmpty()) { notTiles.values().removeIf(until -> until < frame); }
        int plane = wv.getPlane();
        if (identifier != null) { identifier.beginFrame(); }
        Map<Long, Shape> shapes = new LinkedHashMap<>();
        List<Marker> lines = new ArrayList<>();
        List<LocalPoint> matched = new ArrayList<>();
        List<Actor> matchedNpcs = new ArrayList<>();
        ModelShapes.Camera[] camera = {null};
        CapturingGraphics.Sink sink = new CapturingGraphics.Sink()
        {
            @Override public boolean fill(java.awt.Shape shape, Color color)
            {
                Shape s = shape(shape, color);
                if (s == null) { return identified(shape, color, 0, false) || dropOthers; }
                s.fill = color;
                return true;
            }

            @Override public boolean draw(java.awt.Shape shape, Color color, Stroke stroke)
            {
                if (captureLines && shape instanceof java.awt.geom.Line2D && stroke instanceof BasicStroke && color != null)
                {
                    boolean taken = line((java.awt.geom.Line2D) shape, color, ((BasicStroke) stroke).getLineWidth(), wv, plane, lines, camera);
                    return taken || dropOthers;
                }
                Shape s = stroke instanceof BasicStroke ? shape(shape, color) : null;
                if (s == null)
                {
                    float width = stroke instanceof BasicStroke ? ((BasicStroke) stroke).getLineWidth() : 1;
                    return identified(shape, color, width, true) || dropOthers;
                }
                s.border = color;
                s.width = ((BasicStroke) stroke).getLineWidth();
                return true;
            }

            private boolean identified(java.awt.Shape shape, Color color, float width, boolean border)
            {
                return identifier != null && !(shape instanceof java.awt.geom.Line2D) && identifier.take(shape, color, width, border, wv, camera(camera));
            }

            private Shape shape(java.awt.Shape shape, Color color)
            {
                if (!(shape instanceof Polygon) || ((Polygon) shape).npoints != 4 || color == null) { return null; }
                Polygon p = (Polygon) shape;
                long key = 17;
                for (int i = 0; i < 4; i++) { key = key * 31 + p.xpoints[i]; key = key * 31 + p.ypoints[i]; }
                Integer until = notTiles.get(key);
                if (until != null && until >= frame) { return null; }
                int order = matched.size();
                LocalPoint guess = order < guesses.size() ? guesses.get(order) : null;
                long[] found = match(p, guess, wv, camera);
                if (found == null)
                {
                    // A walking NPC's or player's tile lies between tiles (getCanvasTilePoly at its current location).
                    int npcOrder = matchedNpcs.size();
                    Actor actor = matchActor(p, npcOrder < npcGuesses.size() ? npcGuesses.get(npcOrder) : null, wv);
                    if (actor == null) { notTiles.put(key, frame + SKIP_FRAMES); return null; }
                    matchedNpcs.add(actor);
                    NPCComposition composition = actor instanceof NPC ? ((NPC) actor).getTransformedComposition() : null;
                    int size = composition == null ? 1 : Math.max(1, composition.getSize());
                    long id = actor instanceof NPC ? -1L - ((NPC) actor).getIndex() : -100_000L - ((Player) actor).getId();
                    return shapes.computeIfAbsent(id, k -> new Shape(actor.getLocalLocation(), size, actor));
                }
                LocalPoint tile = new LocalPoint((int) found[0], (int) found[1], wv);
                int size = (int) found[2];
                matched.add(tile);
                return shapes.computeIfAbsent(found[0] << 24 | found[1] << 4 | size, k -> new Shape(tile, size));
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
        // A one-off failure is skipped; repeated ones hand the drawing back.
        failures = ok ? 0 : failures + 1;
        List<Marker> out = new ArrayList<>(shapes.size());
        for (Shape s : shapes.values())
        {
            String key = s.npc instanceof NPC ? prefix + "npc:" + ((NPC) s.npc).getIndex() + ":tile"
                : s.npc instanceof Player ? prefix + "player:" + ((Player) s.npc).getId() + ":tile"
                : prefix + s.tile.getX() + ":" + s.tile.getY() + ":" + s.size;
            Marker m = new Marker(key, s.tile, plane, s.size, s.size, s.border, s.fill, s.border.getAlpha() > 0 ? s.width : 0, null, false);
            m.follow = s.npc;
            m.layer = markLayer;
            out.add(m);
        }
        out.addAll(lines);
        if (identifier != null) { identifier.endFrame(); }
        captured = out;
        guesses = matched;
        npcGuesses = matchedNpcs;
    }

    /** Pieces of a scene line are at most this long (local units), so each fits one scene object and follows the terrain. */
    private static final int LINE_PIECE = 6 * 128;

    /**
     * A scene line between where both ends of a canvas line meet the ground, added to out in pieces; false (not taken)
     * when an end does not meet the ground.
     */
    private boolean line(java.awt.geom.Line2D l, Color color, float width, WorldView wv, int plane, List<Marker> out, ModelShapes.Camera[] camera)
    {
        float[] a = WalkPredictor.ground(camera(camera), (float) l.getX1(), (float) l.getY1(), wv, plane);
        float[] b = a == null ? null : WalkPredictor.ground(camera(camera), (float) l.getX2(), (float) l.getY2(), wv, plane);
        if (b == null) { return false; }
        double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
        int pieces = Math.max(1, (int) Math.ceil(length / LINE_PIECE));
        for (int i = 0; i < pieces; i++)
        {
            int x0 = Math.round(a[0] + (b[0] - a[0]) * i / pieces), y0 = Math.round(a[1] + (b[1] - a[1]) * i / pieces);
            int x1 = Math.round(a[0] + (b[0] - a[0]) * (i + 1) / pieces), y1 = Math.round(a[1] + (b[1] - a[1]) * (i + 1) / pieces);
            Marker m = new Marker(prefix + "line:" + out.size(), new LocalPoint((x0 + x1) / 2, (y0 + y1) / 2, wv), plane, 1, 1, color,
                Marker.NO_FILL, width, null, false);
            m.lineX = new int[]{x0, x1};
            m.lineY = new int[]{y0, y1};
            m.layer = markLayer;
            out.add(m);
        }
        return true;
    }

    private ModelShapes.Camera camera(ModelShapes.Camera[] camera)
    {
        if (camera[0] == null)
        {
            camera[0] = new ModelShapes.Camera(client.getCameraFpX(), client.getCameraFpY(), client.getCameraFpZ(),
                client.getCameraFpPitch(), client.getCameraFpYaw(), client.getScale(), client.getViewportXOffset(),
                client.getViewportYOffset(), client.getViewportWidth(), client.getViewportHeight());
        }
        return camera[0];
    }

    /**
     * The tile centre and area size {x, y, size} whose canvas polygon (Perspective.getCanvasTileAreaPoly) this is, or
     * null: the guess and its neighbours first, else the tiles around where the polygon's centre meets the ground.
     */
    private long[] match(Polygon p, LocalPoint guess, WorldView wv, ModelShapes.Camera[] camera)
    {
        long[] found = guess == null ? null : around(p, guess.getX() >> 7, guess.getY() >> 7, wv);
        if (found != null) { return found; }
        float cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) { cx += p.xpoints[i]; cy += p.ypoints[i]; }
        float[] ground = WalkPredictor.ground(camera(camera), cx / 4, cy / 4, wv, wv.getPlane());
        return ground == null ? null : around(p, (int) ground[0] >> 7, (int) ground[1] >> 7, wv);
    }

    /**
     * The NPC whose canvas tile polygon (NPC.getCanvasTilePoly, at its current, possibly between-tiles location) is
     * exactly p: last frame's one first, else the NPCs standing near it on the canvas.
     */
    private Actor matchActor(Polygon p, Actor guess, WorldView wv)
    {
        if (guess != null && samePolygon(p, guess.getCanvasTilePoly())) { return guess; }
        Rectangle bounds = p.getBounds();
        for (NPC npc : wv.npcs())
        {
            if (npc != guess && standsIn(npc, bounds, wv) && samePolygon(p, npc.getCanvasTilePoly())) { return npc; }
        }
        for (Player player : wv.players())
        {
            if (player != guess && standsIn(player, bounds, wv) && samePolygon(p, player.getCanvasTilePoly())) { return player; }
        }
        return null;
    }

    /** The tile's centre projects inside its polygon: only actors standing inside the bounds are compared. */
    private boolean standsIn(Actor actor, Rectangle bounds, WorldView wv)
    {
        LocalPoint lp = actor == null ? null : actor.getLocalLocation();
        net.runelite.api.Point c = lp == null ? null : Perspective.localToCanvas(client, lp, wv.getPlane());
        return c != null && bounds.contains(c.getX(), c.getY());
    }

    private static boolean samePolygon(Polygon p, Polygon q) { return q != null && ShortestPathSource.samePolygon(p, q); }

    /** The tile within one of (tx, ty) whose area polygon equals p, as {x, y, size}, or null. */
    private long[] around(Polygon p, int tx, int ty, WorldView wv)
    {
        for (int r = 0; r <= 1; r++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dy = -r; dy <= r; dy++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) { continue; }
                    int x = tx + dx, y = ty + dy;
                    if (x < 0 || y < 0 || x >= wv.getSizeX() || y >= wv.getSizeY()) { continue; }
                    for (int size = 1; size <= MAX_SIZE; size++)
                    {
                        int offset = size % 2 == 1 ? 64 : 0;
                        LocalPoint lp = new LocalPoint(x * 128 + offset, y * 128 + offset, wv);
                        Polygon q = Perspective.getCanvasTileAreaPoly(client, lp, size);
                        if (q != null && ShortestPathSource.samePolygon(p, q)) { return new long[]{lp.getX(), lp.getY(), size}; }
                    }
                }
            }
        }
        return null;
    }
}
