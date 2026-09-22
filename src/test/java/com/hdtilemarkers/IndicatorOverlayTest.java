package com.hdtilemarkers;

import com.hdtilemarkers.betternpc.BetterNpcView;
import com.hdtilemarkers.betternpc.NPCInfo;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.*;

public class IndicatorOverlayTest
{
    private HdTileMarkersPlugin plugin;
    private IndicatorOverlay overlay;
    private ModelOutlineRenderer outlines;

    @Before public void setup()
    {
        plugin = mock(HdTileMarkersPlugin.class);
        ConfigManager configs = mock(ConfigManager.class);
        when(configs.getConfig(HdTileMarkersConfig.class)).thenReturn(mock(HdTileMarkersConfig.class));
        outlines = mock(ModelOutlineRenderer.class);
        overlay = new IndicatorOverlay(mock(Client.class), plugin, configs, outlines, mock(SpriteManager.class));
        when(plugin.sceneActive()).thenReturn(true);
    }

    private void render()
    {
        Graphics2D g = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try { overlay.render(g); } finally { g.dispose(); }
    }

    @Test public void sixtyFifthModelFallsBackWithoutRedrawingFirstSixtyFour()
    {
        List<ModelTarget> targets = new ArrayList<>();
        for (int i = 0; i < 65; i++)
        {
            NPC npc = mock(NPC.class);
            when(npc.getConvexHull()).thenReturn(new Rectangle(10, 10, 10, 10));
            String key = "model:" + i;
            targets.add(ModelTarget.npc(key, npc, Color.CYAN, Color.BLACK, 2));
            when(plugin.markerInScene(key)).thenReturn(i < 64);
        }
        when(plugin.modelTargets()).thenReturn(targets);
        render();
        for (int i = 0; i < 64; i++) { verify(targets.get(i).npc, never()).getConvexHull(); }
        verify(targets.get(64).npc).getConvexHull();
    }

    @Test public void unsupportedModelFallsBackWhileSceneRemainsActive()
    {
        NPC npc = mock(NPC.class);
        when(npc.getConvexHull()).thenReturn(new Rectangle(10, 10, 10, 10));
        when(plugin.modelTargets()).thenReturn(Collections.singletonList(
            ModelTarget.npc("child-world", npc, Color.CYAN, Color.BLACK, 2)));
        render();
        verify(npc).getConvexHull();
    }

    @Test public void externalOutlineUsesNativeFallbackOnlyWhenUnhandled()
    {
        NPC npc = mock(NPC.class);
        when(plugin.modelTargets()).thenReturn(Collections.singletonList(
            ModelTarget.npcOutline("ext:test:outline", npc, Color.CYAN, 2)));
        render();
        verify(outlines).drawOutline(npc, 2, Color.CYAN, 0);
        when(plugin.markerInScene("ext:test:outline")).thenReturn(true);
        render();
        verifyNoMoreInteractions(outlines);
    }

    @Test public void betterNpcUsesItsStyleFallbackExactlyOnce()
    {
        NPC npc = mock(NPC.class);
        when(npc.getIndex()).thenReturn(7);
        NPCInfo info = mock(NPCInfo.class);
        when(info.getNpc()).thenReturn(npc);
        BetterNpcView view = mock(BetterNpcView.class);
        when(plugin.drawsBetterNpc()).thenReturn(true);
        when(plugin.betterNpcView()).thenReturn(view);
        doAnswer(call -> {
            BetterNpcView.Visitor visitor = call.getArgument(0);
            visitor.accept(info, "hull");
            return null;
        }).when(view).visit(any());
        when(plugin.modelTargets()).thenReturn(Collections.singletonList(
            ModelTarget.npc("bnh:7:hull", npc, Color.CYAN, Color.BLACK, 2)));
        render();
        verify(view).render2d(any(), eq(info), eq("hull"));
        verify(npc, never()).getConvexHull();
        when(plugin.markerInScene("bnh:7:hull")).thenReturn(true);
        render();
        verify(view, times(1)).render2d(any(), eq(info), eq("hull"));
    }
}
