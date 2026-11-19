package com.boltswapreminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Every form a monster is fought in has its row (GitHub issue #1: Vorkath awake, the Demonic gorillas, each phase of the
 * Alchemical Hydra). NPC ids: OSRS Wiki infoboxes (Bucket table infobox_monster, read 2026-11-19); numbers: the OSRS Wiki DPS
 * calculator's monster data of 2026-11-11, then the wiki's infoboxes for forms the calculator
 * has no version for, then the game cache of 2026-11-18 for NPCs defined as the same fight.
 */
public class MonsterIdsTest
{
	private static void is(BoltData data, int npc, String name, int defence, int hitpoints)
	{
		BoltData.Monster m = data.monster(npc);
		assertNotNull("no row for " + npc, m);
		assertEquals(name, m.name);
		assertEquals("defence of " + npc, defence, m.defenceLevel);
		assertEquals("hitpoints of " + npc, hitpoints, m.hitpoints);
	}

	@Test
	public void theFightingFormsHaveRows() throws Exception
	{
		BoltData data = new BoltData();
		// Vorkath: 8058 and 8060 in Dragon Slayer II, 8059 and 8061 after it (asleep, awake)
		is(data, 8060, "Vorkath", 164, 460);
		is(data, 8061, "Vorkath", 214, 750);
		assertTrue(data.monster(8061).undead);
		assertEquals(Boolean.TRUE, data.onTask("Blue dragons", data.monster(8061)));
		for (int npc : new int[]{7144, 7145, 7146, 7147, 7148, 7149})
		{
			is(data, npc, "Demonic gorilla", 200, 380);
		}
		for (int npc : new int[]{8615, 8616, 8617, 8618, 8619, 8620, 8621, 8622})
		{
			is(data, npc, "Alchemical Hydra", 100, 1100);
		}
	}

	@Test
	public void aFormWithOtherNumbersIsNeverTakenForAnother() throws Exception
	{
		BoltData data = new BoltData();
		// forms the calculator has no row for take the OSRS Wiki's own numbers for them, never those of another form of
		// the same name: Tekton (Challenge Mode) 246 Defence and 450 hitpoints, Callisto in the PvM Arena 440 and 255
		is(data, 7545, "Tekton", 246, 450);
		is(data, 6503, "Callisto (PvM Arena)", 440, 255);
		// the Archive Guardian on the ground (Fractured Archive): 1100 hitpoints, heavy ranged defence 50
		is(data, 16660, "Archive Guardian", 260, 1100);
		assertEquals(50, data.monster(16660).heavyDefence);
		// a form one id has in several modes with other numbers (the Deadman bosses) is left out, not guessed
		assertNull(data.monster(12439));
		// an NPC the game defines as the same fight as a listed one (the Lava dragons of 6593)
		is(data, 12607, "Lava dragon", 220, 230);
		is(data, 12608, "Lava dragon", 220, 230);
		// the quest's Phantom Muspah (Secrets of the North) is not the boss: 550 hitpoints, heavy ranged defence 160 in
		// the melee form, 0 in the teleporting one
		is(data, 12074, "Phantom Muspah", 200, 550);
		assertEquals(160, data.monster(12074).heavyDefence);
		is(data, 12081, "Phantom Muspah", 200, 550);
		assertEquals(0, data.monster(12081).heavyDefence);
		is(data, 12078, "Phantom Muspah", 200, 850);
		// the Feral Vampyres of Meiyerditch: Defence 30
		is(data, 3707, "Feral Vampyre", 30, 80);
		is(data, 3708, "Feral Vampyre", 30, 80);
		// the Giant Rock Crab of the Tonali Cavern: heavy ranged defence 250
		assertEquals(250, data.monster(14424).heavyDefence);
	}

	@Test
	public void theMaidenKeepsHerMostHealthInEveryForm() throws Exception
	{
		BoltData data = new BoltData();
		// she changes id at 70, 50 and 30% of her health; the bar is a fraction of her most (3500), so every form has it
		for (int npc : new int[]{8360, 8361, 8362, 8363, 8364, 8365})
		{
			is(data, npc, "The Maiden of Sugadinti", 200, 3500);
		}
		// Hard Mode: the later ids too
		for (int npc = 10822; npc <= 10827; npc++)
		{
			is(data, npc, "The Maiden of Sugadinti", 200, 3500);
		}
		assertEquals(3500, BoltSwapReminderPlugin.healthNow(data.monster(8363).hitpoints, 15, 30) * 2);
	}

	@Test
	public void theReportedVorkathSetupIsCompared() throws Exception
	{
		// the report of 2026-10-29: a blessing in the ammunition slot, ruby dragon bolts (e) in Dizana's quiver, diamond
		// dragon bolts (e) in the inventory, a Dragon hunter crossbow (b) and the Salve amulet(ei), at Vorkath
		assertTrue(BoltSwapReminderPlugin.shotFromQuiver("Honourable blessing", "Ruby dragon bolts (e)"));
		assertTrue(BoltSwapReminderPlugin.firesGemBolts("Dragon hunter crossbow (b)"));
		assertEquals(BoltCalc.Bolt.RUBY, BoltSwapReminderPlugin.kind("Ruby dragon bolts (e)"));
		assertEquals(BoltCalc.Bolt.DIAMOND, BoltSwapReminderPlugin.kind("Diamond dragon bolts (e)"));
		int[] salve = BoltSwapReminderPlugin.headOrNeck("Void ranger helm (l)(or)", "Salve amulet(ei)",
			new BoltData().monster(8061).undead, false);
		assertEquals(6, salve[0]);
		assertEquals(5, salve[1]);
	}

	@Test
	public void noIdHasTwoRows() throws Exception
	{
		Set<String> seen = new HashSet<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
			BoltData.class.getResourceAsStream("monsters.tsv"), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				if (!line.isEmpty() && !line.startsWith("#"))
				{
					String id = line.substring(0, line.indexOf('\t'));
					assertTrue("two rows for " + id, seen.add(id));
				}
			}
		}
	}
}
