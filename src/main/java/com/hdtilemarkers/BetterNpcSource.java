/*
 * Colours, alphas and widths per style follow Better NPC Highlight's BetterNpcHighlightOverlay.renderNpcOverlay
 * (https://github.com/riktenx/better-npc-highlight, commit bf59bfb9a616897e9ffcd14d0d2b543e4c119b09),
 * copyright (c) 2022 Buchus, BSD 2-Clause License; see META-INF/LICENSE-better-npc-highlight and
 * THIRD_PARTY_NOTICES.md. Changes for HD Tile Markers: returns scene shapes instead of drawing.
 */
package com.hdtilemarkers;

import com.hdtilemarkers.betternpc.BetterNpcHighlightConfig;
import com.hdtilemarkers.betternpc.BetterNpcView;
import com.hdtilemarkers.betternpc.ColorManager;
import com.hdtilemarkers.betternpc.HighlightColor;
import java.awt.Color;
import java.awt.Shape;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;

/**
 * Turns Better NPC Highlight's decisions into HD Tile Markers scene shapes. Positions,
 * colors, alphas and widths follow its overlay's renderNpcOverlay; styles
 * without a scene equivalent (dashed or corner lines, outline) are left to its
 * own 2D drawing in BetterNpcView.render2d.
 */
@Singleton
final class BetterNpcSource
{
    private final Client client;
    private final BetterNpcView view;

    @Inject
    BetterNpcSource(Client client, BetterNpcView view) { this.client = client; this.view = view; }

    static String key(NPC npc, String style) { return "bnh:" + npc.getIndex() + ":" + style; }

    static String respawnKey(int npcIndex) { return "bnh:respawn:" + npcIndex; }


    /** The clickbox drawn last frame per NPC, for the hover test. */
    private final java.util.Map<NPC, Shape> drawnClickboxes = new java.util.IdentityHashMap<>();
    private final java.util.Set<NPC> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    void collect(List<Marker> tiles, List<ModelTarget> models)
    {
        seen.clear();
        BetterNpcHighlightConfig c = view.config();
        ColorManager colors = view.colors();
        // Respawn timer tiles; their countdown text stays in Better NPC Highlight's 2D code.
        for (BetterNpcView.RespawnTile r : view.respawnTiles())
        {
            Marker m = new Marker(respawnKey(r.npcIndex), r.center, r.plane, r.size, r.size, c.respawnOutlineColor(),
                c.respawnFillColor(), c.respawnTileWidth(), null, false);
            m.layer = Marker.RESPAWN;
            tiles.add(m);
        }
        view.visit((info, style) -> {
            NPC npc = info.getNpc();
            NPCComposition composition = npc.getTransformedComposition();
            if (composition == null || npc.getWorldView() == null) { return; }
            int size = composition.getSize(), plane = npc.getWorldView().getPlane();
            boolean task = colors.shouldUseSlayerHighlight(info);
            String key = key(npc, style);
            LocalPoint local = npc.getLocalLocation(), trueSw = LocalPoint.fromWorld(npc.getWorldView(), npc.getWorldLocation());
            switch (style)
            {
                case "tile":
                    if (local != null && c.tileLines() != BetterNpcHighlightConfig.lineType.DASH)
                    {
                        tiles.add(lines(c.tileLines(), tile(key, Marker.NPC_TILE, local, plane, size, info.getTile(), task, c.tileRave(), c.tileRaveSpeed(), c.tileWidth())));
                    }
                    break;
                case "trueTile":
                    if (trueSw != null && c.trueTileLines() != BetterNpcHighlightConfig.lineType.DASH)
                    {
                        LocalPoint center = trueSw.plus(size * 128 / 2 - 64, size * 128 / 2 - 64);
                        tiles.add(lines(c.trueTileLines(), tile(key, Marker.NPC_TILE + 1, center, plane, size, info.getTrueTile(), task, c.trueTileRave(), c.trueTileRaveSpeed(), c.trueTileWidth())));
                    }
                    break;
                case "swTile":
                    if (local != null && c.swTileLines() != BetterNpcHighlightConfig.lineType.DASH)
                    {
                        LocalPoint sw = local.plus(-(size - 1) * 128 / 2, -(size - 1) * 128 / 2);
                        tiles.add(lines(c.swTileLines(), tile(key, Marker.NPC_TILE + 2, sw, plane, 1, info.getSwTile(), task, c.swTileRave(), c.swTileRaveSpeed(), c.swTileWidth())));
                    }
                    break;
                case "swTrueTile":
                    if (trueSw != null && c.swTrueTileLines() != BetterNpcHighlightConfig.lineType.DASH)
                    {
                        tiles.add(lines(c.swTrueTileLines(), tile(key, Marker.NPC_TILE + 3, trueSw, plane, 1, info.getSwTrueTile(), task, c.swTrueTileRave(), c.swTrueTileRaveSpeed(), c.swTrueTileWidth())));
                    }
                    break;
                case "hull":
                {
                    HighlightColor h = info.getHull();
                    Color line = alpha(colors.resolveColor(task, c.taskColor(), h.getColor(), c.hullRave(), c.hullRaveSpeed()),
                        task ? c.taskColor().getAlpha() : h.getColor().getAlpha());
                    Color fill = alpha(colors.resolveColor(task, c.taskFillColor(), h.getFill(), c.hullRave(), c.hullRaveSpeed()),
                        task ? c.taskFillColor().getAlpha() : h.getFill().getAlpha());
                    models.add(ModelTarget.npc(key, npc, line, fill, c.hullWidth()));
                    break;
                }
                case "area":
                {
                    // As the original, including its fallback of alpha 50 for a transparent fill.
                    Color color = info.getArea().getFill() != null ? info.getArea().getFill() : info.getArea().getColor();
                    Color fill = colors.resolveColor(task, c.taskFillColor(), color, c.areaRave(), c.areaRaveSpeed());
                    int fillAlpha = task ? c.taskFillColor().getAlpha() : color.getAlpha();
                    Color area = fill.getAlpha() == 0 ? new Color(fill.getRed(), fill.getGreen(), fill.getGreen(), 50)
                        : new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), fillAlpha);
                    models.add(ModelTarget.npc(key, npc, Marker.NO_FILL, area, 0));
                    break;
                }
                case "clickbox":
                {
                    HighlightColor h = info.getClickbox();
                    Color line = colors.resolveColor(task, c.taskColor(), h.getColor(), c.clickboxRave(), c.clickboxRaveSpeed());
                    Color fill = colors.resolveColor(task, c.taskFillColor(), h.getFill(), c.clickboxRave(), c.clickboxRaveSpeed());
                    int lineAlpha = task ? c.taskColor().getAlpha() : h.getColor().getAlpha();
                    int fillAlpha = task ? c.taskFillColor().getAlpha() : h.getFill().getAlpha();
                    // The original darkens the border while the mouse is over the clickbox. RuneLite's clickbox
                    // is costly, so the hover test uses the one drawn last frame instead of computing it again.
                    Shape clickbox = drawnClickboxes.get(npc);
                    net.runelite.api.Point mouse = client.getMouseCanvasPosition();
                    if (clickbox != null && mouse != null && clickbox.contains(mouse.getX(), mouse.getY())) { line = line.darker(); }
                    seen.add(npc);
                    models.add(ModelTarget.npcClickbox(key, npc, alpha(line, lineAlpha), alpha(fill, fillAlpha), 1, () -> {
                        Shape shape = clickbox(npc);
                        drawnClickboxes.put(npc, shape);
                        return shape;
                    }));
                    break;
                }
                case "outline":
                {
                    Color line = colors.resolveColor(task, c.taskColor(), info.getOutline().getColor(), c.outlineRave(), c.outlineRaveSpeed());
                    models.add(ModelTarget.npcOutline(key, npc, line, c.outlineWidth()));
                    break;
                }
                default:
                    // Dashed lines stay 2D.
                    break;
            }
        });
        drawnClickboxes.keySet().retainAll(seen);
    }

    /** Better NPC Highlight's corner style: corner lines of 1/7 of each side, as its renderPolygonCorners. */
    private static Marker lines(BetterNpcHighlightConfig.lineType type, Marker m)
    {
        if (type == BetterNpcHighlightConfig.lineType.CORNER) { m.cornerDivisor = 7; }
        return m;
    }

    private Marker tile(String key, int layer, LocalPoint point, int plane, int size, HighlightColor h, boolean task,
        boolean rave, int raveSpeed, double width)
    {
        BetterNpcHighlightConfig c = view.config();
        ColorManager colors = view.colors();
        Color line = alpha(colors.resolveColor(task, c.taskColor(), h.getColor(), rave, raveSpeed),
            task ? c.taskColor().getAlpha() : h.getColor().getAlpha());
        Color fill = alpha(colors.resolveColor(task, c.taskFillColor(), h.getFill(), rave, raveSpeed),
            task ? c.taskFillColor().getAlpha() : h.getFill().getAlpha());
        Marker m = new Marker(key, point, plane, size, size, line, fill, width, null, false);
        m.layer = layer;
        return m;
    }

    private Shape clickbox(NPC npc)
    {
        LocalPoint lp = npc.getLocalLocation();
        if (lp == null) { return null; }
        return Perspective.getClickbox(client, npc.getWorldView(), npc.getModel(), npc.getCurrentOrientation(), lp.getX(), lp.getY(),
            Perspective.getTileHeight(client, lp, npc.getWorldLocation().getPlane()));
    }

    private static Color alpha(Color c, int alpha) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha); }
}
