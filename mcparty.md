# McParty — System Design Document

**Version:** 1.1  
**Updated:** 2026-07-21  
**Initial target capacity:** 400+ concurrent players  
**Style:** Mario Party Superstars + Cytooxien Minecraft Party + classic Minecraft party maps

---

## 1. Project goals

- Build a large-scale **Mario Party** experience in Minecraft.
- Keep the fun, high-interaction feel of the original (prefer small instances: **4–8 players**, hard cap around 10–12).
- Ship **8 launch minigames** first, expand toward **10–12** soon after.
- Stay easy to extend later (new minigames, boards, Cytooxien-style rotation).
- Use **AdvancedSlimePaper (ASP)** for efficient world/instance load and unload.

---

## 2. Tech stack

| Component | Technology | Notes |
|-----------|------------|--------|
| Server software | **AdvancedSlimePaper** | Fast world load/unload, lower RAM per instance |
| Proxy | **Velocity** | Preferred over BungeeCord when multi-server |
| Core plugin | Java (Paper API) | Custom plugin (`dev.epicc`) |
| Long-term database | **MySQL** | Stats, career coins/stars |
| Cache / runtime | **Redis** | Instance state, queue, multi-server sync |
| World management | ASP Slime format | Dynamic clone load/unload |
| Matchmaking | Custom (room-based + dynamic countdown) | 60s (>10 in queue), 30s (>20), 10s (full/40) |

**Current implementation phase:** single-process in-memory party rooms, board dice, dummy/minigame SPI, ASP board clones. Redis/MySQL/Velocity are design targets, not all implemented yet.

---

## 3. High-level architecture (400 players)

```
Velocity Proxy
     │
     ├── Lobby Server (1 Paper server)
     │
     ├── Game Server 1 (64GB) → 12–18 MC Party instances
     ├── Game Server 2 (64GB) → 12–18 MC Party instances
     └── Game Server 3 (64GB) → 12–18 MC Party instances   ← when scaling
```

**Responsibilities:**

- **Lobby server:** waiting halls, create/join UI, matchmaking, cosmetics hub.
- **Game servers:** real `PartyInstance` sessions (board + minigames).
- Each game server hosts many instances at once via AdvancedSlimePaper.

---

## 4. Plugin design (core types)

### Main classes

| Class | Role | Notes |
|-------|------|--------|
| `PartyManager` | All instances, create/join/start/end, orchestration | Center of the system |
| `PartyInstance` | One match (one board) | Players, state, coins, stars, board logic |
| `Board` / board package | Movement, spaces, items, shop | Coin shop inspired by Cytooxien |
| `MinigameManager` | Select and run minigames | Random + specific id; cancel active safely |
| `Minigame` (interface) | SPI for every minigame | `start` / `cancel` → `MinigameResult` |
| `DatabaseManager` | MySQL | Long-term stats (planned) |
| `RedisManager` | Redis | Fast runtime state (planned) |

### `PartyInstance` state machine

```
WAITING → STARTING → PLAYING → ENDING → CLEANUP
```

| State | Meaning |
|-------|---------|
| `WAITING` | Lobby in memory; world optional |
| `STARTING` | Slot claimed; world load + countdown |
| `PLAYING` | Board turns + minigames |
| `ENDING` | Podium / final scores |
| `CLEANUP` | Sessions cleared, world unloaded, instance removed |

---

## 5. Main gameplay flow

### 5.1. Create & join

1. Player is in lobby → create room or join.
2. `PartyManager` finds a suitable waiting instance (later: Redis-backed).
3. If none → create `PartyInstance` → load/claim board world (ASP when enabled).
4. Player is bound to the instance session.

### 5.2. During a match (board)

1. Players take turns rolling dice and moving on the board path.
2. After everyone has acted for the round → `MinigameManager` picks a minigame and runs it.
3. Minigame ends → placements and coin rewards apply back on the instance.
4. Repeat until max turns are done or someone finishes the board (product rules may evolve).

### 5.3. Match end

1. Compute final ranking (stars/coins/board position — exact formula TBD).
2. Persist results to MySQL (when online).
3. Destroy instance → unload slime world → release board slot.

---

## 6. Minigame system

### Design rules

- Implement every game as `Minigame` (`id()`, `start(context, done)`, `cancel()`).
- `MinigameManager` owns selection, active instance, and force-cancel on party end.
- Average length: **45–90 seconds** (board interludes should stay snappy; Cytooxien averages ~2 minutes — we aim slightly shorter early on).
- Always return **placement 1…n** and **coins** via `MinigameResult` on the main thread.
- `cancel()` must restore blocks, inventory, gamemode, and listeners safely.
- Prefer shared engines (elimination, race finish, score, block restore) over one-off logic.
- Later: Cytooxien-style **rotation** and optional **player vote** for the next game.

### Format mix (launch)

| Format | Count (of 8) | Purpose |
|--------|--------------|---------|
| Free-for-all | 8 | Validate SPI and feel first |
| 2v2 / 1v3 / duel | 0 at launch | Add after FFA pool is solid |

Long-term target still matches the original plan: ~4–5 FFA, 2–3 2v2, 1–2 1v3, 1–2 duels once team assignment exists.

### Shared building blocks

| Utility | Used by |
|---------|---------|
| Elimination / death order ranking | Hot Potato, Spleef, Musical Chairs, Color Chaos, Floor is Lava |
| Block snapshot + restore | Spleef, Floor is Lava, Color Chaos |
| Finish-line / checkpoint race | Red Light Green Light, Race |
| Score accumulator | Laser Tag |
| Arena spawns + inventory reset | All eight |

---

### 6.1. Launch minigames (8)

Inspired by Cytooxien Minecraft Party and classic Mario Party–style Minecraft maps. All are free-for-all unless noted.

#### 1. Hot Potato — `hot_potato`

| Field | Detail |
|-------|--------|
| **Feel** | Pure Mario Party chaos |
| **Goal** | Do not hold the potato when the timer ends |
| **Rules** | One or more players start with a hot potato item. Hitting another player passes it. When the round timer expires, holders explode and are eliminated (or place last). Multiple pass cycles until ranking is complete, or a single timed round with order-out scoring. |
| **Win / rank** | Survival order or fewest potato explosions |
| **Complexity** | Low |
| **Needs** | Item pass on hit, explosion VFX, short timer |

#### 2. Spleef — `spleef`

| Field | Detail |
|-------|--------|
| **Feel** | Classic Minecraft party staple |
| **Goal** | Be the last player standing on the platform |
| **Rules** | Players get shovels (or similar). Breaking floor blocks drops others into the void/water. Optional simple power-ups later (TNT arrow, knockback). |
| **Win / rank** | Last alive = 1st; others by elimination order |
| **Complexity** | Low–medium |
| **Needs** | Flat arena, **block restore** on end/cancel |

#### 3. Musical Chairs — `musical_chairs`

| Field | Detail |
|-------|--------|
| **Feel** | Reaction / elimination rounds |
| **Goal** | Claim a seat when the music stops |
| **Rules** | While “music” plays, players move freely (optional coin rain). When it stops, everyone must stand on / sit in a free chair. Always **one fewer chair than living players**. Failures are eliminated each round until a winner remains. |
| **Win / rank** | Last seated / elimination order |
| **Complexity** | Low–medium |
| **Needs** | Chair entities or marked blocks, round loop, music cue (sound/title) |

#### 4. Color Chaos — `color_chaos`

| Field | Detail |
|-------|--------|
| **Feel** | Fast reaction floor game (Cytooxien Color Chaos) |
| **Goal** | Stand on the announced color before other blocks vanish |
| **Rules** | Arena floor is multi-colored. Title/boss bar shows a color; after a short delay, non-matching blocks disappear. Intervals get shorter. Optional power-ups later (paint bomb, glider). |
| **Win / rank** | Last standing, or farthest round survived |
| **Complexity** | Medium |
| **Needs** | Color grid, timed vanish, restore floor |

#### 5. Red Light, Green Light — `red_light`

| Field | Detail |
|-------|--------|
| **Feel** | Race + freeze discipline |
| **Goal** | Reach the finish line first |
| **Rules** | Green = may move. Red = must stop. Movement (or position delta) on red sends the player backward a fixed penalty. First to the far line wins. |
| **Win / rank** | Finish order; unfinished players ranked by distance |
| **Complexity** | Low–medium |
| **Needs** | Straight lane arena, move detection while red, finish region |

#### 6. Floor is Lava — `floor_is_lava`

| Field | Detail |
|-------|--------|
| **Feel** | Path-cutting survival (Cytooxien Floor is Lava) |
| **Goal** | Stay up as long as possible |
| **Rules** | Blocks under a player vanish shortly after being stepped on. Players can try to cut others off. Falling eliminates. |
| **Win / rank** | Last standing / survival time |
| **Complexity** | Medium |
| **Needs** | Same **block restore** engine as Spleef; trail vanish scheduler |

#### 7. Race — `race`

| Field | Detail |
|-------|--------|
| **Feel** | Sprint course between board rounds |
| **Goal** | First to the finish |
| **Rules** | Short foot-race track with optional coin pickups (speed boost or bonus coins), trampolines/fans as map props later. No horses/elytra in v1. |
| **Win / rank** | Finish order |
| **Complexity** | Medium (map-dependent) |
| **Needs** | Track + start gates + finish line; optional checkpoint coins |

#### 8. Laser Tag — `laser_tag`

| Field | Detail |
|-------|--------|
| **Feel** | Score FFA shooter (Cytooxien Laser Tag) |
| **Goal** | Highest hit score when time ends |
| **Rules** | Players get a “blaster” (snowball, crossbow, or custom projectile). Hits award points; killstreaks can grant bonus coins. Short reload after miss, faster after hit (optional). Melee punch as close-range fallback. |
| **Win / rank** | Score descending; ties by last hit time |
| **Complexity** | Medium |
| **Needs** | Small arena with cover, projectile hit tracking, scoreboard |

---

### 6.2. Implementation order

```text
1. Minigame registry + random pick (Dummy remains fallback)
2. hot_potato
3. spleef (+ block snapshot/restore util)
4. musical_chairs
5. red_light
6. color_chaos
7. floor_is_lava (reuses spleef restore)
8. race
9. laser_tag
```

### 6.3. Deferred (not in the first 8)

| Idea | Why later |
|------|-----------|
| Parkour (dedicated long course) | Map content cost; Race covers “first to finish” first |
| One in the Chamber / Lucky Towers | Combat balance + random items |
| Shooting Range | Mob targets; good Wave 2 score game |
| Horse Race / Elytra Race | Entity and map polish |
| Skywars / Survival Games / Walls | Loot economy, border, longer rounds |
| Memorize / Too Many Items | Heavy content or worldgen |
| 2v2 / 1v3 / Duels | Needs team assignment + result rules |
| Shared power-up meta | After the FFA eight feel good |

---

## 7. Database & storage strategy

### MySQL (persistent)

- `players` — identity / last seen
- `player_stats` — wins, coins earned, stars, playtime
- `minigame_history` — per-game placements for balance tuning

### Redis (runtime — important when multi-server)

- `party:instance:{id}` → instance state blob
- `party:player:{uuid}:current` → current instance id
- `party:queue` → matchmaking queue

**Why Redis:** fast with many instances; easy sync when splitting across game servers.

---

## 8. World & instance management

- Each `PartyInstance` uses its own board world (ASP clone of a template when slime is enabled).
- Load world when the match starts (or on create, if product requires early show).
- Unload as soon as the instance cleans up; never leave players in a world about to unload.
- Cap active instances per game server by RAM.

**Initial recommendation (64GB game server):** about **15–18** concurrent party instances.

**Minigame arenas (later):** dedicated small maps or pads per minigame id, either:

- regions inside the board world, or  
- separate slime templates loaded only for the minigame phase  

Wave-1 games can start on a **shared arena region** so the SPI and ranking path ship before full multi-map rotation.

---

## 9. Matchmaking (dynamic countdown)

| Queue size | Countdown |
|------------|-----------|
| >10 players waiting | 60 seconds |
| >20 | 30 seconds |
| Full / ~40 (or max room size) | 10 seconds, then start |

**Goal:** cut wait time while keeping each instance small (4–10 players) for Mario Party feel.

---

## 10. Scaling roadmap

| Phase | Players | Game servers | Instances / server | Notes |
|-------|---------|--------------|--------------------|--------|
| Phase 1 | 100–150 | 1 | 10–12 | Stabilize core loop |
| Phase 2 | 300–400 | 2–3 | 12–18 | Original capacity goal |
| Phase 3 | 500+ | 4+ | 15–20 | Horizontal scale + Redis sync |

---

## 11. Important decisions

- Prefer **small instances** (4–8 players) over huge lobbies in one board.
- Use **AdvancedSlimePaper** for many worlds without permanent world folders per match.
- Combine **board movement + coin shop** (Cytooxien-inspired), not stars-only.
- Launch with **8 solid minigames**, then grow to 10–12 — quality over count.
- Design Redis/MySQL/Velocity in from the start; implement when single-server limits bite.
- Architecture long-term: **lobby + multiple game servers**.
- Minigames pay **placement coins always**; optional in-round bonus coins later (hits, pickups, risk).

---

## 12. Development roadmap

**Phase 1 — foundation (current direction)**

- `PartyInstance` + `PartyManager` + board dice turns
- ASP board load/unload
- Minigame SPI + Dummy
- Ship first minigames: Hot Potato, Spleef, Musical Chairs, Red Light

**Phase 2 — full launch eight + economy**

- Complete all 8 launch minigames
- Random minigame picker (replace hard-coded dummy only)
- Coin rewards + early board shop hooks
- Create/join GUI polish

**Phase 3 — multi-server**

- Split lobby vs game server
- Velocity proxy
- Redis-backed sessions and queue

**Phase 4 — product depth**

- Minigame & map rotation (Cytooxien-style)
- More boards
- Teams / duels minigames
- Ranks, cosmetics, detailed stats

---

## 13. Quick reference — launch minigame ids

| Id | Display name | Engine |
|----|--------------|--------|
| `hot_potato` | Hot Potato | Pass + eliminate |
| `spleef` | Spleef | Floor break + last standing |
| `musical_chairs` | Musical Chairs | Seat claim rounds |
| `color_chaos` | Color Chaos | Color floor vanish |
| `red_light` | Red Light, Green Light | Freeze race |
| `floor_is_lava` | Floor is Lava | Trail vanish + last standing |
| `race` | Race | Finish-line race |
| `laser_tag` | Laser Tag | Score FFA |

---

This document is the product/system north star for McParty. Implementation details for the current single-server plugin live in `AGENTS.md`. Update both when architecture or the launch minigame list changes in a lasting way.
