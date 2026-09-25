/*
 * Ground mark loading follows RuneLite's Ground Markers (GroundMarkerPlugin.loadPoints) and NPC selection
 * follows NPC Indicators (getHighlights, highlightMatchesNPCName, render) of https://github.com/runelite/runelite,
 * BSD 2-Clause License, copyright (c) 2018 TheLonelyDev, James Swindle, Adam and Tomas Slusny; see
 * META-INF/LICENSE-runelite and THIRD_PARTY_NOTICES.md. Changes for HD Tile Markers: read-only,
 * marks are returned to HD Tile Markers' renderer instead of drawn.
 */
package com.hdtilemarkers;

import com.hdtilemarkers.pathmarker.PathMarker;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.awt.Color;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.groundmarkers.GroundMarkerConfig;
import net.runelite.client.plugins.npchighlight.NpcIndicatorsConfig;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;

/**
 * Collects what to draw. Marks come from the RuneLite plugins that own them:
 * Ground Markers (Mark tile), Object Markers (Mark object) and NPC Indicators
 * (Tag-All and its list). HD Tile Markers only reads their saved configuration.
 */
@Singleton
final class MarkerSources
{
    /** Upper bound of the draw distance option; the loaded area limits it further in practice. */
    static final int MAX_DISTANCE = 200;

    /**
     * The range in local units for marks of a plugin with its own limit: that limit, or with "Extend plugin ranges"
     * as far as the draw distance when that is larger.
     */
    static int pluginRange(HdTileMarkersConfig config, int own)
    {
        return !config.extendRanges() ? own : Math.max(own, Math.max(8, Math.min(MAX_DISTANCE, config.distance())) * 128);
    }

    private final Client client;
    private final ConfigManager configs;
    private final Gson gson;
    private final HdTileMarkersConfig config;
    private final GroundMarkerConfig groundConfig;
    private final NpcIndicatorsConfig npcConfig;
    private final ObjectMarkerSource objectMarkers;
    private final TilePackSource tilePacks;
    private final List<Marker> tilePackMarkers = new ArrayList<>();
    /** Set by the plugin: whether the Tile Packs plugin runs and HD Tile Markers draws its tiles. */
    boolean tilePacksEnabled;
    private final Set<NPC> npcs = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Marker> ground = new ArrayList<>();
    private List<String> npcHighlights = Collections.emptyList();
    private boolean validGround = true;
    // Fadeout and predicted destination state.
    private WorldPoint lastPlayerTile, lastDestination, predicted;
    private long stillSince, arrivedAt, predictedAt;
    /** Last hitsplat on you or your target; combat lasts COMBAT_MS after it. */
    private long lastHit;
    static final long COMBAT_MS = 6000;
    private int predictedTick;
    private boolean predictionConfirmed;
    private WorldView predictedWorld;
    /** Whether the owning RuneLite plugins are enabled; HD Tile Markers draws their marks only then. */
    boolean groundEnabled = true, objectsEnabled = true, npcsEnabled = true;

    @Inject
    MarkerSources(Client client, ConfigManager configs, Gson gson, HdTileMarkersConfig config, ObjectMarkerSource objectMarkers,
        TilePackSource tilePacks)
    {
        this.client = client; this.configs = configs; this.gson = gson; this.config = config; this.objectMarkers = objectMarkers;
        this.tilePacks = tilePacks;
        groundConfig = configs.getConfig(GroundMarkerConfig.class);
        npcConfig = configs.getConfig(NpcIndicatorsConfig.class);
    }

    void rebuild()
    {
        clear();
        // NPC Indicators' list, as its getHighlights(): Tag-All adds names here.
        String list = configs.getConfiguration(NpcIndicatorsConfig.GROUP, "npcToHighlight");
        npcHighlights = list == null || list.isEmpty() ? Collections.emptyList() : Text.fromCSV(list);
        visit(client.getTopLevelWorldView());
    }

    private void visit(WorldView wv)
    {
        if (wv == null) { return; }
        // Load saved points before matching the scene's objects against them.
        objectMarkers.load(wv);
        for (NPC npc : wv.npcs()) { add(npc); }
        Tile[][][] tiles = wv.getScene().getTiles();
        for (Tile[][] plane : tiles)
        {
            for (Tile[] row : plane)
            {
                for (Tile tile : row)
                {
                    if (tile == null) { continue; }
                    add(tile.getWallObject()); add(tile.getDecorativeObject()); add(tile.getGroundObject());
                    for (GameObject object : tile.getGameObjects()) { add(object); }
                }
            }
        }
        if (wv.getMapRegions() != null)
        {
            for (int region : wv.getMapRegions()) { loadGround(wv, region); }
        }
        tilePackMarkers.addAll(tilePacks.markers(wv));
        for (WorldView child : wv.worldViews()) { visit(child); }
    }

    /** Ground Markers' saved points per region, parsed once per saved text: every scene load read them again. */
    private final Map<Integer, ParsedGround> parsedGround = new HashMap<>();

    private static final class ParsedGround
    {
        final String json;
        final List<GroundPoint> points = new ArrayList<>();
        boolean invalid;
        ParsedGround(String json) { this.json = json; }
    }

    private ParsedGround parseGround(int region, String json)
    {
        ParsedGround cached = parsedGround.get(region);
        if (cached != null && cached.json.equals(json)) { return cached; }
        ParsedGround parsed = new ParsedGround(json);
        try
        {
            for (JsonElement element : new JsonParser().parse(json).getAsJsonArray())
            {
                GroundPoint p = gson.fromJson(element, GroundPoint.class);
                if (p == null || p.regionId != region || p.regionX < 0 || p.regionX > 63
                    || p.regionY < 0 || p.regionY > 63 || p.z < 0 || p.z > 3)
                { parsed.invalid = true; continue; }
                parsed.points.add(p);
            }
        }
        catch (RuntimeException ex) { parsed.invalid = true; }
        parsedGround.put(region, parsed);
        return parsed;
    }

    private void loadGround(WorldView wv, int region)
    {
        String json = configs.getConfiguration("groundMarker", "region_" + region);
        if (json == null || json.isEmpty()) { return; }
        ParsedGround parsed = parseGround(region, json);
        if (parsed.invalid) { validGround = false; }
        if (parsed.points.isEmpty()) { return; }
        // Ground Markers' own default colour, fill and border width.
        Color defaultColor = groundConfig.markerColor();
        Color fill = new Color(0, 0, 0, Math.max(0, Math.min(255, groundConfig.fillOpacity())));
        double width = groundConfig.borderWidth();
        for (GroundPoint p : parsed.points)
        {
            WorldPoint world = WorldPoint.fromRegion(region, p.regionX, p.regionY, p.z);
            for (WorldPoint instance : WorldPoint.toLocalInstance(wv, world))
            {
                LocalPoint local = LocalPoint.fromWorld(wv, instance);
                if (local == null) { continue; }
                ground.add(layer(Marker.GROUND, new Marker("ground:" + wv.getId() + ":" + instance, local, instance.getPlane(), 1, 1,
                    p.color == null ? defaultColor : p.color, fill, width, p.label, true)));
            }
        }
    }

    List<Marker> collect(List<PathMarker.SceneTile> pathTiles)
    {
        visible = null;
        List<Marker> result = new ArrayList<>();
        if (config.ground() && groundEnabled) { result.addAll(ground); }
        if (tilePacksEnabled) { result.addAll(tilePackMarkers); }
        Player player = client.getLocalPlayer();
        if (player == null) { return Collections.emptyList(); }
        WorldView playerWorld = player.getWorldView();
        int plane = playerWorld.getPlane();
        // Path tiles first so tile indicators on the same tile are drawn on top.
        for (PathMarker.SceneTile tile : pathTiles)
        {
            // Predicted path tiles can lie beyond the loaded area.
            LocalPoint point = local(playerWorld, tile.point);
            if (point != null)
            {
                result.add(layer(tile.active ? Marker.PATH_ACTIVE : Marker.PATH_HOVER, new Marker((tile.active ? "path:a:" : "path:h:") + tile.point.getX() + ":" + tile.point.getY(),
                    point, plane, 1, 1, tile.stroke, tile.fill, tile.dot ? 2 : config.pathBorderWidth(), null, false, tile.dot)));
            }
        }
        // Tile Indicators.
        long now = System.currentTimeMillis();
        if (config.highlightDestinationTile())
        {
            Marker destination = destination(playerWorld, player, plane, now);
            if (destination != null) { result.add(destination); }
        }
        if (config.highlightCurrentTile())
        {
            WorldPoint tile = player.getWorldLocation();
            if (!tile.equals(lastPlayerTile)) { lastPlayerTile = tile; stillSince = now; }
            // Out-of-combat only: the fade (and its delay) restarts after combat.
            if (config.currentTileFadeoutOutOfCombat() && inCombat(player, now)) { stillSince = now; }
            LocalPoint point = LocalPoint.fromWorld(playerWorld, tile);
            // As Corner Tile Indicators: fade out once the player stops moving.
            float alpha = config.currentTileFadeout() ? fade(now - stillSince - config.currentTileFadeoutDelay(), config.currentTileFadeoutTime()) : 1;
            if (point != null && alpha > 0)
            {
                result.add(corners(config.currentTileCornersOnly(), config.currentTileCornerSize(),
                    layer(Marker.CURRENT, new Marker("current", point, plane, 1, 1, scale(config.highlightCurrentColor(), alpha),
                    scale(config.currentTileFillColor(), alpha), config.currentTileBorderWidth(), null, false))));
            }
        }
        // NPC Indicators' NPCs, in its tile styles.
        for (NPC npc : npcs)
        {
            if (!npcsEnabled || !renderNpc(npc)) { continue; }
            NPCComposition composition = npc.getTransformedComposition();
            if (composition == null || composition.getSize() < 1 || composition.getSize() > 64) { continue; }
            int size = composition.getSize(), npcPlane = npc.getWorldView().getPlane();
            String key = "npc:" + npc.getWorldView().getId() + ":" + npc.getIndex();
            LocalPoint local = npc.getLocalLocation(), trueSw = LocalPoint.fromWorld(npc.getWorldView(), npc.getWorldLocation());
            int offset = (size - 1) * 64;
            String style = npcStyle(npc);
            Color color = npcColor(npc);
            if (style(style, "tile", npcConfig.highlightTile()) && local != null)
            { result.add(layer(Marker.NPC_TILE, npcMarker(key + ":tile", local, npcPlane, size, color))); }
            if (style(style, "truetile", npcConfig.highlightTrueTile()) && trueSw != null)
            { result.add(layer(Marker.NPC_TILE + 1, npcMarker(key + ":true", trueSw.plus(offset, offset), npcPlane, size, color))); }
            if (style(style, "swtile", npcConfig.highlightSouthWestTile()) && local != null)
            { result.add(layer(Marker.NPC_TILE + 2, npcMarker(key + ":sw", local.plus(-offset, -offset), npcPlane, 1, color))); }
            if (style(style, "swtruetile", npcConfig.highlightSouthWestTrueTile()) && trueSw != null)
            { result.add(layer(Marker.NPC_TILE + 3, npcMarker(key + ":swtrue", trueSw, npcPlane, 1, color))); }
        }
        // Object Markers' tile style: its tile stroke is capped at 2.
        for (ObjectMarkerSource.Resolved o : visibleObjects())
        {
            if ((o.flags & ObjectMarkerSource.HF_TILE) == 0) { continue; }
            TileObject object = o.object;
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
            if (width > 0 && height > 0 && width <= 64 && height <= 64)
            {
                result.add(layer(Marker.OBJECT, new Marker("object:" + object.getWorldView().getId() + ":" + object.getHash() + ":tile", point, object.getPlane(),
                    width, height, o.border, o.otherFill, Math.min(o.borderWidth, 2), null, false)));
            }
        }
        int distance = Math.max(8, Math.min(MAX_DISTANCE, config.distance())) * 128;
        LocalPoint origin = player.getLocalLocation();
        result.removeIf(m -> {
            WorldView wv = client.getWorldView(m.point.getWorldView());
            // Draw distance limits the many saved markers, not your own tile, destination and path,
            // which can be far away when the renderer draws further than it.
            boolean own = m.key.equals("destination") || m.key.equals("current") || m.key.startsWith("path:");
            return wv == null || m.plane != wv.getPlane() || m.color.getAlpha() == 0 && m.fill.getAlpha() == 0
                || (!own && m.point.getWorldView() == origin.getWorldView()
                    && m.point.distanceTo(origin) > distance);
        });
        return result;
    }

    /**
     * The destination tile: the predicted walk target while it is active and the
     * client confirms a route beyond the loaded area, else the
     * client's destination, else the last destination fading out after arrival.
     */
    private Marker destination(WorldView wv, Player player, int plane, long now)
    {
        LocalPoint local = client.getLocalDestinationLocation();
        WorldPoint target = null;
        LocalPoint point = null;
        float alpha = 1;
        WorldPoint prediction = predictedTarget(player, now);
        if (prediction != null && local != null && outside(wv, prediction))
        {
            target = prediction;
            point = local(wv, prediction);
        }
        else if (local != null)
        {
            target = WorldPoint.fromLocal(wv, local.getX(), local.getY(), plane);
            // Clicking the tile you stand on is not shown. A walk that just arrived there keeps its
            // destination (already remembered), so it does not blink before the fadeout.
            if (target.equals(player.getWorldLocation()) && !target.equals(lastDestination)) { return null; }
            lastDestination = target;
            arrivedAt = 0;
        }
        else if (lastDestination != null && config.destinationTileFadeout())
        {
            if (arrivedAt == 0 || config.destinationTileFadeoutOutOfCombat() && inCombat(player, now)) { arrivedAt = now; }
            alpha = fade(now - arrivedAt - config.destinationTileFadeoutDelay(), config.destinationTileFadeoutTime());
            target = lastDestination;
            if (alpha <= 0) { lastDestination = null; }
        }
        if (point == null && target != null) { point = LocalPoint.fromWorld(wv, target); }
        if (point == null || alpha <= 0) { return null; }
        return corners(config.destinationTileCornersOnly(), config.destinationTileCornerSize(),
            layer(Marker.DESTINATION, new Marker("destination", point, plane, 1, 1, scale(config.highlightDestinationColor(), alpha),
            scale(config.destinationTileFillColor(), alpha), config.destinationTileBorderWidth(), null, false)));
    }

    /** Records a hitsplat; hits on you or on what you are interacting with count as combat. */
    void hitsplat(Actor target, long now)
    {
        Player player = client.getLocalPlayer();
        if (player != null && target != null && (target == player || target == player.getInteracting())) { lastHit = now; }
    }

    /**
     * In combat: a recent hitsplat on you or your target, or you and an attackable NPC
     * interacting with each other. Current state only, nothing is predicted.
     */
    boolean inCombat(Player player, long now)
    {
        if (lastHit != 0 && now - lastHit < COMBAT_MS) { return true; }
        if (attackable(player.getInteracting())) { return true; }
        WorldView wv = player.getWorldView();
        if (wv == null) { return false; }
        for (NPC npc : wv.npcs())
        {
            if (npc != null && npc.getInteracting() == player && attackable(npc)) { return true; }
        }
        return false;
    }

    private static boolean attackable(Actor actor)
    {
        if (actor instanceof Player) { return true; }
        if (!(actor instanceof NPC) || ((NPC) actor).isDead()) { return false; }
        NPCComposition c = ((NPC) actor).getTransformedComposition();
        if (c == null || c.getActions() == null) { return false; }
        for (String action : c.getActions()) { if ("Attack".equals(action)) { return true; } }
        return false;
    }

    /** Discard unaccepted clicks after two game ticks, and confirmed routes as soon as they end. */
    WorldPoint predictedTarget(Player player, long now)
    {
        if (predicted == null || !config.predictWalk()) { return null; }
        WorldPoint current = player.getWorldLocation();
        boolean hasDestination = client.getLocalDestinationLocation() != null;
        if (player.getWorldView() != predictedWorld || now - predictedAt >= 30000
            || predicted.equals(current) || (current != null && predicted.getPlane() != current.getPlane())
            || (!hasDestination && (predictionConfirmed || client.getTickCount() - predictedTick >= 2)))
        {
            predicted = null;
            return null;
        }
        if (hasDestination) { predictionConfirmed = true; }
        return predicted;
    }

    /** Whether a world point lies beyond the loaded area. */
    static boolean outside(WorldView wv, WorldPoint p)
    {
        int x = p.getX() - wv.getBaseX(), y = p.getY() - wv.getBaseY();
        return x < 0 || y < 0 || x >= wv.getSizeX() || y >= wv.getSizeY();
    }

    /** A local point for any world point, also beyond the loaded area. */
    static LocalPoint local(WorldView wv, WorldPoint p)
    {
        LocalPoint inside = outside(wv, p) ? null : LocalPoint.fromWorld(wv, p);
        if (inside != null) { return inside; }
        return new LocalPoint((p.getX() - wv.getBaseX()) * 128 + 64, (p.getY() - wv.getBaseY()) * 128 + 64, wv.getId());
    }

    /** Remembers the tile clicked with Walk here, for the predicted destination and path. */
    void walkedTo(WorldPoint tile)
    {
        Player player = client.getLocalPlayer();
        // Clicking the tile you already stand on goes nowhere: no prediction, no destination.
        predicted = player != null && tile != null && tile.equals(player.getWorldLocation()) ? null : tile;
        predictedAt = System.currentTimeMillis();
        predictedTick = client.getTickCount();
        predictionConfirmed = false;
        predictedWorld = player == null ? null : player.getWorldView();
        lastDestination = null;
    }

    /** 1 until elapsed reaches 0 (the delay has been subtracted), then down to 0 over time. */
    private static float fade(long elapsed, int time) { return elapsed <= 0 ? 1 : Math.max(0, 1 - elapsed / (float) Math.max(1, time)); }

    private static Color scale(Color c, float alpha)
    { return alpha >= 1 ? c : new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(c.getAlpha() * alpha)); }

    private static Marker layer(int layer, Marker m) { m.layer = layer; return m; }

    private static Marker corners(boolean only, int divisor, Marker m) { m.cornerDivisor = only ? Math.max(2, divisor) : 0; return m; }

    /**
     * The hovered tile, read every frame rather than every client tick: the
     * client picks it while drawing, so it changes faster than markers.
     */
    Marker hover()
    {
        Player player = client.getLocalPlayer();
        if (!config.highlightHoveredTile() || player == null) { return null; }
        Tile hovered = player.getWorldView().getSelectedSceneTile();
        if (hovered == null || hovered.getLocalLocation() == null) { return null; }
        Marker m = new Marker("hover", hovered.getLocalLocation(), hovered.getPlane(), 1, 1, config.highlightHoveredColor(),
            config.hoveredTileFillColor(), config.hoveredTileBorderWidth(), null, false);
        return m.color.getAlpha() == 0 && m.fill.getAlpha() == 0 ? null
            : corners(config.hoveredTileCornersOnly(), config.hoveredTileCornerSize(), layer(Marker.HOVER, m));
    }

    private Marker npcMarker(String key, LocalPoint point, int plane, int size, Color color)
    { return new Marker(key, point, plane, size, size, color, npcConfig.fillColor(), npcConfig.borderWidth(), null, false); }

    /** NPC Indicators' per-NPC style overrides its global styles, as in that plugin. */
    private static boolean style(String override, String name, boolean configured)
    { return override != null ? override.equals(name) : configured; }

    /**
     * NPC Indicators' per-NPC style and colour, looked up once per NPC id: they are read several times
     * per frame. A change of its settings rebuilds the sources, which clears these.
     */
    private final Map<Integer, Optional<String>> styles = new HashMap<>();
    private final Map<Integer, Color> colors = new HashMap<>();

    private String npcStyle(NPC npc)
    {
        return styles.computeIfAbsent(npc.getId(),
            id -> Optional.ofNullable(configs.getConfiguration(NpcIndicatorsConfig.GROUP, "tagstyle_" + id))).orElse(null);
    }

    private Color npcColor(NPC npc)
    {
        return colors.computeIfAbsent(npc.getId(), id -> {
            Color color = configs.getConfiguration(NpcIndicatorsConfig.GROUP, "highlightcolor_" + id, Color.class);
            return color != null ? color : npcConfig.highlightColor();
        });
    }


    /** NPC Indicators' render(): dead NPCs and pets follow its settings. */
    private boolean renderNpc(NPC npc)
    {
        if (npc.isDead() && npcConfig.ignoreDeadNpcs()) { return false; }
        NPCComposition composition = npc.getTransformedComposition();
        return composition == null || !(composition.isFollower() && npcConfig.ignorePets());
    }

    /** Hulls and clickboxes, nearest first. */
    List<ModelTarget> modelTargets()
    {
        Player player = client.getLocalPlayer();
        WorldView top = client.getTopLevelWorldView();
        if (player == null || top == null) { return Collections.emptyList(); }
        List<ModelTarget> result = new ArrayList<>();
        for (NPC npc : npcs)
        {
            if (npcsEnabled && npc.getWorldView() == top && renderNpc(npc) && style(npcStyle(npc), "outline", npcConfig.highlightOutline()))
            {
                result.add(ModelTarget.npcOutline("npc:" + npc.getIndex() + ":outline", npc, npcColor(npc), npcConfig.borderWidth()));
            }
            if (!npcsEnabled || npc.getWorldView() != top || !renderNpc(npc) || !style(npcStyle(npc), "hull", npcConfig.highlightHull())) { continue; }
            result.add(ModelTarget.npc("npc:" + npc.getIndex() + ":hull", npc, npcColor(npc), npcConfig.fillColor(), npcConfig.borderWidth()));
        }
        for (ObjectMarkerSource.Resolved o : visibleObjects())
        {
            TileObject object = o.object;
            if (object.getWorldView() != top) { continue; }
            String key = "object:" + object.getHash();
            if ((o.flags & ObjectMarkerSource.HF_HULL) != 0) { addHulls(result, key, object, o); }
            if ((o.flags & ObjectMarkerSource.HF_OUTLINE) != 0) { addOutlines(result, key, object, o); }
            if ((o.flags & ObjectMarkerSource.HF_CLICKBOX) != 0)
            {
                Renderable renderable = firstRenderable(object);
                if (renderable != null)
                {
                    result.add(ModelTarget.object(key + ":clickbox", object, renderable, HoverClickboxes.offsetX(object), HoverClickboxes.offsetY(object),
                        o.border, o.otherFill,
                        o.borderWidth, true, object::getClickbox));
                }
            }
        }
        LocalPoint origin = player.getLocalLocation();
        int distance = Math.max(8, Math.min(MAX_DISTANCE, config.distance())) * 128;
        // Each target's distance once: an object's location() is a new LocalPoint per call.
        Map<ModelTarget, Integer> distances = new IdentityHashMap<>();
        for (ModelTarget t : result)
        {
            LocalPoint location = t.location();
            if (location != null) { distances.put(t, location.distanceTo(origin)); }
        }
        result.removeIf(t -> { Integer d = distances.get(t); return d == null || d > distance; });
        result.sort(Comparator.comparingInt(distances::get));
        return result;
    }

    /** Hull parts as in ObjectIndicatorsOverlay.renderConvexHull: walls and decorations have two. */
    private static void addHulls(List<ModelTarget> out, String key, TileObject object, ObjectMarkerSource.Resolved o)
    {
        if (object instanceof GameObject)
        {
            GameObject go = (GameObject) object;
            out.add(ModelTarget.object(key + ":hull", object, go.getRenderable(), 0, 0, o.border, o.hullFill, o.borderWidth, false, go::getConvexHull));
        }
        else if (object instanceof WallObject)
        {
            WallObject wall = (WallObject) object;
            out.add(ModelTarget.object(key + ":hull", object, wall.getRenderable1(), 0, 0, o.border, o.hullFill, o.borderWidth, false, wall::getConvexHull));
            out.add(ModelTarget.object(key + ":hull2", object, wall.getRenderable2(), 0, 0, o.border, o.hullFill, o.borderWidth, false, wall::getConvexHull2));
        }
        else if (object instanceof DecorativeObject)
        {
            DecorativeObject decor = (DecorativeObject) object;
            out.add(ModelTarget.object(key + ":hull", object, decor.getRenderable(), decor.getXOffset(), decor.getYOffset(),
                o.border, o.hullFill, o.borderWidth, false, decor::getConvexHull));
            out.add(ModelTarget.object(key + ":hull2", object, decor.getRenderable2(), decor.getXOffset2(), decor.getYOffset2(),
                o.border, o.hullFill, o.borderWidth, false, decor::getConvexHull2));
        }
        else if (object instanceof GroundObject)
        {
            GroundObject groundObject = (GroundObject) object;
            out.add(ModelTarget.object(key + ":hull", object, groundObject.getRenderable(), 0, 0, o.border, o.hullFill, o.borderWidth,
                false, groundObject::getConvexHull));
        }
        out.removeIf(t -> t.renderable == null && t.npc == null);
    }

    /** Outline parts, one per renderable like the hull. */
    private static void addOutlines(List<ModelTarget> out, String key, TileObject object, ObjectMarkerSource.Resolved o)
    {
        if (object instanceof GameObject)
        {
            out.add(ModelTarget.objectOutline(key + ":outline", object, ((GameObject) object).getRenderable(), 0, 0, o.border, o.borderWidth));
        }
        else if (object instanceof WallObject)
        {
            WallObject wall = (WallObject) object;
            out.add(ModelTarget.objectOutline(key + ":outline", object, wall.getRenderable1(), 0, 0, o.border, o.borderWidth));
            out.add(ModelTarget.objectOutline(key + ":outline2", object, wall.getRenderable2(), 0, 0, o.border, o.borderWidth));
        }
        else if (object instanceof DecorativeObject)
        {
            DecorativeObject decor = (DecorativeObject) object;
            out.add(ModelTarget.objectOutline(key + ":outline", object, decor.getRenderable(), decor.getXOffset(), decor.getYOffset(), o.border, o.borderWidth));
            out.add(ModelTarget.objectOutline(key + ":outline2", object, decor.getRenderable2(), decor.getXOffset2(), decor.getYOffset2(), o.border, o.borderWidth));
        }
        else if (object instanceof GroundObject)
        {
            out.add(ModelTarget.objectOutline(key + ":outline", object, ((GroundObject) object).getRenderable(), 0, 0, o.border, o.borderWidth));
        }
        out.removeIf(t -> t.renderable == null && t.npc == null);
    }

    private static Renderable firstRenderable(TileObject object)
    {
        if (object instanceof GameObject) { return ((GameObject) object).getRenderable(); }
        if (object instanceof WallObject) { return ((WallObject) object).getRenderable1(); }
        if (object instanceof DecorativeObject) { return ((DecorativeObject) object).getRenderable(); }
        if (object instanceof GroundObject) { return ((GroundObject) object).getRenderable(); }
        return null;
    }

    /** Object Markers entries for the 2D outline style, which HD Tile Markers draws with RuneLite's outline renderer. */
    List<ObjectMarkerSource.Resolved> objectOutlines()
    {
        List<ObjectMarkerSource.Resolved> result = new ArrayList<>();
        if (!objectsEnabled()) { return result; }
        for (ObjectMarkerSource.Resolved o : visibleObjects())
        {
            if ((o.flags & ObjectMarkerSource.HF_OUTLINE) != 0) { result.add(o); }
        }
        return result;
    }

    /**
     * Object Markers' visible marks, resolved at most once per tick (collect() starts a new one)
     * and shared by the lookups that follow; objects spawning or despawning resolve them again.
     */
    private List<ObjectMarkerSource.Resolved> visible;

    private List<ObjectMarkerSource.Resolved> visibleObjects()
    {
        if (visible == null) { visible = objectsEnabled() ? objectMarkers.visible() : Collections.emptyList(); }
        return visible;
    }

    private boolean objectsEnabled() { return objectsEnabled && config.objectMarkers(); }

    /** NPCs with NPC Indicators' outline style, which the overlay draws in 2D without the scene route. */
    List<NPC> npcOutlines()
    {
        List<NPC> result = new ArrayList<>();
        if (!npcsEnabled) { return result; }
        for (NPC npc : npcs) { if (renderNpc(npc) && style(npcStyle(npc), "outline", npcConfig.highlightOutline())) { result.add(npc); } }
        return result;
    }

    Color outlineColor(NPC npc) { return npcColor(npc); }
    NpcIndicatorsConfig npcConfig() { return npcConfig; }

    /** NPC Indicators' name match: the Tag-All list, with wildcards. */
    boolean isHighlighted(NPC npc)
    {
        String name = npc.getName();
        if (name == null) { return false; }
        for (String highlight : npcHighlights) { if (WildcardMatcher.matches(highlight, name)) { return true; } }
        return false;
    }

    void add(TileObject object) { objectMarkers.check(object); visible = null; }
    void remove(TileObject object) { objectMarkers.remove(object); visible = null; }
    void add(NPC npc) { npcs.remove(npc); if (isHighlighted(npc)) { npcs.add(npc); } }
    void remove(NPC npc) { npcs.remove(npc); }
    /** A world view that loaded inside the scene (a boat): its markers, without rebuilding the rest. */
    void addWorldView(WorldView wv) { removeWorldView(wv); visit(wv); visible = null; }

    void removeWorldView(WorldView wv)
    {
        if (wv == null) { return; }
        objectMarkers.removeWorldView(wv);
        npcs.removeIf(n -> n.getWorldView() == wv);
        ground.removeIf(m -> m.point.getWorldView() == wv.getId());
        tilePackMarkers.removeIf(m -> m.point.getWorldView() == wv.getId());
        visible = null;
    }
    boolean validGround() { return validGround; }
    boolean validObjects() { return objectMarkers.valid(); }
    void clear() { styles.clear(); colors.clear(); visible = null; predicted = null; predictedWorld = null; predictionConfirmed = false;
        lastPlayerTile = null; lastDestination = null; stillSince = 0; arrivedAt = 0; lastHit = 0;
        tilePackMarkers.clear(); ground.clear(); npcs.clear(); objectMarkers.clear(); npcHighlights = Collections.emptyList(); validGround = true; }

    private static final class GroundPoint
    {
        int regionId, regionX, regionY, z;
        Color color;
        String label;
    }
}
