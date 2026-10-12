/*
 * Copyright (c) 2026, GuimGod
 * All rights reserved. Licensed under the BSD 2-Clause License (see LICENSE).
 */
package com.boltswapreminder;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;

/** A small movable box, one line high: the icon of the other bolts and the difference. */
class BoltSwapReminderOverlay extends Overlay
{
	private static final int ICON_H = 22;
	private static final int PAD = 3;
	private static final Color BACK = new Color(40, 0, 0, 200);
	private static final Color BETTER_BACK = new Color(0, 40, 0, 200);
	private static final Color BETTER = new Color(60, 220, 60);
	private static final Color NOT_YET = new Color(220, 60, 60);

	private final BoltSwapReminderPlugin plugin;
	private final ItemManager itemManager;

	@Inject
	BoltSwapReminderOverlay(BoltSwapReminderPlugin plugin, ItemManager itemManager)
	{
		this.plugin = plugin;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		BoltSwapReminderPlugin.Reminder reminder = plugin.reminder();
		if (reminder == null)
		{
			return null;
		}
		graphics.setFont(FontManager.getRunescapeFont());
		FontMetrics fm = graphics.getFontMetrics();
		BufferedImage icon = itemManager.getImage(reminder.itemId);
		// bolts that are in the quiver, under the ones in the slot: taking the slot's off switches to them
		String text = reminder.inQuiver ? reminder.text + " (quiver)" : reminder.text;
		int iconW = icon == null ? 0 : icon.getWidth() * ICON_H / Math.max(1, icon.getHeight());
		int h = Math.max(ICON_H, fm.getHeight()) + 2 * PAD;
		int w = PAD + iconW + (iconW > 0 ? PAD : 0) + fm.stringWidth(text) + PAD;
		// green once the other bolts are the better ones; red while they are still worse and the difference is only shown for information
		Color colour = reminder.better ? BETTER : NOT_YET;
		graphics.setColor(reminder.better ? BETTER_BACK : BACK);
		graphics.fillRect(0, 0, w, h);
		graphics.setColor(colour);
		graphics.drawRect(0, 0, w - 1, h - 1);
		int x = PAD;
		if (icon != null)
		{
			graphics.drawImage(icon, x, (h - ICON_H) / 2, iconW, ICON_H, null);
			x += iconW + PAD;
		}
		int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();
		graphics.setColor(Color.BLACK);
		graphics.drawString(text, x + 1, baseline + 1);
		graphics.setColor(Color.YELLOW);
		graphics.drawString(text, x, baseline);
		return new Dimension(w, h);
	}
}
