package com.hdtilemarkers;

import java.awt.Color;
import org.junit.Test;
import static org.junit.Assert.*;

public class FlatModelTest
{
    private static int[] layers(Color color, int alpha, boolean cap)
    {
        int[] out = new int[3];
        FlatModel.layers(color, alpha, cap, out);
        return out;
    }

    @Test public void capMatches117Hd()
    {
        assertEquals(127, FlatModel.maxLightness(0));
        assertEquals(55, FlatModel.maxLightness(7));
    }

    @Test public void colourWithinTheCapIsOneLayer()
    {
        int[] layers = layers(new Color(255, 0, 0), 127, true);
        assertEquals(FlatModel.hsl(new Color(255, 0, 0)), layers[0]);
        assertEquals(127, layers[1]);
        assertEquals(0, layers[2]);
    }

    @Test public void withoutTheCapNothingChanges()
    {
        Color pink = new Color(255, 207, 207);
        assertArrayEquals(new int[]{FlatModel.hsl(pink), 127, 0}, layers(pink, 127, false));
    }

    /** Pink over any ground looks the same as the capped colour with white over it. */
    @Test public void lightColourBlendsLikeTheOriginal()
    {
        Color pink = new Color(255, 207, 207);
        int alpha = 127;
        int[] layers = layers(pink, alpha, true);
        assertEquals(55, layers[0] & 127);
        assertTrue(layers[2] > 0);
        float[] p = FlatModel.rgb(layers[0]), w = FlatModel.rgb(FlatModel.WHITE);
        float a = alpha / 255f, a1 = layers[1] / 255f, a2 = layers[2] / 255f;
        float[] c = {1f, 207 / 255f, 207 / 255f};
        for (float ground : new float[]{0f, 0.3f, 0.8f})
        {
            for (int i = 0; i < 3; i++)
            {
                float wanted = a * c[i] + (1 - a) * ground;
                float drawn = a2 * w[i] + (1 - a2) * (a1 * p[i] + (1 - a1) * ground);
                assertEquals("channel " + i + " over " + ground, wanted, drawn, 0.06f);
            }
        }
    }
}
