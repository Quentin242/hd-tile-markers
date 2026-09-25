package com.hdtilemarkers;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RoguesDenSourceTest
{
    /** Obstacles on their own tile are highlighted, only while the jewel is carried; other objects are not. */
    @Test public void obstaclesWithTheJewel()
    {
        Client client = mock(Client.class);
        WorldView wv = mock(WorldView.class);
        when(wv.getPlane()).thenReturn(1);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        RoguesDenSource source = new RoguesDenSource(client);
        spawn(source, ObjectID.ROGUESDEN_OBSTACLE_CONTORTION_BARS, new WorldPoint(3049, 4997, 1));
        spawn(source, ObjectID.ROGUESDEN_OBSTACLE_CONTORTION_BARS, new WorldPoint(3050, 4997, 1));
        List<ModelTarget> models = new ArrayList<>();
        source.collect(models);
        assertTrue(models.isEmpty());
        ItemContainer inventory = mock(ItemContainer.class);
        when(inventory.contains(ItemID.ROGUESDEN_GEM)).thenReturn(true);
        source.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inventory));
        source.collect(models);
        assertEquals(1, models.size());
        assertTrue(models.get(0).clickbox);
    }

    private static void spawn(RoguesDenSource source, int id, WorldPoint at)
    {
        GameObject object = mock(GameObject.class);
        when(object.getId()).thenReturn(id);
        when(object.getRenderable()).thenReturn(mock(Model.class));
        Tile tile = mock(Tile.class);
        when(tile.getWorldLocation()).thenReturn(at);
        when(tile.getPlane()).thenReturn(at.getPlane());
        GameObjectSpawned e = new GameObjectSpawned();
        e.setTile(tile);
        e.setGameObject(object);
        source.onGameObjectSpawned(e);
    }
}
