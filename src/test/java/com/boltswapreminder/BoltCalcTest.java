package com.boltswapreminder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Ruby and diamond bolts against a few monsters at several healths, compared with the OSRS Wiki
 * calculator. What the plugin shows is which of the two deals more, so the test
 * checks each figure and then that the better bolt is the same as the calculator's.
 */
public class BoltCalcTest
{
	/** How far a figure may be from the calculator's, as a fraction of it. */
	private static final double FIGURE = 0.04;
	/** A difference between the two bolts under this, in percent, is a tie: either answer is right. */
	private static final double TIE = 2.0;

	private static final class Case
	{
		String line;
		String situation;
		BoltCalc.Bolt bolt;
		double expected;
		double got;
	}

	private List<Case> cases() throws IOException
	{
		BoltData data = new BoltData();
		List<Case> out = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
			BoltCalcTest.class.getResourceAsStream("bolts.tsv"), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				if (line.isEmpty() || line.startsWith("#"))
				{
					continue;
				}
				String[] c = line.split("\t", -1);
				BoltData.Monster m = data.monster(Integer.parseInt(c[4]));
				BoltCalc.Shot s = new BoltCalc.Shot();
				s.rangedLevel = 112; // 99 and a ranging potion
				s.prayerAccuracy = 120; // Rigour
				s.prayerStrength = 123;
				s.rangedAttack = Integer.parseInt(c[7]);
				s.rangedStrength = Integer.parseInt(c[8]);
				s.speed = Integer.parseInt(c[9]);
				s.zaryte = c[2].startsWith("Zaryte");
				s.kandarinDiary = "1".equals(c[3]);
				BoltCalc.Target t = new BoltCalc.Target();
				t.defenceLevel = m.defenceLevel;
				t.heavyDefence = m.heavyDefence;
				t.flatArmour = m.flatArmour;
				t.hitpoints = Integer.parseInt(c[6]);
				Case k = new Case();
				k.line = c[1] + ", " + c[2] + ", " + c[5] + " at " + c[6] + (s.kandarinDiary ? ", diary" : "");
				// the same crossbow, tier of bolt, diary, monster and health: the two bolts to compare
				k.situation = c[2] + "|" + c[1].contains("dragon") + "|" + c[3] + "|" + c[4] + "|" + c[6];
				k.bolt = "ruby".equals(c[0]) ? BoltCalc.Bolt.RUBY : BoltCalc.Bolt.DIAMOND;
				k.expected = Double.parseDouble(c[10]);
				k.got = BoltCalc.perTick(k.bolt, s, t);
				out.add(k);
			}
		}
		return out;
	}

	@Test
	public void figuresAreCloseToTheCalculator() throws IOException
	{
		List<String> off = new ArrayList<>();
		List<Case> cases = cases();
		for (Case k : cases)
		{
			if (Math.abs(k.got - k.expected) > FIGURE * k.expected)
			{
				off.add(String.format("%s: %.3f, calculator %.3f", k.line, k.got, k.expected));
			}
		}
		assertTrue(cases.size() > 500);
		assertTrue(off.size() + " of " + cases.size() + " off: " + off, off.isEmpty());
	}

	@Test
	public void theBetterBoltIsTheCalculators() throws IOException
	{
		Map<String, Case[]> pairs = new HashMap<>();
		for (Case k : cases())
		{
			pairs.computeIfAbsent(k.situation, s -> new Case[2])[k.bolt == BoltCalc.Bolt.RUBY ? 0 : 1] = k;
		}
		List<String> off = new ArrayList<>();
		int decided = 0;
		for (Case[] p : pairs.values())
		{
			if (p[0] == null || p[1] == null)
			{
				continue;
			}
			double calculator = BoltCalc.gain(p[0].expected, p[1].expected);
			double ours = BoltCalc.gain(p[0].got, p[1].got);
			if (Math.abs(calculator) < TIE)
			{
				continue;
			}
			decided++;
			if ((calculator > 0) != (ours > 0))
			{
				off.add(String.format("%s: diamond %+.1f%%, calculator %+.1f%%", p[0].line, ours, calculator));
			}
		}
		assertTrue(decided > 150);
		assertTrue(off.size() + " of " + decided + " off: " + off, off.isEmpty());
	}

	@Test
	public void theRubyEffectFollowsTheMonstersHealth()
	{
		assertEquals(100, BoltCalc.rubyHit(2000, false));
		assertEquals(110, BoltCalc.rubyHit(2000, true));
		assertEquals(51, BoltCalc.rubyHit(255, false));
		assertEquals(56, BoltCalc.rubyHit(255, true));
		assertEquals(2, BoltCalc.rubyHit(10, false));
	}

	@Test
	public void underTenHitpointsTheRubyEffectIsOff()
	{
		BoltCalc.Shot s = new BoltCalc.Shot();
		BoltCalc.Target t = new BoltCalc.Target();
		t.hitpoints = 500;
		s.playerHitpoints = 9;
		assertEquals(7.0, BoltCalc.withEffect(BoltCalc.Bolt.RUBY, s, t, 40, 7.0), 0);
		s.playerHitpoints = 10;
		assertTrue(BoltCalc.withEffect(BoltCalc.Bolt.RUBY, s, t, 40, 7.0) > 7.0);
	}

	@Test
	public void theRubyEffectIsCappedLowerOnTheGemstoneCrab()
	{
		BoltCalc.Shot s = new BoltCalc.Shot();
		BoltCalc.Target t = new BoltCalc.Target();
		t.hitpoints = 50000;
		t.endlessHealth = true;
		assertEquals(0.06 * 60, BoltCalc.withEffect(BoltCalc.Bolt.RUBY, s, t, 40, 0.0), 1e-9);
		s.zaryte = true;
		assertEquals(0.06 * 66, BoltCalc.withEffect(BoltCalc.Bolt.RUBY, s, t, 40, 0.0), 1e-9);
		t.endlessHealth = false;
		assertEquals(0.06 * 110, BoltCalc.withEffect(BoltCalc.Bolt.RUBY, s, t, 40, 0.0), 1e-9);
	}

	@Test
	public void boltsInTheQuiverAreShotOnlyWhenTheSlotHoldsNone()
	{
		assertTrue(BoltSwapReminderPlugin.shotFromQuiver("Rada's blessing 4", "Ruby dragon bolts (e)"));
		assertTrue(BoltSwapReminderPlugin.shotFromQuiver(null, "Ruby bolts (e)"));
		assertTrue(BoltSwapReminderPlugin.shotFromQuiver("Dragon arrow", "Diamond bolts (e)"));
		assertFalse(BoltSwapReminderPlugin.shotFromQuiver("Diamond dragon bolts (e)", "Ruby dragon bolts (e)"));
		assertFalse(BoltSwapReminderPlugin.shotFromQuiver("Rada's blessing 4", "Dragon arrow"));
		assertFalse(BoltSwapReminderPlugin.shotFromQuiver("Ruby bolts (e)", null));
	}

	@Test
	public void theMonsterIsRememberedForFifteenSecondsAfterTheLastAttack()
	{
		assertTrue(BoltSwapReminderPlugin.remembered(100, 100));
		assertTrue(BoltSwapReminderPlugin.remembered(125, 100));
		assertFalse(BoltSwapReminderPlugin.remembered(126, 100));
		assertFalse(BoltSwapReminderPlugin.remembered(50, -1));
	}

	@Test
	public void withNoBoltsOnRubyComesFirstThenDiamondThenAnyOthers()
	{
		assertEquals(2, BoltSwapReminderPlugin.preferred(new String[]{"Shark", "Diamond bolts (e)", "Ruby dragon bolts (e)"}));
		assertEquals(1, BoltSwapReminderPlugin.preferred(new String[]{"Runite bolts", "Diamond dragon bolts (e)", null}));
		assertEquals(0, BoltSwapReminderPlugin.preferred(new String[]{"Runite bolts", "Dragon bolts (unf)"}));
		assertEquals(-1, BoltSwapReminderPlugin.preferred(new String[]{"Dragon bolts (unf)", "Shark", null}));
	}

	@Test
	public void theDifferenceIsWrittenWithItsSign()
	{
		assertEquals("+0.4%", BoltSwapReminderPlugin.gainText(0.44));
		assertEquals("+12%", BoltSwapReminderPlugin.gainText(12.3));
		assertEquals("-5.2%", BoltSwapReminderPlugin.gainText(-5.2));
		assertEquals("-18%", BoltSwapReminderPlugin.gainText(-18.4));
		assertEquals("Ruby +15%", BoltSwapReminderPlugin.label(BoltCalc.Bolt.RUBY, "+15%"));
		assertEquals("Diamond", BoltSwapReminderPlugin.label(BoltCalc.Bolt.DIAMOND, ""));
	}
}
