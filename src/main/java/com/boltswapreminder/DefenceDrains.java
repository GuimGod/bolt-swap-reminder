/*
 * Copyright (c) 2026, GuimGod
 * All rights reserved. Licensed under the BSD 2-Clause License (see LICENSE).
 */
package com.boltswapreminder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/**
 * The special attacks that lowered one monster's Defence (and Magic), and the levels it is left with. The same rules as
 * the Combat Check plugin's, which checks them against the OSRS Wiki DPS calculator; this plugin reads the Defence.
 *
 * <p>Each special attack is taken off the levels the one before left, in the order they landed: the game lowers the
 * level the monster has at the time (OSRS Wiki, Dragon warhammer and Elder maul: "each reduction is calculated from the
 * current temporary level"; Bandos godsword: "drains the opponent's combat levels equal to the damage dealt"). Each one
 * is worked out as the OSRS Wiki DPS calculator works it out ({@code applyDefenceReductions}: each fraction rounded
 * down, no Defence below the monster's floor, a Bandos godsword's damage beyond the Defence left going on to Strength,
 * Attack, Magic and Ranged); the calculator only has counts and applies them in one order of its own (Accursed sceptre,
 * Elder maul, Dragon warhammer, Arclight, Emberlight, Tonalztics of Ralos, Seercull, Bandos godsword, Eye of Ayak), which
 * is the order they landed in when they landed so.
 *
 * <p>Rules the calculator does not have come from the game (OSRS Wiki): an Elder maul or Dragon warhammer that fails to
 * hit Tekton still takes 5% of his Defence, and a Bandos godsword that fails to hit him takes 10 levels; the Accursed
 * sceptre takes a level already lowered no further than 15% below the full one, and the Seercull lowers Magic only while
 * it is full; the bone dagger and the Dorgeshuun crossbow lower Defence by their damage only while it is full, and the
 * Barrelchest anchor lowers one level by a tenth of its damage. The Vulnerability spell, which the calculator also has,
 * is not a special attack: nothing reports it.
 */
final class DefenceDrains
{
	/** What a special attack that landed does to the levels. */
	private enum Kind
	{
		/** The Accursed sceptre: Defence and Magic to 85% of the full levels at most. */
		ACCURSED,
		/** The Elder maul: 35% of the Defence left. */
		ELDER_MAUL,
		/** The Dragon warhammer: 30% of the Defence left. */
		DWH,
		/** An Elder maul or Dragon warhammer that failed to hit Tekton: 5% of the Defence left. */
		TEKTON_MISS,
		/** Arclight and Darklight, which share the same special attack. */
		ARCLIGHT,
		EMBERLIGHT,
		/** One hit of the Tonalztics of Ralos' special attack that landed: an eighth of the Magic left off Defence. */
		TONALZTIC,
		/** The Seercull: its damage off Magic while Magic is full. */
		SEERCULL,
		/** The Bandos godsword: its damage off Defence, then Strength, Attack, Magic and Ranged. */
		BGS,
		/** The Eye of Ayak: its damage off the magic defence bonus. */
		AYAK,
		/** The bone dagger and the Dorgeshuun crossbow: their damage off Defence while Defence is full. */
		BONE,
		/** The Barrelchest anchor: a tenth of its damage off Defence, or Attack, Ranged or Magic past it. */
		ANCHOR
	}

	/** One special attack that landed: what it does, and its damage where that counts. */
	private static final class Drain
	{
		final Kind kind;
		final int amount;
		/** The game tick it landed on (0 when not known): the monster's stats come back from the first one on. */
		final int tick;

		Drain(Kind kind, int amount, int tick)
		{
			this.kind = kind;
			this.amount = amount;
			this.tick = tick;
		}
	}

	/** A level the table does not give: high enough that a Bandos godsword's damage never goes past it. */
	static final int UNKNOWN_LEVEL = 100000;

	/** The special attacks that lowered something, in the order they landed. */
	private final List<Drain> landed = new ArrayList<>();

	/** A monster's levels: what the damage formulas read, and what a Bandos godsword's damage goes on to. */
	static final class Levels
	{
		int attack;
		int strength;
		int defence;
		int magic;
		int ranged;
		/** The magic defence bonus (the Eye of Ayak lowers it). */
		int magicDefence;
		/** Its hitpoints when full (the raid's scaling changes them; no drain does). */
		int hitpoints;

		Levels copy()
		{
			Levels l = new Levels();
			l.attack = attack;
			l.strength = strength;
			l.defence = defence;
			l.magic = magic;
			l.ranged = ranged;
			l.magicDefence = magicDefence;
			l.hitpoints = hitpoints;
			return l;
		}
	}

	/**
	 * Counts one special attack as the Special Attack Counter reports it, after the ones counted before: {@code hit} is
	 * the damage of the hit, 1 or 0 for an Elder maul (whether it dealt damage), and the number of hits that landed for
	 * the Tonalztics of Ralos. A special attack that deals no damage lowers nothing, except on Tekton.
	 */
	void add(SpecialWeapon weapon, int hit, boolean tekton)
	{
		add(weapon, hit, tekton, landed.isEmpty() ? 0 : landed.get(landed.size() - 1).tick);
	}

	/**
	 * Counts one special attack that landed on game tick {@code tick}, which is not before the tick of any counted before
	 * ({@link DrainTracker} keeps them in order): the stats the monster wins back with time come between them
	 * ({@link #left}).
	 */
	void add(SpecialWeapon weapon, int hit, boolean tekton, int tick)
	{
		addingTick = tick;
		if (weapon == null)
		{
			return;
		}
		switch (weapon)
		{
			case DRAGON_WARHAMMER:
			case ELDER_MAUL:
				if (hit > 0)
				{
					landed(weapon == SpecialWeapon.ELDER_MAUL ? Kind.ELDER_MAUL : Kind.DWH, 0);
				}
				else if (tekton)
				{
					landed(Kind.TEKTON_MISS, 0);
				}
				break;
			case ARCLIGHT:
			case DARKLIGHT:
				if (hit > 0)
				{
					landed(Kind.ARCLIGHT, 0);
				}
				break;
			case EMBERLIGHT:
				if (hit > 0)
				{
					landed(Kind.EMBERLIGHT, 0);
				}
				break;
			case BANDOS_GODSWORD:
				if (hit > 0 || tekton)
				{
					// on Tekton a miss drains 10 (OSRS Wiki, Bandos godsword: "following the stat drain order")
					landed(Kind.BGS, hit > 0 ? hit : 10);
				}
				break;
			case TONALZTICS_OF_RALOS:
				for (int i = 0; i < Math.min(hit, 2); i++)
				{
					landed(Kind.TONALZTIC, 0);
				}
				break;
			case SEERCULL:
				if (hit > 0)
				{
					landed(Kind.SEERCULL, hit);
				}
				break;
			case EYE_OF_AYAK:
				if (hit > 0)
				{
					landed(Kind.AYAK, hit);
				}
				break;
			case ACCURSED_SCEPTRE:
				if (hit > 0)
				{
					landed(Kind.ACCURSED, 0);
				}
				break;
			case BONE_DAGGER:
			case DORGESHUUN_CROSSBOW:
				if (hit > 0)
				{
					landed(Kind.BONE, hit);
				}
				break;
			case BARRELCHEST_ANCHOR:
				if (hit > 0)
				{
					landed(Kind.ANCHOR, hit);
				}
				break;
			default:
				// Dinh's bulwark lowers nothing
				break;
		}
	}

	/** The tick of the special attack being added. */
	private int addingTick;

	private void landed(Kind kind, int amount)
	{
		landed.add(new Drain(kind, amount, addingTick));
	}

	/** True when nothing has been counted. */
	boolean none()
	{
		return landed.isEmpty();
	}

	/**
	 * The levels left after these special attacks, from the monster's full ones; {@code floor} is the lowest Defence it can be taken to ({@link #floor}).
	 * No other level goes below 0.
	 */
	Levels apply(Levels full, boolean demon, int floor)
	{
		return apply(full, demon, floor, -1, 0);
	}

	/**
	 * The levels left, with the stats the monster wins back with time ({@link #recovered}) between the special attacks and
	 * up to {@code ticksSinceLast} ticks after the last one: counted from the first one landed, each level that comes back
	 * coming back before the special attacks landed after it. {@code npcId} -1 for none won back.
	 */
	Levels apply(Levels full, boolean demon, int floor, int npcId, int ticksSinceLast)
	{
		Levels l = full.copy();
		int first = landed.isEmpty() ? 0 : landed.get(0).tick;
		int back = 0;
		for (Drain d : landed)
		{
			if (npcId >= 0)
			{
				int now = recovered(npcId, d.tick - first);
				winBack(l, full, now - back);
				back = now;
			}
			switch (d.kind)
			{
				case ACCURSED:
					// "If the target's levels have already been reduced, Condemn will only drain them to a maximum of
					// 15%" (OSRS Wiki, Accursed sceptre): never below 85% of the full level by itself, nothing on a level
					// already lower
					l.defence = Math.max(floor, Math.min(l.defence, full.defence * 17 / 20));
					l.magic = Math.max(0, Math.min(l.magic, full.magic * 17 / 20));
					break;
				case ELDER_MAUL:
					l.defence = Math.max(floor, l.defence - l.defence * 35 / 100);
					break;
				case DWH:
					l.defence = Math.max(floor, l.defence - l.defence * 3 / 10);
					break;
				case TEKTON_MISS:
					l.defence = Math.max(floor, l.defence - l.defence * 5 / 100);
					break;
				case ARCLIGHT:
					// Arclight and Emberlight take a part of the full levels (before any drain) from Attack, Strength
					// and Defence
					arclight(l, full, demon ? 2 : 1, floor);
					break;
				case EMBERLIGHT:
					arclight(l, full, demon ? 3 : 1, floor);
					break;
				case TONALZTIC:
					l.defence = Math.max(floor, l.defence - l.magic / 8);
					break;
				case SEERCULL:
					// "will only drain the opponent's Magic level if it is not already reduced" (OSRS Wiki, Seercull)
					if (l.magic >= full.magic)
					{
						l.magic = Math.max(0, l.magic - d.amount);
					}
					break;
				case BGS:
					bgs(l, d.amount, floor);
					break;
				case AYAK:
					if (l.magicDefence > 0)
					{
						l.magicDefence = Math.max(0, l.magicDefence - d.amount);
					}
					break;
				case BONE:
					// "only try to reduce it if it's on (or above) its base level at the time" (OSRS Wiki, Bone dagger and
					// Dorgeshuun crossbow, Mod Ash)
					if (l.defence >= full.defence)
					{
						l.defence = Math.max(floor, l.defence - d.amount);
					}
					break;
				case ANCHOR:
					anchor(l, d.amount / 10, floor);
					break;
				default:
					break;
			}
		}
		if (npcId >= 0 && !landed.isEmpty())
		{
			int last = landed.get(landed.size() - 1).tick;
			winBack(l, full, recovered(npcId, last - first + Math.max(0, ticksSinceLast)) - back);
		}
		return l;
	}

	/**
	 * {@code levels} levels back on each stat lowered, up to the full one (OSRS Wiki, Stat restoration: "any boosted or
	 * drained stat is restored by 1 closer to its current level"; the monsters "regenerate their stats"). The magic
	 * defence bonus the Eye of Ayak lowers is not a stat and does not come back (Eye of Ayak: "As Magic Defence is not a
	 * stat, NPCs cannot restore it").
	 */
	private static void winBack(Levels l, Levels full, int levels)
	{
		if (levels <= 0)
		{
			return;
		}
		l.attack = Math.max(l.attack, Math.min(full.attack, l.attack + levels));
		l.strength = Math.max(l.strength, Math.min(full.strength, l.strength + levels));
		l.defence = Math.max(l.defence, Math.min(full.defence, l.defence + levels));
		l.magic = Math.max(l.magic, Math.min(full.magic, l.magic + levels));
		l.ranged = Math.max(l.ranged, Math.min(full.ranged, l.ranged + levels));
	}

	/** An Arclight-type special attack, taking {@code twentieths}/20 of the full level, plus 1. */
	private static void arclight(Levels l, Levels full, int twentieths, int floor)
	{
		l.attack = Math.max(0, l.attack - (twentieths * full.attack / 20 + 1));
		l.strength = Math.max(0, l.strength - (twentieths * full.strength / 20 + 1));
		l.defence = Math.max(floor, l.defence - (twentieths * full.defence / 20 + 1));
	}

	/**
	 * A Bandos godsword's damage: Defence first; what it leaves over goes on to Strength, Attack, Magic and Ranged, in that
	 * order, only past a level taken to 0 (a level the floor holds up stops it).
	 */
	private static void bgs(Levels l, int damage, int floor)
	{
		int left = damage;
		int start = l.defence;
		l.defence = Math.max(floor, start - left);
		left = l.defence > 0 ? 0 : left - start;
		start = l.strength;
		l.strength = Math.max(0, start - left);
		left = l.strength > 0 ? 0 : left - start;
		start = l.attack;
		l.attack = Math.max(0, start - left);
		left = l.attack > 0 ? 0 : left - start;
		start = l.magic;
		l.magic = Math.max(0, start - left);
		left = l.magic > 0 ? 0 : left - start;
		l.ranged = Math.max(0, l.ranged - left);
	}

	/**
	 * The Barrelchest anchor's drain: "Drains one stat by 10% of damage - if Defence is already on 1, it drains Attack,
	 * etc." (OSRS Wiki, Barrelchest anchor, Mod Ash), in the order Defence, Attack, Ranged, Magic, the first one above 1;
	 * the tenth rounded down (the wiki does not say how it rounds).
	 */
	private static void anchor(Levels l, int drain, int floor)
	{
		if (drain <= 0)
		{
			return;
		}
		if (l.defence > 1)
		{
			l.defence = Math.max(floor, l.defence - drain);
		}
		else if (l.attack > 1)
		{
			l.attack = Math.max(0, l.attack - drain);
		}
		else if (l.ranged > 1)
		{
			l.ranged = Math.max(0, l.ranged - drain);
		}
		else if (l.magic > 1)
		{
			l.magic = Math.max(0, l.magic - drain);
		}
	}

	/**
	 * The levels the monster fought is left with after the special attacks counted on it ({@code drains}, null for none):
	 * its full ones for a fight that takes no drain ({@link #immune}, or an awakened fight of Desert Treasure II's
	 * bosses); else lowered down to its floor ({@link #floor}), with the stats it won back with time, between the special
	 * attacks and up to {@code ticksSinceLast} ticks after the last one ({@link #recovered}).
	 */
	static Levels left(int npcId, boolean demon, boolean awakened, Levels full, DefenceDrains drains, int ticksSinceLast)
	{
		if (drains == null || drains.none() || awakened || immune(npcId))
		{
			return full;
		}
		// the stats come back with time, between the special attacks and after the last one
		return drains.apply(full, demon, floor(npcId, full.defence), npcId, ticksSinceLast);
	}

	/**
	 * The full levels of a monster whose table gives only its Defence: Attack, Strength and Ranged are not known
	 * ({@link #UNKNOWN_LEVEL}), so a Bandos godsword's damage stops at Defence; Magic is taken as 0, so the Tonalztics of
	 * Ralos, which lowers Defence by a part of the monster's Magic, lowers nothing here (never more than the game does).
	 */
	static Levels full(int defence)
	{
		Levels l = new Levels();
		l.attack = UNKNOWN_LEVEL;
		l.strength = UNKNOWN_LEVEL;
		l.ranged = UNKNOWN_LEVEL;
		l.magic = 0;
		l.defence = defence;
		return l;
	}

	/**
	 * Desert Treasure II's bosses, by the NPC id their awakened fight shares with the fight after the quest: the awakened
	 * one is told by the boss health display showing more hitpoints than the table's row (the Leviathan, the Whisperer:
	 * 2700 against 900).
	 */
	private static final int[] DESERT_TREASURE_BOSSES = {12191, 12204, 12205, 12214, 12223, 12426};

	/** True for the awakened fight of one of Desert Treasure II's bosses: "immune to stat drains" (OSRS Wiki). */
	static boolean awakened(int npcId, int tableHitpoints, int displayedHitpoints)
	{
		return has(DESERT_TREASURE_BOSSES, npcId) && displayedHitpoints > tableHitpoints;
	}

	private static final int[] VERZIK = {10830, 10831, 10832, 8369, 8370, 8371, 10847, 10848, 10849,
		10833, 10834, 10835, 8372, 8373, 8374, 10850, 10851, 10852};
	private static final int[] VARDORVIS = {12223, 12224, 12228, 12425, 12426, 13656};
	private static final int[] SOTETSEG = {8387, 8388, 10867, 10868};
	private static final int[] NIGHTMARE = {378, 9425, 9426, 9427, 9428, 9429, 9430, 9431, 9432, 9433, 9460,
		377, 9423, 9416, 9417, 9418, 9419, 9420, 9421, 9422, 9424, 11153, 11154, 11155};
	private static final int[] AKKHA = {11789, 11790, 11791, 11792, 11793, 11794, 11795, 11796};
	private static final int[] BABA = {11778, 11779, 11780};
	private static final int[] KEPHRI = {11719, 11721};
	private static final int[] ZEBAK = {11730, 11732, 11733};
	private static final int[] WARDEN_P3 = {11761, 11763, 11762, 11764};
	private static final int[] OBELISK = {11751, 11750, 11752};
	private static final int[] NEX = {11278, 11279, 11280, 11281, 11282};
	private static final int[] ARAXXOR = {13668};
	private static final int[] HUEYCOATL = {14009, 14010, 14013, 14017, 14014};
	private static final int[] YAMA = {14176};
	/** Tekton, in each of his forms (the Special Attack Counter's list). */
	private static final int[] TEKTON = {7540, 7541, 7542, 7543, 7544, 7545};
	/** The Phantom Muspah in each of its forms (OSRS Wiki: Ranged, Melee, Teleporting, Shielded, Post-shield). */
	private static final int[] PHANTOM_MUSPAH = {12077, 12078, 12082, 12079, 12080};
	/** Its shielded form: its Defence is back to full on entering it. */
	private static final int MUSPAH_SHIELDED = 12079;
	/**
	 * Kephri shielded and weakened (RuneLite's NpcID TOA_KEPHRI_BOSS_SHIELDED, TOA_KEPHRI_BOSS_WEAK), before her last phase.
	 */
	private static final int[] KEPHRI_SHIELD_PHASES = {11719, 11720};
	/** Kephri in her last phase, open to attacks (TOA_KEPHRI_BOSS_ENRAGE; the OSRS Wiki's version "Aggressive"). */
	private static final int KEPHRI_LAST_PHASE = 11721;
	/**
	 * Vet'ion and Calvar'ion going into their second form, each as {old, new}: "In this form ... any drained Defence levels
	 * will be restored" (OSRS Wiki, Vet'ion and Calvar'ion; the infoboxes' Normal and Enraged ids).
	 */
	private static final int[][] SECOND_FORM = {{6611, 6612}, {11993, 11994}};
	/** The Doom of Mokhaiotl, shielded and burrowed too. */
	private static final int[] DOOM_OF_MOKHAIOTL = {14707, 14708, 14709};
	private static final int[] FRAGMENT_OF_SEREN = {8917, 8918, 8919, 8920};
	private static final int[] MENAPHITE_AKH = {11492};
	/** Sotetseg fighting, in Entry, normal and Hard Mode (RuneLite's NpcID TOB_SOTETSEG_COMBAT_STORY, _COMBAT, _COMBAT_HARD). */
	private static final int[] SOTETSEG_COMBAT = {10865, 8388, 10868};
	/** Sotetseg while the team is in the maze, mode by mode as above (TOB_SOTETSEG_NONCOMBAT_STORY, _NONCOMBAT, _HARD). */
	private static final int[] SOTETSEG_NONCOMBAT = {10864, 8387, 10867};
	/** Galvek's four phases, each with its ids (OSRS Wiki, Galvek: Fire, Air, Water, Earth). */
	private static final int[][] GALVEK = {{8094, 8095, 8177}, {8097}, {8096}, {8098, 8178, 8179}};
	/**
	 * The fights that take no drain at all, by their own ids (OSRS Wiki): Demonic Brutus ("immune to stat drains") and the
	 * Moons of Peril ("Defence-reducing special attacks and Death Charge have no effect on the Moons of Peril").
	 */
	private static final int[] IMMUNE = {15628, 15629, 13011, 13012, 13013};
	/**
	 * The Fractured Archive's bosses whose Defence cannot be lowered below 60% of its base (official post "The Fractured
	 * Archive - Out Today", 20 October 2026, its hotfix on Defence reduction: "The following NPCs should have a 60% cap on
	 * their Defence Reduction, meaning an NPC with 100 Defence could have it reduced down to 60, but no further: Archive
	 * Guardian, Bork, Ezekiel, Moia, Xarth, Ocellus"): the Archive Guardian in flight and on the ground, Bork, Ezekiel, Moia,
	 * Xarth and Ocellus in his first and third phases. The others of the raid (Dreadnoth, the Smorks, the Dagon'hai, The
	 * Blade of Ocellus's second phase) have "no cap": 0, as most monsters. The game held the Dagon'hai at 60% by a bug
	 * until the official post "Quest Month - Week Two & TFA Changes" (2026-11-11: "Fixed a bug where the Dagon'hai trios
	 * Defence could not be reduced to 0 and was instead being incorrectly capped at 60%"): since then the game takes them to
	 * 0, as counted here.
	 */
	private static final int[] ARCHIVE_SIXTY_PERCENT = {16659, 16660, 16640, 16652, 16667, 16684, 16673, 16674, 16677};
	/** The share of its base Defence a boss of {@link #ARCHIVE_SIXTY_PERCENT} keeps, in percent. */
	static final int ARCHIVE_CAP_PERCENT = 60;

	/**
	 * The lowest Defence the monster can be taken to, by its NPC id: the calculator's {@code getDefenceFloor} (its full
	 * Defence for Verzik Vitur and Vardorvis, which cannot be drained, and 0 for most monsters), and the floors the OSRS
	 * Wiki gives that the calculator does not have: the Phantom Muspah 125 (Phantom Muspah/Strategies: "Muspah's defence can
	 * be reduced down to 125"), the Doom of Mokhaiotl 60 (Doom of Mokhaiotl/Strategies: "a defence drain cap of 30, meaning
	 * that its Defence can only be lowered to a minimum of 60"), the Fragment of Seren 60 (Fragment of Seren Speed-Trialist:
	 * "her defence cannot be lowered below 60") and the Menaphite Akh 100 (Menaphite Akh: "her defence cannot be lowered
	 * below 100", Mod Ash). And the Fractured Archive's bosses with a cap: 60% of their base ({@link #ARCHIVE_SIXTY_PERCENT}).
	 */
	static int floor(int npcId, int defence)
	{
		if (has(ARCHIVE_SIXTY_PERCENT, npcId))
		{
			// "an NPC with 100 Defence could have it reduced down to 60, but no further": every base of the table gives a
			// whole number (260 156, 300 180, 140 84, 195 117, 170 102, 215 129, 250 150); the rounding of another is not known
			return defence * ARCHIVE_CAP_PERCENT / 100;
		}
		if (has(VERZIK, npcId) || has(VARDORVIS, npcId))
		{
			return defence;
		}
		if (has(SOTETSEG, npcId))
		{
			return 100;
		}
		if (has(NIGHTMARE, npcId) || has(WARDEN_P3, npcId) || has(HUEYCOATL, npcId))
		{
			return 120;
		}
		if (has(AKKHA, npcId))
		{
			return 70;
		}
		if (has(BABA, npcId) || has(KEPHRI, npcId) || has(OBELISK, npcId))
		{
			return 60;
		}
		if (has(ZEBAK, npcId))
		{
			return 50;
		}
		if (has(NEX, npcId))
		{
			return 250;
		}
		if (has(ARAXXOR, npcId))
		{
			return 90;
		}
		if (has(YAMA, npcId))
		{
			return 145;
		}
		if (has(PHANTOM_MUSPAH, npcId))
		{
			return 125;
		}
		if (has(DOOM_OF_MOKHAIOTL, npcId) || has(FRAGMENT_OF_SEREN, npcId))
		{
			return 60;
		}
		if (has(MENAPHITE_AKH, npcId))
		{
			return 100;
		}
		return 0;
	}

	/**
	 * True for an NPC id of a fight that takes no drain at all, whatever lands (OSRS Wiki): Demonic Brutus and the Moons of
	 * Peril ({@link #IMMUNE}).
	 */
	static boolean immune(int npcId)
	{
		return has(IMMUNE, npcId);
	}

	/**
	 * True when a monster that changes from {@code oldId} into {@code newId} has its Defence back to full, whatever the
	 * table's levels of the two say (OSRS Wiki): the Phantom Muspah entering its shielded phase ("The defence reduction will
	 * preserve between phases until the shielded phase, where Muspah's defence resets to 200"), and Galvek going on to
	 * another of his phases ("he will reset his Defence to its original level after every phase"; an id the wiki does not
	 * put in a phase changes nothing), and Sotetseg sending the team into the maze ("any defence drain will be reset each
	 * time players are transported to the shadow realm"; Theatre of Blood/Strategies: "After clearing the maze,
	 * Sotetseg's Defence level is restored"): in the Theatre of 2026-10-17 he went from fighting (8388) to the maze's
	 * form (8387) and back twice a raid, and the table gives the two forms the same levels. And Kephri going into her last
	 * phase (Kephri: "Her base defence level can be lowered by a maximum of 20 damage ... however the defence level is reset
	 * for the final phase"; "After two phases of shields, Kephri will finally be vulnerable"), whose row has the same
	 * Defence as her shielded one. And Vet'ion and Calvar'ion going into their second form ({@link #SECOND_FORM}). And
	 * Ocellus going on to another phase ({@link #OCELLUS}): the wiki says the Defence reduction "works on all phases", not
	 * how, so each phase is counted from its own full Defence, with its own special attacks only.
	 */
	static boolean resets(int oldId, int newId)
	{
		int ocellusBefore = phase(OCELLUS, oldId);
		int ocellusAfter = phase(OCELLUS, newId);
		if (ocellusBefore >= 0 && ocellusAfter >= 0 && ocellusBefore != ocellusAfter)
		{
			return true;
		}
		if (newId == KEPHRI_LAST_PHASE && has(KEPHRI_SHIELD_PHASES, oldId))
		{
			return true;
		}
		for (int[] change : SECOND_FORM)
		{
			if (oldId == change[0] && newId == change[1])
			{
				return true;
			}
		}
		for (int mode = 0; mode < SOTETSEG_COMBAT.length; mode++)
		{
			if (oldId == SOTETSEG_COMBAT[mode] && newId == SOTETSEG_NONCOMBAT[mode])
			{
				return true;
			}
		}
		if (newId == MUSPAH_SHIELDED && oldId != MUSPAH_SHIELDED && has(PHANTOM_MUSPAH, oldId))
		{
			return true;
		}
		int before = phase(GALVEK, oldId);
		int after = phase(GALVEK, newId);
		return before >= 0 && after >= 0 && before != after;
	}

	/**
	 * Ocellus's phases, each with its ids: the first (OSRS Wiki, 16673 and 16674), the second (16675, the recorder: the
	 * form between the first and the third in all nine fights recorded; the wiki gives it no id) and the third (16677).
	 */
	private static final int[][] OCELLUS = {{16673, 16674}, {16675}, {16677}};

	private static int phase(int[][] phases, int id)
	{
		for (int i = 0; i < phases.length; i++)
		{
			if (has(phases[i], id))
			{
				return i;
			}
		}
		return -1;
	}

	private static final int[] THE_NIGHTMARE = {378, 9425, 9426, 9427, 9428, 9429, 9430, 9431, 9432, 9433, 9460};

	/** The standard time a monster takes to win back one level of each lowered stat, in ticks. */
	static final int STANDARD_RESTORE_TICKS = 100;
	/**
	 * The monsters that win their stats back faster or slower than the standard, each with its ticks a level (OSRS Wiki,
	 * each one's page: "regenerate(s) ... stats at a rate of 1 per N ticks, faster than the standard rate of 1 per 100
	 * ticks"): Tekton 15, Vasa Nistirio 10, Vespula 10, the Vespine soldier flying 16 (walking, not said: the standard),
	 * the Glowing crystal 9, the Skeletal Mystics 25, the Lizardman shamans of the Chambers 20, the Guardians of the
	 * Chambers 8, the Abyssal portal 45, the Lux grubs 10000, the Jewelled Crabs 1; Phosani's Nightmare 5 (Phosani's
	 * Nightmare/Strategies: "she recovers drained levels every 3 seconds (5 ticks) instead of every minute").
	 */
	private static final int[][] RESTORE_TICKS = {
		{15, 7540, 7541, 7542, 7543, 7544, 7545},
		{10, 7566, 7567},
		{10, 7530, 7531, 7532},
		{16, 7538},
		{9, 7568},
		{25, 7604, 7605, 7606},
		{20, 7573, 7574},
		{8, 7569, 7570, 7571, 7572},
		{45, 7533},
		{10000, 7534, 7535, 7536, 7537},
		{1, 7576, 7577, 7578, 7579},
		{5, 377, 9423, 9416, 9417, 9418, 9419, 9420, 9421, 9422, 9424, 11153, 11154, 11155},
	};

	/**
	 * The levels of each lowered stat a monster has won back {@code ticks} ticks after the first special attack counted
	 * on it (the calculator has no time; OSRS Wiki): one level every {@link #STANDARD_RESTORE_TICKS} ticks for most
	 * monsters (Temporary skill drain: "Stats are restored at a rate of 1 per 60 seconds"; "Most monsters follow the same
	 * rules as players"; Stat restoration: "Every minute, equivalent to 100 ticks"; Ice demon: "the standard rate of 1
	 * per 100 ticks"), faster or slower for the monsters of {@link #RESTORE_TICKS}, and 20 levels a minute for the
	 * Nightmare (The Nightmare: "she restores drained stats at a rate of 20 per minute"). Counted from the first drain:
	 * the wiki does not say whether a monster's clock runs from the drain or on its own, and from the drain it is the
	 * least that has come back either way.
	 */
	static int recovered(int npcId, int ticks)
	{
		if (ticks <= 0)
		{
			return 0;
		}
		if (has(THE_NIGHTMARE, npcId))
		{
			return ticks / 100 * 20;
		}
		for (int[] rate : RESTORE_TICKS)
		{
			for (int i = 1; i < rate.length; i++)
			{
				if (rate[i] == npcId)
				{
					return ticks / rate[0];
				}
			}
		}
		return ticks / STANDARD_RESTORE_TICKS;
	}

	/** True for Tekton: his own rules for a special attack that fails to hit. */
	static boolean tekton(int npcId)
	{
		return has(TEKTON, npcId);
	}

	private static boolean has(int[] ids, int id)
	{
		return Arrays.stream(ids).anyMatch(i -> i == id);
	}
}
