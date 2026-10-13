/*
 * Copyright (c) 2026, GuimGod
 * All rights reserved. Licensed under the BSD 2-Clause License (see LICENSE).
 */
package com.boltswapreminder;

import com.google.inject.Provides;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.LinkBrowser;

/**
 * With a crossbow in hand, ruby or diamond bolts (e) in the ammunition slot (or in Dizana's quiver) and
 * the other kind in the inventory, works out which of the two deals more to the monster being fought at the health it has
 * now, and shows the other bolts as soon as they are the better ones. It only draws: nothing is clicked, equipped or changed.
 */
@Slf4j
@PluginDescriptor(
	name = "Bolt Swap Reminder",
	description = "Reminds you which of your ruby and diamond bolts (e) deals more to the monster at its current health",
	tags = {"bolts", "ruby", "diamond", "crossbow", "ranged", "ammo", "switch", "reminder"}
)
public class BoltSwapReminderPlugin extends Plugin
{
	/** The plugin stays silent this long after the last sign of a fight with a player: 100 ticks are a minute. */
	static final int PLAYER_FIGHT_TICKS = 100;

	/** The other bolts are pointed out for any gain at all: a tenth of a percent is the smallest the notice can write. */
	static final double MINIMUM_GAIN = 0.1;

	/**
	 * The monster stays the one being fought this long after the last attack on it: 25 ticks are 15 seconds,
	 * enough to walk away from an attack or wait out a phase without the reminder going away.
	 */
	static final int REMEMBER_TICKS = 25;

	/** The Gemstone Crab: the ruby effect is capped lower on it. */
	static final int GEMSTONE_CRAB = 14779;

	/** The reminder to draw: the bolts in the inventory that deal more, and by how much. */
	static final class Reminder
	{
		final int itemId;
		final String text;
		/** False while the bolts worn are still the better ones and the difference is only shown for information. */
		final boolean better;
		/** True when those bolts are in Dizana's quiver, under the ones in the ammunition slot: taking these off switches. */
		final boolean inQuiver;

		Reminder(int itemId, String text, boolean better, boolean inQuiver)
		{
			this.itemId = itemId;
			this.text = text;
			this.better = better;
			this.inQuiver = inQuiver;
		}
	}

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BoltSwapReminderConfig config;

	@Inject
	private BoltSwapReminderOverlay overlay;

	@Inject
	private BoltInventoryOverlay inventoryOverlay;

	private BoltData data;
	private volatile Reminder reminder;
	private int lastPlayerFight = -1;
	private NPC lastNpc;
	private int lastAttack = -1;
	private int lastHealth;
	private int taskId = -1;
	private String taskName;

	@Provides
	BoltSwapReminderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BoltSwapReminderConfig.class);
	}

	@Override
	protected void startUp() throws IOException
	{
		data = new BoltData();
		overlayManager.add(overlay);
		overlayManager.add(inventoryOverlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(inventoryOverlay);
		reminder = null;
	}

	Reminder reminder()
	{
		return reminder;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Reminder found = null;
		try
		{
			if (client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null && data != null
				&& !fightingPlayer(client.getTickCount()))
			{
				found = work();
				if (found == null)
				{
					found = boltsToPutOn();
				}
			}
		}
		catch (RuntimeException e)
		{
			// a cache lookup that fails must not break the tick for every other plugin
			log.debug("could not compare the bolts", e);
		}
		reminder = found != null && (found.better || config.alwaysShow()) ? found : null;
	}

	/** The bolts in the inventory that deal more than the ones worn, or null. */
	private Reminder work()
	{
		Player me = client.getLocalPlayer();
		Actor interacting = me.getInteracting();
		int tick = client.getTickCount();
		if (interacting instanceof NPC)
		{
			if (interacting != lastNpc)
			{
				lastHealth = 0;
			}
			lastNpc = (NPC) interacting;
			lastAttack = tick;
		}
		else if (lastNpc == null || !remembered(tick, lastAttack) || lastNpc.isDead())
		{
			lastNpc = null;
			return null;
		}
		NPC npc = lastNpc;
		BoltData.Monster monster = data.monster(npc.getId());
		if (monster == null)
		{
			return null; // not a monster of the table
		}
		// a boss with the game's own health display gives its exact health; otherwise the bar's fraction of the table's,
		// and while the bar is hidden, the last health it showed
		int ratio = npc.getHealthRatio();
		int scale = npc.getHealthScale();
		int exact = client.getVarpValue(VarPlayerID.HPBAR_HUD_NPC) == npc.getId()
			&& client.getVarbitValue(VarbitID.HPBAR_HUD_BASEHP) > 0 ? client.getVarbitValue(VarbitID.HPBAR_HUD_HP) : 0;
		if (exact > 0)
		{
			lastHealth = exact;
		}
		else if (ratio > 0 && scale > 0)
		{
			lastHealth = healthNow(monster.hitpoints, ratio, scale);
		}
		if (lastHealth <= 0)
		{
			return null; // its health has not been seen yet
		}
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (worn == null || inventory == null)
		{
			return null;
		}
		Item weapon = worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		Item slot = worn.getItem(EquipmentInventorySlot.AMMO.getSlotIdx());
		if (weapon == null || weapon.getId() <= 0)
		{
			return null;
		}
		// the bolts being shot: the ammunition slot's, or the ones in Dizana's quiver when the slot holds no bolts
		int slotId = slot == null || slot.getId() <= 0 ? -1 : slot.getId();
		int quiverId = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT) > 0
			? client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO) : -1;
		boolean fromQuiver = shotFromQuiver(slotId > 0 ? name(slotId) : null, quiverId > 0 ? name(quiverId) : null);
		int ammoId = fromQuiver ? quiverId : slotId;
		String weaponName = name(weapon.getId());
		if (!weaponName.toLowerCase(Locale.ROOT).contains("crossbow"))
		{
			return null;
		}
		// no bolts on at all: the carried ruby and diamond bolts are compared with each other, starting from the first
		boolean none = ammoId <= 0 || !isBolts(name(ammoId));
		if (none)
		{
			ammoId = -1;
			for (Item item : inventory.getItems())
			{
				if (item != null && item.getId() > 0 && kind(name(item.getId())) != null)
				{
					ammoId = itemManager.canonicalize(item.getId());
					break;
				}
			}
			if (ammoId <= 0)
			{
				return null;
			}
			fromQuiver = false;
		}
		BoltCalc.Bolt wornKind = kind(name(ammoId));
		if (wornKind == null)
		{
			return null;
		}
		BoltCalc.Bolt otherKind = wornKind == BoltCalc.Bolt.RUBY ? BoltCalc.Bolt.DIAMOND : BoltCalc.Bolt.RUBY;
		// the other kind under the slot's bolts, in the quiver: taking the slot's off switches to it
		boolean inQuiver = !none && !fromQuiver && quiverId > 0 && kind(name(quiverId)) == otherKind;
		int carried = inQuiver ? itemManager.canonicalize(quiverId) : -1;
		for (Item item : inventory.getItems())
		{
			if (carried == -1 && item != null && item.getId() > 0 && kind(name(item.getId())) == otherKind)
			{
				carried = itemManager.canonicalize(item.getId());
				break;
			}
		}
		if (carried == -1)
		{
			// only one kind carried and none on: those are the bolts to put on
			return none ? new Reminder(ammoId, label(wornKind, ""), true, false) : null;
		}

		BoltCalc.Shot shot = new BoltCalc.Shot();
		shot.rangedLevel = client.getBoostedSkillLevel(Skill.RANGED);
		shot.playerHitpoints = client.getBoostedSkillLevel(Skill.HITPOINTS);
		shot.prayerAccuracy = 100 + prayer(Prayer.RIGOUR, 20, Prayer.DEADEYE, 18, Prayer.EAGLE_EYE, 15, Prayer.HAWK_EYE, 10,
			Prayer.SHARP_EYE, 5);
		shot.prayerStrength = 100 + prayer(Prayer.RIGOUR, 23, Prayer.DEADEYE, 18, Prayer.EAGLE_EYE, 15, Prayer.HAWK_EYE, 10,
			Prayer.SHARP_EYE, 5);
		String head = "";
		String neck = "";
		for (Item item : worn.getItems())
		{
			if (item == null || item.getId() <= 0)
			{
				continue;
			}
			var stats = itemManager.getItemStats(item.getId());
			if (stats != null && stats.getEquipment() != null)
			{
				shot.rangedAttack += stats.getEquipment().getArange();
				shot.rangedStrength += stats.getEquipment().getRstr();
				if (stats.getEquipment().getSlot() == EquipmentInventorySlot.HEAD.getSlotIdx())
				{
					head = name(item.getId());
				}
				else if (stats.getEquipment().getSlot() == EquipmentInventorySlot.AMULET.getSlotIdx())
				{
					neck = name(item.getId());
				}
			}
		}
		var weaponStats = itemManager.getItemStats(weapon.getId());
		shot.speed = weaponStats == null || weaponStats.getEquipment() == null || weaponStats.getEquipment().getAspeed() <= 0
			? 6 : weaponStats.getEquipment().getAspeed();
		shot.zaryte = weaponName.toLowerCase(Locale.ROOT).contains("zaryte crossbow");
		shot.kandarinDiary = client.getVarbitValue(VarbitID.KANDARIN_DIARY_HARD_COMPLETE) == 1;
		int[] bonus = headOrNeck(head, neck, monster.undead, Boolean.TRUE.equals(data.onTask(taskName(), monster)));
		shot.bonusNumerator = bonus[0];
		shot.bonusDenominator = bonus[1];

		BoltCalc.Target target = new BoltCalc.Target();
		target.defenceLevel = monster.defenceLevel;
		target.heavyDefence = monster.heavyDefence;
		target.flatArmour = monster.flatArmour;
		target.hitpoints = lastHealth;
		target.endlessHealth = npc.getId() == GEMSTONE_CRAB;

		if (fromQuiver)
		{
			// what sits in the ammunition slot is not shot; the quiver's bolts are
			shot.rangedStrength += strength(quiverId) - (slotId > 0 ? strength(slotId) : 0);
		}
		if (none)
		{
			// what sits in the ammunition slot (a blessing, arrows) is not shot: the first carried bolts stand in for it
			shot.rangedStrength += strength(ammoId) - (slotId > 0 ? strength(slotId) : 0);
		}
		double now = BoltCalc.perTick(wornKind, shot, target);
		// the same shot with the carried bolts instead: only the bolts' own strength changes
		shot.rangedStrength += strength(carried) - strength(ammoId);
		double other = BoltCalc.perTick(otherKind, shot, target);
		double gain = BoltCalc.gain(now, other);
		if (none)
		{
			// the better of the two carried kinds, and how much more it deals than the other
			return gain >= 0 ? new Reminder(carried, label(otherKind, gainText(gain)), true, false)
				: new Reminder(ammoId, label(wornKind, gainText(BoltCalc.gain(other, now))), true, false);
		}
		return new Reminder(carried, label(otherKind, gainText(gain)), gain >= MINIMUM_GAIN, inQuiver);
	}

	/** Whether the monster last attacked on {@code lastAttack} is still the one being fought on {@code tick}. */
	static boolean remembered(int tick, int lastAttack)
	{
		return lastAttack >= 0 && tick - lastAttack <= REMEMBER_TICKS;
	}

	/** The monster's health from the bar the game draws: a fraction of its hitpoints, never under 1. */
	static int healthNow(int hitpoints, int ratio, int scale)
	{
		return Math.max(1, (int) ((long) hitpoints * ratio / scale));
	}

	/**
	 * With a crossbow in hand and no bolts on, and no monster to compare against: the bolts in the inventory to
	 * put on. Ruby first (carried, they are the ones to start a fight with), then diamond, then any others.
	 */
	private Reminder boltsToPutOn()
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (worn == null || inventory == null)
		{
			return null;
		}
		Item weapon = worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		Item slot = worn.getItem(EquipmentInventorySlot.AMMO.getSlotIdx());
		if (weapon == null || weapon.getId() <= 0 || !name(weapon.getId()).toLowerCase(Locale.ROOT).contains("crossbow"))
		{
			return null;
		}
		int quiverId = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT) > 0
			? client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO) : -1;
		if ((slot != null && slot.getId() > 0 && isBolts(name(slot.getId()))) || (quiverId > 0 && isBolts(name(quiverId))))
		{
			return null; // bolts are on
		}
		String[] names = new String[inventory.getItems().length];
		for (int i = 0; i < names.length; i++)
		{
			Item item = inventory.getItems()[i];
			names[i] = item == null || item.getId() <= 0 ? null : name(item.getId());
		}
		int at = preferred(names);
		if (at < 0)
		{
			return null;
		}
		BoltCalc.Bolt kind = kind(names[at]);
		return new Reminder(itemManager.canonicalize(inventory.getItems()[at].getId()), kind == null ? "" : label(kind, ""), true, false);
	}

	/** The index of the bolts to put on among these item names: ruby (e), then diamond (e), then any other bolts; -1 for none. */
	static int preferred(String[] names)
	{
		int diamond = -1;
		int other = -1;
		for (int i = 0; i < names.length; i++)
		{
			BoltCalc.Bolt kind = kind(names[i]);
			if (kind == BoltCalc.Bolt.RUBY)
			{
				return i;
			}
			if (kind == BoltCalc.Bolt.DIAMOND && diamond < 0)
			{
				diamond = i;
			}
			else if (kind == null && other < 0 && isBolts(names[i]) && !names[i].contains("(unf)"))
			{
				other = i;
			}
		}
		return diamond >= 0 ? diamond : other;
	}

	/**
	 * Whether the crossbow shoots the bolts in Dizana's quiver: only when the quiver holds bolts and the
	 * ammunition slot does not (bolts in the slot are shot first).
	 */
	static boolean shotFromQuiver(String slotName, String quiverName)
	{
		return isBolts(quiverName) && !isBolts(slotName);
	}

	static boolean isBolts(String itemName)
	{
		return itemName != null && itemName.toLowerCase(Locale.ROOT).contains("bolts");
	}

	/** RUBY or DIAMOND for enchanted bolts of either tier, null for anything else. */
	static BoltCalc.Bolt kind(String itemName)
	{
		if (itemName == null || !itemName.endsWith("(e)"))
		{
			return null;
		}
		if (itemName.startsWith("Ruby bolts") || itemName.startsWith("Ruby dragon bolts"))
		{
			return BoltCalc.Bolt.RUBY;
		}
		if (itemName.startsWith("Diamond bolts") || itemName.startsWith("Diamond dragon bolts"))
		{
			return BoltCalc.Bolt.DIAMOND;
		}
		return null;
	}

	/**
	 * What the helmet or the amulet does to a ranged shot, as numerator and denominator: the Salve
	 * amulet (ei) or (i) against the undead; otherwise an imbued Slayer helmet on a task.
	 */
	static int[] headOrNeck(String head, String neck, boolean undead, boolean onTask)
	{
		String amulet = neck == null ? "" : neck.replace(" ", "");
		if (undead && amulet.startsWith("Salveamulet"))
		{
			if (amulet.endsWith("(ei)"))
			{
				return new int[]{6, 5};
			}
			if (amulet.endsWith("(i)"))
			{
				return new int[]{7, 6};
			}
		}
		String helmet = head == null ? "" : head.toLowerCase(Locale.ROOT);
		if (onTask && helmet.endsWith("(i)") && (helmet.contains("slayer helmet") || helmet.startsWith("black mask")))
		{
			return new int[]{23, 20};
		}
		return new int[]{1, 1};
	}

	/** The notice: the bolts' name and the difference, "Ruby +15%". */
	static String label(BoltCalc.Bolt kind, String gain)
	{
		return ((kind == BoltCalc.Bolt.RUBY ? "Ruby " : "Diamond ") + gain).trim();
	}

	/** The difference as the notice writes it, with its sign: "+0.4%", "+12%", "-5.2%". */
	static String gainText(double gain)
	{
		String sign = gain < 0 ? "-" : "+";
		double size = Math.abs(gain);
		return size < 9.95 ? String.format(Locale.ROOT, "%s%.1f%%", sign, size) : sign + Math.round(size) + "%";
	}

	private String name(int itemId)
	{
		return itemManager.getItemComposition(itemManager.canonicalize(itemId)).getName();
	}

	private int strength(int itemId)
	{
		var stats = itemManager.getItemStats(itemId);
		return stats == null || stats.getEquipment() == null ? 0 : stats.getEquipment().getRstr();
	}

	/** The first of these prayers that is on, as its percentage; 0 when none is. */
	private int prayer(Object... prayerThenPercent)
	{
		for (int i = 0; i < prayerThenPercent.length; i += 2)
		{
			if (client.isPrayerActive((Prayer) prayerThenPercent[i]))
			{
				return (Integer) prayerThenPercent[i + 1];
			}
		}
		return 0;
	}

	/** The name of the current Slayer task, from the game's own table; null when there is none or it cannot be read. */
	private String taskName()
	{
		if (client.getVarpValue(VarPlayerID.SLAYER_COUNT) <= 0)
		{
			return null;
		}
		int id = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		if (id == taskId)
		{
			return taskName;
		}
		String found = null;
		try
		{
			List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, id);
			if (rows != null && !rows.isEmpty())
			{
				Object[] field = client.getDBTableField(rows.get(0), DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0);
				found = field != null && field.length > 0 && field[0] != null ? field[0].toString() : null;
			}
		}
		catch (RuntimeException e)
		{
			log.debug("could not read the slayer task", e);
		}
		taskId = id;
		taskName = found;
		return found;
	}

	/** True while the player is fighting another player, now or within the last minute: then nothing is shown. */
	private boolean fightingPlayer(int tick)
	{
		Player me = client.getLocalPlayer();
		boolean now = me.getInteracting() instanceof Player;
		if (!now)
		{
			for (Player other : client.getTopLevelWorldView().players())
			{
				if (other != null && other != me && other.getInteracting() == me)
				{
					now = true;
					break;
				}
			}
		}
		if (now)
		{
			lastPlayerFight = tick;
		}
		return lastPlayerFight >= 0 && tick - lastPlayerFight <= PLAYER_FIGHT_TICKS;
	}

	/** The author's form for a bug or a suggestion, with the plugin's name filled in. The player writes and sends it in the browser. */
	static final String REPORT_FORM = "https://docs.google.com/forms/d/e/1FAIpQLSclvrdcVzomW3-4lj7-gnvbzmerY6qwHepApCuI7e4Q8raDyw/viewform?usp=pp_url&entry.2100394972=Bolt%20Swap%20Reminder";

	/** "Bug or suggestion" in the settings works as a button: each click opens the form, whichever way the box ends up. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("boltswapreminder".equals(event.getGroup()) && "reportBug".equals(event.getKey()) && event.getNewValue() != null)
		{
			LinkBrowser.browse(REPORT_FORM);
		}
	}
}
