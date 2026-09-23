package com.hdtilemarkers;

import com.hdtilemarkers.pathmarker.PathMarker;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.groundmarkers.GroundMarkerPlugin;
import net.runelite.client.plugins.groundmarkers.GroundMarkerOverlay;
import net.runelite.client.plugins.npchighlight.NpcIndicatorsPlugin;
import net.runelite.client.plugins.objectindicators.ObjectIndicatorsPlugin;
import net.runelite.client.ui.overlay.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(name = "HD Tile Markers", description = "Sharp tile, NPC, object and path markers drawn in the game world, also in stretched mode",
    tags = {"tiles", "markers", "npcs", "objects", "path", "stretched"})
@PluginDependency(GroundMarkerPlugin.class)
@PluginDependency(ObjectIndicatorsPlugin.class)
@PluginDependency(NpcIndicatorsPlugin.class)
@PluginDependency(net.runelite.client.plugins.npcunaggroarea.NpcAggroAreaPlugin.class)
@PluginDependency(net.runelite.client.plugins.agility.AgilityPlugin.class)
// The Agility plugin injects the XP Tracker's service: RuneLite does not load a dependency's own dependencies.
@PluginDependency(net.runelite.client.plugins.xptracker.XpTrackerPlugin.class)
// Better NPC Highlight's task highlighting reads the Slayer plugin's task.
@PluginDependency(net.runelite.client.plugins.slayer.SlayerPlugin.class)
public class HdTileMarkersPlugin extends Plugin
{
    static final int MAX_TILES = 1500, MAX_MODELS = 64;
    private static final Logger log = LoggerFactory.getLogger(HdTileMarkersPlugin.class);
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private EventBus eventBus;
    @Inject private HdTileMarkersConfig config;
    @Inject private MarkerSources sources;
    @Inject private SceneShapeRenderer renderer;
    @Inject private PathMarker pathMarker;
    @Inject private com.hdtilemarkers.betternpc.BetterNpcEvents betterNpcEvents;
    @Inject private com.hdtilemarkers.betternpc.BetterNpcView betterNpcView;
    @Inject private BetterNpcSource betterNpc;
    @Inject private StealingArtefactsSource stealingArtefacts;
    @Inject private SailingSource sailing;
    @Inject private ExternalMarks externalMarks;
    @Inject private AggroAreaSource aggroArea;
    @Inject private GauntletSource gauntlet;
    @Inject private AgilitySource agility;
    @Inject private IndicatorOverlay overlay;
    @Inject private RenderTrace trace;
    @Inject private net.runelite.client.callback.RenderCallbackManager renderCallbacks;
    @Inject private OverlayManager overlays;
    @Inject private PluginManager plugins;
    @Inject private ConfigManager configManager;
    @Inject private GroundMarkerPlugin groundPlugin;
    @Inject private ObjectIndicatorsPlugin objectPlugin;
    @Inject private NpcIndicatorsPlugin npcPlugin;
    private GroundMarkerOverlay originalGround;
    private Overlay originalObjects;
    /** Plugin Hub plugins' scene overlays, held back while HD Tile Markers draws their marks (matched by class name). */
    private HeldOverlays heldBetterNpc, heldTilePacks, heldStealing, heldSailing, heldAggroArea, heldGauntlet, heldAgility;
    private static final java.util.Set<String> STEALING_OVERLAYS = new java.util.HashSet<>(java.util.Arrays.asList(
        "StealingArtefactsHouseOverlay", "StealingArtefactsPatrolOverlay", "StealingArtefactsKhaledOverlay"));
    private volatile boolean running, dirty, failed;
    private boolean sceneReady, sceneUnavailable;
    private int sceneShapes;
    private Boolean loggedReady;
    /** Diagnostics: how often everything was rebuilt, and why last. */
    private int rebuilds;
    private String lastRebuild = "";
    private List<Marker> markers = Collections.emptyList();
    private List<ModelTarget> modelTargets = Collections.emptyList();
    private Marker hover;

    @Provides HdTileMarkersConfig provideConfig(ConfigManager manager)
    { return manager.getConfig(HdTileMarkersConfig.class); }

    @Override protected void startUp()
    {
        running = true; dirty = true; failed = false; sceneUnavailable = false;
        // The shadow transparency notice shows again after turning the plugin on (or installing it).
        warnedShadowTransparency = false;
        if (heldBetterNpc == null)
        {
            heldBetterNpc = new HeldOverlays(overlays, plugins, "com.betternpchighlight.BetterNpcHighlightPlugin",
                o -> o.getClass().getName().equals("com.betternpchighlight.overlays.BetterNpcHighlightOverlay"));
            // Tile Packs' overlay has the same name as Ground Markers'; its class tells them apart.
            heldTilePacks = new HeldOverlays(overlays, plugins, "com.tilepacks.TilePacksPlugin",
                o -> o.getClass().getName().equals("com.tilepacks.ui.overlay.GroundMarkerOverlay"));
            heldStealing = new HeldOverlays(overlays, plugins, "io.cbitler.stealingartefacts.StealingArtefactsPlugin",
                o -> STEALING_OVERLAYS.contains(o.getClass().getSimpleName()) && o.getClass().getName().startsWith("io.cbitler.stealingartefacts."));
            heldAgility = new HeldOverlays(overlays, plugins, AgilitySource.PLUGIN, o -> o.getClass().getName().equals(AgilitySource.OVERLAY));
            heldGauntlet = new HeldOverlays(overlays, plugins, GauntletSource.PLUGIN, o -> o.getClass().getName().equals(GauntletSource.OVERLAY));
            heldAggroArea = new HeldOverlays(overlays, plugins, AggroAreaSource.PLUGIN, o -> o.getClass().getName().equals(AggroAreaSource.OVERLAY));
            heldSailing = new HeldOverlays(overlays, plugins, "com.duckblade.osrs.sailing.SailingPlugin",
                o -> SailingSource.OVERLAYS.contains(o.getClass().getSimpleName()) && o.getClass().getName().startsWith(SailingSource.PACKAGE));
        }
        overlays.add(overlay);
        tracing(config.debug());
        pathMarker.startUp();
        eventBus.register(pathMarker);
        eventBus.register(betterNpcEvents);
        eventBus.register(stealingArtefacts);
        eventBus.register(sailing);
        eventBus.register(externalMarks);
        eventBus.register(gauntlet);
        eventBus.register(agility);
        clientThread.invokeLater(betterNpcEvents::startUp);
    }

    @Override protected void shutDown()
    {
        running = false;
        overlays.remove(overlay);
        tracing(false);
        eventBus.unregister(pathMarker);
        pathMarker.shutDown();
        eventBus.unregister(betterNpcEvents);
        eventBus.unregister(stealingArtefacts);
        eventBus.unregister(sailing);
        eventBus.unregister(externalMarks);
        eventBus.unregister(gauntlet);
        eventBus.unregister(agility);
        externalMarks.clear();
        betterNpcEvents.shutDown();
        clientThread.invoke(() -> { if (!running) { reset(); sources.clear(); } });
    }

    private void reset()
    {
        renderer.reset();
        markers = Collections.emptyList();
        modelTargets = Collections.emptyList();
        hover = null;
        sceneReady = false;
        sceneShapes = 0;
        restoreGround();
        restoreObjects();
        for (HeldOverlays held : new HeldOverlays[]{heldBetterNpc, heldTilePacks, heldStealing, heldSailing, heldAggroArea, heldGauntlet, heldAgility})
        {
            if (held != null) { held.reset(); }
        }
    }

    /** Collects markers once per client tick; the scene is written in onBeforeRender. */
    @Subscribe public void onPostClientTick(PostClientTick event)
    {
        if (!running || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) { return; }
        try
        {
            if (dirty) { reset(); sources.rebuild(); stealingArtefacts.rebuild(client.getTopLevelWorldView()); sailing.rebuild(client.getTopLevelWorldView()); gauntlet.rebuild(client.getTopLevelWorldView()); dirty = false; rebuilds++; }
            sources.groundEnabled = plugins.isPluginEnabled(groundPlugin);
            sources.objectsEnabled = plugins.isPluginEnabled(objectPlugin);
            sources.npcsEnabled = plugins.isPluginEnabled(npcPlugin);
            // The integrations below add to these lists.
            List<Marker> tiles = new ArrayList<>(sources.collect(pathTiles()));
            List<ModelTarget> models = new ArrayList<>(sources.modelTargets());
            // Replace the original overlays unless the scene route has actually failed. Without GPU
            // HD Tile Markers draws 2D itself; merely not having drawn a frame yet (start-up) is not a failure.
            boolean drawing = !client.isGpu() || sceneActive();
            // Plugin Hub plugins: only while the plugin runs and the scene route works; otherwise its own overlay draws.
            heldBetterNpc.update(sceneActive(), o -> true);
            if (heldBetterNpc.drawing())
            {
                betterNpc.collect(tiles, models);
            }
            // Stealing Artefacts: the same rule, only while its plugin runs and the scene route works.
            heldStealing.update(sceneActive(), o -> true);
            if (heldStealing.drawing())
            {
                stealingArtefacts.collect(models);
            }
            // Sailing: its sea overlays; the ones on the boat itself stay its own.
            heldSailing.update(sceneActive(), o -> sailingStillShown(o.getClass().getSimpleName()));
            if (heldSailing.drawing())
            {
                sailing.collect(tiles);
            }
            // NPC Aggression Timer's area lines; its timer infobox stays its own.
            heldAggroArea.update(sceneActive(), o -> true);
            if (heldAggroArea.drawing())
            {
                aggroArea.collect(tiles);
            }
            // The Gauntlet's maze resources and utilities; its NPC highlights and counters stay its own.
            heldGauntlet.update(sceneActive(), o -> true);
            if (heldGauntlet.drawing())
            {
                gauntlet.collect(tiles, models);
            }
            // Agility: obstacle and shortcut clickboxes, traps, marks of grace; its lap counter stays its own.
            heldAgility.update(sceneActive(), o -> true);
            if (heldAgility.drawing())
            {
                agility.collect(tiles, models);
            }
            // Marks other plugins sent through PluginMessage.
            externalMarks.collect(tiles, models);
            markers = tiles;
            modelTargets = models;
            if (config.ground() && config.replaceGround() && sources.validGround() && drawing) { suppressGround(); }
            else { restoreGround(); }
            if (config.objectMarkers() && config.replaceObjectMarkers() && sources.validObjects() && drawing) { suppressObjects(); }
            else { restoreObjects(); }
            heldTilePacks.update(drawing, o -> true);
            sources.tilePacksEnabled = heldTilePacks.drawing();
        }
        catch (RuntimeException ex)
        {
            fail(ex);
        }
    }

    /** Rebuilds all scene shapes for the camera of the frame about to be drawn. */
    @Subscribe public void onBeforeRender(BeforeRender event)
    {
        if (!running || dirty || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) { return; }
        if (!client.isGpu() || failed)
        {
            renderer.clear();
            sceneReady = false;
            return;
        }
        try
        {
            Player player = client.getLocalPlayer();
            ModelShapes.Camera camera = camera();
            LocalPoint xray = config.tilesThroughWalls() ? xrayAnchor(camera, player) : null;
            // 117 HD's shadow settings, read-only: without its shadows nothing needs stacking.
            boolean hdShadows = hdActive() && !"false".equals(configManager.getConfiguration("hd", "shadowsEnabled"));
            boolean transparentShadows = !"false".equals(configManager.getConfiguration("hd", "enableShadowTransparency"));
            // With its shadow transparency on every visible face casts (threshold 0.01): nothing to gain there.
            boolean capped = hdShadows && !transparentShadows;
            renderer.floatingAlphaCap = capped ? SceneShapeRenderer.HD_CAP_OPAQUE_SHADOWS : 255;
            if (hdShadows && transparentShadows) { warnShadowTransparency(); }
            renderer.begin(camera, 1f / stretchScale(), xray, player.getWorldView().getPlane());
            int drawn = 0;
            hover = sources.hover();
            if (hover != null && !config.hoveredTileIn2d() && renderer.tile(hover)) { drawn++; }
            // Beyond the limit, tiles keep their 2D fallback rather than being dropped.
            if (markers.size() <= MAX_TILES)
            {
                for (Marker m : markers) { if (renderer.tile(m)) { drawn++; } }
            }
            for (int i = 0; i < Math.min(MAX_MODELS, modelTargets.size()); i++)
            {
                if (renderer.model(modelTargets.get(i))) { drawn++; }
            }
            boolean ok = renderer.end();
            if (loggedReady == null || ok != loggedReady)
            {
                log.info("HD Tile Markers scene rendering {} ({} shapes)", ok ? "active" : "unavailable", drawn);
                loggedReady = ok;
            }
            sceneReady = ok;
            sceneUnavailable = !ok;
            sceneShapes = drawn;
        }
        catch (RuntimeException ex)
        {
            fail(ex);
        }
    }

    /**
     * Where through-walls marks hang. The client draws see-through models zone by zone (8x8 tiles), per level,
     * from the zone whose centre is farthest from the camera to the nearest; 117 HD then sorts within a zone by
     * distance to the camera. Marks on the player's tile were drawn before see-through objects nearer the
     * camera, such as tree leaves, which then covered their fill. So they hang in the zone nearest the camera
     * that is surely drawn, at its point nearest the camera. The player's tile if no ground is in view.
     */
    private LocalPoint xrayAnchor(ModelShapes.Camera camera, Player player)
    {
        WorldView wv = client.getTopLevelWorldView();
        int plane = player.getWorldView().getPlane();
        float bottom = client.getViewportYOffset() + client.getViewportHeight() - 8;
        List<float[]> ground = new ArrayList<>(XRAY_SAMPLES);
        for (int i = 0; i < XRAY_SAMPLES; i++)
        {
            float x = client.getViewportXOffset() + 8 + (client.getViewportWidth() - 16) * i / (float) (XRAY_SAMPLES - 1);
            float[] p = WalkPredictor.ground(camera, x, bottom, wv, plane);
            if (p != null) { ground.add(p); }
        }
        int[] anchor = nearestDrawnZonePoint(camera.x, camera.y, ground, wv.getSizeX(), wv.getSizeY());
        return anchor == null ? player.getLocalLocation() : new LocalPoint(anchor[0], anchor[1], wv);
    }

    private static final int XRAY_SAMPLES = 5;
    /**
     * A zone counts as drawn when visible ground lies this close to it: 117 HD and the GPU plugin test zone
     * boxes widened by 512 on every side against the view (DrawCallbacks.zoneInFrustum).
     */
    private static final int ZONE_VIEW_MARGIN = 384;

    /**
     * Of the zones near the given visible ground points, the point (local x, y) nearest the camera in the zone
     * whose centre is nearest the camera, the one the client draws last; null without ground points.
     */
    static int[] nearestDrawnZonePoint(float camX, float camY, List<float[]> ground, int sceneSizeX, int sceneSizeY)
    {
        int bestX = -1, bestY = -1;
        double best = Double.MAX_VALUE;
        int zonesX = Math.max(1, sceneSizeX / 8), zonesY = Math.max(1, sceneSizeY / 8);
        for (float[] g : ground)
        {
            int x0 = Math.max(0, (int) Math.floor((g[0] - ZONE_VIEW_MARGIN) / 1024)), x1 = Math.min(zonesX - 1, (int) Math.floor((g[0] + ZONE_VIEW_MARGIN) / 1024));
            int y0 = Math.max(0, (int) Math.floor((g[1] - ZONE_VIEW_MARGIN) / 1024)), y1 = Math.min(zonesY - 1, (int) Math.floor((g[1] + ZONE_VIEW_MARGIN) / 1024));
            for (int zx = x0; zx <= x1; zx++)
            {
                for (int zy = y0; zy <= y1; zy++)
                {
                    double dx = zx * 1024 + 512 - camX, dy = zy * 1024 + 512 - camY, d = dx * dx + dy * dy;
                    if (d < best) { best = d; bestX = zx; bestY = zy; }
                }
            }
        }
        if (bestX < 0) { return null; }
        // Inside the zone, a tile's width from its edges, as near the camera as that allows.
        int x = Math.max(bestX * 1024 + 64, Math.min(bestX * 1024 + 959, Math.round(camX)));
        int y = Math.max(bestY * 1024 + 64, Math.min(bestY * 1024 + 959, Math.round(camY)));
        return new int[]{x, y};
    }

    private ModelShapes.Camera camera()
    {
        return new ModelShapes.Camera(client.getCameraFpX(), client.getCameraFpY(), client.getCameraFpZ(),
            client.getCameraFpPitch(), client.getCameraFpYaw(), client.getScale(), client.getViewportXOffset(),
            client.getViewportYOffset(), client.getViewportWidth(), client.getViewportHeight());
    }

    /**
     * Path Marker's tiles plus, while a walk prediction is active, a predicted path
     * to it: when Path Marker has none, or the target lies beyond the loaded area.
     */
    List<PathMarker.SceneTile> pathTiles()
    {
        List<PathMarker.SceneTile> tiles = pathMarker.sceneTiles();
        Player player = client.getLocalPlayer();
        WorldPoint target = sources.predictedTarget(player, System.currentTimeMillis());
        HdTileMarkersConfig.DrawLocations where = config.activePathDrawLocations();
        boolean gameWorld = where == HdTileMarkersConfig.DrawLocations.BOTH || where == HdTileMarkersConfig.DrawLocations.GAME_WORLD;
        HdTileMarkersConfig.PathDisplaySetting display = config.activePathDisplaySetting();
        if (target == null || !gameWorld || display == HdTileMarkersConfig.PathDisplaySetting.NEVER
            || (display != HdTileMarkersConfig.PathDisplaySetting.ALWAYS && !pathMarker.isKeyDisplayActivePath())
            || (pathMarker.isPathActive() && !MarkerSources.outside(player.getWorldView(), target))) { return tiles; }
        List<PathMarker.SceneTile> result = new ArrayList<>(tiles);
        if (config.activePathDrawMode() == HdTileMarkersConfig.DrawMode.TARGET_TILE)
        {
            result.add(new PathMarker.SceneTile(target, config.activePathStroke1(), config.activePathFill1(),
                config.activePathMarkerStyle() == HdTileMarkersConfig.MarkerStyle.DOT, true));
            return result;
        }
        result.addAll(WalkPredictor.path(player, pathMarker.pathfinder, target, config.activePathStroke1(), config.activePathFill1(),
            config.activePathStroke2(), config.activePathFill2(),
            config.activePathMarkerStyle() == HdTileMarkersConfig.MarkerStyle.DOT, pathMarker.runningForPath()));
        return result;
    }

    private void fail(RuntimeException ex)
    {
        if (!failed) { log.warn("Scene markers unavailable; using 2D markers and the original ground overlay", ex); }
        failed = true;
        reset();
    }

    /** Canvas pixels per screen pixel is 1 / this; the GPU scene is rasterized at the stretched size. */
    private float stretchScale()
    {
        if (!client.isStretchedEnabled()) { return 1f; }
        java.awt.Dimension real = client.getRealDimensions(), stretched = client.getStretchedDimensions();
        if (real == null || stretched == null || real.width <= 0 || stretched.width <= 0) { return 1f; }
        return stretched.width / (float) real.width;
    }

    private void suppressGround()
    {
        if (originalGround != null) { return; }
        overlays.anyMatch(candidate -> {
            if (candidate instanceof GroundMarkerOverlay)
            { originalGround = (GroundMarkerOverlay) candidate; return true; }
            return false;
        });
        if (originalGround != null) { overlays.remove(originalGround); }
    }

    private void suppressObjects()
    {
        if (originalObjects != null) { return; }
        // Object Markers' overlay class is not public; RuneLite names overlays after their class.
        overlays.anyMatch(candidate -> {
            if ("ObjectIndicatorsOverlay".equals(candidate.getName())) { originalObjects = candidate; return true; }
            return false;
        });
        if (originalObjects != null) { overlays.remove(originalObjects); }
    }

    /** Whether Sailing itself would still show this overlay: its feature toggles, as their isEnabled. */
    private boolean sailingStillShown(String overlay)
    {
        java.util.function.Predicate<String> on = key -> !"false".equals(configManager.getConfiguration(SailingSource.GROUP, key));
        switch (overlay)
        {
            case "RapidsOverlay": return on.test("highlightRapids");
            case "LightningCloudsOverlay": return on.test("highlightLightningCloudStrikes");
            case "LostCargoHighlighter": return on.test("barracudaHighlightLostCrates");
            case "SalvagingHighlight": return on.test("salvagingHighlightActiveWrecks") || on.test("salvagingHighlightInactiveWrecks")
                || "true".equals(configManager.getConfiguration(SailingSource.GROUP, "salvagingHideHighLevelWrecks"));
            case "TrueTileIndicator":
            {
                String mode = configManager.getConfiguration(SailingSource.GROUP, "navigationTrueTileIndicator");
                return mode != null && !"OFF".equals(mode);
            }
            default: return true;
        }
    }

    private void restoreObjects()
    {
        if (originalObjects == null) { return; }
        if (plugins.isPluginEnabled(objectPlugin)) { overlays.add(originalObjects); }
        originalObjects = null;
    }

    private void restoreGround()
    {
        if (originalGround == null) { return; }
        if (plugins.isPluginEnabled(groundPlugin)) { overlays.add(originalGround); }
        originalGround = null;
    }

    /** Walk here: remember the clicked tile for the predicted destination. */
    @Subscribe public void onMenuOptionClicked(MenuOptionClicked e)
    {
        if (e.getMenuAction() != MenuAction.WALK || client.getLocalPlayer() == null) { return; }
        WorldView wv = client.getLocalPlayer().getWorldView();
        Tile tile = wv.getSelectedSceneTile();
        WorldPoint target = tile != null ? tile.getWorldLocation() : null;
        if (target == null && config.predictWalk() && client.getMouseCanvasPosition() != null)
        {
            // No tile under the mouse, such as 117 HD's extended terrain: find where the click meets the ground.
            target = WalkPredictor.raycast(camera(), client.getMouseCanvasPosition().getX(), client.getMouseCanvasPosition().getY(),
                wv, wv.getPlane());
        }
        if (target != null) { sources.walkedTo(target); }
    }

    @Subscribe public void onGameStateChanged(GameStateChanged e)
    { if (e.getGameState() != GameState.LOGGED_IN) { reset(); sources.clear(); dirty = true; failed = false; } }
    @Subscribe public void onWorldViewLoaded(WorldViewLoaded e) { dirty = true; lastRebuild = "worldview loaded"; }
    @Subscribe public void onWorldViewUnloaded(WorldViewUnloaded e) { sources.removeWorldView(e.getWorldView()); reset(); dirty = true; lastRebuild = "worldview unloaded"; }
    @Subscribe public void onProfileChanged(ProfileChanged e)
    { clientThread.invoke(() -> { if (running) { reset(); dirty = true; failed = false; } }); }
    /** Whether 117 HD runs; checked on plugin changes, not every frame. */
    private Boolean hd;

    private boolean hdActive()
    {
        if (hd == null)
        {
            hd = false;
            for (Plugin p : plugins.getPlugins())
            {
                if (p.getClass().getName().equals("rs117.hd.HdPlugin")) { hd = plugins.isPluginActive(p); break; }
            }
        }
        return hd;
    }

    @Subscribe public void onPluginChanged(PluginChanged e) { hd = null;  dirty = true; failed = false; lastRebuild = "plugin " + e.getPlugin().getName(); }
    /** The render trace only runs while debug info is shown: it is called for every object the client draws. */
    private boolean traced;

    @Inject private net.runelite.client.chat.ChatMessageManager chatMessages;
    /**
     * The shadow transparency notice is shown once after the plugin starts, and again when 117 HD's
     * Shadow transparency is turned back on.
     */
    private boolean warnedShadowTransparency;

    /**
     * 117 HD with its "Shadow transparency" on lets every visible face cast a shadow, so marks drawn
     * through walls cast shadows that move with the camera. HD Tile Markers never changes another
     * plugin's settings: it only tells the player, once, in the chat box (local only, nothing is sent).
     */
    private void warnShadowTransparency()
    {
        if (warnedShadowTransparency) { return; }
        warnedShadowTransparency = true;
        chatMessages.queue(net.runelite.client.chat.QueuedMessage.builder()
            .type(ChatMessageType.CONSOLE)
            .runeLiteFormattedMessage(new net.runelite.client.chat.ChatMessageBuilder()
                .append(net.runelite.client.chat.ChatColorType.HIGHLIGHT)
                .append("HD Tile Markers: ")
                .append(net.runelite.client.chat.ChatColorType.NORMAL)
                .append("turn off \"Shadow transparency\" in 117 HD's settings, or marks drawn through walls cast shadows that move with the camera.")
                .build())
            .build());
    }

    private void tracing(boolean on)
    {
        if (on == traced) { return; }
        if (on) { renderCallbacks.register(trace); } else { renderCallbacks.unregister(trace); }
        traced = on;
    }

    @Subscribe public void onConfigChanged(ConfigChanged e)
    {
        if (e.getGroup().equals(HdTileMarkersConfig.GROUP) && "debug".equals(e.getKey())) { tracing(running && config.debug()); return; }
        if ("hd".equals(e.getGroup()) && "enableShadowTransparency".equals(e.getKey())) { warnedShadowTransparency = false; return; }
        // Marks and settings of the plugins HD Tile Markers reads: rebuild when they change.
        String group = e.getGroup();
        if (group.equals(HdTileMarkersConfig.GROUP) || group.equals("groundMarker")
            || group.equals(TilePackSource.DATA_GROUP) || group.equals(TilePackSource.SETTINGS_GROUP)
            || group.equals(ObjectMarkerSource.GROUP) || group.equals(net.runelite.client.plugins.npchighlight.NpcIndicatorsConfig.GROUP))
        { dirty = true; failed = false; lastRebuild = group + "." + e.getKey(); }
    }
    @Subscribe public void onHitsplatApplied(HitsplatApplied e) { sources.hitsplat(e.getActor(), System.currentTimeMillis()); }
    @Subscribe public void onNpcSpawned(NpcSpawned e) { sources.add(e.getNpc()); }
    @Subscribe public void onNpcChanged(NpcChanged e) { sources.add(e.getNpc()); }
    @Subscribe public void onNpcDespawned(NpcDespawned e) { sources.remove(e.getNpc()); }
    @Subscribe public void onGameObjectSpawned(GameObjectSpawned e) { sources.add(e.getGameObject()); }
    @Subscribe public void onGameObjectDespawned(GameObjectDespawned e) { sources.remove(e.getGameObject()); }
    @Subscribe public void onWallObjectSpawned(WallObjectSpawned e) { sources.add(e.getWallObject()); }
    @Subscribe public void onWallObjectDespawned(WallObjectDespawned e) { sources.remove(e.getWallObject()); }
    @Subscribe public void onDecorativeObjectSpawned(DecorativeObjectSpawned e) { sources.add(e.getDecorativeObject()); }
    @Subscribe public void onDecorativeObjectDespawned(DecorativeObjectDespawned e) { sources.remove(e.getDecorativeObject()); }
    @Subscribe public void onGroundObjectSpawned(GroundObjectSpawned e) { sources.add(e.getGroundObject()); }
    @Subscribe public void onGroundObjectDespawned(GroundObjectDespawned e) { sources.remove(e.getGroundObject()); }

    List<Marker> markers() { return markers; }
    List<ModelTarget> modelTargets() { return modelTargets; }
    boolean hoverIn2d() { return config.hoveredTileIn2d(); }

    /** The Gauntlet's resource icons, drawn in 2D while its maze overlay is held back. */
    java.util.Map<LocalPoint, java.awt.image.BufferedImage> gauntletIcons()
    { return heldGauntlet == null || !heldGauntlet.drawing() ? java.util.Collections.emptyMap() : gauntlet.icons(); }

    /** Guards whose facing arrow HD Tile Markers draws while Stealing Artefacts' patrol overlay is held back. */
    List<NPC> stealingArrows() { return heldStealing == null || !heldStealing.drawing() ? java.util.Collections.emptyList() : stealingArtefacts.facingArrows(); }

    /** The hovered tile for the overlay: read at overlay time when drawn in 2D, for zero delay. */
    Marker hover() { return config.hoveredTileIn2d() || !client.isGpu() || failed ? sources.hover() : hover; }
    boolean replacedGround() { return originalGround != null; }
    /** Whether HD Tile Markers draws Better NPC Highlight (its overlay is held back). */
    boolean drawsBetterNpc() { return heldBetterNpc != null && heldBetterNpc.drawing(); }
    com.hdtilemarkers.betternpc.BetterNpcView betterNpcView() { return betterNpcView; }
    List<ObjectMarkerSource.Resolved> objectOutlines() { return sources.objectOutlines(); }
    List<NPC> npcOutlines() { return sources.npcOutlines(); }
    java.awt.Color npcOutlineColor(NPC npc) { return sources.outlineColor(npc); }
    net.runelite.client.plugins.npchighlight.NpcIndicatorsConfig npcConfig() { return sources.npcConfig(); }

    /** The scene route works: GPU on, no rendering error, carrier models available. */
    boolean sceneActive() { return client.isGpu() && !failed && !sceneUnavailable; }

    /** Tile markers go to 2D only without the scene route or above the tile limit. */
    boolean tilesIn2d() { return !sceneActive() || markers.size() > MAX_TILES; }

    /** Includes intentionally culled offscreen models; omitted/unsupported marks stay in 2D. */
    boolean markerInScene(String key) { return sceneActive() && renderer.drawn(key); }

    java.util.Set<TileObject> objectOutlinesInScene()
    {
        java.util.Set<TileObject> handled = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Set<TileObject> missing = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ModelTarget t : modelTargets)
        {
            if (t.object != null && t.outline)
            {
                if (markerInScene(t.key)) { handled.add(t.object); }
                else { missing.add(t.object); }
            }
        }
        handled.removeAll(missing);
        return handled;
    }

    /** Markers per source, as collected, with how many are in the scene: tells an empty source from a drawing problem. */
    private String sourceCounts()
    {
        String[][] kinds = {{"ground", "ground:"}, {"packs", "tilepack:"}, {"path", "path:"}, {"npc", "npc:"}, {"bnh", "bnh:"}, {"obj", "object:"}};
        StringBuilder out = new StringBuilder();
        for (String[] kind : kinds)
        {
            int total = 0, drawn = 0;
            for (Marker m : markers) { if (m.key.startsWith(kind[1])) { total++; if (renderer.drawn(m.key)) { drawn++; } } }
            for (ModelTarget t : modelTargets) { if (t.key.startsWith(kind[1])) { total++; if (renderer.drawn(t.key)) { drawn++; } } }
            if (total > 0) { out.append(out.length() == 0 ? "" : ", ").append(kind[0]).append(' ').append(drawn).append('/').append(total); }
        }
        return out.length() == 0 ? "no markers" : out.toString();
    }

    /** Source plugins that are off, so their marks are not drawn. */
    private String sourcesOff()
    {
        StringBuilder off = new StringBuilder();
        if (!sources.groundEnabled) { off.append(", Ground Markers"); }
        if (!sources.objectsEnabled) { off.append(", Object Markers"); }
        if (!sources.npcsEnabled) { off.append(", NPC Indicators"); }
        return off.length() == 0 ? "" : " | off:" + off.substring(1);
    }

    String rendererStatus()
    {
        if (failed) { return "HD Tile Markers: 2D fallback - rendering error"; }
        if (!client.isGpu()) { return "HD Tile Markers: 2D fallback - GPU/117 HD inactive"; }
        if (!sceneReady) { return "HD Tile Markers: 2D fallback - scene model unavailable"; }
        String limit = markers.size() > MAX_TILES ? " (tiles in 2D: over " + MAX_TILES + ")" : "";
        return "HD Tile Markers: " + trace.summary() + " | " + sceneShapes + " shapes" + limit + " | " + sourceCounts() + sourcesOff()
            + " | rebuilds " + rebuilds + (lastRebuild.isEmpty() ? "" : " (" + lastRebuild + ")") + ", new models " + renderer.carriersCreated();
    }
}
