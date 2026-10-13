# Group Ironmen Tracker In-Game Companion

Brings the [groupiron.men](https://groupiron.men) group ironman dashboard into the client, so
you can see what your team has and where they are without alt-tabbing to a website.

It reads the data your group already publishes, so it works as a companion to
[group-ironmen-tracker](https://github.com/christoabrown/group-ironmen-tracker), and falls
back to collecting that data itself when that plugin is not running.

> **Unofficial.** This is an independent plugin. It is not affiliated with, endorsed by, or
> maintained by the authors of the Group Ironmen Tracker plugin or the groupiron.men website.
> It talks to their API the same way their own website does, using credentials you supply.

## Features

Every feature below can be switched off on its own in the plugin settings.

**Group bank** - a read-only, searchable window showing everything the group owns.
`Ctrl+G` by default, or click the small **Group** tab that appears beside your bank.

The first time you open it, it places itself to the left of the bank, or against the left
edge of the screen when the bank is too wide to sit beside (which it always is in fixed
mode). After that, alt-drag it wherever you like: RuneLite remembers the position and the
plugin never moves it again.

It only swallows clicks inside its own frame, so your own bank stays fully usable beside it.

- An **All** tab totalling every item across the group, sorted by value, with a hover
  breakdown of who is holding it.
- A tab per member, split into Bank / Inventory / Equipment / Rune pouch / Seed vault /
  Quiver, plus a **Shared** tab for the group storage.
- Click the search box and type to filter. `Esc` clears, then closes.
- Alt-drag to move it, like any RuneLite overlay.

**Group levels on the Skills tab** - hover any skill and a panel lists every member's level,
experience and experience to the next level, each in that member's colour. The panel is
anchored beside the Skills tab rather than following the mouse, so it never covers the game's
own experience tooltip and never runs off the edge of the screen. Its side is configurable.

**World map markers** - a coloured pin per member on the world map. When a member is off the
overworld, a second hollow marker with a downward chevron is drawn at the surface entrance
that leads to them, so you can find them without knowing where they went. Their real marker
still appears when you pan the map to that dungeon's own view, because RuneLite only draws a
world map point on the map that actually covers it.

While a member is somewhere the map cannot draw, their surface marker stays parked on one
entrance rather than sliding around as they walk about underground.

Opening the world map also shows a small list of online members: click one to jump the map to
them, or right-click to route to them with the Shortest Path plugin. The route follows them as
they move and clears once you reach them. Shortest Path is optional; without it, right-click
simply does nothing.

Entrances cover every area the game defines a map for - around 40, including the ones that
are not simply "the surface plus 6400", such as Zanaris, God Wars Dungeon, Mor Ul Rek and the
Stronghold of Security. The data is generated from the game cache rather than measured by
hand; see [tools/mapdump](tools/mapdump/README.md).

**Side panel** - five tabs:

- **Group** - each member's hitpoints, prayer, run energy, world, last-seen time and what
  they seem to be doing (see below).
- **Items** - search everything the group owns and see who is holding it.
- **Skills** - total level and experience, or one skill compared across the group.
- **Quests** - completion per member, plus a search showing where everyone stands on a quest.
- **Diaries** - achievement diary completion per member, overall or broken down by region
  and tier.

**Status overlay** (off by default) - the group at a glance, drawn in the game's own fonts
and colours. Each member is their name in their colour with their world beside it, thin
hitpoints, prayer and run energy bars, and one line saying what they seem to be doing.
Offline members are a single grey line with how long ago they were seen. It draws straight
over the game, or on a translucent backdrop if you prefer; alt-drag it to move it, and alt-drag
its edge to change its width. Numbers can be written on the bars or beside them.

**What members are doing** - a best guess, shown in the status overlay and the side panel:
"Fishing at Catherby", "Fighting Goblin (43%)", "Woodcutting at Draynor Village",
"Banking at Seers' Village", "Teleported to Varrock", "Doing Gertrude's Cat",
"Idle at Lumbridge (3m)".

It is inferred, and can be wrong. The tracker shares no activity of its own, and the
groupiron.men server accepts no new fields, so it is worked out from what changes between
updates: experience gained in the last minute or so, position and the Sailing boat flag,
quest states, bank and inventory changes, and the NPC a member is interacting with. Things
it cannot see:

- Objects never count as an interaction, so woodcutting, mining or cooking is only
  recognised once the experience arrives.
- Quests only report not started, started or finished. A quest shows as being done only
  if it was started while you were watching, and a quest finished by a tracker user can
  take up to a minute to show.
- Standing still and being logged out look the same until the offline threshold passes.
- Instances report the template they were copied from; raids, bosses and the player-owned
  house are named, but an odd instance may show a nearby place instead.

Places come from region names and the world map's own labels (see Credits), so new areas are
named without a plugin update.

## Setup

1. One member creates a group at [groupiron.men](https://groupiron.men) and shares the group
   name and token.
2. Put them in this plugin's **Connection** settings.

If you already run the official Group Ironmen Tracker plugin, you can leave all three fields
blank - this plugin reads the group name, token and self-hosted server URL from that plugin's
configuration.

This works whether your group uses the public groupiron.men site or self-hosts: the plugin
reads the same API the website reads, with the same credentials. A group where everyone runs
only the official tracker plugin needs no extra setup at all - install this alongside it and
the in-game views populate from what the tracker is already uploading.

### Sharing your own data

Everyone in the group needs *something* uploading their character data for any of this to
have anything to show.

- If the official **Group Ironmen Tracker** plugin is installed and enabled, it does the
  uploading and this plugin only reads. The two never both upload.
- If it is not, this plugin uploads the same fields in the same format, so the website keeps
  working either way. Collection log progress is the one thing it does not collect; install
  the tracker plugin if your group wants that.
- Set **Data sharing → Share my data** to `OFF` to never upload anything.

## Settings

| Section | What it controls |
| --- | --- |
| Connection | Group name, token, self-hosted server URL, refresh interval, offline threshold |
| Side panel | Enable, show what members are doing, show offline members, include yourself |
| Group bank | Enable, hotkey, bank tab, open/close with the bank, item values |
| Skills tab | Enable hover, panel position, include yourself, include offline members |
| World map | Enable markers, dungeon entrance markers, include offline members, member list, routing |
| Status overlay | Enable, vitals bars, what members are doing, offline members, include yourself, numbers on or beside the bars, show where members are, show world, idle threshold, backdrop |
| Member colours | One colour per member, assigned alphabetically |
| Data sharing | Whether to upload your own data when the tracker plugin is absent |

## Rules compliance

Group ironman data is shared between accounts that have all opted in, which is exactly what
the official tracker plugin and website already do. Bringing that display in-game raises the
bar, so this plugin is built to stay clearly inside
[Jagex's Third-Party Client Guidelines](https://oldschool.runescape.wiki/w/Update:Third_Party_Client_Guidelines):

- **No menu entries.** The plugin never calls `createMenuEntry` or `Overlay.addMenuEntry`.
  Nothing it draws can cause an action to be sent to the server, which is the guidelines'
  actual prohibition on menus. Everything clickable belongs to the plugin's own overlays.
- **No interface modification.** No game widget is created, hidden, unhidden, reparented,
  moved or resized, and no click zone is changed. Game widgets are read for their on-screen
  bounds only - to know which skill the mouse is over, and where the bank window sits so the
  Group tab can be drawn *outside* it. Every view is the plugin's own drawing.
- **Read-only by construction.** The plugin only ever holds item ids and quantities read back
  from your group's server. There is no code path that could withdraw, deposit or use
  anything.
- **Nothing in the game scene or on the minimap.** Member positions are drawn on the world
  map only.
- **No PvP or combat assistance.** The only combat-adjacent data shown is what a group member
  is interacting with and a guess at what they are doing, in the side panel and the optional
  status overlay, never as a scene overlay and never for players outside the group. It can be switched off. This is your own five-person group,
  who each opted in by running a tracker plugin; it is not scouting information about anyone
  else.
- **No reflection**, no JNI, no external processes, no filesystem access, no runtime code
  loading, and no dependencies beyond what runelite-client already provides.

Each of those is enforced by `ComplianceTest`, which greps the source tree on every build, so
none of them can regress quietly.

## Security

- The only host the plugin contacts is the base URL you configured - `groupiron.men` by
  default, or your own server.
- Requests carry your group token, so redirects are disabled: a redirect could otherwise hand
  that token to a host you never configured.
- All requests have a hard call timeout and run on the plugin's own thread, so a slow or
  hung server cannot stall the client or another plugin's scheduled work.
- Responses are parsed into plain data classes and are size-capped. Response bodies are never
  logged, and neither is your token.
- No files are read or written. The bundled resources (map areas and place names) are read
  from the jar.

## Building

```bash
./gradlew build
```

`./gradlew deploy` copies the jar into `~/.runelite/sideloaded-plugins`, clearing out older
builds of this plugin first.

## Map area data

`map-areas.json` is generated from the game cache by [tools/mapdump](tools/mapdump/README.md)
and covers every world map the game defines. Regenerate it after a game update; `MapAreasTest`
asserts that a spread of known dungeons still resolve to the right entrances.

Where the cache defines no route to the surface - the Abyss, Tutorial Island, a couple of
Prifddinas interiors - a member still gets their true-position marker and the area's real
name in the tooltip, just no entrance marker.

## Credits

Place names combine the region list from
[Location Display](https://github.com/trinhc2/Location-Display) by trinhc2 (BSD 2-Clause)
with the world map's own labels from the game cache. The lookup and the bundled
`places.gz` are ported from [Golems Don't Die](https://github.com/Varzeki/golems-dont-die)
by Varzeki (BSD 2-Clause), which also has the generator. Region ids for the instances the map
labels name badly come from RuneLite's Discord plugin.

## Not implemented

Collection log is neither collected nor shown; the official tracker plugin remains the way to
populate that.
