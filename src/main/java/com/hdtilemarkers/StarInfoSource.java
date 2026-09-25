/*
 * Display rules adapted from Star Info (pwatts6060/runelite-plugins, commit 38fc77770ad0a5c07e74e7491975a90e40e62173:
 * StarInfoOverlay.render and getStarColor, the tier IDs of Star), copyright (c) 2022, Cute Rock, BSD 2-Clause License;
 * see THIRD_PARTY_NOTICES.md. Changes for HD Tile Markers: the star is tracked from spawn events (HD Tile Markers
 * does not reference its classes), its option read by key, and its hull returned as a scene shape.
 */
package com.hdtilemarkers;

import java.awt.Color;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/** Star Info's crashed star hull, red or green by Mining level, drawn by HD Tile Markers; its text and health bar stay 2D. */
@Singleton
final class StarInfoSource
{
    static final String PLUGIN = "com.starinfo.StarInfoPlugin";
    static final String OVERLAY = "com.starinfo.StarInfoOverlay";
    static final String GROUP = "starinfoplugin";
    /** Crashed star objects, tier 1 to 9 (Star.TIER_IDS). */
    private static final int[] TIER_IDS = {41229, 41228, 41227, 41226, 41225, 41224, 41223, 41021, 41020};

    private final Client client;
    private final ConfigManager configs;
    /** Stars in the scene, the newest first: its overlay draws the first of its list. */
    private final java.util.Deque<GameObject> stars = new ArrayDeque<>();

    @Inject
    StarInfoSource(Client client, ConfigManager configs) { this.client = client; this.configs = configs; }

    static int tier(int id)
    {
        for (int i = 0; i < TIER_IDS.length; i++) { if (TIER_IDS[i] == id) { return i + 1; } }
        return -1;
    }

    /** Finds a star in the loaded scene (after a rebuild, or when the plugin starts next to one). */
    void rebuild(WorldView wv)
    {
        stars.clear();
        if (wv == null || wv.getScene() == null) { return; }
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

    void collect(List<ModelTarget> models)
    {
        GameObject star = stars.peekFirst();
        // Its "Color star" option, on by default.
        if (star == null || "false".equals(configs.getConfiguration(GROUP, "colorStar"))) { return; }
        Color color = client.getBoostedSkillLevel(Skill.MINING) < tier(star.getId()) * 10 ? Color.RED : Color.GREEN;
        HoverClickboxes.hull(models, "starinfo:" + star.getHash(), star, color);
    }

    private void add(GameObject object)
    {
        if (object != null && tier(object.getId()) > 0) { stars.remove(object); stars.addFirst(object); }
    }

    @Subscribe public void onGameObjectSpawned(GameObjectSpawned e) { add(e.getGameObject()); }
    @Subscribe public void onGameObjectDespawned(GameObjectDespawned e) { stars.remove(e.getGameObject()); }
    @Subscribe public void onGameStateChanged(GameStateChanged e) { if (e.getGameState() == GameState.LOADING) { stars.clear(); } }
}
