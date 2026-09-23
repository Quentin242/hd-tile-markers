package com.hdtilemarkers;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import org.junit.Assume;
import org.junit.Test;

/**
 * CPU cost of one frame of the scene renderer, with light stand-ins for the client (no Mockito
 * overhead). Skipped unless BENCH is set: {@code BENCH=1 ./gradlew test --tests '*RenderBenchmark*' -i}.
 * Measures only HD Tile Markers' own work; the GPU's drawing is not included.
 */
public class RenderBenchmark
{
    private static final ModelShapes.Camera CAMERA =
        new ModelShapes.Camera(6656, 6656 - 2400, -1800, 0.55f, 0, 700, 0, 0, 1600, 900);
    private final int[][][] heights = new int[4][105][105];
    private final byte[][][] settings = new byte[4][104][104];
    private final Set<Object> registered = Collections.newSetFromMap(new IdentityHashMap<>());
    private WorldView wv;
    private Client client;

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, java.util.function.BiFunction<String, Object[], Object> answer)
    {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("hashCode")) { return System.identityHashCode(proxy); }
            if (method.getName().equals("equals")) { return proxy == args[0]; }
            Object r = answer.apply(method.getName(), args);
            if (r != null) { return r; }
            Class<?> rt = method.getReturnType();
            // Copy methods (shallowCopy, cloneVertices, translate...) return the stand-in itself.
            if (rt.isInstance(proxy)) { return proxy; }
            if (rt == boolean.class) { return false; }
            if (rt == int.class) { return 0; }
            if (rt == float.class) { return 0f; }
            if (rt == double.class) { return 0d; }
            if (rt == long.class) { return 0L; }
            if (rt == short.class) { return (short) 0; }
            if (rt == byte.class) { return (byte) 0; }
            return null;
        });
    }

    /** A carrier-sized model with real arrays. */
    private static Model model(int vertices, int faces, boolean transparent)
    {
        float[] x = new float[vertices], y = new float[vertices], z = new float[vertices];
        int[] a = new int[faces], b = new int[faces], c = new int[faces], c1 = new int[faces], c2 = new int[faces], c3 = new int[faces];
        byte[] alpha = transparent ? new byte[faces] : null;
        short[] unlit = new short[faces];
        int[] nx = new int[vertices], ny = new int[vertices], nz = new int[vertices];
        return stub(Model.class, (name, args) -> {
            switch (name)
            {
                case "getVerticesCount": return vertices;
                case "getFaceCount": return faces;
                case "getVerticesX": return x;
                case "getVerticesY": return y;
                case "getVerticesZ": return z;
                case "getFaceIndices1": return a;
                case "getFaceIndices2": return b;
                case "getFaceIndices3": return c;
                case "getFaceColors1": return c1;
                case "getFaceColors2": return c2;
                case "getFaceColors3": return c3;
                case "getFaceTransparencies": return alpha;
                case "getUnlitFaceColors": return unlit;
                case "getVertexNormalsX": return nx;
                case "getVertexNormalsY": return ny;
                case "getVertexNormalsZ": return nz;
                case "getRadius": { float r = 0; for (float v : x) { r = Math.max(r, Math.abs(v)); } return (int) r; }
                default: return null;
            }
        });
    }

    /** An NPC-sized mesh: a closed sphere of about 1,000 vertices and 2,000 faces, 200 units tall. */
    private static Model mesh() { return mesh(1); }

    /** The sphere mesh scaled by size (5 is about a large boss). */
    private static Model mesh(float size)
    {
        int rings = 30, segments = 34, n = (rings + 1) * segments;
        float[] x = new float[n], y = new float[n], z = new float[n];
        for (int r = 0; r <= rings; r++)
        {
            double phi = Math.PI * r / rings;
            for (int s = 0; s < segments; s++)
            {
                double theta = 2 * Math.PI * s / segments;
                int i = r * segments + s;
                x[i] = (float) (size * 60 * Math.sin(phi) * Math.cos(theta));
                z[i] = (float) (size * 60 * Math.sin(phi) * Math.sin(theta));
                y[i] = (float) (size * (-100 - 100 * Math.cos(phi)));
            }
        }
        int faces = rings * segments * 2;
        int[] a = new int[faces], b = new int[faces], c = new int[faces], colors = new int[faces];
        int f = 0;
        for (int r = 0; r < rings; r++)
        {
            for (int s = 0; s < segments; s++)
            {
                int i = r * segments + s, j = r * segments + (s + 1) % segments;
                a[f] = i; b[f] = j; c[f] = i + segments; f++;
                a[f] = j; b[f] = j + segments; c[f] = i + segments; f++;
            }
        }
        return stub(Model.class, (name, args) -> {
            switch (name)
            {
                case "getVerticesCount": return n;
                case "getFaceCount": return faces;
                case "getVerticesX": return x;
                case "getVerticesY": return y;
                case "getVerticesZ": return z;
                case "getFaceIndices1": return a;
                case "getFaceIndices2": return b;
                case "getFaceIndices3": return c;
                case "getFaceColors3": return colors;
                default: return null;
            }
        });
    }

    private void setUp()
    {
        Random random = new Random(7);
        for (int[][] plane : heights) { for (int[] row : plane) { for (int i = 0; i < row.length; i++) { row[i] = -random.nextInt(200); } } }
        wv = stub(WorldView.class, (name, args) -> {
            switch (name)
            {
                case "getId": return -1;
                case "getSizeX": case "getSizeY": return 104;
                case "getTileHeights": return heights;
                case "getTileSettings": return settings;
                case "isTopLevel": return true;
                default: return null;
            }
        });
        ModelData seed = stub(ModelData.class, new java.util.function.BiFunction<String, Object[], Object>()
        {
            @Override public Object apply(String name, Object[] args)
            {
                switch (name)
                {
                    case "getVerticesCount": return 20;
                    case "getFaceCount": return 30;
                    case "getFaceIndices1": case "getFaceIndices2": case "getFaceIndices3": return new int[30];
                    case "getVerticesX": return new float[20];
                    default: return null;
                }
            }
        });
        ItemComposition item = stub(ItemComposition.class, (name, args) -> null);
        client = stub(Client.class, (name, args) -> {
            switch (name)
            {
                case "getTopLevelWorldView": case "getWorldView": return wv;
                case "getViewportWidth": return 1600;
                case "getViewportHeight": return 900;
                case "getItemDefinition": return item;
                case "loadModelData": return seed;
                case "mergeModels":
                {
                    int copies = ((ModelData[]) args[0]).length;
                    Model[] made = {null};
                    return stub(ModelData.class, (n2, a2) -> {
                        switch (n2)
                        {
                                                        case "getFaceIndices1": case "getFaceIndices2": case "getFaceIndices3": return new int[1];
                            case "getVerticesX": return new float[1];
                            case "light": return made[0] != null ? made[0] : (made[0] = model(20 * copies, 30 * copies, true));
                            default: return null;
                        }
                    });
                }
                case "isRuneLiteObjectRegistered": return registered.contains(args[0]);
                case "registerRuneLiteObject": registered.add(args[0]); return null;
                case "removeRuneLiteObject": registered.remove(args[0]); return null;
                case "isClientThread": return true;
                case "getCameraFpX": return CAMERA.x;
                case "getCameraFpY": return CAMERA.y;
                case "getCameraFpZ": return CAMERA.z;
                case "getCameraFpPitch": return 0.55f;
                case "getScale": return 700;
                default: return null;
            }
        });
    }

    private NPC npc(Model mesh, int x, int y)
    {
        LocalPoint lp = new LocalPoint(x, y, -1);
        return stub(NPC.class, (name, args) -> {
            switch (name)
            {
                case "getModel": return mesh;
                case "getLocalLocation": return lp;
                case "getWorldView": return wv;
                default: return null;
            }
        });
    }

    private double frameMillis(SceneShapeRenderer renderer, List<Marker> tiles, List<ModelTarget> models, LocalPoint xray)
    {
        for (int i = 0; i < 300; i++) { frame(renderer, tiles, models, xray); }
        int frames = 1000;
        long start = System.nanoTime();
        for (int i = 0; i < frames; i++) { frame(renderer, tiles, models, xray); }
        return (System.nanoTime() - start) / 1e6 / frames;
    }

    private void frame(SceneShapeRenderer renderer, List<Marker> tiles, List<ModelTarget> models, LocalPoint xray)
    {
        renderer.begin(CAMERA, 1, xray, 0);
        for (Marker m : tiles) { renderer.tile(m); }
        for (ModelTarget t : models) { renderer.model(t); }
        if (!renderer.end()) { throw new IllegalStateException("carrier unavailable"); }
        // The client asks every scene object for its model once per frame.
        for (Object o : registered.toArray()) { ((RuneLiteObjectController) o).getModel(); }
    }

    private List<Marker> tiles(int count)
    {
        List<Marker> result = new ArrayList<>();
        Random random = new Random(3);
        for (int i = 0; i < count; i++)
        {
            int x = 40 + random.nextInt(24), y = 45 + random.nextInt(24);
            Marker m = new Marker("t" + i, new LocalPoint(x * 128 + 64, y * 128 + 64, -1), 0, 1, 1, Color.CYAN, new Color(0, 255, 255, 50), 2, null, false);
            m.layer = Marker.GROUND;
            result.add(m);
        }
        return result;
    }

    @Test public void outlineStages()
    {
        Assume.assumeTrue(System.getenv("BENCH") != null);
        Model mesh = mesh();
        int n = mesh.getVerticesCount();
        float[] px = new float[n], py = new float[n];
        ModelShapes.projectModel(CAMERA, mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(), n, 6656, 6656, 0, 0, px, py);
        Silhouette.Scratch scratch = new Silhouette.Scratch();
        int faces = mesh.getFaceCount();
        List<float[]> loops = null;
        for (int i = 0; i < 2000; i++) { loops = Silhouette.trace(px, py, mesh.getFaceIndices1(), mesh.getFaceIndices2(), mesh.getFaceIndices3(), faces, null, scratch); }
        long t = System.nanoTime();
        for (int i = 0; i < 5000; i++) { loops = Silhouette.trace(px, py, mesh.getFaceIndices1(), mesh.getFaceIndices2(), mesh.getFaceIndices3(), faces, null, scratch); }
        double trace = (System.nanoTime() - t) / 1e6 / 5000;
        ScreenOutline outline = new ScreenOutline();
        int points = 0;
        for (float[] loop : loops) { points += loop.length / 2; }
        t = System.nanoTime();
        for (int i = 0; i < 5000; i++)
        {
            for (float[] loop : loops)
            {
                int h = loop.length / 2;
                float[] hx = new float[h], hy = new float[h], hd = new float[h];
                for (int k = 0; k < h; k++) { hx[k] = loop[k * 2]; hy[k] = loop[k * 2 + 1]; hd[k] = 1000; }
                outline.buildPolygon(hx, hy, hd, h, 2, true);
            }
        }
        double build = (System.nanoTime() - t) / 1e6 / 5000;
        System.out.printf("BENCH outline stages: trace %.3f ms, polygon %.3f ms (%d loop points)%n", trace, build, points);
    }

    @Test public void benchmark()
    {
        Assume.assumeTrue(System.getenv("BENCH") != null);
        setUp();
        LocalPoint player = new LocalPoint(52 * 128 + 64, 52 * 128 + 64, -1);
        Model mesh = mesh();
        List<ModelTarget> hulls = new ArrayList<>(), outlines = new ArrayList<>();
        for (int i = 0; i < 10; i++)
        {
            NPC npc = npc(mesh, (46 + i) * 128 + 64, 56 * 128 + 64);
            hulls.add(ModelTarget.npc("h" + i, npc, Color.RED, new Color(255, 0, 0, 40), 2));
            outlines.add(ModelTarget.npcOutline("o" + i, npc, Color.RED, 2));
        }
        List<Marker> none = Collections.emptyList();
        List<ModelTarget> noModels = Collections.emptyList();
        System.out.printf("BENCH 100 tiles, in the scene:        %.3f ms/frame%n", frameMillis(new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace()), tiles(100), noModels, null));
        System.out.printf("BENCH 100 tiles, through walls:       %.3f ms/frame%n", frameMillis(new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace()), tiles(100), noModels, player));
        System.out.printf("BENCH 500 tiles, through walls:       %.3f ms/frame%n", frameMillis(new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace()), tiles(500), noModels, player));
        System.out.printf("BENCH 10 NPC hulls (2k faces each):   %.3f ms/frame%n", frameMillis(new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace()), none, hulls, player));
        System.out.printf("BENCH 10 NPC outlines (2k faces each):%.3f ms/frame%n", frameMillis(new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace()), none, outlines, player));
        List<ModelTarget> manyOutlines = new ArrayList<>();
        for (int i = 0; i < 40; i++)
        {
            manyOutlines.add(ModelTarget.npcOutline("m" + i, npc(mesh, (42 + i % 20) * 128 + 64, (54 + i / 20 * 3) * 128 + 64), Color.RED, 2));
        }
        System.out.printf("BENCH 40 NPC outlines (budget):       %.3f ms/frame%n", frameMillis(new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace()), none, manyOutlines, player));
        List<ModelTarget> objects = new ArrayList<>();
        for (int i = 0; i < 20; i++)
        {
            objects.add(ModelTarget.objectOutline("s" + i, object((42 + i) * 128 + 64, 58 * 128 + 64), mesh, 0, 0, Color.GREEN, 2));
        }
        List<ModelTarget> bosses = new ArrayList<>();
        Model big = mesh(5);
        for (int i = 0; i < 3; i++)
        {
            bosses.add(ModelTarget.npcOutline("b" + i, npc(big, (48 + i * 4) * 128 + 64, 58 * 128 + 64), Color.RED, 2));
        }
        System.out.printf("BENCH 3 large boss outlines:          %.3f ms/frame%n", frameMillis(new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace()), none, bosses, player));
        System.out.printf("BENCH 20 object outlines, still cam:  %.3f ms/frame%n", frameMillis(new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace()), none, objects, player));
    }

    private GameObject object(int x, int y)
    {
        return stub(GameObject.class, (name, args) -> {
            switch (name)
            {
                case "getX": return x;
                case "getY": return y;
                case "getWorldView": return wv;
                default: return null;
            }
        });
    }
}
