/*
 * Adapted from Better NPC Highlight, https://github.com/riktenx/better-npc-highlight,
 * commit bf59bfb9a616897e9ffcd14d0d2b543e4c119b09. BSD 2-Clause License, see
 * META-INF/LICENSE-better-npc-highlight and THIRD_PARTY_NOTICES.md. Changes for Better
 * Indicator Renderer: package only.
 */
package com.hdtilemarkers.betternpc;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.coords.WorldPoint;

/**
 * Tracks a highlighted NPC across its death/respawn cycle so its respawn
 * location and time can be learned and displayed.
 */
@Getter
public class MemorizedNpc {
	private final int npcIndex;
	private final String npcName;
	private final int npcSize;

	/**
	 * The game tick the NPC died at, or -1 while it is alive.
	 */
	@Setter
	private int diedOnTick;

	/**
	 * The observed time (in game ticks) it takes the NPC to respawn, or -1 when
	 * unknown.
	 */
	@Setter
	private int respawnTime;

	/**
	 * Candidate tiles where the NPC may respawn. This is narrowed down on each
	 * respawn until the true spawn tile remains.
	 */
	@Setter
	private List<WorldPoint> possibleRespawnLocations;

	public MemorizedNpc(NPC npc) {
		this.npcName = npc.getName();
		this.npcIndex = npc.getIndex();
		this.possibleRespawnLocations = new ArrayList<>(2);
		this.respawnTime = -1;
		this.diedOnTick = -1;

		final NPCComposition composition = npc.getTransformedComposition();
		this.npcSize = composition != null ? composition.getSize() : 1;
	}
}