/*
 * Target and highlight rules adapted from Stealing Artefacts (https://github.com/pajlads/StealingArtefacts,
 * commit 631227f32d7518da6615165c4658bf6e3704dedb): StealingArtefactsState, shouldMarkObject, isGuardLured,
 * isInPisc and the colours of its house, patrol and Khaled overlays.
 * Copyright 2020 Christopher Bitler. MIT License; see META-INF/LICENSE-stealing-artefacts and THIRD_PARTY_NOTICES.md.
 * Changes for HD Tile Markers: read-only (its plugin keeps hint arrows, panel and saved state);
 * the marks are returned to HD Tile Markers' renderer instead of drawn.
 */
package com.hdtilemarkers;

import java.awt.Color;
import java.awt.Shape;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/** The Port Piscarilius minigame marks of the Stealing Artefacts plugin, drawn by HD Tile Markers. */
@Singleton
final class StealingArtefactsSource
{
    static final String GROUP = "stealingartefacts";
    private static final int VARBIT = 4903, PATROL_ID_MIN = 6973, PATROL_ID_MAX = 6980, LADDER = 27634;
    private static final int SOUTH = 0, WEST = 512;
    private static final Set<Integer> KHALED = new HashSet<>(Arrays.asList(6971, 6972));
    private static final WorldPoint EAST_GUARD = new WorldPoint(1777, 3746, 0), SOUTHEAST_GUARD = new WorldPoint(1780, 3731, 0);
    // Stealing Artefacts' overlay colours.
    static final Color BORDER = Color.YELLOW, HOUSE_FILL = new Color(0, 255, 0, 50), PATROL_FILL = new Color(255, 0, 0, 50),
        LURED_FILL = new Color(0, 255, 0, 50), KHALED_FILL = new Color(255, 0, 0, 50), KHALED_HOVER = Color.ORANGE;

    /** The target house per varbit value, as StealingArtefactsState: drawer id and ladder location, or none. */
    private static final int[] DRAWERS = {-1, 27771, 27772, 27773, 27774, 27775, 27776, -1, -1};
    private static final WorldPoint[] LADDERS = {null, new WorldPoint(0, 0, 0), new WorldPoint(1776, 3730, 0),
        new WorldPoint(1768, 3733, 0), new WorldPoint(1749, 3730, 0), new WorldPoint(1751, 3751, 0),
        new WorldPoint(1750, 3756, 0), null, null};
    private static final int NO_TASK = 0, FAILURE = 7;

    private final Client client;
    private final ConfigManager configs;
    private final Set<GameObject> objects = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<NPC> patrols = Collections.newSetFromMap(new IdentityHashMap<>());
    private NPC khaled;

    @Inject
    StealingArtefactsSource(Client client, ConfigManager configs) { this.client = client; this.configs = configs; }

    private boolean setting(String key)
    {
        // Stealing Artefacts' options all default to true.
        return !"false".equals(configs.getConfiguration(GROUP, key));
    }

    private int state()
    {
        int value = client.getVarbitValue(VARBIT);
        return value >= 0 && value < DRAWERS.length ? value : NO_TASK;
    }

    static boolean inPisc(WorldPoint p)
    {
        return p.getX() >= 1739 && p.getX() <= 1860 && p.getY() >= 3675 && p.getY() <= 3803;
    }

    /** shouldMarkObject: the target house's drawers, and its ladder when highlighted. */
    private boolean marked(GameObject object, int state)
    {
        boolean mark = false;
        if (DRAWERS[state] != -1) { mark = object.getId() == DRAWERS[state]; }
        if (LADDERS[state] != null && object.getWorldLocation().distanceTo(LADDERS[state]) == 0) { mark = object.getId() == LADDER; }
        return mark && (object.getId() != LADDER || setting("highlightLadders"));
    }

    /** isGuardLured: a guard standing on its lure tile and facing the lure direction. */
    static boolean lured(NPC guard)
    {
        WorldPoint at = guard.getWorldLocation();
        return at.distanceTo(EAST_GUARD) == 0 && guard.getCurrentOrientation() == SOUTH
            || at.distanceTo(SOUTHEAST_GUARD) == 0 && guard.getCurrentOrientation() == WEST;
    }

    /** Clickboxes and hulls to draw, as the house, patrol and Khaled overlays decide. */
    void collect(List<ModelTarget> out)
    {
        Player player = client.getLocalPlayer();
        if (player == null || !inPisc(player.getWorldLocation())) { return; }
        int state = state(), plane = player.getWorldView().getPlane();
        for (GameObject object : objects)
        {
            if (object.getPlane() != plane || !marked(object, state)) { continue; }
            out.add(ModelTarget.object("sa:object:" + object.getHash(), object, object.getRenderable(), 0, 0, BORDER, HOUSE_FILL,
                2, true, object::getClickbox));
        }
        if (setting("highlightPatrols"))
        {
            for (NPC guard : patrols)
            {
                if (guard.getWorldView() == null || guard.getWorldView().getPlane() != plane) { continue; }
                boolean isLured = guard.getId() == PATROL_ID_MAX && lured(guard) && setting("highlightGuardLures");
                out.add(ModelTarget.npc("sa:patrol:" + guard.getIndex(), guard, BORDER, isLured ? LURED_FILL : PATROL_FILL, 2));
            }
        }
        if (khaled != null && (state == NO_TASK || state == FAILURE) && setting("highlightKhaledTaskless"))
        {
            Shape hull = khaled.getConvexHull();
            net.runelite.api.Point mouse = client.getMouseCanvasPosition();
            // Its Khaled overlay borders orange while hovered.
            Color border = hull != null && mouse != null && hull.contains(mouse.getX(), mouse.getY()) ? KHALED_HOVER : BORDER;
            out.add(ModelTarget.npc("sa:khaled", khaled, border, KHALED_FILL, 2));
        }
    }

    /** Guards whose facing arrow the patrol overlay shows. */
    List<NPC> facingArrows()
    {
        Player player = client.getLocalPlayer();
        if (player == null || !inPisc(player.getWorldLocation()) || !setting("showPatrolFacingDirection")) { return Collections.emptyList(); }
        List<NPC> result = new ArrayList<>();
        for (NPC guard : patrols) { if (guard.getWorldView() != null && guard.getWorldView().getPlane() == player.getWorldView().getPlane()) { result.add(guard); } }
        return result;
    }

    void add(GameObject object) { if (object != null && (object.getId() == LADDER || object.getId() >= 27771 && object.getId() <= 27776)) { objects.add(object); } }

    void add(NPC npc)
    {
        if (KHALED.contains(npc.getId())) { khaled = npc; }
        else if (npc.getId() >= PATROL_ID_MIN && npc.getId() <= PATROL_ID_MAX) { patrols.add(npc); }
    }

    void clear() { objects.clear(); patrols.clear(); khaled = null; }

    /** Picks up what was already in the scene before HD Tile Markers started or the scene reloaded. */
    void rebuild(WorldView wv)
    {
        clear();
        if (wv == null) { return; }
        for (NPC npc : wv.npcs()) { add(npc); }
        for (Tile[][] plane : wv.getScene().getTiles())
        {
            for (Tile[] row : plane)
            {
                for (Tile tile : row)
                {
                    if (tile == null) { continue; }
                    for (GameObject object : tile.getGameObjects()) { add(object); }
                }
            }
        }
    }

    @Subscribe public void onGameObjectSpawned(GameObjectSpawned e) { add(e.getGameObject()); }
    @Subscribe public void onGameObjectDespawned(GameObjectDespawned e) { objects.remove(e.getGameObject()); }
    @Subscribe public void onNpcSpawned(NpcSpawned e) { add(e.getNpc()); }
    @Subscribe public void onNpcDespawned(NpcDespawned e) { patrols.remove(e.getNpc()); if (e.getNpc() == khaled) { khaled = null; } }
    @Subscribe public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() == GameState.LOGGING_IN || e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING) { clear(); }
    }
}
