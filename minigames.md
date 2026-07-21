# McParty — Minigame Design & Implementation Plan

**Version:** 1.0  
**Updated:** 2026-07-21  
**Scope:** Launch set of **8 free-for-all** minigames between board dice rounds  
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
3. **Real physics where fall/stand matters** — Spleef, Floor is Lava, Race terrain.
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
| `BlockChangeJournal` | pos → old `BlockData`; batch restore N blocks/tick | Spleef, Floor is Lava, Color Chaos (real mode) |
| `EliminationTracker` | elimination order → placements | Hot Potato, Spleef, Musical Chairs, Color Chaos, Floor is Lava |
| `Region` / AABB | integer bounds; contains / finish line | Red Light, Race, arenas |
| `ScoreTracker` | UUID → score; rank by score | Laser Tag |
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
| Fake walls, UI titles, boss bars | Spleef / Floor is Lava floors |
| Optional Color Chaos client paint | Race / Red Light terrain |
| Chair markers, potato glow FX | Player damage, item pass |
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

| # | Id | Display name | Length | Engine | Difficulty to build |
|---|-----|--------------|--------|--------|---------------------|
| 1 | `hot_potato` | Hot Potato | 45–60s | Pass + eliminate | Low |
| 2 | `spleef` | Spleef | 60–90s | Floor break + last standing | Low–med |
| 3 | `musical_chairs` | Musical Chairs | 45–75s | Seat claim rounds | Low–med |
| 4 | `color_chaos` | Color Chaos | 45–75s | Color floor vanish | Medium |
| 5 | `red_light` | Red Light, Green Light | 45–60s | Freeze race | Low–med |
| 6 | `floor_is_lava` | Floor is Lava | 60–90s | Trail vanish | Medium |
| 7 | `race` | Race | 45–75s | Finish-line race | Medium (map) |
| 8 | `laser_tag` | Laser Tag | 60–90s | Score FFA | Medium |

**Implementation order:**  
`hot_potato` → `spleef` (+ journal) → `musical_chairs` → `red_light` → `color_chaos` → `floor_is_lava` → `race` → `laser_tag`.

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

#### Rules

1. Players spawn on a flat multi-layer or single-layer snow/terracotta platform.
2. Each gets a shovel (instant or fast break on arena materials only).
3. Breaking floor blocks removes them (real world); players who fall below Y threshold or into void/water are eliminated.
4. Optional: snowballs with knockback later (v2).
5. Last player above the floor wins.

#### Win / coins

- Last standing = 1; others by fall order.
- Placement coin table.

#### Tech

| Area | Plan |
|------|------|
| State | Alive set, `BlockChangeJournal` |
| Events | `BlockBreakEvent` (only arena materials + match players), fall check on move/tick |
| Blocks | Real break; **no physics**; journal every change |
| Restore | Batch restore journal on end/cancel |
| Packets | Not for floor |

#### Flow

```text
start → snapshot → teleport spawns → fill/ensure platform (or assume prebuilt)
     → give shovels → listen breaks + fall
     → last alive / timeout (rank by alive then height)
     → restore journal → result → done
```

#### Cancel / edge cases

- Timeout: rank remaining by Y height then random.
- Prevent breaking outside pad bounds.
- Anti-camp (v2): shrink border or damage if idle.

#### Config

```yaml
minigame:
  spleef:
    fall-y: <pad minY - 2>
    tool: DIAMOND_SHOVEL
    timeout-seconds: 90
```

#### References

- [Spleef_reloaded](https://github.com/steve4744/Spleef) (active 2025–26): multi-arena, regen, anti-camp.
- BattleArena dynamic arena restore patterns (FAWE / clipboard — optional later).

---

### 5.3 Musical Chairs — `musical_chairs`

**Fantasy:** When the music stops, sit. One fewer chair each round.

#### Rules

1. Pre-place or generate **N = players − 1** chairs (blocks or interaction points) on the pad.
2. **Music phase** (e.g. 5–8s): players can move freely; optional coin particles.
3. **Stop phase:** title “STOP!” — players must stand on a free chair region within a short claim window (e.g. 3s).
4. At most one claim per chair; unclaimed players eliminated.
5. Remove one chair, repeat until one player remains (or two players one chair → last claimer wins).

#### Win / coins

- Last seated / elimination order.
- Optional mid-music coin pickups as bonus coins in result.

#### Tech

| Area | Plan |
|------|------|
| Chairs | Fixed `Location` list or armor-stand/display markers + AABB claim |
| Events | Move or interact to claim during STOP only |
| Packets | Optional display-entity chairs (claim still by AABB) |
| Real world | Optional stair blocks; no mass restore needed if prebuilt |

#### Flow

```text
start → place/select chairs (n-1)
     → loop: music → stop → claim → eliminate fails → remove 1 chair
     → one left → result
```

#### Cancel / edge cases

- Disconnect during claim → eliminate that player.
- Two players same chair: first claim wins (timestamp).

#### Config

```yaml
minigame:
  musical_chairs:
    music-seconds: 6
    claim-seconds: 3
```

#### References

- Cytooxien Musical Chairs (chairs rebrand); implement as round-based elimination.

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

### 5.5 Red Light, Green Light — `red_light`

**Fantasy:** Run on green. Freeze on red. First to the finish wins.

#### Rules

1. Players start on a start line; finish AABB at the far end of a straight (or simple) lane.
2. **Green:** free movement.
3. **Red:** any **block-position change** (or velocity threshold) applies penalty: teleport back N blocks or to last legal pos.
4. Light intervals random or alternating with shortening greens (config).
5. First player to enter finish region wins; others ranked by distance along track when timer ends.

#### Win / coins

- Finish order; unfinished by progress (distance along axis or checkpoints).

#### Tech

| Area | Plan |
|------|------|
| Events | `PlayerMoveEvent` filtered to same-block ignore; only during red |
| State | light enum, task flipping lights, progress double |
| World | Prebuilt lane; no block restore |
| UI | Titles GREEN/RED; optional boss bar |

#### Flow

```text
start → teleport start line → freeze countdown
     → loop green/red until all finished or timeout
     → rank finish order + distance → result
```

#### Cancel / edge cases

- Rubber-band only on intentional move, not head rotation.
- Knockback from others: count as move on red (fair chaos) or ignore horizontal from damage (v1: count all).

#### Config

```yaml
minigame:
  red_light:
    green-seconds-min: 2
    green-seconds-max: 5
    red-seconds-min: 2
    red-seconds-max: 4
    penalty-blocks: 3
    timeout-seconds: 60
```

#### References

- Cytooxien Red Light, Green Light; Squid-Game style plugins (logic only).

---

### 5.6 Floor is Lava — `floor_is_lava`

**Fantasy:** The floor dies under your feet. Cut others off. Last up wins.

#### Rules

1. Players on a wide platform (can be multi-layer later).
2. When a player **leaves** a block (or after standing delay), that block is queued to become air after D ticks (TNTRun-style).
3. Deduplicate positions in the vanish queue.
4. Fall below threshold → eliminate.
5. Last standing wins; timeout ranks by survival time / height.

#### Win / coins

- Elimination order / last alive.

#### Tech

| Area | Plan |
|------|------|
| State | `Set` of broken positions, delay queue, journal |
| Events | Move (block change only) → schedule vanish |
| Blocks | Real air; no physics; **same journal as Spleef** |
| Packets | No |

#### Flow

```text
start → spawns on platform
     → on step-off: queue block → later set air + journal
     → falls eliminate → last alive → batch restore → result
```

#### Cancel / edge cases

- Same restore path as Spleef.
- Don’t break blocks outside pad.
- Optional: blocks under player only break after they step off (not instantly under feet) to reduce unfair instant falls.

#### Config

```yaml
minigame:
  floor_is_lava:
    destroy-delay-ticks: 8
    timeout-seconds: 90
```

#### References

- [TNTRun_reloaded](https://github.com/steve4744/TNTRun) (active 2026): destroy delay, multi-layer, auto regen.
- Cytooxien Floor is Lava (score by blocks destroyed optional v2).

---

### 5.7 Race — `race`

**Fantasy:** First across the finish line.

#### Rules

1. Players line up at race start (gates optional).
2. On go, run along a short course to finish AABB.
3. Optional: coin pickups on path → bonus coins and/or brief speed.
4. Optional: trampolines as map-only slime/honey blocks (no special code v1).
5. Rank by finish time; DNF by progress at timeout.

#### Win / coins

- Finish order; placement coins + optional pickup bonus coins.

#### Tech

| Area | Plan |
|------|------|
| Regions | start box, finish box, optional checkpoint list |
| Events | Move enter finish once; pickup interact or pressure |
| World | Prebuilt track; no restore |
| State | finished list with timestamps |

#### Flow

```text
start → teleport lanes → countdown → unfreeze
     → finish enter → record place
     → all finished or timeout → result
```

#### Cancel / edge cases

- Re-enter finish ignored after first.
- Disconnect = DNF last among unfinished.

#### Config

```yaml
minigame:
  race:
    timeout-seconds: 75
    pickup-bonus-coins: 1
```

#### Map requirement

Needs a **short track** in the pad world (or path points). Until built, dev can use a straight corridor between two regions.

#### References

- Cytooxien Race (coins, fans — v2); keep v1 foot-race only (no horse/elytra).

---

### 5.8 Laser Tag — `laser_tag`

**Fantasy:** Tag others with a blaster. Highest score wins.

#### Rules

1. Small arena with cover; all players same kit (blaster item).
2. Right-click / interact fires a **hitscan** ray (or snowball v1 if faster to ship).
3. Hit on in-match player = +1 score (config); short invuln frames optional.
4. Miss → longer cooldown; hit → shorter cooldown (Cytooxien-style, optional).
5. Melee punch = small score or knockback only.
6. Time limit ends game; highest score wins (ties by last scorer or first to score).

#### Win / coins

- Score descending → placement; optional killstreak bonus coins.

#### Tech

| Area | Plan |
|------|------|
| Preferred | Raytrace from eye along direction, max range, ignore non-match |
| Alt v1 | Snowball projectile + owner track (clear all on end) |
| State | `ScoreTracker`, cooldown map |
| UI | Action bar score; sound on hit |
| Packets | Hit particles; avoid armor-stand spam |

#### Flow

```text
start → snapshot → kit blaster → soft PvP rules
     → on hit: score++ → action bar
     → timer end → rank scores → restore → result
```

#### Cancel / edge cases

- Clear projectiles and scores on cancel.
- No real death: cancel damage, apply score only (or 1-heart with auto-regen off and freeze on “out” if using lives — v1 score-only, no eliminate).

#### Config

```yaml
minigame:
  laser_tag:
    duration-seconds: 60
    hit-score: 1
    cooldown-miss-ticks: 15
    cooldown-hit-ticks: 8
    range: 30
```

#### References

- Cytooxien Laser Tag; avoid unmaintained Laser Tag OSS — implement hitscan in-house.

---

## 6. Coin & placement model

Shared for all eight (matches current Dummy behavior, config-driven):

```yaml
minigame:
  coin-rewards: [10, 7, 5, 3]  # 1st, 2nd, 3rd, 4th+
```

- Always set placement for every participant still in the party at resolve time.
- Offline mid-minigame: place as eliminated last among leavers.
- Optional per-game **bonus coins** (race pickups, laser streaks, potato explosion drops) added on top of placement coins in `MinigameResult`.

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

- Vote UI (Cytooxien-style) — not required for launch eight.

---

## 8. Config surface (summary)

```yaml
minigame:
  coin-rewards: [10, 7, 5, 3]
  dummy-duration-seconds: 5
  enabled:
    - hot_potato
    - spleef
    - musical_chairs
    - color_chaos
    - red_light
    - floor_is_lava
    - race
    - laser_tag
  hot_potato: { ... }
  spleef: { ... }
  musical_chairs: { ... }
  color_chaos: { ... }
  red_light: { ... }
  floor_is_lava: { ... }
  race: { ... }
  laser_tag: { ... }
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
| Per-game extras | Race finish AABB; chair points; color floor Y level |

**Future admin commands** (sketch):

```text
/partyadmin minigame pad set          # WE selection → pad bounds
/partyadmin minigame spawn add
/partyadmin minigame finish set       # race / red light
/partyadmin minigame chairs scan     # optional
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
- [ ] No leftover potato/blaster items on board after return

---

## 11. Deferred (not in this plan)

- Teams (2v2, 1v3), Duels
- Horse / Elytra race, Parkour as separate ranked course pack
- Lucky Towers, One in the Chamber, Shooting Range, Memorize
- FAWE clipboard service, full Cytooxien power-up meta
- Vote rotation UI, MySQL minigame history

---

## 12. Suggested build milestones

| Milestone | Deliverable |
|-----------|-------------|
| **M0** | Registry + random pick + Dummy fallback |
| **M1** | `PlayerStateSnapshot` + `MatchScope` + `hot_potato` |
| **M2** | `BlockChangeJournal` + `spleef` |
| **M3** | `musical_chairs` + `red_light` |
| **M4** | `color_chaos` (real floor) + `floor_is_lava` |
| **M5** | `race` (needs finish region) + `laser_tag` |
| **M6** | Config toggles, polish FX, admin pad setup |

---

## 13. Quick id reference

| Id | One-line rule |
|----|----------------|
| `hot_potato` | Pass the potato; holder at boom is out |
| `spleef` | Break floor; last above wins |
| `musical_chairs` | When music stops, claim a seat |
| `color_chaos` | Stand on the announced color |
| `red_light` | Run green, freeze red, reach finish |
| `floor_is_lava` | Floor vanishes behind you |
| `race` | First to finish line |
| `laser_tag` | Most tags when time ends |

---

This plan is the working contract for implementing the launch eight. Update it when a game’s rules or arena model change; keep `mcparty.md` for product-level vision and `AGENTS.md` for bootstrap/SPI wiring.
