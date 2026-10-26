package com.boltswapreminder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AscensionCrossbowTest
{
	@Test
	public void theAscensionCrossbowsGetNoReminder()
	{
		// they shoot only ascension bolts (OSRS Wiki, Ascension crossbows): ruby or diamond dragon bolts (e) in the
		// inventory must not be put on them
		assertFalse(BoltSwapReminderPlugin.firesGemBolts("Ascension crossbows"));
		assertTrue(BoltSwapReminderPlugin.firesGemBolts("Zaryte crossbow"));
		assertTrue(BoltSwapReminderPlugin.firesGemBolts("Armadyl crossbow"));
		assertTrue(BoltSwapReminderPlugin.firesGemBolts("Dragon hunter crossbow"));
		assertFalse(BoltSwapReminderPlugin.firesGemBolts("Twisted bow"));
		assertFalse(BoltSwapReminderPlugin.firesGemBolts(null));
		// and their own enchanted bolts are neither kind the reminder compares
		assertNull(BoltSwapReminderPlugin.kind("Diamond ascension bolts (e)"));
		assertNull(BoltSwapReminderPlugin.kind("Onyx ascension bolts (e)"));
	}
}
