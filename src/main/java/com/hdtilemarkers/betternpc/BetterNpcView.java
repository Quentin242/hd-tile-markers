/*
 * Copyright (c) 2022, Buchus <http://github.com/MoreBuchus>
 * Copyright (c) 2023, geheur <http://github.com/geheur>
 * Copyright (c) 2021, LeikvollE <http://github.com/LeikvollE>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
/*
 * Adapted for HD Tile Markers from BetterNpcHighlightOverlay (Better NPC Highlight,
 * https://github.com/riktenx/better-npc-highlight, commit bf59bfb9a616897e9ffcd14d0d2b543e4c119b09).
 * Changes: no longer an overlay. visit() is the original selection loop, render2d() the original
 * drawing of one style (used for styles HD Tile Markers cannot draw in the scene and as the 2D fallback),
 * renderExtras() the original names and respawn timers. Draw-beneath is not included.
 */
package com.hdtilemarkers.betternpc;

import com.hdtilemarkers.betternpc.BetterNpcHighlightConfig.tagStyleMode;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import net.runelite.api.Point;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

import javax.inject.Inject;
import javax.inject.Singleton;


import java.awt.*;
import java.time.Instant;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;


@Singleton
public class BetterNpcView
{
	/** Receives each highlight style an NPC should get, as decided by Better NPC Highlight. */
	public interface Visitor
	{
		void accept(NPCInfo npcInfo, String style);
	}

	@Inject
	private Client client;

	private BetterNpcHighlightConfig config;

	// Read from ConfigManager, not bound in HD Tile Markers' injector: RuneLite takes a plugin's Config bindings as
	// its own settings panel and fills in their defaults, which must never touch Better NPC Highlight's.
	@Inject
	void readConfig(net.runelite.client.config.ConfigManager configManager)
	{
		config = configManager.getConfig(BetterNpcHighlightConfig.class);
	}

	@Inject
	private ModelOutlineRenderer modelOutlineRenderer;

	@Inject
	private NpcUtil npcUtil;

	@Inject
	private ColorManager colorManager;

	@Inject
	private NameAndIdContainer nameAndIdContainer;

	@Inject
	private RespawnManager respawnManager;

	public BetterNpcHighlightConfig config()
	{
		return config;
	}

	public ColorManager colors()
	{
		return colorManager;
	}

	/** The selection of the original render loop, reporting each style instead of drawing it. */
	public void visit(Visitor visitor)
	{
		for (NPCInfo npcInfo : nameAndIdContainer.getNpcList())
		{
			NPC npc = npcInfo.getNpc();
			NPCComposition npcComposition = npc.getTransformedComposition();
			if (npcComposition != null && ((npc.getName() != null && !npc.getName().equals("") && !npc.getName().equals("null")) || !isInvisible(npc.getModel())))
			{
				boolean showWhileDead = (!npc.isDead() && !npcUtil.isDying(npc)) || !config.ignoreDeadNpcs() || npcInfo.isIgnoreDead();
				boolean showNPC = (npcComposition.isFollower() && config.highlightPets()) || (!npcComposition.isFollower() && showWhileDead);

				if (showNPC && withinDistanceLimit(npc))
				{
					if (colorManager.shouldUseSlayerHighlight(npcInfo))
					{
						for (tagStyleMode mode : tagStyleMode.values())
						{
							if (config.taskHighlightStyle().contains(mode))
							{
								visitor.accept(npcInfo, mode.getKey());
							}
						}
					}
					else
					{
						if (config.tileHighlight() && npcInfo.getTile().isHighlight())
						{
							visitor.accept(npcInfo, "tile");
						}

						if (config.trueTileHighlight() && npcInfo.getTrueTile().isHighlight())
						{
							visitor.accept(npcInfo, "trueTile");
						}

						if (config.swTileHighlight() && npcInfo.getSwTile().isHighlight())
						{
							visitor.accept(npcInfo, "swTile");
						}

						if (config.swTrueTileHighlight() && npcInfo.getSwTrueTile().isHighlight())
						{
							visitor.accept(npcInfo, "swTrueTile");
						}

						if (config.hullHighlight() && npcInfo.getHull().isHighlight())
						{
							visitor.accept(npcInfo, "hull");
						}

						if (config.areaHighlight() && npcInfo.getArea().isHighlight())
						{
							visitor.accept(npcInfo, "area");
						}

						if (config.outlineHighlight() && npcInfo.getOutline().isHighlight())
						{
							visitor.accept(npcInfo, "outline");
						}

						if (config.clickboxHighlight() && npcInfo.getClickbox().isHighlight())
						{
							visitor.accept(npcInfo, "clickbox");
						}
					}
				}
			}
		}
	}

	/** A respawn tile: where a memorized NPC will respawn, centred on its footprint. */
	public static final class RespawnTile
	{
		public final int npcIndex, size, plane;
		public final LocalPoint center;

		public RespawnTile(int npcIndex, LocalPoint center, int size, int plane)
		{
			this.npcIndex = npcIndex;
			this.center = center;
			this.size = size;
			this.plane = plane;
		}
	}

	/** The respawn tiles the original overlay would draw, with the positions of renderNpcRespawn. */
	public java.util.List<RespawnTile> respawnTiles()
	{
		java.util.List<RespawnTile> tiles = new java.util.ArrayList<>();
		if (config.respawnTimer() == BetterNpcHighlightConfig.respawnTimerMode.OFF)
		{
			return tiles;
		}
		for (MemorizedNpc npc : respawnManager.getDeadNpcsToDisplay().values())
		{
			LocalPoint center = respawnCenter(npc);
			if (center != null)
			{
				tiles.add(new RespawnTile(npc.getNpcIndex(), center, npc.getNpcSize(), npc.getPossibleRespawnLocations().get(0).getPlane()));
			}
		}
		return tiles;
	}

	private LocalPoint respawnCenter(MemorizedNpc npc)
	{
		if (npc.getPossibleRespawnLocations().isEmpty())
		{
			return null;
		}
		final WorldPoint respawnLocation = npc.getPossibleRespawnLocations().get(0);
		final LocalPoint lp = LocalPoint.fromWorld(client, respawnLocation.getX(), respawnLocation.getY());
		if (lp == null)
		{
			return null;
		}
		return new LocalPoint(
				lp.getX() + Perspective.LOCAL_TILE_SIZE * (npc.getNpcSize() - 1) / 2,
				lp.getY() + Perspective.LOCAL_TILE_SIZE * (npc.getNpcSize() - 1) / 2);
	}

	/** Names above NPCs and respawn timers, as the original overlay draws them. */
	public void renderExtras(Graphics2D graphics)
	{
		renderExtras(graphics, index -> false);
	}

	/** As renderExtras; tileInScene tells which respawn tiles HD Tile Markers draws in the scene, so only their text is drawn here. */
	public void renderExtras(Graphics2D graphics, java.util.function.IntPredicate tileInScene)
	{
		for (NPCInfo npcInfo : nameAndIdContainer.getNpcList())
		{
			NPC npc = npcInfo.getNpc();
			NPCComposition npcComposition = npc.getTransformedComposition();
			if (npcComposition != null && ((npc.getName() != null && !npc.getName().equals("") && !npc.getName().equals("null")) || !isInvisible(npc.getModel())))
			{
				boolean showWhileDead = (!npc.isDead() && !npcUtil.isDying(npc)) || !config.ignoreDeadNpcs() || npcInfo.isIgnoreDead();
				boolean showNPC = (npcComposition.isFollower() && config.highlightPets()) || (!npcComposition.isFollower() && showWhileDead);
				if (showNPC && withinDistanceLimit(npc))
				{
					if (nameAndIdContainer.getNamesToDisplay().size() > 0 && npc.getName() != null)
					{
						for (String str : nameAndIdContainer.getNamesToDisplay())
						{
							if (WildcardMatcher.matches(str, npc.getName().toLowerCase()))
							{
								String text = Text.removeTags(npc.getName());
								Point textLoc = npc.getCanvasTextLocation(graphics, text, npc.getLogicalHeight() + 40);
								if (textLoc != null)
								{
									drawTextBackground(graphics, textLoc, text);
									OverlayUtil.renderTextLocation(graphics, textLoc, text, colorManager.getSpecificColor(npcInfo));
									break;
								}
							}
						}
					}
				}
			}
		}

		if (config.respawnTimer() != BetterNpcHighlightConfig.respawnTimerMode.OFF)
		{
			for (MemorizedNpc npc : respawnManager.getDeadNpcsToDisplay().values())
			{
				renderNpcRespawn(graphics, npc, tileInScene.test(npc.getNpcIndex()));
			}
		}
	}

	/** The original drawing of one style, in 2D. */
	public void render2d(Graphics2D graphics, NPCInfo npcInfo, String highlight)
	{
		NPC npc = npcInfo.getNpc();
		NPCComposition npcComposition = npc.getTransformedComposition();
		if (npcComposition != null)
		{
			int size = npcComposition.getSize();
			Polygon tilePoly;
			LocalPoint lp;
			Color line;
			Color fill;
			int lineAlpha;
			int fillAlpha;
			boolean antialias;
			boolean isTask = colorManager.shouldUseSlayerHighlight(npcInfo);

			switch (highlight)
			{
				case "hull":
					line = colorManager.resolveColor(isTask, config.taskColor(), npcInfo.getHull().getColor(), config.hullRave(), config.hullRaveSpeed());
					fill = colorManager.resolveColor(isTask, config.taskFillColor(), npcInfo.getHull().getFill(), config.hullRave(), config.hullRaveSpeed());
					lineAlpha = isTask ? config.taskColor().getAlpha() : npcInfo.getHull().getColor().getAlpha();
					fillAlpha = isTask ? config.taskFillColor().getAlpha() : npcInfo.getHull().getFill().getAlpha();
					antialias = isTask ? config.slayerAA() : config.hullAA();

					Shape hull = npc.getConvexHull();
					if (hull != null)
					{
						renderPoly(graphics, line, fill, lineAlpha, fillAlpha, hull, config.hullWidth(), antialias);
					}
					break;
				case "tile":
					line = colorManager.resolveColor(isTask, config.taskColor(), npcInfo.getTile().getColor(), config.tileRave(), config.tileRaveSpeed());
					fill = colorManager.resolveColor(isTask, config.taskFillColor(), npcInfo.getTile().getFill(), config.tileRave(), config.tileRaveSpeed());
					lineAlpha = isTask ? config.taskColor().getAlpha() : npcInfo.getTile().getColor().getAlpha();
					fillAlpha = isTask ? config.taskFillColor().getAlpha() : npcInfo.getTile().getFill().getAlpha();
					antialias = isTask ? config.slayerAA() : config.tileAA();

					lp = npc.getLocalLocation();
					if (lp != null)
					{
						tilePoly = Perspective.getCanvasTileAreaPoly(client, lp, size);
						if (tilePoly != null)
						{
							switch (config.tileLines())
							{
								case REG:
									renderPoly(graphics, line, fill, lineAlpha, fillAlpha, tilePoly, config.tileWidth(), antialias);
									break;
								case DASH:
									renderPolygonDashed(graphics, line, fill, lineAlpha, fillAlpha, tilePoly, config.tileWidth(), size, antialias);
									break;
								case CORNER:
									renderPolygonCorners(graphics, line, fill, lineAlpha, fillAlpha, tilePoly, config.tileWidth(), antialias);
									break;
							}
						}
					}
					break;
				case "trueTile":
					line = colorManager.resolveColor(isTask, config.taskColor(), npcInfo.getTrueTile().getColor(), config.trueTileRave(), config.trueTileRaveSpeed());
					fill = colorManager.resolveColor(isTask, config.taskFillColor(), npcInfo.getTrueTile().getFill(), config.trueTileRave(), config.trueTileRaveSpeed());
					lineAlpha = isTask ? config.taskColor().getAlpha() : npcInfo.getTrueTile().getColor().getAlpha();
					fillAlpha = isTask ? config.taskFillColor().getAlpha() : npcInfo.getTrueTile().getFill().getAlpha();
					antialias = isTask ? config.slayerAA() : config.trueTileAA();

					lp = LocalPoint.fromWorld(client, npc.getWorldLocation());
					if (lp != null)
					{
						lp = new LocalPoint(lp.getX() + size * 128 / 2 - 64, lp.getY() + size * 128 / 2 - 64);
						tilePoly = Perspective.getCanvasTileAreaPoly(client, lp, size);
						if (tilePoly != null)
						{
							switch (config.trueTileLines())
							{
								case REG:
									renderPoly(graphics, line, fill, lineAlpha, fillAlpha, tilePoly, config.trueTileWidth(), antialias);
									break;
								case DASH:
									renderPolygonDashed(graphics, line, fill, lineAlpha, fillAlpha, tilePoly, config.trueTileWidth(), size, antialias);
									break;
								case CORNER:
									renderPolygonCorners(graphics, line, fill, lineAlpha, fillAlpha, tilePoly, config.trueTileWidth(), antialias);
									break;
							}
						}
					}
					break;
				case "swTile":
					line = colorManager.resolveColor(isTask, config.taskColor(), npcInfo.getSwTile().getColor(), config.swTileRave(), config.swTileRaveSpeed());
					fill = colorManager.resolveColor(isTask, config.taskFillColor(), npcInfo.getSwTile().getFill(), config.swTileRave(), config.swTileRaveSpeed());
					lineAlpha = isTask ? config.taskColor().getAlpha() : npcInfo.getSwTile().getColor().getAlpha();
					fillAlpha = isTask ? config.taskFillColor().getAlpha() : npcInfo.getSwTile().getFill().getAlpha();
					antialias = isTask ? config.slayerAA() : config.swTileAA();

					lp = npc.getLocalLocation();
					if (lp != null)
					{
						int x = lp.getX() - (size - 1) * 128 / 2;
						int y = lp.getY() - (size - 1) * 128 / 2;
						tilePoly = Perspective.getCanvasTilePoly(client, new LocalPoint(x, y));
						if (tilePoly != null)
						{
							switch (config.swTileLines())
							{
								case REG:
									renderPoly(graphics, line, fill, lineAlpha, fillAlpha, tilePoly, config.swTileWidth(), antialias);
									break;
								case DASH:
									renderPolygonDashed(graphics, line, fill, lineAlpha, fillAlpha, tilePoly, config.swTileWidth(), size, antialias);
									break;
								case CORNER:
									renderPolygonCorners(graphics, line, fill, lineAlpha, fillAlpha, tilePoly, config.swTileWidth(), antialias);
									break;
							}
						}
					}
					break;
				case "swTrueTile":
					line = colorManager.resolveColor(isTask, config.taskColor(), npcInfo.getSwTrueTile().getColor(), config.swTrueTileRave(), config.swTrueTileRaveSpeed());
					fill = colorManager.resolveColor(isTask, config.taskFillColor(), npcInfo.getSwTrueTile().getFill(), config.swTrueTileRave(), config.swTrueTileRaveSpeed());
					lineAlpha = isTask ? config.taskColor().getAlpha() : npcInfo.getSwTrueTile().getColor().getAlpha();
					fillAlpha = isTask ? config.taskFillColor().getAlpha() : npcInfo.getSwTrueTile().getFill().getAlpha();
					antialias = isTask ? config.slayerAA() : config.swTrueTileAA();

					lp = LocalPoint.fromWorld(client, npc.getWorldLocation());
					if (lp != null)
					{
						tilePoly = Perspective.getCanvasTilePoly(client, lp);
						if (tilePoly != null)
						{
							switch (config.swTrueTileLines())
							{
								case REG:
									renderPoly(graphics, line, fill, lineAlpha, fillAlpha, tilePoly, config.swTrueTileWidth(), antialias);
									break;
								case DASH:
									renderPolygonDashed(graphics, line, fill, lineAlpha, fillAlpha, tilePoly, config.swTrueTileWidth(), size, antialias);
									break;
								case CORNER:
									renderPolygonCorners(graphics, line, fill, lineAlpha, fillAlpha, tilePoly, config.swTrueTileWidth(), antialias);
									break;
							}
						}
					}
					break;
				case "outline":
					line = colorManager.resolveColor(isTask, config.taskColor(), npcInfo.getOutline().getColor(), config.outlineRave(), config.outlineRaveSpeed());

					modelOutlineRenderer.drawOutline(npc, config.outlineWidth(), line, config.outlineFeather());
					break;
				case "area":
					Color color = npcInfo.getArea().getFill() != null ? npcInfo.getArea().getFill() : npcInfo.getArea().getColor();
					fill = colorManager.resolveColor(isTask, config.taskFillColor(), color, config.areaRave(), config.areaRaveSpeed());
					fillAlpha = isTask ? config.taskFillColor().getAlpha() : color.getAlpha();

					Shape area = npc.getConvexHull();
					if (area != null)
					{
						graphics.setColor(fill.getAlpha() == 0 ? new Color(fill.getRed(), fill.getGreen(), fill.getGreen(), 50)
							: new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), fillAlpha));
						graphics.fill(area);
					}
					break;
				case "clickbox":
					line = colorManager.resolveColor(isTask, config.taskColor(), npcInfo.getClickbox().getColor(), config.clickboxRave(), config.clickboxRaveSpeed());
					fill = colorManager.resolveColor(isTask, config.taskFillColor(), npcInfo.getClickbox().getFill(), config.clickboxRave(), config.clickboxRaveSpeed());
					lineAlpha = isTask ? config.taskColor().getAlpha() : npcInfo.getClickbox().getColor().getAlpha();
					fillAlpha = isTask ? config.taskFillColor().getAlpha() : npcInfo.getClickbox().getFill().getAlpha();

					lp = npc.getLocalLocation();
					if (lp != null)
					{
						Shape clickbox = Perspective.getClickbox(client, npc.getWorldView(), npc.getModel(), npc.getCurrentOrientation(), lp.getX(), lp.getY(),
							Perspective.getTileHeight(client, lp, npc.getWorldLocation().getPlane()));
						renderClickbox(graphics, clickbox, client.getMouseCanvasPosition(), line, fill, lineAlpha, fillAlpha, line.darker(), config.clickboxAA());
					}
					break;
			}
		}
	}

	private void renderNpcRespawn(Graphics2D graphics, MemorizedNpc npc, boolean tileInScene) {
		if (npc.getPossibleRespawnLocations().isEmpty())
		{
			return;
		}

		final WorldPoint respawnLocation = npc.getPossibleRespawnLocations().get(0);
		final LocalPoint lp = LocalPoint.fromWorld(client, respawnLocation.getX(), respawnLocation.getY());

		if (lp == null)
		{
			return;
		}

		final LocalPoint centerLp = new LocalPoint(
				lp.getX() + Perspective.LOCAL_TILE_SIZE * (npc.getNpcSize() - 1) / 2,
				lp.getY() + Perspective.LOCAL_TILE_SIZE * (npc.getNpcSize() - 1) / 2);

		Polygon tilePoly = Perspective.getCanvasTileAreaPoly(client, centerLp, npc.getNpcSize());
		if (tilePoly != null && !tileInScene)
		{
			renderPoly(graphics, config.respawnOutlineColor(), config.respawnFillColor(),
					config.respawnOutlineColor().getAlpha(), config.respawnFillColor().getAlpha(), tilePoly, config.respawnTileWidth(), true);
		}

		String text;
		if (config.respawnTimer() == BetterNpcHighlightConfig.respawnTimerMode.SECONDS)
		{
			final Instant now = Instant.now();
			final double baseTick = (npc.getDiedOnTick() + npc.getRespawnTime() - client.getTickCount()) * (Constants.GAME_TICK_LENGTH / 1000.0);
			final double sinceLast = (now.toEpochMilli() - respawnManager.getLastTickUpdate().toEpochMilli()) / 1000.0;
			final double timeLeft = Math.max(0, baseTick - sinceLast);
			text = String.valueOf(timeLeft);
			if (text.contains("."))
			{
				text = text.substring(0, text.indexOf(".") + 2);
			}
		}
		else
		{
			text = String.valueOf(Math.max(0, npc.getDiedOnTick() + npc.getRespawnTime() - client.getTickCount()));
		}

		Point textLoc = Perspective.getCanvasTextLocation(client, graphics, centerLp, text, 0);
		if (textLoc != null)
		{
			drawTextBackground(graphics, textLoc, text);
			OverlayUtil.renderTextLocation(graphics, textLoc, text, config.respawnTimerColor());
		}
	}

	private void renderPoly(Graphics2D graphics, Color outlineColor, Color fillColor, int lineAlpha, int fillAlpha, Shape polygon, double width, boolean antiAlias)
	{
		if (polygon != null)
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antiAlias ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
			graphics.setColor(new Color(outlineColor.getRed(), outlineColor.getGreen(), outlineColor.getBlue(), lineAlpha));
			graphics.setStroke(new BasicStroke((float) width));
			graphics.draw(polygon);
			graphics.setColor(new Color(fillColor.getRed(), fillColor.getGreen(), fillColor.getBlue(), fillAlpha));
			graphics.fill(polygon);
		}
	}

	public static void renderClickbox(Graphics2D graphics, Shape area, Point mousePosition, Color line, Color fill, int lineAlpha, int fillAlpha, Color hovered, boolean antiAlias)
	{
		if (area != null)
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antiAlias ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
			graphics.setStroke(new BasicStroke(1));
			if (area.contains(mousePosition.getX(), mousePosition.getY()))
			{
				graphics.setColor(new Color(hovered.getRed(), hovered.getGreen(), hovered.getBlue(), lineAlpha));
			}
			else
			{
				graphics.setColor(new Color(line.getRed(), line.getGreen(), line.getBlue(), lineAlpha));
			}
			graphics.draw(area);
			graphics.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), fillAlpha));
			graphics.fill(area);
		}
	}

	private static void renderPolygonCorners(Graphics2D graphics, Color outlineColor, Color fillColor, int lineAlpha, int fillAlpha, Shape poly, double width, boolean antiAlias)
	{
		if (poly instanceof Polygon)
		{
			Polygon p = (Polygon) poly;
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antiAlias ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
			graphics.setColor(new Color(outlineColor.getRed(), outlineColor.getGreen(), outlineColor.getBlue(), lineAlpha));
			graphics.setStroke(new BasicStroke((float) width));

			int divisor = 7;
			for (int i = 0; i < p.npoints; i++)
			{
				int ptx = p.xpoints[i];
				int pty = p.ypoints[i];
				int prev = (i - 1) < 0 ? 3 : (i - 1);
				int next = (i + 1) > 3 ? 0 : (i + 1);
				int ptxN = ((p.xpoints[next]) - ptx) / divisor + ptx;
				int ptyN = ((p.ypoints[next]) - pty) / divisor + pty;
				int ptxP = ((p.xpoints[prev]) - ptx) / divisor + ptx;
				int ptyP = ((p.ypoints[prev]) - pty) / divisor + pty;
				graphics.drawLine(ptx, pty, ptxN, ptyN);
				graphics.drawLine(ptx, pty, ptxP, ptyP);
			}

			graphics.setColor(new Color(fillColor.getRed(), fillColor.getGreen(), fillColor.getBlue(), fillAlpha));
			graphics.fill(poly);
		}
	}

	private static void renderPolygonDashed(Graphics2D graphics, Color outlineColor, Color fillColor, int lineAlpha, int fillAlpha, Shape poly,
											double width, int tiles, boolean antiAlias)
	{
		if (poly instanceof Polygon)
		{
			Polygon p = (Polygon) poly;
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antiAlias ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
			graphics.setColor(new Color(outlineColor.getRed(), outlineColor.getGreen(), outlineColor.getBlue(), lineAlpha));
			graphics.setStroke(new BasicStroke((float) width));

			int divisor = 7 * tiles;
			for (int i = 0; i < p.npoints; i++)
			{
				int ptx = p.xpoints[i];
				int pty = p.ypoints[i];
				int next = (i + 1) > 3 ? 0 : (i + 1);
				int ptxN = (p.xpoints[next]) - ptx;
				int ptyN = (p.ypoints[next]) - pty;
				float length = (float) Point2D.distance(ptx, pty, ptx + ptxN, pty + ptyN);
				float dashLength = length * 2f / divisor;
				float spaceLength = length * 5f / divisor;
				Stroke s = new BasicStroke((float) width, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10, new float[]{dashLength, spaceLength}, dashLength / 2);
				graphics.setStroke(s);
				graphics.drawLine(ptx, pty, ptx + ptxN, pty + ptyN);
			}

			graphics.setColor(new Color(fillColor.getRed(), fillColor.getGreen(), fillColor.getBlue(), fillAlpha));
			graphics.fill(poly);
		}
	}

	private void drawTextBackground(Graphics2D graphics, Point textLoc, String text)
	{
		switch (config.fontBackground())
		{
			case OUTLINE:
			{
				OverlayUtil.renderTextLocation(graphics, new Point(textLoc.getX(), textLoc.getY() + 1), text, Color.BLACK);
				OverlayUtil.renderTextLocation(graphics, new Point(textLoc.getX(), textLoc.getY() - 1), text, Color.BLACK);
				OverlayUtil.renderTextLocation(graphics, new Point(textLoc.getX() + 1, textLoc.getY()), text, Color.BLACK);
				OverlayUtil.renderTextLocation(graphics, new Point(textLoc.getX() - 1, textLoc.getY()), text, Color.BLACK);
				break;
			}
			case SHADOW:
			{
				OverlayUtil.renderTextLocation(graphics, new Point(textLoc.getX() + 1, textLoc.getY() + 1), text, Color.BLACK);
				break;
			}
			default:
				break;
		}
	}

	private static boolean isInvisible(Model model)
	{
		// If all the values in model.getFaceColors3() are -1 then the model is invisible
		for (int value : model.getFaceColors3())
		{
			if (value != -1)
			{
				return false;
			}
		}
		return true;
	}

	private boolean withinDistanceLimit(NPC npc)
	{
		final int maxDistance = config.renderDistance().getDistance();
		return maxDistance == 0 || npc.getWorldArea().distanceTo(client.getLocalPlayer().getWorldArea()) - 1 <= maxDistance;
	}
}
