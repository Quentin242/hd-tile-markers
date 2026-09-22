/*
 * Adapted from Better NPC Highlight, https://github.com/riktenx/better-npc-highlight,
 * commit bf59bfb9a616897e9ffcd14d0d2b543e4c119b09. BSD 2-Clause License, see
 * META-INF/LICENSE-better-npc-highlight and THIRD_PARTY_NOTICES.md. Changes for Better
 * Indicator Renderer: package; entity hider, draw-beneath and menu-tag state removed.
 */
package com.hdtilemarkers.betternpc;

import java.util.ArrayList;

import javax.inject.Singleton;

import lombok.Getter;
import lombok.Setter;

@Singleton
@Getter
@Setter
public class NameAndIdContainer {
	private String currentTask = "";
	private ArrayList<NPCInfo> npcList = new ArrayList<>();

	private ArrayList<String> tileNames = new ArrayList<>();
	private ArrayList<String> tileIds = new ArrayList<>();
	private ArrayList<String> trueTileNames = new ArrayList<>();
	private ArrayList<String> trueTileIds = new ArrayList<>();
	private ArrayList<String> swTileNames = new ArrayList<>();
	private ArrayList<String> swTileIds = new ArrayList<>();
	private ArrayList<String> swTrueTileNames = new ArrayList<>();
	private ArrayList<String> swTrueTileIds = new ArrayList<>();
	private ArrayList<String> hullNames = new ArrayList<>();
	private ArrayList<String> hullIds = new ArrayList<>();
	private ArrayList<String> areaNames = new ArrayList<>();
	private ArrayList<String> areaIds = new ArrayList<>();
	private ArrayList<String> outlineNames = new ArrayList<>();
	private ArrayList<String> outlineIds = new ArrayList<>();
	private ArrayList<String> clickboxNames = new ArrayList<>();
	private ArrayList<String> clickboxIds = new ArrayList<>();

	private ArrayList<String> namesToDisplay = new ArrayList<>();
	private ArrayList<String> ignoreDeadExclusionList = new ArrayList<>();
	private ArrayList<String> ignoreDeadExclusionIDList = new ArrayList<>();

	public void clearAll() {
		tileNames.clear();
		tileIds.clear();
		trueTileNames.clear();
		trueTileIds.clear();
		swTileNames.clear();
		swTileIds.clear();
		swTrueTileNames.clear();
		swTrueTileIds.clear();
		hullNames.clear();
		hullIds.clear();
		areaNames.clear();
		areaIds.clear();
		outlineNames.clear();
		outlineIds.clear();
		clickboxNames.clear();
		clickboxIds.clear();
		ignoreDeadExclusionList.clear();
		ignoreDeadExclusionIDList.clear();
		namesToDisplay.clear();
		npcList.clear();
	}
}