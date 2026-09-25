/*
 * Display rules adapted from RuneLite's Pyramid Plunder plugin (PyramidPlunderOverlay.render and the object IDs of
 * PyramidPlunderPlugin; https://github.com/runelite/runelite, tag runelite-parent-1.12.39). BSD 2-Clause License;
 * see META-INF/LICENSE-runelite and THIRD_PARTY_NOTICES.md. Changes for HD Tile Markers: the plugin's objects are
 * read through its public getters and returned as scene hulls and clickboxes.
 */
package com.hdtilemarkers;

import com.google.common.collect.ImmutableSet;
import java.awt.Color;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.pyramidplunder.PyramidPlunderConfig;
import net.runelite.client.plugins.pyramidplunder.PyramidPlunderPlugin;
import net.runelite.client.util.ColorUtil;

/** The Pyramid Plunder plugin's container hulls and door and speartrap clickboxes, drawn by HD Tile Markers. */
@Singleton
final class PyramidPlunderSource
{
    static final String PLUGIN = "net.runelite.client.plugins.pyramidplunder.PyramidPlunderPlugin";
    static final String OVERLAY = "net.runelite.client.plugins.pyramidplunder.PyramidPlunderOverlay";
    private static final int MAX_DISTANCE = 2350;
    private static final Set<Integer> TOMB_DOOR_WALL_IDS = ImmutableSet.of(ObjectID.NTK_TOMB_DOOR1, ObjectID.NTK_TOMB_DOOR2,
        ObjectID.NTK_TOMB_DOOR3, ObjectID.NTK_TOMB_DOOR4);
    private static final int TOMB_DOOR_CLOSED_ID = ObjectID.NTK_TOMB_DOOR_NOANIM, SPEARTRAP_ID = ObjectID.NTK_SPEARTRAP_INMOTION;
    private static final Set<Integer> URN_IDS = ImmutableSet.of(ObjectID.NTK_URN_TYPE1_MULTI_1, ObjectID.NTK_URN_TYPE1_MULTI_2,
        ObjectID.NTK_URN_TYPE1_MULTI_3, ObjectID.NTK_URN_TYPE1_MULTI_4, ObjectID.NTK_URN_TYPE1_MULTI_5, ObjectID.NTK_URN_TYPE2_MULTI_6,
        ObjectID.NTK_URN_TYPE2_MULTI_7, ObjectID.NTK_URN_TYPE2_MULTI_8, ObjectID.NTK_URN_TYPE2_MULTI_9, ObjectID.NTK_URN_TYPE2_MULTI_10,
        ObjectID.NTK_URN_TYPE3_MULTI_11, ObjectID.NTK_URN_TYPE3_MULTI_12, ObjectID.NTK_URN_TYPE3_MULTI_13, ObjectID.NTK_URN_TYPE3_MULTI_14,
        ObjectID.NTK_URN_TYPE3_MULTI_15);
    private static final Set<Integer> URN_CLOSED_IDS = ImmutableSet.of(ObjectID.NTK_URN1_CLOSED, ObjectID.NTK_URN2_CLOSED, ObjectID.NTK_URN3_CLOSED);
    private static final int GRAND_GOLD_CHEST_ID = ObjectID.NTK_GOLDEN_CHEST_MULTI, GRAND_GOLD_CHEST_CLOSED_ID = ObjectID.NTK_GOLDEN_CHEST_CLOSED;
    private static final int SARCOPHAGUS_ID = ObjectID.NTK_SARCOPHAGUS_MULTI, SARCOPHAGUS_CLOSED_ID = ObjectID.NTK_SARCOPHAGUS;

    private final Client client;
    private final net.runelite.client.plugins.PluginManager plugins;
    private final PyramidPlunderConfig config;
    private final HdTileMarkersConfig hdConfig;

    @Inject
    PyramidPlunderSource(Client client, net.runelite.client.plugins.PluginManager plugins, ConfigManager configs, HdTileMarkersConfig hdConfig)
    {
        this.client = client; this.plugins = plugins; this.hdConfig = hdConfig;
        // Read from ConfigManager, not bound in HD Tile Markers' injector (see BetterNpcView.readConfig).
        config = configs.getConfig(PyramidPlunderConfig.class);
    }

    void collect(List<ModelTarget> models)
    {
        Player player = client.getLocalPlayer();
        WorldView wv = client.getTopLevelWorldView();
        // As the overlay: nothing outside the minigame (its timer widget only exists inside).
        PyramidPlunderPlugin plugin = plugin();
        if (plugin == null || player == null || wv == null || client.getWidget(net.runelite.api.gameval.InterfaceID.NtkOverlay.CONTENT) == null) { return; }
        LocalPoint origin = player.getLocalLocation();
        int range = MarkerSources.pluginRange(hdConfig, MAX_DISTANCE);
        // Containers still closed below the configured floors: their convex hulls.
        int floor = client.getVarbitValue(VarbitID.NTK_ROOM_NUMBER);
        for (GameObject object : plugin.getObjectsToHighlight())
        {
            if (config.highlightUrnsFloor() > floor && URN_IDS.contains(object.getId())
                || config.highlightChestFloor() > floor && GRAND_GOLD_CHEST_ID == object.getId()
                || config.highlightSarcophagusFloor() > floor && SARCOPHAGUS_ID == object.getId()
                || object.getLocalLocation().distanceTo(origin) >= range)
            {
                continue;
            }
            int imposter = imposter(object);
            if (URN_CLOSED_IDS.contains(imposter) || GRAND_GOLD_CHEST_CLOSED_ID == imposter || SARCOPHAGUS_CLOSED_ID == imposter)
            {
                HoverClickboxes.hull(models, "pyramidplunder:" + object.getHash(), object, config.highlightContainersColor());
            }
        }
        // Speartraps while the room's trap is active, and closed tomb doors: their clickboxes.
        net.runelite.api.Point mouse = client.getMouseCanvasPosition();
        for (Map.Entry<TileObject, Tile> entry : plugin.getTilesToHighlight().entrySet())
        {
            TileObject object = entry.getKey();
            if (!config.highlightDoors() && TOMB_DOOR_WALL_IDS.contains(object.getId())
                || !config.highlightSpeartraps() && SPEARTRAP_ID == object.getId()
                || entry.getValue().getPlane() != wv.getPlane()
                || object.getLocalLocation().distanceTo(origin) >= range)
            {
                continue;
            }
            Color color;
            if (SPEARTRAP_ID == object.getId())
            {
                // Set to 1 on entering a room, 0 once past its spear traps.
                if (client.getVarbitValue(VarbitID.NTK_TRAP_ACTIVE) != 1) { continue; }
                color = config.highlightSpeartrapsColor();
            }
            else
            {
                if (imposter(object) != TOMB_DOOR_CLOSED_ID) { continue; }
                color = config.highlightDoorsColor();
            }
            HoverClickboxes.clickbox(models, "pyramidplunder:" + object.getHash(), object, color,
                ColorUtil.colorWithAlpha(color, color.getAlpha() / 5), mouse);
        }
    }

    /**
     * The plugin's instance, from the plugin list: injecting it would need a @PluginDependency on it, which HD Tile
     * Markers avoids for plugins it only reads.
     */
    private PyramidPlunderPlugin plugin()
    {
        for (net.runelite.client.plugins.Plugin p : plugins.getPlugins())
        {
            if (p instanceof PyramidPlunderPlugin) { return (PyramidPlunderPlugin) p; }
        }
        return null;
    }

    private int imposter(TileObject object)
    {
        ObjectComposition composition = client.getObjectDefinition(object.getId());
        ObjectComposition imposter = composition == null ? null : composition.getImpostor();
        return imposter == null ? -1 : imposter.getId();
    }
}
