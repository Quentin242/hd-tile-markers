/*
 * Adapted from Path Marker by GeChallengeM
 * https://github.com/GeChallengeM/path-marker, commit 495f3594bf697a1b9a5313802f731b2c85a6e37e
 * Copyright (c) 2022, GeChallengeM. BSD 2-Clause License; see META-INF/LICENSE-path-marker
 * and THIRD_PARTY_NOTICES.md. Changes for HD Tile Markers: no longer a Plugin (creates its listeners and minimap overlay itself); started by HD Tile Markers; Lombok replaced by accessors; config and enums from HD Tile Markers; scene tiles are drawn by the HD Tile Markers renderer instead of PathMarkerOverlay, whose display conditions moved to sceneTiles(); the minimap route and hover tiles are only recalculated when their input changes.
 */
package com.hdtilemarkers.pathmarker;

import com.hdtilemarkers.HdTileMarkersConfig;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.OverlayManager;
import org.apache.commons.lang3.tuple.Pair;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@javax.inject.Singleton
public class PathMarker
{
    @Inject
    private Client client;

    @Inject
    private HdTileMarkersConfig config;

    @Inject
    private OverlayManager overlayManager;

    private PathMinimapMarkerOverlay minimapOverlay;

    public Pathfinder pathfinder;

    private Tile lastSelectedSceneTile;

    private boolean ctrlHeld;

    private WorldPoint lastTickWorldLocation;

    private MenuEntry lastSelectedMenuEntry;

    private List<WorldPoint> activeCheckpointWPs;

    private Tile oldSelectedSceneTile;

    private boolean isRunning;

    private boolean activePathFound;

    private boolean hoverPathFound;

    private boolean activePathStartedLastTick;

    private boolean activePathMismatchLastTick;

    private boolean calcTilePathOnNextClientTick;
    // Inputs of the last minimap route and hover tiles, to skip recalculating unchanged ones.
    private Point lastMinimapPoint;
    private WorldPoint lastMinimapFrom, lastHoverStart;
    private List<WorldPoint> lastHoverCheckpoints;
    private boolean lastHoverRunning, lastHoverFound;

    private long hoverPathId;

    private boolean keyDisplayActivePath;

    private boolean keyDisplayHoverPath;

    private boolean leftClicked;

    private boolean pathActive;

    private List<WorldPoint> hoverPathTiles;

    private List<WorldPoint> hoverMiddlePathTiles;

    private List<WorldPoint> activePathTiles;

    private List<WorldPoint> activeMiddlePathTiles;

    private List<WorldPoint> hoverCheckpointWPs;

    private List<WorldArea> npcBlockWAs;

    private Point lastMouseCanvasPosition;

    private MenuEntry[] oldMenuEntries;

    @Inject
    private KeyManager keyManager;

    @Inject
    private MouseManager mouseManager;

    private PathKeyListener keyListener;

    private PathMouseListener mouseListener;

    private static Map<Integer, Integer> objectBlocking;

    private static Map<Integer, Integer> npcBlocking;

    static class PathDestination
    {
        private final WorldPoint worldPoint;
        private final int sizeX;
        private final int sizeY;
        private int objConfig;
        private final int objID;
        private final Actor actor;

        public PathDestination(WorldPoint worldPoint, int sizeX, int sizeY, int objConfig, int objID)
        {
            this.worldPoint = worldPoint;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.objConfig = objConfig;
            this.objID = objID;
            this.actor = null;
        }
        public PathDestination(WorldPoint worldPoint, int sizeX, int sizeY, int objConfig, int objID, Actor actor)
        {
            this.worldPoint = worldPoint;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.objConfig = objConfig;
            this.objID = objID;
            this.actor = actor;
        }
    }

    private PathDestination activePathDestination;

    /** Called by HD Tile Markers when path marking is enabled. */
    public void startUp() {
        hoverPathTiles = new ArrayList<>();
        hoverMiddlePathTiles = new ArrayList<>();
        hoverCheckpointWPs = new ArrayList<>();
        activePathTiles = new ArrayList<>();
        activeMiddlePathTiles = new ArrayList<>();
        activeCheckpointWPs = new ArrayList<>();
        npcBlockWAs = new ArrayList<>();
        ctrlHeld = false;
        pathActive = false;
        activePathStartedLastTick = false;
        activePathMismatchLastTick = false;
        leftClicked = false;
        keyDisplayActivePath = false;
        isRunning = willRunOnClick();
        objectBlocking = readFile("loc_blocking.txt");
        npcBlocking = readFile("npc_blocking.txt");
        // Created here rather than injected: they need this instance, and
        // injecting them into it would be a construction cycle.
        minimapOverlay = new PathMinimapMarkerOverlay(client, config, this);
        keyListener = new PathKeyListener(this, config);
        mouseListener = new PathMouseListener(client, this);
        overlayManager.add(minimapOverlay);
        keyManager.registerKeyListener(keyListener);
        mouseManager.registerMouseListener(mouseListener);
        pathfinder = new Pathfinder(client, this);
    }

    public void shutDown() {
        if (minimapOverlay == null)
        {
            return;
        }
        overlayManager.remove(minimapOverlay);
        keyManager.unregisterKeyListener(keyListener);
        mouseManager.unregisterMouseListener(mouseListener);
    }

    private Pair<List<WorldPoint>, Boolean> pathToHover()
    {
        if (client.getLocalPlayer() == null)
        {
            return null;
        }
        MenuEntry[] menuEntries = client.getMenu().getMenuEntries();
        if (menuEntries.length == 0)
        {
            hoverPathId = 0;
            return null;
        }
        MenuEntry menuEntry;
        if (!client.isMenuOpen())
        {
            int i = 1;
            menuEntry = menuEntries[menuEntries.length - 1];
            MenuAction type = menuEntry.getType();
            while (i < menuEntries.length && (type == MenuAction.EXAMINE_ITEM_GROUND
                    || type == MenuAction.EXAMINE_NPC
                    || type == MenuAction.EXAMINE_OBJECT
                    || type == MenuAction.RUNELITE
                    || type == MenuAction.RUNELITE_HIGH_PRIORITY
                    || type == MenuAction.RUNELITE_INFOBOX
                    || type == MenuAction.RUNELITE_OVERLAY
                    || type == MenuAction.RUNELITE_PLAYER
                    || type == MenuAction.RUNELITE_OVERLAY_CONFIG))
            {
                // For some reason, RuneLite considers the "Examine" options to be the first menuEntryOptions when no right-click menu is open.
                // It's impossible to have "Examine" as left-click option, a far as I'm aware.
                // The first non-Examine option is the real left-click option.
                // Oh, and apparently the issue is also there with RuneLite menu entries, so It'll be assumed those are never left-click.
                i += 1;
                menuEntry = menuEntries[menuEntries.length - i];
                type = menuEntry.getType();
            }
        }
        else
        {
            menuEntry = hoveredMenuEntry(menuEntries);
        }
        switch (menuEntry.getType())
        {
            case EXAMINE_ITEM_GROUND:
            case EXAMINE_NPC:
            case EXAMINE_OBJECT:
            case CANCEL:
            case CC_OP:
            case CC_OP_LOW_PRIORITY:
            case PLAYER_EIGHTH_OPTION:
            case WIDGET_CLOSE:
            case WIDGET_CONTINUE:
            case WIDGET_FIRST_OPTION:
            case WIDGET_SECOND_OPTION:
            case WIDGET_THIRD_OPTION:
            case WIDGET_FOURTH_OPTION:
            case WIDGET_FIFTH_OPTION:
            case WIDGET_TARGET:
            case WIDGET_TARGET_ON_WIDGET:
            case WIDGET_TYPE_1:
            case WIDGET_TYPE_4:
            case WIDGET_TYPE_5:
            case RUNELITE:
            case RUNELITE_HIGH_PRIORITY:
            case RUNELITE_INFOBOX:
            case RUNELITE_OVERLAY:
            case RUNELITE_OVERLAY_CONFIG:
            case RUNELITE_PLAYER:
            {
                hoverCheckpointWPs.clear();
                // Cleared in place: the hover tiles must be rebuilt.
                lastHoverCheckpoints = null;
                hoverPathId = 0;
                return null;
            }
            case GAME_OBJECT_FIRST_OPTION:
            case GAME_OBJECT_SECOND_OPTION:
            case GAME_OBJECT_THIRD_OPTION:
            case GAME_OBJECT_FOURTH_OPTION:
            case GAME_OBJECT_FIFTH_OPTION:
            case WIDGET_TARGET_ON_GAME_OBJECT:
            case GROUND_ITEM_FIRST_OPTION:
            case GROUND_ITEM_SECOND_OPTION:
            case GROUND_ITEM_THIRD_OPTION:
            case GROUND_ITEM_FOURTH_OPTION:
            case GROUND_ITEM_FIFTH_OPTION:
            case WIDGET_TARGET_ON_GROUND_ITEM:
            {
                int x = menuEntry.getParam0();
                int y = menuEntry.getParam1();
                int id = menuEntry.getIdentifier();
                int objConfig = -1;
                int sizeX = 1;
                int sizeY = 1;
                TileObject tileObject = findTileObject(x, y, id);
                TileItem tileItem = findTileItem(x, y, id);
                if (tileObject == null && tileItem == null)
                {
                    hoverPathId = 0;
                    return null;
                }
                if (tileObject != null)
                {
                    if (tileObject instanceof GameObject)
                    {
                        GameObject gameObject = (GameObject) tileObject;
                        objConfig = gameObject.getConfig();
                        sizeX = gameObject.sizeX();
                        sizeY = gameObject.sizeY();
                    }
                    if (tileObject instanceof WallObject)
                    {
                        WallObject wallObject = (WallObject) tileObject;
                        objConfig = wallObject.getConfig();
                    }
                    if (tileObject instanceof DecorativeObject)
                    {
                        DecorativeObject decorativeObject = (DecorativeObject) tileObject;
                        objConfig = decorativeObject.getConfig();
                    }
                    if (tileObject instanceof GroundObject)
                    {
                        GroundObject groundObject = (GroundObject) tileObject;
                        objConfig = groundObject.getConfig();
                    }
                }
                long newHoverPathId = (x & 0xFF) |
                        ((y & 0xFF) << 8) |
                        ((sizeX & 0xF) << 16) |
                        ((sizeY & 0xF) << 20) |
                        ((long) (objConfig & 0xFFFF) << 24) |
                        ((long) (id & 0xFFFF) << 40) |
                        ((long) client.getLocalPlayer().getWorldView().getPlane()) << 56;
                if (hoverPathId != newHoverPathId) {
                    hoverPathId = newHoverPathId;
                    return pathfinder.pathTo(x, y, sizeX, sizeY, objConfig, id);
                } else {
                    return null;
                }

            }
            case NPC_FIRST_OPTION:
            case NPC_SECOND_OPTION:
            case NPC_THIRD_OPTION:
            case NPC_FOURTH_OPTION:
            case NPC_FIFTH_OPTION:
            case WIDGET_TARGET_ON_NPC:
            case PLAYER_FIRST_OPTION:
            case PLAYER_SECOND_OPTION:
            case PLAYER_THIRD_OPTION:
            case PLAYER_FOURTH_OPTION:
            case PLAYER_FIFTH_OPTION:
            case PLAYER_SIXTH_OPTION:
            case PLAYER_SEVENTH_OPTION:
            case WIDGET_TARGET_ON_PLAYER:
            {
                Actor actor = menuEntry.getActor();
                if (actor == null)
                {
                    return null;
                }
                int x = actor.getLocalLocation().getSceneX();
                int y = actor.getLocalLocation().getSceneY();
                int size = 1;
                if (actor instanceof NPC)
                {
                    size = ((NPC) actor).getComposition().getSize();
                }
                long newHoverPathId = (x & 0xFF) |
                        ((y & 0xFF) << 8) |
                        ((size & 0xF) << 16) |
                        ((size & 0xF) << 20) |
                        ((long) client.getLocalPlayer().getWorldView().getPlane()) << 56;
                if (hoverPathId != newHoverPathId) {
                    hoverPathId = newHoverPathId;
                    return pathfinder.pathTo(x, y, size, size, -2, -1);
                } else {
                    return null;
                }
            }
            case WALK:
            default:
            {
                Tile selectedSceneTile = client.getLocalPlayer().getWorldView().getSelectedSceneTile();
                if (selectedSceneTile == null)
                {
                    return null;
                }
                Tile tile = client.getLocalPlayer().getWorldView().getSelectedSceneTile();
                long newHoverPathId = (tile.getSceneLocation().getX() & 0xFF) |
                        ((tile.getSceneLocation().getY() & 0xFF) << 8) |
                        ((long) tile.getPlane()) << 56;
                if (hoverPathId != newHoverPathId) {
                    hoverPathId = newHoverPathId;
                    return pathfinder.pathTo(client.getLocalPlayer().getWorldView().getSelectedSceneTile());
                } else {
                    return null;
                }
            }
        }
    }

    void pathFromCheckpointTiles(List<WorldPoint> checkpointWPs, boolean running, List<WorldPoint> middlePathTiles, List<WorldPoint> pathTiles, boolean pathFound)
    {
        pathTiles.clear();
        middlePathTiles.clear();
        if (client.getLocalPlayer() == null || client.getTopLevelWorldView() == null)
        {
            return;
        }
        WorldArea currentWA = client.getLocalPlayer().getWorldArea();
        if (currentWA == null || checkpointWPs == null || checkpointWPs.size() == 0)
        {
            return;
        }
        if ((currentWA.getPlane() != checkpointWPs.get(0).getPlane()) && pathFound)
        {
            return;
        }
        boolean runSkip = true;
        int cpTileIndex = 0;
        while (currentWA.toWorldPoint().getX() != checkpointWPs.get(checkpointWPs.size() - 1).getX()
                || currentWA.toWorldPoint().getY() != checkpointWPs.get(checkpointWPs.size() - 1).getY())
        {
            WorldPoint cpTileWP = checkpointWPs.get(cpTileIndex);
            if (currentWA.toWorldPoint().equals(cpTileWP))
            {
                cpTileIndex += 1;
                cpTileWP = checkpointWPs.get(cpTileIndex);
            }
            int dx = Integer.signum(cpTileWP.getX() - currentWA.getX());
            int dy = Integer.signum(cpTileWP.getY() - currentWA.getY());
            if (!WorldPoint.isInScene(client.getTopLevelWorldView(), currentWA.getX(), currentWA.getY()) ||
                !WorldPoint.isInScene(client.getTopLevelWorldView(), currentWA.getX() + dx, currentWA.getY() + dy) ||
                (dx != 0 && !WorldPoint.isInScene(client.getTopLevelWorldView(), currentWA.getX() + dx, currentWA.getY())) ||
                (dy != 0 && !WorldPoint.isInScene(client.getTopLevelWorldView(), currentWA.getX(), currentWA.getY() + dy)))
            {
                break;
            }
            WorldArea finalCurrentWA = currentWA;
            boolean movementCheck = currentWA.canTravelInDirection(client.getTopLevelWorldView(), dx, dy, (worldPoint -> {
                WorldPoint worldPoint1 = new WorldPoint(finalCurrentWA.getX() + dx, finalCurrentWA.getY(), client.getLocalPlayer().getWorldView().getPlane());
                WorldPoint worldPoint2 = new WorldPoint(finalCurrentWA.getX(), finalCurrentWA.getY() + dy, client.getLocalPlayer().getWorldView().getPlane());
                WorldPoint worldPoint3 = new WorldPoint(finalCurrentWA.getX() + dx, finalCurrentWA.getY() + dy, client.getLocalPlayer().getWorldView().getPlane());
                for (WorldArea worldArea : npcBlockWAs)
                {
                    if (worldArea.contains(worldPoint1) || worldArea.contains(worldPoint2) || worldArea.contains(worldPoint3))
                    {
                        return false;
                    }
                }
                return true;
            }));
            if (movementCheck)
            {
                currentWA = new WorldArea(currentWA.getX() + dx, currentWA.getY() + dy, 1, 1, client.getLocalPlayer().getWorldView().getPlane());
                if (currentWA.toWorldPoint().equals(checkpointWPs.get(checkpointWPs.size() - 1)))
                {
                    pathTiles.add(currentWA.toWorldPoint());
                }
                else if (runSkip && running)
                {
                    middlePathTiles.add(currentWA.toWorldPoint());
                }
                else
                {
                    pathTiles.add(currentWA.toWorldPoint());
                }
                runSkip = !runSkip;
                continue;
            }
            movementCheck = currentWA.canTravelInDirection(client.getTopLevelWorldView(), dx, 0, (worldPoint -> {
                for (WorldArea worldArea : npcBlockWAs)
                {
                    WorldPoint worldPoint1 = new WorldPoint(finalCurrentWA.getX() + dx, finalCurrentWA.getY(), client.getLocalPlayer().getWorldView().getPlane());
                    if (worldArea.contains(worldPoint1))
                    {
                        return false;
                    }
                }
                return true;
            }));
            if (dx != 0 && movementCheck)
            {
                currentWA = new WorldArea(currentWA.getX() + dx, currentWA.getY(), 1, 1, client.getLocalPlayer().getWorldView().getPlane());
                if (currentWA.toWorldPoint().equals(checkpointWPs.get(checkpointWPs.size() - 1)))
                {
                    pathTiles.add(currentWA.toWorldPoint());
                }
                else if (runSkip && running)
                {
                    middlePathTiles.add(currentWA.toWorldPoint());
                }
                else
                {
                    pathTiles.add(currentWA.toWorldPoint());
                }
                runSkip = !runSkip;
                continue;
            }
            movementCheck = currentWA.canTravelInDirection(client.getTopLevelWorldView(), 0, dy, (worldPoint -> {
                for (WorldArea worldArea : npcBlockWAs)
                {
                    WorldPoint worldPoint1 = new WorldPoint(finalCurrentWA.getX(), finalCurrentWA.getY() + dy, client.getLocalPlayer().getWorldView().getPlane());
                    if (worldArea.contains(worldPoint1))
                    {
                        return false;
                    }
                }
                return true;
            }));
            if (dy != 0 && movementCheck)
            {
                currentWA = new WorldArea(currentWA.getX(), currentWA.getY() + dy, 1, 1, client.getLocalPlayer().getWorldView().getPlane());
                if (currentWA.toWorldPoint().equals(checkpointWPs.get(checkpointWPs.size() - 1)))
                {
                    pathTiles.add(currentWA.toWorldPoint());
                }
                else if (runSkip && running)
                {
                    middlePathTiles.add(currentWA.toWorldPoint());
                }
                else
                {
                    pathTiles.add(currentWA.toWorldPoint());
                }
                runSkip = !runSkip;
                continue;
            }
            return;
        }
    }

    private void updateCheckpointTiles()
    {
        if (client.getLocalPlayer() == null || client.getTopLevelWorldView() == null)
        {
            return;
        }
        if (lastTickWorldLocation == null || activeCheckpointWPs.isEmpty())
        {
            return;
        }
        WorldArea currentWA = new WorldArea(lastTickWorldLocation.getX(), lastTickWorldLocation.getY(), 1,1, client.getLocalPlayer().getWorldView().getPlane());
        if (activeCheckpointWPs == null)
        {
            return;
        }
        if ((lastTickWorldLocation.getPlane() != activeCheckpointWPs.get(0).getPlane()) && activePathFound)
        {
            WorldPoint lastActiveCPTile = activeCheckpointWPs.get(0);
            activeCheckpointWPs.clear();
            activeCheckpointWPs.add(lastActiveCPTile);
            pathActive = false;
            return;
        }
        int cpTileIndex = 0;
        int steps = 0;
        while (currentWA.toWorldPoint().getX() != activeCheckpointWPs.get(activeCheckpointWPs.size() - 1).getX()
                || currentWA.toWorldPoint().getY() != activeCheckpointWPs.get(activeCheckpointWPs.size() - 1).getY())
        {
            WorldPoint cpTileWP = activeCheckpointWPs.get(cpTileIndex);
            if (currentWA.toWorldPoint().equals(cpTileWP))
            {
                cpTileIndex += 1;
                cpTileWP = activeCheckpointWPs.get(cpTileIndex);
            }
            int dx = Integer.signum(cpTileWP.getX() - currentWA.getX());
            int dy = Integer.signum(cpTileWP.getY() - currentWA.getY());
            if (!WorldPoint.isInScene(client.getTopLevelWorldView(), currentWA.getX(), currentWA.getY()) ||
                !WorldPoint.isInScene(client.getTopLevelWorldView(), currentWA.getX() + dx, currentWA.getY() + dy) ||
                (dx != 0 && !WorldPoint.isInScene(client.getTopLevelWorldView(), currentWA.getX() + dx, currentWA.getY())) ||
                (dy != 0 && !WorldPoint.isInScene(client.getTopLevelWorldView(), currentWA.getX(), currentWA.getY() + dy)))
            {
                break;
            }
            WorldArea finalCurrentWA = currentWA;
            boolean movementCheck = currentWA.canTravelInDirection(client.getTopLevelWorldView(), dx, dy, (worldPoint -> {
                WorldPoint worldPoint1 = new WorldPoint(finalCurrentWA.getX() + dx, finalCurrentWA.getY(), client.getLocalPlayer().getWorldView().getPlane());
                WorldPoint worldPoint2 = new WorldPoint(finalCurrentWA.getX(), finalCurrentWA.getY() + dy, client.getLocalPlayer().getWorldView().getPlane());
                WorldPoint worldPoint3 = new WorldPoint(finalCurrentWA.getX() + dx, finalCurrentWA.getY() + dy, client.getLocalPlayer().getWorldView().getPlane());
                for (WorldArea worldArea : npcBlockWAs)
                {
                    if (worldArea.contains(worldPoint1) || worldArea.contains(worldPoint2) || worldArea.contains(worldPoint3))
                    {
                        return false;
                    }
                }
                return true;
            }));
            if (movementCheck)
            {
                currentWA = new WorldArea(currentWA.getX() + dx, currentWA.getY() + dy, 1, 1, client.getLocalPlayer().getWorldView().getPlane());
            }
            else
            {
                movementCheck = currentWA.canTravelInDirection(client.getTopLevelWorldView(), dx, 0, (worldPoint -> {
                    WorldPoint worldPoint1 = new WorldPoint(finalCurrentWA.getX() + dx, finalCurrentWA.getY(), client.getLocalPlayer().getWorldView().getPlane());
                    for (WorldArea worldArea : npcBlockWAs)
                    {
                        if (worldArea.contains(worldPoint1))
                        {
                            return false;
                        }
                    }
                    return true;
                }));
                if (dx != 0 && movementCheck)
                {
                    currentWA = new WorldArea(currentWA.getX() + dx, currentWA.getY(), 1, 1, client.getLocalPlayer().getWorldView().getPlane());
                }
                else
                {
                    movementCheck = currentWA.canTravelInDirection(client.getTopLevelWorldView(), 0, dy, (worldPoint -> {
                        WorldPoint worldPoint1 = new WorldPoint(finalCurrentWA.getX(), finalCurrentWA.getY() + dy, client.getLocalPlayer().getWorldView().getPlane());
                        for (WorldArea worldArea : npcBlockWAs)
                        {
                            if (worldArea.contains(worldPoint1))
                            {
                                return false;
                            }
                        }
                        return true;
                    }));
                    if (dy != 0 && movementCheck)
                    {
                        currentWA = new WorldArea(currentWA.getX(), currentWA.getY() + dy, 1, 1, client.getLocalPlayer().getWorldView().getPlane());
                    }
                }
            }
            steps += 1;
            if (steps == 2 || !isRunning)
            {
                break;
            }
        }
        if (steps == 0 && pathActive)
        {
            WorldPoint lastActiveCPTile = activeCheckpointWPs.get(0);
            activeCheckpointWPs.clear();
            activeCheckpointWPs.add(lastActiveCPTile);
            pathActive = false;
            activePathDestination.objConfig = -1;
            activePathMismatchLastTick = true;
            return;
        }
        if (!currentWA.toWorldPoint().equals(client.getLocalPlayer().getWorldLocation()) && pathActive)
        {
            if (activePathStartedLastTick)
            {
                LocalPoint localPoint = LocalPoint.fromWorld(client.getLocalPlayer().getWorldView(), activePathDestination.worldPoint);
                if (localPoint != null)
                {
                    Pair<List<WorldPoint>, Boolean> pathResult = pathfinder.pathTo(localPoint.getSceneX(), localPoint.getSceneY(), activePathDestination.sizeX, activePathDestination.sizeY, activePathDestination.objConfig, activePathDestination.objID);
                    if (pathResult == null)
                    {
                        return;
                    }
                    lastTickWorldLocation = client.getLocalPlayer().getWorldLocation();
                    pathActive = true;
                    activeCheckpointWPs = pathResult.getLeft();
                    activePathFound = pathResult.getRight();
                    pathFromCheckpointTiles(activeCheckpointWPs, isRunning, activeMiddlePathTiles, activePathTiles, activePathFound);
                    activePathStartedLastTick = false;
                }
            }
            else if (activePathMismatchLastTick)
            {
                WorldPoint lastActiveCPTile = activeCheckpointWPs.get(0);
                activeCheckpointWPs.clear();
                activeCheckpointWPs.add(lastActiveCPTile);
                pathActive = false;
                activePathStartedLastTick = false;
            }
            activePathMismatchLastTick = true;
        }
        else
        {
            activePathMismatchLastTick = false;
        }
        for (int i = 0; i < cpTileIndex; i++)
        {
            if (activeCheckpointWPs.size()>1)
            {
                activeCheckpointWPs.remove(0);
            }
        }
    }

    private void updateNpcBlockings()
    {
        if (client.getLocalPlayer() == null)
        {
            return;
        }
        List<NPC> npcs = client.getLocalPlayer().getWorldView().npcs()
                .stream()
                .collect(Collectors.toCollection(ArrayList::new));
        npcBlockWAs.clear();
        for (NPC npc : npcs)
        {
            NPCComposition npcComposition = npc.getTransformedComposition();
            if (npcComposition == null)
            {
                continue;
            }
            if (getNpcBlocking(npcComposition.getId()))
            {
                npcBlockWAs.add(npc.getWorldArea());
            }
        }
    }

    /** Running state for the active route, or the current click preview if no route exists. */
    public boolean runningForPath() { return pathActive ? isRunning : willRunOnClick(); }

    private boolean willRunOnClick()
    {
        boolean willRun = (client.getVarpValue(173) == 1); //run toggled on
        if (!ctrlHeld)
        {
            return willRun;
        }
        int ctrlSetting = client.getVarbitValue(13132);
        switch (ctrlSetting)
        {
            case 0: //never
                return willRun;
            case 1: //walk --> run only
                return true;
            case 2: //Run --> walk only
                return false;
            case 3: //Always
                return !willRun;
            default:
                return willRun;
        }
    }

    TileItem findTileItem(int x, int y, int id)
    {
        if (client.getLocalPlayer() == null)
        {
            return null;
        }
        Scene scene = client.getLocalPlayer().getWorldView().getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getLocalPlayer().getWorldView().getPlane();
        if (plane < 0 || plane >= tiles.length || x < 0 || x >= tiles[plane].length || y < 0 || y >= tiles[plane][x].length)
        {
            return null;
        }
        Tile tile = tiles[plane][x][y];
        if (tile == null)
        {
            return null;
        }
        List<TileItem> tileItems = tile.getGroundItems();
        if (tileItems == null)
        {
            return null;
        }
        for (TileItem tileItem : tileItems)
        {
            if (tileItem != null && tileItem.getId() == id)
            {
                return tileItem;
            }
        }
        return null;
    }

    TileObject findTileObject(int x, int y, int id)
    {
        if (client.getLocalPlayer() == null)
        {
            return null;
        }
        Scene scene = client.getLocalPlayer().getWorldView().getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getLocalPlayer().getWorldView().getPlane();
        if (plane < 0 || plane >= tiles.length || x < 0 || x >= tiles[plane].length || y < 0 || y >= tiles[plane][x].length)
        {
            return null;
        }
        Tile tile = tiles[plane][x][y];
        if (tile != null)
        {
            for (GameObject gameObject : tile.getGameObjects())
            {
                if (gameObject != null && gameObject.getId() == id)
                {
                    return gameObject;
                }
            }

            WallObject wallObject = tile.getWallObject();
            if (wallObject != null && wallObject.getId() == id)
            {
                return wallObject;
            }

            DecorativeObject decorativeObject = tile.getDecorativeObject();
            if (decorativeObject != null && decorativeObject.getId() == id)
            {
                return decorativeObject;
            }

            GroundObject groundObject = tile.getGroundObject();
            if (groundObject != null && groundObject.getId() == id)
            {
                return groundObject;
            }
        }
        return null;
    }

    public static int getObjectBlocking(final int objectId, final int rotation)
    {
        if (objectBlocking == null)
        {
            return 0;
        }
        int blockingValue = objectBlocking.getOrDefault(objectId, 0);
        return rotation == 0 ? blockingValue : (((blockingValue << rotation) & 0xF) + (blockingValue >> (4 - rotation)));
    }

    public static boolean getNpcBlocking(final int npcCompId)
    {
        if (npcBlocking == null)
        {
            return false;
        }
        return npcBlocking.getOrDefault(npcCompId, 0) == 1;
    }

    private static Map<Integer, Integer> readFile(String name) {
        try {
            InputStream inputStream = PathMarker.class.getResourceAsStream(name);
            if (inputStream == null)
            {
                return null;
            }
            InputStreamReader streamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(streamReader);
            final Map<Integer, Integer> map = new LinkedHashMap<>();
            for (String line; (line = reader.readLine()) != null;) {
                String[] split = line.split("=");
                int id = Integer.parseInt(split[0]);
                int blocking = Integer.parseInt(split[1].split(" ")[0]);
                map.put(id, blocking);
            }
            reader.close();
            return map;
        } catch (Exception e) {
            // e.printStackTrace();
            return null;
        }
    }

    private Point minimapToScenePoint()
    {
        if (client.getLocalPlayer() == null)
        {
            return null;
        }
        if (client.getMenu().getMenuEntries().length != 1 || lastMouseCanvasPosition == null)
        {
            // Minimap hovering doesn't add menu entries other than the default "cancel"
            return null;
        }
        Widget minimapDrawWidget;
        if (client.isResized())
        {
            if (client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1)
            {
                minimapDrawWidget = client.getWidget(InterfaceID.ToplevelPreEoc.MINIMAP);
            }
            else
            {
                minimapDrawWidget = client.getWidget(InterfaceID.ToplevelOsrsStretch.MINIMAP);
            }
        }
        else
        {
            minimapDrawWidget = client.getWidget(InterfaceID.Toplevel.MINIMAP);
        }

        if (minimapDrawWidget == null || minimapDrawWidget.isHidden())
        {
            return null;
        }
        if (!minimapDrawWidget.contains(lastMouseCanvasPosition))
        {
            return null;
        }
        LocalPoint localPoint = client.getLocalPlayer().getLocalLocation();
        if (localPoint == null)
        {
            return null;
        }
        double minimapZoom = client.getMinimapZoom();
        int locationSize = (int)Math.round(32.0 * minimapZoom);
        int widgetX = lastMouseCanvasPosition.getX() - minimapDrawWidget.getCanvasLocation().getX() - minimapDrawWidget.getWidth()/2;
        int widgetY = lastMouseCanvasPosition.getY() - minimapDrawWidget.getCanvasLocation().getY() - minimapDrawWidget.getHeight()/2;
        int angle = client.getCameraYawTarget();
        int sine = (int) (65536.0D * Math.sin((double) angle * 0.000383495196971D));
        int cosine = (int) (65536.0D * Math.cos((double) angle * 0.000383495196971D));
        int xx = cosine * widgetX + widgetY * sine >> 11;
        int yy = widgetY * cosine - widgetX * sine >> 11;
        int deltaX = xx/locationSize;
        int deltaY = -(yy/locationSize);
        return new Point(localPoint.getSceneX() + deltaX, localPoint.getSceneY() + deltaY);
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        if (event.getVarpId() == 173)
        {
            // Run toggled
            int[] varps = client.getVarps();
            isRunning = varps[173] == 1;
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (client.getLocalPlayer() == null)
        {
            return;
        }
        switch (event.getMenuAction())
        {
            case EXAMINE_ITEM_GROUND:
            case EXAMINE_NPC:
            case EXAMINE_OBJECT:
            case CANCEL:
            case CC_OP:
            case CC_OP_LOW_PRIORITY:
            case PLAYER_EIGHTH_OPTION:
            case WIDGET_CLOSE:
            case WIDGET_CONTINUE:
            case WIDGET_FIRST_OPTION:
            case WIDGET_SECOND_OPTION:
            case WIDGET_THIRD_OPTION:
            case WIDGET_FOURTH_OPTION:
            case WIDGET_FIFTH_OPTION:
            case WIDGET_TARGET:
            case WIDGET_TARGET_ON_WIDGET:
            case WIDGET_TYPE_1:
            case WIDGET_TYPE_4:
            case WIDGET_TYPE_5:
            case RUNELITE:
            case RUNELITE_HIGH_PRIORITY:
            case RUNELITE_INFOBOX:
            case RUNELITE_OVERLAY:
            case RUNELITE_OVERLAY_CONFIG:
            case RUNELITE_PLAYER:
                return;
            case GAME_OBJECT_FIRST_OPTION:
            case GAME_OBJECT_SECOND_OPTION:
            case GAME_OBJECT_THIRD_OPTION:
            case GAME_OBJECT_FOURTH_OPTION:
            case GAME_OBJECT_FIFTH_OPTION:
            case WIDGET_TARGET_ON_GAME_OBJECT:
            case GROUND_ITEM_FIRST_OPTION:
            case GROUND_ITEM_SECOND_OPTION:
            case GROUND_ITEM_THIRD_OPTION:
            case GROUND_ITEM_FOURTH_OPTION:
            case GROUND_ITEM_FIFTH_OPTION:
            case WIDGET_TARGET_ON_GROUND_ITEM:
            {
                int x = event.getParam0();
                int y = event.getParam1();
                int id = event.getId();
                int config = -1;
                int sizeX = 1;
                int sizeY = 1;
                TileObject tileObject = findTileObject(x, y, id);
                TileItem tileItem = findTileItem(x, y, id);
                if (tileObject == null && tileItem == null)
                {
                    return;
                }
                isRunning = willRunOnClick();
                if (tileObject != null)
                {
                    if (tileObject instanceof GameObject)
                    {
                        GameObject gameObject = (GameObject) tileObject;
                        config = gameObject.getConfig();
                        sizeX = gameObject.sizeX();
                        sizeY = gameObject.sizeY();
                    }
                    if (tileObject instanceof WallObject)
                    {
                        WallObject wallObject = (WallObject) tileObject;
                        config = wallObject.getConfig();
                    }
                    if (tileObject instanceof DecorativeObject)
                    {
                        DecorativeObject decorativeObject = (DecorativeObject) tileObject;
                        config = decorativeObject.getConfig();
                    }
                    if (tileObject instanceof GroundObject)
                    {
                        GroundObject groundObject = (GroundObject) tileObject;
                        config = groundObject.getConfig();
                    }
                }
                WorldPoint worldPoint = WorldPoint.fromScene(client.getLocalPlayer().getWorldView(), x, y, client.getLocalPlayer().getWorldView().getPlane());
                Pair<List<WorldPoint>, Boolean> pathResult = pathfinder.pathTo(x, y, sizeX, sizeY, config, id);
                activePathDestination = new PathDestination(worldPoint, sizeX, sizeY, config, id);
                if (pathResult == null)
                {
                    return;
                }
                lastTickWorldLocation = client.getLocalPlayer().getWorldLocation();
                pathActive = true;
                activeCheckpointWPs = pathResult.getLeft();
                activePathFound = pathResult.getRight();
                pathFromCheckpointTiles(activeCheckpointWPs, isRunning, activeMiddlePathTiles, activePathTiles, activePathFound);
                activePathStartedLastTick = true;
                calcTilePathOnNextClientTick = false;
                return;
            }
            case NPC_FIRST_OPTION:
            case NPC_SECOND_OPTION:
            case NPC_THIRD_OPTION:
            case NPC_FOURTH_OPTION:
            case NPC_FIFTH_OPTION:
            case WIDGET_TARGET_ON_NPC:
            case PLAYER_FIRST_OPTION:
            case PLAYER_SECOND_OPTION:
            case PLAYER_THIRD_OPTION:
            case PLAYER_FOURTH_OPTION:
            case PLAYER_FIFTH_OPTION:
            case PLAYER_SIXTH_OPTION:
            case PLAYER_SEVENTH_OPTION:
            case WIDGET_TARGET_ON_PLAYER:
            {
                Actor actor = event.getMenuEntry().getActor();
                if (actor == null)
                {
                    return;
                }
                isRunning = willRunOnClick();
                LocalPoint localPoint = LocalPoint.fromWorld(client.getLocalPlayer().getWorldView(), actor.getWorldLocation());
                if (localPoint == null)
                {
                    return;
                }
                int x = localPoint.getSceneX();
                int y = localPoint.getSceneY();
                int size = 1;
                if (actor instanceof NPC)
                {
                    size = ((NPC) actor).getComposition().getSize();
                }
                WorldPoint worldPoint = WorldPoint.fromScene(client.getLocalPlayer().getWorldView(), x, y, client.getLocalPlayer().getWorldView().getPlane());
                Pair<List<WorldPoint>, Boolean> pathResult = pathfinder.pathTo(x, y, size, size, -2, -1);
                activePathDestination = new PathDestination(worldPoint, size, size, -2, -1, actor);
                if (pathResult == null)
                {
                    return;
                }
                lastTickWorldLocation = client.getLocalPlayer().getWorldLocation();
                pathActive = true;
                activeCheckpointWPs = pathResult.getLeft();
                activePathFound = pathResult.getRight();
                pathFromCheckpointTiles(activeCheckpointWPs, isRunning, activeMiddlePathTiles, activePathTiles, activePathFound);
                activePathStartedLastTick = true;
                calcTilePathOnNextClientTick = false;
                return;
            }
            case WALK:
            default:
            {
                if (!client.isMenuOpen())
                {
                    calcTilePathOnNextClientTick = true;
                    return;
                }
                if (oldSelectedSceneTile == null)
                {
                    return;
                }
                isRunning = willRunOnClick();
                WorldPoint worldPoint = WorldPoint.fromScene(client.getLocalPlayer().getWorldView(), oldSelectedSceneTile.getSceneLocation().getX(), oldSelectedSceneTile.getSceneLocation().getY(), client.getLocalPlayer().getWorldView().getPlane());
                Pair<List<WorldPoint>, Boolean> pathResult = pathfinder.pathTo(oldSelectedSceneTile);
                activePathDestination = new PathDestination(worldPoint, 1, 1, -1, -1);
                if (pathResult == null)
                {
                    return;
                }
                lastTickWorldLocation = client.getLocalPlayer().getWorldLocation();
                pathActive = true;
                activeCheckpointWPs = pathResult.getLeft();
                activePathFound = pathResult.getRight();
                pathFromCheckpointTiles(activeCheckpointWPs, isRunning, activeMiddlePathTiles, activePathTiles, activePathFound);
                activePathStartedLastTick = true;
                calcTilePathOnNextClientTick = (event.getMenuAction() == MenuAction.WALK && !client.isMenuOpen());
            }
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        switch (event.getGameState())
        {
            case HOPPING:
            case LOGGING_IN:
            {
                activeCheckpointWPs.clear();
                activeCheckpointWPs.add(new WorldPoint(0,0,0));
                pathActive = false;
            }
        }
    }

    @Subscribe
    public void onClientTick(ClientTick event)
    {
        if (client.getLocalPlayer() == null)
        {
            return;
        }
        if (calcTilePathOnNextClientTick)
        {
            Tile selectedSceneTile = client.getLocalPlayer().getWorldView().getSelectedSceneTile();
            if (selectedSceneTile != null)
            {
                isRunning = willRunOnClick();
                WorldPoint worldPoint = WorldPoint.fromScene(client.getLocalPlayer().getWorldView(), selectedSceneTile.getSceneLocation().getX(), selectedSceneTile.getSceneLocation().getY(), client.getLocalPlayer().getWorldView().getPlane());
                Pair<List<WorldPoint>, Boolean> pathResult = pathfinder.pathTo(selectedSceneTile);
                activePathDestination = new PathDestination(worldPoint, 1, 1, -1, -1);
                if (pathResult != null)
                {
                    lastTickWorldLocation = client.getLocalPlayer().getWorldLocation();
                    pathActive = true;
                    activeCheckpointWPs = pathResult.getLeft();
                    activePathFound = pathResult.getRight();
                    pathFromCheckpointTiles(activeCheckpointWPs, isRunning, activeMiddlePathTiles, activePathTiles, activePathFound);
                    activePathStartedLastTick = true;
                    calcTilePathOnNextClientTick = false;
                }
            }
        }
        Tile selectedSceneTile = client.getLocalPlayer().getWorldView().getSelectedSceneTile();
        MenuEntry[] menuEntries = client.getMenu().getMenuEntries();
        if (menuEntries.length == 1 && !client.isMenuOpen()
            && (leftClicked || (config.hoverPathDisplaySetting() != HdTileMarkersConfig.PathDisplaySetting.NEVER)))
        {
            // Potential minimap hover/click. The route is only recalculated when the hovered minimap
            // tile or the player's tile changes, or on a click (HD Tile Markers change).
            Point point = minimapToScenePoint();
            WorldPoint from = client.getLocalPlayer().getWorldLocation();
            boolean sameMinimapRoute = point != null && point.equals(lastMinimapPoint) && from.equals(lastMinimapFrom);
            lastMinimapPoint = point;
            lastMinimapFrom = from;
            if (point != null && (leftClicked || !sameMinimapRoute))
            {
                Pair<List<WorldPoint>, Boolean> pathResult = pathfinder.pathTo(point.getX(), point.getY(), 1,1,-1,-1);
                if (pathResult != null)
                {
                    hoverCheckpointWPs = pathResult.getLeft();
                    hoverPathFound = pathResult.getRight();
                    if (hoverCheckpointWPs != null)
                    {
                        lastSelectedSceneTile = selectedSceneTile;
                    }
                    if (leftClicked)
                    {
                        isRunning = willRunOnClick();
                        activePathDestination = new PathDestination(WorldPoint.fromScene(client.getLocalPlayer().getWorldView(), point.getX(), point.getY(), client.getLocalPlayer().getWorldView().getPlane()), 1, 1, -1, -1);
                        lastTickWorldLocation = client.getLocalPlayer().getWorldLocation();
                        pathActive = true;
                        activeCheckpointWPs = new ArrayList<>(pathResult.getLeft());
                        activePathFound = pathResult.getRight();
                        pathFromCheckpointTiles(activeCheckpointWPs, isRunning, activeMiddlePathTiles, activePathTiles, activePathFound);
                        activePathStartedLastTick = true;
                    }
                }
            }
        }
        if (lastSelectedSceneTile==null || lastSelectedSceneTile!=selectedSceneTile
                || (client.isMenuOpen() && hoveredMenuEntry(menuEntries) != lastSelectedMenuEntry)
                || oldMenuEntries.length != menuEntries.length)
        {
            if (client.isMenuOpen())
            {
                lastSelectedMenuEntry = hoveredMenuEntry(menuEntries);
            }
            if (selectedSceneTile != null)
            {
                Pair<List<WorldPoint>, Boolean> pathResult = pathToHover();
                if (pathResult != null)
                {
                    hoverCheckpointWPs = pathResult.getLeft();
                    hoverPathFound = pathResult.getRight();
                }
            }
        }
        oldSelectedSceneTile = client.getLocalPlayer().getWorldView().getSelectedSceneTile();
        oldMenuEntries = menuEntries;
        leftClicked = false;
        lastMouseCanvasPosition=client.getMouseCanvasPosition();
        lastSelectedSceneTile = selectedSceneTile;
        // Hover tiles are only rebuilt when their route, run state or starting tile changed (HD Tile Markers change).
        boolean running = willRunOnClick();
        WorldPoint start = client.getLocalPlayer().getWorldLocation();
        if (hoverCheckpointWPs != lastHoverCheckpoints || running != lastHoverRunning || hoverPathFound != lastHoverFound
            || !start.equals(lastHoverStart))
        {
            pathFromCheckpointTiles(hoverCheckpointWPs, running, hoverMiddlePathTiles, hoverPathTiles, hoverPathFound);
            lastHoverCheckpoints = hoverCheckpointWPs;
            lastHoverRunning = running;
            lastHoverFound = hoverPathFound;
            lastHoverStart = start;
        }
    }

    private MenuEntry hoveredMenuEntry(final MenuEntry[] menuEntries)
    {
        final int menuX = client.getMenu().getMenuX();
        final int menuY = client.getMenu().getMenuY();
        final int menuWidth = client.getMenu().getMenuWidth();
        final Point mousePosition = client.getMouseCanvasPosition();

        int dy = mousePosition.getY() - menuY;
        dy -= 19; // Height of Choose Option
        if (dy < 0)
        {
            return menuEntries[0];
        }

        int idx = dy / 15; // Height of each menu option
        idx = menuEntries.length - 1 - idx;

        if (mousePosition.getX() > menuX && mousePosition.getX() < menuX + menuWidth
                && idx >= 0 && idx < menuEntries.length)
        {
            return menuEntries[idx];
        }
        return menuEntries[0];
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (client.getLocalPlayer() == null)
        {
            return;
        }
        LocalPoint localDestinationLocation = client.getLocalDestinationLocation();
        if (localDestinationLocation != null && pathActive && activePathFound)
        {
            WorldPoint worldDestinationLocation = WorldPoint.fromLocal(client, localDestinationLocation);
            if (!worldDestinationLocation.equals(activeCheckpointWPs.get(activeCheckpointWPs.size() - 1)))
            {
                Pair<List<WorldPoint>, Boolean> pathResult = pathfinder.pathTo(localDestinationLocation.getSceneX(), localDestinationLocation.getSceneY(), activePathDestination.sizeX, activePathDestination.sizeY, activePathDestination.objConfig, activePathDestination.objID);
                if (pathResult != null)
                {
                    activeCheckpointWPs = pathResult.getLeft();
                    activePathFound = pathResult.getRight();
                    activePathStartedLastTick = false;
                }
            }
        }
        updateNpcBlockings();
        WorldPoint currentWorldLocation = client.getLocalPlayer().getWorldLocation();
        if (lastTickWorldLocation == null || lastTickWorldLocation != currentWorldLocation)
        {
            Pair<List<WorldPoint>, Boolean> pathResult = pathToHover();
            if (pathResult != null)
            {
                hoverCheckpointWPs = pathResult.getLeft();
                hoverPathFound = pathResult.getRight();
            }
        }
        if (hoverCheckpointWPs !=null && hoverCheckpointWPs.size()>0)
        {
            pathFromCheckpointTiles(hoverCheckpointWPs, willRunOnClick(), hoverMiddlePathTiles, hoverPathTiles, hoverPathFound);
        }
        if ((activeCheckpointWPs.size() > 0 && currentWorldLocation.equals(activeCheckpointWPs.get(activeCheckpointWPs.size() - 1)))
                || (lastTickWorldLocation != null && currentWorldLocation.distanceTo(lastTickWorldLocation) > 2))
        {
            pathActive = false;
        }
        updateCheckpointTiles();
        if (pathActive && activePathDestination.objConfig == -2 && activeCheckpointWPs.size()<2)
        {
            // Path is recalculated when there's <2 checkpoint tiles remaining when pathing to a NPC/player
            Actor actor = activePathDestination.actor;
            if (actor != null) {
                LocalPoint localPoint = LocalPoint.fromWorld(client.getLocalPlayer().getWorldView(), actor.getWorldLocation());
                if (localPoint != null)
                {
                    Pair<List<WorldPoint>, Boolean> pathResult = pathfinder.pathTo(localPoint.getSceneX(), localPoint.getSceneY(), activePathDestination.sizeX, activePathDestination.sizeY, activePathDestination.objConfig, activePathDestination.objID);
                    if (pathResult != null)
                    {
                        pathActive = true;
                        activeCheckpointWPs = pathResult.getLeft();
                        activePathFound = pathResult.getRight();
                        activePathStartedLastTick = false;
                    }
                }
            }
        }
        pathFromCheckpointTiles(activeCheckpointWPs, isRunning, activeMiddlePathTiles, activePathTiles, activePathFound);
        lastTickWorldLocation = currentWorldLocation;
    }

    /** A path tile to draw in the game world. */
    public static final class SceneTile
    {
        public final WorldPoint point;
        public final java.awt.Color stroke, fill;
        public final boolean dot, active;

        public SceneTile(WorldPoint point, java.awt.Color stroke, java.awt.Color fill, boolean dot, boolean active)
        { this.point = point; this.stroke = stroke; this.fill = fill; this.dot = dot; this.active = active; }
    }

    /**
     * Tiles to draw in the game world, hover path first. The display conditions
     * are those of PathMarkerOverlay.render; HD Tile Markers draws the result.
     */
    public List<SceneTile> sceneTiles()
    {
        List<SceneTile> tiles = new ArrayList<>();
        if (client.getLocalPlayer() == null || hoverPathTiles == null)
        {
            return tiles;
        }
        if ((config.hoverPathDisplaySetting() != HdTileMarkersConfig.PathDisplaySetting.NEVER)
                && (config.activePathDisplaySetting() == HdTileMarkersConfig.PathDisplaySetting.NEVER || !isPathActive() || !config.drawOnlyIfNoActivePath())
                && (isKeyDisplayHoverPath() || config.hoverPathDisplaySetting() == HdTileMarkersConfig.PathDisplaySetting.ALWAYS)
                && gameWorld(config.hoverPathDrawLocations()))
        {
            boolean dot = config.hoverPathMarkerStyle() == HdTileMarkersConfig.MarkerStyle.DOT;
            addTiles(tiles, hoverPathTiles, hoverPathTiles, config.hoverPathDrawMode(), config.hoverPathStroke1(), config.hoverPathFill1(), dot, false);
            addTiles(tiles, hoverMiddlePathTiles, hoverPathTiles, config.hoverPathDrawMode(), config.hoverPathStroke2(), config.hoverPathFill2(), dot, false);
        }
        if (config.activePathDisplaySetting() != HdTileMarkersConfig.PathDisplaySetting.NEVER && isPathActive()
                && (isKeyDisplayActivePath() || config.activePathDisplaySetting() == HdTileMarkersConfig.PathDisplaySetting.ALWAYS)
                && gameWorld(config.activePathDrawLocations()))
        {
            boolean dot = config.activePathMarkerStyle() == HdTileMarkersConfig.MarkerStyle.DOT;
            addTiles(tiles, activePathTiles, activePathTiles, config.activePathDrawMode(), config.activePathStroke1(), config.activePathFill1(), dot, true);
            addTiles(tiles, activeMiddlePathTiles, activePathTiles, config.activePathDrawMode(), config.activePathStroke2(), config.activePathFill2(), dot, true);
        }
        return tiles;
    }

    private static boolean gameWorld(HdTileMarkersConfig.DrawLocations locations)
    {
        return locations == HdTileMarkersConfig.DrawLocations.BOTH || locations == HdTileMarkersConfig.DrawLocations.GAME_WORLD;
    }

    private static void addTiles(List<SceneTile> out, List<WorldPoint> points, List<WorldPoint> main, HdTileMarkersConfig.DrawMode mode,
        java.awt.Color stroke, java.awt.Color fill, boolean dot, boolean active)
    {
        for (WorldPoint worldPoint : points)
        {
            if (mode == HdTileMarkersConfig.DrawMode.FULL_PATH || (!main.isEmpty() && worldPoint == main.get(main.size() - 1)))
            {
                out.add(new SceneTile(worldPoint, stroke, fill, dot, active));
            }
        }
    }

    // Accessors replacing the original Lombok annotations.
    public void setCtrlHeld(boolean ctrlHeld) { this.ctrlHeld = ctrlHeld; }
    public List<WorldPoint> getActiveCheckpointWPs() { return activeCheckpointWPs; }
    public boolean isKeyDisplayActivePath() { return keyDisplayActivePath; }
    public void setKeyDisplayActivePath(boolean keyDisplayActivePath) { this.keyDisplayActivePath = keyDisplayActivePath; }
    public boolean isKeyDisplayHoverPath() { return keyDisplayHoverPath; }
    public void setKeyDisplayHoverPath(boolean keyDisplayHoverPath) { this.keyDisplayHoverPath = keyDisplayHoverPath; }
    public void setLeftClicked(boolean leftClicked) { this.leftClicked = leftClicked; }
    public boolean isPathActive() { return pathActive; }
    public List<WorldPoint> getHoverPathTiles() { return hoverPathTiles; }
    public List<WorldPoint> getHoverMiddlePathTiles() { return hoverMiddlePathTiles; }
    public List<WorldPoint> getActivePathTiles() { return activePathTiles; }
    public List<WorldPoint> getActiveMiddlePathTiles() { return activeMiddlePathTiles; }
    public void setLastMouseCanvasPosition(Point lastMouseCanvasPosition) { this.lastMouseCanvasPosition = lastMouseCanvasPosition; }
}
