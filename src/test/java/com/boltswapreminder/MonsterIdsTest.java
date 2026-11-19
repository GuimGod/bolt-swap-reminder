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
 * Alchemical Hydra). NPC ids: OSRS Wiki infoboxes (Bucket table infobox_monster, read 2026-11-19).
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
	public void anUnlistedIdFallsBackOnTheNameOnlyWhenItsRowsAgree() throws Exception
	{
		BoltData data = new BoltData();
		// every Demonic gorilla row is the same fight
		BoltData.Monster gorilla = data.monster(999999, "Demonic gorilla");
		assertNotNull(gorilla);
		assertEquals(200, gorilla.defenceLevel);
		// Vorkath's two versions differ: an unknown id is not guessed
		assertNull(data.monster(999999, "Vorkath"));
		assertNull(data.monster(999999, "Not a monster"));
		assertNull(data.monster(999999, null));
		// a listed id is never overridden by its name
		assertEquals(164, data.monster(8060, "Vorkath").defenceLevel);
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
