# TournamentsLite

A lightweight, Folia-native tournament sign-up plugin built from scratch
for Minecraft 1.21.11 (Paper & Folia).

## What it does

- A GUI (`/tl`) showing your tournaments as items in configurable slots:
  weekly schedule, countdown to the next occurrence, a top-5 leaderboard
  you type in by hand, and whether you're currently signed up.
- Players click an item to sign up; click again to leave.
- Each tournament is its **own file** under `tournaments/` (`uhc.yml`,
  `cartpvp.yml`, `clanwars.yml` by default) - open one, type the week's
  winners into the `leaderboard:` section, save. The plugin re-reads it
  from disk automatically every ~30 seconds, no reload needed.
- Default schedule: UHC every Wednesday, CartPvP every Monday, ClanWars
  every Friday - purely for the GUI's countdown display, you still decide
  exactly when to start with `/tl tpsigned`.
- `/tl tpsigned <tournament>` teleports every currently-online signed-up
  player straight to you.
- `/tl tpsigned <tournament>` plays a short, **private** pre-teleport
  animation for each signed-up player (totem-style screen flash + a
  tournament-themed item + the totem pop sound), then teleports them to
  you 1-2 seconds later. Nobody else ever sees it.
- Admin override commands to force sign-ups, remove them, and ban players
  from tournaments entirely.
- Sign-ups persist across restarts (`data.yml`), autosaved every 5 minutes.

## Why it's Folia-safe

- No usage of the legacy `org.bukkit.scheduler.BukkitScheduler` /
  `Bukkit.getScheduler()` anywhere.
- The one repeating background job (re-reading tournament files from disk
  to pick up hand-edited leaderboard names) is real file I/O, so it runs
  on the `AsyncScheduler`, never a region thread - a slow disk can't cause
  a TPS hiccup.
- Autosaving sign-up data also runs on the `AsyncScheduler`.
- Sign-up data lives in `ConcurrentHashMap`s, since different players can
  sign up from different regions at the same time.
- GUI clicks only ever touch the event's own player, on the thread that
  already owns that player - no extra scheduling needed.
- `/tl tpsigned` uses `Entity#teleportAsync`, the Paper/Folia-safe async,
  cross-region teleport, instead of the old blocking `teleport()`.
- The pre-teleport animation is scheduled on each player's own
  `EntityScheduler` (`Player#getScheduler()`), not the global or a region
  scheduler - this ties the delayed "now teleport them" step to that
  specific player's lifecycle, so it safely no-ops (via the scheduler's
  "retired" callback) if they disconnect mid-animation instead of ever
  touching a stale/removed entity.
- `plugin.yml` declares `folia-supported: true`.

## How the pre-teleport animation stays private and shows a custom icon

`Entity#playEffect(EntityEffect.PROTECTED_FROM_DEATH)` sends a vanilla
"entity event" packet - the totem-pop screen flash. The Minecraft client
only renders that dramatic full-screen effect when the entity referenced
in the packet is the client's *own* player; that check is hardcoded into
the client, so there's nothing extra the plugin needs to do to keep the
big screen flash private - it simply never renders on anyone else's
screen. `PROTECTED_FROM_DEATH` is the current, non-deprecated replacement
for the old `TOTEM_RESURRECT` id as of Minecraft 1.21.2.

Since 1.21.2, totems were also generalized so *any* item can trigger that
flash via the `DEATH_PROTECTION` data component - that's what lets us
swap the icon shown in the flash itself. The plugin briefly places a
tagged copy of the tournament's `animation-item` (cobweb, sword, TNT,
whichever you've set) into the player's off-hand, triggers the flash so
the client shows *that* item instead of a plain totem, then restores
their real off-hand item a few ticks later - fast enough that nothing
looks different in their own UI. This off-hand swap/revert only ever
syncs to that player's own client, same as any of their own inventory
changes.

The sound and the lingering icon particle are added on top using
`Player#playSound(Player, ...)` and `Player#spawnParticle(...)` - both
Player-scoped overloads send the packet directly to that one player's
connection only, never broadcast to anyone nearby.

## Building it

You need JDK 21 and Maven with internet access (to pull `paper-api` from
`repo.papermc.io`, which isn't reachable from the sandbox this was built
in, so build it on your own machine or CI):

```bash
mvn clean package
```

Output jar: `target/TournamentsLite-1.0.0.jar`. Check `pom.xml`'s
`<paper.version>` property first and bump it if it doesn't match your
server's exact build.

## Editing a tournament

Open `plugins/TournamentsLite/tournaments/uhc.yml` (or cartpvp.yml /
clanwars.yml) directly:

```yaml
display-name: "UHC"
slot: 10
material: GOLDEN_APPLE
weekday: WEDNESDAY
leaderboard:
  1: "ItsLewizzz"
  2: "Steve"
  3: "Alex"
  4: ""
  5: ""
```

Save the file - within ~30 seconds (configurable via
`leaderboard-refresh-interval-seconds` in `config.yml`) the GUI shows the
new names. `weekday` is one of `MONDAY`...`SUNDAY`, or blank for no fixed
schedule.

You can also set winners without touching a file:

```
/tl setwinner <tournament> <rank 1-5> <name>
/tl clearwinners <tournament>
```

## Managing tournaments themselves

```
/tl create <id> <slot> <material> <weekday|none>
/tl remove <id>
/tl list
/tl reload            # config.yml + messages.yml + all tournament files
/tl tpsigned <id>      # teleport all signed-up players to you
```

## Managing sign-ups

```
/tl signup <player> <tournament>   # force sign someone up
/tl kick <player> <tournament>      # remove one sign-up
/tl ban <player>                    # ban from ALL tournaments + kicks existing sign-ups
/tl unban <player>
```

## Editing GUI text

Everything the player sees (lore layout, chat messages, the leaderboard
line format) is in `messages.yml`. Edit and `/tl reload`.

## Customizing the teleport animation

Each tournament file has an `animation-item` field controlling the icon
shown *inside the totem-pop flash itself* during the pre-teleport
animation (independent of the GUI's `material`) - `COBWEB` for UHC,
`NETHERITE_SWORD` for ClanWars, and `TNT` for CartPvP by default. Leave
it blank to just reuse the GUI icon.

`config.yml`'s `teleport-animation-delay-ticks` (default `30` = 1.5s)
controls how long the animation plays before the actual teleport fires.
