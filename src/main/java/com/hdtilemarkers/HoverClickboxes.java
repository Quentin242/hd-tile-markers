package com.hdtilemarkers;

import java.awt.Color;
import java.awt.Shape;
import java.util.List;
import net.runelite.api.*;

/**
 * Clickboxes and hulls as RuneLite's overlays draw them: a border that turns darker while the mouse is over the
 * clickbox, and a light fill.
 */
final class HoverClickboxes
{
    private HoverClickboxes() { }

    /** The mouse must be this close (canvas pixels) to an object's base before its clickbox is computed for the hover test. */
    private static final int HOVER_REACH = 300;

    /** A clickbox mark for the object's first renderable, darker while the mouse is over its clickbox; nothing without one. */
    static void clickbox(List<ModelTarget> out, String key, TileObject object, Color color, Color fill, net.runelite.api.Point mouse)
    {
        Renderable renderable = renderable(object);
        if (renderable == null) { return; }
        Color border = hovered(object, mouse) ? color.darker() : color;
        out.add(ModelTarget.object(key, object, renderable, offsetX(object), offsetY(object), border, fill, 1, true, object::getClickbox));
    }

    /**
     * Whether the mouse is over the object's clickbox, as the overlays test it. RuneLite's clickbox is costly and the
     * scene draws most clickboxes without it, so it is only computed for objects near the mouse.
     */
    static boolean hovered(TileObject object, net.runelite.api.Point mouse)
    {
        if (mouse == null || mouse.getX() < 0) { return false; }
        try
        {
            net.runelite.api.Point base = object.getCanvasLocation();
            if (base == null || Math.abs(base.getX() - mouse.getX()) > HOVER_REACH || Math.abs(base.getY() - mouse.getY()) > HOVER_REACH) { return false; }
            Shape clickbox = object.getClickbox();
            return clickbox != null && clickbox.contains(mouse.getX(), mouse.getY());
        }
        catch (NullPointerException ex)
        {
            // The client's dynamic object model can be unavailable during a scene transition (Sepulchre).
            // Hover colour is optional: retry next tick instead of disabling every scene marker.
            return false;
        }
    }

    /** A convex hull mark as OverlayUtil.renderPolygon draws it: width 2, fill black at alpha 50. */
    static void hull(List<ModelTarget> out, String key, GameObject object, Color color)
    {
        if (object.getRenderable() == null) { return; }
        out.add(ModelTarget.object(key, object, object.getRenderable(), 0, 0, color, new Color(0, 0, 0, 50), 2, false, object::getConvexHull));
    }

    /**
     * Where a decoration's model sits relative to its object (walls, decorations on them): the scene draws the
     * clickbox from the model's own projection, so without it the clickbox showed beside the model.
     */
    static int offsetX(TileObject object) { return object instanceof DecorativeObject ? ((DecorativeObject) object).getXOffset() : 0; }

    static int offsetY(TileObject object) { return object instanceof DecorativeObject ? ((DecorativeObject) object).getYOffset() : 0; }

    static Renderable renderable(TileObject object)
    {
        if (object instanceof GameObject) { return ((GameObject) object).getRenderable(); }
        if (object instanceof WallObject) { return ((WallObject) object).getRenderable1(); }
        if (object instanceof DecorativeObject) { return ((DecorativeObject) object).getRenderable(); }
        if (object instanceof GroundObject) { return ((GroundObject) object).getRenderable(); }
        return null;
    }
}
