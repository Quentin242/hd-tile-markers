/*
 * Adapted from Better NPC Highlight, https://github.com/riktenx/better-npc-highlight,
 * commit bf59bfb9a616897e9ffcd14d0d2b543e4c119b09. BSD 2-Clause License, see
 * META-INF/LICENSE-better-npc-highlight and THIRD_PARTY_NOTICES.md. Changes for HD
 * Tile Markers: package; renamed from NameAndIdContainer; entity hider, draw-beneath and menu-tag state and NPC ID lists removed.
 */
package com.hdtilemarkers.betternpc;

import java.util.ArrayList;

import javax.inject.Singleton;

import lombok.Getter;
import lombok.Setter;

@Singleton
@Getter
@Setter
public class NameListContainer {
	private String currentTask = "";
	private ArrayList<NPCInfo> npcList = new ArrayList<>();

	private ArrayList<String> tileNames = new ArrayList<>();
	private ArrayList<String> trueTileNames = new ArrayList<>();
	private ArrayList<String> swTileNames = new ArrayList<>();
	private ArrayList<String> swTrueTileNames = new ArrayList<>();
	private ArrayList<String> hullNames = new ArrayList<>();
	private ArrayList<String> areaNames = new ArrayList<>();
	private ArrayList<String> outlineNames = new ArrayList<>();
	private ArrayList<String> clickboxNames = new ArrayList<>();

	private ArrayList<String> namesToDisplay = new ArrayList<>();
	private ArrayList<String> ignoreDeadExclusionList = new ArrayList<>();

	public void clearAll() {
		tileNames.clear();
		trueTileNames.clear();
		swTileNames.clear();
		swTrueTileNames.clear();
		hullNames.clear();
		areaNames.clear();
		outlineNames.clear();
		clickboxNames.clear();
		ignoreDeadExclusionList.clear();
		namesToDisplay.clear();
		npcList.clear();
	}
}