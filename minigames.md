# McParty — Minigame Design & Implementation Plan

**Version:** 1.0  
**Updated:** 2026-08-29
**Scope:** Launch set of **12 free-for-all** minigames between board dice rounds
**Parent docs:** `mcparty.md` (product vision), `AGENTS.md` (current plugin architecture)

---

## 1. Goals

- Replace the dummy timer with real short games that always return **placement 1…n** and **coins**.
- Keep rounds **45–90 seconds** so the board loop stays snappy.
- Prefer **shared engines** over one-off code per game.
- Stay correct under `cancel()` (party end, disconnect, plugin disable).
- Optimize for **4–8 players** on an ASP board/arena (not Hypixel-scale packet worlds).

### Design principles

1. **Match-scoped everything** — listeners and tasks only apply to players in the active match.
2. **Main-thread results** — `MinigameResult` applied on the server thread.
3. **Real physics where fall/stand matters** — Spleef and floating-island courses.
4. **Packets only for visuals / UI** — walls, optional Color Chaos paint, displays; not fake collision for spleef.
5. **Restore or discard** — either journal changed blocks + batch restore, or unload a disposable minigame pad.

---

## 2. Integration with the party loop

```text
Board round complete (all players acted)
        │
        ▼
MinigameManager.pick()  →  cancelActive() if any
        │
        ▼
Minigame.start(context, done)
        │
        ├─ snapshot player state (inv, gamemode, effects…)
        ├─ teleport to arena / freeze on pad
        ├─ run game logic
        │
        ▼
build MinigameResult (placement + coins)
        │
        ▼
cleanup (restore blocks/inv, clear listeners)
        │
        ▼
done.accept(result)  →  PartyInstance applies coins → next board round
```

| Contract | Detail |
|----------|--------|
| SPI | `Minigame`: `id()`, `start(MinigameContext, Consumer<MinigameResult>)`, `cancel()` |
| Context | plugin, `PartyInstance`, online `Player` list |
| Result | placement map + coin map per UUID |
| Cancel | Safe if called mid-game; no double `done`; restore world/player state |

---

## 3. Shared building blocks

Implement once under `minigame/` (names indicative):

| Component | Responsibility | Used by |
|-----------|----------------|---------|
| `MinigameRegistry` | id → factory; random pick; Dummy fallback | Manager |
| `MatchScope` | UUID set, world, cancelled flag, task list | All |
| `PlayerStateSnapshot` | inv, armor, XP, gamemode, effects, flight | All |
| `BlockChangeJournal` | pos → old `BlockData`; batch restore N blocks/tick | Color Chaos (real mode) |
| `EliminationTracker` | elimination order → placements | Hot Potato, Spleef, Mini Skywars, Color Chaos, Warden Escape, Antwar |
| `Region` / AABB | integer bounds; contains / finish line | Elytra Race, King of the Hill, Warden Escape, Block Jump, Laser Tag, Speed Race, arenas |
| `ScoreTracker` | UUID → score; rank by score | King of the Hill, Antwar, Laser Tag, Shooting Range |
| `CheckpointTracker` | sequential progress through a course | Elytra Race, King of the Hill, Warden Escape, Block Jump, Speed Race |
| `ArenaSpawns` | list of spawn locations per minigame pad | All |
| `SpectatorUtil` | eliminated → spectator (or freeze) until end | Elimination games |

### Arena strategy (v1)

**Shared minigame pad** on the party board world (or one dedicated region per slot):

- Admin sets pad bounds + spawn points (later: slot config / WE selection).
- Until pads exist: fall back to board spawn area with reduced bounds (dev only).

**v2 (optional):** separate slime templates per minigame id → load clone → play → unload (no block restore).

### Packet policy

| Use packets / displays | Use real world |
|------------------------|----------------|
| Fake walls, UI titles, boss bars | Spleef floors / Shooting Range targets |
| Optional Color Chaos client paint | Floating-island / Warden course terrain |
| Elytra rings, hill capture FX, Block Jump platform FX, laser hit FX | Player damage, item pass, block mining |
| Score holograms (optional) | Finish regions, collision |

Collision is always server-authoritative. Do not build Spleef on fake-only floors.

### Optimization defaults (from maintained plugins)

- Block writes: `setBlockData` / `setType(..., false)` — **no physics**.
- Only write if material actually changes.
- Journal **changed** blocks only; restore in batches (e.g. 200–500/tick).
- `PlayerMoveEvent`: ignore same-block moves; prefer block-coord logic.
- Clear projectiles/items in pad on end.
- Skip world save pressure on temp arenas when using disposable clones later.

---

## 4. Launch minigames overview

| # | Id | Display name | Length | Reusable foundation | Difficulty to build |
|---|-----|--------------|--------|---------------------|---------------------|
| 1 | `hot_potato` | Hot Potato | 45–60s | MatchScope + elimination | Low |
| 2 | `speed_race` | Speed Race | 45–75s | Region + checkpoints | Low–med |
| 3 | `spleef` | Spleef (Classic Snow / TNT) | 60–90s | Disposable arena + elimination | Low–med |
| 4 | `elytra_race` | Elytra Race | 45–75s | Checkpoints + player flight state | Medium |
| 5 | `shooting_range` | Shooting Range | 60–90s | ScoreTracker + routed projectiles | Medium |
| 6 | `color_chaos` | Color Chaos | 45–75s | BlockChangeJournal + color grid | Medium |
| 7 | `laser_tag` | Laser Tag | 60–90s | ScoreTracker + routed combat | Medium |
| 8 | `king_of_the_hill` | King of the Hill | 60–90s | Checkpoints + scoring + block placement | Medium–high |
| 9 | `hopper` | Block Jump | 45–75s | Sequential jumps + checkpoints | Medium–high |
| 10 | `mini_skywars` | Mini Skywars | 60–90s | Disposable arena + loot + PvP | High |
| 11 | `warden_escape` | Warden Escape | 60–90s | Arena + checkpoints + entity lifecycle | High |
| 12 | `antwar` | Antwar (MineBattle) | 60–90s | Mining regeneration + economy + queen cores | High |

**Implementation order:**  
`hot_potato` → `speed_race` → `spleef` → `elytra_race` → `shooting_range` → `color_chaos` → `laser_tag` → `king_of_the_hill` → `hopper` → `mini_skywars` → `warden_escape` → `antwar`.

This is a reuse-first order: each stage introduces a small amount of new match logic, then gives later games a foundation to reuse. The detailed plans retain stable minigame IDs so config and implementation references do not drift.

---

## 5. Per-minigame plans

---

### 5.1 Hot Potato — `hot_potato`

**Fantasy:** Pass the exploding potato. Don’t hold it when time runs out.

#### Rules

1. When the game starts, **one** random player receives the Hot Potato item (tagged custom item).
2. Holding the potato applies a short warning (title/particles/glow).
3. Hitting another **in-match** player with melee **passes** the potato (give item, clear from previous).
4. A match timer counts down (config: e.g. 20–30s per “bomb”).
5. On timer end, holder(s) are **eliminated** (explosion FX, spectator). Optionally drop bonus coins for others.
6. If more than one player remains, give potato to a random survivor and start next bomb cycle.
7. Last player standing wins; placements from elimination order (last out = 2nd, etc.).

**Variant (simpler v1):** single bomb cycle only — rank by “holding at boom” last vs not holding; or multi-cycle until one remains.

#### Win / coins

- Placement = elimination order (last alive = 1).
- Coins from shared placement table (config).

#### Tech

| Area | Plan |
|------|------|
| State | `UUID potatoHolder`, bomb task, cycle index |
| Events | `EntityDamageByEntityEvent` (melee only, cancel real damage or set 0) |
| Items | PDC-tagged potato; clear on cancel |
| Packets | Optional outline/glow on holder |
| Real world | None required |

#### Flow

```text
start → snapshot inv → clear inv → give potato to random
     → bomb timer loop
           hold ends → eliminate holder → if >1 alive → new potato
     → last alive → result → restore inv → done
```

#### Cancel / edge cases

- Holder disconnects → potato transfers to random alive, or treat as elimination.
- `cancel()` → cancel tasks, clear potato items, restore snapshots.

#### Config keys (suggested)

```yaml
minigame:
  hot_potato:
    bomb-seconds: 25
    max-cycles: 8
    pass-damage: false
```

#### References

- Cytooxien Hot Potato; TNT-Tag style pass-bomb (public Hot Potato plugins mostly stale — implement thin ourselves).

---

### 5.2 Spleef — `spleef`

**Fantasy:** Break the floor under others. Last one standing wins.

#### Variants

- **Classic Snow:** Players use shovels to destroy the snow floor beneath their opponents.
- **TNT:** Players use Fire Crossbows and arrows to remove TNT floor blocks. The existing Multishot power-up belongs to this variant.

#### Rules

1. Select one variant for the match; all players use the same floor and tool set.
2. In Classic Snow, players spawn on a flat snow platform and use shovels to destroy snow blocks or layers.
3. In TNT, players spawn on a flat TNT platform and use unbreakable Fire Crossbows and arrows to remove TNT floor blocks.
4. Players who fall below the configured Y threshold or leave the arena world are eliminated.
5. The TNT variant may spawn a Multishot power-up above a remaining TNT block; touching it grants Multishot for the configured duration.
6. Last player above the floor wins.

#### Win / coins

- Last standing = 1; others by fall order.
- Placement coin table.

#### Tech

| Area | Plan |
|------|------|
| State | Selected variant, alive set |
| Events | Snow block break, `ProjectileHitEvent` for TNT arrows, fall check on move/tick |
| Blocks | Real break; **no physics**; the per-party arena clone is disposable |
| Weapon | Classic Snow: shovel; TNT: unbreakable `CROSSBOW` with Quick Charge |
| Power-up | TNT only: non-persistent `ItemDisplay` with configurable custom item model |
| Restore | `MinigameRunner` unloads the arena clone on end/cancel |
| Packets | Not for floor |

#### Flow

```text
start → select Classic Snow or TNT → snapshot → load disposable arena clone → teleport spawns
     → assume prebuilt variant floor → give shovels or crossbows → listen breaks/hits + fall
     → TNT power-up touch when enabled
     → last alive / timeout (rank by alive then height)
     → result → unload arena clone → done
```

#### Cancel / edge cases

- Timeout: survivors keep party order through `EliminationTracker`.
- Prevent breaking outside pad bounds.
- Anti-camp (v2): shrink border or damage if idle.

#### Config

```yaml
minigame:
  spleef:
    variant: random # classic_snow or tnt
    arena:
      template: spleef_arena
      spawn: { x: 0.5, y: 70.0, z: 0.5, yaw: 0.0, pitch: 0.0 }
      boundary: { minX: -40, minY: 40, minZ: -40, maxX: 40, maxY: 140, maxZ: 40 }
    timeout-seconds: 90
    fall-y: 60.0
    spawn-radius: 7.0
    classic-snow:
      floor-materials: [SNOW_BLOCK, SNOW]
      tool: DIAMOND_SHOVEL
    tnt:
      floor-materials: [TNT]
      powerup:
        spawn-interval-seconds: 10
        multishot-duration-seconds: 10
        item-model: tnt_multishot
```

#### References

- [Spleef_reloaded](https://github.com/steve4744/Spleef) (active 2025–26): multi-arena, regen, anti-camp.
- BattleArena dynamic arena restore patterns (FAWE / clipboard — optional later).

---

### 5.3 Elytra Race — `elytra_race`

**Fantasy:** Thread the rings, control your glide, and reach the finish before anyone else.

#### Rules

1. Give every player an Elytra, a fixed number of fireworks, and the same starting lane.
2. Start all players together after a short countdown; freeze movement until “GO!”.
3. The course contains visible `ItemDisplay` rings that must be crossed in order. The display is visual only; it has no physical collision and the server does not place ring blocks.
4. Crossing a checkpoint records progress and can grant a small visual or sound confirmation. Missed gates leave the player at their current checkpoint.
5. The first player through the finish ring wins. Finish order is used for the remaining placements; unfinished players are ranked by checkpoint progress and distance.

#### Win / coins

- Finish time/order, then sequential checkpoint progress at timeout.
- Optional bonus coins for a clean run or remaining fireworks; do not let boosts change placement rewards in v1.

#### Tech

| Area | Plan |
|------|------|
| Flight | Enable Elytra flight and provide a controlled firework supply; disable unrelated flight and item use |
| Checkpoints | Ordered ring-plane segment collision; calculate the intersection point in world space, project it onto the ring plane, and reject out-of-order crossings |
| Events | `PlayerMoveEvent` at monitor priority for checkpoint/finish detection; `PlayerToggleFlightEvent` and damage hooks for race rules |
| Anti-skip | Boundary checks, course timeout, and teleport/velocity reset when a player leaves the allowed course |
| Ring display | One non-persistent `ItemDisplay` per ring, using `mcparty:item/elytra_ring`, fixed orientation, and scale matching the model opening to `ring.radius` |
| Collision | Convert movement `from`/`to` to eye positions because setup stores `player.getEyeLocation()`. Require a real plane crossing, same world, gliding, and a point inside `radius + padding`; center bonus uses that intersection point |
| World | Prebuilt disposable course or shared immutable track; ring blocks are never created, checked, or restored |

#### Flow

```text
start → snapshot → equip Elytra and fireworks → teleport lanes
     → countdown → display rings + movement-segment collision → finish order
     → all finished or timeout → result → restore player state
```

#### Cancel / edge cases

- Remove ring displays before finish/cancel; `MatchScope` then restores Elytra, inventory, and flight state from the snapshot.
- A disconnected player is DNF and cannot rejoin the active race.
- Do not count a finish unless every required checkpoint was crossed in order.

#### Config

```yaml
minigame:
  elytra_race:
    timeout-seconds: 75
    fireworks-per-player: 8
    checkpoint-count: 12
    boundary-grace-blocks: 8
```

#### References

- [Cytooxien Minigame Overview](https://www.cytooxien.net/help/minecraft-party-games) — ring-based Elytra racing with optional checkpoints.
- [ElytraRace](https://modrinth.com/plugin/elytrarace) — sequential checkpoint validation, ring courses, timers, and boundary handling.

---

### 5.4 Color Chaos — `color_chaos`

**Fantasy:** Stand on the shown color before the rest of the floor disappears.

#### Rules

1. Pad floor is a multi-color grid (terracotta/wool palette).
2. Each round: announce color (title + inventory dye/item).
3. After delay (starts longer, gets shorter), **non-matching** floor cells become air (or vanish).
4. Players not on a matching solid cell are eliminated (fall or fail check).
5. Rebuild floor → next round until one remains or max rounds (rank by rounds survived).

#### Win / coins

- Last standing, or most rounds survived if timeout.

#### Tech (two backends; pick one for v1)

| Mode | How | Pros |
|------|-----|------|
| **A. Real floor (recommended v1)** | Like BlockParty: `setType(..., false)`, skip unchanged, in-memory material grid | Simple, shared collision correct |
| **B. Hybrid packet** | Memory grid + `sendBlockChange` for paint; eliminate by grid coords; real floor stays full solid OR wrong cells real-air | Less world churn on huge floors |

v1 recommendation: **Mode A** + `BlockChangeJournal` or full-grid rewrite each round + restore pad template at end.

#### Flow

```text
start → generate/paint grid → snapshot journal baseline
     → loop: pick color → show → wait → remove non-match → check standing → restore grid
     → winner → full pad restore → result
```

#### Cancel / edge cases

- Always restore full floor on cancel.
- Standing on edge: use block under feet (from/to block coords).

#### Config

```yaml
minigame:
  color_chaos:
    first-delay-ticks: 60
    min-delay-ticks: 20
    delay-step-ticks: 5
    max-rounds: 12
```

#### References

- [lmk02/BlockParty](https://github.com/lmk02/BlockParty) (updated 2026): real blocks, no physics, in-memory layout, skip same material.
- Cytooxien Color Chaos.

---

### 5.5 King of the Hill — `king_of_the_hill`

**Fantasy:** Race across floating islands with a limited stack of bridge blocks, then fight for the hill on the final island.

#### Rules

1. Each player starts on a separate launch island with an identical stack of blocks in their off-hand. The stack is the only bridge-building supply; no refills or crafting in v1.
2. Players cross a short sequence of floating islands. Blocks may be placed only inside the course’s build corridor, so bridging is meaningful without letting players cover the whole arena.
3. Falling into the void eliminates the player. Reaching an island checkpoint records progress and provides a safe respawn point only if the map requires recovery rather than elimination.
4. The final island contains a raised hill and a capture zone. Entering the hill starts that player’s capture progress; progress pauses while contested and resets when the zone is empty.
5. The first player to hold the uncontested hill for the target duration wins. If nobody completes the target before timeout, rank by hill-control time, final-island arrival, then course progress.

#### Win / coins

- Hill completion order, then total uncontested hill-control time.
- Players eliminated before the hill are ranked by the last island checkpoint reached.

#### Tech

| Area | Plan |
|------|------|
| Course | Prebuilt chain of floating islands with start islands, checkpoint islands, and one final hill island |
| Bridging | `BlockPlaceEvent` validates the build corridor and off-hand block type; normal stack consumption enforces the budget |
| Progress | `PlayerMoveEvent` detects island checkpoints, void falls, hill entry, and hill exit |
| Scoring | `ScoreTracker` records uncontested hill ticks; `EliminationTracker` handles void deaths and disconnects |
| World | Disposable arena clone is preferred because players place blocks and can alter the course |
| UI | Action bar or boss bar shows hill capture progress and remaining block count |

#### Flow

```text
start → snapshot → give equal off-hand block stacks → teleport start islands
     → countdown → bridge through island checkpoints
     → final hill capture → target reached or timeout → result → unload arena
```

#### Cancel / edge cases

- Deny block placement outside the build corridor, into the hill, or above the configured height limit.
- A player who disconnects loses their current hill progress and is placed after all active players.
- If a player runs out of blocks, they must use the existing islands and cannot receive a hidden refill.

#### Config

```yaml
minigame:
  king_of_the_hill:
    timeout-seconds: 90
    hill-hold-seconds: 12
    bridge-blocks: 32
    checkpoint-count: 4
    void-y: 0.0
```

#### References

- [King of the Hill — MC Public Wiki](https://wiki.nerd.nu/wiki/King_of_the_Hill) — control-point scoring based on time held.
- [King of the Hill — Minecraft Map](https://www.minecraftmaps.com/49765-king-of-the-hill) — a floating-island KOTH layout reference.

---

### 5.6 Shooting Range — `shooting_range`

**Fantasy:** Aim carefully, hit valuable targets, and score more points than the other players.

#### Rules

1. Players spawn in separate firing lanes and receive a bow with enough arrows for the whole match.
2. Targets appear in front of each player. Target types include monsters and animals, with each type awarding a configured number of points.
3. A valid hit awards points to the shooter and triggers a target-hit effect; players cannot damage one another or targets in another lane.
4. Targets respawn or rotate during the match so players always have something to aim at.
5. When the time limit ends, the player with the most points wins. Ties are broken by hit count, then by the time of the final scored hit.

#### Win / coins

- Rank by score, then hit count, then final scored hit time.
- Award placement coins and optional point or accuracy bonuses.

#### Tech

| Area | Plan |
|------|------|
| State | `ScoreTracker` for points, hit count, and final scored hit time; target registry |
| Events | Routed `ProjectileHitEvent` / entity-hit handling through `MinigameEventBus` |
| Targets | Real target entities or target blocks inside each lane; no cross-lane scoring |
| Weapons | Bow and arrows; prevent PvP and enforce the arena boundary |
| Packets | Optional hit particles, sounds, score display, and target reset FX |
| Cleanup | Despawn owned targets and clear projectiles on end/cancel |

#### Flow

```text
start → snapshot → load disposable arena clone → teleport players to firing lanes
     → give bows/arrows → spawn target set → score valid hits and refresh targets
     → timeout → rank by score → result → despawn targets/unload arena clone → done
```

#### Cancel / edge cases

- Ignore hits after the match has completed or been cancelled.
- Don’t score targets outside the player’s lane or projectiles fired by non-participants.
- Despawn all targets and clear arrows on cancel; restore every player through `MatchScope`.

#### Config

```yaml
minigame:
  shooting_range:
    timeout-seconds: 60
    target-refresh-ticks: 20
    points:
      zombie: 1
      cow: 2
      skeleton: 3
      bullseye: 5
```

#### References

- [Cytooxien Minecraft Party minigames](https://www.cytooxien.net/en/help/minecraft-party-games#shooting-range): hit monster and animal targets for different point values; highest score within the time limit wins.

---

### 5.7 Warden Escape — `warden_escape`

**Fantasy:** Escape the deep dark before the Warden hears you, using stealth, timing, and distractions instead of combat.

#### Rules

1. Players start at separate entrances in a compact Ancient City or deep-dark course. Each route leads to the same exit or to equivalent exits with a shared finish order.
2. Give each player a small distraction kit, such as snowballs, and prevent ordinary combat gear from turning the game into a Warden fight.
3. Movement, block placement/breaking, projectiles, and other configured actions can create noise or trigger sculk hazards. Native sculk sensors and shriekers remain part of the map’s threat pattern.
4. One or more Wardens patrol or spawn after the opening phase. Players may hide, sneak, use wool routes, or throw distractions, but killing a Warden is not a valid shortcut.
5. The first player through the exit wins. Warden death, leaving the course, or the timeout eliminates a player; survivors are ranked by exit/checkpoint progress.

#### Win / coins

- Exit order, then checkpoint progress for players who do not escape.
- Optional bonus coins for escaping without triggering a personal Warden warning threshold.

#### Tech

| Area | Plan |
|------|------|
| Arena | Disposable Ancient City/deep-dark clone with fixed entrances, checkpoints, exit, wool routes, and sculk hazards |
| Wardens | Spawn and remove only Wardens owned by the match; never affect players outside the match |
| Noise | Use native sculk behavior where possible, with a small match-owned noise hook for actions the event bus can observe |
| Events | Movement/checkpoint/finish, block place/break, projectile throws, Warden damage, player damage, and entity lifecycle hooks |
| State | `CheckpointTracker`, Warden warning state, elimination order, and per-player finish timestamps |
| UI | Darkness-safe action bar, warning sounds/titles, and a finish beacon; avoid revealing every player’s position |

#### Flow

```text
start → snapshot → equip distraction kit → teleport entrances
     → countdown → Wardens active → checkpoints and stealth route
     → exit order / eliminations → timeout → unload arena → result
```

#### Cancel / edge cases

- Remove all match-owned Wardens, projectiles, items, and temporary effects before unloading the arena.
- Cancel must restore players even if a Warden is currently targeting or damaging them.
- A player who disconnects is eliminated immediately; do not leave a Warden targeting an unloaded player.

#### Config

```yaml
minigame:
  warden_escape:
    arena:
      template: warden_escape_arena
      checkpoint-count: 5
    timeout-seconds: 90
    warden-count: 2
    distraction-items: 8
    eliminate-on-warden-hit: true
```

#### References

- [Warden — Minecraft](https://www.minecraft.net/en-us/article/warden) — blindness, vibration detection, distractions, and the recommendation to avoid combat.
- [Exploring an ancient city — Minecraft Wiki](https://minecraft.fandom.com/wiki/Tutorials/Exploring_an_ancient_city) — escape routes and waiting for a Warden to calm down.

---

### 5.8 Mini Skywars — `mini_skywars`

**Fantasy:** Loot your island, bridge to the center, and be the last player standing above the void.

#### Rules

1. Each player starts on a compact floating island with a private loot chest and a light, fixed kit. The center island contains stronger or more valuable loot.
2. Players may bridge, loot, place, and break blocks inside the disposable arena. Void falls and normal combat eliminate players; there are no respawns.
3. The arena has a short grace period before PvP and a visible boundary. After the midpoint, the boundary or safe area begins to tighten so players cannot hide indefinitely.
4. Last player alive wins. If the timer expires, rank survivors by eliminations, then health, then distance from the center or remaining safe-area progress.

#### Win / coins

- Last alive first; elimination order for the rest.
- Optional small bonus for eliminations, capped so combat rewards do not outweigh placement coins.

#### Tech

| Area | Plan |
|------|------|
| Arena | Disposable per-party floating-island clone with player islands, center island, void threshold, and loot chests |
| Loot | Match-owned chest contents generated from a fixed tier table; clear or discard with the arena |
| PvP | Route damage, projectile, block place/break, and void-fall events through the shared `MinigameEventBus` |
| State | `EliminationTracker`, elimination count, grace-period task, and optional shrinking safe boundary |
| Cleanup | Unload the arena clone rather than attempting to journal arbitrary player block edits |
| UI | Remaining-player count, grace/PvP state, and optional center-loot indicator |

#### Flow

```text
start → snapshot → load disposable arena → fill island chests → teleport islands
     → grace countdown → PvP and bridging → boundary pressure
     → last alive or timeout → result → unload arena → restore player state
```

#### Cancel / edge cases

- Clear all match-owned entities and projectiles before unloading the clone.
- A player who disconnects is eliminated and their island loot is discarded with the arena.
- Do not let players place blocks outside the configured arena or use the lobby/board world as an escape route.

#### Config

```yaml
minigame:
  mini_skywars:
    arena:
      template: mini_skywars_arena
      spawn-radius: 0.0
      boundary: { minX: -48, minY: 40, minZ: -48, maxX: 48, maxY: 160, maxZ: 48 }
    grace-seconds: 15
    duration-seconds: 90
    border-shrink-start-seconds: 45
    kill-bonus-coins: 1
```

#### References

- [Skywars — Battle on Floating Sky Islands](https://miniblox.io/game/skywars) — the compact loop of island loot, bridging, combat, and last-player-standing.
- [Always Building: Download the final map!](https://www.minecraft.net/en-us/article/always-building-download-final-map) — official Minecraft floating-island and bridge-building map inspiration explicitly described as a basis for a Sky Wars map.

---

### 5.9 Antwar — `antwar`

**Fantasy:** Mine to grow your colony, fortify your burrow, and destroy every rival queen.

#### Rules

1. Each player starts in a separate burrow with a protected queen core, a pickaxe, and a small starter kit. The queen core is the player’s elimination anchor.
2. Players mine regenerating resource nodes around the map. Iron is basic equipment, gold supports ranged gear, copper provides healing, and diamond unlocks the strongest loot tier.
3. During the preparation phase, players may mine and build defenses. After PvP opens, players can tunnel, raid, and fight, but the queen core remains inside the configured base region.
4. Destroying a queen core eliminates its owner. A player who dies before their core is destroyed returns once with reduced gear; the second death or core destruction eliminates them.
5. Last living queen wins. If the timer expires, rank by queen-core health, eliminations, and mined resource value.

#### Win / coins

- Last queen alive first, then elimination order or the timeout score.
- Optional resource and elimination bonuses are capped so mining cannot outweigh match placement.

#### Tech

| Area | Plan |
|------|------|
| Arena | Disposable mine/burrow clone with protected base regions, regenerating ore nodes, and a neutral center |
| Mining | `BlockBreakEvent` allows only configured resource nodes; nodes respawn from a fixed tier table |
| Economy | Convert mined resources into kits, healing, blocks, and one-use scout pulses; keep the v1 shop small |
| Combat | Shared damage and projectile routing; queen-core damage is a separate protected-region hook |
| State | `ScoreTracker` for mined value/eliminations and `EliminationTracker` for queen deaths |
| Cleanup | Unload the clone so tunnels, defenses, drops, and temporary loot cannot leak into the board world |

#### Flow

```text
start → snapshot → load burrow arena → give pickaxes → resource phase
     → PvP opens → mine, fortify, raid, and attack queen cores
     → last queen or timeout → result → unload arena → restore player state
```

#### Cancel / edge cases

- Deny block changes outside the mine, build, and queen-core regions.
- A disconnected player loses their queen and is eliminated; remove their drops before unloading.
- Prevent resource duplication by marking each node with its match and tier, then consume it before scheduling regeneration.

#### Config

```yaml
minigame:
  antwar:
    arena:
      template: antwar_arena
      resource-region: { minX: -48, minY: 40, minZ: -48, maxX: 48, maxY: 100, maxZ: 48 }
    preparation-seconds: 20
    duration-seconds: 90
    respawn-lives: 1
    ore-regeneration-seconds: 8
    scout-pulse-seconds: 20
```

#### References

- [Minebattle — Minecraft PE Maps](https://mcpedl.com/minebattle-minigame/) — mining tiered ores for gear, then fighting until the last player remains.
- [Ant War: How to Play](https://antwargame.com/play) — worker resource gathering, defensive structures, scouting, and destroying enemy queens.

---

### 5.10 Block Jump — `hopper`

**Fantasy:** Master a chain of jumps, take risks for speed, and reach the finish before everyone else.

#### Rules

1. Players start at separate lanes or staggered starting points on the same Block Jump course. Normal jumping remains enabled; there is no automatic bounce.
2. The course is a sequential chain of blocks and platforms. Players must land on the current jump before the next jump is validated; skipping ahead does not count.
3. The next jump is generated or revealed after valid progress. Every generated jump must pass a reachability check for the player’s normal movement and jump limits.
4. Players may activate optional checkpoints between jumps for a safer reset. Each checkpoint adds a configurable delay before the next jump is generated, trading safety for speed.
5. Falling or missing a jump resets the player to the start or the last chosen checkpoint with a time penalty. The first player to finish wins; players who do not finish are ranked by validated jump progress at timeout.

#### Win / coins

- Finish order is the primary ranking; unfinished players are ranked by validated jump count and time penalty.
- Optional bonus for finishing without using a checkpoint or reset.

#### Tech

| Area | Plan |
|------|------|
| Movement | Use normal player jumping and movement; do not apply automatic bounce, Jump Boost, flight, or Elytra movement |
| Course | Sequential fixed or generated platform AABBs with a visible next-jump target and finish region |
| Generation | Deterministic seeded layouts; validate horizontal/vertical gaps and reachability before showing each jump |
| Progress | `CheckpointTracker` stores the next required jump, validated jump count, checkpoint choice, and finish timestamp |
| Events | `PlayerMoveEvent` for landing/progress, reset/finish handling, and cleanup of match-owned course blocks or entities |
| UI | Show the next jump, current progress, checkpoint delay, and a short reset/finish message |

#### Flow

```text
start → snapshot → load/generate Block Jump course → teleport starts → countdown
     → reveal next jump → normal jump to next block → optional checkpoint or reset
     → finish or timeout → result → unload arena → restore player state
```

#### Cancel / edge cases

- Do not validate a jump from below, from outside the intended landing area, or after the player skips the required sequence.
- A reset must preserve the player’s validated progress and apply the configured time penalty; checkpoint use must add its generation delay only once.
- A disconnected player is ranked after active players using their last validated jump.
- Clear match-owned course blocks/entities and restore snapshots on cancel.

#### Config

```yaml
minigame:
  hopper:
    arena:
      template: block_jump_arena
      jump-count: 20
      max-horizontal-gap: 5
      max-vertical-gap: 3
    timeout-seconds: 75
    checkpoint-delay-ticks: 20
    max-checkpoints: 3
    reset-time-penalty-seconds: 2
```

#### References

- [Cytooxien Minecraft Party minigames](https://www.cytooxien.net/en/help/minecraft-party-games) — Block Jump is a race across sequential jumps; checkpoints offer safety but delay generation of the next jump.

---

### 5.11 Laser Tag — `laser_tag`

**Fantasy:** Take cover, track your targets, and finish with the highest blaster score.

#### Rules

1. Spawn players in a compact arena with cover and an identical blaster kit.
2. Right-clicking the blaster fires a hitscan ray. A hit on an in-match player awards score and applies a short invulnerability window; misses use a longer cooldown.
3. Damage is cancelled and converted into score/knockback only. There are no real deaths or inventory drops in v1.
4. The arena contains moving cover or sightline changes only if they are map-owned; do not create fake collision with packets.
5. Time limit ends the game. Rank by score, then last scorer or first score as the tie-breaker.

#### Win / coins

- Score descending → placement.
- Optional capped streak bonus coins.

#### Tech

| Area | Plan |
|------|------|
| Weapon | Raytrace from the player’s eye direction, max range, and first in-match player hit |
| Events | Interact/right-click, damage cancellation, projectile cleanup, and player quit through `MinigameEventBus` |
| State | `ScoreTracker`, per-player cooldowns, hit timestamps, and optional streak count |
| UI | Action bar score, hit sound, cooldown feedback, and a small match scoreboard |
| Packets | Hit particles and tracer visuals only; never rely on fake blocks for cover or collision |
| World | Prebuilt arena or disposable clone; clear dropped items/projectiles on end |

#### Flow

```text
start → snapshot → give blasters → soft PvP rules → countdown
     → ray hits award score → timer end → rank scores
     → clear effects/projectiles → restore → result
```

#### Cancel / edge cases

- Clear blasters, projectiles, cooldowns, scores, and temporary effects on cancel.
- Ignore friendly/non-match entities and hits through the arena boundary.
- A disconnected player keeps their recorded score but is placed after active tied players.

#### Config

```yaml
minigame:
  laser_tag:
    duration-seconds: 60
    hit-score: 1
    cooldown-miss-ticks: 15
    cooldown-hit-ticks: 8
    range: 30
    streak-bonus-cap: 3
```

#### References

- Cytooxien Laser Tag — score-based FFA blaster gameplay; implement the hitscan weapon in-house.

---

### 5.12 Speed Race — `speed_race`

**Fantasy:** Run the fastest route, chain every speed boost, and beat the clock to the finish.

#### Rules

1. Players start on parallel lanes with the same movement kit and a synchronized countdown.
2. The course uses speed pads, jump pads, ice/water sections, short parkour cuts, and optional collectible boosts. Map geometry supplies the challenge; v1 does not add combat.
3. Checkpoints must be crossed in order. Falling or taking a wrong route returns the player to the last checkpoint with a short time penalty.
4. The first player through the finish wins. Finish order is followed by checkpoint progress and distance at timeout.
5. A player may take a harder shortcut, but a shortcut must still cross the required checkpoint sequence.

#### Win / coins

- Finish time/order, then checkpoint progress at timeout.
- Optional bonus coins for collecting every boost or completing a shortcut without a reset.

#### Tech

| Area | Plan |
|------|------|
| Course | Prebuilt short track with parallel starts, ordered checkpoints, speed/jump pads, and one finish region |
| Movement | Map blocks or match-owned launch pads; apply only temporary speed effects and restore them on end |
| Progress | `CheckpointTracker` validates order and records the last safe location/time |
| Events | `PlayerMoveEvent` for checkpoint/finish, pressure/interact for boosts, and damage/fall hooks for resets |
| State | Finish timestamps, reset counts, checkpoint progress, and timeout distance |
| World | Shared immutable track or disposable course clone; no player block editing |

#### Flow

```text
start → snapshot → teleport lanes → countdown → speed sections and checkpoints
     → finish order / checkpoint resets → all finished or timeout
     → result → restore player state
```

#### Cancel / edge cases

- Ignore repeated finish entries after a player has finished.
- Re-entering a checkpoint does not advance progress twice.
- Disconnect is DNF and ranks after unfinished active players.

#### Config

```yaml
minigame:
  speed_race:
    timeout-seconds: 75
    checkpoint-count: 10
    reset-time-penalty-seconds: 2
    pickup-bonus-coins: 1
```

#### References

- [Cytooxien Minigame Overview](https://www.cytooxien.net/help/minecraft-party-games) — race modes built around finish order, checkpoints, and movement challenges.
- [MC Championship](https://en.wikipedia.org/wiki/MC_Championship) — Ace Race as a Minecraft movement race with laps and powerups.

---

## 6. Coin & placement model

Shared for all twelve (matches current Dummy behavior, config-driven):

```yaml
minigame:
  coin-rewards: [10, 7, 5, 3]  # 1st, 2nd, 3rd, 4th+
```

- Always set placement for every participant still in the party at resolve time.
- Offline mid-minigame: place as eliminated last among leavers.
- Optional per-game **bonus coins** (Elytra clean runs, Skywars eliminations, Antwar resources, Block Jump clean runs, Laser Tag streaks, Speed Race pickups, potato explosion drops) added on top of placement coins in `MinigameResult`.

---

## 7. Manager & selection plan

### Phase A

- Keep `DummyMinigame` as fallback.
- Add `MinigameRegistry` + `run(id)` / `runRandom(excludeRecent)`.
- `BoardTurnController` calls random real game when registry size ≥ 1, else dummy.

### Phase B

- Weighted random; optional “no repeat last 2”.
- Config enable/disable per minigame id.

### Phase C (later)

- Vote UI (Cytooxien-style) — not required for launch twelve.

---

## 8. Config surface (summary)

```yaml
minigame:
  coin-rewards: [10, 7, 5, 3]
  dummy-duration-seconds: 5
  enabled:
    - hot_potato
    - speed_race
    - spleef
    - elytra_race
    - shooting_range
    - color_chaos
    - laser_tag
    - king_of_the_hill
    - hopper
    - mini_skywars
    - warden_escape
    - antwar
  hot_potato: { ... }
  speed_race: { ... }
  spleef: { ... }
  elytra_race: { ... }
  shooting_range: { ... }
  color_chaos: { ... }
  laser_tag: { ... }
  king_of_the_hill: { ... }
  hopper: { ... }
  mini_skywars: { ... }
  warden_escape: { ... }
  antwar: { ... }
```

Wire new keys through `PluginConfig` + default `config.yml` together (per `AGENTS.md`).

---

## 9. Arena / admin setup (planned)

Until a full setup command set exists:

| Data | Purpose |
|------|---------|
| Pad world (usually party board world) | Where minigames run |
| Pad cuboid | Bounds for break/fall/claims |
| Spawn list (4–12 points) | Fair starts |
| Per-game extras | Elytra rings; island checkpoints and hill zone; Warden exit; Skywars loot/spawns; Antwar cores/ore; Block Jump course/jump bounds; Laser Tag arena; Shooting Range lanes/targets; Speed Race boosts/checkpoints |

**Future admin commands** (sketch):

```text
/partyadmin minigame pad set          # WE selection → pad bounds
/partyadmin minigame spawn add
/partyadmin minigame finish set       # Elytra Race / Warden Escape
/partyadmin minigame hill set         # King of the Hill capture zone
/partyadmin minigame islands scan     # Mini Skywars island and loot points
/partyadmin minigame antwar set       # Queen cores and resource nodes
/partyadmin minigame hopper scan      # Block Jump sequence and checkpoint bounds
/partyadmin minigame laser set        # Laser Tag arena bounds and spawns
/partyadmin minigame speed set        # Speed Race pads and checkpoints
```

Store in `slots.yml` or `minigames.yml` next to board data; remap with `forWorld` on slime clones like board slots.

---

## 10. Testing checklist (per game)

- [ ] Starts only with ≥2 online players in party
- [ ] `cancel()` mid-game leaves no tasks, listeners, or broken floor
- [ ] Inventory/gamemode restored for all participants
- [ ] Placements unique 1…n for participants
- [ ] Coins applied once on main thread
- [ ] Disconnect mid-game does not soft-lock manager (`active` cleared)
- [ ] Party end during minigame cancels cleanly
- [ ] No block leaks outside pad
- [ ] No leftover Spleef variant items, targets, or projectiles on board after return

---

## 11. Deferred (not in this plan)

- Teams (2v2, 1v3), Duels
- Parkour as a separate ranked course pack
- Lucky Towers, One in the Chamber, Memorize
- FAWE clipboard service, full Cytooxien power-up meta
- Vote rotation UI, MySQL minigame history

---

## 12. Suggested build milestones

| Milestone | Deliverable |
|-----------|-------------|
| **M0** | Registry + random pick + Dummy fallback |
| **M1** | `PlayerStateSnapshot` + `MatchScope` + `hot_potato` |
| **M2** | `speed_race` + `spleef` on a disposable arena clone |
| **M3** | `elytra_race` + `shooting_range` |
| **M4** | `color_chaos` + `laser_tag` |
| **M5** | `king_of_the_hill` + `hopper` |
| **M6** | `mini_skywars` (disposable arena with loot/PvP) |
| **M7** | `warden_escape` (disposable arena with Warden lifecycle) |
| **M8** | `antwar` (mining regeneration, economy, queen cores) |
| **M9** | Config toggles, polish FX, admin pad setup |

---

## 13. Quick id reference

| Id | One-line rule |
|----|----------------|
| `hot_potato` | Pass the potato; holder at boom is out |
| `speed_race` | Chain boosts and checkpoints to finish first |
| `spleef` | Choose Classic Snow or TNT; break the floor and be last above |
| `elytra_race` | Fly through ordered rings to finish |
| `shooting_range` | Hit monster and animal targets to score the most points |
| `color_chaos` | Stand on the announced color |
| `laser_tag` | Score hits with a blaster before time ends |
| `king_of_the_hill` | Bridge across islands and hold the final hill |
| `hopper` | Jump through the sequence and finish first; checkpoints trade time for safety |
| `mini_skywars` | Loot, bridge, fight, and be last alive |
| `warden_escape` | Escape the deep dark before the Warden gets you |
| `antwar` | Mine resources, fortify your burrow, and destroy rival queens |

---

This plan is the working contract for implementing the launch twelve. Update it when a game’s rules or arena model change; keep `mcparty.md` for product-level vision and `AGENTS.md` for bootstrap/SPI wiring.
