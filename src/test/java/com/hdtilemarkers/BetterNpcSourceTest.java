package com.hdtilemarkers;

import com.hdtilemarkers.betternpc.*;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class BetterNpcSourceTest
{
    private BetterNpcView view;
    private BetterNpcHighlightConfig config;
    private NPCInfo info;
    private NPC npc;
    private final List<String> styles = new ArrayList<>();
    private BetterNpcSource source;

    @Before public void setup()
    {
        Client client = mock(Client.class);
        view = mock(BetterNpcView.class);
        config = mock(BetterNpcHighlightConfig.class, CALLS_REAL_METHODS);
        ColorManager colors = mock(ColorManager.class);
        when(view.config()).thenReturn(config);
        when(view.colors()).thenReturn(colors);
        // No task and no rave: the custom color is used as is.
        when(colors.resolveColor(anyBoolean(), any(), any(), anyBoolean(), anyInt())).thenAnswer(i -> i.getArgument(2));
        WorldView wv = mock(WorldView.class);
        when(wv.getId()).thenReturn(-1);
        when(wv.getBaseX()).thenReturn(3200); when(wv.getBaseY()).thenReturn(3200);
        when(wv.getSizeX()).thenReturn(104); when(wv.getSizeY()).thenReturn(104);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        npc = mock(NPC.class);
        when(npc.getIndex()).thenReturn(7);
        when(npc.getWorldView()).thenReturn(wv);
        NPCComposition composition = mock(NPCComposition.class);
        when(composition.getSize()).thenReturn(3);
        when(npc.getTransformedComposition()).thenReturn(composition);
        when(npc.getLocalLocation()).thenReturn(new LocalPoint(1400, 1400, -1));
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3210, 3210, 0));
        HighlightColor red = new HighlightColor(true, new Color(255, 0, 0, 200), new Color(255, 0, 0, 30));
        info = new NPCInfo(npc, red, red, red, red, red, red, red, red, false, false);
        doAnswer(i -> {
            BetterNpcView.Visitor v = i.getArgument(0);
            for (String style : styles) { v.accept(info, style); }
            return null;
        }).when(view).visit(any());
        source = new BetterNpcSource(client, view, new SceneShapeRenderer(client, new CarrierModels(client), new RenderTrace()));
    }

    @Test public void tilesFollowTheOriginalPositionsAndColors()
    {
        styles.add("tile"); styles.add("trueTile"); styles.add("swTile"); styles.add("swTrueTile");
        List<Marker> tiles = new ArrayList<>();
        source.collect(tiles, new ArrayList<>());
        assertEquals(4, tiles.size());
        // tile: centered on the rendered location, full size.
        assertEquals(1400, tiles.get(0).point.getX()); assertEquals(3, tiles.get(0).width);
        // true tile: south-west world tile moved to the footprint center.
        assertEquals(1280 + 64 + 128, tiles.get(1).point.getX()); assertEquals(3, tiles.get(1).width);
        // south-west tile: one tile at the south-west of the rendered footprint.
        assertEquals(1400 - 128, tiles.get(2).point.getX()); assertEquals(1, tiles.get(2).width);
        assertEquals(1280 + 64, tiles.get(3).point.getX());
        assertEquals(200, tiles.get(0).color.getAlpha());
        assertEquals(30, tiles.get(0).fill.getAlpha());
        assertEquals("bnh:7:tile", tiles.get(0).key);
    }

    @Test public void respawnTilesUseTheRespawnSettings()
    {
        when(view.respawnTiles()).thenReturn(java.util.Collections.singletonList(
            new BetterNpcView.RespawnTile(9, new LocalPoint(1472, 1472, -1), 3, 0)));
        List<Marker> tiles = new ArrayList<>();
        source.collect(tiles, new ArrayList<>());
        assertEquals(1, tiles.size());
        Marker m = tiles.get(0);
        assertEquals(BetterNpcSource.respawnKey(9), m.key);
        assertEquals(3, m.width);
        assertEquals(config.respawnOutlineColor(), m.color);
        assertEquals(config.respawnFillColor(), m.fill);
        assertEquals(config.respawnTileWidth(), m.borderWidth, 0);
    }

    @Test public void dashedLinesStayTwoDimensionalAndOutlinesGoToTheScene()
    {
        when(config.tileLines()).thenReturn(BetterNpcHighlightConfig.lineType.DASH);
        styles.add("tile"); styles.add("outline");
        List<Marker> tiles = new ArrayList<>();
        List<ModelTarget> models = new ArrayList<>();
        source.collect(tiles, models);
        assertTrue(tiles.isEmpty());
        assertEquals(1, models.size());
        assertTrue(models.get(0).outline);
    }

    @Test public void hullAreaAndClickboxBecomeSceneModels()
    {
        styles.add("hull"); styles.add("area"); styles.add("clickbox");
        List<ModelTarget> models = new ArrayList<>();
        source.collect(new ArrayList<>(), models);
        assertEquals(3, models.size());
        assertFalse(models.get(0).clickbox);
        assertEquals(0, models.get(1).borderWidth, 0);
        assertTrue(models.get(2).clickbox);
        assertEquals(1, models.get(2).borderWidth, 0);
    }
}
