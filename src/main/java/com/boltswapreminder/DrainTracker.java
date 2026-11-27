/*
 * Copyright (c) 2026, GuimGod
 * All rights reserved. Licensed under the BSD 2-Clause License (see LICENSE).
 */
package com.boltswapreminder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/**
 * The Defence drains of every monster around, by its NPC index. The index is reused by the game for another
 * monster once one goes away, so each entry also keeps the monster itself: drains are only ever read back
 * for that same monster.
 *
 * <p>Each special attack is kept with the tick its hit landed on, in that order (one that comes later for an earlier
 * tick, a party member's, goes in its place); the drains are worked out from that list, one after the other
 * ({@link DefenceDrains}).
 */
final class DrainTracker
{
	private static final class Record
	{
		final SpecialWeapon weapon;
		final int hit;
		final boolean tekton;
		final int tick;

		Record(SpecialWeapon weapon, int hit, boolean tekton, int tick)
		{
			this.weapon = weapon;
			this.hit = hit;
			this.tekton = tekton;
			this.tick = tick;
		}
	}

	private static final class Entry
	{
		final Object npc;
		final List<Record> records = new ArrayList<>();
		DefenceDrains drains = new DefenceDrains();
		/** The game tick of the last special attack counted. */
		int lastTick;

		Entry(Object npc)
		{
			this.npc = npc;
		}

		void rebuild()
		{
			drains = new DefenceDrains();
			for (Record r : records)
			{
				drains.add(r.weapon, r.hit, r.tekton, r.tick);
			}
		}
	}

	private final Map<Integer, Entry> byIndex = new HashMap<>();

	private Entry entry(int index, Object npc)
	{
		Entry e = byIndex.get(index);
		if (e == null || e.npc != npc)
		{
			e = new Entry(npc);
			byIndex.put(index, e);
		}
		return e;
	}

	/** Counts a special attack that landed on the monster at {@code index} on game tick {@code tick}. */
	void add(int index, Object npc, SpecialWeapon weapon, int hit, boolean tekton, int tick)
	{
		Entry e = entry(index, npc);
		Record r = new Record(weapon, hit, tekton, tick);
		int at = e.records.size();
		while (at > 0 && e.records.get(at - 1).tick > r.tick)
		{
			at--;
		}
		e.records.add(at, r);
		if (at == e.records.size() - 1)
		{
			e.drains.add(r.weapon, r.hit, r.tekton, r.tick);
		}
		else
		{
			e.rebuild();
		}
		e.lastTick = Math.max(e.lastTick, tick);
	}

	/** How many ticks ago, at tick {@code now}, the last special attack was counted on this monster; 0 for none. */
	int ticksSince(int index, Object npc, int now)
	{
		Entry e = byIndex.get(index);
		return e == null || e.npc != npc ? 0 : Math.max(0, now - e.lastTick);
	}

	/** The drains on this monster, or null when none was counted. */
	DefenceDrains of(int index, Object npc)
	{
		Entry e = byIndex.get(index);
		return e == null || e.npc != npc || e.drains.none() ? null : e.drains;
	}

	/** Drops what was counted on this monster: it died, went away or changed into another. */
	void forget(int index, Object npc)
	{
		Entry e = byIndex.get(index);
		if (e != null && e.npc == npc)
		{
			byIndex.remove(index);
		}
	}

	void clear()
	{
		byIndex.clear();
	}
}
