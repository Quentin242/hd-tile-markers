/*
 * Display rules adapted from RuneLite's Runecraft plugin (AbyssOverlay.render and renderRift, the rift list of
 * AbyssRifts and the rift tracking of RunecraftPlugin; https://github.com/runelite/runelite, tag runelite-parent-1.12.39).
 * BSD 2-Clause License; see META-INF/LICENSE-runelite and THIRD_PARTY_NOTICES.md. Changes for HD Tile Markers: the
 * rifts are tracked from spawn events (the plugin does not expose them) and returned as scene clickboxes.
 */
package com.hdtilemarkers;

import com.google.common.collect.ImmutableMap;
import java.awt.Color;
import java.util.*;
import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.runecraft.RunecraftConfig;

/** The Runecraft plugin's Abyss rift clickboxes, drawn by HD Tile Markers. */
@Singleton
final class AbyssSource
{
    static final String PLUGIN = "net.runelite.client.plugins.runecraft.RunecraftPlugin";
    static final String OVERLAY = "net.runelite.client.plugins.runecraft.AbyssOverlay";
    private static final int ABYSS_REGION = 12107;
    private static final Color FILL = new Color(255, 0, 255, 20);
    /** AbyssRifts: each rift's object and the Runecraft option that shows it. */
    static final Map<Integer, Predicate<RunecraftConfig>> RIFTS = ImmutableMap.<Integer, Predicate<RunecraftConfig>>builder()
        .put(ObjectID.ABYSS_EXIT_TO_AIR, RunecraftConfig::showAir)
        .put(ObjectID.ABYSS_EXIT_TO_BLOOD_PARENT, RunecraftConfig::showBlood)
        .put(ObjectID.ABYSS_EXIT_TO_BODY, RunecraftConfig::showBody)
        .put(ObjectID.ABYSS_EXIT_TO_CHAOS, RunecraftConfig::showChaos)
        .put(ObjectID.ABYSS_EXIT_TO_COSMIC, RunecraftConfig::showCosmic)
        .put(ObjectID.ABYSS_EXIT_TO_DEATH, RunecraftConfig::showDeath)
        .put(ObjectID.ABYSS_EXIT_TO_EARTH, RunecraftConfig::showEarth)
        .put(ObjectID.ABYSS_EXIT_TO_FIRE, RunecraftConfig::showFire)
        .put(ObjectID.ABYSS_EXIT_TO_LAW, RunecraftConfig::showLaw)
        .put(ObjectID.ABYSS_EXIT_TO_MIND, RunecraftConfig::showMind)
        .put(ObjectID.ABYSS_EXIT_TO_NATURE, RunecraftConfig::showNature)
        .put(ObjectID.ABYSS_EXIT_TO_SOUL, RunecraftConfig::showSoul)
        .put(ObjectID.ABYSS_EXIT_TO_WATER, RunecraftConfig::showWater)
        .build();

    private final Client client;
    private final RunecraftConfig config;
    private final Set<DecorativeObject> rifts = Collections.newSetFromMap(new IdentityHashMap<>());

    @Inject
    AbyssSource(Client client, ConfigManager configs)
    {
        this.client = client;
        // Read from ConfigManager, not bound in HD Tile Markers' injector (see BetterNpcView.readConfig).
        config = configs.getConfig(RunecraftConfig.class);
    }

    /** Finds the rifts in the loaded scene (after a rebuild, or when the plugin starts inside the Abyss). */
    void rebuild(WorldView wv)
    {
        rifts.clear();
        if (wv == null || wv.getScene() == null) { return; }
        for (Tile[][] plane : wv.getScene().getTiles())
        {
            for (Tile[] row : plane)
            {
                for (Tile tile : row)
                {
                    if (tile != null) { add(tile.getDecorativeObject()); }
                }
            }
        }
    }

    void collect(List<ModelTarget> models)
    {
        Player player = client.getLocalPlayer();
        if (player == null || player.getWorldLocation().getRegionID() != ABYSS_REGION || rifts.isEmpty()
            || !config.showRifts() || !config.showClickBox()) { return; }
        net.runelite.api.Point mouse = client.getMouseCanvasPosition();
        for (DecorativeObject rift : rifts)
        {
            if (RIFTS.get(rift.getId()).test(config))
            {
                HoverClickboxes.clickbox(models, "abyss:" + rift.getHash(), rift, Color.MAGENTA, FILL, mouse);
            }
        }
    }

    private void add(DecorativeObject object)
    {
        if (object != null && RIFTS.containsKey(object.getId())) { rifts.add(object); }
    }

    @Subscribe public void onDecorativeObjectSpawned(DecorativeObjectSpawned e) { add(e.getDecorativeObject()); }
    @Subscribe public void onDecorativeObjectDespawned(DecorativeObjectDespawned e) { rifts.remove(e.getDecorativeObject()); }

    @Subscribe public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() == GameState.LOADING) { rifts.clear(); }
    }
}
