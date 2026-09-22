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
    private final Map<NPC, Projection> projections = new IdentityHashMap<>();
    private final java.util.Deque<Bucket> spareCarriers = new ArrayDeque<>();
    private static final int MAX_SPARES = 64;

    /** Frame-local owned data: actor mesh arrays can be overwritten by another getModel(). */
    private static final class Projection
    {
        float[] x, y, hull;
        int[] a, b, c;
        boolean[] hidden;
        float depth;
        boolean outside;
        List<float[]> loops;
    }
    private final Silhouette.Scratch silhouetteScratch = new Silhouette.Scratch();
    private final ScreenOutline outline = new ScreenOutline();
    private final float[] point = new float[3];
    private float[] px = new float[64], py = new float[64], pd = new float[64];
    private ModelShapes.Camera camera;
    private float normalX, normalY, normalZ, pixel = 1;
    private boolean unavailable;
    private int carriersCreated;
    private int layer;
    /** Through walls: shapes are pulled towards the camera, gathered on the player's tile where possible. */
    private boolean xray;
    private LocalPoint xrayAnchor;
    private int xrayLevel;
    private static final float XRAY_NEAR = 200, XRAY_SCALE = 0.03f;
    /*
     * The GPU plugin draws nothing of a see-through model whose diameter is 6000 or more
     * (ModelUploader.uploadSortedModel; Zone.renderAlpha likewise). Carrier bounds are six extreme
     * vertices at +-radius on each axis, giving a diameter of about 2.83 x radius, so carriers stay
     * within MAX_RADIUS; through-walls points move towards the camera only as far as XRAY_REACH
     * from their object's anchor.
     */
    static final int MAX_RADIUS = 2000, XRAY_REACH = 1900;
    /** Ground points of a shape sharing the player's through-walls object stay this close to its anchor. */
    private static final int XRAY_SHARED = XRAY_REACH - 300;
    private float[] tx = new float[64], ty = new float[64], tz = new float[64];
    static final int HULL_LAYER = 12;

    @Inject
    SceneShapeRenderer(Client client, CarrierModels carriers, RenderTrace trace) { this.client = client; this.carriers = carriers; this.trace = trace; }

    /** Starts a frame. canvasPerPixel converts screen pixels to canvas units (1 / stretch scale). */
    void begin(ModelShapes.Camera camera, float canvasPerPixel)
    {
        begin(camera, canvasPerPixel, null, 0);
    }

    /**
     * Starts a frame. With xrayAnchor set (the player's tile), every shape is drawn
     * through walls: each vertex moves along its view ray towards the camera, keeping
     * its screen position and its order relative to other shapes.
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
        projections.clear();
        unavailable = false;
        for (Bucket b : buckets.values()) { b.vertices = 0; b.faces = 0; }
        // Normals point straight up (model y is down): lighting renderers such as 117 HD then shade
        // every face like flat ground, the same from every camera angle.
        normalX = 0;
        normalY = -1;
        normalZ = 0;
    }

    /** Draws a tile footprint; returns whether it is in the scene this frame. */
    boolean tile(Marker m)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (unavailable || m.point.getWorldView() != wv.getId()) { return hide(m.key); }
        if ((m.borderWidth <= 0 || m.color.getAlpha() == 0) && m.fill.getAlpha() == 0) { return culled(m.key); }
        layer = m.layer;
        if (m.dot) { return dot(wv, m); }
        if (m.quadX != null) { return quad(wv, m); }
        if (m.lineX != null) { return line(wv, m); }
        int w = m.width, h = m.height, n = 2 * (w + h);
        ensure(n);
        int x0 = m.point.getX() - w * 64, y0 = m.point.getY() - h * 64, k = 0;
        int level = Terrain.level(wv, m.point.getSceneX(), m.point.getSceneY(), m.plane);
        // Walk the perimeter counterclockwise, sampling height at every tile boundary.
        for (int i = 0; i < w; i++) { k = sample(wv, level, x0 + i * 128, y0, k); }
        for (int j = 0; j < h; j++) { k = sample(wv, level, x0 + w * 128, y0 + j * 128, k); }
        for (int i = w; i > 0; i--) { k = sample(wv, level, x0 + i * 128, y0 + h * 128, k); }
        for (int j = h; j > 0; j--) { k = sample(wv, level, x0, y0 + j * 128, k); }
        if (k < 0) { return hide(m.key); }
        int centerHeight = Terrain.heightOnLevel(wv, m.point.getX(), m.point.getY(), level);
        camera.project(m.point.getX(), m.point.getY(), centerHeight, point);
        boolean corners = m.cornerDivisor > 0;
        if (!(point[2] >= ModelShapes.NEAR) || !outline.build(px, py, pd, n, point[0], point[1], point[2],
            Math.max(0.01f, m.borderWidth * pixel))) { return hide(m.key); }
        if (offscreen()) { return culled(m.key); }
        if (!corners) { return draw(m.key, m.point, level, centerHeight, m.color, m.fill, m.borderWidth > 0); }
        // Corners only: the fill covers the whole footprint, the border only its corners.
        if (m.fill.getAlpha() > 0) { draw(m.key, m.point, level, centerHeight, m.color, m.fill, false); }
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
                draw(m.key, m.point, level, centerHeight, m.color, Marker.NO_FILL, true);
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
        if (k < 0) { return hide(m.key); }
        int centerHeight = Terrain.heightOnLevel(wv, m.point.getX(), m.point.getY(), level);
        camera.project(m.point.getX(), m.point.getY(), centerHeight, point);
        if (!(point[2] >= ModelShapes.NEAR) || !outline.build(px, py, pd, n, point[0], point[1], point[2],
            Math.max(0.01f, m.borderWidth * pixel))) { return hide(m.key); }
        if (offscreen()) { return culled(m.key); }
        return draw(m.key, m.point, level, centerHeight, m.color, m.fill, m.borderWidth > 0);
    }

    /** An open polyline on the ground, each vertex at the terrain height of its own point. */
    private boolean line(WorldView wv, Marker m)
    {
        int n = m.lineX.length;
        if (n < 2 || m.borderWidth <= 0) { return hide(m.key); }
        ensure(n);
        int level = Terrain.level(wv, m.point.getSceneX(), m.point.getSceneY(), m.plane), k = 0;
        for (int i = 0; i < n && k >= 0; i++) { k = sample(wv, level, Math.max(0, m.lineX[i]), Math.max(0, m.lineY[i]), k); }
        if (k < 0 || !outline.buildStrip(px, py, pd, n, Math.max(0.01f, m.borderWidth * pixel))) { return hide(m.key); }
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
        if (!(point[2] >= ModelShapes.NEAR)) { return hide(m.key); }
        float radius = 4 * pixel;
        for (int i = 0; i < n; i++)
        {
            double angle = i * 2 * Math.PI / n;
            px[i] = point[0] + (float) Math.cos(angle) * radius;
            py[i] = point[1] + (float) Math.sin(angle) * radius;
            pd[i] = point[2];
        }
        if (!outline.build(px, py, pd, n, point[0], point[1], point[2], Math.max(0.01f, m.borderWidth * pixel)))
        { return hide(m.key); }
        if (offscreen()) { return culled(m.key); }
        return draw(m.key, m.point, level, height, m.color, m.fill, m.borderWidth > 0);
    }

    private int sample(WorldView wv, int level, int x, int y, int k)
    {
        if (k < 0) { return k; }
        // Corners on the far scene edge belong to the last tile.
        int limitX = wv.getSizeX() * 128 - 1, limitY = wv.getSizeY() * 128 - 1;
        camera.project(x, y, Terrain.heightOnLevel(wv, Math.min(x, limitX), Math.min(y, limitY), level), point);
        if (!(point[2] >= ModelShapes.NEAR)) { return -1; }
        px[k] = point[0]; py[k] = point[1]; pd[k] = point[2];
        return k + 1;
    }

    /** Draws a model hull or clickbox; returns whether it is in the scene this frame. */
    boolean model(ModelTarget t)
    {
        layer = HULL_LAYER;
        LocalPoint location = t.location();
        if (unavailable || location == null || location.getWorldView() != client.getTopLevelWorldView().getId())
        { return hide(t.key); }
        int height = t.height(client);
        Projection projected = t.npc == null ? null : projections.get(t.npc);
        if (projected == null)
        {
            Mesh<?> mesh = t.mesh();
            if (mesh == null) { return hide(t.key); }
            projected = project(t, mesh, location, height);
            if (t.npc != null) { projections.put(t.npc, projected); }
        }
        float depth = projected.depth;
        if (Float.isNaN(depth)) { return hide(t.key); }
        if (projected.outside && !t.clickbox)
        {
            // Handled by the scene route: do not repeat this work in the 2D fallback.
            return culled(t.key);
        }
        float[] hull;
        if (t.outline)
        {
            if (projected.a.length == 0) { return hide(t.key); }
            if (projected.loops == null)
            {
                projected.loops = Silhouette.trace(projected.x, projected.y, projected.a, projected.b,
                    projected.c, projected.a.length, projected.hidden, silhouetteScratch);
            }
            List<float[]> loops = projected.loops;
            int level = Terrain.level(client.getTopLevelWorldView(), location.getSceneX(), location.getSceneY(), t.plane());
            boolean any = false;
            for (float[] loop : loops)
            {
                int h = loop.length / 2;
                float[] hx = new float[h], hy = new float[h], hd = new float[h];
                for (int i = 0; i < h; i++) { hx[i] = loop[i * 2]; hy[i] = loop[i * 2 + 1]; hd[i] = depth; }
                if (outline.buildPolygon(hx, hy, hd, h, Math.max(0.01f, t.borderWidth * pixel), true) && !offscreen())
                {
                    draw(t.key, location, level, height, t.color, Marker.NO_FILL, true);
                    any = true;
                }
            }
            return any || hide(t.key);
        }
        if (t.clickbox)
        {
            // RuneLite's own clickbox: an AABB shape for bounding-box models, otherwise a
            // union of per-face screen rectangles. Drawn as is, at the model's nearest depth.
            java.awt.Shape shape = t.shape();
            if (shape == null) { return hide(t.key); }
            int level = Terrain.level(client.getTopLevelWorldView(), location.getSceneX(), location.getSceneY(), t.plane());
            boolean any = false;
            for (float[] polygon : polygons(shape))
            {
                int h = polygon.length / 2;
                float[] hx = new float[h], hy = new float[h], hd = new float[h];
                for (int i = 0; i < h; i++) { hx[i] = polygon[i * 2]; hy[i] = polygon[i * 2 + 1]; hd[i] = depth; }
                if (outline.buildPolygon(hx, hy, hd, h, Math.max(0.01f, t.borderWidth * pixel)) && !offscreen())
                {
                    draw(t.key, location, level, height, t.color, t.fill, t.borderWidth > 0);
                    any = true;
                }
            }
            return any || hide(t.key);
        }
        else
        {
            if (projected.hull == null)
            {
                projected.hull = ModelShapes.convexHull(projected.x, projected.y, projected.x.length);
            }
            hull = projected.hull;
        }
        if (hull == null || hull.length < 6) { return hide(t.key); }
        int h = hull.length / 2;
        float[] hx = new float[h], hy = new float[h], hd = new float[h];
        float cx = 0, cy = 0;
        for (int i = 0; i < h; i++) { hx[i] = hull[i * 2]; hy[i] = hull[i * 2 + 1]; hd[i] = depth; cx += hx[i]; cy += hy[i]; }
        // Just in front of the nearest vertex, so the shape covers the model like the 2D overlay does.
        if (!outline.build(hx, hy, hd, h, cx / h, cy / h, depth, Math.max(0.01f, t.borderWidth * pixel)))
        { return hide(t.key); }
        if (offscreen()) { return culled(t.key); }
        int level = Terrain.level(client.getTopLevelWorldView(), location.getSceneX(), location.getSceneY(), t.plane());
        return draw(t.key, location, level, height, t.color, t.fill, t.borderWidth > 0);
    }

    private Projection project(ModelTarget t, Mesh<?> mesh, LocalPoint location, int height)
    {
        Projection p = new Projection();
        int n = mesh.getVerticesCount();
        p.x = new float[n]; p.y = new float[n];
        p.depth = ModelShapes.projectModel(camera, mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(),
            n, location.getX(), location.getY(), height, t.orientation(), p.x, p.y);
        float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
        for (int i = 0; i < n; i++)
        {
            minX = Math.min(minX, p.x[i]); maxX = Math.max(maxX, p.x[i]);
            minY = Math.min(minY, p.y[i]); maxY = Math.max(maxY, p.y[i]);
        }
        // Conservative margin includes the widest supported border and its mitres.
        float margin = 64 * pixel;
        int vx = client.getViewportXOffset(), vy = client.getViewportYOffset();
        p.outside = maxX + margin < vx || maxY + margin < vy
            || minX - margin > vx + client.getViewportWidth() || minY - margin > vy + client.getViewportHeight();
        int faces = mesh.getFaceCount();
        // NPC styles share this snapshot; never retain the client's mutable model arrays.
        if (!p.outside && !Float.isNaN(p.depth) && (t.npc != null || t.outline) && faces > 0)
        {
            p.a = Arrays.copyOf(mesh.getFaceIndices1(), faces);
            p.b = Arrays.copyOf(mesh.getFaceIndices2(), faces);
            p.c = Arrays.copyOf(mesh.getFaceIndices3(), faces);
            if (mesh instanceof Model && ((Model) mesh).getFaceColors3() != null)
            {
                int[] colors = ((Model) mesh).getFaceColors3();
                p.hidden = new boolean[faces];
                for (int f = 0; f < faces && f < colors.length; f++) { p.hidden[f] = colors[f] == -2; }
            }
        }
        else { p.a = new int[0]; }
        return p;
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
        boolean throughWalls = xray;
        // World points of the shape: on the ground for through walls (moved to the camera at draw time),
        // else just above it towards the camera. Later layers slightly nearer, so shapes never z-fight.
        if (tx.length < outline.vertices)
        {
            tx = new float[outline.vertices * 2]; ty = new float[outline.vertices * 2]; tz = new float[outline.vertices * 2];
        }
        float layerBias = layer * 0.001f;
        for (int i = 0; i < outline.vertices; i++)
        {
            float d = outline.depth[i];
            float at = throughWalls ? d : d - Math.max(4, d * 0.01f) - Math.max(1, d * layerBias);
            camera.unproject(outline.x[i], outline.y[i], at, point);
            tx[i] = point[0]; ty[i] = point[1]; tz[i] = point[2];
        }
        WorldView top = client.getTopLevelWorldView();
        if (throughWalls && within(xrayAnchor.getX(), xrayAnchor.getY(),
            Terrain.heightOnLevel(top, xrayAnchor.getX(), xrayAnchor.getY(), xrayLevel), XRAY_SHARED))
        {
            // One object on the player's tile: a tile hidden behind a wall may not be drawn by the client.
            // Shapes too far from it for one model get a through-walls object on their own tile.
            anchor = xrayAnchor;
            level = xrayLevel;
        }
        // Shapes beyond the loaded area hang on the nearest edge tile; the client only places objects inside it.
        int tileX = Math.max(0, Math.min(top.getSizeX() - 1, anchor.getX() >> 7));
        int tileY = Math.max(0, Math.min(top.getSizeY() - 1, anchor.getY() >> 7));
        // A single shape too large for any scene object is left out.
        if (!carriers.fits(outline.vertices, outline.faces)
            || !within(tileX * 128 + 64, tileY * 128 + 64, Terrain.heightOnLevel(top, tileX * 128 + 64, tileY * 128 + 64, level),
                throughWalls ? XRAY_REACH - 200 : MAX_RADIUS - 300)) { return hide(key); }
        // Through-walls shapes get their own buckets: they are moved to the camera, others are not.
        long base = ((long) level << 32) | ((long) tileX << 16) | tileY | (throughWalls ? 1L << 40 : 0);
        // A full bucket continues in another object on the same tile, so no model exceeds the renderer's limits.
        Bucket b = null;
        for (long part = 0; ; part++)
        {
            long id = base | (part << 41);
            b = buckets.get(id);
            if (b == null) { b = new Bucket(tileX, tileY, level, anchor.getWorldView()); buckets.put(id, b); break; }
            if (carriers.fits(b.vertices + outline.vertices, b.faces + outline.faces)) { break; }
        }
        int first = b.vertices;
        b.ensure(first + outline.vertices, b.faces + outline.faces);
        b.xray = throughWalls;
        for (int i = 0; i < outline.vertices; i++)
        {
            b.layer[first + i] = layer;
            b.wx[first + i] = tx[i]; b.wy[first + i] = ty[i]; b.wz[first + i] = tz[i];
        }
        b.vertices += outline.vertices;
        int borderAlpha = hasBorder ? border.getAlpha() : 0, borderHsl = FlatModel.hsl(border), fillHsl = FlatModel.hsl(fill);
        for (int f = 0; f < outline.faces; f++)
        {
            boolean isBorder = f < outline.borderFaces;
            int alpha = isBorder ? borderAlpha : fill.getAlpha();
            if (alpha <= 0) { continue; }
            int k = b.faces++;
            b.fa[k] = first + outline.a[f]; b.fb[k] = first + outline.b[f]; b.fc[k] = first + outline.c[f];
            b.color[k] = isBorder ? borderHsl : fillHsl;
            b.alpha[k] = alpha;
        }
        appended.add(key);
        return true;
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

    private void ensure(int n)
    {
        if (px.length < n) { px = new float[n * 2]; py = new float[n * 2]; pd = new float[n * 2]; }
    }

    private boolean culled(String key)
    {
        appended.add(key);
        return false;
    }

    private boolean hide(String key)
    {
        // Shapes are rebuilt every frame; one that is not appended is simply not drawn.
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
                b.hide();
                if (!unavailable && b.model != null && spareCarriers.size() < MAX_SPARES) { spareCarriers.addLast(b); }
                it.remove();
            }
        }
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
        int anchorX = b.tileX * 128 + 64, anchorY = b.tileY * 128 + 64;
        float z = 0;
        for (int i = 0; i < b.vertices; i++) { z += b.wz[i]; }
        int anchorZ = Math.round(z / b.vertices);
        float extent = 0;
        for (int i = 0; i < b.vertices; i++)
        {
            float dx = b.wx[i] - anchorX, dy = b.wy[i] - anchorY, dz = b.wz[i] - anchorZ;
            extent = Math.max(extent, Math.max((float) Math.hypot(dx, dy), Math.abs(dz)));
        }
        // Through walls: the geometry is pulled towards the camera, at most XRAY_REACH from the anchor.
        if (b.xray) { extent = Math.max(extent, XRAY_REACH + 16); }
        if (b.model == null || b.model.getVerticesCount() < b.vertices || b.model.getFaceCount() < b.faces || extent > b.radius)
        {
            b.hide();
            Bucket spare = null;
            for (Iterator<Bucket> free = spareCarriers.iterator(); free.hasNext();)
            {
                Bucket candidate = free.next();
                if (candidate.radius >= extent && candidate.model.getVerticesCount() >= b.vertices
                    && candidate.model.getFaceCount() >= b.faces)
                { spare = candidate; free.remove(); break; }
            }
            if (spare != null)
            {
                b.model = spare.model; b.radius = spare.radius;
                b.usedFaces = spare.usedFaces; b.usedVertices = spare.usedVertices;
            }
            else
            {
                b.radius = b.xray ? MAX_RADIUS : Math.min(MAX_RADIUS, Math.max(MIN_RADIUS, (int) Math.ceil(extent * 1.5f)));
                b.model = carriers.create(Math.max(MIN_VERTICES, b.vertices * 2), Math.max(MIN_FACES, b.faces * 2), b.radius, true);
                carriersCreated++;
                b.usedFaces = 0;
                // Carriers start with six extreme vertices from fixing their bounds.
                b.usedVertices = 6;
                if (b.model == null) { return false; }
            }
        }
        Model m = b.model;
        b.anchorX = anchorX; b.anchorY = anchorY; b.anchorZ = anchorZ;
        float[] vx = m.getVerticesX(), vy = m.getVerticesY(), vz = m.getVerticesZ();
        if (b.xray) { b.pullToCamera(camera); }
        else
        {
            for (int i = 0; i < b.vertices; i++)
            {
                vx[i] = b.wx[i] - anchorX;
                vy[i] = b.wz[i] - anchorZ;
                vz[i] = b.wy[i] - anchorY;
            }
        }
        // Unused vertices at the origin, inside the fixed bounds.
        for (int i = b.vertices; i < b.usedVertices; i++) { vx[i] = 0; vy[i] = 0; vz[i] = 0; }
        b.usedVertices = b.vertices;
        int[] i1 = m.getFaceIndices1(), i2 = m.getFaceIndices2(), i3 = m.getFaceIndices3();
        for (int f = 0; f < b.faces; f++)
        {
            i1[f] = b.fa[f]; i2[f] = b.fb[f]; i3[f] = b.fc[f];
            FlatModel.paint(m, f, b.color[f], b.alpha[f]);
        }
        for (int f = b.faces; f < b.usedFaces; f++) { FlatModel.hide(m, f); }
        b.usedFaces = b.faces;
        FlatModel.normals(m, normalX, normalY, normalZ);
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

    /** Whether the shape with this key is in the scene this frame. */
    boolean drawn(String key) { return !unavailable && appended.contains(key); }

    void clear()
    {
        for (Bucket b : buckets.values()) { b.hide(); }
        buckets.clear();
        spareCarriers.clear();
        silhouetteScratch.grid = new boolean[0];
        projections.clear();
        appended.clear();
    }

    void reset() { clear(); carriers.reset(); }

    /** Diagnostics: carrier models created so far. */
    int carriersCreated() { return carriersCreated; }

    /** All shapes of one tile and level, merged into one scene object. */
    private final class Bucket extends RuneLiteObjectController
    {
        final int tileX, tileY, level, worldView;
        Model model;
        int radius, usedFaces, usedVertices, vertices, faces;
        float[] wx = new float[64], wy = new float[64], wz = new float[64];
        int[] layer = new int[64];
        int[] fa = new int[96], fb = new int[96], fc = new int[96], color = new int[96], alpha = new int[96];
        boolean xray;
        int anchorX, anchorY, anchorZ;

        /**
         * Moves every vertex along its view ray to just in front of the camera, keeping
         * its screen position; farther tiles stay behind nearer ones, higher layers in front.
         */
        void pullToCamera(ModelShapes.Camera cam)
        {
            float[] vx = model.getVerticesX(), vy = model.getVerticesY(), vz = model.getVerticesZ();
            float[] p = new float[3];
            for (int i = 0; i < vertices; i++)
            {
                cam.project(wx[i], wy[i], wz[i], p);
                float at = Math.max(ModelShapes.NEAR + 1, XRAY_NEAR + p[2] * XRAY_SCALE - layer[i] * 0.25f);
                at = withinReach(cam, wx[i], wy[i], wz[i], p[2], at, layer[i]);
                cam.unproject(p[0], p[1], at, p);
                vx[i] = p[0] - anchorX;
                vy[i] = p[2] - anchorZ;
                vz[i] = p[1] - anchorY;
            }
        }

        /**
         * The depth to use on the ray from the camera to ground point g (at depth dg): the wanted depth,
         * or, when that lies farther than XRAY_REACH from the anchor, the nearest depth within it.
         */
        private float withinReach(ModelShapes.Camera cam, float gx, float gy, float gz, float dg, float wanted, int layer)
        {
            if (!(dg > 0)) { return wanted; }
            // Points on the ray: camera + (g - camera) * s, where s = depth / dg.
            float ux = cam.x - anchorX, uy = cam.y - anchorY, uz = cam.z - anchorZ;
            float vx = gx - cam.x, vy = gy - cam.y, vz = gz - cam.z;
            double a = vx * vx + vy * vy + vz * vz, b = 2.0 * (ux * vx + uy * vy + uz * vz);
            double c = ux * ux + uy * uy + uz * uz - (double) XRAY_REACH * XRAY_REACH;
            double disc = b * b - 4 * a * c;
            if (a <= 0 || disc < 0) { return dg; }
            double enter = (-b - Math.sqrt(disc)) / (2 * a);
            double s = wanted / dg;
            if (s >= enter) { return wanted; }
            // Clamped: keep the layer order among shapes at the same place.
            return (float) Math.min(dg, enter * dg) - layer * 0.25f;
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

        @Override public Model getModel()
        {
            trace.modelRequested(client.isClientThread());
            // Near the camera, a camera that moved since the frame began is off by many pixels:
            // follow the camera the renderer is drawing with right now.
            if (xray && model != null && vertices > 0)
            {
                pullToCamera(new ModelShapes.Camera(client.getCameraFpX(), client.getCameraFpY(), client.getCameraFpZ(),
                    client.getCameraFpPitch(), client.getCameraFpYaw(), client.getScale(), client.getViewportXOffset(),
                    client.getViewportYOffset(), client.getViewportWidth(), client.getViewportHeight()));
                FlatModel.bounds(model);
            }
            return model;
        }
    }
}
