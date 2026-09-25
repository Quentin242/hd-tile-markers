/*
 * Pack loading adapted from Tile Packs by TrevorMDev (TilePackManager.loadPacks, loadCustomPacks,
 * loadEnabledPacks and PointManager), https://github.com/TrevorMDev/tile-packs,
 * commit d02a2e2f3197eb71df8b94e1282749f1a2844e46. Copyright (c) 2022, TrevorMDev.
 * BSD 2-Clause License; see META-INF/LICENSE-tile-packs and THIRD_PARTY_NOTICES.md.
 * Changes for HD Tile Markers: read-only, returns markers for HD Tile Markers' renderer.
 * The bundled tilePacks.jsonc is the pack list of that commit.
 */
package com.hdtilemarkers;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.awt.Color;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;

/** The tiles of the packs enabled in the Tile Packs plugin, with its display settings. */
@Singleton
final class TilePackSource
{
    static final String DATA_GROUP = "tilePacks", SETTINGS_GROUP = "tilepacks";
    private final ConfigManager configs;
    private final Gson gson;
    private Map<Integer, TilePack> bundled;
    private Map<Integer, List<Point>> activePointsByRegion;

    @Inject
    TilePackSource(ConfigManager configs, Gson gson) { this.configs = configs; this.gson = gson; }

    static final class TilePack { Integer id; String packName; String link; String packTiles; }

    static final class Point { int regionId, regionX, regionY, z; Color color; String label; }

    void clear() { activePointsByRegion = null; }

    /** Markers for the loaded regions of a world view. */
    List<Marker> markers(WorldView wv)
    {
        if (wv == null || wv.getMapRegions() == null) { return Collections.emptyList(); }
        boolean override = Boolean.parseBoolean(Strings.nullToEmpty(configs.getConfiguration(SETTINGS_GROUP, "overrideColorActive")));
        Color overrideColor = color("overrideColor", Color.YELLOW);
        boolean labels = !"false".equals(configs.getConfiguration(SETTINGS_GROUP, "showLabels"));
        double width = number("borderWidth", 2);
        Color fill = new Color(0, 0, 0, (int) Math.max(0, Math.min(255, number("fillOpacity", 50))));
        List<Marker> result = new ArrayList<>();
        for (int region : wv.getMapRegions())
        {
            for (Point p : activePoints(region))
            {
                WorldPoint world = WorldPoint.fromRegion(p.regionId, p.regionX, p.regionY, p.z);
                for (WorldPoint instance : WorldPoint.toLocalInstance(wv, world))
                {
                    LocalPoint local = LocalPoint.fromWorld(wv, instance);
                    if (local == null) { continue; }
                    Color color = override || p.color == null ? overrideColor : p.color;
                    Marker m = new Marker("tilepack:" + wv.getId() + ":" + instance, local, instance.getPlane(), 1, 1, color, fill, width,
                        labels ? p.label : null, false);
                    m.layer = Marker.GROUND;
                    result.add(m);
                }
            }
        }
        return result;
    }

    /** As PointManager.getActivePoints: all enabled packs indexed by region once. */
    private List<Point> activePoints(int region)
    {
        if (activePointsByRegion == null)
        {
            activePointsByRegion = new HashMap<>();
            Map<Integer, TilePack> packs = new HashMap<>(bundled());
            packs.putAll(customPacks());
            for (Integer id : enabledPacks())
            {
                TilePack pack = packs.get(id);
                if (pack == null || pack.packTiles == null) { continue; }
                List<Point> points = gson.fromJson(pack.packTiles, new TypeToken<List<Point>>() { }.getType());
                if (points == null) { continue; }
                for (Point p : points) { activePointsByRegion.computeIfAbsent(p.regionId, k -> new ArrayList<>()).add(p); }
            }
        }
        return activePointsByRegion.getOrDefault(region, Collections.emptyList());
    }

    private Map<Integer, TilePack> bundled()
    {
        if (bundled == null)
        {
            bundled = Collections.emptyMap();
            try (InputStream in = TilePackSource.class.getResourceAsStream("tilepacks/tilePacks.jsonc"))
            {
                // Gson reads the file leniently, which allows its // comments.
                Map<Integer, TilePack> parsed = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<Integer, TilePack>>() { }.getType());
                if (parsed != null) { bundled = parsed; }
            }
            catch (Exception ex) { bundled = Collections.emptyMap(); }
        }
        return bundled;
    }

    /**
     * Packs players made themselves. Tile Packs 2 saves each as its own key, pack_&lt;id&gt; (TilePackManager.loadSavedPacks);
     * bundled packs have an entry there too, holding only whether they show in its panel, without tiles.
     * Tile Packs 1 kept them all under customPacks, which is still read for players who did not update.
     */
    private Map<Integer, TilePack> customPacks()
    {
        Map<Integer, TilePack> packs = new HashMap<>();
        String json = configs.getConfiguration(DATA_GROUP, "customPacks");
        if (!Strings.isNullOrEmpty(json))
        {
            Map<Integer, TilePack> legacy = gson.fromJson(json, new TypeToken<Map<Integer, TilePack>>() { }.getType());
            if (legacy != null) { packs.putAll(legacy); }
        }
        String prefix = ConfigManager.getWholeKey(DATA_GROUP, null, "pack_");
        List<String> keys = configs.getConfigurationKeys(prefix);
        for (String key : keys == null ? Collections.<String>emptyList() : keys)
        {
            try
            {
                Integer id = Integer.valueOf(key.substring(prefix.length()));
                TilePack pack = gson.fromJson(configs.getConfiguration(DATA_GROUP, "pack_" + id), TilePack.class);
                if (pack != null && pack.packTiles != null) { packs.put(id, pack); }
            }
            catch (RuntimeException ex) { /* Tile Packs skips an unreadable pack too. */ }
        }
        return packs;
    }

    private List<Integer> enabledPacks()
    {
        String json = configs.getConfiguration(DATA_GROUP, "packs");
        if (Strings.isNullOrEmpty(json)) { return Collections.emptyList(); }
        List<Integer> packs = gson.fromJson(json, new TypeToken<List<Integer>>() { }.getType());
        return packs == null ? Collections.emptyList() : packs;
    }

    private Color color(String key, Color fallback)
    {
        Color c = configs.getConfiguration(SETTINGS_GROUP, key, Color.class);
        return c == null ? fallback : c;
    }

    private double number(String key, double fallback)
    {
        try
        {
            String value = configs.getConfiguration(SETTINGS_GROUP, key);
            return value == null ? fallback : Double.parseDouble(value);
        }
        catch (NumberFormatException ex) { return fallback; }
    }
}
