package com.boltswapreminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * The Defence the monster is left with after special attacks: the same cases of the OSRS Wiki DPS calculator the Combat
 * Check plugin checks its rules against (drains.tsv), and what this plugin does with it: the bolts compared on the Defence
 * left, the fights immune to drains, a change of form, the party's special attacks and the order they landed in.
 */
public class DefenceDrainsTest
{
	/** The special attacks of the calculator's counts, added in its order, which is an order they can land in. */
	private static DefenceDrains calculatorOrder(boolean accursed, int elderMaul, int dwh, int arclight, int emberlight,
		int tonalztic, int seercull, int bgs, int ayak)
	{
		DefenceDrains d = new DefenceDrains();
		if (accursed)
		{
			d.add(SpecialWeapon.ACCURSED_SCEPTRE, 1, false);
		}
		for (int i = 0; i < elderMaul; i++)
		{
			d.add(SpecialWeapon.ELDER_MAUL, 1, false);
		}
		for (int i = 0; i < dwh; i++)
		{
			d.add(SpecialWeapon.DRAGON_WARHAMMER, 1, false);
		}
		for (int i = 0; i < arclight; i++)
		{
			d.add(SpecialWeapon.ARCLIGHT, 1, false);
		}
		for (int i = 0; i < emberlight; i++)
		{
			d.add(SpecialWeapon.EMBERLIGHT, 1, false);
		}
		for (int left = tonalztic; left > 0; left -= 2)
		{
			d.add(SpecialWeapon.TONALZTICS_OF_RALOS, Math.min(2, left), false);
		}
		if (seercull > 0)
		{
			d.add(SpecialWeapon.SEERCULL, seercull, false);
		}
		if (bgs > 0)
		{
			d.add(SpecialWeapon.BANDOS_GODSWORD, bgs, false);
		}
		if (ayak > 0)
		{
			d.add(SpecialWeapon.EYE_OF_AYAK, ayak, false);
		}
		return d;
	}

	/** Every case of the calculator: the same Defence left, from the same full levels. */
	@Test
	public void sameDefenceAsTheCalculator() throws IOException
	{
		int rows = 0;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			DefenceDrainsTest.class.getResourceAsStream("drains.tsv"), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = in.readLine()) != null)
			{
				if (line.startsWith("#") || line.trim().isEmpty())
				{
					continue;
				}
				String[] c = line.split("\t", -1);
				int npc = Integer.parseInt(c[1]);
				boolean demon = "1".equals(c[3]);
				int defence = Integer.parseInt(c[4]);
				DefenceDrains d = calculatorOrder("1".equals(c[7]), Integer.parseInt(c[8]), Integer.parseInt(c[9]),
					Integer.parseInt(c[10]), Integer.parseInt(c[11]), Integer.parseInt(c[12]), Integer.parseInt(c[13]),
					Integer.parseInt(c[14]), Integer.parseInt(c[15]));
				DefenceDrains.Levels full = DefenceDrains.full(defence);
				full.magic = Integer.parseInt(c[5]);
				full.magicDefence = Integer.parseInt(c[6]);
				assertEquals(c[0], Integer.parseInt(c[16]), d.apply(full, demon, DefenceDrains.floor(npc, defence)).defence);
				rows++;
			}
		}
		assertTrue("the file has its cases", rows >= 62);
	}

	/** The report of 2026-11-26: a Bandos godsword on Vorkath. Each special attack is taken off what the one before left. */
	@Test
	public void vorkathAfterSpecialAttacks()
	{
		int vorkath = 8061;
		DefenceDrains bgs = new DefenceDrains();
		bgs.add(SpecialWeapon.BANDOS_GODSWORD, 60, false);
		assertEquals(154, left(vorkath, 214, bgs).defence);
		DefenceDrains hammers = new DefenceDrains();
		hammers.add(SpecialWeapon.DRAGON_WARHAMMER, 40, false);
		hammers.add(SpecialWeapon.ELDER_MAUL, 1, false);
		// 214 - 64 = 150, then 150 - 52 = 98
		assertEquals(98, left(vorkath, 214, hammers).defence);
		DefenceDrains missed = new DefenceDrains();
		missed.add(SpecialWeapon.DRAGON_WARHAMMER, 0, false);
		missed.add(SpecialWeapon.BANDOS_GODSWORD, 0, false);
		assertEquals("a miss lowers nothing", 214, left(vorkath, 214, missed).defence);
		DefenceDrains huge = new DefenceDrains();
		huge.add(SpecialWeapon.BANDOS_GODSWORD, 300, false);
		assertEquals("never below 0", 0, left(vorkath, 214, huge).defence);
		assertEquals("none counted: the table's", 214, left(vorkath, 214, null).defence);
	}

	/** The Tonalztics of Ralos lowers Defence by a part of Magic, which this plugin's table does not have: nothing. */
	@Test
	public void tonalzticsLowerNothingWithoutMagic()
	{
		DefenceDrains d = new DefenceDrains();
		d.add(SpecialWeapon.TONALZTICS_OF_RALOS, 2, false);
		assertEquals(214, left(8061, 214, d).defence);
	}

	/** Floors, fights that take no drain, and the awakened fights of Desert Treasure II. */
	@Test
	public void floorsAndImmuneFights()
	{
		DefenceDrains three = new DefenceDrains();
		three.add(SpecialWeapon.ELDER_MAUL, 1, false);
		three.add(SpecialWeapon.ELDER_MAUL, 1, false);
		three.add(SpecialWeapon.ELDER_MAUL, 1, false);
		assertEquals("Sotetseg stops at 100", 100, left(8388, 200, three).defence);
		assertEquals("Demonic Brutus takes none", 300, left(15628, 300, three).defence);
		// the Leviathan after the quest takes them; awakened (the health display shows more than the row's 900) it does not
		assertFalse(DefenceDrains.awakened(12214, 900, 900));
		assertFalse(DefenceDrains.awakened(12214, 900, 0));
		assertTrue(DefenceDrains.awakened(12214, 900, 2700));
		assertFalse("not a Desert Treasure II boss", DefenceDrains.awakened(8061, 750, 2700));
		assertEquals(250, DefenceDrains.left(12214, false, true, DefenceDrains.full(250), three, 0).defence);
		assertTrue(DefenceDrains.left(12214, false, false, DefenceDrains.full(250), three, 0).defence < 250);
	}

	/** Arclight takes a twentieth of the full Defence and 1, two twentieths on a demon (the table's d). */
	@Test
	public void arclightOnDemons() throws IOException
	{
		BoltData data = new BoltData();
		assertTrue("K'ril Tsutsaroth", data.monster(3129).demon);
		assertFalse(data.monster(8061).demon);
		DefenceDrains d = new DefenceDrains();
		d.add(SpecialWeapon.ARCLIGHT, 1, false);
		assertEquals(270 - (2 * 270 / 20 + 1), DefenceDrains.left(3129, true, false, DefenceDrains.full(270), d, 0).defence);
		assertEquals(270 - (270 / 20 + 1), DefenceDrains.left(3129, false, false, DefenceDrains.full(270), d, 0).defence);
	}

	/** The levels come back with time: one a minute (100 ticks) for most monsters, counted from the first drain. */
	@Test
	public void levelsComeBack()
	{
		DefenceDrains d = new DefenceDrains();
		d.add(SpecialWeapon.BANDOS_GODSWORD, 60, false, 1000);
		DefenceDrains.Levels full = DefenceDrains.full(214);
		assertEquals(154, DefenceDrains.left(8061, false, false, full, d, 0).defence);
		assertEquals(154, DefenceDrains.left(8061, false, false, full, d, 99).defence);
		assertEquals(155, DefenceDrains.left(8061, false, false, full, d, 100).defence);
		assertEquals(214, DefenceDrains.left(8061, false, false, full, d, 100 * 1000).defence);
	}

	/** A change of form keeps the drains only for the same Defence, and never where the game gives it back. */
	@Test
	public void changeOfForm() throws IOException
	{
		BoltData data = new BoltData();
		// Vorkath's two versions have different Defence; one form into itself keeps them
		assertTrue(BoltSwapReminderPlugin.forgetsOnChange(data.monster(8060), data.monster(8061), 8060, 8061));
		assertFalse(BoltSwapReminderPlugin.forgetsOnChange(data.monster(8061), data.monster(8061), 8061, 8061));
		assertTrue("a form not in the table", BoltSwapReminderPlugin.forgetsOnChange(data.monster(8061), null, 8061, 1));
		// Vorkath back to sleep after a kill: the next one starts from 214, though asleep and awake have the same Defence
		assertTrue(BoltSwapReminderPlugin.forgetsOnChange(data.monster(8061), data.monster(8059), 8061, 8059));
		assertEquals(data.monster(8061).defenceLevel, data.monster(8059).defenceLevel);
		// Sotetseg into the maze: his Defence comes back, whatever the rows say
		assertTrue(BoltSwapReminderPlugin.forgetsOnChange(data.monster(8388), data.monster(8387), 8388, 8387));
	}

	/** The drains of each monster, by its index and the monster itself, in the order the hits landed. */
	@Test
	public void trackerOrderAndForget()
	{
		DrainTracker t = new DrainTracker();
		Object vorkath = new Object();
		// a Dragon warhammer that landed on tick 10, then the party's Bandos godsword of 50 that landed on tick 9
		t.add(5, vorkath, SpecialWeapon.DRAGON_WARHAMMER, 30, false, 10);
		t.add(5, vorkath, SpecialWeapon.BANDOS_GODSWORD, 50, false, 9);
		// 214 - 50 = 164, then 164 - 49 = 115 (the hammer first would give 214 - 64 = 150, then 100)
		assertEquals(115, DefenceDrains.left(8061, false, false, DefenceDrains.full(214), t.of(5, vorkath), 0).defence);
		assertEquals(3, t.ticksSince(5, vorkath, 13));
		// another monster under the same index has none
		Object other = new Object();
		assertNull(t.of(5, other));
		assertNotNull(t.of(5, vorkath));
		t.forget(5, vorkath);
		assertNull(t.of(5, vorkath));
		// a special attack that lowers nothing counts nothing
		t.add(6, other, SpecialWeapon.BANDOS_GODSWORD, 0, false, 1);
		assertNull(t.of(6, other));
	}

	/** The party's special attacks go on the tick their hit landed: an Elder maul's message comes on it, the others' a tick after. */
	@Test
	public void partyTicks()
	{
		assertEquals(20, BoltSwapReminderPlugin.partyHitTick(SpecialWeapon.ELDER_MAUL, 20));
		assertEquals(19, BoltSwapReminderPlugin.partyHitTick(SpecialWeapon.BANDOS_GODSWORD, 20));
	}

	/** The weapons read as the Special Attack Counter's, the ornamented ones too. */
	@Test
	public void specialWeapons()
	{
		assertSame(SpecialWeapon.BANDOS_GODSWORD, BoltSwapReminderPlugin.specialWeapon(ItemID.BGS));
		assertSame(SpecialWeapon.BANDOS_GODSWORD, BoltSwapReminderPlugin.specialWeapon(ItemID.BGSG));
		assertSame(SpecialWeapon.DRAGON_WARHAMMER, BoltSwapReminderPlugin.specialWeapon(ItemID.DRAGON_WARHAMMER_ORNAMENT));
		assertSame(SpecialWeapon.ELDER_MAUL, BoltSwapReminderPlugin.specialWeapon(ItemID.ELDER_MAUL_ORNAMENT));
		assertSame(SpecialWeapon.BARRELCHEST_ANCHOR, BoltSwapReminderPlugin.specialWeapon(ItemID.BRAIN_BROKEN_ANCHOR));
		assertSame(SpecialWeapon.DARKLIGHT, BoltSwapReminderPlugin.specialWeapon(ItemID.ARCLIGHT_INACTIVE));
		assertSame(SpecialWeapon.TONALZTICS_OF_RALOS, BoltSwapReminderPlugin.specialWeapon(ItemID.TONALZTICS_OF_RALOS_UNCHARGED));
		assertNull(BoltSwapReminderPlugin.specialWeapon(ItemID.DRAGONHUNTER_XBOW));
		// the hitsplat of the weapon: the last of the tick for an instant melee hit, the first for a delayed one
		assertEquals(Integer.valueOf(55), BoltSwapReminderPlugin.weaponHitsplat(SpecialWeapon.BANDOS_GODSWORD, Arrays.asList(7, 55)));
		assertEquals(Integer.valueOf(1), BoltSwapReminderPlugin.landedHits(Arrays.asList(20, 0), 2));
		assertNull(BoltSwapReminderPlugin.landedHits(Arrays.asList(20), 2));
	}

	/**
	 * What it changes: on Vorkath after the quest (Defence 214, 750 hitpoints), with 99 Ranged boosted, Rigour and a
	 * crossbow of +120 attack and strength, a Bandos godsword of 60 takes the Defence to 154. Then the ruby bolts' lead at
	 * full health is smaller, and the health under which the diamond bolts are the better ones is another.
	 */
	@Test
	public void theBetterBoltMovesWithTheDefence() throws IOException
	{
		BoltData.Monster vorkath = new BoltData().monster(8061);
		BoltCalc.Shot s = new BoltCalc.Shot();
		s.rangedLevel = 112;
		s.prayerAccuracy = 120;
		s.prayerStrength = 123;
		s.rangedAttack = 120;
		s.rangedStrength = 120;
		s.speed = 6;
		double fullLead = gainOfRuby(s, vorkath, 214, 750);
		double drainedLead = gainOfRuby(s, vorkath, 154, 750);
		int fullSwitch = switchHealth(s, vorkath, 214);
		int drainedSwitch = switchHealth(s, vorkath, 154);
		System.out.printf(java.util.Locale.ROOT, "ruby at 750: %+.1f%% (Def 214), %+.1f%% (Def 154); diamond better under %d (Def 214), %d (Def 154)%n",
			fullLead, drainedLead, fullSwitch, drainedSwitch);
		assertTrue(fullLead - drainedLead > 2);
		assertTrue(drainedSwitch < fullSwitch);
	}

	/** The highest health at which the diamond bolts deal more than the ruby bolts, at this Defence. */
	private static int switchHealth(BoltCalc.Shot s, BoltData.Monster m, int defence)
	{
		for (int hp = m.hitpoints; hp > 0; hp--)
		{
			if (gainOfRuby(s, m, defence, hp) < 0)
			{
				return hp;
			}
		}
		return 0;
	}

	/** How much more ruby bolts deal than diamond bolts, in percent, at this Defence and health. */
	private static double gainOfRuby(BoltCalc.Shot s, BoltData.Monster m, int defence, int hp)
	{
		BoltCalc.Target t = new BoltCalc.Target();
		t.defenceLevel = defence;
		t.heavyDefence = m.heavyDefence;
		t.flatArmour = m.flatArmour;
		t.hitpoints = hp;
		return BoltCalc.gain(BoltCalc.perTick(BoltCalc.Bolt.DIAMOND, s, t), BoltCalc.perTick(BoltCalc.Bolt.RUBY, s, t));
	}

	private static DefenceDrains.Levels left(int npcId, int defence, DefenceDrains d)
	{
		return DefenceDrains.left(npcId, false, false, DefenceDrains.full(defence), d, 0);
	}
}
