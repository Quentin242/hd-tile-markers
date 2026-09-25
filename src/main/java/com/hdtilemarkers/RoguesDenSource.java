/*
 * Display rules adapted from Rogues' Den (nightfirecat/plugin-hub-plugins, commit 3ece9e0401f5ebac9da2762015449d5e68bb0bfc:
 * RoguesDenOverlay.render, the obstacle objects of Obstacles and the tracking of RoguesDenPlugin), copyright (c) 2021,
 * Jordan, BSD 2-Clause License; see THIRD_PARTY_NOTICES.md. Changes for HD Tile Markers: its obstacles are tracked from
 * spawn events (HD Tile Markers does not reference its classes) and returned as scene clickboxes.
 */
package com.hdtilemarkers;

import com.google.common.collect.ImmutableMap;
import java.awt.Color;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.eventbus.Subscribe;

/** Rogues' Den's obstacle clickboxes, drawn by HD Tile Markers; its hint tiles are captured from its overlay (TileCapture). */
@Singleton
final class RoguesDenSource
{
    static final String PLUGIN = "at.nightfirec.roguesden.RoguesDenPlugin";
    static final String OVERLAY = "at.nightfirec.roguesden.RoguesDenOverlay";
    private static final Color BORDER = Color.RED, FILL = new Color(255, 0, 0, 50);
    /** Its obstacle objects (CONTORTION_BARS, GRILL_7255, LEDGE_7240, PASSAGEWAY, WALL_7249, DOOR_7234, WALL_SAFE_7237, DOOR_7246). */
    private static final int CONTORTION_BARS = ObjectID.ROGUESDEN_OBSTACLE_CONTORTION_BARS, GRILL = ObjectID.ROGUESDEN_OBSTACLE_BLOCKING_DOOR_ENTER,
        LEDGE = ObjectID.ROGUESDEN_OBSTACLE_WALL_HANGING_LEDGE, PASSAGEWAY = ObjectID.ROGUESDEN_PASSAGE_ACROSS,
        WALL = ObjectID.ROGUESDEN_TRAP_WALL_CRUSHER, DOOR = ObjectID.ROGUESDEN_PUZZLE_DOOR_MOSAIC,
        WALL_SAFE = ObjectID.ROGUESDEN_WALLDECOR_MAZESAFE, THIEVING_DOOR = ObjectID.ROGUESDEN_OBSTACLE_DOOR;
    /** Obstacles: the object marked on each tile (level 1). */
    static final Map<WorldPoint, Integer> OBJECTS;

    static
    {
        int[][] marks = {
            {3049, 4997, CONTORTION_BARS}, {3024, 5001, GRILL}, {2993, 5004, LEDGE}, {2993, 5005, LEDGE},
            {2957, 5069, PASSAGEWAY}, {2955, 5095, PASSAGEWAY}, {2972, 5097, PASSAGEWAY}, {2972, 5094, GRILL},
            {2983, 5087, LEDGE}, {2983, 5090, LEDGE}, {2993, 5087, WALL}, {2993, 5089, WALL}, {3023, 5082, DOOR},
            // Maze
            {3030, 5079, GRILL}, {3032, 5078, GRILL}, {3036, 5076, GRILL}, {3039, 5079, GRILL}, {3042, 5076, GRILL},
            {3044, 5069, GRILL}, {3041, 5068, GRILL}, {3040, 5070, GRILL}, {3038, 5069, GRILL},
            {3015, 5033, GRILL}, {3010, 5033, GRILL}, {3018, 5047, WALL_SAFE},
            // 80+ Thieving shortcut
            {2967, 5061, THIEVING_DOOR}, {2967, 5066, THIEVING_DOOR}, {2974, 5060, CONTORTION_BARS}, {2989, 5057, GRILL}, {2989, 5058, GRILL},
        };
        ImmutableMap.Builder<WorldPoint, Integer> builder = ImmutableMap.builder();
        for (int[] m : marks) { builder.put(new WorldPoint(m[0], m[1], 1), m[2]); }
        OBJECTS = builder.build();
    }

    private final Client client;
    private final Map<TileObject, Tile> obstacles = new IdentityHashMap<>();
    private boolean hasGem;

    @Inject
    RoguesDenSource(Client client) { this.client = client; }

    /** Finds the obstacles in the loaded scene and whether the jewel is carried (after a rebuild, or on starting inside). */
    void rebuild(WorldView wv)
    {
        obstacles.clear();
        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        hasGem = inventory != null && inventory.contains(ItemID.ROGUESDEN_GEM);
        if (wv == null || wv.getScene() == null) { return; }
        for (Tile[][] plane : wv.getScene().getTiles())
        {
            for (Tile[] row : plane)
            {
                for (Tile tile : row)
                {
                    if (tile == null) { continue; }
                    add(tile, tile.getWallObject()); add(tile, tile.getDecorativeObject()); add(tile, tile.getGroundObject());
                    for (GameObject object : tile.getGameObjects()) { add(tile, object); }
                }
            }
        }
    }

    void collect(List<ModelTarget> models)
    {
        if (!hasGem) { return; }
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) { return; }
        net.runelite.api.Point mouse = client.getMouseCanvasPosition();
        for (Map.Entry<TileObject, Tile> entry : obstacles.entrySet())
        {
            if (entry.getValue().getPlane() != wv.getPlane()) { continue; }
            TileObject object = entry.getKey();
            HoverClickboxes.clickbox(models, "roguesden:" + object.getHash(), object, BORDER, FILL, mouse);
        }
    }

    private static final Set<Integer> OBJECT_IDS = new HashSet<>(OBJECTS.values());

    private void add(Tile tile, TileObject object)
    {
        // The id first: every object of every scene load passes here, and the tile's world location costs.
        if (object == null || tile == null || !OBJECT_IDS.contains(object.getId())) { return; }
        Integer id = OBJECTS.get(tile.getWorldLocation());
        if (id != null && id == object.getId()) { obstacles.put(object, tile); }
    }

    private void remove(TileObject object) { obstacles.remove(object); }

    @Subscribe public void onItemContainerChanged(ItemContainerChanged e)
    {
        if (e.getContainerId() == InventoryID.INV) { hasGem = e.getItemContainer().contains(ItemID.ROGUESDEN_GEM); }
    }

    @Subscribe public void onGameStateChanged(GameStateChanged e) { if (e.getGameState() == GameState.LOADING) { obstacles.clear(); } }
    @Subscribe public void onGameObjectSpawned(GameObjectSpawned e) { add(e.getTile(), e.getGameObject()); }
    @Subscribe public void onGameObjectDespawned(GameObjectDespawned e) { remove(e.getGameObject()); }
    @Subscribe public void onGroundObjectSpawned(GroundObjectSpawned e) { add(e.getTile(), e.getGroundObject()); }
    @Subscribe public void onGroundObjectDespawned(GroundObjectDespawned e) { remove(e.getGroundObject()); }
    @Subscribe public void onWallObjectSpawned(WallObjectSpawned e) { add(e.getTile(), e.getWallObject()); }
    @Subscribe public void onWallObjectDespawned(WallObjectDespawned e) { remove(e.getWallObject()); }
    @Subscribe public void onDecorativeObjectSpawned(DecorativeObjectSpawned e) { add(e.getTile(), e.getDecorativeObject()); }
    @Subscribe public void onDecorativeObjectDespawned(DecorativeObjectDespawned e) { remove(e.getDecorativeObject()); }
}
