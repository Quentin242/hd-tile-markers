package com.hdtilemarkers;

import com.hdtilemarkers.pathmarker.PathMarker;
import com.hdtilemarkers.pathmarker.Pathfinder;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Predicts where a Walk here click goes when the client has no tile under the
 * mouse (for example 117 HD's extended terrain beyond the loaded area) and the
 * path to it. Beyond the loaded area heights and walls are unknown, so the
 * height of the nearest edge is used and the path continues in a straight line.
 */
final class WalkPredictor
{
    static final int MAX_PATH_TILES = 300;

    private WalkPredictor() { }

    /**
     * The world tile where the ray through canvas point (mx, my) meets the
     * terrain, or null. Marches along the ray and refines the crossing.
     */
    static WorldPoint raycast(ModelShapes.Camera camera, float mx, float my, WorldView wv, int plane)
    {
        float[] p = ground(camera, mx, my, wv, plane);
        return p == null ? null
            : new WorldPoint(wv.getBaseX() + (int) Math.floor(p[0] / 128), wv.getBaseY() + (int) Math.floor(p[1] / 128), plane);
    }

    /** The local point {x, y, height} where the ray through canvas point (mx, my) meets the terrain, or null. */
    static float[] ground(ModelShapes.Camera camera, float mx, float my, WorldView wv, int plane)
    {
        float[] p = new float[3];
        float previous = ModelShapes.NEAR;
        for (float depth = ModelShapes.NEAR; depth < 40000; depth += 32)
        {
            camera.unproject(mx, my, depth, p);
            // Heights grow downwards: the ray is below the terrain once its height passes it.
            if (p[2] >= Terrain.height(wv, (int) p[0], (int) p[1], plane))
            {
                float low = previous, high = depth;
                for (int i = 0; i < 12; i++)
                {
                    float mid = (low + high) / 2;
                    camera.unproject(mx, my, mid, p);
                    if (p[2] >= Terrain.height(wv, (int) p[0], (int) p[1], plane)) { high = mid; } else { low = mid; }
                }
                camera.unproject(mx, my, high, p);
                return p;
            }
            previous = depth;
        }
        return null;
    }

    /**
     * Tiles from the player to target: Path Marker's pathfinder up to the nearest
     * reachable tile inside the loaded area, then a straight line of steps, moving
     * diagonally first as the game does.
     */
    static List<PathMarker.SceneTile> path(Player player, Pathfinder pathfinder, WorldPoint target,
        Color stroke, Color fill, Color middleStroke, Color middleFill, boolean dot, boolean running)
    {
        if (player == null || target == null) { return Collections.emptyList(); }
        WorldView wv = player.getWorldView();
        List<WorldPoint> waypoints = new ArrayList<>();
        if (pathfinder != null)
        {
            int x = Math.max(0, Math.min(wv.getSizeX() - 1, target.getX() - wv.getBaseX()));
            int y = Math.max(0, Math.min(wv.getSizeY() - 1, target.getY() - wv.getBaseY()));
            Pair<List<WorldPoint>, Boolean> result = pathfinder.pathTo(x, y, 1, 1, -1, -1);
            if (result != null && result.getLeft() != null) { waypoints.addAll(result.getLeft()); }
        }
        waypoints.add(target);
        List<PathMarker.SceneTile> tiles = new ArrayList<>();
        WorldPoint at = player.getWorldLocation();
        for (WorldPoint waypoint : waypoints)
        {
            while (!(at.getX() == waypoint.getX() && at.getY() == waypoint.getY()) && tiles.size() < MAX_PATH_TILES)
            {
                at = new WorldPoint(at.getX() + Integer.signum(waypoint.getX() - at.getX()),
                    at.getY() + Integer.signum(waypoint.getY() - at.getY()), at.getPlane());
                boolean middle = running && (tiles.size() & 1) == 0 && !at.equals(target);
                tiles.add(new PathMarker.SceneTile(at, middle ? middleStroke : stroke, middle ? middleFill : fill, dot, true));
            }
        }
        return tiles;
    }
}
