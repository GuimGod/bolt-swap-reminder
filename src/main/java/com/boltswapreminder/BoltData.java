/*
 * Copyright (c) 2026, GuimGod
 * All rights reserved. Licensed under the BSD 2-Clause License (see LICENSE).
 */
package com.boltswapreminder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The bundled table of monsters: defence against crossbows, hitpoints, and what kind of monster it is. From the OSRS Wiki. */
final class BoltData
{
	static final class Monster
	{
		final String name;
		final int defenceLevel;
		final int heavyDefence;
		final int hitpoints;
		final int flatArmour;
		final boolean undead;
		/** The Slayer categories it belongs to, as {@link #taskKey} writes them. */
		final Set<String> slayer;

		Monster(String[] c)
		{
			name = c[1];
			defenceLevel = Integer.parseInt(c[2]);
			heavyDefence = Integer.parseInt(c[3]);
			hitpoints = Integer.parseInt(c[4]);
			flatArmour = c.length > 5 && !c[5].isEmpty() ? Integer.parseInt(c[5]) : 0;
			undead = c.length > 6 && c[6].contains("u");
			Set<String> categories = new HashSet<>();
			if (c.length > 7)
			{
				for (String category : c[7].split(";"))
				{
					String key = taskKey(category);
					if (!key.isEmpty())
					{
						categories.add(key);
					}
				}
			}
			slayer = Collections.unmodifiableSet(categories);
		}

		/** Whether the two rows give a crossbow the same fight: same defence, hitpoints, armour, undead and categories. */
		boolean sameFight(Monster o)
		{
			return defenceLevel == o.defenceLevel && heavyDefence == o.heavyDefence && hitpoints == o.hitpoints
				&& flatArmour == o.flatArmour && undead == o.undead && slayer.equals(o.slayer);
		}
	}

	/** Stands for a name whose rows differ (Vorkath in Dragon Slayer II and after it): such a name is never guessed. */
	private static final Monster DIFFERENT = new Monster(new String[]{"", "", "0", "0", "0"});

	private final Map<Integer, Monster> monsters = new HashMap<>();
	/** By the name in lower case: the one fight every row of that name gives, or {@link #DIFFERENT}. */
	private final Map<String, Monster> byName = new HashMap<>();
	private final Set<String> categories = new HashSet<>();

	BoltData() throws IOException
	{
		InputStream in = BoltData.class.getResourceAsStream("monsters.tsv");
		if (in == null)
		{
			throw new IOException("missing resource monsters.tsv");
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				if (!line.isEmpty() && !line.startsWith("#"))
				{
					Monster m = new Monster(line.split("\t", -1));
					monsters.put(Integer.parseInt(line.substring(0, line.indexOf('\t'))), m);
					categories.addAll(m.slayer);
					byName.merge(m.name.toLowerCase(Locale.ROOT), m, (a, b) -> a.sameFight(b) ? a : DIFFERENT);
				}
			}
		}
	}

	Monster monster(int npcId)
	{
		return monsters.get(npcId);
	}

	/**
	 * The monster by its NPC id; an id the table does not list (a form the OSRS Wiki has not listed yet) falls back on
	 * the name, but only when every row of that name gives the same fight. Null when neither finds it.
	 */
	Monster monster(int npcId, String name)
	{
		Monster m = monsters.get(npcId);
		if (m != null || name == null)
		{
			return m;
		}
		Monster named = byName.get(name.toLowerCase(Locale.ROOT));
		return named == DIFFERENT ? null : named;
	}

	/**
	 * Whether a monster is of the player's Slayer task: TRUE or FALSE when the task is a known
	 * category, null when it is not (then nothing is assumed).
	 */
	Boolean onTask(String taskName, Monster monster)
	{
		String key = taskKey(taskName);
		if (key.isEmpty() || key.equals("boss") || key.equals("bosse") || !categories.contains(key) || monster == null)
		{
			return null;
		}
		return monster.slayer.contains(key);
	}

	/** A Slayer task or category name as compared here: lower case, without the plural "s". */
	static String taskKey(String name)
	{
		if (name == null)
		{
			return "";
		}
		String key = name.trim().toLowerCase(Locale.ROOT);
		return key.endsWith("s") && !key.endsWith("ss") ? key.substring(0, key.length() - 1) : key;
	}
}
