/*
 * Display rules adapted from RuneLite's Agility plugin (AgilityOverlay.render and highlightTile, and the
 * shortcut matching of AgilityPlugin.onTileObject; https://github.com/runelite/runelite, tag runelite-parent-1.12.39).
 * Copyright (c) 2018, Adam <Adam@sigterm.info> and Cas <https://github.com/casvandongen>, BSD 2-Clause License;
 * see META-INF/LICENSE-runelite and THIRD_PARTY_NOTICES.md. Changes for HD Tile Markers: the plugin's obstacles,
 * marks of grace and Sepulchre NPCs are read through its public getters and returned as scene shapes.
 */
package com.hdtilemarkers;

import java.awt.Color;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.AgilityShortcut;
import net.runelite.client.plugins.agility.AgilityConfig;
import net.runelite.client.plugins.agility.AgilityPlugin;
import net.runelite.client.util.ColorUtil;

/** The Agility plugin's obstacle clickboxes, shortcuts, traps, marks of grace and Sepulchre NPCs, drawn by HD Tile Markers. */
@Singleton
final class AgilitySource
{
    static final String PLUGIN = "net.runelite.client.plugins.agility.AgilityPlugin";
    static final String OVERLAY = "net.runelite.client.plugins.agility.AgilityOverlay";
    /** The Agility plugin's own limit; HD Tile Markers' draw distance applies when it is larger. */
    private static final int MAX_DISTANCE = 2350;
    private static final Color SHORTCUT_HIGH_LEVEL_COLOR = Color.ORANGE;
    /** OverlayUtil.renderPolygon's fill. */
    private static final Color TILE_FILL = new Color(0, 0, 0, 50);

    private final Client client;
    private final AgilityPlugin plugin;
    private final AgilityConfig config;
    private final HdTileMarkersConfig hdConfig;
    /** The matched shortcut per object; it does not change while the object exists. */
    private final Map<TileObject, Optional<AgilityShortcut>> shortcuts = new IdentityHashMap<>();
    /** AgilityPlugin.stickTile, which the plugin does not expose: tracked the same way. */
    private Tile stickTile;

    @Inject
    AgilitySource(Client client, AgilityPlugin plugin, ConfigManager configs, HdTileMarkersConfig hdConfig)
    {
        this.client = client; this.plugin = plugin; this.hdConfig = hdConfig;
        // Read from ConfigManager, not bound in HD Tile Markers' injector (see BetterNpcView.readConfig).
        config = configs.getConfig(AgilityConfig.class);
    }

    void collect(List<Marker> tiles, List<ModelTarget> models) { collect(tiles, models, Collections.emptySet(), Collections.emptySet()); }

    /**
     * claimedObjects and claimedTiles (local x << 32 | y) are highlighted by Rooftop Agility Improved: the Agility
     * plugin's own marks for them are left out, so each obstacle or mark of grace shows one highlight, not both.
     */
    void collect(List<Marker> tiles, List<ModelTarget> models, Set<TileObject> claimedObjects, Set<Long> claimedTiles)
    {
        CameraFocusableEntity focus = client.getCameraFocusEntity();
        WorldView wv = client.getTopLevelWorldView();
        if (focus == null || wv == null) { return; }
        LocalPoint player = focus.getCameraFocus();
        int plane = wv.getPlane();
        // Extended: as far as marked objects are drawn; the plugin's 2350 cuts obstacles off at about 18 tiles.
        int maxDistance = MarkerSources.pluginRange(hdConfig, MAX_DISTANCE);
        List<Tile> marks = plugin.getMarksOfGrace();
        net.runelite.api.Point mouse = client.getMouseCanvasPosition();
        for (TileObject object : plugin.getObstacles().keySet())
        {
            int id = object.getId();
            if (AgilityObstacles.SHORTCUT_OBSTACLE_IDS.containsKey(id) && !config.highlightShortcuts()
                || AgilityObstacles.TRAP_OBSTACLE_IDS.contains(id) && !config.showTrapOverlay()
                || AgilityObstacles.OBSTACLE_IDS.contains(id) && !config.showClickboxes()
                || AgilityObstacles.SEPULCHRE_OBSTACLE_IDS.contains(id) && !config.highlightSepulchreObstacles()
                || AgilityObstacles.SEPULCHRE_SKILL_OBSTACLE_IDS.contains(id) && !config.highlightSepulchreSkilling())
            { continue; }
            LocalPoint lp = object.getLocalLocation();
            if (object.getPlane() != plane || lp == null || lp.distanceTo(player) >= maxDistance || claimedObjects.contains(object)) { continue; }
            String key = "agility:" + object.getHash();
            if (AgilityObstacles.TRAP_OBSTACLE_IDS.contains(id))
            {
                tiles.add(tile(key, object, config.getTrapColor()));
                continue;
            }
            AgilityShortcut shortcut = shortcuts.computeIfAbsent(object, o -> Optional.ofNullable(shortcut(o))).orElse(null);
            Color color = shortcut == null || shortcut.getLevel() <= plugin.getAgilityLevel() ? config.getOverlayColor() : SHORTCUT_HIGH_LEVEL_COLOR;
            if (config.highlightMarks() && !marks.isEmpty()) { color = config.getMarkColor(); }
            if (AgilityObstacles.PORTAL_OBSTACLE_IDS.contains(id))
            {
                if (!config.highlightPortals()) { continue; }
                color = config.getPortalsColor();
            }
            // Darker while hovered (the clickbox is only computed near the mouse).
            Color border = HoverClickboxes.hovered(object, mouse) ? color.darker() : color;
            Renderable renderable = renderable(object);
            if (renderable == null) { continue; }
            models.add(ModelTarget.object(key, object, renderable, HoverClickboxes.offsetX(object), HoverClickboxes.offsetY(object), border, ColorUtil.colorWithAlpha(color, color.getAlpha() / 5),
                1, true, object::getClickbox));
        }
        shortcuts.keySet().retainAll(plugin.getObstacles().keySet());
        if (config.highlightMarks())
        {
            for (Tile mark : marks)
            {
                LocalPoint lp = mark.getLocalLocation();
                if (lp != null && claimedTiles.contains((long) lp.getX() << 32 | lp.getY())) { continue; }
                groundTile(tiles, "agility:mark:", mark, player, plane, maxDistance, config.getMarkColor());
            }
        }
        if (stickTile != null && config.highlightStick()) { groundTile(tiles, "agility:stick:", stickTile, player, plane, maxDistance, config.stickHighlightColor()); }
        if (config.highlightSepulchreNpcs())
        {
            for (NPC npc : plugin.getNpcs())
            {
                NPCComposition composition = npc.getTransformedComposition();
                LocalPoint lp = npc.getLocalLocation();
                if (lp == null || npc.getWorldView() != wv) { continue; }
                int size = composition == null ? 1 : Math.max(1, composition.getSize());
                Marker m = new Marker("agility:npc:" + npc.getIndex(), lp, plane, size, size, config.sepulchreHighlightColor(), TILE_FILL, 2, null, false);
                m.layer = Marker.OBJECT;
                tiles.add(m);
            }
        }
    }

    /** highlightTile: the tile of a ground item, near the player. */
    private static void groundTile(List<Marker> out, String key, Tile tile, LocalPoint player, int plane, int maxDistance, Color color)
    {
        LocalPoint lp = tile.getLocalLocation();
        if (tile.getPlane() != plane || tile.getItemLayer() == null || lp == null || lp.distanceTo(player) >= maxDistance) { return; }
        Marker m = new Marker(key + lp.getX() + ":" + lp.getY(), lp, plane, 1, 1, color, TILE_FILL, 2, null, false);
        m.layer = Marker.OBJECT;
        out.add(m);
    }

    /** A trap's footprint, as its getCanvasTilePoly. */
    private static Marker tile(String key, TileObject object, Color color)
    {
        int width = 1, height = 1;
        LocalPoint point = object.getLocalLocation();
        if (object instanceof GameObject)
        {
            GameObject go = (GameObject) object;
            width = go.getSceneMaxLocation().getX() - go.getSceneMinLocation().getX() + 1;
            height = go.getSceneMaxLocation().getY() - go.getSceneMinLocation().getY() + 1;
            point = LocalPoint.fromScene(go.getSceneMinLocation().getX(), go.getSceneMinLocation().getY(), object.getWorldView())
                .plus((width - 1) * 64, (height - 1) * 64);
        }
        Marker m = new Marker(key, point, object.getPlane(), Math.max(1, width), Math.max(1, height), color, TILE_FILL, 2, null, false);
        m.layer = Marker.OBJECT;
        return m;
    }

    /** The closest matching shortcut, as AgilityPlugin.onTileObject. */
    private AgilityShortcut shortcut(TileObject object)
    {
        AgilityShortcut closest = null;
        int distance = -1;
        for (AgilityShortcut shortcut : AgilityObstacles.SHORTCUT_OBSTACLE_IDS.get(object.getId()))
        {
            if (!shortcut.matches(client, object)) { continue; }
            if (shortcut.getWorldLocation() == null) { return shortcut; }
            int d = shortcut.getWorldLocation().distanceTo2D(object.getWorldLocation());
            if (closest == null || d < distance) { closest = shortcut; distance = d; }
        }
        return closest;
    }

    private static Renderable renderable(TileObject object)
    {
        if (object instanceof GameObject) { return ((GameObject) object).getRenderable(); }
        if (object instanceof WallObject) { return ((WallObject) object).getRenderable1(); }
        if (object instanceof DecorativeObject) { return ((DecorativeObject) object).getRenderable(); }
        if (object instanceof GroundObject) { return ((GroundObject) object).getRenderable(); }
        return null;
    }

    @Subscribe public void onItemSpawned(ItemSpawned e) { if (e.getItem().getId() == ItemID.WAA_STICK) { stickTile = e.getTile(); } }
    @Subscribe public void onItemDespawned(ItemDespawned e)
    {
        if (e.getItem().getId() == ItemID.WAA_STICK && stickTile == e.getTile()) { stickTile = null; }
    }
}
