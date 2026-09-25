package com.hdtilemarkers;

import java.awt.Color;
import java.util.Random;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class SceneShapeRendererTest
{
    // South of and above the test tile at scene (10, 10), looking north and down.
    private static final ModelShapes.Camera CAMERA =
        new ModelShapes.Camera(1344, 1344 - 2000, -1500, 0.6f, 0, 600, 0, 0, 1000, 700);
    private Client client;
    private WorldView wv;
    private int[][][] heights;
    private byte[][][] settings;
    private ModelData seed, copy;
    private Model carrier, alphaCarrier;

    @Before public void setup()
    {
        client = mock(Client.class);
        // The client draws with the camera the frame was built with (CAMERA), unless a test moves it.
        when(client.getCameraFpX()).thenReturn(1344f); when(client.getCameraFpY()).thenReturn(1344f - 2000);
        when(client.getCameraFpZ()).thenReturn(-1500f); when(client.getCameraFpPitch()).thenReturn(0.6f);
        when(client.getScale()).thenReturn(600);
        wv = mock(WorldView.class);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        when(client.getWorldView(-1)).thenReturn(wv);
        when(wv.getId()).thenReturn(-1);
        when(wv.getSizeX()).thenReturn(104);
        when(wv.getSizeY()).thenReturn(104);
        heights = new int[4][105][105];
        settings = new byte[4][104][104];
        Random random = new Random(7);
        for (int[][] plane : heights) { for (int[] row : plane) { for (int i = 0; i < row.length; i++) { row[i] = -random.nextInt(400); } } }
        when(wv.getTileHeights()).thenReturn(heights);
        when(wv.getTileSettings()).thenReturn(settings);
        when(client.getViewportWidth()).thenReturn(1000);
        when(client.getViewportHeight()).thenReturn(700);
        when(client.getItemDefinition(anyInt())).thenReturn(mock(ItemComposition.class));
        seed = mock(ModelData.class);
        when(seed.getVerticesCount()).thenReturn(20);
        when(seed.getFaceCount()).thenReturn(30);
        when(seed.getFaceIndices1()).thenReturn(new int[30]);
        when(seed.getFaceIndices2()).thenReturn(new int[30]);
        when(seed.getFaceIndices3()).thenReturn(new int[30]);
        when(seed.getVerticesX()).thenReturn(new float[20]);
        copy = mock(ModelData.class);
        when(seed.shallowCopy()).thenReturn(copy);
        when(copy.cloneVertices()).thenReturn(copy);
        when(copy.translate(anyInt(), anyInt(), anyInt())).thenReturn(copy);
        when(client.loadModelData(anyInt())).thenReturn(seed);
        // Opaque carriers have no transparency array; transparent ones get one from cloneTransparencies.
        carrier = model(200, 300, false);
        alphaCarrier = model(200, 300, true);
        ModelData merged = mock(ModelData.class), mergedAlpha = mock(ModelData.class);
        when(merged.cloneVertices()).thenReturn(merged);
        when(merged.cloneColors()).thenReturn(merged);
        when(merged.cloneTransparencies(true)).thenReturn(mergedAlpha);
        when(mergedAlpha.getFaceIndices1()).thenReturn(new int[1]);
        when(mergedAlpha.getFaceIndices2()).thenReturn(new int[1]);
        when(mergedAlpha.getFaceIndices3()).thenReturn(new int[1]);
        when(mergedAlpha.getVerticesX()).thenReturn(new float[1]);
        when(mergedAlpha.light()).thenReturn(alphaCarrier);
        when(merged.getFaceIndices1()).thenReturn(new int[1]);
        when(merged.getFaceIndices2()).thenReturn(new int[1]);
        when(merged.getFaceIndices3()).thenReturn(new int[1]);
        when(merged.getVerticesX()).thenReturn(new float[1]);
        when(merged.light()).thenReturn(carrier);
        when(client.mergeModels(any(ModelData[].class))).thenReturn(merged);
    }

    @Test public void terrainHeightMatchesClient()
    {
        settings[1][12][13] = Terrain.BRIDGE;
        Random random = new Random(3);
        for (int i = 0; i < 500; i++)
        {
            int x = random.nextInt(104 * 128), y = random.nextInt(104 * 128), plane = random.nextInt(3);
            assertEquals(Perspective.getTileHeight(client, new LocalPoint(x, y, -1), plane), Terrain.height(wv, x, y, plane));
        }
        // Bridge tile: height and render level both use the plane above.
        assertEquals(Perspective.getTileHeight(client, new LocalPoint(12 * 128 + 5, 13 * 128 + 9, -1), 0),
            Terrain.height(wv, 12 * 128 + 5, 13 * 128 + 9, 0));
        assertEquals(1, Terrain.level(wv, 12, 13, 0));
        assertEquals(0, Terrain.level(wv, 11, 13, 0));
    }

    @Test public void carrierCopiesAreOffsetSoMergingKeepsVertexCapacity()
    {
        assertNotNull(new CarrierModels(client).create(100, 100, 300, true));
        verify(copy).translate(0, 0, 0);
        verify(copy).translate(2048, 0, 0);
        verify(seed, never()).translate(anyInt(), anyInt(), anyInt());
    }

    @Test public void modelsMadeAheadServeTheFirstFrame()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.prewarm(1_000_000_000L);
        int made = renderer.carriersCreated();
        assertTrue(made > 0);
        Marker m = new Marker("t", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, new Color(0, 0, 0, 50), 2, null, true);
        renderer.begin(CAMERA, 1, null, 0);
        assertTrue(renderer.tile(m));
        assertTrue(renderer.end());
        assertEquals(made, renderer.carriersCreated());
    }

    @Test public void tileKeepsScreenPositionAndSitsAboveTerrain()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        Marker m = new Marker("t", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, new Color(0, 0, 0, 50), 2, null, true);
        renderer.begin(CAMERA, 1, null, 0);
        assertTrue(renderer.tile(m));
        assertTrue(renderer.end());
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        // Border and fill of one tile are one scene object.
        verify(client).registerRuneLiteObject(registered.capture());
        RuneLiteObjectController object = registered.getValue();
        assertSame(alphaCarrier, object.getModel());
        Model carrier = alphaCarrier;
        // Anchored at the tile centre, with a footprint inside that tile.
        int ox = object.getX(), oy = object.getY(), anchorHeight = object.getZ();
        assertEquals(1344, ox); assertEquals(1344, oy);
        assertTrue(object.getRadius() < 64);
        // Outer corner 0 is the south-west corner: same canvas point as the terrain corner, but nearer.
        float[] terrain = new float[3], drawn = new float[3];
        CAMERA.project(1280, 1280, Terrain.height(wv, 1280, 1280, 0), terrain);
        float[] vx = carrier.getVerticesX(), vy = carrier.getVerticesY(), vz = carrier.getVerticesZ();
        float[] corner = null;
        for (int i = 0; i < 4 && corner == null; i++)
        {
            CAMERA.project(ox + vx[i], oy + vz[i], anchorHeight + vy[i], drawn);
            // The mitred outer corner lies at most 4 half-widths from the terrain corner.
            if (Math.hypot(drawn[0] - terrain[0], drawn[1] - terrain[1]) <= 4) { corner = drawn.clone(); }
        }
        assertNotNull("an outer vertex lies at the mitred terrain corner", corner);
        assertTrue(corner[2] < terrain[2]);
        // 8 opaque border faces, then 4 fill faces at alpha 50, then hidden faces.
        assertEquals(FlatModel.hsl(Color.RED), carrier.getFaceColors1()[0]);
        // Visible faces carry their colour on all three corners (not -1, "flat": see FlatModel.paint).
        assertEquals(FlatModel.hsl(Color.RED), carrier.getFaceColors3()[7]);
        assertEquals((byte) 0, carrier.getFaceTransparencies()[7]);
        assertEquals((byte) (255 - 50), carrier.getFaceTransparencies()[8]);
        assertEquals(FlatModel.hsl(new Color(0, 0, 0, 50)), carrier.getFaceColors3()[11]);
        assertEquals(-2, carrier.getFaceColors3()[12]);
    }

    @Test public void rotatedQuadFollowsItsCorners()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        Marker m = new Marker("q", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.CYAN, Marker.NO_FILL, 1, null, false);
        // A diamond: the square rotated by 45 degrees.
        m.quadX = new int[]{1344, 1500, 1344, 1188};
        m.quadY = new int[]{1188, 1344, 1500, 1344};
        renderer.begin(CAMERA, 1, null, 0);
        assertTrue(renderer.tile(m));
        assertTrue(renderer.end());
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        verify(client).registerRuneLiteObject(registered.capture());
        RuneLiteObjectController object = registered.getValue();
        Model carrier = object.getModel();
        float[] terrain = new float[3], drawn = new float[3];
        CAMERA.project(1500, 1344, Terrain.height(wv, 1500, 1344, 0), terrain);
        float[] vx = carrier.getVerticesX(), vy = carrier.getVerticesY(), vz = carrier.getVerticesZ();
        boolean found = false;
        for (int i = 0; i < 64 && !found; i++)
        {
            CAMERA.project(object.getX() + vx[i], object.getY() + vz[i], object.getZ() + vy[i], drawn);
            found = Math.hypot(drawn[0] - terrain[0], drawn[1] - terrain[1]) <= 3;
        }
        assertTrue("a vertex lies at the east corner of the diamond", found);
    }

    @Test public void floatingOpaqueBordersStayUnderHdsShadowThreshold()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.floatingAlphaCap = SceneShapeRenderer.HD_CAP_OPAQUE_SHADOWS;
        renderer.begin(CAMERA, 1, new LocalPoint(1344, 1344, -1), 0);
        assertTrue(renderer.tile(new Marker("t", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, Marker.NO_FILL, 2, null, false)));
        assertTrue(renderer.end());
        int borders = 0;
        for (int f = 0; f < alphaCarrier.getFaceCount(); f++)
        {
            if (alphaCarrier.getFaceColors3()[f] == -2) { continue; }
            borders++;
            // Transparency byte 255 - alpha: at most 180 opaque.
            assertTrue(255 - (alphaCarrier.getFaceTransparencies()[f] & 0xff) <= 180);
        }
        assertEquals(8, borders);
    }

    @Test public void normalsPointUpFromEveryCameraAngle()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        Marker m = new Marker("t", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, new Color(0, 0, 0, 50), 2, null, true);
        ModelShapes.Camera turned = new ModelShapes.Camera(1344 + 1800, 1344, -1200, 0.8f, (float) (Math.PI / 2), 600, 0, 0, 1000, 700);
        for (ModelShapes.Camera camera : new ModelShapes.Camera[]{CAMERA, turned})
        {
            renderer.begin(camera, 1, null, 0);
            assertTrue(renderer.tile(m));
            assertTrue(renderer.end());
            // Lighting renderers shade by the normal: straight up (model y is down), whatever the camera.
            assertEquals(0, alphaCarrier.getVertexNormalsX()[0]);
            assertEquals(-256, alphaCarrier.getVertexNormalsY()[0]);
            assertEquals(0, alphaCarrier.getVertexNormalsZ()[0]);
        }
    }

    @Test public void openLinesAreDrawnInTheScene()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        Marker m = new Marker("aggro:0", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.YELLOW, Marker.NO_FILL, 1, null, false);
        m.lineX = new int[]{1280, 1408, 1536};
        m.lineY = new int[]{1280, 1280, 1280};
        renderer.begin(CAMERA, 1, null, 0);
        assertTrue(renderer.tile(m));
        assertTrue(renderer.end());
        verify(client).registerRuneLiteObject(any(RuneLiteObjectController.class));
    }

    @Test public void geometryStaysInsideTheCarrierBounds()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, null, 0);
        // A 5x5 footprint needs more than the minimum radius.
        assertTrue(renderer.tile(new Marker("big", new LocalPoint(1344, 1344, -1), 0, 5, 5, Color.RED, null, 2, null, false)));
        assertTrue(renderer.end());
        float[] x = alphaCarrier.getVerticesX(), y = alphaCarrier.getVerticesY(), z = alphaCarrier.getVerticesZ();
        for (int v = 0; v < alphaCarrier.getVerticesCount(); v++)
        {
            assertTrue(Math.hypot(x[v], z[v]) <= alphaCarrier.getRadius());
            assertTrue(Math.abs(y[v]) <= alphaCarrier.getRadius());
        }
    }

    @Test public void carrierWithBoundsAlreadyFixedIsRefused()
    {
        alphaCarrier.calculateBoundsCylinder();
        assertNull(new CarrierModels(client).create(100, 100, 300, true));
    }

    @Test public void shapesOnOneTileShareOneSceneObject()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, null, 0);
        // Two markers on tile (10, 10) and one on (12, 10).
        renderer.tile(new Marker("a", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, null, 2, null, false));
        renderer.tile(new Marker("b", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.BLUE, null, 1, null, false));
        renderer.tile(new Marker("c", new LocalPoint(1600, 1344, -1), 0, 1, 1, Color.RED, null, 2, null, false));
        assertTrue(renderer.end());
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        verify(client, times(2)).registerRuneLiteObject(registered.capture());
        for (RuneLiteObjectController o : registered.getAllValues())
        {
            // Footprint stays on its own tile: centre +- radius within the tile's 128 units.
            assertEquals(64, Math.floorMod(o.getX(), 128));
            assertTrue(o.getRadius() < 64);
        }
        assertTrue(renderer.drawn("a") && renderer.drawn("b") && renderer.drawn("c"));
    }

    @Test public void cornersOnlyDrawsFourCornerLinesAndTheFullFill()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        Marker m = new Marker("c", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, new Color(0, 0, 0, 50), 2, null, false);
        m.cornerDivisor = 5;
        renderer.begin(CAMERA, 1, null, 0);
        assertTrue(renderer.tile(m));
        assertTrue(renderer.end());
        // Fill: a centre fan of 4 faces. Corners: 4 L-strips of 4 faces each. Then hidden faces.
        int visible = 0, opaque = 0;
        for (int f = 0; f < alphaCarrier.getFaceCount(); f++)
        {
            if (alphaCarrier.getFaceColors3()[f] != -2)
            {
                visible++;
                if (alphaCarrier.getFaceTransparencies()[f] == 0) { opaque++; }
            }
        }
        assertEquals(4 + 16, visible);
        assertEquals(16, opaque);
    }

    @Test public void objectClickboxWorksWithUnlitModelDataAndNoAabb()
    {
        // A static scene object holding ModelData; no getAABB() is involved.
        ModelData data = mock(ModelData.class);
        float[] x = new float[8], y = new float[8], z = new float[8];
        for (int i = 0; i < 8; i++) { x[i] = (i & 1) == 0 ? -64 : 64; y[i] = (i & 2) == 0 ? 0 : -200; z[i] = (i & 4) == 0 ? -64 : 64; }
        when(data.getVerticesCount()).thenReturn(8);
        when(data.getVerticesX()).thenReturn(x); when(data.getVerticesY()).thenReturn(y); when(data.getVerticesZ()).thenReturn(z);
        GameObject object = mock(GameObject.class);
        when(object.getRenderable()).thenReturn(data);
        when(object.getWorldView()).thenReturn(wv);
        when(object.getX()).thenReturn(1344); when(object.getY()).thenReturn(1344);
        int ground = Terrain.height(wv, 1344, 1344, 0);
        when(object.getZ()).thenReturn(ground);
        // RuneLite's rectilinear clickbox: an L-shaped union of face rectangles.
        java.awt.Polygon clickbox = new java.awt.Polygon(new int[]{400, 520, 520, 460, 460, 400}, new int[]{200, 200, 260, 260, 330, 330}, 6);
        ModelTarget target = ModelTarget.object("object:1:clickbox", object, data, 0, 0, Color.YELLOW, new Color(255, 255, 0, 20), 2, true, () -> clickbox);
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, null, 0);
        assertTrue(renderer.model(target));
        assertTrue(renderer.end());
        assertTrue(renderer.drawn("object:1:clickbox"));
        // Border: 2 faces per edge; fill: an ear-clipped L of 4 triangles.
        int visible = 0;
        for (int f = 0; f < alphaCarrier.getFaceCount(); f++) { if (alphaCarrier.getFaceColors3()[f] != -2) { visible++; } }
        assertEquals(12 + 4, visible);
    }

    @Test public void tilesThroughWallsSitJustInFrontOfTheCameraOnThePlayersTile()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        LocalPoint playerTile = new LocalPoint(1600, 1600, -1);
        renderer.begin(CAMERA, 1, playerTile, 0);
        renderer.tile(new Marker("a", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, null, 2, null, false));
        renderer.tile(new Marker("b", new LocalPoint(1856, 1344, -1), 0, 1, 1, Color.RED, null, 2, null, false));
        assertTrue(renderer.end());
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        // Both tiles in one object, on the player's tile.
        verify(client).registerRuneLiteObject(registered.capture());
        RuneLiteObjectController o = registered.getValue();
        assertEquals(1600, o.getX()); assertEquals(1600, o.getY());
        // Every vertex is far in front of the ground, as near the camera as the carrier's reach allows,
        // and projects where the terrain corner does.
        float[] vx = alphaCarrier.getVerticesX(), vy = alphaCarrier.getVerticesY(), vz = alphaCarrier.getVerticesZ();
        float[] p = new float[3], terrain = new float[3];
        CAMERA.project(1280, 1280, Terrain.height(wv, 1280, 1280, 0), terrain);
        boolean cornerFound = false;
        for (int i = 0; i < 16; i++)
        {
            CAMERA.project(o.getX() + vx[i], o.getY() + vz[i], o.getZ() + vy[i], p);
            assertTrue(p[2] < terrain[2] - 1000);
            assertTrue(Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]) <= SceneShapeRenderer.XRAY_REACH + 16);
            if (Math.hypot(p[0] - terrain[0], p[1] - terrain[1]) <= 4) { cornerFound = true; }
        }
        assertTrue(cornerFound);
    }

    @Test public void throughWallsHangsAtTheCameraHeightWhenTheCameraIsNear()
    {
        // Renderers draw see-through models farthest first: at the camera's height, over the visible ground
        // nearest the camera, the marks come after nearer see-through objects such as tree leaves.
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        LocalPoint nearest = new LocalPoint(1344, 344, -1);
        renderer.begin(CAMERA, 1, nearest, 0);
        renderer.tile(new Marker("a", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, null, 2, null, false));
        renderer.tile(new Marker("b", new LocalPoint(2600, 2600, -1), 0, 1, 1, Color.RED, null, 2, null, false));
        assertTrue(renderer.end());
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        verify(client).registerRuneLiteObject(registered.capture());
        RuneLiteObjectController o = registered.getValue();
        assertEquals(1344, o.getX()); assertEquals(344, o.getY()); assertEquals(-1500, o.getZ());
        float[] vx = alphaCarrier.getVerticesX(), vy = alphaCarrier.getVerticesY(), vz = alphaCarrier.getVerticesZ();
        float[] p = new float[3], terrain = new float[3];
        CAMERA.project(1280, 1280, Terrain.height(wv, 1280, 1280, 0), terrain);
        boolean cornerFound = false;
        for (int i = 0; i < 32; i++)
        {
            assertTrue(Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]) <= SceneShapeRenderer.XRAY_REACH + 16);
            CAMERA.project(o.getX() + vx[i], o.getY() + vz[i], o.getZ() + vy[i], p);
            assertTrue(p[2] < terrain[2] - 1000);
            if (Math.hypot(p[0] - terrain[0], p[1] - terrain[1]) <= 4) { cornerFound = true; }
        }
        assertTrue(cornerFound);
    }

    @Test public void overlappingMarksThroughWallsAreDrawnInRankOrder()
    {
        // 117 HD draws see-through faces without writing depth, by distance and ties in face order: in rank order
        // and nearer per rank, the higher mark lies on top whatever the camera does, even drawn first.
        Color top = new Color(0, 255, 0, 120), bottom = new Color(255, 0, 0, 120);
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, new LocalPoint(1344, 344, -1), 0);
        Marker current = new Marker("current", new LocalPoint(1344, 1344, -1), 0, 1, 1, Marker.NO_FILL, top, 0, null, false);
        current.layer = Marker.CURRENT;
        Marker path = new Marker("path", new LocalPoint(1344, 1344, -1), 0, 3, 1, Marker.NO_FILL, bottom, 0, null, false);
        path.layer = Marker.EXTERNAL;
        assertTrue(renderer.tile(current));
        assertTrue(renderer.tile(path));
        assertTrue(renderer.end());
        int[] hsl = new int[3];
        FlatModel.layers(top, 120, false, hsl);
        int topHsl = hsl[0];
        FlatModel.layers(bottom, 120, false, hsl);
        int bottomHsl = hsl[0];
        int[] colors = alphaCarrier.getFaceColors3();
        int lastBottom = -1, firstTop = Integer.MAX_VALUE;
        for (int f = 0; f < colors.length; f++)
        {
            if (colors[f] == bottomHsl) { lastBottom = f; }
            if (colors[f] == topHsl) { firstTop = Math.min(firstTop, f); }
        }
        assertTrue(lastBottom >= 0 && firstTop < Integer.MAX_VALUE);
        assertTrue(lastBottom < firstTop);
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        verify(client).registerRuneLiteObject(registered.capture());
        RuneLiteObjectController o = registered.getValue();
        float[] vx = alphaCarrier.getVerticesX(), vy = alphaCarrier.getVerticesY(), vz = alphaCarrier.getVerticesZ();
        int[] i1 = alphaCarrier.getFaceIndices1();
        // One depth per layer, the higher nearer by far more than 117 HD's integer camera is off while moving: its
        // see-through sort and its depth test for opaque faces both keep the ranking.
        float[] p = new float[3];
        float topDepth = Float.NaN, bottomDepth = Float.NaN;
        for (int f = 0; f < colors.length; f++)
        {
            if (colors[f] != topHsl && colors[f] != bottomHsl) { continue; }
            int v = i1[f];
            CAMERA.project(o.getX() + vx[v], o.getY() + vz[v], o.getZ() + vy[v], p);
            if (colors[f] == topHsl) { topDepth = p[2]; } else { bottomDepth = p[2]; }
        }
        assertEquals(200.5f + (20 - Marker.CURRENT) * 6, topDepth, 0.05f);
        assertEquals(200.5f + (20 - Marker.EXTERNAL) * 6, bottomDepth, 0.05f);
    }

    @Test public void manyMarksThroughWallsStayInOneSceneObject()
    {
        // Carriers sized by their copies of the seed (20 vertices, 30 faces each), as merging really does.
        when(client.mergeModels(any(ModelData[].class))).thenAnswer(inv -> {
            int copies = inv.getArguments().length;
            ModelData merged = mock(ModelData.class), alpha = mock(ModelData.class);
            when(merged.cloneVertices()).thenReturn(merged);
            when(merged.cloneColors()).thenReturn(merged);
            when(merged.cloneTransparencies(true)).thenReturn(alpha);
            when(alpha.getFaceIndices1()).thenReturn(new int[1]);
            when(alpha.getFaceIndices2()).thenReturn(new int[1]);
            when(alpha.getFaceIndices3()).thenReturn(new int[1]);
            when(alpha.getVerticesX()).thenReturn(new float[1]);
            Model m = model(20 * copies, 30 * copies, true);
            when(alpha.light()).thenReturn(m);
            return merged;
        });
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, new LocalPoint(1344, 344, -1), 0);
        // More than half the largest carrier: one object filled past its usual room to spare, not two.
        int drawn = 0;
        for (int x = 0; x < 16; x++)
        {
            for (int y = 0; y < 16; y++)
            {
                Marker m = new Marker("t" + x + ":" + y, new LocalPoint(320 + x * 128, 704 + y * 128, -1), 0, 1, 1,
                    Color.RED, new Color(0, 0, 255, 80), 2, null, false);
                if (renderer.tile(m)) { drawn++; }
            }
        }
        assertTrue(drawn > 100);
        assertTrue(renderer.end());
        verify(client, times(1)).registerRuneLiteObject(any(RuneLiteObjectController.class));
    }

    @Test public void noThroughWallsVertexIsNearerThanTheGpuPluginAllows()
    {
        // The GPU plugin skips a see-through model whole if any vertex, used or not, is nearer than 50;
        // at the camera's height the anchor itself is at the camera.
        ModelShapes.Camera camera = new ModelShapes.Camera(1344, 344, -1500, 0.6f, 0, 600, 0, 0, 1000, 700);
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(camera, 1, new LocalPoint(1344, 344, -1), 0);
        renderer.tile(new Marker("a", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, null, 2, null, false));
        assertTrue(renderer.end());
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        verify(client).registerRuneLiteObject(registered.capture());
        RuneLiteObjectController o = registered.getValue();
        float[] vx = alphaCarrier.getVerticesX(), vy = alphaCarrier.getVerticesY(), vz = alphaCarrier.getVerticesZ();
        float[] p = new float[3];
        for (int i = 0; i < alphaCarrier.getVerticesCount(); i++)
        {
            camera.project(o.getX() + vx[i], o.getY() + vz[i], o.getZ() + vy[i], p);
            assertTrue("vertex " + i + " at depth " + p[2], p[2] >= 50);
        }
    }

    @Test public void lightColoursUnder117HdGetAWhiteLayer()
    {
        Color pink = new Color(255, 207, 207, 127);
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.hdLightnessCap = true;
        renderer.begin(CAMERA, 1, null, 0);
        assertTrue(renderer.tile(new Marker("t", new LocalPoint(1344, 1344, -1), 0, 1, 1, Marker.NO_FILL, pink, 0, null, false)));
        assertTrue(renderer.end());
        int[] colors = alphaCarrier.getFaceColors3();
        int capped = 0, white = 0;
        for (int f = 0; f < colors.length; f++)
        {
            if (colors[f] == FlatModel.WHITE) { white++; }
            else if (colors[f] != -2) { capped++; assertEquals(55, colors[f] & 127); }
        }
        assertEquals(4, capped);
        assertEquals(4, white);
    }

    @Test public void lightColoursStayOneLayerWithoutTheCap()
    {
        Color pink = new Color(255, 207, 207, 127);
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, null, 0);
        assertTrue(renderer.tile(new Marker("t", new LocalPoint(1344, 1344, -1), 0, 1, 1, Marker.NO_FILL, pink, 0, null, false)));
        assertTrue(renderer.end());
        int visible = 0;
        for (int c : alphaCarrier.getFaceColors3()) { if (c != -2) { visible++; assertEquals(FlatModel.hsl(pink), c); } }
        assertEquals(4, visible);
    }

    @Test public void hullsGoThroughWallsWithTheTiles()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        LocalPoint playerTile = new LocalPoint(1600, 1600, -1);
        renderer.begin(CAMERA, 1, playerTile, 0);
        renderer.tile(new Marker("a", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, null, 2, null, false));
        assertTrue(renderer.model(ModelTarget.npc("npc:hull", npc(triangle(), 1472), Color.BLUE, Marker.NO_FILL, 2)));
        assertTrue(renderer.end());
        // Without through walls the hull would hang on the NPC's own tile (1472, 1344). Now tile and hull share the
        // through-walls object on the player's tile, placed and lit alike.
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        verify(client, atLeastOnce()).registerRuneLiteObject(registered.capture());
        for (RuneLiteObjectController o : registered.getAllValues())
        {
            assertEquals(1600, o.getX()); assertEquals(1600, o.getY());
        }
    }

    @Test public void zoomedOutThroughWallsStaysWithinTheGpuAlphaLimit()
    {
        // A camera 5000 units away: the old pull reached the camera and the GPU plugin skipped the model.
        ModelShapes.Camera far = new ModelShapes.Camera(1600, 1600 - 4000, -3000, 0.64f, 0, 600, 0, 0, 1000, 700);
        // getModel() pulls with the client's camera at draw time.
        when(client.getCameraFpX()).thenReturn(1600f); when(client.getCameraFpY()).thenReturn(1600f - 4000);
        when(client.getCameraFpZ()).thenReturn(-3000f); when(client.getCameraFpPitch()).thenReturn(0.64f);
        when(client.getCameraFpYaw()).thenReturn(0f); when(client.getScale()).thenReturn(600);
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        LocalPoint playerTile = new LocalPoint(1600, 1600, -1);
        renderer.begin(far, 1, playerTile, 0);
        assertTrue(renderer.tile(new Marker("a", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, Marker.NO_FILL, 2, null, false)));
        assertTrue(renderer.tile(new Marker("b", new LocalPoint(1856, 1856, -1), 0, 1, 1, Color.RED, Marker.NO_FILL, 2, null, false)));
        assertTrue(renderer.end());
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        verify(client, atLeastOnce()).registerRuneLiteObject(registered.capture());
        for (RuneLiteObjectController o : registered.getAllValues())
        {
            Model m = o.getModel();
            float[] vx = m.getVerticesX(), vy = m.getVerticesY(), vz = m.getVerticesZ();
            for (int i = 0; i < vx.length; i++)
            {
                // Inside the bounds cylinder the carrier's extreme vertices fix.
                assertTrue("vertex " + i, Math.hypot(vx[i], vz[i]) <= SceneShapeRenderer.MAX_RADIUS + 0.5);
                assertTrue("vertex " + i, Math.abs(vy[i]) <= SceneShapeRenderer.MAX_RADIUS + 0.5);
            }
        }
    }

    @Test public void carrierDiameterStaysBelowTheGpuSortLimit()
    {
        // Model.calculateBoundsCylinder over the extremes (+-r on each axis): radius r*sqrt2, diameter twice that.
        double radius = Math.ceil(Math.sqrt(2.0) * SceneShapeRenderer.MAX_RADIUS);
        assertTrue(2 * radius + 2 < 6000);
    }

    @Test public void withoutThroughWallsHullsStayOnTheirOwnTile()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, null, 0);
        assertTrue(renderer.model(ModelTarget.npc("npc:hull", npc(triangle(), 1472), Color.BLUE, Marker.NO_FILL, 2)));
        assertTrue(renderer.end());
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        verify(client).registerRuneLiteObject(registered.capture());
        assertEquals(1472, registered.getValue().getX());
    }

    @Test public void throughWallsFollowsTheCameraAtDrawTime()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, new LocalPoint(1600, 1600, -1), 0);
        renderer.tile(new Marker("a", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, null, 2, null, false));
        assertTrue(renderer.end());
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        verify(client).registerRuneLiteObject(registered.capture());
        RuneLiteObjectController o = registered.getValue();
        // The camera turns before the frame is drawn: the model follows the camera at draw time.
        float yaw = 0.2f, pitch = 0.65f;
        when(client.getCameraFpX()).thenReturn(1344f + 150); when(client.getCameraFpY()).thenReturn(1344f - 2000);
        when(client.getCameraFpZ()).thenReturn(-1500f);
        when(client.getCameraFpYaw()).thenReturn(yaw); when(client.getCameraFpPitch()).thenReturn(pitch);
        when(client.getScale()).thenReturn(600);
        ModelShapes.Camera moved = new ModelShapes.Camera(1344f + 150, 1344f - 2000, -1500f, pitch, yaw, 600, 0, 0, 1000, 700);
        Model m = o.getModel();
        float[] terrain = new float[3], p = new float[3];
        moved.project(1280, 1280, Terrain.height(wv, 1280, 1280, 0), terrain);
        boolean cornerFound = false;
        for (int i = 0; i < 16; i++)
        {
            moved.project(o.getX() + m.getVerticesX()[i], o.getY() + m.getVerticesZ()[i], o.getZ() + m.getVerticesY()[i], p);
            if (Math.hypot(p[0] - terrain[0], p[1] - terrain[1]) <= 4) { cornerFound = true; }
        }
        assertTrue(cornerFound);
    }

    @Test public void bridgeTilesRenderOnThePlaneAbove()
    {
        settings[1][10][10] = Terrain.BRIDGE;
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, null, 0);
        assertTrue(renderer.tile(new Marker("t", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, null, 2, null, false)));
        assertTrue(renderer.end());
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        verify(client).registerRuneLiteObject(registered.capture());
        assertEquals(1, registered.getValue().getLevel());
    }

    @Test public void unsubmittedShapesAreRemovedAtFrameEnd()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        Marker m = new Marker("t", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, null, 2, null, false);
        renderer.begin(CAMERA, 1, null, 0);
        renderer.tile(m);
        renderer.end();
        assertTrue(renderer.drawn("t"));
        when(client.isRuneLiteObjectRegistered(any())).thenReturn(true);
        renderer.begin(CAMERA, 1, null, 0);
        renderer.end();
        verify(client).removeRuneLiteObject(any(RuneLiteObjectController.class));
        assertFalse(renderer.drawn("t"));
    }

    @Test public void missingCarrierReportsUnavailable()
    {
        when(client.loadModelData(anyInt())).thenReturn(null);
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, null, 0);
        renderer.tile(new Marker("t", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, null, 2, null, false));
        assertFalse(renderer.end());
        assertFalse(renderer.drawn("t"));
        verify(client, never()).registerRuneLiteObject(any());
    }

    @Test public void movingMarkerReusesVacatedCarrierAndClearsOldFaces()
    {
        java.util.Set<RuneLiteObjectController> registered = new java.util.HashSet<>();
        when(client.isRuneLiteObjectRegistered(any())).thenAnswer(call -> registered.contains(call.getArgument(0)));
        doAnswer(call -> { registered.add(call.getArgument(0)); return null; }).when(client).registerRuneLiteObject(any());
        doAnswer(call -> { registered.remove(call.getArgument(0)); return null; }).when(client).removeRuneLiteObject(any());
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, null, 0);
        renderer.tile(new Marker("moving", new LocalPoint(1344, 1344, -1), 0, 1, 1,
            Color.RED, new Color(0, 0, 0, 50), 2, null, false));
        assertTrue(renderer.end());
        assertEquals(1, renderer.carriersCreated());
        renderer.begin(CAMERA, 1, null, 0);
        renderer.tile(new Marker("moving", new LocalPoint(1472, 1344, -1), 0, 1, 1,
            Color.BLUE, Marker.NO_FILL, 2, null, false));
        assertTrue(renderer.end());
        assertEquals(1, renderer.carriersCreated());
        assertEquals(FlatModel.hsl(Color.BLUE), alphaCarrier.getFaceColors1()[0]);
        for (int f = 8; f < 12; f++) { assertEquals(-2, alphaCarrier.getFaceColors3()[f]); }
        verify(client).removeRuneLiteObject(any(RuneLiteObjectController.class));
        // A scene load clears every shape but keeps the models: the next frame reuses them.
        renderer.clear();
        renderer.begin(CAMERA, 1, null, 0);
        renderer.tile(new Marker("moving", new LocalPoint(1472, 1344, -1), 0, 1, 1,
            Color.BLUE, Marker.NO_FILL, 2, null, false));
        assertTrue(renderer.end());
        assertEquals("clear keeps the models", 1, renderer.carriersCreated());
        renderer.reset();
        renderer.begin(CAMERA, 1, null, 0);
        renderer.tile(new Marker("moving", new LocalPoint(1472, 1344, -1), 0, 1, 1,
            Color.BLUE, Marker.NO_FILL, 2, null, false));
        assertTrue(renderer.end());
        assertEquals("reset releases the pool", 2, renderer.carriersCreated());
    }

    @Test public void npcStylesShareOwnedProjectionButRefreshNextFrame()
    {
        Model mesh = triangle();
        NPC first = npc(mesh, 1344), second = npc(mesh, 1472);
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, null, 0);
        assertTrue(renderer.model(ModelTarget.npc("first:hull", first, Color.RED, Marker.NO_FILL, 2)));
        assertTrue(renderer.model(ModelTarget.npc("second:hull", second, Color.BLUE, Marker.NO_FILL, 2)));
        // Simulate a client model buffer being overwritten after its first projection.
        java.util.Arrays.fill(mesh.getVerticesX(), 1000000);
        java.util.Arrays.fill(mesh.getFaceIndices1(), 1000000);
        assertTrue(renderer.model(ModelTarget.npcOutline("first:outline", first, Color.RED, 2)));
        verify(first, times(1)).getModel();
        verify(second, times(1)).getModel();
        renderer.begin(CAMERA, 1, null, 0);
        assertFalse(renderer.model(ModelTarget.npcOutline("first:outline", first, Color.RED, 2)));
        verify(first, times(2)).getModel();
        assertTrue("offscreen shapes suppress the expensive 2D fallback", renderer.drawn("first:outline"));
    }

    @Test public void staticObjectOutlineIsReusedWhileTheCameraStandsStill()
    {
        Model mesh = triangle();
        GameObject object = mock(GameObject.class);
        when(object.getX()).thenReturn(1344); when(object.getY()).thenReturn(1344);
        when(object.getWorldView()).thenReturn(wv);
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        ModelTarget target = ModelTarget.objectOutline("tree:outline", object, mesh, 0, 0, Color.RED, 2);
        for (int i = 0; i < 3; i++)
        {
            renderer.begin(CAMERA, 1, null, 0);
            assertTrue(renderer.model(target));
            assertTrue(renderer.end());
        }
        // Projected and traced once for three frames with the same camera.
        verify(mesh, times(1)).getFaceIndices1();
        ModelShapes.Camera moved = new ModelShapes.Camera(1344 + 40, 1344 - 2000, -1500, 0.6f, 0, 600, 0, 0, 1000, 700);
        renderer.begin(moved, 1, null, 0);
        assertTrue(renderer.model(target));
        assertTrue(renderer.end());
        verify(mesh, times(2)).getFaceIndices1();
    }

    @Test public void offscreenOutlineSkipsTopologyAndFallback()
    {
        Model mesh = triangle();
        java.util.Arrays.fill(mesh.getVerticesX(), 1000000);
        NPC npc = npc(mesh, 1344);
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, null, 0);
        assertFalse(renderer.model(ModelTarget.npcOutline("outside", npc, Color.RED, 2)));
        assertTrue(renderer.drawn("outside"));
        verify(mesh, never()).getFaceIndices1();
        verify(mesh, never()).getFaceColors3();
        assertTrue(renderer.end());
        assertEquals(0, renderer.carriersCreated());
    }

    @Test public void childWorldMarkersRemainEligibleForFallback()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, null, 0);
        Marker marker = new Marker("child:tile", new LocalPoint(1344, 1344, 7), 0, 1, 1,
            Color.CYAN, Marker.NO_FILL, 2, null, false);
        assertFalse(renderer.tile(marker));
        assertFalse(renderer.drawn(marker.key));
        NPC childNpc = npc(triangle(), 1344);
        when(childNpc.getLocalLocation()).thenReturn(new LocalPoint(1344, 1344, 7));
        assertFalse(renderer.model(ModelTarget.npc("child:npc", childNpc, Color.CYAN, Marker.NO_FILL, 2)));
        assertFalse(renderer.drawn("child:npc"));
        assertTrue(renderer.end());
    }

    @Test public void transparentTilesAreHandledWithoutFallbackOrSceneObjects()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, null, 0);
        Marker marker = new Marker("invisible", new LocalPoint(1344, 1344, -1), 0, 1, 1,
            Marker.NO_FILL, Marker.NO_FILL, 2, null, false);
        assertFalse(renderer.tile(marker));
        assertTrue(renderer.drawn(marker.key));
        assertTrue(renderer.end());
        assertEquals(0, renderer.carriersCreated());
    }

    private NPC npc(Model mesh, int x)
    {
        NPC npc = mock(NPC.class);
        when(npc.getModel()).thenReturn(mesh);
        when(npc.getLocalLocation()).thenReturn(new LocalPoint(x, 1344, -1));
        when(npc.getWorldView()).thenReturn(wv);
        return npc;
    }

    private Model triangle()
    {
        Model mesh = mock(Model.class);
        when(mesh.getVerticesCount()).thenReturn(3);
        when(mesh.getVerticesX()).thenReturn(new float[]{-64, 64, 0});
        when(mesh.getVerticesY()).thenReturn(new float[]{0, 0, -200});
        when(mesh.getVerticesZ()).thenReturn(new float[]{0, 0, 0});
        when(mesh.getFaceCount()).thenReturn(1);
        when(mesh.getFaceIndices1()).thenReturn(new int[]{0});
        when(mesh.getFaceIndices2()).thenReturn(new int[]{1});
        when(mesh.getFaceIndices3()).thenReturn(new int[]{2});
        when(mesh.getFaceColors3()).thenReturn(new int[]{0});
        return mesh;
    }

    private static Model model(int vertices, int faces, boolean transparent)
    {
        Model m = mock(Model.class);
        when(m.getVerticesCount()).thenReturn(vertices);
        when(m.getFaceCount()).thenReturn(faces);
        when(m.getVerticesX()).thenReturn(new float[vertices]);
        when(m.getVerticesY()).thenReturn(new float[vertices]);
        when(m.getVerticesZ()).thenReturn(new float[vertices]);
        when(m.getFaceIndices1()).thenReturn(new int[faces]);
        when(m.getFaceIndices2()).thenReturn(new int[faces]);
        when(m.getFaceIndices3()).thenReturn(new int[faces]);
        when(m.getFaceColors1()).thenReturn(new int[faces]);
        when(m.getFaceColors2()).thenReturn(new int[faces]);
        when(m.getFaceColors3()).thenReturn(new int[faces]);
        when(m.getFaceTransparencies()).thenReturn(transparent ? new byte[faces] : null);
        when(m.getUnlitFaceColors()).thenReturn(new short[faces]);
        when(m.getVertexNormalsX()).thenReturn(new int[vertices]);
        when(m.getVertexNormalsY()).thenReturn(new int[vertices]);
        when(m.getVertexNormalsZ()).thenReturn(new int[vertices]);
        // Like the client: bounds follow the vertices at the first calculateBoundsCylinder call only.
        int[] radius = {-1};
        doAnswer(i -> {
            if (radius[0] < 0)
            {
                float r = 0;
                float[] x = m.getVerticesX(), z = m.getVerticesZ();
                for (int v = 0; v < vertices; v++) { r = Math.max(r, (float) Math.hypot(x[v], z[v])); }
                radius[0] = (int) r;
            }
            return null;
        }).when(m).calculateBoundsCylinder();
        when(m.getRadius()).thenAnswer(i -> radius[0]);
        return m;
    }

    @Test public void aCameraJumpDrawsNothingUntilTheNextFrame()
    {
        SceneShapeRenderer renderer = new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace());
        renderer.begin(CAMERA, 1, null, 0);
        assertTrue(renderer.tile(new Marker("t", new LocalPoint(1344, 1344, -1), 0, 1, 1, Color.RED, Marker.NO_FILL, 2, null, false)));
        assertTrue(renderer.end());
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        verify(client).registerRuneLiteObject(registered.capture());
        RuneLiteObjectController o = registered.getValue();
        assertNotNull(o.getModel());
        // The client draws with a camera set somewhere else (login, teleport): its widths would not fit.
        when(client.getCameraFpX()).thenReturn(1344f + 3000);
        assertNull(o.getModel());
        when(client.getCameraFpX()).thenReturn(1344f);
        when(client.getScale()).thenReturn(200);
        assertNull(o.getModel());
        // Moving and zooming a little between frames is no jump.
        when(client.getCameraFpX()).thenReturn(1344f + 60);
        when(client.getScale()).thenReturn(650);
        assertNotNull(o.getModel());
    }

    @Test public void largePolygonsAreSimplifiedToFitOneSceneObject()
    {
        // A clickbox close to the camera: a 600 pixel staircase circle of thousands of points.
        java.util.List<Float> pts = new java.util.ArrayList<>();
        float lx = Float.NaN, ly = Float.NaN;
        for (int i = 0; i < 8000; i++)
        {
            double t = 2 * Math.PI * i / 8000;
            float x = Math.round(600 * Math.cos(t) * 4) / 4f, y = Math.round(600 * Math.sin(t) * 4) / 4f;
            if (x == lx && y == ly) { continue; }
            if (!Float.isNaN(lx) && x != lx && y != ly) { pts.add(x); pts.add(ly); }
            pts.add(x); pts.add(y);
            lx = x; ly = y;
        }
        float[] circle = new float[pts.size()];
        for (int i = 0; i < circle.length; i++) { circle[i] = pts.get(i); }
        assertTrue(circle.length / 2 > SceneShapeRenderer.MAX_POINTS);
        float[] fit = SceneShapeRenderer.fit(circle);
        assertTrue(fit.length / 2 <= SceneShapeRenderer.MAX_POINTS);
        double area = Math.abs(SceneShapeRenderer.signedArea(circle));
        assertEquals(area, Math.abs(SceneShapeRenderer.signedArea(fit)), area * 0.005);
        float[] small = {0, 0, 10, 0, 10, 10};
        assertSame(small, SceneShapeRenderer.fit(small));
        ScreenOutline o = new ScreenOutline();
        float[] x = new float[fit.length / 2], y = new float[x.length], d = new float[x.length];
        for (int i = 0; i < x.length; i++) { x[i] = fit[i * 2] + 700; y[i] = fit[i * 2 + 1] + 700; d[i] = 500; }
        assertTrue(o.buildPolygon(x, y, d, x.length, 2));
        // Border and fill, twice for a light colour's white layer, within half a scene object.
        assertTrue(2 * o.vertices <= CarrierModels.MAX_VERTICES / 2 && 2 * o.faces <= CarrierModels.MAX_FACES / 2);
    }
}
