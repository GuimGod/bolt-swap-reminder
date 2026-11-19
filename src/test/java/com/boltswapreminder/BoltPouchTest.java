package com.boltswapreminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The bolts in the bolt pouch count as carried (report of 2026-11-03, "bolt pouch"): the pouch swaps them in from the
 * Worn Equipment tab, except with Dizana's quiver on the player (OSRS Wiki, Bolt pouch).
 */
public class BoltPouchTest
{
	@Test
	public void thePouchCountsOnlyWithoutDizanasQuiver()
	{
		// the reported setup: Armadyl crossbow, ruby dragon bolts (e) worn, the pouch in the inventory, no quiver
		String[] worn = {"Crystal helm", "Masori assembler", "Necklace of rupture", "Armadyl crossbow", "Ruby dragon bolts (e)"};
		String[] inventory = {"Toxic blowpipe", "Bolt pouch", null, "Anglerfish"};
		assertTrue(BoltSwapReminderPlugin.pouchSwaps(worn, inventory));
		// no pouch
		assertFalse(BoltSwapReminderPlugin.pouchSwaps(worn, new String[]{"Toxic blowpipe", null}));
		// the quiver worn or carried, any of its kinds, turns the pouch's swap off
		assertFalse(BoltSwapReminderPlugin.pouchSwaps(new String[]{"Blessed dizana's quiver", "Armadyl crossbow"}, inventory));
		assertFalse(BoltSwapReminderPlugin.pouchSwaps(worn, new String[]{"Bolt pouch", "Dizana's quiver (uncharged)"}));
		// a pouch worn is not a thing; only the inventory's counts
		assertFalse(BoltSwapReminderPlugin.pouchSwaps(new String[]{"Bolt pouch"}, new String[]{null}));
	}

	@Test
	public void theInventoryOutlinesThePouch()
	{
		BoltSwapReminderPlugin.Reminder inPouch = new BoltSwapReminderPlugin.Reminder(21946, "Diamond +5.0%", true, false, true);
		assertEquals(ItemID.XBOWS_BOLT_POUCH, inPouch.markedItem());
		assertEquals(9433, ItemID.XBOWS_BOLT_POUCH);
		BoltSwapReminderPlugin.Reminder carried = new BoltSwapReminderPlugin.Reminder(21946, "Diamond +5.0%", true, false);
		assertEquals(21946, carried.markedItem());
	}
}
