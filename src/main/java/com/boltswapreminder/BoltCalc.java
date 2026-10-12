/*
 * Copyright (c) 2026, GuimGod
 * All rights reserved. Licensed under the BSD 2-Clause License (see LICENSE).
 */
package com.boltswapreminder;

/**
 * Expected damage per game tick of a crossbow with ruby or diamond bolts (e) against a monster at a
 * given health. The standard ranged accuracy and max hit formulas (OSRS Wiki), plus the two effects:
 *
 * <ul>
 * <li>Ruby, "Blood Forfeit": 6% of the shots deal 20% of the monster's current health, at most 100
 * (22% and 110 with a Zaryte crossbow), whatever the accuracy roll.</li>
 * <li>Diamond, "Armour Piercing": 10% of the shots cannot miss and roll up to 115% of the max hit
 * (126% with a Zaryte crossbow).</li>
 * </ul>
 *
 * The Kandarin hard diary makes either effect a tenth more likely. So ruby bolts are worth less and
 * less as the monster's health falls, and at some health the diamond ones overtake them: where
 * depends on the player's own max hit and accuracy, on the crossbow and on the monster's defence.
 */
final class BoltCalc
{
	enum Bolt
	{
		RUBY, DIAMOND
	}

	/** The player's side of a shot. */
	static final class Shot
	{
		/** Boosted Ranged level. */
		int rangedLevel;
		/** Prayer multipliers as percentages: 100 for none, 120 and 123 for Rigour. */
		int prayerAccuracy = 100;
		int prayerStrength = 100;
		/** Ranged attack bonus of everything worn. */
		int rangedAttack;
		/** Ranged strength of everything worn, with the bolts being compared in the ammunition slot. */
		int rangedStrength;
		/** Ticks between attacks on Accurate, as on the item (Rapid is one less). */
		int speed = 6;
		boolean zaryte;
		boolean kandarinDiary;
		/** Slayer helmet (i) on a task or Salve amulet (i) on the undead: multiplies the roll and the max hit. */
		int bonusNumerator = 1;
		int bonusDenominator = 1;
		/** The ruby effect costs the player a tenth of their health and does nothing under 10 hitpoints. */
		int playerHitpoints = 99;
	}

	/** The monster's side. */
	static final class Target
	{
		int defenceLevel;
		/** Defence against heavy ranged weapons (crossbows). */
		int heavyDefence;
		/** Hitpoints it has now. */
		int hitpoints;
		/** Taken off every hit that lands. */
		int flatArmour;
		/** True for the Gemstone Crab, whose health never runs out: the ruby effect stops at 60 (66 with a Zaryte crossbow). */
		boolean endlessHealth;
	}

	private BoltCalc()
	{
	}

	/** Expected damage per tick with these bolts, on the better of Rapid and Accurate. */
	static double perTick(Bolt bolt, Shot s, Target t)
	{
		double best = 0;
		for (int accurate = 0; accurate <= 1; accurate++)
		{
			long effective = (long) s.rangedLevel * s.prayerAccuracy / 100 + 8 + (accurate == 1 ? 3 : 0);
			long effectiveStrength = (long) s.rangedLevel * s.prayerStrength / 100 + 8 + (accurate == 1 ? 3 : 0);
			long roll = effective * (s.rangedAttack + 64) * s.bonusNumerator / s.bonusDenominator;
			long max = (effectiveStrength * (s.rangedStrength + 64) + 320) / 640 * s.bonusNumerator / s.bonusDenominator;
			long defenceRoll = (long) (t.defenceLevel + 9) * (t.heavyDefence + 64);
			double normal = chance(roll, defenceRoll) * landed(max, t.flatArmour);
			double perShot = withEffect(bolt, s, t, max, normal);
			int speed = accurate == 1 ? s.speed : Math.max(1, s.speed - 1);
			best = Math.max(best, perShot / speed);
		}
		return best;
	}

	/** Expected damage of one shot: the effect on its share of the shots, the ordinary hit on the rest. */
	static double withEffect(Bolt bolt, Shot s, Target t, long max, double normal)
	{
		double diary = s.kandarinDiary ? 1.1 : 1.0;
		if (bolt == Bolt.DIAMOND)
		{
			double p = 0.10 * diary;
			long effectMax = max * (s.zaryte ? 126 : 115) / 100;
			return p * effectMax / 2.0 + (1 - p) * normal;
		}
		if (s.playerHitpoints < 10)
		{
			return normal;
		}
		double p = 0.06 * diary;
		long ruby = rubyHit(t.hitpoints, s.zaryte);
		return p * (t.endlessHealth ? Math.min(ruby, s.zaryte ? 66 : 60) : ruby) + (1 - p) * normal;
	}

	/** What the ruby effect deals to a monster with this much health. */
	static long rubyHit(int monsterHitpoints, boolean zaryte)
	{
		return Math.min(zaryte ? 110 : 100, (long) monsterHitpoints * (zaryte ? 22 : 20) / 100);
	}

	/** How much more {@code b} deals than {@code a}, in percent; 0 when {@code a} deals nothing. */
	static double gain(double a, double b)
	{
		return a <= 0 ? 0 : (b / a - 1) * 100;
	}

	/** Mean damage of a hit rolled evenly from 0 to max that landed: a 0 counts as 1, then the flat armour comes off. */
	static double landed(long max, int flatArmour)
	{
		if (max < 0)
		{
			return 0;
		}
		double sum = 0;
		for (long h = 0; h <= max; h++)
		{
			sum += Math.max(1, Math.max(1, h) - flatArmour);
		}
		return sum / (max + 1);
	}

	/** The standard chance for an attack roll to beat a defence roll. */
	static double chance(long attack, long defence)
	{
		if (attack < 0 || defence < 0)
		{
			return attack >= 0 ? 1 : 0;
		}
		return attack > defence
			? 1 - (defence + 2) / (2.0 * (attack + 1))
			: attack / (2.0 * (defence + 1));
	}
}
