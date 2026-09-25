package com.hdtilemarkers;

import java.awt.Color;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;

/**
 * The shared scene renderer. Every frame, each tile footprint and model hull
 * is projected to the canvas, given a border of an exact pixel width, and
 * written back into the scene as flat geometry. The GPU rasterizes it at the
 * scene's full resolution, so it is not blurred by the stretched 2D layer.
 *
 * Vertices are unprojected slightly towards the camera along their own view
 * ray. That leaves their screen position unchanged but keeps them above the
 * terrain in depth-tested renderers.
 *
 * All shapes that belong to one tile are merged into a single scene object,
 * whose radius stays inside that tile. The client gives every tile only a few
 * object slots and an object takes one on each tile its radius touches; one
 * object per shape (up to 3x3 tiles each) overflowed them, and the client then
 * dropped a different set of objects every frame.
 */
@Singleton
final class SceneShapeRenderer
{
    private static final int MIN_VERTICES = 64, MIN_FACES = 96, MIN_RADIUS = 256;
    /** Keeps an object's tile footprint to its own tile (tile centre +- 60). */
    private static final int FOOTPRINT_RADIUS = 60;
    private final Client client;
    private final CarrierModels carriers;
    private final RenderTrace trace;
    private final Map<Long, Bucket> buckets = new HashMap<>();
    private final Set<String> appended = new HashSet<>();
    /**
     * Projections per NPC or object renderable, kept across frames so their arrays are reused.
     * Styles of one NPC share its projection within a frame; static object models keep theirs,
     * silhouette included, while the camera and the object stay where they were.
     */
    private final Map<Object, Projection> projections = new IdentityHashMap<>();
    private int frame;
    /** New outline traces per frame are limited to this much time; the rest are drawn as hulls. */
    private static final long OUTLINE_BUDGET_NANOS = 2_500_000;
    private long outlineNanos, clickboxNanos, newModelNanos;

    /** Diagnostics: this frame's time tracing outlines and making clickboxes. */
    long outlineNanos() { return outlineNanos; }
    long clickboxNanos() { return clickboxNanos; }
    /** Diagnostics: this frame's time making models for scene objects (none spare), prewarm included. */
    long newModelNanos() { return newModelNanos; }
    /** Models no scene object uses now, kept for the next ones: making and uploading a model costs. */
    private final java.util.Deque<Spare> spareCarriers = new ArrayDeque<>();

    /** A model given up by its scene object, with what the next owner must clear of it. */
    private static final class Spare
    {
        final Model model;
        final int radius, usedFaces, usedVertices;
        Spare(Model model, int radius, int usedFaces, int usedVertices)
        { this.model = model; this.radius = radius; this.usedFaces = usedFaces; this.usedVertices = usedVertices; }
    }

    /** Takes the scene object out of the scene and keeps its model as a spare, if there is room. */
    private void retire(Bucket b)
    {
        b.hide();
        Spare spare = b.release();
        if (spare != null && spareCarriers.size() < MAX_SPARES) { spareCarriers.addLast(spare); }
    }
    /** Many tagged NPCs take more objects than 64: after a teleport the rest were all made anew in one frame. */
    private static final int MAX_SPARES = 256;
    /** Spares made ahead while logging in (see prewarm): half for tiles, half for hulls, clickboxes and outlines. */
    private static final int PREWARM = 64;

    /**
     * Makes spare models ahead, in frames that draw nothing yet (logging in), at most budgetNanos per frame: every
     * object of the first frame that drew needed one, all made in that frame. Normals as begin sets them.
     */
    void prewarm(long budgetNanos)
    {
        long start = System.nanoTime();
        while (spareCarriers.size() < PREWARM && System.nanoTime() - start < budgetNanos)
        {
            boolean small = spareCarriers.size() % 2 == 0;
            int radius = small ? MIN_RADIUS : 768;
            Model created = small ? carriers.create(MIN_VERTICES, MIN_FACES, radius, true) : carriers.create(512, 768, radius, true);
            if (created == null) { break; }
            carriersCreated++;
            FlatModel.normals(created, 0, -1, 0);
            spareCarriers.addLast(new Spare(created, radius, 0, 6));
        }
        newModelNanos = System.nanoTime() - start;
    }

    /** Frame-local owned data: actor mesh arrays can be overwritten by another getModel(). */
    private static final class Projection
    {
        float[] x = new float[0], y = new float[0], hull;
        int[] a = new int[0], b = new int[0], c = new int[0];
        boolean[] hidden;
        /** Number of faces copied this frame; 0 until an outline needs them. */
        int faces, n;
        float depth;
        boolean outside;
        /** Some vertices were not projected (at or too near the camera): their faces are left out of every shape. */
        boolean partial;
        /** The model's clickbox is its projected bounding box (Model.useBoundingBox), not a union of face rectangles. */
        boolean boundingBox;
        List<float[]> loops;
        /** The projected bounding box's convex hull (clickbox bounds), or null; and the clickbox from it. */
        float[] boundsHull;
        List<float[]> clickbox;
        int frame, facesFrame, localX, localY, height, orientation;
        ModelShapes.Camera camera;
        /** The previous projection's points, kept to see whether a new one came out the same (see project). */
        float[] lastX = new float[0], lastY = new float[0];
        /** Hidden faces, summed, and the viewport of the projection: all a clickbox or outline depends on besides points. */
        long hiddenSum;
        int viewport;
    }
    private final Silhouette.Scratch silhouetteScratch = new Silhouette.Scratch();
    private final ScreenOutline outline = new ScreenOutline();
    /** The outline cut around the player, made apart: used only when it still fits a scene object. */
    private final ScreenOutline cutOutline = new ScreenOutline();
    /** Diagnostics: shapes left out this frame because they did not fit a scene object, or lay out of reach. */
    private int tooLarge, outOfReach, cutSkipped;
    private final float[] point = new float[3];
    private float[] px = new float[64], py = new float[64], pd = new float[64];
    /** Scratch for model hulls, outlines and clickboxes; ScreenOutline copies what it needs. */
    private float[] hx = new float[64], hy = new float[64], hd = new float[64];
    private ModelShapes.Camera camera;
    private float normalX, normalY, normalZ, pixel = 1;
    /**
     * 117 HD casts a shadow from each face more opaque than its threshold (0.71, or 0.01 with its
     * "shadow transparency"), and floating through-walls marks move with the camera, so their shadows
     * shimmer over the ground. Through-walls faces are drawn at most floatingAlphaCap opaque.
     */
    int floatingAlphaCap = 255;
    /** Shadow transparency off: just under its 0.71 threshold. */
    static final int HD_CAP_OPAQUE_SHADOWS = 180;
    /**
     * 117 HD with its default shading caps the lightness of every coloured face (undoVanillaShading:
     * 127 - 72 * (saturation / 7) ^ 0.05, 55 at full saturation); only grey may be lighter. Light colours,
     * such as pink, would all turn into their pure colour: they are drawn as that colour with white over it.
     */
    boolean hdLightnessCap;
    /** Scratch for FlatModel.layers: {hsl, alpha, white alpha} of border and fill. */
    private final int[] borderLayers = new int[3], fillLayers = new int[3];
    private boolean unavailable;
    private int carriersCreated;
    private int layer;
    /** Through walls: shapes are pulled towards the camera, gathered in one object where possible. */
    private boolean xray, xrayCameraNear;
    /**
     * Through walls, marks leave out the local player's silhouette on screen (set per frame by cutAround): they still
     * show through every wall and object, and the player stands in front of them. Depth cannot do both: behind the
     * player, a mark near it went under the walls and objects around it too.
     */
    private PlayerCut playerCut;
    /** The player's projection this frame is ready but not yet traced (see cutAround); its bounds on the canvas. */
    private boolean playerPending;
    private float playerMinX, playerMinY, playerMaxX, playerMaxY;
    private int playerN, playerFaces;
    /** The last traced projection, to reuse its cut while the player and the camera stay exactly the same. */
    private float[] lastPlayerX = new float[0], lastPlayerY = new float[0];
    private int lastPlayerN = -1, lastPlayerFaces;
    private PlayerCut lastPlayerCut;
    /** Marks with more faces than this are drawn whole over the player: cut, they could outgrow a scene object. */
    private static final int MAX_CUT_FACES = 600;
    /** Whether the shape being drawn goes through walls (xray). */
    private boolean shapeXray;
    private final Silhouette.Scratch playerScratch = new Silhouette.Scratch();
    private float[] playerX = new float[0], playerY = new float[0];
    private int[] playerA = new int[0], playerB = new int[0], playerC = new int[0];
    private boolean[] playerHidden = new boolean[0];
    private LocalPoint xrayAnchor;
    private int xrayLevel, xrayHeight;
    private static final float XRAY_NEAR = 200, XRAY_SCALE = 0.03f;
    /**
     * Nearest depth of a through-walls vertex: the GPU plugin skips a see-through model whole if any vertex is
     * nearer than 50 (ModelUploader.uploadSortedModel), with its own camera, which may differ slightly.
     */
    private static final float XRAY_MIN_DEPTH = ModelShapes.NEAR + 30;
    /** Depth taken by the layer bias (0.25 per layer, up to HULL_LAYER + 3 = 15 and a white layer), above XRAY_MIN_DEPTH. */
    private static final float XRAY_LAYER_SPAN = 4f;
    /*
     * The GPU plugin draws nothing of a see-through model whose diameter is 6000 or more
     * (ModelUploader.uploadSortedModel; Zone.renderAlpha likewise). Carrier bounds are six extreme
     * vertices at +-radius on each axis, giving a diameter of about 2.83 x radius, so carriers stay
     * within MAX_RADIUS; through-walls points move towards the camera only as far as XRAY_REACH
     * from their object's anchor.
     */
    static final int MAX_RADIUS = 2000, XRAY_REACH = 1900;
    /** Ground points of a shape sharing the through-walls object stay this close to its anchor. */
    private static final int XRAY_SHARED = XRAY_REACH - 300;
    /**
     * With the camera this close (horizontally) to the through-walls anchor, the object hangs at the camera's
     * height and every shape can share it: its points are pulled to at most a few hundred units from the camera.
     */
    private static final int XRAY_CAMERA = XRAY_REACH - 700;
    private float[] tx = new float[64], ty = new float[64], tz = new float[64];
    static final int HULL_LAYER = 12;

    @Inject
    SceneShapeRenderer(Client client, CarrierModels carriers, RenderTrace trace) { this.client = client; this.carriers = carriers; this.trace = trace; }

    /**
     * Starts a frame; canvasPerPixel converts screen pixels to canvas units (1 / stretch scale).
     * Shapes are rebuilt every frame: one that is not appended is simply not drawn. With xrayAnchor set
     * (in the drawn zone nearest the camera), every shape is drawn through walls: each vertex moves along
     * its view ray towards the camera, keeping its screen position and its order relative to other shapes.
     */
    void begin(ModelShapes.Camera camera, float canvasPerPixel, LocalPoint xrayAnchor, int xrayLevel)
    {
        this.xray = xrayAnchor != null;
        this.xrayAnchor = xrayAnchor;
        this.xrayLevel = xrayLevel;
        this.camera = camera;
        pixel = canvasPerPixel;
        trace.beginFrame();
        appended.clear();
        frame++;
        outlineNanos = 0;
        clickboxNanos = 0;
        newModelNanos = 0;
        playerCutNanos = 0;
        playerCut = null;
        playerPending = false;
        unavailable = false;
        for (Bucket b : buckets.values()) { b.vertices = 0; b.faces = 0; }
        // Normals point straight up (model y is down): 117 HD, which uses them because faces are not
        // marked flat (see FlatModel.paint), then lights every mark like flat ground seen from above,
        // the same from every camera angle.
        normalX = 0;
        normalY = -1;
        normalZ = 0;
        if (xray)
        {
            // Renderers sort see-through models by the distance from their position to the camera, farthest
            // first: at the camera's height the shared object comes after everything else in its zone.
            float dx = camera.x - xrayAnchor.getX(), dy = camera.y - xrayAnchor.getY();
            xrayCameraNear = dx * dx + dy * dy <= (float) XRAY_CAMERA * XRAY_CAMERA;
            xrayHeight = xrayCameraNear ? Math.round(camera.z)
                : Terrain.heightOnLevel(client.getTopLevelWorldView(), xrayAnchor.getX(), xrayAnchor.getY(), xrayLevel);
        }
    }

    /** Draws a tile footprint; returns whether it is in the scene this frame. */
    boolean tile(Marker m)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (unavailable || m.point.getWorldView() != wv.getId()) { return false; }
        if ((m.borderWidth <= 0 || m.color.getAlpha() == 0) && m.fill.getAlpha() == 0) { return culled(m.key); }
        shapeXray = xray;
        layer = m.layer;
        if (m.dot) { return dot(wv, m); }
        if (m.quadX != null) { return quad(wv, m); }
        if (m.lineX != null) { return line(wv, m); }
        LocalPoint at = m.where();
        int w = m.width, h = m.height, n = 2 * (w + h);
        ensure(n);
        int x0 = at.getX() - w * 64, y0 = at.getY() - h * 64, k = 0;
        int level = Terrain.level(wv, at.getSceneX(), at.getSceneY(), m.plane);
        // Walk the perimeter counterclockwise, sampling height at every tile boundary.
        for (int i = 0; i < w; i++) { k = sample(wv, level, x0 + i * 128, y0, k); }
        for (int j = 0; j < h; j++) { k = sample(wv, level, x0 + w * 128, y0 + j * 128, k); }
        for (int i = w; i > 0; i--) { k = sample(wv, level, x0 + i * 128, y0 + h * 128, k); }
        for (int j = h; j > 0; j--) { k = sample(wv, level, x0, y0 + j * 128, k); }
        if (k < 0) { return false; }
        int centerHeight = Terrain.heightOnLevel(wv, at.getX(), at.getY(), level);
        camera.project(at.getX(), at.getY(), centerHeight, point);
        boolean corners = m.cornerDivisor > 0;
        if (!(point[2] >= PARTIAL_NEAR) || !outline.build(px, py, pd, n, point[0], point[1], point[2],
            Math.max(0.01f, m.borderWidth * pixel))) { return false; }
        if (oversized()) { return false; }
        if (offscreen()) { return culled(m.key); }
        if (!corners) { return draw(m.key, at, level, centerHeight, m.color, m.fill, m.borderWidth > 0); }
        // Corners only: the fill covers the whole footprint, the border only its corners.
        if (m.fill.getAlpha() > 0) { draw(m.key, at, level, centerHeight, m.color, m.fill, false); }
        if (m.borderWidth > 0) { corners(m, n, w, h, level, centerHeight); }
        return true;
    }

    /**
     * The four corner lines of a footprint, each 1/divisor of its side along the
     * projected perimeter, as Corner Tile Indicators' and Better NPC Highlight's
     * renderPolygonCorners do on screen.
     */
    private void corners(Marker m, int n, int w, int h, int level, int centerHeight)
    {
        // Perimeter indices of the four corners: south-west, south-east, north-east, north-west.
        int[] at = {0, w, w + h, 2 * w + h};
        float[] cx = px.clone(), cy = py.clone(), cd = pd.clone();
        float[] sx = new float[3], sy = new float[3], sd = new float[3];
        for (int k = 0; k < 4; k++)
        {
            int corner = at[k], next = at[(k + 1) % 4], prev = at[(k + 3) % 4];
            float t = 1f / m.cornerDivisor;
            sx[0] = lerp(cx[corner], cx[prev], t); sy[0] = lerp(cy[corner], cy[prev], t); sd[0] = lerp(cd[corner], cd[prev], t);
            sx[1] = cx[corner]; sy[1] = cy[corner]; sd[1] = cd[corner];
            sx[2] = lerp(cx[corner], cx[next], t); sy[2] = lerp(cy[corner], cy[next], t); sd[2] = lerp(cd[corner], cd[next], t);
            if (outline.buildStrip(sx, sy, sd, 3, Math.max(0.01f, m.borderWidth * pixel)))
            {
                draw(m.key, m.where(), level, centerHeight, m.color, Marker.NO_FILL, true);
            }
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** A free quadrilateral on the ground, each side sampled in four steps to follow the terrain. */
    private boolean quad(WorldView wv, Marker m)
    {
        int steps = 4, n = 4 * steps;
        ensure(n);
        int level = Terrain.level(wv, m.point.getSceneX(), m.point.getSceneY(), m.plane), k = 0;
        for (int c = 0; c < 4 && k >= 0; c++)
        {
            int x0 = m.quadX[c], y0 = m.quadY[c], x1 = m.quadX[(c + 1) % 4], y1 = m.quadY[(c + 1) % 4];
            for (int s = 0; s < steps && k >= 0; s++)
            {
                int x = x0 + (x1 - x0) * s / steps, y = y0 + (y1 - y0) * s / steps;
                k = sample(wv, level, Math.max(0, x), Math.max(0, y), k);
            }
        }
        if (k < 0) { return false; }
        int centerHeight = Terrain.heightOnLevel(wv, m.point.getX(), m.point.getY(), level);
        camera.project(m.point.getX(), m.point.getY(), centerHeight, point);
        if (!(point[2] >= PARTIAL_NEAR) || !outline.build(px, py, pd, n, point[0], point[1], point[2],
            Math.max(0.01f, m.borderWidth * pixel))) { return false; }
        if (oversized()) { return false; }
        if (offscreen()) { return culled(m.key); }
        return draw(m.key, m.point, level, centerHeight, m.color, m.fill, m.borderWidth > 0);
    }

    /** An open polyline on the ground, each vertex at the terrain height of its own point. */
    private boolean line(WorldView wv, Marker m)
    {
        int n = m.lineX.length;
        if (n < 2 || m.borderWidth <= 0) { return false; }
        ensure(n);
        int level = Terrain.level(wv, m.point.getSceneX(), m.point.getSceneY(), m.plane), k = 0;
        for (int i = 0; i < n && k >= 0; i++) { k = sample(wv, level, Math.max(0, m.lineX[i]), Math.max(0, m.lineY[i]), k); }
        if (k < 0 || !outline.buildStrip(px, py, pd, n, Math.max(0.01f, m.borderWidth * pixel))) { return false; }
        if (oversized()) { return false; }
        if (offscreen()) { return culled(m.key); }
        int height = Terrain.heightOnLevel(wv, m.point.getX(), m.point.getY(), level);
        return draw(m.key, m.point, level, height, m.color, Marker.NO_FILL, true);
    }

    /** Path Marker's dot style: an 8 pixel circle at the tile center. */
    private boolean dot(WorldView wv, Marker m)
    {
        int n = 16;
        ensure(n);
        int level = Terrain.level(wv, m.point.getSceneX(), m.point.getSceneY(), m.plane);
        int height = Terrain.heightOnLevel(wv, m.point.getX(), m.point.getY(), level);
        camera.project(m.point.getX(), m.point.getY(), height, point);
        if (!(point[2] >= PARTIAL_NEAR)) { return false; }
        float radius = 4 * pixel;
        for (int i = 0; i < n; i++)
        {
            double angle = i * 2 * Math.PI / n;
            px[i] = point[0] + (float) Math.cos(angle) * radius;
            py[i] = point[1] + (float) Math.sin(angle) * radius;
            pd[i] = point[2];
        }
        if (!outline.build(px, py, pd, n, point[0], point[1], point[2], Math.max(0.01f, m.borderWidth * pixel)))
        { return false; }
        if (oversized()) { return false; }
        if (offscreen()) { return culled(m.key); }
        return draw(m.key, m.point, level, height, m.color, m.fill, m.borderWidth > 0);
    }

    private int sample(WorldView wv, int level, int x, int y, int k)
    {
        if (k < 0) { return k; }
        // Corners on the far scene edge belong to the last tile.
        int limitX = wv.getSizeX() * 128 - 1, limitY = wv.getSizeY() * 128 - 1;
        camera.project(x, y, Terrain.heightOnLevel(wv, Math.min(x, limitX), Math.min(y, limitY), level), point);
        if (!(point[2] >= PARTIAL_NEAR)) { return -1; }
        px[k] = point[0]; py[k] = point[1]; pd[k] = point[2];
        return k + 1;
    }

    /** Draws a model hull or clickbox; returns whether it is in the scene this frame. */
    boolean model(ModelTarget t)
    {
        layer = t.layer;
        shapeXray = xray;
        LocalPoint location = t.location();
        if (unavailable || location == null || location.getWorldView() != client.getTopLevelWorldView().getId())
        { return false; }
        int height = t.height(client);
        Object id = t.npc != null ? t.npc : t.renderable;
        if (id == null) { return false; }
        Projection projected = projections.get(id);
        if (projected == null) { projected = new Projection(); projections.put(id, projected); }
        if (projected.frame != frame && !unchanged(t, projected, location, height))
        {
            Mesh<?> mesh = t.mesh();
            if (mesh == null) { return false; }
            project(t, mesh, projected, location, height);
        }
        projected.frame = frame;
        float depth = projected.depth;
        // Nothing of it projects (all at or behind the camera): RuneLite draws nothing of it either, so no 2D shape.
        if (Float.isNaN(depth)) { return culled(t.key); }
        if (projected.outside && !t.clickbox)
        {
            // Handled by the scene route: do not repeat this work in the 2D fallback.
            return culled(t.key);
        }
        float[] hull;
        boolean asHull = false;
        if (t.outline && projected.loops == null)
        {
            // Over this frame's budget: a hull border instead of a new trace.
            asHull = outlineNanos > OUTLINE_BUDGET_NANOS || !faces(t, projected);
            if (!asHull)
            {
                long start = System.nanoTime();
                projected.loops = Silhouette.trace(projected.x, projected.y, projected.a, projected.b,
                    projected.c, projected.faces, projected.hidden, silhouetteScratch);
                outlineNanos += System.nanoTime() - start;
            }
        }
        if (t.outline && !asHull)
        {
            List<float[]> loops = projected.loops;
            int level = Terrain.level(client.getTopLevelWorldView(), location.getSceneX(), location.getSceneY(), t.plane());
            boolean any = false;
            for (float[] traced : loops)
            {
                float[] loop = fit(traced);
                int h = loop.length / 2;
                ensureHull(h);
                for (int i = 0; i < h; i++) { hx[i] = loop[i * 2]; hy[i] = loop[i * 2 + 1]; hd[i] = depth; }
                if (outline.buildPolygon(hx, hy, hd, h, Math.max(0.01f, t.borderWidth * pixel), true) && !offscreen())
                {
                    draw(t.key, location, level, height, t.color, Marker.NO_FILL, true);
                    any = true;
                }
            }
            // Close to the camera the faces left out can leave nothing to trace: then no shape this frame rather than
            // RuneLite's 2D one, which flickered in and out as the camera moved.
            return any || projected.partial && culled(t.key);
        }
        if (t.clickbox)
        {
            // RuneLite's clickbox, computed the same way from the float projection (FloatClickbox): RuneLite rounds every
            // face's rectangle to whole pixels, so its edge wobbled as the camera moved. Kept for static objects while the
            // camera stands still. Only when it cannot be made (off screen, behind the camera) RuneLite's own is drawn.
            List<float[]> polygons = t.exactClickbox || twoModels(t.object) ? null : clickbox(t, projected);
            if (polygons == null)
            {
                java.awt.Shape shape = t.shape();
                if (shape == null) { return projected.partial && culled(t.key); }
                polygons = polygons(shape);
            }
            int level = Terrain.level(client.getTopLevelWorldView(), location.getSceneX(), location.getSceneY(), t.plane());
            boolean any = false;
            // A union of rectangles can enclose holes, wound the other way round; RuneLite leaves them unfilled
            // (even-odd), so they get their border only instead of being filled again over the rest.
            if (polygons.isEmpty()) { return projected.partial && culled(t.key); }
            double outer = Math.signum(signedArea(largest(polygons)));
            for (float[] full : polygons)
            {
                boolean hole = polygons.size() > 1 && Math.signum(signedArea(full)) != outer;
                float[] polygon = fit(full);
                int h = polygon.length / 2;
                ensureHull(h);
                for (int i = 0; i < h; i++) { hx[i] = polygon[i * 2]; hy[i] = polygon[i * 2 + 1]; hd[i] = depth; }
                if (outline.buildPolygon(hx, hy, hd, h, Math.max(0.01f, t.borderWidth * pixel)) && !offscreen())
                {
                    draw(t.key, location, level, height, t.color, hole ? Marker.NO_FILL : t.fill, t.borderWidth > 0);
                    any = true;
                }
            }
            // Close to the camera the faces left out can leave nothing to trace: then no shape this frame rather than
            // RuneLite's 2D one, which flickered in and out as the camera moved.
            return any || projected.partial && culled(t.key);
        }
        if (projected.hull == null)
        {
            projected.hull = hullOf(projected.x, projected.y, projected.n);
        }
        hull = projected.hull;
        if (hull == null || hull.length < 6) { return projected.partial && culled(t.key); }
        int h = hull.length / 2;
        ensureHull(h);
        float cx = 0, cy = 0;
        for (int i = 0; i < h; i++) { hx[i] = hull[i * 2]; hy[i] = hull[i * 2 + 1]; hd[i] = depth; cx += hx[i]; cy += hy[i]; }
        // Just in front of the nearest vertex, so the shape covers the model like the 2D overlay does.
        if (!outline.build(hx, hy, hd, h, cx / h, cy / h, depth, Math.max(0.01f, t.borderWidth * pixel)))
        { return false; }
        if (offscreen()) { return culled(t.key); }
        int level = Terrain.level(client.getTopLevelWorldView(), location.getSceneX(), location.getSceneY(), t.plane());
        return draw(t.key, location, level, height, t.color, asHull ? Marker.NO_FILL : t.fill, t.borderWidth > 0);
    }

    /**
     * A static object model (not an actor, not animated) whose camera, place and rotation are
     * those of its last projection: its projection and silhouette still hold.
     */
    private boolean unchanged(ModelTarget t, Projection p, LocalPoint location, int height)
    {
        return t.npc == null && (t.renderable instanceof Model || t.renderable instanceof ModelData)
            && p.camera != null && p.camera.same(camera) && p.localX == location.getX() && p.localY == location.getY()
            && p.height == height && p.orientation == t.orientation() && p.frame == frame - 1;
    }

    /**
     * Points nearer the camera than this (about one tile) are left out of hulls, outlines and clickboxes, and a tile,
     * path or line with such a point is not drawn: they project to huge canvas positions, which drew a screen-wide
     * shape for a frame when the camera passed an object or came in close at login.
     */
    static final float PARTIAL_NEAR = 150;

    private void project(ModelTarget t, Mesh<?> mesh, Projection p, LocalPoint location, int height)
    {
        int n = mesh.getVerticesCount();
        // The last projection's points and shapes, to keep when this one comes out the same: an NPC standing still,
        // or its animation between two of its frames, while the camera does not move. Its clickbox and outline were
        // made again every frame, the largest part of the scene's time.
        int lastN = p.n, lastFaces = p.faces, lastViewport = p.viewport;
        long lastHidden = p.hiddenSum;
        List<float[]> lastClickbox = p.clickbox, lastLoops = p.loops;
        float[] lastHull = p.hull, swapX = p.lastX, swapY = p.lastY;
        p.lastX = p.x; p.lastY = p.y;
        p.x = swapX; p.y = swapY;
        if (p.x.length < n) { p.x = new float[n]; p.y = new float[n]; }
        p.n = n;
        p.hull = null;
        p.loops = null;
        p.clickbox = null;
        // Every projection gets the clickbox bounds from the mesh at hand (one pass over the vertices, eight projections),
        // whichever style made it: fetching the mesh again for a clickbox rebuilt an NPC's animated model a second time.
        p.boundsHull = boundsHull(mesh, n, location.getX(), location.getY(), height, t.orientation());
        p.facesFrame = 0;
        p.faces = 0;
        p.camera = camera; p.localX = location.getX(); p.localY = location.getY(); p.height = height; p.orientation = t.orientation();
        p.boundingBox = mesh instanceof Model && ((Model) mesh).useBoundingBox();
        // A model partly at or behind the camera keeps its other vertices, as RuneLite's clickbox and hull skip such
        // faces; it used to fail whole, and its 2D fallback then drew RuneLite's shape over the scene.
        p.depth = ModelShapes.projectModel(camera, mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(),
            n, location.getX(), location.getY(), height, t.orientation(), p.x, p.y, PARTIAL_NEAR);
        float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
        p.partial = false;
        for (int i = 0; i < n; i++)
        {
            if (Float.isNaN(p.x[i])) { p.partial = true; continue; }
            minX = Math.min(minX, p.x[i]); maxX = Math.max(maxX, p.x[i]);
            minY = Math.min(minY, p.y[i]); maxY = Math.max(maxY, p.y[i]);
        }
        // Conservative margin includes the widest supported border and its mitres.
        float margin = 64 * pixel;
        int vx = client.getViewportXOffset(), vy = client.getViewportYOffset();
        int vw = client.getViewportWidth(), vh = client.getViewportHeight();
        p.viewport = Objects.hash(vx, vy, vw, vh);
        p.outside = maxX + margin < vx || maxY + margin < vy
            || minX - margin > vx + vw || minY - margin > vy + vh;
        // Actor models live in a shared client buffer: copy their faces now, into reused arrays, in case
        // another style of this NPC needs them after another actor's getModel(). Objects only for outlines.
        if (t.outline || t.npc != null) { copyFaces(mesh, p); }
        if (n == lastN && p.faces == lastFaces && p.faces > 0 && p.hiddenSum == lastHidden && p.viewport == lastViewport
            && Arrays.equals(p.x, 0, n, p.lastX, 0, n) && Arrays.equals(p.y, 0, n, p.lastY, 0, n))
        {
            p.clickbox = lastClickbox; p.loops = lastLoops; p.hull = lastHull;
        }
    }

    /**
     * Face topology for outlines, copied once per projection. NPCs copy it when projected; a static
     * object's hull projection gets it here when its outline follows.
     */
    private boolean faces(ModelTarget t, Projection p)
    {
        // Copied faces stay valid until the next projection; one copy attempt per frame otherwise.
        if (p.faces > 0) { return true; }
        if (p.facesFrame == frame) { return false; }
        Mesh<?> mesh = t.mesh();
        if (mesh == null || mesh.getVerticesCount() != p.n) { return false; }
        copyFaces(mesh, p);
        return p.faces > 0;
    }

    private void copyFaces(Mesh<?> mesh, Projection p)
    {
        p.facesFrame = frame;
        p.faces = 0;
        int faces = mesh.getFaceCount();
        if (p.outside || Float.isNaN(p.depth) || faces <= 0) { return; }
        if (p.a.length < faces) { p.a = new int[faces]; p.b = new int[faces]; p.c = new int[faces]; }
        System.arraycopy(mesh.getFaceIndices1(), 0, p.a, 0, faces);
        System.arraycopy(mesh.getFaceIndices2(), 0, p.b, 0, faces);
        System.arraycopy(mesh.getFaceIndices3(), 0, p.c, 0, faces);
        for (int f = 0; f < faces; f++)
        {
            // Topology that does not fit the projected vertices is not traced.
            if (p.a[f] < 0 || p.b[f] < 0 || p.c[f] < 0 || p.a[f] >= p.n || p.b[f] >= p.n || p.c[f] >= p.n) { return; }
        }
        int[] colors = mesh instanceof Model ? ((Model) mesh).getFaceColors3() : null;
        if (colors == null) { p.hidden = null; }
        else
        {
            if (p.hidden == null || p.hidden.length < faces) { p.hidden = new boolean[faces]; }
            for (int f = 0; f < faces; f++) { p.hidden[f] = f < colors.length && colors[f] == -2; }
        }
        long hiddenSum = 0;
        if (p.hidden != null) { for (int f = 0; f < faces; f++) { if (p.hidden[f]) { hiddenSum += f * 31L + 1; } } }
        p.hiddenSum = hiddenSum;
        p.faces = faces;
    }

    /** Whether every point of the current shape lies within reach of the given anchor. */
    private boolean within(float x, float y, float height, float reach)
    {
        for (int i = 0; i < outline.vertices; i++)
        {
            float dx = tx[i] - x, dy = ty[i] - y, dz = tz[i] - height;
            if (dx * dx + dy * dy + dz * dz > reach * reach) { return false; }
        }
        return true;
    }

    /**
     * Appends the current outline to the bucket of the anchor's tile, in world
     * coordinates. The scene object is written once per bucket in end().
     */
    private boolean draw(String key, LocalPoint anchor, int level, int anchorHeight, Color border, Color fill, boolean hasBorder)
    {
        // Through walls applies to every shape, tiles as well as hulls, clickboxes and outlines: all of them
        // then sit at the same place in front of the camera, so lighting renderers (117 HD: shadows, fog,
        // lights) shade them all alike.
        boolean throughWalls = shapeXray;
        // World points of the shape: on the ground for through walls (moved to the camera at draw time),
        // else just above it towards the camera. Later layers slightly nearer, so shapes never z-fight.
        if (tx.length < outline.vertices)
        {
            tx = new float[outline.vertices * 2]; ty = new float[outline.vertices * 2]; tz = new float[outline.vertices * 2];
        }
        // A mark through walls leaves the player uncovered: the part over its silhouette is cut out.
        PlayerCut cut = throughWalls && (playerPending || playerCut != null) && outline.faces <= MAX_CUT_FACES
            && outline.maxX > playerMinX && outline.minX < playerMaxX && outline.maxY > playerMinY && outline.minY < playerMaxY
            ? playerCut() : null;
        if (cut != null && outline.maxX > cut.minX && outline.minX < cut.maxX && outline.maxY > cut.minY && outline.minY < cut.maxY)
        {
            cutOutline.copyFrom(outline);
            cutOutline.cutOut(cut);
            if (cutOutline.faces == 0) { appended.add(key); return true; }
            // Cut into many pieces, a large mark could outgrow a scene object (twice, for a white layer); it is then
            // drawn whole rather than falling back to its 2D shape (which flickered as the player moved).
            if (carriers.fits(cutOutline.vertices * 2, cutOutline.faces * 2)) { outline.copyFrom(cutOutline); }
            else { cutSkipped++; }
            if (tx.length < outline.vertices)
            {
                tx = new float[outline.vertices * 2]; ty = new float[outline.vertices * 2]; tz = new float[outline.vertices * 2];
            }
        }
        place(throughWalls, false);
        WorldView top = client.getTopLevelWorldView();
        boolean shared = throughWalls && (xrayCameraNear || within(xrayAnchor.getX(), xrayAnchor.getY(), xrayHeight, XRAY_SHARED));
        if (shared)
        {
            // One object in the drawn zone nearest the camera: a tile hidden behind a wall may not be drawn
            // by the client. Shapes too far from it for one model get a through-walls object on their own tile.
            anchor = xrayAnchor;
            level = xrayLevel;
        }
        // Shapes beyond the loaded area hang on the nearest edge tile; the client only places objects inside it.
        int tileX = Math.max(0, Math.min(top.getSizeX() - 1, anchor.getX() >> 7));
        int tileY = Math.max(0, Math.min(top.getSizeY() - 1, anchor.getY() >> 7));
        // Floating marks under 117 HD stay under its shadow threshold.
        int cap = throughWalls ? floatingAlphaCap : 255;
        // The cap applies to the colour as a whole, before it is split into layers that blend to it.
        FlatModel.layers(border, Math.min(cap, hasBorder ? border.getAlpha() : 0), hdLightnessCap, borderLayers);
        FlatModel.layers(fill, Math.min(cap, fill.getAlpha()), hdLightnessCap, fillLayers);
        int borderHsl = borderLayers[0], borderAlpha = borderLayers[1], borderWhite = borderLayers[2];
        int fillHsl = fillLayers[0], fillAlpha = fillLayers[1], fillWhite = fillLayers[2];
        // The white layer: a second copy of the outline, just nearer the camera so it is drawn over the colour.
        boolean white = borderWhite > 0 || fillWhite > 0;
        // A single shape too large for any scene object is left out; a bucket past the carrier limits fails the whole frame.
        int addVertices = outline.vertices * (white ? 2 : 1), addFaces = outline.faces * (white ? 2 : 1);
        if (!carriers.fits(addVertices, addFaces)) { tooLarge++; return false; }
        if (!shared && !within(tileX * 128 + 64, tileY * 128 + 64, Terrain.heightOnLevel(top, tileX * 128 + 64, tileY * 128 + 64, level),
                throughWalls ? XRAY_REACH - 200 : MAX_RADIUS - 300)) { outOfReach++; return false; }
        // Through-walls shapes get their own buckets: they are moved to the camera, others are not.
        long base = ((long) level << 32) | ((long) tileX << 16) | tileY | (throughWalls ? 1L << 40 : 0) | (shared ? 1L << 39 : 0);
        // A full bucket continues in another object on the same tile, so no model exceeds the renderer's limits.
        Bucket b = null;
        for (long part = 0; ; part++)
        {
            long id = base | (part << 41);
            b = buckets.get(id);
            if (b == null) { b = new Bucket(tileX, tileY, level, anchor.getWorldView()); buckets.put(id, b); break; }
            if (carriers.fits(b.vertices + addVertices, b.faces + addFaces)) { break; }
        }
        b.ensure(b.vertices + addVertices, b.faces + addFaces);
        b.xray = throughWalls;
        b.shared = shared;
        append(b, layer, borderHsl, borderAlpha, fillHsl, fillAlpha);
        if (white)
        {
            if (!throughWalls) { place(false, true); }
            append(b, layer + 0.5f, FlatModel.WHITE, borderWhite, FlatModel.WHITE, fillWhite);
        }
        appended.add(key);
        return true;
    }

    /**
     * World points (tx, ty, tz) of the outline: on the ground for through walls (moved to the camera at draw time),
     * else just above it towards the camera, later layers slightly nearer so shapes never z-fight. The white layer
     * of a light colour is always a little nearer than its colour layer, also where the layer offset bottoms out.
     */
    private void place(boolean throughWalls, boolean white)
    {
        float layerBias = layer * 0.001f;
        for (int i = 0; i < outline.vertices; i++)
        {
            float d = outline.depth[i];
            float at = throughWalls ? d : d - Math.max(4, d * 0.01f) - Math.max(1, d * layerBias) - (white ? Math.max(0.5f, d * 0.0005f) : 0);
            camera.unproject(outline.x[i], outline.y[i], at, point);
            tx[i] = point[0]; ty[i] = point[1]; tz[i] = point[2];
        }
    }

    /** Adds the outline's vertices (tx, ty, tz) and its visible faces to the bucket. */
    private void append(Bucket b, float vertexLayer, int borderHsl, int borderAlpha, int fillHsl, int fillAlpha)
    {
        int first = b.vertices;
        for (int i = 0; i < outline.vertices; i++)
        {
            b.layer[first + i] = vertexLayer;
            b.wx[first + i] = tx[i]; b.wy[first + i] = ty[i]; b.wz[first + i] = tz[i];
        }
        b.vertices += outline.vertices;
        for (int f = 0; f < outline.faces; f++)
        {
            boolean isBorder = f < outline.borderFaces;
            int alpha = isBorder ? borderAlpha : fillAlpha;
            if (alpha <= 0) { continue; }
            int k = b.faces++;
            b.fa[k] = first + outline.a[f]; b.fb[k] = first + outline.b[f]; b.fc[k] = first + outline.c[f];
            b.color[k] = isBorder ? borderHsl : fillHsl;
            b.alpha[k] = alpha;
        }
    }

    /**
     * Walls and decorations with a second model: RuneLite's clickbox covers both, the float clickbox only the first,
     * so RuneLite's own is drawn for them.
     */
    private static boolean twoModels(TileObject object)
    {
        return object instanceof WallObject && ((WallObject) object).getRenderable2() != null
            || object instanceof DecorativeObject && ((DecorativeObject) object).getRenderable2() != null;
    }

    /**
     * The clickbox polygons from the projection: the bounding box hull for bounding-box models, else RuneLite's union of
     * face rectangles clipped to it (FloatClickbox). Null without face topology.
     */
    private List<float[]> clickbox(ModelTarget t, Projection p)
    {
        if (p.clickbox != null) { return p.clickbox; }
        long start = System.nanoTime();
        try { return makeClickbox(t, p); }
        finally { clickboxNanos += System.nanoTime() - start; }
    }

    private List<float[]> makeClickbox(ModelTarget t, Projection p)
    {
        if (p.boundingBox) { return p.boundsHull == null ? null : (p.clickbox = Collections.singletonList(p.boundsHull)); }
        if (!faces(t, p)) { return null; }
        int vx = client.getViewportXOffset();
        // As calculate2DBounds, which takes the viewport's x offset for its top edge too.
        return p.clickbox = FloatClickbox.of(p.x, p.y, p.a, p.b, p.c, p.faces, p.hidden, p.boundsHull,
            vx, vx, vx + client.getViewportWidth(), vx + client.getViewportHeight());
    }

    /**
     * The convex hull {x0, y0, ...} of the model's bounding box on the canvas (Perspective.calculateAABB), or null:
     * Model.getAABB as RuneLite takes it, or for unlit ModelData (no getAABB) the box of its vertices.
     */
    private float[] boundsHull(Mesh<?> mesh, int n, int localX, int localY, int height, int orientation)
    {
        if (n <= 0) { return null; }
        float x1, x2, y1, y2, z1, z2;
        AABB box = mesh instanceof Model ? ((Model) mesh).getAABB(orientation) : null;
        if (box != null)
        {
            // RuneLite's own box (Perspective.calculateAABB): it can be larger than the vertices, and a bounding-box
            // model's clickbox is exactly this box.
            x1 = box.getCenterX() - box.getExtremeX(); x2 = box.getCenterX() + box.getExtremeX();
            y1 = box.getCenterY() - box.getExtremeY(); y2 = box.getCenterY() + box.getExtremeY();
            z1 = box.getCenterZ() - box.getExtremeZ(); z2 = box.getCenterZ() + box.getExtremeZ();
        }
        else
        {
            // Unlit ModelData has no getAABB: the box of its vertices turned as ModelShapes.projectModel turns them.
            float[] vx = mesh.getVerticesX(), vy = mesh.getVerticesY(), vz = mesh.getVerticesZ();
            double angle = (orientation & 2047) * Math.PI / 1024;
            float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
            x1 = Float.MAX_VALUE; x2 = -Float.MAX_VALUE; y1 = x1; y2 = x2; z1 = x1; z2 = x2;
            for (int i = 0; i < n; i++)
            {
                float rx = vz[i] * sin + vx[i] * cos, rz = vz[i] * cos - vx[i] * sin;
                x1 = Math.min(x1, rx); x2 = Math.max(x2, rx);
                y1 = Math.min(y1, vy[i]); y2 = Math.max(y2, vy[i]);
                z1 = Math.min(z1, rz); z2 = Math.max(z2, rz);
            }
        }
        float[] bx = {x1, x2, x1, x2, x1, x2, x1, x2}, by = {y1, y1, y1, y1, y2, y2, y2, y2}, bz = {z1, z1, z2, z2, z1, z1, z2, z2};
        float[] cx = new float[8], cy = new float[8];
        // Corners at or behind the camera are left out, as the model's own vertices.
        if (Float.isNaN(ModelShapes.projectModel(camera, bx, by, bz, 8, localX, localY, height, 0, cx, cy, PARTIAL_NEAR)))
        { return null; }
        return hullOf(cx, cy, 8);
    }

    /** Points a drawn polygon keeps at most: its border and fill then always fit one scene object (see fit). */
    static final int MAX_POINTS = 256;

    /**
     * The polygon with at most MAX_POINTS points: simplified (Douglas-Peucker) with the smallest tolerance from half a
     * pixel up, doubling, that gets there. Close to the camera a clickbox or outline is large on screen and has
     * thousands of points; its border and fill then outgrew a scene object, the mark was left out and RuneLite's own
     * 2D shape was drawn instead. At that size a pixel or two is not visible. Unchanged when small enough.
     */
    static float[] fit(float[] p)
    {
        int n = p.length / 2;
        if (n <= MAX_POINTS) { return p; }
        // Split at the first point and the one farthest from it, and simplify both halves.
        int far = 0;
        double best = -1;
        for (int i = 1; i < n; i++)
        {
            double d = Math.hypot(p[i * 2] - p[0], p[i * 2 + 1] - p[1]);
            if (d > best) { best = d; far = i; }
        }
        boolean[] keep = new boolean[n];
        int[] stack = new int[2 * n + 4];
        for (double tolerance = 0.5; ; tolerance *= 2)
        {
            java.util.Arrays.fill(keep, false);
            keep[0] = keep[far] = true;
            int top = 0;
            stack[top++] = 0; stack[top++] = far;
            stack[top++] = far; stack[top++] = n;
            while (top > 0)
            {
                int to = stack[--top], from = stack[--top];
                double ax = p[from * 2], ay = p[from * 2 + 1], bx = p[(to % n) * 2], by = p[(to % n) * 2 + 1];
                double dx = bx - ax, dy = by - ay, length = Math.hypot(dx, dy);
                int worst = -1;
                double worstDistance = tolerance;
                for (int i = from + 1; i < to; i++)
                {
                    double qx = p[i * 2] - ax, qy = p[i * 2 + 1] - ay;
                    double d = length < 1e-9 ? Math.hypot(qx, qy) : Math.abs(dx * qy - dy * qx) / length;
                    if (d > worstDistance) { worstDistance = d; worst = i; }
                }
                if (worst >= 0)
                {
                    keep[worst] = true;
                    stack[top++] = from; stack[top++] = worst;
                    stack[top++] = worst; stack[top++] = to;
                }
            }
            int count = 0;
            for (boolean k : keep) { if (k) { count++; } }
            if (count <= MAX_POINTS || tolerance > 64)
            {
                float[] out = new float[count * 2];
                for (int i = 0, k = 0; i < n; i++) { if (keep[i]) { out[k++] = p[i * 2]; out[k++] = p[i * 2 + 1]; } }
                return out;
            }
        }
    }

    static double signedArea(float[] p)
    {
        double area = 0;
        for (int i = 0, n = p.length / 2; i < n; i++) { int j = (i + 1) % n; area += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]; }
        return area / 2;
    }

    private static float[] largest(List<float[]> polygons)
    {
        float[] best = polygons.get(0);
        for (float[] p : polygons) { if (Math.abs(signedArea(p)) > Math.abs(signedArea(best))) { best = p; } }
        return best;
    }

    /** The convex hull of the projected points (NaN: not projected, left out), or null with fewer than three. */
    static float[] hullOf(float[] xs, float[] ys, int n)
    {
        int valid = 0;
        for (int i = 0; i < n; i++) { if (!Float.isNaN(xs[i])) { valid++; } }
        if (valid < 3) { return null; }
        float[] hull;
        if (valid == n) { hull = ModelShapes.convexHull(xs, ys, n); }
        else
        {
            float[] fx = new float[valid], fy = new float[valid];
            for (int i = 0, k = 0; i < n; i++) { if (!Float.isNaN(xs[i])) { fx[k] = xs[i]; fy[k++] = ys[i]; } }
            hull = ModelShapes.convexHull(fx, fy, valid);
        }
        return hull == null || hull.length < 6 ? null : hull;
    }

    /** The closed polygons of a canvas shape, as {x0, y0, x1, y1, ...}, without a repeated closing point. */
    static java.util.List<float[]> polygons(java.awt.Shape shape)
    {
        java.util.List<float[]> result = new ArrayList<>();
        float[] coords = new float[6];
        float[] current = new float[64];
        int size = 0;
        for (java.awt.geom.PathIterator it = shape.getPathIterator(null, 0.5); !it.isDone(); it.next())
        {
            int type = it.currentSegment(coords);
            if (type == java.awt.geom.PathIterator.SEG_MOVETO)
            {
                if (size >= 6) { result.add(Arrays.copyOf(current, size)); }
                size = 0;
            }
            if (type == java.awt.geom.PathIterator.SEG_MOVETO || type == java.awt.geom.PathIterator.SEG_LINETO)
            {
                if (size >= 2 && current[size - 2] == coords[0] && current[size - 1] == coords[1]) { continue; }
                if (size + 2 > current.length) { current = Arrays.copyOf(current, current.length * 2); }
                current[size++] = coords[0];
                current[size++] = coords[1];
            }
            if (type == java.awt.geom.PathIterator.SEG_CLOSE)
            {
                if (size >= 4 && current[0] == current[size - 2] && current[1] == current[size - 1]) { size -= 2; }
                if (size >= 6) { result.add(Arrays.copyOf(current, size)); }
                size = 0;
            }
        }
        if (size >= 6) { result.add(Arrays.copyOf(current, size)); }
        result.replaceAll(SceneShapeRenderer::clean);
        result.removeIf(p -> p.length < 6);
        return result;
    }

    /**
     * Drops repeated points, points on a straight line and spurs (a turn back
     * along the same line). The border mitre at such a point shoots out as a
     * spike, which showed as a stray edge on RuneLite's rectilinear clickboxes.
     */
    static float[] clean(float[] p)
    {
        int n = p.length / 2;
        boolean changed = true;
        while (changed && n >= 3)
        {
            changed = false;
            for (int i = 0; i < n && n >= 3; i++)
            {
                int prev = (i + n - 1) % n, next = (i + 1) % n;
                float ax = p[i * 2] - p[prev * 2], ay = p[i * 2 + 1] - p[prev * 2 + 1];
                float bx = p[next * 2] - p[i * 2], by = p[next * 2 + 1] - p[i * 2 + 1];
                float cross = ax * by - ay * bx;
                boolean duplicate = ax == 0 && ay == 0;
                if (duplicate || Math.abs(cross) < 1e-3f)
                {
                    System.arraycopy(p, (i + 1) * 2, p, i * 2, (n - i - 1) * 2);
                    n--;
                    changed = true;
                    i--;
                }
            }
        }
        return Arrays.copyOf(p, n * 2);
    }

    private boolean offscreen()
    {
        int vx = client.getViewportXOffset(), vy = client.getViewportYOffset();
        return outline.maxX < vx || outline.maxY < vy
            || outline.minX > vx + client.getViewportWidth() || outline.minY > vy + client.getViewportHeight();
    }

    /**
     * A tile, path or line shape larger than three viewports: none is, unless a point lies right at the camera (a
     * screen-wide square flashed at login). Such a shape is not drawn. Hulls and clickboxes of a building close by can
     * be that large, so they are not checked.
     */
    private boolean oversized()
    {
        return outline.maxX - outline.minX > 3 * client.getViewportWidth() || outline.maxY - outline.minY > 3 * client.getViewportHeight();
    }

    private void ensure(int n)
    {
        if (px.length < n) { px = new float[n * 2]; py = new float[n * 2]; pd = new float[n * 2]; }
    }

    private void ensureHull(int n)
    {
        if (hx.length < n) { hx = new float[n * 2]; hy = new float[n * 2]; hd = new float[n * 2]; }
    }

    private boolean culled(String key)
    {
        appended.add(key);
        return false;
    }

    /** Ends a frame: writes one scene object per tile. Returns false if no carrier model could be made. */
    boolean end()
    {
        Iterator<Map.Entry<Long, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext())
        {
            Bucket b = it.next().getValue();
            if (unavailable || b.faces == 0)
            {
                if (unavailable) { b.hide(); } else { retire(b); }
                it.remove();
            }
        }
        // Projections of models not drawn this frame are dropped.
        projections.values().removeIf(p -> p.frame != frame);
        // Reclaim vacated tiles before allocating models for newly occupied tiles.
        for (Bucket b : buckets.values())
        {
            if (!unavailable && !write(b)) { unavailable = true; }
        }
        if (unavailable) { clear(); }
        return !unavailable;
    }

    private boolean write(Bucket b)
    {
        int anchorX = b.tileX * 128 + 64, anchorY = b.tileY * 128 + 64, anchorZ;
        if (b.shared)
        {
            anchorX = Math.max(b.tileX * 128, Math.min(b.tileX * 128 + 127, xrayAnchor.getX()));
            anchorY = Math.max(b.tileY * 128, Math.min(b.tileY * 128 + 127, xrayAnchor.getY()));
            anchorZ = xrayHeight;
        }
        else
        {
            float z = 0;
            for (int i = 0; i < b.vertices; i++) { z += b.wz[i]; }
            anchorZ = Math.round(z / b.vertices);
        }
        // Through walls: the geometry is pulled towards the camera, at most XRAY_REACH from the anchor.
        float extent = b.xray ? XRAY_REACH + 16 : 0;
        for (int i = 0; i < b.vertices && !b.xray; i++)
        {
            float dx = b.wx[i] - anchorX, dy = b.wy[i] - anchorY, dz = b.wz[i] - anchorZ;
            extent = Math.max(extent, Math.max((float) Math.sqrt((double) dx * dx + (double) dy * dy), Math.abs(dz)));
        }
        if (b.model == null || b.model.getVerticesCount() < b.vertices || b.model.getFaceCount() < b.faces || extent > b.radius)
        {
            // The model it had goes back to the spares for smaller objects.
            retire(b);
            // The smallest spare with room to grow, so the next frame's slightly larger shape still fits and a small
            // object does not take a large model, whose unused capacity is walked every frame.
            float wantRadius = b.xray ? extent : Math.min(MAX_RADIUS, extent * 1.25f);
            int wantVertices = (int) Math.ceil(b.vertices * 1.25f), wantFaces = (int) Math.ceil(b.faces * 1.25f);
            Spare spare = null;
            for (Spare candidate : spareCarriers)
            {
                if (candidate.radius >= wantRadius && candidate.model.getVerticesCount() >= wantVertices
                    && candidate.model.getFaceCount() >= wantFaces
                    && (spare == null || candidate.model.getVerticesCount() < spare.model.getVerticesCount()))
                { spare = candidate; }
            }
            if (spare != null)
            {
                spareCarriers.remove(spare);
                b.adopt(spare.model, spare.radius, spare.usedFaces, spare.usedVertices);
            }
            else
            {
                int radius = b.xray ? MAX_RADIUS : Math.min(MAX_RADIUS, Math.max(MIN_RADIUS, (int) Math.ceil(extent * 1.5f)));
                long start = System.nanoTime();
                Model created = carriers.create(Math.max(MIN_VERTICES, b.vertices * 2), Math.max(MIN_FACES, b.faces * 2), radius, true);
                newModelNanos += System.nanoTime() - start;
                carriersCreated++;
                if (created == null) { return false; }
                // Normals point straight up on every carrier: set once (see begin).
                FlatModel.normals(created, normalX, normalY, normalZ);
                // Carriers start with six extreme vertices from fixing their bounds.
                b.adopt(created, radius, 0, 6);
            }
        }
        Model m = b.model;
        b.anchorX = anchorX; b.anchorY = anchorY; b.anchorZ = anchorZ;
        float[] vx = m.getVerticesX(), vy = m.getVerticesY(), vz = m.getVerticesZ();
        // Unused vertices at the origin, inside the fixed bounds. Through walls, pullToCamera writes every vertex
        // itself (unused ones onto a used one): no passing through the origin, which the renderer may read meanwhile.
        if (!b.xray) { for (int i = b.vertices; i < b.usedVertices; i++) { vx[i] = 0; vy[i] = 0; vz[i] = 0; } }
        if (b.xray) { b.freeze(); b.pullToCamera(camera); }
        else
        {
            for (int i = 0; i < b.vertices; i++)
            {
                vx[i] = b.wx[i] - anchorX;
                vy[i] = b.wz[i] - anchorZ;
                vz[i] = b.wy[i] - anchorY;
            }
        }
        // Through walls parks every unused vertex on a used one: all go back to the origin on the next write.
        b.usedVertices = b.xray ? m.getVerticesCount() : b.vertices;
        int[] i1 = m.getFaceIndices1(), i2 = m.getFaceIndices2(), i3 = m.getFaceIndices3();
        for (int f = 0; f < b.faces; f++)
        {
            i1[f] = b.fa[f]; i2[f] = b.fb[f]; i3[f] = b.fc[f];
            FlatModel.paint(m, f, b.color[f], b.alpha[f]);
        }
        for (int f = b.faces; f < b.usedFaces; f++) { FlatModel.hide(m, f); }
        b.usedFaces = b.faces;
        FlatModel.bounds(m);
        b.setLocation(new LocalPoint(anchorX, anchorY, b.worldView), b.level);
        b.setZ(anchorZ);
        b.setOrientation(0);
        // The footprint radius, not the geometry's: the model's own fixed bounds cover the geometry.
        b.setRadius(FOOTPRINT_RADIUS);
        if (!client.isRuneLiteObjectRegistered(b)) { client.registerRuneLiteObject(b); }
        trace.submitted(m);
        return true;
    }

    /**
     * The local player's silhouette this frame, left uncovered by marks drawn through walls; null for none. Its model
     * is projected with this frame's camera (after begin) and traced as for outlines.
     */
    void cutAround(Player player)
    {
        playerCut = null;
        playerPending = false;
        if (player == null || !xray || unavailable) { return; }
        // On a boat the player's location is in the boat's world view, not the one projected here.
        if (player.getWorldView() != client.getTopLevelWorldView()) { return; }
        Model model = player.getModel();
        LocalPoint at = player.getLocalLocation();
        if (model == null || at == null) { return; }
        int n = model.getVerticesCount(), faces = model.getFaceCount();
        if (n <= 0 || faces <= 0) { return; }
        if (playerX.length < n) { playerX = new float[n * 2]; playerY = new float[n * 2]; }
        if (playerA.length < faces)
        {
            playerA = new int[faces * 2]; playerB = new int[faces * 2]; playerC = new int[faces * 2]; playerHidden = new boolean[faces * 2];
        }
        // As the client places an actor: on its footprint's height, raised by its animation.
        int height = Perspective.getFootprintTileHeight(client, at, player.getWorldView().getPlane(), player.getFootprintSize())
            - player.getAnimationHeightOffset();
        float depth = ModelShapes.projectModel(camera, model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(), n,
            at.getX(), at.getY(), height, player.getCurrentOrientation(), playerX, playerY, PARTIAL_NEAR);
        if (Float.isNaN(depth)) { return; }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++)
        {
            if (Float.isNaN(playerX[i])) { continue; }
            minX = Math.min(minX, playerX[i]); maxX = Math.max(maxX, playerX[i]);
            minY = Math.min(minY, playerY[i]); maxY = Math.max(maxY, playerY[i]);
        }
        if (!(minX < maxX)) { return; }
        // The model is the client's shared buffer: its faces are copied now, traced only when a mark needs it.
        System.arraycopy(model.getFaceIndices1(), 0, playerA, 0, faces);
        System.arraycopy(model.getFaceIndices2(), 0, playerB, 0, faces);
        System.arraycopy(model.getFaceIndices3(), 0, playerC, 0, faces);
        int[] colors = model.getFaceColors3();
        for (int f = 0; f < faces; f++)
        {
            playerHidden[f] = colors != null && f < colors.length && colors[f] == -2
                || playerA[f] < 0 || playerB[f] < 0 || playerC[f] < 0 || playerA[f] >= n || playerB[f] >= n || playerC[f] >= n;
            if (playerHidden[f]) { playerA[f] = playerB[f] = playerC[f] = 0; }
        }
        playerN = n; playerFaces = faces;
        playerMinX = minX - 1; playerMinY = minY - 1; playerMaxX = maxX + 1; playerMaxY = maxY + 1;
        playerPending = true;
    }

    /**
     * The player's cut, traced on first use in a frame: no trace when no mark lies over the player, and the last one
     * again while its projection is exactly the same (player and camera still, between animation frames).
     */
    private PlayerCut playerCut()
    {
        if (!playerPending) { return playerCut; }
        playerPending = false;
        int n = playerN;
        if (lastPlayerCut != null && n == lastPlayerN && playerFaces == lastPlayerFaces
            && Arrays.equals(playerX, 0, n, lastPlayerX, 0, n) && Arrays.equals(playerY, 0, n, lastPlayerY, 0, n))
        {
            return playerCut = lastPlayerCut;
        }
        long start = System.nanoTime();
        playerCut = PlayerCut.of(Silhouette.trace(playerX, playerY, playerA, playerB, playerC, playerFaces, playerHidden, playerScratch));
        playerCutNanos += System.nanoTime() - start;
        if (lastPlayerX.length < n) { lastPlayerX = new float[playerX.length]; lastPlayerY = new float[playerX.length]; }
        System.arraycopy(playerX, 0, lastPlayerX, 0, n);
        System.arraycopy(playerY, 0, lastPlayerY, 0, n);
        lastPlayerN = n; lastPlayerFaces = playerFaces; lastPlayerCut = playerCut;
        return playerCut;
    }

    private long playerCutNanos;

    /** Diagnostics: shapes left out (too large, out of reach) and cuts skipped since the last reset, or empty. */
    String leftOut() { return tooLarge + outOfReach + cutSkipped == 0 ? "" : ", left out: " + tooLarge + " too large, " + outOfReach + " out of reach, " + cutSkipped + " uncut"; }

    void resetLeftOut() { tooLarge = outOfReach = cutSkipped = 0; }

    /** Diagnostics: time spent tracing the player's silhouette this frame. */
    long playerCutNanos() { return playerCutNanos; }

    /** Whether the shape with this key is in the scene this frame. */
    boolean drawn(String key) { return !unavailable && appended.contains(key); }

    /** Takes every shape out of the scene; their models stay as spares for the next frames. */
    void clear()
    {
        for (Bucket b : buckets.values()) { retire(b); }
        buckets.clear();
        silhouetteScratch.bits = new long[0];
        projections.clear();
        appended.clear();
    }

    /** As clear, and the models are dropped too. */
    void reset() { clear(); spareCarriers.clear(); carriers.reset(); }

    /**
     * The clickbox drawn last frame for this NPC, canvas polygons (even-odd), or null when none was made from its
     * projection: RuneLite's own (Perspective.getClickbox) fetched the NPC's model again and was costly.
     */
    List<float[]> lastClickbox(NPC npc)
    {
        Projection p = projections.get(npc);
        return p == null ? null : p.clickbox;
    }

    /** Diagnostics: carrier models created so far. */
    int carriersCreated() { return carriersCreated; }

    /** All shapes of one tile and level, merged into one scene object. */
    private final class Bucket extends RuneLiteObjectController
    {
        final int tileX, tileY, level, worldView;
        Model model;
        int radius, usedFaces, usedVertices, vertices, faces;
        float[] wx = new float[64], wy = new float[64], wz = new float[64];
        float[] layer = new float[64];
        int[] fa = new int[96], fb = new int[96], fc = new int[96], color = new int[96], alpha = new int[96];
        boolean xray, shared;
        int anchorX, anchorY, anchorZ;

        /**
         * What the renderer may read while HD Tile Markers writes the next frame: getModel() can run
         * on another thread, so it works on this copy, taken when the frame is written.
         */
        private Model frozenModel;
        private float[] fx = new float[0], fy = fx, fz = fx;
        private float[] fl = new float[0];
        private int frozenVertices, fAnchorX, fAnchorY, fAnchorZ;
        /** The camera of the last pull in getModel; NaN after freeze (a new frame), so the next call pulls. */
        private final double[] pulledWith = {Double.NaN, 0, 0, 0, 0, 0, 0, 0, 0, 0};

        private synchronized boolean pulledSame(double x, double y, double z, double pitch, double yaw, int scale, int vx, int vy, int vw, int vh)
        {
            double[] c = pulledWith;
            if (c[0] == x && c[1] == y && c[2] == z && c[3] == pitch && c[4] == yaw && c[5] == scale && c[6] == vx && c[7] == vy
                && c[8] == vw && c[9] == vh) { return true; }
            c[0] = x; c[1] = y; c[2] = z; c[3] = pitch; c[4] = yaw; c[5] = scale; c[6] = vx; c[7] = vy; c[8] = vw; c[9] = vh;
            return false;
        }

        /** Scratch for pullToCamera, which is synchronized. */
        private final float[] pullPoint = new float[3];

        synchronized void freeze()
        {
            if (fx.length < vertices) { fx = new float[wx.length]; fy = new float[wx.length]; fz = new float[wx.length]; fl = new float[wx.length]; }
            System.arraycopy(wx, 0, fx, 0, vertices); System.arraycopy(wy, 0, fy, 0, vertices);
            System.arraycopy(wz, 0, fz, 0, vertices); System.arraycopy(layer, 0, fl, 0, vertices);
            frozenModel = model; frozenVertices = vertices;
            pulledWith[0] = Double.NaN;
            fAnchorX = anchorX; fAnchorY = anchorY; fAnchorZ = anchorZ;
        }

        /**
         * Moves every vertex along its view ray to just in front of the camera, keeping
         * its screen position; farther tiles stay behind nearer ones, higher layers in front.
         */
        synchronized void pullToCamera(ModelShapes.Camera cam)
        {
            if (frozenModel == null || frozenVertices > frozenModel.getVerticesCount()) { return; }
            float[] vx = frozenModel.getVerticesX(), vy = frozenModel.getVerticesY(), vz = frozenModel.getVerticesZ();
            float[] p = pullPoint;
            for (int i = 0; i < frozenVertices; i++)
            {
                cam.project(fx[i], fy[i], fz[i], p);
                float bias = fl[i] * 0.25f;
                // The floor keeps the layer order (and the white layer in front) where the depth is clamped.
                float floor = XRAY_MIN_DEPTH + XRAY_LAYER_SPAN - bias;
                float at = Math.max(floor, XRAY_NEAR + p[2] * XRAY_SCALE - bias);
                at = Math.max(floor, withinReach(cam, fx[i], fy[i], fz[i], p[2], at, bias));
                cam.unproject(p[0], p[1], at, p);
                vx[i] = p[0] - fAnchorX;
                vy[i] = p[2] - fAnchorZ;
                vz[i] = p[1] - fAnchorY;
            }
            // The GPU plugin skips a see-through model whole if any vertex, used or not, is nearer the camera
            // than 50 (ModelUploader.uploadSortedModel). The anchor, where unused vertices would sit, can be at
            // the camera itself: they sit on the first vertex, which is surely in front of it.
            if (frozenVertices > 0)
            {
                for (int i = frozenVertices; i < frozenModel.getVerticesCount(); i++) { vx[i] = vx[0]; vy[i] = vy[0]; vz[i] = vz[0]; }
            }
        }

        /**
         * The depth to use on the ray from the camera to ground point g (at depth dg): the wanted depth,
         * or, when that lies farther than XRAY_REACH from the anchor, the nearest depth within it.
         */
        private float withinReach(ModelShapes.Camera cam, float gx, float gy, float gz, float dg, float wanted, float bias)
        {
            if (!(dg > 0)) { return wanted; }
            // Points on the ray: camera + (g - camera) * s, where s = depth / dg.
            float ux = cam.x - fAnchorX, uy = cam.y - fAnchorY, uz = cam.z - fAnchorZ;
            float vx = gx - cam.x, vy = gy - cam.y, vz = gz - cam.z;
            double a = vx * vx + vy * vy + vz * vz, b = 2.0 * (ux * vx + uy * vy + uz * vz);
            double c = ux * ux + uy * uy + uz * uz - (double) XRAY_REACH * XRAY_REACH;
            double disc = b * b - 4 * a * c;
            if (a <= 0 || disc < 0) { return dg; }
            double enter = (-b - Math.sqrt(disc)) / (2 * a), exit = (-b + Math.sqrt(disc)) / (2 * a);
            double s = wanted / dg;
            // A camera inside the reach: far points could be wanted beyond it.
            if (s > exit) { return (float) Math.max(XRAY_MIN_DEPTH, exit * dg - bias); }
            if (s >= enter) { return wanted; }
            // Clamped: keep the layer order among shapes at the same place.
            return (float) Math.min(dg, enter * dg) - bias;
        }

        Bucket(int tileX, int tileY, int level, int worldView)
        { this.tileX = tileX; this.tileY = tileY; this.level = level; this.worldView = worldView; }

        void ensure(int vertexCount, int faceCount)
        {
            if (wx.length < vertexCount)
            {
                int n = vertexCount * 2;
                wx = Arrays.copyOf(wx, n); wy = Arrays.copyOf(wy, n); wz = Arrays.copyOf(wz, n);
                layer = Arrays.copyOf(layer, n);
            }
            if (fa.length < faceCount)
            {
                int n = faceCount * 2;
                fa = Arrays.copyOf(fa, n); fb = Arrays.copyOf(fb, n); fc = Arrays.copyOf(fc, n);
                color = Arrays.copyOf(color, n); alpha = Arrays.copyOf(alpha, n);
            }
        }

        void hide() { if (client.isRuneLiteObjectRegistered(this)) { client.removeRuneLiteObject(this); } }

        /**
         * Gives up the model (null without one): a renderer thread still holding this object then finds no model to
         * pull, so it never writes into a model another object took over.
         */
        synchronized Spare release()
        {
            Spare spare = model == null ? null : new Spare(model, radius, usedFaces, usedVertices);
            model = null;
            frozenModel = null;
            return spare;
        }

        synchronized void adopt(Model m, int radius, int usedFaces, int usedVertices)
        {
            model = m; this.radius = radius; this.usedFaces = usedFaces; this.usedVertices = usedVertices;
        }

        @Override public Model getModel()
        {
            trace.modelRequested(client.isClientThread());
            Model model = this.model;
            if (model == null) { return null; }
            double cx = client.getCameraFpX(), cy = client.getCameraFpY(), cz = client.getCameraFpZ();
            double pitch = client.getCameraFpPitch(), yaw = client.getCameraFpYaw();
            int scale = client.getScale();
            // Borders are as wide as wanted for the camera the frame was built with. When the client draws with one
            // that jumped (at login the frame's camera was not set yet, or a teleport), they became a screen-wide
            // flash of colour for a frame: nothing is drawn until the next frame is built with the new camera.
            ModelShapes.Camera built = camera;
            if (built != null && built.jumpedTo(cx, cy, cz, pitch, yaw, scale)) { return null; }
            // Near the camera, a camera that moved since the frame began is off by many pixels:
            // follow the camera the renderer is drawing with right now.
            if (xray)
            {
                int vx = client.getViewportXOffset(), vy = client.getViewportYOffset();
                int vw = client.getViewportWidth(), vh = client.getViewportHeight();
                // The renderer may ask several times per frame (117 HD: shadows, scene): pull again only when the
                // camera moved or the frame was rewritten since the last pull.
                if (!pulledSame(cx, cy, cz, pitch, yaw, scale, vx, vy, vw, vh))
                {
                    pullToCamera(new ModelShapes.Camera((float) cx, (float) cy, (float) cz, (float) pitch, (float) yaw, scale, vx, vy, vw, vh));
                    FlatModel.bounds(model);
                }
            }
            return model;
        }
    }
}
