/*
 * Target rules adapted from Sailing (https://github.com/LlemonDuck/sailing, commit af987f3be93bf0ca666af3d1f111cbf91e6a4d0d):
 * RapidsOverlay, LightningCloudsOverlay, SalvagingHighlight, LostCargoHighlighter, TrueTileIndicator and HelmTier.
 * Copyright (c) 2025, LlemonDuck. BSD 2-Clause License; see META-INF/LICENSE-sailing and THIRD_PARTY_NOTICES.md.
 * Changes for HD Tile Markers: read-only; the marks are returned to HD Tile Markers' renderer instead of drawn.
 */
package com.hdtilemarkers;

import java.awt.Color;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/** The sea marks of the Sailing plugin, drawn by HD Tile Markers: rapids, lightning clouds, wrecks, lost crates and the boat's true tile. */
@Singleton
final class SailingSource
{
    static final String GROUP = "sailing";
    /** Its overlays HD Tile Markers replaces, by class name in its package. */
    static final Set<String> OVERLAYS = new HashSet<>(Arrays.asList("RapidsOverlay", "LightningCloudsOverlay",
        "SalvagingHighlight", "LostCargoHighlighter", "TrueTileIndicator"));
    static final String PACKAGE = "com.duckblade.osrs.sailing.";
    /** OverlayUtil.renderPolygon's fill. */
    static final Color FILL = new Color(0, 0, 0, 50);
    private static final int SALVAGEABLE_AREA = 15, CRATE_AREA = 5;

    private static final Set<Integer> RAPIDS = new HashSet<>(Arrays.asList(
        ObjectID.SAILING_RAPIDS, ObjectID.SAILING_RAPIDS_STRONG, ObjectID.SAILING_RAPIDS_POWERFUL,
        ObjectID.SAILING_RAPIDS_DEADLY, ObjectID.SAILING_CHARTING_RAPIDS_KHARIDIAN_SEA,
        ObjectID.SAILING_CHARTING_RAPIDS_BAY_OF_SARIM, ObjectID.SAILING_CHARTING_RAPIDS_GREAT_SOUND,
        ObjectID.SAILING_CHARTING_RAPIDS_LUMBRIDGE_BASIN, ObjectID.SAILING_CHARTING_RAPIDS_CRABCLAW_BAY,
        ObjectID.SAILING_CHARTING_RAPIDS_MUDSKIPPER_SOUND, ObjectID.SAILING_CHARTING_RAPIDS_RIMMINGTON_STRAIT,
        ObjectID.SAILING_CHARTING_RAPIDS_CATHERBY_BAY, ObjectID.SAILING_CHARTING_RAPIDS_BRIMHAVEN_PASSAGE,
        ObjectID.SAILING_CHARTING_RAPIDS_GULF_OF_KOUREND, ObjectID.SAILING_CHARTING_RAPIDS_STRAIT_OF_KHAZARD,
        ObjectID.SAILING_CHARTING_RAPIDS_GUTANOTH_BAY, ObjectID.SAILING_CHARTING_RAPIDS_HOSIDIAN_SEA,
        ObjectID.SAILING_CHARTING_RAPIDS_PILGRIMS_PASSAGE, ObjectID.SAILING_CHARTING_RAPIDS_FELDIP_GULF,
        ObjectID.SAILING_CHARTING_RAPIDS_KHARAZI_STRAIT, ObjectID.SAILING_CHARTING_RAPIDS_LITUS_LUCIS,
        ObjectID.SAILING_CHARTING_RAPIDS_OOGLOG_CHANNEL, ObjectID.SAILING_CHARTING_RAPIDS_FORTIS_BAY,
        ObjectID.SAILING_CHARTING_RAPIDS_ARROW_PASSAGE, ObjectID.SAILING_CHARTING_RAPIDS_AUREUM_COAST,
        ObjectID.SAILING_CHARTING_RAPIDS_MENAPHITE_SEA, ObjectID.SAILING_CHARTING_RAPIDS_TURTLE_BELT,
        ObjectID.SAILING_CHARTING_RAPIDS_WYRMS_WATERS, ObjectID.SAILING_CHARTING_RAPIDS_THE_SIMIAN_SEA,
        ObjectID.SAILING_CHARTING_RAPIDS_SEA_OF_SHELLS, ObjectID.SAILING_CHARTING_RAPIDS_SUNSET_BAY,
        ObjectID.SAILING_CHARTING_RAPIDS_THE_STORM_TEMPOR, ObjectID.SAILING_CHARTING_RAPIDS_RED_REEF,
        ObjectID.SAILING_CHARTING_RAPIDS_MISTY_SEA, ObjectID.SAILING_CHARTING_RAPIDS_MYTHIC_SEA,
        ObjectID.SAILING_CHARTING_RAPIDS_ANGLERFISHS_LIGHT, ObjectID.SAILING_CHARTING_RAPIDS_BAY_OF_ELIDINIS,
        ObjectID.SAILING_CHARTING_RAPIDS_BREAKBONE_STRAIT, ObjectID.SAILING_CHARTING_RAPIDS_TORTUGAN_SEA,
        ObjectID.SAILING_CHARTING_RAPIDS_DUSKS_MAW, ObjectID.SAILING_CHARTING_RAPIDS_BACKWATER,
        ObjectID.SAILING_CHARTING_RAPIDS_PEARL_BANK, ObjectID.SAILING_CHARTING_RAPIDS_THE_LONELY_SEA,
        ObjectID.SAILING_CHARTING_RAPIDS_ZUL_EGIL, ObjectID.SAILING_CHARTING_RAPIDS_THE_SKULLHORDE,
        ObjectID.SAILING_CHARTING_RAPIDS_SEA_OF_SOULS, ObjectID.SAILING_CHARTING_RAPIDS_SOUL_BAY,
        ObjectID.SAILING_CHARTING_RAPIDS_BARRACUDA_BELT, ObjectID.SAILING_CHARTING_RAPIDS_THE_EVERDEEP,
        ObjectID.SAILING_CHARTING_RAPIDS_SAPPHIRE_SEA, ObjectID.SAILING_CHARTING_RAPIDS_WESTERN_GATE,
        ObjectID.SAILING_CHARTING_RAPIDS_RAINBOW_REEF, ObjectID.SAILING_CHARTING_RAPIDS_SOUTHERN_EXPANSE,
        ObjectID.SAILING_CHARTING_RAPIDS_PORTH_NEIGWL, ObjectID.SAILING_CHARTING_RAPIDS_TIRANNWN_BIGHT,
        ObjectID.SAILING_CHARTING_RAPIDS_CRYSTAL_SEA, ObjectID.SAILING_CHARTING_RAPIDS_PORTH_GWENITH,
        ObjectID.SAILING_CHARTING_RAPIDS_PISCATORIS_SEA, ObjectID.SAILING_CHARTING_RAPIDS_VAGABONDS_REST,
        ObjectID.SAILING_CHARTING_RAPIDS_MOONSHADOW, ObjectID.SAILING_CHARTING_RAPIDS_FREMENSUND,
        ObjectID.SAILING_CHARTING_RAPIDS_GRANDROOT_BAY, ObjectID.SAILING_CHARTING_RAPIDS_VS_BELT,
        ObjectID.SAILING_CHARTING_RAPIDS_FREMENNIK_STRAIT, ObjectID.SAILING_CHARTING_RAPIDS_IDESTIA_STRAIT,
        ObjectID.SAILING_CHARTING_RAPIDS_LUNAR_BAY, ObjectID.SAILING_CHARTING_RAPIDS_WINTERS_EDGE,
        ObjectID.SAILING_CHARTING_RAPIDS_LUNAR_SEA, ObjectID.SAILING_CHARTING_RAPIDS_EVERWINTER_SEA,
        ObjectID.SAILING_CHARTING_RAPIDS_KANNSKI_TIDES, ObjectID.SAILING_CHARTING_RAPIDS_WEISSMERE,
        ObjectID.SAILING_CHARTING_RAPIDS_STONEHEART_SEA, ObjectID.SAILING_CHARTING_RAPIDS_SHIVERWAKE_EXPANSE,
        ObjectID.SAILING_CHARTING_RAPIDS_WEISS_MELT));
    /** Helm tier (BRONZE, IRON, STEEL, MITHRIL, ADAMANT, RUNE, DRAGON) by helm object id. */
    private static final Map<Integer, Integer> HELM_TIER = new HashMap<>();
    private static final Map<Integer, Integer> SALVAGE_LEVEL = new HashMap<>(), STUMP_LEVEL = new HashMap<>();
    private static final int IRON = 1, MITHRIL = 3, RUNE = 5;

    static
    {
        helm(0, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_WOOD, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_WOOD_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_WOOD_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_WOOD, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_WOOD_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_WOOD_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_WOOD, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_WOOD_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_WOOD_IDLE);
        helm(1, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_OAK, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_OAK_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_OAK_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_OAK, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_OAK_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_OAK_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_OAK, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_OAK_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_OAK_IDLE);
        helm(2, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_TEAK, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_TEAK_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_TEAK_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_TEAK, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_TEAK_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_TEAK_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_TEAK, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_TEAK_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_TEAK_IDLE);
        helm(3, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_MAHOGANY, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_MAHOGANY_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_MAHOGANY_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_MAHOGANY, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_MAHOGANY_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_MAHOGANY_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_MAHOGANY, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_MAHOGANY_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_MAHOGANY_IDLE);
        helm(4, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_CAMPHOR, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_CAMPHOR_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_CAMPHOR_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_CAMPHOR, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_CAMPHOR_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_CAMPHOR_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_CAMPHOR, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_CAMPHOR_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_CAMPHOR_IDLE);
        helm(5, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_IRONWOOD, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_IRONWOOD_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_IRONWOOD_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_IRONWOOD, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_IRONWOOD_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_IRONWOOD_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_IRONWOOD, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_IRONWOOD_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_IRONWOOD_IDLE);
        helm(6, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_ROSEWOOD, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_ROSEWOOD_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_1X3_ROSEWOOD_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_ROSEWOOD, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_ROSEWOOD_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_2X5_ROSEWOOD_IDLE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_ROSEWOOD, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_ROSEWOOD_IN_USE, ObjectID.SAILING_BOAT_STEERING_KANDARIN_3X8_ROSEWOOD_IDLE);
        SALVAGE_LEVEL.put(ObjectID.SAILING_SMALL_SHIPWRECK, 15);
        SALVAGE_LEVEL.put(ObjectID.SAILING_FISHERMAN_SHIPWRECK, 26);
        SALVAGE_LEVEL.put(ObjectID.SAILING_BARRACUDA_SHIPWRECK, 35);
        SALVAGE_LEVEL.put(ObjectID.SAILING_LARGE_SHIPWRECK, 53);
        SALVAGE_LEVEL.put(ObjectID.SAILING_PIRATE_SHIPWRECK, 64);
        SALVAGE_LEVEL.put(ObjectID.SAILING_MERCENARY_SHIPWRECK, 73);
        SALVAGE_LEVEL.put(ObjectID.SAILING_FREMENNIK_SHIPWRECK, 80);
        SALVAGE_LEVEL.put(ObjectID.SAILING_MERCHANT_SHIPWRECK, 87);
        STUMP_LEVEL.put(ObjectID.SAILING_SMALL_SHIPWRECK_STUMP, 15);
        STUMP_LEVEL.put(ObjectID.SAILING_FISHERMAN_SHIPWRECK_STUMP, 26);
        STUMP_LEVEL.put(ObjectID.SAILING_BARRACUDA_SHIPWRECK_STUMP, 35);
        STUMP_LEVEL.put(ObjectID.SAILING_LARGE_SHIPWRECK_STUMP, 53);
        STUMP_LEVEL.put(ObjectID.SAILING_PIRATE_SHIPWRECK_STUMP, 64);
        STUMP_LEVEL.put(ObjectID.SAILING_MERCENARY_SHIPWRECK_STUMP, 73);
        STUMP_LEVEL.put(ObjectID.SAILING_FREMENNIK_SHIPWRECK_STUMP, 80);
        STUMP_LEVEL.put(ObjectID.SAILING_MERCHANT_SHIPWRECK_STUMP, 87);
    }

    private static void helm(int tier, int... ids) { for (int id : ids) { HELM_TIER.put(id, tier); } }

    static boolean lostCrate(int id)
    {
        return id >= ObjectID.SAILING_BT_GWENITH_GLIDE_COLLECTABLE_1 && id <= ObjectID.SAILING_BT_GWENITH_GLIDE_COLLECTABLE_96
            || id >= ObjectID.SAILING_BT_JUBBLY_JIVE_COLLECTABLE_1 && id <= ObjectID.SAILING_BT_JUBBLY_JIVE_COLLECTABLE_56
            || id >= ObjectID.SAILING_BT_TEMPOR_TANTRUM_COLLECTABLE_1 && id <= ObjectID.SAILING_BT_TEMPOR_TANTRUM_COLLECTABLE_36;
    }

    private final Client client;
    private final ConfigManager configs;
    private final Set<GameObject> rapids = Collections.newSetFromMap(new IdentityHashMap<>()), wrecks = Collections.newSetFromMap(new IdentityHashMap<>()),
        crates = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<NPC> clouds = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Helm per boat world view, as its BoatTracker. */
    private final Map<Integer, GameObject> helms = new HashMap<>();

    @Inject
    SailingSource(Client client, ConfigManager configs) { this.client = client; this.configs = configs; }

    private boolean setting(String key, boolean fallback)
    {
        String value = configs.getConfiguration(GROUP, key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private Color color(String key, Color fallback)
    {
        Color value = configs.getConfiguration(GROUP, key, Color.class);
        return value == null ? fallback : value;
    }

    /** SailingUtil.isSailing: the player stands on a boat. */
    boolean sailing()
    {
        Player player = client.getLocalPlayer();
        return player != null && player.getWorldView() != null && !player.getWorldView().isTopLevel();
    }

    void collect(List<Marker> out)
    {
        if (!sailing()) { return; }
        WorldView top = client.getTopLevelWorldView();
        int plane = top.getPlane();
        if (setting("highlightRapids", true))
        {
            Color safe = color("safeRapidsColour", Color.CYAN), dangerous = color("dangerousRapidsColour", Color.RED),
                unknown = color("unknownRapidsColour", Color.YELLOW);
            for (GameObject rapid : rapids)
            {
                ObjectComposition def = transformed(rapid);
                if (def == null) { continue; }
                Marker m = footprint("sail:rapid:" + rapid.getHash(), rapid, plane, rapidColour(def.getId(), safe, dangerous, unknown));
                if (m != null) { out.add(m); }
            }
        }
        if (setting("highlightLightningCloudStrikes", true))
        {
            Color strike = color("lightningCloudStrikeColour", new Color(210, 109, 3));
            for (NPC cloud : clouds)
            {
                int anim = cloud.getAnimation();
                NPCComposition c = cloud.getTransformedComposition();
                if (anim != AnimationID.TEMPOROSS_LIGHTNING_CLOUD_CHARGING_IDLE && anim != AnimationID.SAILING_LIGHTNING_CLOUD_ATTACK
                    || c == null || cloud.getLocalLocation() == null) { continue; }
                Color color = anim == AnimationID.SAILING_LIGHTNING_CLOUD_ATTACK ? strike.darker() : strike;
                out.add(layer(new Marker("sail:cloud:" + cloud.getIndex(), cloud.getLocalLocation(), plane, c.getSize(), c.getSize(), color, FILL, 2, null, false)));
            }
        }
        boolean active = setting("salvagingHighlightActiveWrecks", true), inactive = setting("salvagingHighlightInactiveWrecks", true),
            highLevel = setting("salvagingHideHighLevelWrecks", false);
        if (active || inactive || highLevel)
        {
            int level = client.getBoostedSkillLevel(Skill.SAILING);
            Color activeColour = color("salvagingHighlightActiveWrecksColour", Color.GREEN),
                inactiveColour = color("salvagingHighlightInactiveWrecksColour", Color.DARK_GRAY),
                highColour = color("salvagingHideHighLevelWrecksColour", new Color(255, 0, 0, 64));
            for (GameObject wreck : wrecks)
            {
                boolean stump = STUMP_LEVEL.containsKey(wreck.getId());
                boolean hasReq = level >= (stump ? STUMP_LEVEL : SALVAGE_LEVEL).get(wreck.getId());
                if (hasReq ? !(stump ? inactive : active) : !highLevel) { continue; }
                Color color = !hasReq ? highColour : stump ? inactiveColour : activeColour;
                out.add(layer(new Marker("sail:wreck:" + wreck.getHash(), wreck.getLocalLocation(), plane, SALVAGEABLE_AREA, SALVAGEABLE_AREA,
                    color, FILL, 2, null, false)));
            }
        }
        if (setting("barracudaHighlightLostCrates", true))
        {
            Color crate = color("barracudaHighlightLostCratesColour", Color.ORANGE);
            for (GameObject o : crates)
            {
                if (transformed(o) == null) { continue; }
                out.add(layer(new Marker("sail:crate:" + o.getHash(), o.getLocalLocation(), plane, CRATE_AREA, CRATE_AREA, crate, FILL, 2, null, false)));
            }
        }
        Marker trueTile = trueTile(top, plane);
        if (trueTile != null) { out.add(trueTile); }
    }

    /** TrueTileIndicator: the boat's bounds at its server position and heading. */
    private Marker trueTile(WorldView top, int plane)
    {
        String mode = configs.getConfiguration(GROUP, "navigationTrueTileIndicator");
        if (mode == null || "OFF".equals(mode)) { return null; }
        if ("NAVIGATING".equals(mode) && top.getYellowClickAction() != Constants.CLICK_ACTION_SET_HEADING) { return null; }
        WorldEntity we = top.worldEntities().byIndex(client.getLocalPlayer().getWorldView().getId());
        if (we == null || we.getConfig() == null || we.getTargetLocation() == null) { return null; }
        return layer(boatArea("sail:truetile", we.getConfig(), we.getTargetLocation(), we.getTargetOrientation(), plane,
            color("navigationTrueTileIndicatorColor", Color.CYAN)));
    }

    /** renderBoatArea: the four corners of the bounds, rotated as Perspective.modelToCanvas. */
    static Marker boatArea(String key, WorldEntityConfig wec, LocalPoint lp, int angle, int plane, Color color)
    {
        int halfWidth = wec.getBoundsWidth() / 2, halfHeight = wec.getBoundsHeight() / 2;
        float[] x = {wec.getBoundsX() + halfWidth, wec.getBoundsX() + halfWidth, wec.getBoundsX() - halfWidth, wec.getBoundsX() - halfWidth};
        float[] y = {wec.getBoundsY() - halfHeight, wec.getBoundsY() + halfHeight, wec.getBoundsY() + halfHeight, wec.getBoundsY() - halfHeight};
        float sin = Perspective.SINE[angle & 2047] / 65536f, cos = Perspective.COSINE[angle & 2047] / 65536f;
        int[] qx = new int[4], qy = new int[4];
        for (int i = 0; i < 4; i++)
        {
            qx[i] = lp.getX() + Math.round(x[i] * cos + y[i] * sin);
            qy[i] = lp.getY() + Math.round(y[i] * cos - x[i] * sin);
        }
        Marker m = new Marker(key, lp, plane, 1, 1, color, Marker.NO_FILL, 1, null, false);
        m.quadX = qx; m.quadY = qy;
        return m;
    }

    private int rapidColourTier()
    {
        Player player = client.getLocalPlayer();
        GameObject helm = player == null ? null : helms.get(player.getWorldView().getId());
        Integer tier = helm == null ? null : HELM_TIER.get(helm.getId());
        return tier == null ? -1 : tier;
    }

    private Color rapidColour(int id, Color safe, Color dangerous, Color unknown)
    {
        int minTier = id == ObjectID.SAILING_RAPIDS ? IRON : id == ObjectID.SAILING_RAPIDS_STRONG ? MITHRIL
            : id == ObjectID.SAILING_RAPIDS_POWERFUL ? RUNE : -1;
        int tier = rapidColourTier();
        if (minTier < 0 || tier < 0) { return unknown; }
        return tier >= minTier ? safe : dangerous;
    }

    private ObjectComposition transformed(GameObject o)
    {
        ObjectComposition def = client.getObjectDefinition(o.getId());
        return def == null || def.getImpostorIds() == null ? def : def.getImpostor();
    }

    /** OverlayUtil.renderTileOverlay: the object's footprint. */
    private static Marker footprint(String key, GameObject o, int plane, Color color)
    {
        int width = o.getSceneMaxLocation().getX() - o.getSceneMinLocation().getX() + 1;
        int height = o.getSceneMaxLocation().getY() - o.getSceneMinLocation().getY() + 1;
        if (width <= 0 || height <= 0 || width > 64 || height > 64) { return null; }
        LocalPoint point = LocalPoint.fromScene(o.getSceneMinLocation().getX(), o.getSceneMinLocation().getY(), o.getWorldView())
            .plus((width - 1) * 64, (height - 1) * 64);
        return layer(new Marker(key, point, plane, width, height, color, FILL, 2, null, false));
    }

    private static Marker layer(Marker m) { m.layer = Marker.SAILING; return m; }

    void add(GameObject o)
    {
        if (o == null) { return; }
        int id = o.getId();
        if (HELM_TIER.containsKey(id) && o.getWorldView() != null && !o.getWorldView().isTopLevel()) { helms.put(o.getWorldView().getId(), o); }
        if (o.getWorldView() == null || !o.getWorldView().isTopLevel()) { return; }
        if (RAPIDS.contains(id)) { rapids.add(o); }
        else if (SALVAGE_LEVEL.containsKey(id) || STUMP_LEVEL.containsKey(id)) { wrecks.add(o); }
        else if (lostCrate(id)) { crates.add(o); }
    }

    void remove(GameObject o)
    {
        rapids.remove(o); wrecks.remove(o); crates.remove(o);
        helms.values().removeIf(h -> h == o);
    }

    void add(NPC npc) { if (npc.getId() == NpcID.SAILING_SEA_STORMY_CLOUD) { clouds.add(npc); } }

    void clear() { rapids.clear(); wrecks.clear(); crates.clear(); clouds.clear(); helms.clear(); }

    /** Picks up what was already loaded: the sea and every boat in it. */
    void rebuild(WorldView top)
    {
        clear();
        if (top == null) { return; }
        scan(top);
        for (WorldView boat : top.worldViews()) { scan(boat); }
    }

    void scan(WorldView wv)
    {
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
    @Subscribe public void onGameObjectDespawned(GameObjectDespawned e) { remove(e.getGameObject()); }
    @Subscribe public void onNpcSpawned(NpcSpawned e) { add(e.getNpc()); }
    @Subscribe public void onNpcDespawned(NpcDespawned e) { clouds.remove(e.getNpc()); }
    @Subscribe public void onWorldViewUnloaded(WorldViewUnloaded e)
    {
        if (e.getWorldView().isTopLevel()) { rapids.clear(); wrecks.clear(); crates.clear(); clouds.clear(); }
        else { helms.remove(e.getWorldView().getId()); }
    }
}
