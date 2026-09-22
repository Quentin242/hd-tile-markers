/*
 * Adapted from Path Marker by GeChallengeM
 * https://github.com/GeChallengeM/path-marker, commit 495f3594bf697a1b9a5313802f731b2c85a6e37e
 * Copyright (c) 2022, GeChallengeM. BSD 2-Clause License; see META-INF/LICENSE-path-marker
 * and THIRD_PARTY_NOTICES.md. Changes for HD Tile Markers: renamed; HD Tile Markers config.
 */
package com.hdtilemarkers.pathmarker;

import com.hdtilemarkers.HdTileMarkersConfig;

import java.awt.event.KeyEvent;

public class PathKeyListener implements net.runelite.client.input.KeyListener
{
    private final PathMarker plugin;

    private final HdTileMarkersConfig config;

    PathKeyListener(PathMarker plugin, HdTileMarkersConfig config)
    {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void keyTyped(KeyEvent event)
    {
    }

    @Override
    public void keyPressed(KeyEvent event)
    {
        if (KeyEvent.VK_CONTROL == event.getKeyCode())
        {
            plugin.setCtrlHeld(true);
        }
        if (config.displayKeybindActivePath().matches(event))
        {
            if (config.activePathDisplaySetting() == HdTileMarkersConfig.PathDisplaySetting.TOGGLE_ON_KEYPRESS)
            {
                plugin.setKeyDisplayActivePath(!plugin.isKeyDisplayActivePath());
            }
            else if (config.activePathDisplaySetting() == HdTileMarkersConfig.PathDisplaySetting.WHILE_KEY_PRESSED)
            {
                plugin.setKeyDisplayActivePath(true);
            }
        }
        if (config.displayKeybindHoverPath().matches(event))
        {
            if (config.hoverPathDisplaySetting() == HdTileMarkersConfig.PathDisplaySetting.TOGGLE_ON_KEYPRESS)
            {
                plugin.setKeyDisplayHoverPath(!plugin.isKeyDisplayHoverPath());
            }
            else if (config.hoverPathDisplaySetting() == HdTileMarkersConfig.PathDisplaySetting.WHILE_KEY_PRESSED)
            {
                plugin.setKeyDisplayHoverPath(true);
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent event)
    {
        if (KeyEvent.VK_CONTROL == event.getKeyCode())
        {
            plugin.setCtrlHeld(false);
        }
        if (config.displayKeybindActivePath().matches(event))
        {
            if (config.activePathDisplaySetting() == HdTileMarkersConfig.PathDisplaySetting.WHILE_KEY_PRESSED)
            {
                plugin.setKeyDisplayActivePath(false);
            }
        }
        if (config.displayKeybindHoverPath().matches(event))
        {
            if (config.hoverPathDisplaySetting() == HdTileMarkersConfig.PathDisplaySetting.WHILE_KEY_PRESSED)
            {
                plugin.setKeyDisplayHoverPath(false);
            }
        }
    }
}
