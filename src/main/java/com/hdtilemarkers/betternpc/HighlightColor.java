/*
 * Adapted from Better NPC Highlight, https://github.com/riktenx/better-npc-highlight,
 * commit bf59bfb9a616897e9ffcd14d0d2b543e4c119b09. BSD 2-Clause License, see
 * META-INF/LICENSE-better-npc-highlight and THIRD_PARTY_NOTICES.md. Changes for HD
 * Tile Markers: package only.
 */
package com.hdtilemarkers.betternpc;

import java.awt.Color;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class HighlightColor {
	boolean isHighlight;
	Color color;
	Color fill;
}