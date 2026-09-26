package com.hdtilemarkers;

import java.awt.Color;
import java.awt.Rectangle;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Renderable;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ModelTargetTest
{
    @Test public void temporaryClientGeometryFailureIsRetried()
    {
        GameObject object = mock(GameObject.class);
        Renderable renderable = mock(Renderable.class);
        Model model = mock(Model.class);
        Rectangle clickbox = new Rectangle(10, 10, 20, 20);
        when(renderable.getModel()).thenThrow(new NullPointerException("client model unavailable")).thenReturn(model);
        when(object.getClickbox()).thenThrow(new NullPointerException("client model unavailable")).thenReturn(clickbox);
        ModelTarget target = ModelTarget.object("obstacle", object, renderable, 0, 0,
            Color.GREEN, Color.BLACK, 1, true, object::getClickbox);
        assertNull(target.mesh());
        assertNull(target.shape());
        assertSame(model, target.mesh());
        assertSame(clickbox, target.shape());
    }
}
