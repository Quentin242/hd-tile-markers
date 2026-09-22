/*
 * Display rules adapted from RuneLite's NPC Aggression Timer (NpcAggroAreaOverlay.render and renderPath,
 * https://github.com/runelite/runelite, tag runelite-parent-1.12.39), copyright (c) 2018 Woox,
 * BSD 2-Clause License; see META-INF/LICENSE-runelite and THIRD_PARTY_NOTICES.md. Changes for Better
 * Indicator Renderer: the plugin's own area lines are read through its public getters and returned
 * as scene lines instead of drawn.
 */
package com.hdtilemarkers;

import java.awt.Color;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.npcunaggroarea.NpcAggroAreaConfig;
import net.runelite.client.plugins.npcunaggroarea.NpcAggroAreaPlugin;

/** NPC Aggression Timer's unaggressive area lines, drawn by HD Tile Markers. */
@Singleton
final class AggroAreaSource
{
    static final String PLUGIN = "net.runelite.client.plugins.npcunaggroarea.NpcAggroAreaPlugin";
    static final String OVERLAY = "net.runelite.client.plugins.npcunaggroarea.NpcAggroAreaOverlay";
    /** As the overlay: only lines within 20 tiles of the player. */
    private static final int MAX_LOCAL_DRAW_LENGTH = 20 * Perspective.LOCAL_TILE_SIZE;
    /** Tile edges per scene line, so each piece stays well inside one scene object. */
    private static final int EDGES_PER_LINE = 6;
    /** The overlay's stroke width. */
    private static final int WIDTH = 1;

    private final Client client;
    private final NpcAggroAreaPlugin plugin;
    private final NpcAggroAreaConfig config;

    @Inject
    AggroAreaSource(Client client, NpcAggroAreaPlugin plugin, ConfigManager configs)
    {
        this.client = client; this.plugin = plugin;
        // Read from ConfigManager, not bound in HD Tile Markers' injector (see BetterNpcView.readConfig).
        config = configs.getConfig(NpcAggroAreaConfig.class);
    }

    void collect(List<Marker> out)
    {
        Player player = client.getLocalPlayer();
        WorldView wv = client.getTopLevelWorldView();
        if (player == null || wv == null || !plugin.isActive() || plugin.getSafeCenters()[1] == null) { return; }
        if (player.getHealthScale() == -1 && config.hideIfOutOfCombat()) { return; }
        GeneralPath[] all = plugin.getLinesToDisplay();
        int plane = wv.getPlane();
        GeneralPath lines = plane < all.length ? all[plane] : null;
        if (lines == null) { return; }
        // The aggressive colour while the timer runs, then the unaggressive one.
        Color color = config.unaggroAreaColor();
        Instant end = plugin.getEndTime();
        if (color == null || (end != null && Instant.now().isBefore(end))) { color = config.aggroAreaColor(); }
        if (color == null) { return; }
        lines(lines, player.getLocalLocation(), plane, color, wv.getId(), out);
    }

    /** Splits the path into short polylines of connected tile edges near the player. */
    static void lines(GeneralPath path, LocalPoint player, int plane, Color color, int worldView, List<Marker> out)
    {
        float[] c = new float[6];
        List<int[]> current = new ArrayList<>();
        int n = 0, startX = 0, startY = 0;
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next())
        {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) { startX = Math.round(c[0]); startY = Math.round(c[1]); }
            // A close is a line back to the subpath's start.
            if (type == PathIterator.SEG_CLOSE) { c[0] = startX; c[1] = startY; type = PathIterator.SEG_LINETO; }
            int x = Math.round(c[0]), y = Math.round(c[1]);
            boolean near = Math.abs(x - player.getX()) <= MAX_LOCAL_DRAW_LENGTH && Math.abs(y - player.getY()) <= MAX_LOCAL_DRAW_LENGTH;
            int[] last = current.isEmpty() ? null : current.get(current.size() - 1);
            if (last != null && last[0] == x && last[1] == y) { continue; }
            if (type == PathIterator.SEG_LINETO && near && last != null)
            {
                current.add(new int[]{x, y});
                if (current.size() > EDGES_PER_LINE)
                {
                    n = emit(current, plane, color, worldView, n, out);
                    int[] end = current.get(current.size() - 1);
                    current.clear();
                    current.add(end);
                }
                continue;
            }
            n = emit(current, plane, color, worldView, n, out);
            current.clear();
            if ((type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) && near) { current.add(new int[]{x, y}); }
        }
        emit(current, plane, color, worldView, n, out);
    }

    private static int emit(List<int[]> points, int plane, Color color, int worldView, int n, List<Marker> out)
    {
        if (points.size() < 2) { return n; }
        int[] xs = new int[points.size()], ys = new int[points.size()];
        for (int i = 0; i < xs.length; i++) { xs[i] = points.get(i)[0]; ys[i] = points.get(i)[1]; }
        int[] middle = points.get(points.size() / 2);
        Marker m = new Marker("aggro:" + n, new LocalPoint(middle[0], middle[1], worldView), plane, 1, 1, color, Marker.NO_FILL,
            WIDTH, null, false);
        m.lineX = xs; m.lineY = ys;
        m.layer = Marker.AGGRO_AREA;
        out.add(m);
        return n + 1;
    }
}
