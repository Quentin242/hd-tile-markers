package com.hdtilemarkers;

import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class XrayAnchorTest
{
    @Test public void picksTheZoneTheClientDrawsLast()
    {
        // Ground seen at (5000, 3000), in zone (4, 2); zone (5, 2) is within HD's view margin of it and its
        // centre is nearer the camera, so the client draws it later: the marks go there, near the camera.
        int[] p = HdTileMarkersPlugin.nearestDrawnZonePoint(6000, 1500, Collections.singletonList(new float[]{5000, 3000}), 104, 104);
        assertArrayEquals(new int[]{6000, 2048 + 64}, p);
    }

    @Test public void staysInsideTheZoneAndTheScene()
    {
        int[] p = HdTileMarkersPlugin.nearestDrawnZonePoint(-3000, -3000, Arrays.asList(new float[]{100, 100}, new float[]{900, 200}), 104, 104);
        assertArrayEquals(new int[]{64, 64}, p);
    }

    @Test public void noGroundNoAnchor()
    {
        assertNull(HdTileMarkersPlugin.nearestDrawnZonePoint(0, 0, Collections.emptyList(), 104, 104));
    }
}
