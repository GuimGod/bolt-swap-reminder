# Bolt Swap Reminder

Ruby bolts (e) deal a share of the monster's **current** health when their effect goes off, so they are worth less and less as the fight goes on. At some point diamond bolts (e) deal more. Where that point is depends on your own gear, on the crossbow and on the monster.

With a crossbow in hand, one of the two kinds in the ammunition slot (or in Dizana's quiver, when the slot holds no bolts) and the other in your inventory, this plugin works out which of the two deals more to the monster you are fighting **at the health it has now**, and shows you the other bolts as soon as they are the better ones: their icon, their name and how much more they would deal ("Diamond +12%"), and an outline in the inventory.

With a crossbow in hand and no bolts on at all, the bolts to put on are shown the same way: the better of the ruby and diamond bolts in your inventory against the monster you are fighting, or, with no monster yet, the ruby ones first, then the diamond ones, then any others.

## What goes into the figure

- Your boosted Ranged level, your prayers, the ranged attack and strength of everything you wear, and the strength of each of the two bolts (so ruby and diamond, plain or dragon, in any mix).
- The crossbow: a Zaryte crossbow makes both effects stronger.
- The Kandarin hard diary, which makes either effect a tenth more likely.
- The monster's defence against crossbows and its hitpoints, from a bundled table, and its health from the bar the game draws.
- An imbued Slayer helmet on a monster of your task, and a Salve amulet (i) or (ei) against the undead.

The two effects, as the OSRS Wiki gives them:

| Bolts | Chance | Effect |
|---|---|---|
| Ruby (e) | 6% (6.6% with the diary) | 20% of the monster's current health, at most 100. With a Zaryte crossbow: 22%, at most 110. On the Gemstone Crab: at most 60 (66). Nothing under 10 hitpoints of your own |
| Diamond (e) | 10% (11% with the diary) | The shot cannot miss and rolls up to 115% of your max hit. With a Zaryte crossbow: 126% |

The tests compare the figure with the OSRS Wiki calculator over 1,344 cases (three crossbows, both tiers of bolt, eight monsters at seven healths, with and without the diary) and check that the better bolt is the same one.

## What it never does

- It does not click, equip or change anything. It only draws.
- It says nothing about what a monster is going to do. It reads the health bar the game already shows.
- It says nothing in a fight with another player, and nothing while the monster's health bar is not showing.

## Settings

The other bolts are pointed out as soon as they deal more, by any margin and with no wait. The monster stays the one being fought for 15 seconds after your last attack on it, so the reminder does not go away when you step aside or wait out a phase. When the other bolts are in Dizana's quiver, under the ones in the ammunition slot, the reminder says "(quiver)": taking the slot's bolts off switches to them.

| Setting | What it is | Default |
|---|---|---|
| Show the difference all the time | Show the other bolts and the difference even while yours are still better ("Diamond -5%", in red), so you can see the switch coming | off |
| Mark them in the inventory | Outline the better bolts in your inventory | on |
| Colour | Colour of the outline | cyan |

## Limits

- Ruby and diamond bolts (e) only. The other enchanted bolts are not compared.
- The monster must be in the bundled table, and you must be attacking it.
- The health comes from the health bar, which the game gives in steps: on a monster with a lot of hitpoints it is off by a few. Right at the point where the two bolts tie the reminder may come and go for a moment.
- Not counted: the health the ruby effect costs you, special attacks, and a monster that changes its defence during the fight.

## Data

Monster defence and hitpoints come from the [OSRS Wiki](https://oldschool.runescape.wiki) through its DPS calculator project; each monster's Slayer category comes from the wiki's monster infoboxes.
