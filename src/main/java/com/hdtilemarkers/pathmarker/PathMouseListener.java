/*
 * Adapted from Path Marker by GeChallengeM
 * https://github.com/GeChallengeM/path-marker, commit 495f3594bf697a1b9a5313802f731b2c85a6e37e
 * Copyright (c) 2022, GeChallengeM. BSD 2-Clause License; see META-INF/LICENSE-path-marker
 * and THIRD_PARTY_NOTICES.md. Changes for HD Tile Markers: renamed.
 */
package com.hdtilemarkers.pathmarker;

import net.runelite.api.Client;

import java.awt.event.MouseEvent;

public class PathMouseListener implements net.runelite.client.input.MouseListener
{
    private final Client client;

    private final PathMarker plugin;

    PathMouseListener(Client client, PathMarker plugin)
    {
        this.client = client;
        this.plugin = plugin;
    }

    @Override
    public MouseEvent mouseClicked(MouseEvent mouseEvent)
    {
        return mouseEvent;
    }

    @Override
    public MouseEvent mousePressed(MouseEvent mouseEvent)
    {
        if (mouseEvent.getButton()==MouseEvent.BUTTON1)
        {
            plugin.setLeftClicked(true);
            plugin.setLastMouseCanvasPosition(client.getMouseCanvasPosition());
        }
        return mouseEvent;
    }

    @Override
    public MouseEvent mouseReleased(MouseEvent mouseEvent)
    {
        return mouseEvent;
    }

    @Override
    public MouseEvent mouseEntered(MouseEvent mouseEvent)
    {
        return mouseEvent;
    }

    @Override
    public MouseEvent mouseExited(MouseEvent mouseEvent)
    {
        return mouseEvent;
    }

    @Override
    public MouseEvent mouseDragged(MouseEvent mouseEvent)
    {
        return mouseEvent;
    }

    @Override
    public MouseEvent mouseMoved(MouseEvent mouseEvent)
    {
        return mouseEvent;
    }
}
