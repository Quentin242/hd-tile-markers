package com.hdtilemarkers;

import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.Overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyInt;

public class ShortestPathSourceTest
{
    /** Draws like Shortest Path's PathTileOverlay: a filled tile polygon, a stroked line and a counter. */
    private static void drawLikePathOverlay(Graphics2D g)
    {
        g.setColor(new Color(255, 0, 0, 127));
        g.fill(new Polygon(new int[]{10, 30, 30, 10}, new int[]{10, 10, 30, 30}, 4));
        g.setStroke(new BasicStroke(4));
        g.draw(new Line2D.Double(20, 20, 60, 20));
        g.setColor(Color.WHITE);
        g.drawString("3", 20, 20);
    }

    @Test public void sinkReceivesShapesWithColourAndStroke()
    {
        List<Object> seen = new ArrayList<>();
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = new CapturingGraphics(image.createGraphics(), new CapturingGraphics.Sink()
        {
            @Override public boolean fill(Shape shape, Color color) { seen.add(color); return true; }
            @Override public boolean draw(Shape shape, Color color, Stroke stroke) { seen.add(((BasicStroke) stroke).getLineWidth()); return true; }
        });
        drawLikePathOverlay(g);
        g.dispose();
        assertEquals(new Color(255, 0, 0, 127), seen.get(0));
        assertEquals(4f, seen.get(1));
        // Taken shapes are not drawn: only the text reached the image, nothing at the tile's corner.
        assertEquals(0, image.getRGB(12, 12) >>> 24);
    }

    @Test public void untakenShapesAreDrawn()
    {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = new CapturingGraphics(image.createGraphics(), new CapturingGraphics.Sink()
        {
            @Override public boolean fill(Shape shape, Color color) { return false; }
            @Override public boolean draw(Shape shape, Color color, Stroke stroke) { return false; }
        });
        drawLikePathOverlay(g);
        g.dispose();
        assertNotEquals(0, image.getRGB(12, 12) >>> 24);
    }

    @Test public void tileAndLineMarkers()
    {
        LocalPoint a = new LocalPoint(64, 64, -1), b = new LocalPoint(192, 64, -1);
        Marker tile = ShortestPathSource.tile("t", a, 0, new Color(255, 0, 0, 127));
        assertEquals(new Color(255, 0, 0, 127), tile.fill);
        assertEquals(0, tile.borderWidth, 0);
        Marker line = ShortestPathSource.line("l", a, b, 0, Color.RED, 4);
        assertArrayEquals(new int[]{64, 192}, line.lineX);
        assertArrayEquals(new int[]{64, 64}, line.lineY);
        assertEquals(4, line.borderWidth, 0);
    }

    @Test public void polygonsMatchExactly()
    {
        Polygon p = new Polygon(new int[]{1, 2, 3, 4}, new int[]{5, 6, 7, 8}, 4);
        assertTrue(ShortestPathSource.samePolygon(p, new Polygon(new int[]{1, 2, 3, 4}, new int[]{5, 6, 7, 8}, 4)));
        assertFalse(ShortestPathSource.samePolygon(p, new Polygon(new int[]{1, 2, 3, 4}, new int[]{5, 6, 7, 9}, 4)));
    }

    /** A client whose CPU projection (Perspective) looks north from south of scene tile (10, 10), over a hill. */
    private static Client hillClient(int[][][] heights)
    {
        Client client = mock(Client.class);
        WorldView wv = mock(WorldView.class);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        // Perspective.localToCanvas projects world view 0 as the scene itself, others through their entity.
        when(client.getWorldView(anyInt())).thenReturn(wv);
        when(wv.getId()).thenReturn(0);
        when(wv.isTopLevel()).thenReturn(true);
        Scene scene = mock(Scene.class);
        when(scene.getExtendedTileSettings()).thenReturn(new byte[4][104][104]);
        when(wv.getScene()).thenReturn(scene);
        // Tile heights as the client interpolates them (Terrain.height matches it, see SceneShapeRendererTest).
        when(wv.getTileHeight(anyInt(), anyInt(), anyInt())).thenAnswer(i -> Terrain.height(wv, i.getArgument(0), i.getArgument(1), i.getArgument(2)));
        when(wv.getSizeX()).thenReturn(104);
        when(wv.getSizeY()).thenReturn(104);
        when(wv.getTileHeights()).thenReturn(heights);
        when(wv.getTileSettings()).thenReturn(new byte[4][104][104]);
        when(client.getCameraX()).thenReturn(1344);
        when(client.getCameraY()).thenReturn(1344 - 1500);
        when(client.getCameraZ()).thenReturn(-900);
        when(client.getCameraPitch()).thenReturn(160);
        when(client.getCameraYaw()).thenReturn(0);
        when(client.getScale()).thenReturn(600);
        when(client.getViewportWidth()).thenReturn(1000);
        when(client.getViewportHeight()).thenReturn(700);
        return client;
    }

    @Test public void tileBehindAHillIsStillFound()
    {
        int[][][] heights = new int[4][105][105];
        // A hill across the view between the camera and tile (10, 12): its top 600 above the ground.
        for (int x = 0; x < 105; x++) { for (int y = 7; y <= 9; y++) { heights[0][x][y] = -600; } }
        Client client = hillClient(heights);
        LocalPoint target = new LocalPoint(10 * 128 + 64, 12 * 128 + 64, 0);
        Polygon drawn = Perspective.getCanvasTilePoly(client, target);
        assertNotNull(drawn);
        // The old way, a ray through the polygon's centre, meets the hill first.
        ModelShapes.Camera camera = new ModelShapes.Camera(1344, 1344 - 1500, -900,
            (float) (160 * 2 * Math.PI / 2048), 0, 600, 0, 0, 1000, 700);
        Rectangle b = drawn.getBounds();
        net.runelite.api.coords.WorldPoint hit = WalkPredictor.raycast(camera, (float) b.getCenterX(), (float) b.getCenterY(),
            client.getTopLevelWorldView(), 0);
        assertTrue("the ray should stop at the hill, got " + hit, hit == null || hit.getY() < 12 - 1);
        // Shortest Path's overlay fills the tile's polygon; HD Tile Markers finds that tile.
        Overlay overlay = new Overlay()
        {
            @Override public Dimension render(Graphics2D g)
            {
                g.setColor(new Color(255, 207, 207, 127));
                g.fill(Perspective.getCanvasTilePoly(client, target));
                return null;
            }
        };
        List<Marker> out = new ArrayList<>();
        ShortestPathSource source = new ShortestPathSource(client, mock(ConfigManager.class));
        source.render(java.util.Collections.singletonList(overlay), new BufferedImage(1000, 700, BufferedImage.TYPE_INT_ARGB).createGraphics());
        source.collect(out);
        assertEquals(1, out.size());
        assertEquals(target.getX(), out.get(0).point.getX());
        assertEquals(target.getY(), out.get(0).point.getY());
        assertEquals(new Color(255, 207, 207, 127), out.get(0).fill);
    }

    @Test public void matchedTilesGoToTheSceneOthersAndTextAreDrawn()
    {
        Client client = hillClient(new int[4][105][105]);
        LocalPoint target = new LocalPoint(10 * 128 + 64, 12 * 128 + 64, 0);
        Polygon tile = Perspective.getCanvasTilePoly(client, target);
        // No scene tile has this polygon: it must still be drawn, in 2D.
        Polygon other = new Polygon(new int[]{900, 960, 960, 900}, new int[]{20, 20, 60, 60}, 4);
        Overlay overlay = new Overlay()
        {
            @Override public Dimension render(Graphics2D g)
            {
                g.setColor(Color.RED);
                g.fill(tile);
                g.fill(other);
                g.drawString("12", 20, 680);
                return null;
            }
        };
        BufferedImage image = new BufferedImage(1000, 700, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        ShortestPathSource source = new ShortestPathSource(client, mock(ConfigManager.class));
        source.render(java.util.Collections.singletonList(overlay), g);
        g.dispose();
        List<Marker> out = new ArrayList<>();
        source.collect(out);
        assertEquals(1, out.size());
        Rectangle b = tile.getBounds();
        assertEquals("the matched tile is not drawn in 2D", 0, image.getRGB((int) b.getCenterX(), (int) b.getCenterY()) >>> 24);
        assertNotEquals("the unmatched polygon is drawn in 2D", 0, image.getRGB(930, 40) >>> 24);
        boolean text = false;
        for (int x = 18; x < 40; x++) { for (int y = 660; y < 682; y++) { text |= (image.getRGB(x, y) >>> 24) != 0; } }
        assertTrue("text is drawn", text);
    }

    @Test public void oneFailureKeepsTheIntegrationRepeatedOnesHandItBack()
    {
        Client client = hillClient(new int[4][105][105]);
        boolean[] fail = {true};
        Overlay overlay = new Overlay()
        {
            @Override public Dimension render(Graphics2D g)
            {
                if (fail[0]) { throw new IllegalStateException("path replaced while drawn"); }
                return null;
            }
        };
        ShortestPathSource source = new ShortestPathSource(client, mock(ConfigManager.class));
        Graphics2D g = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB).createGraphics();
        source.render(java.util.Collections.singletonList(overlay), g);
        assertTrue(source.usable());
        fail[0] = false;
        source.render(java.util.Collections.singletonList(overlay), g);
        fail[0] = true;
        for (int i = 0; i < 3; i++) { source.render(java.util.Collections.singletonList(overlay), g); }
        assertFalse(source.usable());
        source.clear();
        assertTrue(source.usable());
    }
}
