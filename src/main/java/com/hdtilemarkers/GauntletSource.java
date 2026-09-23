/*
 * Adapted from The Gauntlet (https://github.com/LlemonDuck/the-gauntlet, commit bf0246abf6dc04ce5541264c10e663536f9864c2):
 * the resource and utility object IDs of MazeModule, ResourceGameObject and Resource, the chat message
 * tracking of ResourceManager and the display rules of MazeOverlay. Copyright (c) 2023, rdutta.
 * BSD 2-Clause License; see META-INF/LICENSE-the-gauntlet and THIRD_PARTY_NOTICES.md.
 * Changes for HD Tile Markers: read-only (its settings, infoboxes and NPC highlights stay its own);
 * the marks are returned to HD Tile Markers' renderer instead of drawn.
 */
package com.hdtilemarkers;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

/** The Gauntlet's maze resources and utilities, drawn by HD Tile Markers with that plugin's settings. */
@Singleton
final class GauntletSource
{
    static final String GROUP = "thegauntlet";
    static final String PLUGIN = "ca.gauntlet.TheGauntletPlugin", OVERLAY = "ca.gauntlet.module.maze.MazeOverlay";
    private static final int GAUNTLET_TIMER = 637, REGION_NORMAL = 7512, REGION_CORRUPTED = 7768, SHARDS_BREAK_DOWN = 80;
    private static final Pattern RESOURCE_DROP = Pattern.compile("^.+ drop:\\s+((?<quantity>\\d+) x )?(?<name>.+)$");
    private static final Pattern SHARD_MONSTER_DROP = Pattern.compile("You gather (\\d+) Crystal Shards\\.?");

    /** What is gathered, as The Gauntlet's Resource: its drop name and the chat message of gathering it. */
    enum Resource
    {
        WEAPON_FRAME("Weapon frame", null), CRYSTALLINE_BOWSTRING("Crystalline bowstring", null), CORRUPTED_BOWSTRING("Corrupted bowstring", null),
        CRYSTAL_SPIKE("Crystal spike", null), CORRUPTED_SPIKE("Corrupted spike", null), CRYSTAL_ORB("Crystal orb", null), CORRUPTED_ORB("Corrupted orb", null),
        RAW_PADDLEFISH("Raw paddlefish", "You manage to catch a fish\\."),
        CRYSTAL_SHARDS("Crystal shards", "You find (\\d+) crystal shards\\."), CORRUPTED_SHARDS("Corrupted shards", "You find (\\d+) corrupted shards\\."),
        ORE("Crystal ore", "You manage to mine some ore\\."), CORRUPTED_ORE("Corrupted ore", "You manage to mine some ore\\."),
        BARK("Phren bark", "You get some bark\\."), LINUM("Linum tirinum", "You pick some fibre from the plant\\."),
        GRYM("Grym leaf", "You pick a herb from the roots\\.");

        final String name;
        final Pattern pattern;

        Resource(String name, String pattern) { this.name = name; this.pattern = pattern == null ? null : Pattern.compile(pattern); }
    }

    /** A resource node: the resource it gives, its overlay settings and its skill icon. */
    enum Node
    {
        ORE("overlayOreDeposit", "oreDeposit", Skill.MINING, 36064, 35967),
        BARK("overlayPhrenRoots", "phrenRoots", Skill.WOODCUTTING, 36066, 35969),
        FISH("overlayFishingSpot", "fishingSpot", Skill.FISHING, 36068, 35971),
        GRYM("overlayGrymRoot", "grymRoot", Skill.HERBLORE, 36070, 35973),
        LINUM("overlayLinumTirinum", "linumTirinum", Skill.FARMING, 36072, 35975);

        final String toggle, colors;
        final Skill skill;
        final int normalId, corruptedId;

        Node(String toggle, String colors, Skill skill, int normalId, int corruptedId)
        {
            this.toggle = toggle; this.colors = colors; this.skill = skill; this.normalId = normalId; this.corruptedId = corruptedId;
        }

        Resource resource(boolean corrupted)
        {
            switch (this)
            {
                case ORE: return corrupted ? Resource.CORRUPTED_ORE : Resource.ORE;
                case BARK: return Resource.BARK;
                case FISH: return Resource.RAW_PADDLEFISH;
                case GRYM: return Resource.GRYM;
                default: return Resource.LINUM;
            }
        }

        static Node of(int id)
        {
            for (Node n : values()) { if (n.normalId == id || n.corruptedId == id) { return n; } }
            return null;
        }
    }

    private static final Set<Integer> UTILITIES = new HashSet<>(Arrays.asList(35966, 35980, 35981, 36063, 36077, 36078));

    private final Client client;
    private final ConfigManager configs;
    private final SkillIconManager skillIcons;
    private final Map<GameObject, Node> nodes = new IdentityHashMap<>();
    private final Set<GameObject> utilities = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Still to gather per resource this run, from The Gauntlet's amounts; absent when not tracked. */
    private final EnumMap<Resource, Integer> remaining = new EnumMap<>(Resource.class);
    private boolean corrupted, inRun;
    private final Map<Node, BufferedImage> icons = new EnumMap<>(Node.class);
    private int iconSize = -1;

    @Inject
    GauntletSource(Client client, ConfigManager configs, SkillIconManager skillIcons)
    {
        this.client = client; this.configs = configs; this.skillIcons = skillIcons;
    }

    private boolean setting(String key, boolean fallback)
    {
        String value = configs.getConfiguration(GROUP, key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private int number(String key, int fallback)
    {
        try
        {
            String value = configs.getConfiguration(GROUP, key);
            return value == null ? fallback : Integer.parseInt(value);
        }
        catch (NumberFormatException ex) { return fallback; }
    }

    private Color color(String key, Color fallback)
    {
        Color value = configs.getConfiguration(GROUP, key, Color.class);
        return value == null ? fallback : value;
    }

    private static final Map<String, Color> DEFAULT_COLORS = new HashMap<>();
    static
    {
        DEFAULT_COLORS.put("oreDeposit", Color.RED); DEFAULT_COLORS.put("phrenRoots", Color.GREEN);
        DEFAULT_COLORS.put("fishingSpot", Color.CYAN); DEFAULT_COLORS.put("grymRoot", Color.YELLOW);
        DEFAULT_COLORS.put("linumTirinum", Color.WHITE);
    }

    private Color outline(Node n) { return color(n.colors + "OutlineColor", DEFAULT_COLORS.get(n.colors)); }

    private Color fill(Node n)
    {
        Color c = DEFAULT_COLORS.get(n.colors);
        return color(n.colors + "FillColor", new Color(c.getRed(), c.getGreen(), c.getBlue(), 50));
    }

    /** ResourceManager.hasAcquired: gathered all that was asked, or untracked and "remove acquired" on. */
    boolean acquired(Resource resource)
    {
        Integer left = remaining.get(resource);
        return left == null ? setting("resourceRemoveAcquired", false) : left <= 0;
    }

    /** MazeOverlay.renderResources and renderUtilities. */
    void collect(List<Marker> tiles, List<ModelTarget> models)
    {
        if (setting("overlayResources", false))
        {
            int hullWidth = number("resourceHullOutlineWidth", 1), tileWidth = number("resourceTileOutlineWidth", 1);
            boolean hideAcquired = setting("resourceTracker", false) && setting("resourceRemoveOutlineOnceAcquired", false);
            for (Map.Entry<GameObject, Node> e : nodes.entrySet())
            {
                GameObject object = e.getKey();
                Node node = e.getValue();
                if (!setting(node.toggle, true) || hideAcquired && acquired(node.resource(corrupted))) { continue; }
                String key = "gauntlet:" + object.getHash();
                if (hullWidth > 0)
                {
                    models.add(ModelTarget.objectOutline(key + ":outline", object, object.getRenderable(), 0, 0, outline(node), hullWidth));
                }
                LocalPoint lp = object.getLocalLocation();
                if (tileWidth > 0 && lp != null)
                {
                    Marker m = new Marker(key + ":tile", lp, object.getPlane(), 1, 1, outline(node), fill(node), tileWidth, null, false);
                    m.layer = Marker.OBJECT;
                    tiles.add(m);
                }
            }
        }
        if (setting("utilitiesOutline", false))
        {
            Color color = color("utilitiesOutlineColor", Color.MAGENTA);
            int width = number("utilitiesOutlineWidth", 1);
            for (GameObject object : utilities)
            {
                models.add(ModelTarget.objectOutline("gauntlet:" + object.getHash() + ":utility", object, object.getRenderable(), 0, 0, color, width));
            }
        }
    }

    /** The resource icons MazeOverlay draws in 2D: where, and which image. */
    Map<LocalPoint, BufferedImage> icons()
    {
        int size = number("resourceIconSize", 14);
        if (!setting("overlayResources", false) || size <= 0 || nodes.isEmpty()) { return Collections.emptyMap(); }
        if (size != iconSize) { icons.clear(); iconSize = size; }
        boolean hideAcquired = setting("resourceTracker", false) && setting("resourceRemoveOutlineOnceAcquired", false);
        Map<LocalPoint, BufferedImage> result = new HashMap<>();
        for (Map.Entry<GameObject, Node> e : nodes.entrySet())
        {
            Node node = e.getValue();
            if (!setting(node.toggle, true) || hideAcquired && acquired(node.resource(corrupted))) { continue; }
            BufferedImage icon = icons.computeIfAbsent(node, n -> {
                BufferedImage original = skillIcons.getSkillImage(n.skill, false);
                return original == null ? null : ImageUtil.resizeImage(original, iconSize, iconSize);
            });
            if (icon != null && e.getKey().getLocalLocation() != null) { result.put(e.getKey().getLocalLocation(), icon); }
        }
        return result;
    }

    /** ResourceManager.init: the amounts to gather, when a run starts. */
    private void startRun()
    {
        remaining.clear();
        int[] regions = client.getTopLevelWorldView().getMapRegions();
        int region = regions == null || regions.length == 0 ? -1 : regions[0];
        inRun = region == REGION_NORMAL || region == REGION_CORRUPTED;
        corrupted = region == REGION_CORRUPTED;
        if (!inRun || !setting("resourceTracker", false)) { return; }
        track(corrupted ? Resource.CORRUPTED_ORE : Resource.ORE, number("resourceOre", 3));
        track(Resource.BARK, number("resourceBark", 3));
        track(Resource.LINUM, number("resourceTirinum", 3));
        track(Resource.GRYM, number("resourceGrym", 2));
        track(Resource.WEAPON_FRAME, number("resourceFrame", 2));
        track(Resource.RAW_PADDLEFISH, number("resourcePaddlefish", 20));
        track(corrupted ? Resource.CORRUPTED_SHARDS : Resource.CRYSTAL_SHARDS, number("resourceShard", 320));
        track(corrupted ? Resource.CORRUPTED_BOWSTRING : Resource.CRYSTALLINE_BOWSTRING, setting("resourceBowstring", false) ? 1 : 0);
        track(corrupted ? Resource.CORRUPTED_SPIKE : Resource.CRYSTAL_SPIKE, setting("resourceSpike", false) ? 1 : 0);
        track(corrupted ? Resource.CORRUPTED_ORB : Resource.CRYSTAL_ORB, setting("resourceOrb", false) ? 1 : 0);
    }

    private void track(Resource resource, int amount) { if (amount > 0) { remaining.put(resource, amount); } }

    /** ResourceManager.parseChatMessage. */
    void chat(String message)
    {
        if (!inRun || !setting("resourceTracker", false) || message.isEmpty()) { return; }
        if (message.charAt(0) == '<')
        {
            // Loot drops start with a colour tag.
            Matcher m = RESOURCE_DROP.matcher(Text.removeTags(message));
            if (!m.matches() || m.group("name") == null) { return; }
            Resource resource = byName(m.group("name"));
            if (resource != null) { gathered(resource, m.group("quantity") != null ? Integer.parseInt(m.group("quantity")) : 1); }
            return;
        }
        Resource shards = corrupted ? Resource.CORRUPTED_SHARDS : Resource.CRYSTAL_SHARDS;
        if (message.startsWith("break down", 4)) { gathered(shards, SHARDS_BREAK_DOWN); return; }
        Matcher monster = SHARD_MONSTER_DROP.matcher(message);
        if (monster.matches()) { gathered(shards, Integer.parseInt(monster.group(1))); return; }
        for (Resource r : Resource.values())
        {
            if (r.pattern == null || !fits(r)) { continue; }
            Matcher m = r.pattern.matcher(message);
            if (m.matches()) { gathered(r, m.groupCount() == 1 ? Integer.parseInt(m.group(1)) : 1); return; }
        }
    }

    /** Resources of the current Gauntlet: corrupted ones only in the Corrupted Gauntlet, and the reverse. */
    private boolean fits(Resource r)
    {
        switch (r)
        {
            case CORRUPTED_BOWSTRING: case CORRUPTED_SPIKE: case CORRUPTED_ORB: case CORRUPTED_SHARDS: case CORRUPTED_ORE: return corrupted;
            case CRYSTALLINE_BOWSTRING: case CRYSTAL_SPIKE: case CRYSTAL_ORB: case CRYSTAL_SHARDS: case ORE: return !corrupted;
            default: return true;
        }
    }

    private Resource byName(String name)
    {
        for (Resource r : Resource.values()) { if (fits(r) && r.name.equals(name)) { return r; } }
        return null;
    }

    private void gathered(Resource resource, int count)
    {
        Integer left = remaining.get(resource);
        if (left == null) { return; }
        left = Math.max(0, left - count);
        // With "remove acquired" the plugin drops the counter once complete, which counts as acquired too.
        if (left == 0 && setting("resourceRemoveAcquired", false)) { remaining.remove(resource); }
        else { remaining.put(resource, left); }
    }

    void add(GameObject object)
    {
        if (object == null) { return; }
        Node node = Node.of(object.getId());
        if (node != null) { nodes.put(object, node); }
        else if (UTILITIES.contains(object.getId())) { utilities.add(object); }
    }

    /** Objects already in the scene when HD Tile Markers starts or the scene is rebuilt. */
    void rebuild(WorldView wv)
    {
        nodes.clear(); utilities.clear();
        if (wv == null) { return; }
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
    @Subscribe public void onGameObjectDespawned(GameObjectDespawned e) { nodes.remove(e.getGameObject()); utilities.remove(e.getGameObject()); }
    @Subscribe public void onWidgetLoaded(WidgetLoaded e) { if (e.getGroupId() == GAUNTLET_TIMER) { startRun(); } }
    @Subscribe public void onChatMessage(ChatMessage e)
    {
        if (e.getType() == ChatMessageType.SPAM || e.getType() == ChatMessageType.GAMEMESSAGE) { chat(e.getMessage()); }
    }
    @Subscribe public void onGameStateChanged(GameStateChanged e)
    {
        switch (e.getGameState())
        {
            case LOADING: nodes.clear(); utilities.clear(); break;
            case LOGIN_SCREEN: case HOPPING: nodes.clear(); utilities.clear(); remaining.clear(); inRun = false; break;
            default: break;
        }
    }
    @Subscribe public void onConfigChanged(net.runelite.client.events.ConfigChanged e)
    {
        // As MazeModule: changing tracking settings restarts the counters.
        if (GROUP.equals(e.getGroup()) && ("resourceTracker".equals(e.getKey()) || "resourceTrackingMode".equals(e.getKey())
            || "resourceRemoveAcquired".equals(e.getKey())) && inRun) { startRun(); }
    }
}
