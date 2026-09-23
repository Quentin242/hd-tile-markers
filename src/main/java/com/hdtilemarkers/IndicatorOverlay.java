/*
 * facingArrow adapts Stealing Artefacts' StealingArtefactsPatrolOverlay.renderFacingDirection
 * (https://github.com/pajlads/StealingArtefacts, commit 631227f32d7518da6615165c4658bf6e3704dedb),
 * copyright 2020 Christopher Bitler, MIT License; see META-INF/LICENSE-stealing-artefacts and THIRD_PARTY_NOTICES.md.
 */
package com.hdtilemarkers;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.NPC;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.ui.overlay.*;

/**
 * Status line, labels, and the 2D fallback for every shape that is not in the
 * scene this frame (no GPU, rendering error, other world views, or limits).
 */
final class IndicatorOverlay extends Overlay
{
    private final Client client;
    private final HdTileMarkersPlugin plugin;
    private final HdTileMarkersConfig config;
    private final ModelOutlineRenderer outlines;

    private volatile java.awt.image.BufferedImage directionArrow;

    @Inject IndicatorOverlay(Client client, HdTileMarkersPlugin plugin, ConfigManager manager, ModelOutlineRenderer outlines,
        net.runelite.client.game.SpriteManager sprites)
    {
        this.client = client; this.plugin = plugin; this.outlines = outlines;
        // Stealing Artefacts' patrol facing arrow sprite.
        sprites.getSpriteAsync(net.runelite.api.gameval.SpriteID.Arrow.YELLOW_UP, 0, sprite -> directionArrow = sprite);
        config = manager.getConfig(HdTileMarkersConfig.class);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPosition(OverlayPosition.DYNAMIC);
        setPriority(PRIORITY_LOW);
    }

    @Override public Dimension render(Graphics2D graphics)
    {
        Graphics2D g = (Graphics2D) graphics.create();
        try
        {
            if (config.debug())
            {
                String status = plugin.rendererStatus();
                int x = client.getViewportXOffset() + 12, y = client.getViewportYOffset() + 24;
                g.setColor(new Color(0, 0, 0, 180));
                g.fillRect(x - 4, y - g.getFontMetrics().getAscent() - 3,
                    g.getFontMetrics().stringWidth(status) + 8, g.getFontMetrics().getHeight() + 6);
                g.setColor(status.startsWith("HD Tile Markers: trace") ? Color.GREEN : Color.ORANGE);
                g.drawString(status, x, y);
            }
            // Without the scene route, RuneLite's outline renderer draws outlines in 2D.
            java.util.Set<net.runelite.api.TileObject> sceneObjectOutlines = plugin.objectOutlinesInScene();
            for (ObjectMarkerSource.Resolved o : plugin.objectOutlines())
            {
                if (!sceneObjectOutlines.contains(o.object))
                { outlines.drawOutline(o.object, (int) o.borderWidth, o.border, o.feather); }
            }
            for (NPC npc : plugin.npcOutlines())
            {
                if (!plugin.markerInScene("npc:" + npc.getIndex() + ":outline"))
                { outlines.drawOutline(npc, (int) plugin.npcConfig().borderWidth(), plugin.npcOutlineColor(npc), plugin.npcConfig().outlineFeather()); }
            }
            if (plugin.drawsBetterNpc())
            {
                // Better NPC Highlight's own 2D drawing for what is not in the scene this frame.
                Graphics2D bnh = (Graphics2D) g.create();
                try
                {
                    plugin.betterNpcView().visit((info, style) -> {
                        if (!plugin.markerInScene(BetterNpcSource.key(info.getNpc(), style)))
                        { plugin.betterNpcView().render2d(bnh, info, style); }
                    });
                    plugin.betterNpcView().renderExtras(bnh, index -> plugin.markerInScene(BetterNpcSource.respawnKey(index)));
                }
                finally { bnh.dispose(); }
            }
            for (NPC guard : plugin.stealingArrows()) { facingArrow(g, guard); }
            // The Gauntlet's resource icons, as its MazeOverlay draws them.
            for (java.util.Map.Entry<net.runelite.api.coords.LocalPoint, java.awt.image.BufferedImage> icon : plugin.gauntletIcons().entrySet())
            {
                OverlayUtil.renderImageLocation(client, g, icon.getKey(), icon.getValue(), 0);
            }
            for (ModelTarget t : plugin.modelTargets())
            {
                // Better NPC Highlight owns its style-specific fallback above.
                if (t.key.startsWith("bnh:") || plugin.markerInScene(t.key)) { continue; }
                if (t.outline)
                {
                    // Core NPC/Object outline styles retain their own feather settings above.
                    if (t.npc != null && !t.key.startsWith("npc:"))
                    { outlines.drawOutline(t.npc, (int) t.borderWidth, t.color, 0); }
                    continue;
                }
                Shape shape = t.shape();
                if (shape != null) { draw(g, shape, t.color, t.fill, t.borderWidth); }
            }
            Marker hover = plugin.hover();
            if (hover != null && (plugin.hoverIn2d() || !plugin.markerInScene(hover.key)))
            {
                Polygon shape = polygon(hover);
                if (shape != null) { draw(g, shape, hover); }
            }
            // Higher layers last, so your own tile and destination end up on top in 2D too.
            // Only 2D tiles need the layer order; in the scene only labels are drawn here.
            java.util.List<Marker> ordered = plugin.markers();
            if (plugin.tilesIn2d())
            {
                ordered = new java.util.ArrayList<>(ordered);
                ordered.sort(java.util.Comparator.comparingInt(m -> m.layer));
            }
            for (Marker m : ordered)
            {
                // Without replacement the original Ground Markers overlay draws these.
                if (m.ground && !plugin.replacedGround()) { continue; }
                // BNH's tiles and respawns use its own fallback, avoiding a second copy.
                if (!m.key.startsWith("bnh:") && !plugin.markerInScene(m.key))
                {
                    Shape shape = m.dot ? dot(m) : m.lineX != null ? line(m) : polygon(m);
                    if (shape != null) { draw(g, shape, m); }
                }
                if (m.label != null && !m.label.isEmpty())
                {
                    Point p = Perspective.getCanvasTextLocation(client, g, m.point, m.label, 0);
                    if (p != null) { OverlayUtil.renderTextLocation(g, p, m.label, m.color); }
                }
            }
        }
        finally { g.dispose(); }
        return null;
    }

    private static void draw(Graphics2D g, Shape shape, Marker m)
    {
        if (m.cornerDivisor <= 0 || !(shape instanceof Polygon)) { draw(g, shape, m.color, m.fill, m.borderWidth); return; }
        // Corners only, as Corner Tile Indicators' renderPolygonCorners.
        Polygon p = (Polygon) shape;
        if (m.fill.getAlpha() > 0) { g.setColor(m.fill); g.fill(p); }
        if (m.borderWidth <= 0 || m.color.getAlpha() == 0) { return; }
        g.setColor(m.color);
        g.setStroke(new BasicStroke(m.borderWidth));
        for (int i = 0; i < p.npoints; i++)
        {
            int prev = (i + p.npoints - 1) % p.npoints, next = (i + 1) % p.npoints;
            int x = p.xpoints[i], y = p.ypoints[i];
            g.drawLine(x, y, x + (p.xpoints[next] - x) / m.cornerDivisor, y + (p.ypoints[next] - y) / m.cornerDivisor);
            g.drawLine(x, y, x + (p.xpoints[prev] - x) / m.cornerDivisor, y + (p.ypoints[prev] - y) / m.cornerDivisor);
        }
    }

    private static void draw(Graphics2D g, Shape shape, Color color, Color fill, float width)
    {
        if (fill.getAlpha() > 0) { g.setColor(fill); g.fill(shape); }
        if (width > 0 && color.getAlpha() > 0)
        {
            g.setColor(color);
            g.setStroke(new BasicStroke(width));
            g.draw(shape);
        }
    }

    /** Stealing Artefacts' renderFacingDirection: its arrow sprite, rotated to the guard's facing. */
    private void facingArrow(Graphics2D graphics, NPC actor)
    {
        java.awt.image.BufferedImage arrow = directionArrow;
        if (arrow == null || actor.getLocalLocation() == null) { return; }
        Point canvasPoint = Perspective.localToCanvas(client, actor.getLocalLocation(), client.getTopLevelWorldView().getPlane());
        if (canvasPoint == null) { return; }
        Graphics2D arrowGraphics = (Graphics2D) graphics.create();
        try
        {
            double scaleX = 24.0 / arrow.getWidth(), scaleY = 24.0 / arrow.getHeight();
            java.awt.geom.AffineTransform transform = new java.awt.geom.AffineTransform();
            transform.translate(canvasPoint.getX(), canvasPoint.getY());
            int relative = (actor.getCurrentOrientation() & 2047) * 8 + (client.getCameraYaw() & 16383) - 8192;
            transform.rotate(relative * Perspective.UNIT14);
            transform.scale(scaleX, scaleY);
            transform.translate(-arrow.getWidth() / 2.0, -arrow.getHeight() / 2.0);
            arrowGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            arrowGraphics.drawImage(arrow, transform, null);
        }
        finally { arrowGraphics.dispose(); }
    }

    private Shape dot(Marker m)
    {
        Point p = Perspective.localToCanvas(client, m.point, m.plane);
        return p == null ? null : new Ellipse2D.Double(p.getX() - 4, p.getY() - 4, 8, 8);
    }

    /** An open polyline marker, projected point by point. */
    private Shape line(Marker m)
    {
        java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
        for (int i = 0; i < m.lineX.length; i++)
        {
            Point p = Perspective.localToCanvas(client, new net.runelite.api.coords.LocalPoint(m.lineX[i], m.lineY[i], m.point.getWorldView()), m.plane);
            if (p == null) { return null; }
            if (i == 0) { path.moveTo(p.getX(), p.getY()); } else { path.lineTo(p.getX(), p.getY()); }
        }
        return path;
    }

    private Polygon polygon(Marker m)
    {
        if (m.quadX != null)
        {
            Polygon quad = new Polygon();
            for (int i = 0; i < 4; i++)
            {
                Point p = Perspective.localToCanvas(client, new net.runelite.api.coords.LocalPoint(m.quadX[i], m.quadY[i], m.point.getWorldView()), m.plane);
                if (p == null) { return null; }
                quad.addPoint(p.getX(), p.getY());
            }
            return quad;
        }
        int hx = m.width * 64, hy = m.height * 64;
        int[] dx = {-hx, hx, hx, -hx}, dy = {-hy, -hy, hy, hy};
        Polygon result = new Polygon();
        for (int i = 0; i < 4; i++)
        {
            Point p = Perspective.localToCanvas(client, m.point.plus(dx[i], dy[i]), m.plane);
            if (p == null) { return null; }
            result.addPoint(p.getX(), p.getY());
        }
        return result;
    }
}
