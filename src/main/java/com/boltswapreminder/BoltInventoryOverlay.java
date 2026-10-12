/*
 * Copyright (c) 2026, GuimGod
 * All rights reserved. Licensed under the BSD 2-Clause License (see LICENSE).
 */
package com.boltswapreminder;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/** Outlines, in the inventory, the bolts the reminder is about. */
class BoltInventoryOverlay extends WidgetItemOverlay
{
	private final BoltSwapReminderPlugin plugin;
	private final BoltSwapReminderConfig config;
	private final ItemManager itemManager;

	@Inject
	BoltInventoryOverlay(BoltSwapReminderPlugin plugin, BoltSwapReminderConfig config, ItemManager itemManager)
	{
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		showOnInventory();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		BoltSwapReminderPlugin.Reminder reminder = plugin.reminder();
		if (reminder == null || !reminder.better || !config.markInventory() || itemManager.canonicalize(itemId) != reminder.itemId)
		{
			return;
		}
		Rectangle bounds = widgetItem.getCanvasBounds();
		graphics.setColor(config.colour());
		graphics.setStroke(new BasicStroke(2));
		graphics.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);
	}
}
