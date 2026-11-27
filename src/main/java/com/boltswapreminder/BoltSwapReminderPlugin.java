/*
 * Copyright (c) 2026, GuimGod
 * All rights reserved. Licensed under the BSD 2-Clause License (see LICENSE).
 */
package com.boltswapreminder;

import com.google.inject.Provides;
import java.io.IOException;
import java.util.ArrayList;
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
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * With a crossbow in hand, ruby or diamond bolts (e) in the ammunition slot (or in Dizana's quiver) and
 * the other kind in the inventory, works out which of the two deals more to the monster being fought at the health it has
 * now, and with the Defence it has left after the special attacks that lowered it (a Bandos godsword, a Dragon warhammer,
 * an Elder maul...: the player's own, and the party's through RuneLite's Special Attack Counter), and shows the other bolts
 * as soon as they are the better ones. It only draws: nothing is clicked, equipped or changed.
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
		/** True when those bolts are in the bolt pouch: the pouch is what the inventory outlines. */
		final boolean inPouch;

		Reminder(int itemId, String text, boolean better, boolean inQuiver)
		{
			this(itemId, text, better, inQuiver, false);
		}

		Reminder(int itemId, String text, boolean better, boolean inQuiver, boolean inPouch)
		{
			this.itemId = itemId;
			this.text = text;
			this.better = better;
			this.inQuiver = inQuiver;
			this.inPouch = inPouch;
		}

		/** The item the inventory outlines: the bolts, or the bolt pouch that holds them. */
		int markedItem()
		{
			return inPouch ? ItemID.XBOWS_BOLT_POUCH : itemId;
		}
	}

	/** The game's table of the bolt pouch's bolt types: each slot holds an index into it (client script 4047). */
	static final int BOLT_POUCH_TYPES = 336;
	private static final int[] POUCH_TYPE = {VarbitID.XBOWS_POUCH_SLOT1, VarbitID.XBOWS_POUCH_SLOT2,
		VarbitID.XBOWS_POUCH_SLOT3, VarbitID.XBOWS_POUCH_SLOT4};
	private static final int[] POUCH_AMOUNT = {VarbitID.XBOWS_POUCH_NUM1, VarbitID.XBOWS_POUCH_NUM2,
		VarbitID.XBOWS_POUCH_NUM3, VarbitID.XBOWS_POUCH_NUM4};

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

	@Inject
	private ClientThread clientThread;

	@Inject
	private PartyService party;

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
		clientThread.invoke(this::forgetSpecialAttacks);
		overlayManager.add(overlay);
		overlayManager.add(inventoryOverlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(inventoryOverlay);
		reminder = null;
		// what the client thread reads and writes is cleared on it
		clientThread.invoke(this::forgetSpecialAttacks);
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
			landSpecialAttack();
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
		if (!firesGemBolts(weaponName))
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
		// the other kind in the bolt pouch, which swaps it in from the Worn Equipment tab; with Dizana's quiver on the
		// player the pouch does not swap (OSRS Wiki, Bolt pouch), so then it does not count
		boolean inPouch = false;
		if (carried == -1 && !none && pouchSwaps(names(worn), names(inventory)))
		{
			carried = pouchBolts(otherKind);
			inPouch = carried != -1;
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
		target.defenceLevel = defenceNow(npc, monster);
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
		return new Reminder(carried, label(otherKind, gainText(gain)), gain >= MINIMUM_GAIN, inQuiver, inPouch);
	}

	/** The names of a container's items, null for an empty slot. */
	private String[] names(ItemContainer container)
	{
		Item[] items = container.getItems();
		String[] out = new String[items.length];
		for (int i = 0; i < items.length; i++)
		{
			out[i] = items[i] == null || items[i].getId() <= 0 ? null : name(items[i].getId());
		}
		return out;
	}

	/**
	 * Whether the bolt pouch can swap bolts in: it is in the inventory, and Dizana's quiver (any of its kinds) is neither
	 * worn nor carried, since with the quiver on the player the pouch cannot change the ammunition (OSRS Wiki, Bolt pouch).
	 */
	static boolean pouchSwaps(String[] worn, String[] inventory)
	{
		boolean pouch = false;
		for (String[] names : new String[][]{worn, inventory})
		{
			for (String n : names)
			{
				String lower = n == null ? "" : n.toLowerCase(Locale.ROOT);
				if (lower.contains("dizana's quiver"))
				{
					return false;
				}
				pouch |= names == inventory && lower.equals("bolt pouch");
			}
		}
		return pouch;
	}

	/** The bolts of this kind in the bolt pouch's four slots, as an item id; -1 for none. */
	private int pouchBolts(BoltCalc.Bolt kind)
	{
		var types = client.getEnum(BOLT_POUCH_TYPES);
		if (types == null)
		{
			return -1;
		}
		for (int i = 0; i < POUCH_TYPE.length; i++)
		{
			if (client.getVarbitValue(POUCH_AMOUNT[i]) <= 0)
			{
				continue;
			}
			int item = types.getIntValue(client.getVarbitValue(POUCH_TYPE[i]));
			if (item > 0 && kind(name(item)) == kind)
			{
				return itemManager.canonicalize(item);
			}
		}
		return -1;
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
		if (weapon == null || weapon.getId() <= 0 || !firesGemBolts(name(weapon.getId())))
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
	 * Whether the crossbow shoots the bolts in Dizana's quiver: only when the quiver holds bolts it fires and the
	 * ammunition slot does not (bolts in the slot are shot first). Ascension bolts in the slot hold nothing back: only
	 * the ascension crossbows fire them (OSRS Wiki), so a Zaryte crossbow with them on shoots the quiver's rubies.
	 */
	static boolean shotFromQuiver(String slotName, String quiverName)
	{
		return firedBolts(quiverName) && !firedBolts(slotName);
	}

	/** Bolts a crossbow other than the ascension crossbows fires: any bolts but ascension bolts. */
	static boolean firedBolts(String itemName)
	{
		return isBolts(itemName) && !itemName.toLowerCase(Locale.ROOT).contains("ascension bolts");
	}

	/**
	 * True for a crossbow that shoots ruby and diamond bolts (e). The ascension crossbows shoot only ascension bolts, whose
	 * gem effects trigger half as often (OSRS Wiki, Ascension crossbows): nothing to say for them.
	 */
	static boolean firesGemBolts(String weaponName)
	{
		String n = weaponName == null ? "" : weaponName.toLowerCase(Locale.ROOT);
		return n.contains("crossbow") && !n.contains("ascension");
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

	@Inject
	private okhttp3.OkHttpClient reportHttp;

	/** "Bug or suggestion" in the settings works as a button: each click opens the report window, whichever way the box ends up. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("boltswapreminder".equals(event.getGroup()) && "reportBug".equals(event.getKey()) && event.getNewValue() != null)
		{
			clientThread.invokeLater(() ->
			{
				final NPC monster = lastNpc;
				final String name = monster == null ? null : monster.getName();
				final String context = ReportWindow.context(client, monster);
				javax.swing.SwingUtilities.invokeLater(() -> ReportWindow.open(client.getCanvas(), name, context, reportHttp));
			});
		}
	}

	/** The Defence drains counted on each monster around ({@link DrainTracker}). */
	private final DrainTracker drains = new DrainTracker();
	/** The special attack energy last seen, -1 before the first. */
	private int specEnergy = -1;
	/** The Hitpoints experience last seen, and the client cycle it last grew on (an Elder maul hit deals damage). */
	private int hitpointsXp = -1;
	private int hitpointsXpCycle;
	/**
	 * The player's own special attack waiting for its hitsplat: the weapon, the monster, the tick it lands on and how many
	 * hitsplats it deals.
	 */
	private SpecialWeapon specWeapon;
	private NPC specTarget;
	private int specTick;
	private int specSplats;
	private boolean specDealtDamage;
	private final List<Integer> specHits = new ArrayList<>();

	/** Ticks from the Special Attack Counter sending a party message to it coming here (measured: 116 of 118 Ralos). */
	static final int PARTY_MESSAGE_TICKS = 1;

	/**
	 * The ornamented and other dresses of the weapons whose special attack lowers Defence, each with the item it is (the
	 * Combat Check plugin's variant table): read as that weapon.
	 */
	private static final int[][] DRESSES = {
		{ItemID.BGSG, ItemID.BGS},
		{ItemID.DRAGON_WARHAMMER_ORNAMENT, ItemID.DRAGON_WARHAMMER},
		{ItemID.BH_DRAGON_WARHAMMER_CORRUPTED, ItemID.DRAGON_WARHAMMER},
		{ItemID.ELDER_MAUL_ORNAMENT, ItemID.ELDER_MAUL},
		{ItemID.BRAIN_BROKEN_ANCHOR, ItemID.BRAIN_ANCHOR},
	};

	/**
	 * Vorkath going back to sleep after a kill (OSRS Wiki infobox, Vorkath: 8058 and 8059 asleep, 8060 and 8061 awake): the
	 * sleeping form of the repeatable fight has the same Defence as the awake one, so the change alone would keep the
	 * drains of the kill before for the next.
	 */
	private static final int[] VORKATH_ASLEEP = {8058, 8059};

	private void forgetSpecialAttacks()
	{
		drains.clear();
		specWeapon = null;
		specTarget = null;
		specEnergy = -1;
		hitpointsXp = -1;
		specHits.clear();
	}

	/**
	 * The Defence the monster fought has now: the table's, lowered by the special attacks counted on it, in the order they
	 * landed (none for a fight immune to drains, never below its floor, with the levels it wins back with time).
	 */
	private int defenceNow(NPC npc, BoltData.Monster monster)
	{
		int displayed = client.getVarpValue(VarPlayerID.HPBAR_HUD_NPC) == npc.getId()
			? client.getVarbitValue(VarbitID.HPBAR_HUD_BASEHP) : 0;
		boolean awakened = DefenceDrains.awakened(npc.getId(), monster.hitpoints, displayed);
		return DefenceDrains.left(npc.getId(), monster.demon, awakened, DefenceDrains.full(monster.defenceLevel),
			drains.of(npc.getIndex(), npc), drains.ticksSince(npc.getIndex(), npc, client.getTickCount())).defence;
	}

	/**
	 * The player's own special attacks are found the way RuneLite's Special Attack Counter finds them: the energy drops
	 * while one of its weapons is held, and the player's hitsplat lands on the monster attacked on the tick the attack
	 * hits. They are counted here, in a party too: the party's message about them is not counted again.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarpId() != VarPlayerID.SA_ENERGY)
		{
			return;
		}
		int energy = event.getValue();
		boolean used = specEnergy != -1 && energy < specEnergy;
		specEnergy = energy;
		if (!used)
		{
			return;
		}
		// the player and the monsters are not updated yet: what is attacked is read once they are
		final int serverTick = client.getTickCount();
		clientThread.invokeLater(() ->
		{
			int item = weaponInHand();
			specWeapon = specialWeapon(item);
			specSplats = item == ItemID.TONALZTICS_OF_RALOS_CHARGED ? 2 : 1;
			specHits.clear();
			Player me = client.getLocalPlayer();
			Actor target = me == null ? null : me.getInteracting();
			specTarget = specWeapon != null && target instanceof NPC ? (NPC) target : null;
			if (specTarget == null)
			{
				specWeapon = null;
				return;
			}
			specDealtDamage = hitpointsXpCycle == client.getGameCycle();
			int distance = 1;
			net.runelite.api.coords.WorldPoint at = me.getWorldLocation();
			net.runelite.api.coords.WorldArea area = specTarget.getWorldArea();
			if (at != null && area != null)
			{
				distance = area.distanceTo(at);
			}
			specTick = serverTick + specWeapon.getHitDelay(distance);
		});
	}

	/** The item id of the weapon in the player's hand, -1 for none. */
	private int weaponInHand()
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		Item weapon = worn == null ? null : worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		return weapon == null ? -1 : weapon.getId();
	}

	/**
	 * The Special Attack Counter's weapon of an item, or null; an ornamented one as its weapon ({@link #DRESSES}). Two that
	 * it does not list are read as the weapon whose special attack they have (OSRS Wiki): the Arclight without charges,
	 * which "functions identically to Darklight", and the Tonalztics of Ralos without charges, whose special attack hits
	 * once.
	 */
	static SpecialWeapon specialWeapon(int itemId)
	{
		int base = itemId;
		for (int[] dress : DRESSES)
		{
			if (dress[0] == itemId)
			{
				base = dress[1];
			}
		}
		for (int item : new int[]{itemId, base})
		{
			for (SpecialWeapon w : SpecialWeapon.values())
			{
				for (int id : w.getItemID())
				{
					if (id == item)
					{
						return w;
					}
				}
			}
		}
		if (itemId == ItemID.ARCLIGHT_INACTIVE)
		{
			return SpecialWeapon.DARKLIGHT;
		}
		return itemId == ItemID.TONALZTICS_OF_RALOS_UNCHARGED ? SpecialWeapon.TONALZTICS_OF_RALOS : null;
	}

	/**
	 * The hits of a Tonalztics of Ralos' special attack that landed, of the first {@code splats} hitsplats on the tick it
	 * hits; null while they have not all come.
	 */
	static Integer landedHits(List<Integer> hitsplats, int splats)
	{
		if (hitsplats.size() < splats)
		{
			return null;
		}
		int landed = 0;
		for (int i = 0; i < splats; i++)
		{
			landed += Math.min(hitsplats.get(i), 1);
		}
		return landed;
	}

	/**
	 * The weapon's hitsplat among the player's own on the tick its special attack hits, as the Special Attack Counter picks
	 * it: the first for a delayed hit (every ranged and magic special attack), the last for an instant one (the melee ones
	 * but the Elder maul), since a thrall's and vengeance's come between the two kinds; null for none yet.
	 */
	static Integer weaponHitsplat(SpecialWeapon weapon, List<Integer> hitsplats)
	{
		if (hitsplats.isEmpty())
		{
			return null;
		}
		return hitsplats.get(weapon.getHitDelay(1) > 1 ? 0 : hitsplats.size() - 1);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() == Skill.HITPOINTS)
		{
			if (event.getXp() > hitpointsXp)
			{
				hitpointsXpCycle = client.getGameCycle();
			}
			hitpointsXp = event.getXp();
		}
	}

	@Subscribe
	public void onFakeXpDrop(FakeXpDrop event)
	{
		if (event.getSkill() == Skill.HITPOINTS)
		{
			hitpointsXpCycle = client.getGameCycle();
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (specTarget != null && event.getActor() == specTarget && event.getHitsplat().isMine()
			&& client.getTickCount() == specTick)
		{
			specHits.add(event.getHitsplat().getAmount());
		}
	}

	/** Counts the player's own special attack once it has landed, as the Special Attack Counter reads its hit. */
	private void landSpecialAttack()
	{
		if (specWeapon == null || specTarget == null)
		{
			return;
		}
		int tick = client.getTickCount();
		Integer hit = null;
		if (specWeapon == SpecialWeapon.ELDER_MAUL)
		{
			// read from the Hitpoints experience on the tick of the attack, without waiting for the hitsplat
			hit = specDealtDamage ? 1 : 0;
		}
		else if (tick == specTick)
		{
			hit = specWeapon == SpecialWeapon.TONALZTICS_OF_RALOS ? landedHits(specHits, specSplats)
				: weaponHitsplat(specWeapon, specHits);
			if (hit == null)
			{
				return; // the hitsplat may still come this tick
			}
		}
		else if (tick < specTick)
		{
			return;
		}
		if (hit != null)
		{
			drains.add(specTarget.getIndex(), specTarget, specWeapon, hit, DefenceDrains.tekton(specTarget.getId()), specTick);
		}
		specWeapon = null;
		specTarget = null;
		specHits.clear();
	}

	/**
	 * A party member's special attack, sent by their Special Attack Counter (it is read only while this player has that
	 * plugin on). The player's own comes back the same way and is skipped: it was counted when it landed.
	 */
	@Subscribe
	public void onSpecialCounterUpdate(SpecialCounterUpdate event)
	{
		if (party.getLocalMember() == null || party.getLocalMember().getMemberId() == event.getMemberId())
		{
			return;
		}
		clientThread.invoke(() ->
		{
			if (event.getWorld() != client.getWorld())
			{
				return;
			}
			NPC npc = client.getTopLevelWorldView().npcs().byIndex(event.getNpcIndex());
			if (npc != null)
			{
				drains.add(npc.getIndex(), npc, event.getWeapon(), event.getHit(), DefenceDrains.tekton(npc.getId()),
					partyHitTick(event.getWeapon(), client.getTickCount()));
			}
		});
	}

	/**
	 * The tick a party member's special attack is counted on, from the tick its message came: the Special Attack Counter
	 * sends an Elder maul's the tick after the attack, on the tick its hit lands once the message arrives, and any other
	 * weapon's on the tick its hit lands, which arrives a tick later ({@link #PARTY_MESSAGE_TICKS}).
	 */
	static int partyHitTick(SpecialWeapon weapon, int received)
	{
		return weapon == SpecialWeapon.ELDER_MAUL ? received : received - PARTY_MESSAGE_TICKS;
	}

	/**
	 * A monster that changes into one with another Defence (a new phase) keeps nothing of the drains, nor one whose Defence
	 * the game gives back on the change ({@link DefenceDrains#resets}), nor one that dies or goes back to sleep: the next
	 * fight with it starts from its full Defence.
	 */
	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		NPC npc = event.getNpc();
		BoltData d = data;
		if (d == null || event.getOld() == null)
		{
			return;
		}
		if (npc.isDead()
			|| forgetsOnChange(d.monster(event.getOld().getId()), d.monster(npc.getId()), event.getOld().getId(), npc.getId()))
		{
			drains.forget(npc.getIndex(), npc);
		}
	}

	/** True when the change of a monster from {@code oldId} (row {@code before}) into {@code newId} (row {@code now}) drops its drains. */
	static boolean forgetsOnChange(BoltData.Monster before, BoltData.Monster now, int oldId, int newId)
	{
		return before == null || now == null || before.defenceLevel != now.defenceLevel
			|| DefenceDrains.resets(oldId, newId) || java.util.Arrays.stream(VORKATH_ASLEEP).anyMatch(id -> id == newId);
	}

	/** Whatever lowered a monster's Defence is gone with it: a monster seen again is a new one to the game's index. */
	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		drains.forget(event.getNpc().getIndex(), event.getNpc());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			forgetSpecialAttacks();
		}
	}
}
