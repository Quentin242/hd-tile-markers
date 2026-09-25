package com.hdtilemarkers;

import java.awt.Color;
import net.runelite.api.coords.LocalPoint;

/** A rectangular tile footprint centered on point. */
final class Marker
{
    static final Color NO_FILL = new Color(0, 0, 0, 0);

    /*
     * Depth layers, low to high: NPC highlights at the bottom, your own marks above them,
     * and your tile indicators on top. Hulls, clickboxes and outlines use SceneShapeRenderer.HULL_LAYER.
     */
    /** NPC tile styles: tile, true tile, south-west tile, south-west true tile (+0..3). */
    static final int NPC_TILE = 1;
    static final int RESPAWN = 5, PATH_HOVER = 6, PATH_ACTIVE = 7, GROUND = 8, OBJECT = 9, SAILING = 10, HOVER = 11;
    /** Tiles other plugins send (ExternalMarks): with the saved ground marks. */
    static final int EXTERNAL = GROUND;
    /** NPC Aggression Timer's area lines: with the saved ground marks. */
    static final int AGGRO_AREA = GROUND;
    static final int DESTINATION = SceneShapeRenderer.HULL_LAYER + 2, CURRENT = SceneShapeRenderer.HULL_LAYER + 3;

    final String key;
    final LocalPoint point;
    final int plane, width, height;
    final Color color, fill;
    /** Border width in screen pixels, as in the source plugin's options. */
    final float borderWidth;
    final String label;
    final boolean ground;
    /** Path Marker dot style: a small circle at the tile center instead of the tile. */
    final boolean dot;
    /** Depth layer: overlapping shapes of higher layers are drawn in front, without z-fighting. */
    int layer;
    /** Corners only: each corner line is 1/cornerDivisor of its side, as Corner Tile Indicators and Better NPC Highlight; 0 draws the full border. */
    int cornerDivisor;
    /** A free quadrilateral in local coordinates (Sailing's rotated boat bounds) instead of the rectangle; point is its center. */
    int[] quadX, quadY;
    /** An open polyline on the ground in local coordinates (NPC Aggression Timer's area lines) instead of the rectangle. */
    int[] lineX, lineY;
    /**
     * The actor this footprint stands under (a walking NPC's tile captured from another plugin): drawn where it is each
     * frame rather than where it was when the mark was made; point is the fallback.
     */
    net.runelite.api.Actor follow;

    /** Where the footprint is now: the followed actor's location, else point. */
    net.runelite.api.coords.LocalPoint where()
    {
        net.runelite.api.coords.LocalPoint now = follow == null ? null : follow.getLocalLocation();
        return now != null && now.getWorldView() == point.getWorldView() ? now : point;
    }

    Marker(String key, LocalPoint point, int plane, int width, int height, Color color, Color fill,
        double borderWidth, String label, boolean ground)
    {
        this(key, point, plane, width, height, color, fill, borderWidth, label, ground, false);
    }

    Marker(String key, LocalPoint point, int plane, int width, int height, Color color, Color fill,
        double borderWidth, String label, boolean ground, boolean dot)
    {
        this.key = key;
        this.point = point;
        this.plane = plane;
        this.width = width;
        this.height = height;
        this.color = color == null ? NO_FILL : color;
        this.fill = fill == null ? NO_FILL : fill;
        this.borderWidth = (float) Math.max(0, Math.min(16, borderWidth));
        this.label = label;
        this.ground = ground;
        this.dot = dot;
    }
}
