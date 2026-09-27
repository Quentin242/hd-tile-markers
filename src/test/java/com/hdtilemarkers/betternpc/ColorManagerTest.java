package com.hdtilemarkers.betternpc;

import com.google.inject.Guice;
import java.awt.Color;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ColorManagerTest
{
    @Test public void raveSpeedUnder20MillisecondsStillCycles()
    {
        Client client = mock(Client.class);
        when(client.getGameCycle()).thenReturn(12345);
        ConfigManager configs = mock(ConfigManager.class);
        when(configs.getConfig(BetterNpcHighlightConfig.class)).thenReturn(mock(BetterNpcHighlightConfig.class, CALLS_REAL_METHODS));
        ColorManager colors = Guice.createInjector(binder -> {
            binder.bind(Client.class).toInstance(client);
            binder.bind(ConfigManager.class).toInstance(configs);
        }).getInstance(ColorManager.class);
        // Better NPC Highlight's rave speed setting allows 0 to 19 ms, which gave 0 cycles and divided by zero.
        for (int speed = 0; speed < 20; speed++) { assertNotNull(colors.applyConfigOrRaveColor(Color.RED, true, speed)); }
        assertEquals(Color.RED, colors.applyConfigOrRaveColor(Color.RED, false, 0));
    }
}
