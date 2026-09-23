/*
 * Adapted from Better NPC Highlight, https://github.com/riktenx/better-npc-highlight,
 * commit bf59bfb9a616897e9ffcd14d0d2b543e4c119b09. BSD 2-Clause License, see
 * META-INF/LICENSE-better-npc-highlight and THIRD_PARTY_NOTICES.md. Changes for HD
 * Tile Markers: package only.
 */
package com.hdtilemarkers.betternpc;

import lombok.Getter;
import net.runelite.api.NPC;

@Getter
public class NPCInfo {
	private final NPC npc;
	private final HighlightColor tile;
	private final HighlightColor trueTile;
	private final HighlightColor swTile;
	private final HighlightColor swTrueTile;
	private final HighlightColor hull;
	private final HighlightColor area;
	private final HighlightColor outline;
	private final HighlightColor clickbox;
	private final boolean isTask;
	private final boolean ignoreDead;

	public NPCInfo(NPC npc, HighlightColor tile, HighlightColor trueTile, HighlightColor swTile, HighlightColor swTrueTile,
			HighlightColor hull, HighlightColor area, HighlightColor outline, HighlightColor clickbox, boolean isTask, boolean ignoreDead) {
		this.npc = npc;
		this.tile = tile;
		this.trueTile = trueTile;
		this.swTile = swTile;
		this.swTrueTile = swTrueTile;
		this.hull = hull;
		this.area = area;
		this.outline = outline;
		this.clickbox = clickbox;
		this.isTask = isTask;
		this.ignoreDead = ignoreDead;
	}
}