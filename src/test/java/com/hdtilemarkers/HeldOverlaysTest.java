package com.hdtilemarkers;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.*;

public class HeldOverlaysTest
{
    private OverlayManager overlays;
    private PluginManager plugins;
    private Plugin owner;
    private Overlay original;
    private HeldOverlays held;
    private final AtomicBoolean featureEnabled = new AtomicBoolean(true);

    @Before public void setup()
    {
        overlays = mock(OverlayManager.class);
        plugins = mock(PluginManager.class);
        owner = mock(Plugin.class);
        original = mock(Overlay.class);
        when(plugins.getPlugins()).thenReturn(Collections.singletonList(owner));
        when(plugins.isPluginActive(owner)).thenReturn(true);
        doAnswer(call -> {
            Predicate<Overlay> predicate = call.getArgument(0);
            predicate.test(original);
            return false;
        }).when(overlays).anyMatch(any());
        held = new HeldOverlays(overlays, plugins, owner.getClass().getName(), o -> o == original);
        held.update(true, o -> featureEnabled.get());
        verify(overlays).remove(original);
    }

    @Test public void resetDoesNotResurrectDisabledFeature()
    {
        featureEnabled.set(false);
        held.reset();
        verify(overlays, never()).add(original);
    }

    @Test public void resetRestoresEnabledFeatureOnlyOnce()
    {
        held.reset();
        held.reset();
        verify(overlays).add(original);
    }

    @Test public void resetDoesNotResurrectStoppedPlugin()
    {
        when(plugins.isPluginActive(owner)).thenReturn(false);
        held.reset();
        verify(overlays, never()).add(original);
    }

    @Test public void fallbackRespectsCurrentFeatureSetting()
    {
        featureEnabled.set(false);
        held.update(false, o -> featureEnabled.get());
        held.reset();
        verify(overlays, never()).add(original);
    }
}
