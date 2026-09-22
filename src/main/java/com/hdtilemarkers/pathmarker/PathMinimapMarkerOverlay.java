/*
 * Adapted from Path Marker by GeChallengeM
 * https://github.com/GeChallengeM/path-marker, commit 495f3594bf697a1b9a5313802f731b2c85a6e37e
 * Copyright (c) 2022, GeChallengeM. BSD 2-Clause License; see META-INF/LICENSE-path-marker
 * and THIRD_PARTY_NOTICES.md. Changes for HD Tile Markers: HD Tile Markers config and enums.
 */
package com.hdtilemarkers.pathmarker;

import com.hdtilemarkers.HdTileMarkersConfig;

import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.Varbits;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.*;

public class PathMinimapMarkerOverlay extends Overlay
{
    private final Client client;
    private final PathMarker plugin;

    private final HdTileMarkersConfig config;

    PathMinimapMarkerOverlay(Client client, HdTileMarkersConfig config, PathMarker plugin)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (client.getLocalPlayer() == null)
        {
            return null;
        }
        double angle = client.getCameraYawTarget() * 0.000383495196971D;
        Widget minimapDrawWidget;
        if (client.isResized())
        {
            if (client.getVarbitValue(Varbits.SIDE_PANELS) == 1)
            {
                minimapDrawWidget = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_MINIMAP_DRAW_AREA);
            }
            else
            {
                minimapDrawWidget = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_MINIMAP_DRAW_AREA);
            }
        }
        else
        {
            minimapDrawWidget = client.getWidget(ComponentID.FIXED_VIEWPORT_MINIMAP_DRAW_AREA);
        }
        if (minimapDrawWidget == null || minimapDrawWidget.isHidden())
        {
            return null;
        }
        Point minimapWidgetLocation = minimapDrawWidget.getCanvasLocation();
        Point minimapPoint = new Point( minimapWidgetLocation.getX() + minimapDrawWidget.getWidth()/2, minimapWidgetLocation.getY() + minimapDrawWidget.getHeight()/2);
        graphics.rotate(angle, minimapPoint.getX(), minimapPoint.getY());
        // HD Tile Markers: the hover path on the minimap is a separate toggle, off by default.
        if (config.hoverPathMinimap() && (config.hoverPathDisplaySetting() != HdTileMarkersConfig.PathDisplaySetting.NEVER)
                && (config.activePathDisplaySetting() == HdTileMarkersConfig.PathDisplaySetting.NEVER || !plugin.isPathActive() || !config.drawOnlyIfNoActivePath())
                && (plugin.isKeyDisplayHoverPath() || config.hoverPathDisplaySetting() == HdTileMarkersConfig.PathDisplaySetting.ALWAYS))
        {
            for (WorldPoint worldPoint : plugin.getHoverPathTiles())
            {
                if (config.hoverPathDrawLocations() == HdTileMarkersConfig.DrawLocations.BOTH || config.hoverPathDrawLocations() == HdTileMarkersConfig.DrawLocations.MINIMAP)
                {
                    if (config.hoverPathDrawMode() == HdTileMarkersConfig.DrawMode.FULL_PATH || worldPoint == plugin.getHoverPathTiles().get(plugin.getHoverPathTiles().size() - 1))
                    {
                        renderMinimapTile(graphics, worldPoint, config.hoverPathFill1(), minimapPoint);
                    }
                }
            }
            for (WorldPoint worldPoint : plugin.getHoverMiddlePathTiles())
            {
                if (config.hoverPathDrawLocations() == HdTileMarkersConfig.DrawLocations.BOTH || config.hoverPathDrawLocations() == HdTileMarkersConfig.DrawLocations.MINIMAP)
                {
                    if (config.hoverPathDrawMode() == HdTileMarkersConfig.DrawMode.FULL_PATH || worldPoint == plugin.getHoverPathTiles().get(plugin.getHoverPathTiles().size() - 1))
                    {
                        renderMinimapTile(graphics, worldPoint, config.hoverPathFill2(), minimapPoint);
                    }
                }
            }
        }
        if ((config.activePathDisplaySetting() != HdTileMarkersConfig.PathDisplaySetting.NEVER) && plugin.isPathActive()
                && (plugin.isKeyDisplayActivePath() || config.activePathDisplaySetting() == HdTileMarkersConfig.PathDisplaySetting.ALWAYS))
        {
            for (WorldPoint worldPoint : plugin.getActivePathTiles())
            {
                if (config.activePathDrawLocations() == HdTileMarkersConfig.DrawLocations.BOTH || config.activePathDrawLocations() == HdTileMarkersConfig.DrawLocations.MINIMAP)
                {
                    if (config.activePathDrawMode() == HdTileMarkersConfig.DrawMode.FULL_PATH || worldPoint == plugin.getActivePathTiles().get(plugin.getActivePathTiles().size() - 1))
                    {
                        renderMinimapTile(graphics, worldPoint, config.activePathFill1(), minimapPoint);
                    }
                }
            }
            for (WorldPoint worldPoint : plugin.getActiveMiddlePathTiles())
            {
                if (config.activePathDrawLocations() == HdTileMarkersConfig.DrawLocations.BOTH || config.activePathDrawLocations() == HdTileMarkersConfig.DrawLocations.MINIMAP)
                {
                    if (config.activePathDrawMode() == HdTileMarkersConfig.DrawMode.FULL_PATH || worldPoint == plugin.getActivePathTiles().get(plugin.getActivePathTiles().size() - 1))
                    {
                        renderMinimapTile(graphics, worldPoint, config.activePathFill2(), minimapPoint);
                    }
                }
            }
        }
        graphics.rotate(-angle, minimapPoint.getX(), minimapPoint.getY());
        return null;
    }

    private void renderMinimapTile(Graphics2D graphics, WorldPoint worldPoint, Color color, Point miniMapPoint)
    {
        double minimapTileSize = client.getMinimapZoom();
        LocalPoint lp = LocalPoint.fromWorld(client.getLocalPlayer().getWorldView(), worldPoint);
        if (lp == null)
        {
            return;
        }
        LocalPoint localLocation = client.getLocalPlayer().getLocalLocation();
        if (localLocation == null)
        {
            return;
        }
        // Every world tile is 128 units apart
        double locationSize = 128.0/minimapTileSize;
        int x = lp.getX() - localLocation.getX();
        int y = localLocation.getY() - lp.getY();
        int squareDistance = x*x + y*y;
        // Roughly measured
        double tileRadius = 74.0 / minimapTileSize;
        double maxDistance = tileRadius * 128.0;
        int maxSquareDistance = (int)(maxDistance * maxDistance);
        if (squareDistance > maxSquareDistance) {
            return;
        }
        int tileStartX = (int)Math.round((x - 64) / locationSize);
        int tileStartY = (int)Math.round((y - 64) / locationSize);
        int tileEndX = (int)Math.round((x + 64) / locationSize);
        int tileEndY = (int)Math.round((y + 64) / locationSize);
        int tileSizeX = tileEndX - tileStartX;
        int tileSizeY = tileEndY - tileStartY;
        graphics.setColor(color);
        graphics.fillRect(tileStartX + miniMapPoint.getX(), tileStartY + miniMapPoint.getY(), tileSizeX, tileSizeY);
    }
}
