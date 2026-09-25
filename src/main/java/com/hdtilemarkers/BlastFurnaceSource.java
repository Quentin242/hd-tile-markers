/*
 * Display rules adapted from RuneLite's Blast Furnace plugin (BlastFurnaceClickBoxOverlay.render and renderObject,
 * and the object tracking of BlastFurnacePlugin; https://github.com/runelite/runelite, tag runelite-parent-1.12.39).
 * BSD 2-Clause License; see META-INF/LICENSE-runelite and THIRD_PARTY_NOTICES.md. Changes for HD Tile Markers: the
 * conveyor belt and bar dispenser are tracked from spawn events (the plugin does not expose them) and returned as
 * scene clickboxes.
 */
package com.hdtilemarkers;

import java.awt.Color;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.blastfurnace.BlastFurnaceConfig;

/** The Blast Furnace plugin's conveyor belt and bar dispenser clickboxes, drawn by HD Tile Markers. */
@Singleton
final class BlastFurnaceSource
{
    static final String PLUGIN = "net.runelite.client.plugins.blastfurnace.BlastFurnacePlugin";
    static final String OVERLAY = "net.runelite.client.plugins.blastfurnace.BlastFurnaceClickBoxOverlay";
    private static final int CONVEYOR_BELT = ObjectID.BLAST_FURNACE_CONVEYER_BELT_CLICKABLE, BAR_DISPENSER = ObjectID.BLAST_FURNACE_DISPENSER;
    /** The overlay's own limit; HD Tile Markers' draw distance applies with extended ranges. */
    private static final int MAX_DISTANCE = 2350;

    private final Client client;
    private final BlastFurnaceConfig config;
    private final HdTileMarkersConfig hdConfig;
    private GameObject conveyorBelt, barDispenser;

    @Inject
    BlastFurnaceSource(Client client, ConfigManager configs, HdTileMarkersConfig hdConfig)
    {
        this.client = client; this.hdConfig = hdConfig;
        // Read from ConfigManager, not bound in HD Tile Markers' injector (see BetterNpcView.readConfig).
        config = configs.getConfig(BlastFurnaceConfig.class);
    }

    /** Finds both objects in the loaded scene (after a rebuild, or when the plugin starts inside the Blast Furnace). */
    void rebuild(WorldView wv)
    {
        conveyorBelt = null;
        barDispenser = null;
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
        Player player = client.getLocalPlayer();
        if (player == null) { return; }
        int state = client.getVarbitValue(VarbitID.BLAST_FURNACE_BARS_HOT);
        net.runelite.api.Point mouse = client.getMouseCanvasPosition();
        int maxDistance = MarkerSources.pluginRange(hdConfig, MAX_DISTANCE);
        if (config.showConveyorBelt() && conveyorBelt != null)
        {
            add(models, "blastfurnace:belt", conveyorBelt, state == 1 ? Color.RED : Color.GREEN, player, mouse, maxDistance);
        }
        if (config.showBarDispenser() && barDispenser != null)
        {
            Color color = state == 2 && hasIceGloves() ? Color.GREEN : state == 3 ? Color.GREEN : Color.RED;
            add(models, "blastfurnace:dispenser", barDispenser, color, player, mouse, maxDistance);
        }
    }

    private void add(List<ModelTarget> models, String key, GameObject object, Color color, Player player, net.runelite.api.Point mouse, int maxDistance)
    {
        LocalPoint location = object.getLocalLocation();
        if (location == null || player.getLocalLocation().distanceTo(location) > maxDistance) { return; }
        HoverClickboxes.clickbox(models, key, object, color, new Color(color.getRed(), color.getGreen(), color.getBlue(), 20), mouse);
    }

    private boolean hasIceGloves()
    {
        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        return worn != null && (worn.contains(ItemID.ICE_GLOVES) || worn.contains(ItemID.SMITHING_UNIFORM_GLOVES_ICE));
    }

    private void add(GameObject object)
    {
        if (object == null) { return; }
        if (object.getId() == CONVEYOR_BELT) { conveyorBelt = object; }
        else if (object.getId() == BAR_DISPENSER) { barDispenser = object; }
    }

    @Subscribe public void onGameObjectSpawned(GameObjectSpawned e) { add(e.getGameObject()); }

    @Subscribe public void onGameObjectDespawned(GameObjectDespawned e)
    {
        if (e.getGameObject() == conveyorBelt) { conveyorBelt = null; }
        if (e.getGameObject() == barDispenser) { barDispenser = null; }
    }

    @Subscribe public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() == GameState.LOADING) { conveyorBelt = null; barDispenser = null; }
    }
}
