package com.hdtilemarkers;

import com.hdtilemarkers.pathmarker.PathMarker;
import com.google.gson.Gson;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.groundmarkers.GroundMarkerConfig;
import net.runelite.client.plugins.npchighlight.NpcIndicatorsConfig;
import net.runelite.client.plugins.objectindicators.ObjectIndicatorsConfig;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class MarkerSourcesTest
{
    // As RuneLite's injected Gson, which reads colors saved as "#AARRGGBB".
    private static final Gson GSON = new com.google.gson.GsonBuilder()
        .registerTypeAdapter(java.awt.Color.class, new net.runelite.http.api.gson.ColorTypeAdapter()).create();
    private Client client;
    private ConfigManager config;
    private MarkerSources sources;
    private WorldView wv;
    private HdTileMarkersConfig settings;

    @Before public void setup()
    {
        client = mock(Client.class); config = mock(ConfigManager.class); wv = mock(WorldView.class);
        when(config.getConfig(GroundMarkerConfig.class)).thenReturn(mock(GroundMarkerConfig.class, CALLS_REAL_METHODS));
        when(config.getConfig(NpcIndicatorsConfig.class)).thenReturn(mock(NpcIndicatorsConfig.class, CALLS_REAL_METHODS));
        when(config.getConfig(ObjectIndicatorsConfig.class)).thenReturn(mock(ObjectIndicatorsConfig.class, CALLS_REAL_METHODS));
        when(client.getTopLevelWorldView()).thenReturn(wv);
        when(client.getWorldView(-1)).thenReturn(wv);
        when(wv.getId()).thenReturn(-1);
        when(wv.getBaseX()).thenReturn(3200); when(wv.getBaseY()).thenReturn(3200);
        when(wv.getSizeX()).thenReturn(104); when(wv.getSizeY()).thenReturn(104);
        when(wv.contains(any(WorldPoint.class))).thenReturn(true);
        when(wv.getMapRegions()).thenReturn(new int[]{12850});
        doReturn(emptySet()).when(wv).npcs(); doReturn(emptySet()).when(wv).worldViews(); doReturn(emptySet()).when(wv).worldEntities();
        Scene scene = mock(Scene.class); when(wv.getScene()).thenReturn(scene);
        when(scene.getBaseX()).thenReturn(3200); when(scene.getBaseY()).thenReturn(3200);
        when(scene.getTiles()).thenReturn(new Tile[4][104][104]);
        Player player = mock(Player.class); when(client.getLocalPlayer()).thenReturn(player);
        when(player.getWorldView()).thenReturn(wv);
        when(player.getLocalLocation()).thenReturn(new LocalPoint(1344, 1344, -1));
        settings = mock(HdTileMarkersConfig.class, CALLS_REAL_METHODS);
        // Source-selection tests isolate saved marks from the user-facing true-tile default.
        // Tests for the current tile explicitly enable it and provide a world location.
        when(settings.highlightCurrentTile()).thenReturn(false);
        sources = new MarkerSources(client, config, GSON, settings, new ObjectMarkerSource(client, config, GSON), new TilePackSource(config, GSON));
    }

    @Test public void automaticallyLoadsSavedGroundTilesAndLabels()
    {
        when(config.getConfiguration("groundMarker", "region_12850")).thenReturn(
            "[{\"regionId\":12850,\"regionX\":10,\"regionY\":10,\"z\":0,\"label\":\"Saved tile\"}]");
        sources.rebuild();
        List<Marker> markers = collect();
        assertTrue(sources.validGround());
        assertEquals(1, markers.size());
        assertEquals("Saved tile", markers.get(0).label);
        assertEquals(1344, markers.get(0).point.getX());
        verify(config, never()).setConfiguration(anyString(), anyString(), any());
    }

    @Test public void savedGroundTilesSurviveInstanceFloorChanges()
    {
        when(config.getConfiguration("groundMarker", "region_12850")).thenReturn(
            "[{\"regionId\":12850,\"regionX\":10,\"regionY\":10,\"z\":0}]");
        assertInstanceFloorChanges("ground:");
    }

    @Test public void tilePackTilesSurviveInstanceFloorChanges()
    {
        String tiles = "[{\"regionId\":12850,\"regionX\":10,\"regionY\":10,\"z\":0}]";
        when(config.getConfiguration(TilePackSource.DATA_GROUP, "packs")).thenReturn("[10000]");
        when(config.getConfiguration(TilePackSource.DATA_GROUP, "customPacks")).thenReturn(
            GSON.toJson(Collections.singletonMap("10000", Collections.singletonMap("packTiles", tiles))));
        sources.tilePacksEnabled = true;
        assertInstanceFloorChanges("tilepack:");
    }

    private void assertInstanceFloorChanges(String prefix)
    {
        // One template tile appears on two instance floors. Load upstairs, then descend without a scene load.
        int[][][] chunks = new int[4][13][13];
        for (int[][] floor : chunks) { for (int[] row : floor) { Arrays.fill(row, -1); } }
        chunks[2][1][1] = chunks[1][1][1] = (401 << 14) | (401 << 3);
        when(wv.isInstance()).thenReturn(true);
        when(wv.getInstanceTemplateChunks()).thenReturn(chunks);
        when(wv.getPlane()).thenReturn(2);
        sources.rebuild();
        for (int plane : new int[]{2, 1, 0, 2, 1})
        {
            when(wv.getPlane()).thenReturn(plane);
            List<Marker> markers = collect();
            assertEquals("visible tiles on floor " + plane, plane == 0 ? 0 : 1, markers.size());
            if (plane == 0) { continue; }
            Marker marker = markers.get(0);
            assertTrue(marker.key.startsWith(prefix));
            assertEquals(plane, marker.plane);
            assertEquals(1344, marker.point.getX());
            assertEquals(1344, marker.point.getY());
        }
    }

    @Test public void malformedSavedDataPreventsReplacingOriginalOverlay()
    {
        when(config.getConfiguration("groundMarker", "region_12850")).thenReturn("broken");
        sources.rebuild();
        assertFalse(sources.validGround());
    }

    @Test public void profileReloadRemovesOldMarkers()
    {
        when(config.getConfiguration("groundMarker", "region_12850")).thenReturn(
            "[{\"regionId\":12850,\"regionX\":10,\"regionY\":10,\"z\":0}]");
        sources.rebuild(); assertEquals(1, collect().size());
        when(config.getConfiguration("groundMarker", "region_12850")).thenReturn(null);
        sources.rebuild(); assertTrue(collect().isEmpty());
    }

    @Test public void npcsComeFromNpcIndicatorsListWithWildcards()
    {
        when(config.getConfiguration(NpcIndicatorsConfig.GROUP, "npcToHighlight")).thenReturn("gob*,Man");
        sources.rebuild();
        NPC goblin = mock(NPC.class); when(goblin.getName()).thenReturn("Goblin");
        NPC man = mock(NPC.class); when(man.getName()).thenReturn("Man");
        NPC cow = mock(NPC.class); when(cow.getName()).thenReturn("Cow");
        assertTrue(sources.isHighlighted(goblin));
        assertTrue(sources.isHighlighted(man));
        assertFalse(sources.isHighlighted(cow));
        verify(config, never()).setConfiguration(anyString(), anyString(), any());
    }

    @Test public void npcFootprintUsesServerTileAndDisappearsOnDespawn()
    {
        when(config.getConfiguration(NpcIndicatorsConfig.GROUP, "npcToHighlight")).thenReturn("giant");
        sources.rebuild();
        NPC npc = mock(NPC.class);
        when(npc.getName()).thenReturn("Giant"); when(npc.getWorldView()).thenReturn(wv);
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3210, 3210, 0));
        NPCComposition composition = mock(NPCComposition.class);
        when(composition.getSize()).thenReturn(3); when(npc.getTransformedComposition()).thenReturn(composition);
        NpcIndicatorsConfig npcSettings = config.getConfig(NpcIndicatorsConfig.class);
        when(npcSettings.highlightTrueTile()).thenReturn(true);
        sources.add(npc);
        Marker marker = collect().get(0);
        assertEquals(3, marker.width); assertEquals(3, marker.height);
        assertEquals(1472, marker.point.getX()); assertEquals(1472, marker.point.getY());
        sources.remove(npc); assertTrue(collect().isEmpty());
    }

    @Test public void taggingMatchesTheSceneNpcsAgainWithoutRebuilding()
    {
        when(config.getConfiguration(NpcIndicatorsConfig.GROUP, "npcToHighlight")).thenReturn("giant");
        sources.rebuild();
        NPC npc = mock(NPC.class);
        when(npc.getName()).thenReturn("Cow"); when(npc.getWorldView()).thenReturn(wv);
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3210, 3210, 0));
        NPCComposition composition = mock(NPCComposition.class);
        when(composition.getSize()).thenReturn(1); when(npc.getTransformedComposition()).thenReturn(composition);
        when(config.getConfig(NpcIndicatorsConfig.class).highlightTrueTile()).thenReturn(true);
        IndexedObjectSet<NPC> npcs = mock(IndexedObjectSet.class);
        when(npcs.iterator()).thenAnswer(i -> Collections.singletonList(npc).iterator());
        doReturn(npcs).when(wv).npcs();
        sources.add(npc);
        assertTrue(collect().isEmpty());
        // Tag-All on the cow: its name joins the list and only the NPCs are matched again.
        when(config.getConfiguration(NpcIndicatorsConfig.GROUP, "npcToHighlight")).thenReturn("giant,Cow");
        sources.refreshNpcs();
        assertEquals(1, collect().size());
        verify(wv, times(1)).getScene();
        when(config.getConfiguration(NpcIndicatorsConfig.GROUP, "npcToHighlight")).thenReturn("giant");
        sources.refreshNpcs();
        assertTrue(collect().isEmpty());
    }

    @Test public void objectMarkersPointsKeepTheirOwnStyleAndColor()
    {
        when(config.getConfiguration(ObjectMarkerSource.GROUP, "region_12850")).thenReturn(
            "[{\"id\":123,\"name\":\"Tree\",\"regionId\":12850,\"regionX\":10,\"regionY\":10,\"z\":0,"
            + "\"color\":\"#FFFF0000\",\"tile\":true}]");
        GameObject object = object(123);
        when(object.getSceneMinLocation()).thenReturn(new Point(10, 10));
        when(object.getSceneMaxLocation()).thenReturn(new Point(11, 12));
        sources.rebuild();
        sources.add(object);
        Marker marker = collect().get(0);
        assertEquals(java.awt.Color.RED, marker.color);
        // Tile fill defaults to the border color at a/12, the tile stroke is capped at 2.
        assertEquals(255 / 12, marker.fill.getAlpha());
        assertEquals(2, marker.width); assertEquals(3, marker.height);
        assertEquals(1408, marker.point.getX()); assertEquals(1472, marker.point.getY());
        // Only the tile style is set, so no hull.
        assertTrue(sources.modelTargets().isEmpty());
        sources.remove(object); assertTrue(collect().isEmpty());
        verify(config, never()).setConfiguration(anyString(), anyString(), any());
    }

    @Test public void objectMarkersWithoutStyleUseTheConfiguredHull()
    {
        when(config.getConfiguration(ObjectMarkerSource.GROUP, "region_12850")).thenReturn(
            "[{\"id\":123,\"name\":\"Tree\",\"regionId\":12850,\"regionX\":10,\"regionY\":10,\"z\":0}]");
        GameObject object = object(123);
        sources.rebuild();
        sources.add(object);
        List<ModelTarget> targets = sources.modelTargets();
        assertEquals(1, targets.size());
        assertEquals(java.awt.Color.YELLOW, targets.get(0).color);
        assertEquals(50, targets.get(0).fill.getAlpha());
        assertTrue(collect().isEmpty());
    }

    @Test public void objectMarkersIgnoreOtherIdsAndDisabledPlugin()
    {
        when(config.getConfiguration(ObjectMarkerSource.GROUP, "region_12850")).thenReturn(
            "[{\"id\":999,\"name\":\"Tree\",\"regionId\":12850,\"regionX\":10,\"regionY\":10,\"z\":0}]");
        sources.rebuild();
        sources.add(object(123));
        assertTrue(sources.modelTargets().isEmpty());
        when(config.getConfiguration(ObjectMarkerSource.GROUP, "region_12850")).thenReturn(
            "[{\"id\":123,\"name\":\"Tree\",\"regionId\":12850,\"regionX\":10,\"regionY\":10,\"z\":0}]");
        sources.rebuild();
        sources.add(object(123));
        sources.objectsEnabled = false;
        assertTrue(sources.modelTargets().isEmpty());
    }

    @Test public void legacyObjectMarkersFormatIsRead()
    {
        // Older saves: numbers as decimals, the color as {"value": argb, "falpha": 0.0}.
        when(config.getConfiguration(ObjectMarkerSource.GROUP, "region_12850")).thenReturn(
            "[{\"id\":123.0,\"name\":\"Tree\",\"regionId\":12850.0,\"regionX\":10.0,\"regionY\":10.0,\"z\":0.0,"
            + "\"color\":{\"value\":-65536.0,\"falpha\":0.0}}]");
        GameObject object = object(123);
        sources.rebuild();
        sources.add(object);
        assertTrue(sources.validObjects());
        List<ModelTarget> targets = sources.modelTargets();
        assertEquals(1, targets.size());
        assertEquals(java.awt.Color.RED, targets.get(0).color);
    }

    @Test public void unacceptedWalkClickDoesNotShowDestinationAndPredictionExpires()
    {
        sources.walkedTo(new WorldPoint(3240, 3240, 0));
        assertTrue(collect().isEmpty());
        when(client.getTickCount()).thenReturn(2);
        assertNull(sources.predictedTarget(client.getLocalPlayer(), System.currentTimeMillis()));
    }

    @Test public void clickingYourOwnTileShowsNoDestination()
    {
        when(settings.highlightDestinationTile()).thenReturn(true);
        when(settings.destinationTileFadeout()).thenReturn(true);
        Player player = client.getLocalPlayer();
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3210, 3210, 0));
        sources.walkedTo(new WorldPoint(3210, 3210, 0));
        assertNull(sources.predictedTarget(player, System.currentTimeMillis()));
        when(client.getLocalDestinationLocation()).thenReturn(new LocalPoint(10 * 128 + 64, 10 * 128 + 64, -1));
        assertTrue(collect().stream().noneMatch(m -> m.key.equals("destination")));
        when(client.getLocalDestinationLocation()).thenReturn(null);
        assertTrue(collect().stream().noneMatch(m -> m.key.equals("destination")));
    }

    @Test public void arrivingKeepsTheDestinationUntilItFades()
    {
        when(settings.highlightDestinationTile()).thenReturn(true);
        when(settings.destinationTileFadeout()).thenReturn(true);
        Player player = client.getLocalPlayer();
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3205, 3210, 0));
        sources.walkedTo(new WorldPoint(3210, 3210, 0));
        when(client.getLocalDestinationLocation()).thenReturn(new LocalPoint(10 * 128 + 64, 10 * 128 + 64, -1));
        assertTrue(collect().stream().anyMatch(m -> m.key.equals("destination")));
        // Server tile arrived, client still walking there: no blink.
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3210, 3210, 0));
        assertTrue(collect().stream().anyMatch(m -> m.key.equals("destination")));
        when(client.getLocalDestinationLocation()).thenReturn(null);
        assertTrue(collect().stream().anyMatch(m -> m.key.equals("destination")));
    }

    @Test public void stoppedRouteDoesNotResurrectPredictedDestination()
    {
        when(settings.destinationTileFadeout()).thenReturn(false);
        sources.walkedTo(new WorldPoint(3240, 3240, 0));
        when(client.getLocalDestinationLocation()).thenReturn(new LocalPoint(20 * 128 + 64, 20 * 128 + 64, -1));
        Marker marker = collect().get(0);
        assertEquals(20 * 128 + 64, marker.point.getX());
        when(client.getLocalDestinationLocation()).thenReturn(null);
        assertTrue(collect().isEmpty());
        assertNull(sources.predictedTarget(client.getLocalPlayer(), System.currentTimeMillis()));
    }

    @Test public void clearingSceneDropsUnconfirmedPrediction()
    {
        sources.walkedTo(new WorldPoint(3240, 3240, 0));
        sources.clear();
        assertNull(sources.predictedTarget(client.getLocalPlayer(), System.currentTimeMillis()));
    }

    @Test public void drawDistanceLimitsSavedMarkersButNotOwnTiles()
    {
        when(settings.distance()).thenReturn(8);
        when(client.getLocalDestinationLocation()).thenReturn(new LocalPoint(1344 + 20 * 128, 1344, -1));
        when(config.getConfiguration("groundMarker", "region_12850")).thenReturn(
            "[{\"regionId\":12850,\"regionX\":30,\"regionY\":10,\"z\":0}]");
        sources.rebuild();
        List<Marker> markers = collect();
        assertEquals(1, markers.size());
        assertEquals("destination", markers.get(0).key);
    }

    @Test public void currentTileFadesOutWhenStandingStill() throws Exception
    {
        when(settings.highlightCurrentTile()).thenReturn(true);
        when(settings.currentTileFadeout()).thenReturn(true);
        when(settings.currentTileFadeoutDelay()).thenReturn(0);
        when(settings.currentTileFadeoutTime()).thenReturn(60);
        when(client.getLocalPlayer().getWorldLocation()).thenReturn(new WorldPoint(3210, 3210, 0));
        Marker first = collect().get(0);
        assertEquals("current", first.key);
        Thread.sleep(90);
        assertTrue(collect().isEmpty());
        // Moving shows it again at full strength.
        when(client.getLocalPlayer().getWorldLocation()).thenReturn(new WorldPoint(3211, 3210, 0));
        assertEquals(java.awt.Color.CYAN.getAlpha(), collect().get(0).color.getAlpha());
    }

    @Test public void fadeoutDelayKeepsTheTileFullyVisibleFirst() throws Exception
    {
        when(settings.highlightCurrentTile()).thenReturn(true);
        when(settings.currentTileFadeout()).thenReturn(true);
        when(settings.currentTileFadeoutDelay()).thenReturn(150);
        when(settings.currentTileFadeoutTime()).thenReturn(60);
        when(client.getLocalPlayer().getWorldLocation()).thenReturn(new WorldPoint(3210, 3210, 0));
        collect();
        Thread.sleep(80);
        // Still inside the delay: full strength.
        assertEquals(java.awt.Color.CYAN.getAlpha(), collect().get(0).color.getAlpha());
        Thread.sleep(160);
        assertTrue(collect().isEmpty());
    }

    @Test public void outOfCombatOnlyHoldsTheFadeDuringCombat() throws Exception
    {
        when(settings.highlightCurrentTile()).thenReturn(true);
        when(settings.currentTileFadeout()).thenReturn(true);
        when(settings.currentTileFadeoutOutOfCombat()).thenReturn(true);
        when(settings.currentTileFadeoutDelay()).thenReturn(0);
        when(settings.currentTileFadeoutTime()).thenReturn(60);
        Player player = client.getLocalPlayer();
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3210, 3210, 0));
        NPC goblin = mock(NPC.class);
        NPCComposition composition = mock(NPCComposition.class);
        when(composition.getActions()).thenReturn(new String[]{null, "Attack", null, null, null});
        when(goblin.getTransformedComposition()).thenReturn(composition);
        when(player.getInteracting()).thenReturn(goblin);
        collect();
        Thread.sleep(90);
        // Attacking: stays at full strength.
        assertEquals(java.awt.Color.CYAN.getAlpha(), collect().get(0).color.getAlpha());
        // Combat over: the fade starts then.
        when(player.getInteracting()).thenReturn(null);
        assertFalse(collect().isEmpty());
        Thread.sleep(90);
        assertTrue(collect().isEmpty());
    }

    @Test public void recentHitsplatOnYouCountsAsCombat()
    {
        Player player = client.getLocalPlayer();
        long now = System.currentTimeMillis();
        assertFalse(sources.inCombat(player, now));
        sources.hitsplat(player, now);
        assertTrue(sources.inCombat(player, now + MarkerSources.COMBAT_MS - 1));
        assertFalse(sources.inCombat(player, now + MarkerSources.COMBAT_MS));
        // Someone else's hitsplat does not.
        sources.hitsplat(mock(NPC.class), now + 2 * MarkerSources.COMBAT_MS);
        assertFalse(sources.inCombat(player, now + 2 * MarkerSources.COMBAT_MS));
    }

    @Test public void npcHighlightsLieUnderYourOwnMarksAndTileIndicatorsOnTop()
    {
        int[] npcTiles = {Marker.NPC_TILE, Marker.NPC_TILE + 3, Marker.RESPAWN};
        int[] own = {Marker.PATH_HOVER, Marker.PATH_ACTIVE, Marker.GROUND, Marker.OBJECT, Marker.SAILING};
        for (int n : npcTiles) { for (int o : own) { assertTrue(n < o); } }
        for (int o : own) { assertTrue(o < Marker.HOVER); }
        assertTrue(Marker.HOVER < SceneShapeRenderer.HULL_LAYER);
        assertTrue(SceneShapeRenderer.HULL_LAYER < Marker.DESTINATION);
        assertTrue(Marker.DESTINATION < Marker.CURRENT);
    }

    private GameObject object(int id)
    {
        GameObject object = mock(GameObject.class);
        when(object.getId()).thenReturn(id); when(object.getWorldView()).thenReturn(wv);
        when(object.getLocalLocation()).thenReturn(new LocalPoint(1344, 1344, -1));
        when(object.getX()).thenReturn(1344); when(object.getY()).thenReturn(1344);
        when(object.getRenderable()).thenReturn(mock(Model.class));
        ObjectComposition composition = mock(ObjectComposition.class);
        when(composition.getName()).thenReturn("Tree");
        when(client.getObjectDefinition(id)).thenReturn(composition);
        return object;
    }

    @Test public void repeatedInstanceChunksUseDestinationPlaneAndDistinctMarkers()
    {
        when(config.getConfiguration("groundMarker", "region_12850")).thenReturn(
            "[{\"regionId\":12850,\"regionX\":10,\"regionY\":10,\"z\":0}]");
        int[][][] chunks = new int[4][13][13];
        for (int[][] plane : chunks) { for (int[] row : plane) { Arrays.fill(row, -1); } }
        chunks[1][1][1] = (401 << 14) | (401 << 3);
        chunks[1][2][1] = (401 << 14) | (401 << 3);
        when(wv.isInstance()).thenReturn(true); when(wv.getPlane()).thenReturn(1);
        when(wv.getInstanceTemplateChunks()).thenReturn(chunks);
        sources.rebuild();
        List<Marker> markers = collect();
        assertEquals(2, markers.size());
        assertEquals(1, markers.get(0).plane); assertEquals(1, markers.get(1).plane);
        assertNotEquals(markers.get(0).key, markers.get(1).key);
        assertEquals(1344, markers.get(0).point.getX());
        assertEquals(2368, markers.get(1).point.getX());
    }

    @Test public void groundMarkersUseTheirOwnBorderWidthAndFill()
    {
        GroundMarkerConfig ground = mock(GroundMarkerConfig.class, CALLS_REAL_METHODS);
        when(ground.borderWidth()).thenReturn(3.5);
        when(ground.fillOpacity()).thenReturn(80);
        when(config.getConfig(GroundMarkerConfig.class)).thenReturn(ground);
        sources = new MarkerSources(client, config, GSON, settings, new ObjectMarkerSource(client, config, GSON), new TilePackSource(config, GSON));
        when(config.getConfiguration("groundMarker", "region_12850")).thenReturn(
            "[{\"regionId\":12850,\"regionX\":10,\"regionY\":10,\"z\":0}]");
        sources.rebuild();
        Marker m = collect().get(0);
        assertEquals(3.5f, m.borderWidth, 0);
        assertEquals(80, m.fill.getAlpha());
        assertEquals(0, m.fill.getRed());
    }

    @Test public void tileIndicatorOptionsApplyPerTile()
    {
        when(client.getLocalDestinationLocation()).thenReturn(new LocalPoint(1600, 1600, -1));
        when(settings.destinationTileBorderWidth()).thenReturn(4.0);
        when(settings.highlightDestinationColor()).thenReturn(java.awt.Color.ORANGE);
        Marker m = collect().get(0);
        assertEquals("destination", m.key);
        assertEquals(java.awt.Color.ORANGE, m.color);
        assertEquals(4f, m.borderWidth, 0);
        when(settings.highlightDestinationTile()).thenReturn(false);
        assertTrue(collect().isEmpty());
    }

    @Test public void pathTilesKeepPrimaryAndSecondaryStyles()
    {
        WorldPoint first = new WorldPoint(3210, 3210, 0), second = new WorldPoint(3211, 3210, 0);
        List<PathMarker.SceneTile> tiles = Arrays.asList(
            new PathMarker.SceneTile(first, java.awt.Color.RED, new java.awt.Color(255, 0, 0, 50), false, true),
            new PathMarker.SceneTile(second, java.awt.Color.YELLOW, new java.awt.Color(255, 255, 0, 50), true, true));
        List<Marker> markers = sources.collect(tiles);
        assertEquals(2, markers.size());
        assertEquals(java.awt.Color.RED, markers.get(0).color);
        assertFalse(markers.get(0).dot);
        assertEquals(java.awt.Color.YELLOW, markers.get(1).color);
        assertTrue(markers.get(1).dot);
        assertEquals(1344, markers.get(0).point.getX());
    }

    @Test public void deadNpcsAreIgnoredForHullsByDefault()
    {
        when(config.getConfiguration(NpcIndicatorsConfig.GROUP, "npcToHighlight")).thenReturn("giant");
        sources.rebuild();
        NPC npc = mock(NPC.class);
        when(npc.getName()).thenReturn("Giant"); when(npc.getWorldView()).thenReturn(wv);
        when(npc.getLocalLocation()).thenReturn(new LocalPoint(1344, 1344, -1));
        sources.add(npc);
        assertEquals(1, sources.modelTargets().size());
        when(npc.isDead()).thenReturn(true);
        assertTrue(sources.modelTargets().isEmpty());
        // NPC Indicators' own "Ignore dead NPCs" decides.
        NpcIndicatorsConfig npcConfig = mock(NpcIndicatorsConfig.class, CALLS_REAL_METHODS);
        when(npcConfig.ignoreDeadNpcs()).thenReturn(false);
        when(config.getConfig(NpcIndicatorsConfig.class)).thenReturn(npcConfig);
        sources = new MarkerSources(client, config, GSON, settings, new ObjectMarkerSource(client, config, GSON), new TilePackSource(config, GSON));
        sources.rebuild();
        sources.add(npc);
        assertEquals(1, sources.modelTargets().size());
    }

    private List<Marker> collect() { return sources.collect(Collections.emptyList()); }

    private static IndexedObjectSet<?> emptySet()
    {
        IndexedObjectSet<?> set = mock(IndexedObjectSet.class);
        when(set.iterator()).thenAnswer(i -> Collections.emptyIterator());
        return set;
    }
}
