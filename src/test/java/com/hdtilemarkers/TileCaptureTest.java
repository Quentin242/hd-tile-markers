package com.hdtilemarkers;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.Overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TileCaptureTest
{
    private static Overlay overlay(java.util.function.Consumer<Graphics2D> render)
    {
        return new Overlay()
        {
            @Override public Dimension render(Graphics2D g) { render.accept(g); return null; }
        };
    }

    private static TileCapture capture()
    {
        Client client = mock(Client.class);
        when(client.getTopLevelWorldView()).thenReturn(mock(WorldView.class));
        return new TileCapture(client, "test:");
    }

    /** Text, images and shapes that are no tile polygon (a rectangle, a timer pie) are drawn in 2D as the overlay drew them. */
    @Test public void nonTileDrawingIsDrawnAsIs()
    {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        TileCapture capture = capture();
        capture.render(Collections.singletonList(overlay(o -> {
            o.setColor(Color.RED);
            o.fill(new Rectangle(0, 0, 10, 10));
            o.fill(new java.awt.geom.Ellipse2D.Double(20, 0, 10, 10));
        })), g);
        g.dispose();
        assertNotEquals(0, image.getRGB(5, 5) >>> 24);
        assertNotEquals(0, image.getRGB(25, 5) >>> 24);
        List<Marker> out = new ArrayList<>();
        capture.collect(out);
        assertTrue(out.isEmpty());
    }

    /** An overlay that keeps failing when run here draws itself again. */
    @Test public void repeatedFailuresHandTheOverlayBack()
    {
        TileCapture capture = capture();
        Overlay failing = overlay(o -> { throw new IllegalStateException(); });
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        for (int i = 0; i < 2; i++) { capture.render(Collections.singletonList(failing), g); }
        assertTrue(capture.usable());
        capture.render(Collections.singletonList(failing), g);
        assertFalse(capture.usable());
        capture.clear();
        assertTrue(capture.usable());
    }

    /** Dropping: shapes HD Tile Markers draws itself (a clickbox) are dropped; text and int-based bars stay. */
    @Test public void droppedShapesLeaveTextAndBars()
    {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        Client client = mock(Client.class);
        when(client.getTopLevelWorldView()).thenReturn(mock(WorldView.class));
        new TileCapture(client, "test:", true).render(Collections.singletonList(overlay(o -> {
            o.setColor(Color.RED);
            o.fill(new java.awt.geom.Ellipse2D.Double(0, 0, 10, 10));
            o.fillRect(20, 0, 10, 10);
        })), g);
        g.dispose();
        assertEquals(0, image.getRGB(5, 5) >>> 24);
        assertNotEquals(0, image.getRGB(25, 5) >>> 24);
    }
}
