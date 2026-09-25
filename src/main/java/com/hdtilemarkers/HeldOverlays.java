package com.hdtilemarkers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Another plugin's scene overlays, held back while HD Tile Markers draws their marks. Only
 * overlays its plugin has added are held, including ones added later (plugins that
 * add and remove overlays per feature), and only those it still wants come back.
 */
final class HeldOverlays
{
    private final OverlayManager overlays;
    private final PluginManager plugins;
    private final String pluginClass;
    private final Predicate<Overlay> match;
    private final List<Overlay> held = new ArrayList<>();
    private boolean drawing;
    private Predicate<Overlay> stillShown = o -> true;

    HeldOverlays(OverlayManager overlays, PluginManager plugins, String pluginClass, Predicate<Overlay> match)
    {
        this.overlays = overlays; this.plugins = plugins; this.pluginClass = pluginClass; this.match = match;
    }

    /** Bumped when plugins are loaded, unloaded, started or stopped: the plugin found by class name is looked up again. */
    private static volatile int generation;
    private int foundIn = -1;
    private Plugin plugin;

    static void pluginsChanged() { generation++; }

    /**
     * Whether that plugin is running; matched by class name, as HD Tile Markers has no compile-time dependency on it.
     * The plugin is looked up once per change of the plugin list, not every tick for every held plugin.
     */
    boolean running()
    {
        int now = generation;
        if (foundIn != now)
        {
            plugin = null;
            for (Plugin p : plugins.getPlugins())
            {
                if (p.getClass().getName().equals(pluginClass)) { plugin = p; break; }
            }
            foundIn = now;
        }
        return plugin != null && plugins.isPluginActive(plugin);
    }

    /** Whether HD Tile Markers draws that plugin's marks this tick. */
    boolean drawing() { return drawing; }

    /**
     * Holds its overlays while wanted and it runs, else gives them back; wanted
     * decides per overlay whether it would still be shown (its own feature toggles).
     */
    void update(boolean wanted, Predicate<Overlay> stillShown)
    {
        this.stillShown = stillShown;
        boolean running = running();
        drawing = wanted && running;
        if (drawing)
        {
            List<Overlay> found = new ArrayList<>();
            overlays.anyMatch(o -> { if (match.test(o)) { found.add(o); } return false; });
            for (Overlay o : found) { overlays.remove(o); if (!held.contains(o)) { held.add(o); } }
        }
        else { restore(running, stillShown); }
    }

    /** The overlays held back now. */
    List<Overlay> held() { return java.util.Collections.unmodifiableList(held); }

    void restore(boolean running, Predicate<Overlay> stillShown)
    {
        drawing = false;
        // A stopped plugin removed its overlays itself; only a running one gets them back.
        if (running) { for (Overlay o : held) { if (stillShown.test(o)) { overlays.add(o); } } }
        held.clear();
    }

    void reset() { restore(running(), stillShown); }
}
