/*
 * Copyright (c) 2026, GuimGod
 * All rights reserved. Licensed under the BSD 2-Clause License (see LICENSE).
 */
package com.boltswapreminder;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("boltswapreminder")
public interface BoltSwapReminderConfig extends Config
{
	@ConfigItem(
		keyName = "alwaysShow",
		name = "Show the difference all the time",
		description = "Show the other bolts and the difference even while the ones you have on are still the better ones (\"-5%\"), so you can see the switch coming and be ready for it. Off: only once the other bolts deal more",
		position = 1
	)
	default boolean alwaysShow()
	{
		return false;
	}

	@ConfigItem(
		keyName = "markInventory",
		name = "Mark them in the inventory",
		description = "Outline the better bolts in your inventory",
		position = 3
	)
	default boolean markInventory()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "colour",
		name = "Colour",
		description = "Colour of the outline in the inventory",
		position = 4
	)
	default Color colour()
	{
		return new Color(0, 255, 255);
	}
}
