# McParty — Minigame Design Spec

**Version:** 2.1
**Updated:** 2026-10-11
**Scope:** Launch set of **12 free-for-all** minigames played between board dice rounds
**Parent docs:** `mcparty.md` (product vision), `AGENTS.md` (plugin architecture, SPI wiring)
**Supersedes:** v1.0 (`minigames.md`, 2026-08-29). Minigame ids are kept.
**Baseline:** the shipped code, not the v1 design. `hot_potato`, `spleef` and `elytra_race` already run (§0.3); their sections describe v2 changes on top of that code.

---

## 0. Research basis and freshness

This revision folds a review of Cytooxien's live Minecraft Party mode into the v1 plan. The research was done on 2026-10-11.

| Item | Value |
|------|-------|
| Latest official Minecraft Party update | **2026-03-07** ("Minecraft Party Gets Even More Chaotic"). The Cytooxien blog has no newer Party post through its 2026-08-02 entry. |
| Help pages last edited | 2026-04-15 |
| Newest footage reviewed (frames + transcript) | EnderKnight_MC live (≈2026-10-07), c0by (2026-08-07) |
| Older footage used for mechanics | Marvelino series (2025-09, 2025-11), Reddoons (2025), Grapfen tips (2023), EinfachGustaf event (2023) |

**Evidence tags used below:**

- **[O]** — official Cytooxien text (help page or update post).
- **[V]** — seen on screen or heard in gameplay footage.
- **[I]** — inference from [O]/[V].
- **[D]** — McParty design decision (ours, not Cytooxien's).

Values Cytooxien does not publish (fuse lengths, point tables, timings) are always [D]. They are tuning defaults, not facts.

### 0.1 Cytooxien status of the twelve (as of 2026-10-11)

"In rotation" means added or returned, and not listed as removed in any later post. Cytooxien does not publish the full public list.

| McParty id | Cytooxien counterpart (DE name) | Public rotation | Known current maps | Notable mechanics |
|------------|----------------------------------|-----------------|--------------------|-------------------|
| `hot_potato` | Hot Potato (Heiße Kartoffel) | In | Pier (new 2026-03); Garden removed | Time Freeze + Speed Boots power-ups; explosion drops coins |
| `speed_race` | Race (Wettrennen) | In | Ashstone Peaks (new 2026-03), Winter | Coins give speed boosts; mushroom trampolines; fans |
| `spleef` | Spleef | In (multi-level shovel variant since 2025-01) | Snowy mountain valley [V] | Creeper Arrows, Boxing Glove, TNT Arrow, Wind Charge, Bomb |
| `elytra_race` | Elytra Race (Elytrarennen) | In [I] | Shards (2025-05), canyon/cave [V] | Ring coins scale with center accuracy |
| `shooting_range` | Shooting Range (Schießstand) | In | Trial Chamber (2025-05), Farm (2026-03); Jungle removed | Golden glowing monsters give coins; points pop on hit |
| `color_chaos` | Color Chaos (Buntes Chaos) | In | Town plaza [V 2026-10] | Visual overhaul + pattern system (2026-03); Chicken Glider, Paint Bomb |
| `laser_tag` | Laser Tag (Lasertag) | In | Neon, Port, Factory | Streak coins; faster recharge after a hit; melee tag |
| `king_of_the_hill` | King of the Hill (Kletterkönig; 2023 name "Gipfelstürmer") | **Removed 2026-03** (private rounds only) | Frosty (2025-01) | Block-placing climb; anti-trap fix |
| `hopper` | Block Jump (Blockhüpfer) | In since 2025-05 [I] | City arena [V 2025-09] | Checkpoints delay the next jump; remaining jumps shown |
| `mini_skywars` | Mini Skywars / Skywars | In | Wild West (2026-03), Oasis (2025-05) | Instant kill coins; height cap; shrinking border; center enchanting table |
| `warden_escape` | none | — | — | Borrowed from Dragon Escape (Drachenflucht) and Nether Escape (Höllenschacht, 2026-03) |
| `antwar` | Mine Battle (Minengefecht) | In [I] | Solid underground stone [V] | Iron → gear, coal → explosives, gold → coins; players revealed after a hidden phase |

### 0.2 What changed from v1

1. **Standard match shell.** Every game now has the same intro, HUD and end screen (§3), modelled on what Cytooxien shows [V].
2. **Coins for everyone.** Placement coins go to every rank, not only the top 3. On top of that, games award in-game coin pickups, kill coins and accuracy coins [O]. These are folded into the existing `MinigameResult` coin map; no contract change is needed (§7).
3. **Shared power-up pickup.** Every game uses the existing `PowerUpBox` mystery box: touch only, projectiles never collect it, and a player may hold several power-ups at once [O 2026-03] (§8).
4. **Race kit convention.** In race games, slot 1 holds a reset-to-checkpoint item and slot 9 a player-visibility toggle. Racers are invisible, and finished players become invisible spectators [V].
5. **Per-game corrections from footage.**
   - Shooting Range is a shared gallery.
   - Block Jump gives each player a personal coloured block chain with an `n/24` hologram.
   - Antwar uses Mine Battle's hidden-then-revealed phase.
   - Hot Potato can run several potatoes at once.
6. **Length.** Cytooxien games average about 2 minutes [O]. McParty keeps 45–90 s defaults. The one exception is `antwar` at 120 s, because digging, fortifying and raiding do not fit in 90 s.

### 0.3 Implementation baseline (code as of 2026-10-11)

| Area | In code today |
|------|---------------|
| SPI | `Minigame` definitions create a fresh `MinigameSession` per run (§2) |
| Runner | One `MinigameRunner` per party: reveal roulette, arena clone load/unload, board ↔ arena teleports through `ArenaTransitions` |
| Match engine | `MatchScope`, `MinigameEventBus` + `MatchListener`, `PlayerStateSnapshot`, `EliminationTracker`, `PowerUpBox` (§4) |
| `hot_potato` | Runs. One potato, passed by throwing it (right-click or the drop key) **or** by a melee hit; a missed throw returns it to the thrower. Same fuse every cycle; no timeout, power-ups or coin drops. |
| `spleef` | Runs. Three baked snow layers, charged creepers, mystery-box power-ups (TNT, wind charge, snowballs, Sugar Rush, Creeper Surprise), 120 s timeout |
| `elytra_race` | Runs. Courses in `elytra-courses.yml` built with `/partyadmin elytra ...`, ordered rings, flat center bonus; a player who stops gliding is out |
| Other nine | Not implemented |
| Selection | Five `DummyMinigame` roulette entries sit in the registry next to the real games, and `pickRandom()` is uniform over all of them (§9) |

---

## 1. Goals

- Replace the dummy timer with real short games that always return **placement 1…n** and **coins**.
- Keep rounds **45–90 seconds** (antwar 120 s) so the board loop stays snappy.
- Prefer **shared engines** over one-off code per game.
- Stay correct under `cancel()` (party end, disconnect, plugin disable).
- Optimize for **4–8 players** on an ASP board/arena; every map must still work at 2 and at 12.

### Design principles

1. **Match-scoped everything.** Listeners and tasks only apply to players in the active match.
2. **Main-thread results.** `MinigameResult` is applied on the server thread.
3. **Real physics where standing or falling matters.** This covers Spleef, Color Chaos, Block Jump and floating-island courses.
4. **Packets and displays only for visuals and UI.** Use them for rings, holograms, outlines and tracers, never for collision.
5. **Restore or discard.** Every game runs on a disposable arena clone, so cleanup is an unload. `BlockChangeJournal` exists only for the rare game run on a shared pad.
6. **Readable at a glance.** Anyone who has played Mario Party should understand the goal from the intro title plus three chat lines.

---

## 2. Integration with the party loop

```text
Board round complete (all hops done)
        │
        ▼
BoardTurnController → MinigameRunner.runRandom()      one runner per party
        │  cancel() the previous run, registry.pickRandom()
        │
        ├─ REVEAL   roulette title on the board (≈ 8 s)
        │           arena clone loads in parallel (loadCloneAsync)
        ▼
both ready, and the party is still PLAYING
        │
        ▼
ArenaTransitions.enter → PartyManager teleports everyone to the arena spawn
        │
        ▼
definition.createSession().start(context, done)
        │
        ├─ PREPARE  MatchScope.open (snapshot + clear), place players, give kit, attach HUD
        ├─ INTRO    5 s freeze: title countdown, rules in chat
        ├─ LIVE     game logic (phases per game)
        ├─ END      3 s: personal placement title, top-3 chat summary, coin line
        │
        ▼
scope.finish(result) → tasks cancelled, tracked entities removed, players restored
        │
        ▼
runner: ArenaTransitions.exit (back to the board) → unload the clone → done.accept(result) next tick
        │
        ▼
BoardTurnController pays coins (×2 with Double Coins) and placement bonus dice → round cap check → next board round
```

| Contract | Detail |
|----------|--------|
| SPI | `Minigame` (registered definition): `id()`, `displayName()`, `arenaSpec()`, `createSession()`, which must return a fresh session. `MinigameSession`: `start(MinigameContext, Consumer<MinigameResult>)`, `cancel()` |
| Context | plugin, `MessageService`, `MinigameEventBus`, `PartyInstance` (`null` in admin test runs), online players, `Optional<MinigameArena>` (the clone's spawn + `SlotBoundary`) |
| Result | Placement map + coin map per UUID. The coin map holds the **total** (placement + in-game bonus). |
| Cancel | Safe in every phase. `cancel()` calls `scope.close()`, which restores players and never calls `done`; the runner then unloads the clone. A cancelled match awards **no coins** [D]. |

Board-side context: as at Cytooxien [O], 1st/2nd/3rd roll a gold (4–6), silver (2–5) or bronze (1–3) die on top of the standard die in the next round (`board/economy/BoardEconomy`, needs at least 2 placed players). Placement order therefore decides movement, not only coins; ties must be broken deliberately, never by party order.

---

## 3. Match framework (shared by all twelve)

### 3.1 Lifecycle phases

| Phase | Owner | Default length | Behaviour |
|-------|-------|----------------|-----------|
| `REVEAL` | `MinigameRunner` (exists) | ≈ 8 s | Roulette title while players are still on the board; the arena clone loads in parallel. |
| `PREPARE` | session | 1 tick (not shown) | `PartyManager` has already teleported everyone to the arena spawn. `MatchScope.open` snapshots and clears each player (§4); the game sets the gamemode, moves players to its own spawns, gives the kit and attaches the HUD. |
| `INTRO` | session | `intro-seconds: 5` | Players are frozen: position changes are cancelled, head rotation is allowed. The title counts down "Starts in N seconds" (the reveal already showed the name). A clock tick sounds at 3-2-1. Chat prints a 3-line rules box. The sidebar shows **Minigame / Map / Goal**. |
| `LIVE` | session | per game | Game logic. Per-game sub-phases (grace, cycles, rounds, border) are defined in §6. |
| `END` | session | `end-seconds: 3` | Inputs are frozen and eliminated players stay spectators. Each player sees a title "#N" with a subtitle of their metric. Chat shows the top 3 plus "You placed #N · +X coins". Then `scope.finish(result)` runs. |

**Timer rule:** every `timeout-seconds`, `duration-seconds` and `*-start-seconds` key is measured **from GO** (the end of INTRO). Timeline tables in §6 use the same clock: `GO+15 s` is 15 s after GO, and INTRO is listed before GO.

**Pre-game time:** REVEAL ≈ 8 s + INTRO 5 s + END 3 s ≈ 16 s per minigame. INTRO is 5 s, not v2.0's 10 s, because the reveal already announces the game. The three shipped games have no INTRO or END yet; they go LIVE as soon as the session starts.

What Cytooxien shows, for reference:

- Intro [V]: the title "Lasertag" with "Das Spiel startet in 7 Sekunden" ("the game starts in 7 seconds"), a rules box in chat, and a sidebar with MINISPIEL / MAP / REKORDE (minigame / map / records).
- End [V]: chat "#1 …, #2 A & B, #3 …", then "Du belegst Platz #8" ("you take place #8") and "Du hast 14 Münzen erhalten" ("you received 14 coins"), plus a floating "#8" hologram back on the board.

### 3.2 HUD standard

**Sidebar** (one `Scoreboard` per **player**, owned by `MatchHud`). Each viewer sees their own placement and highlighted row, which one shared match scoreboard cannot show. Lines are score entries with `Score#customName(Component)`, and the objective uses `NumberFormat.blank()`. The player's previous scoreboard is captured at PREPARE and restored when the scope closes or the player detaches. Teams live on these scoreboards too: the client predicts pushing from the teams on *its own* scoreboard, so the match collision team (§3.4) must be registered on every player's scoreboard.

Layout:

```text
 McParty
 MINIGAME
  Color Chaos
 TIME
  1:12
 PLACEMENT #3
  9  Alice
  9  Bob
  7  You
  5  Carol
  +3 players
```

- The left column holds the game's ranking metric:

  | Metric | Games |
  |--------|-------|
  | Rounds survived | Color Chaos |
  | Points | Shooting Range |
  | Tags | Laser Tag |
  | Jumps | Block Jump |
  | Distance to finish | Elytra Race, Speed Race |
  | Kills | Mini Skywars, Antwar |
  | Hill seconds | King of the Hill |
  | Checkpoint | Warden Escape |
  | none, alive list only | Hot Potato, Spleef |

- Show up to 5 rows plus a "+N players" row. The viewer's own row is always shown and highlighted.
- Eliminated players are shown struck through / grey.

**Boss bar:** the global timer or phase bar, with the colour per state (green → yellow → red as time runs out). Game-specific uses:

- Hot Potato: the fuse.
- Color Chaos: the colour call.
- King of the Hill: the leading capture.

**Action bar:** personal state only, such as cooldown, blocks left, noise meter or "+1 coin". Throttle it to at most 1 update every 4 ticks.

**Titles:** phase changes only (intro, "GO!", eliminated, finished, end). Never use them for repeating info.

**Chat:** the rules box, streak announcements, elimination feed (≤ 1 line per event) and the end summary.

**Text:** every player-facing string goes through `MessageService` and `messages.yml` (MiniMessage, Vietnamese, `[McParty]` prefix per `AGENTS.md`). English strings in this spec only describe the meaning.

### 3.3 Hotbar conventions

| Slot | Content |
|------|---------|
| 1 | Primary tool or weapon. In race games (`speed_race`, `elytra_race`): **Reset** item (green arrow), which teleports to the last checkpoint with a 3 s cooldown [V]. |
| 2–8 | Game items, then collected power-ups (fill the first free slot; the same type stacks) |
| 9 | Race games: **Player visibility** toggle, using `Player#hidePlayer/showPlayer` for all match players [V]. Other games: empty. |

Custom looks use the `item_model` component: `ItemMeta#setItemModel(new NamespacedKey("mcparty", "<name>"))` resolves to `resourcepack/assets/mcparty/items/<name>.json`. Write model ids as `mcparty:<name>`; `mcparty:item/<name>` would look for `items/item/<name>.json` and show a missing model.

Minigame item models already in the pack:

| Model | Use |
|-------|-----|
| `hot_potato`, `hot_potato_3d` | Potato in hand / thrown potato (code) |
| `snow_shovel`, `throwing_tnt`, `sugar_rush` | Spleef shovel, TNT, Sugar Rush (code) |
| `power_up_box`, `power_up_question` | `PowerUpBox` (code) |
| `elytra_ring` | Elytra ring displays (code) |
| `laser_gun` | Laser Tag blaster |
| `checkpoint_flag`, `jump_marker` | Block Jump flag and next-block arrow |
| `finish_flag` | Finish marker (races, Block Jump) |
| `speed_boost` | Speed Boots power-up |
| `noise_maker` | Warden Escape distraction throwable |
| `queen_core` | Antwar core marker |
| `target`, `crown` | Not assigned yet (candidates: Shooting Range, King of the Hill) |
| `claw_blade`, `musical_chair`, `tnt_multishot` | Not used by the launch twelve |

Still missing: `coin` (coin pickups, §3.6). Echo shards use the vanilla `ECHO_SHARD` item.

Each game tags its own items with a PDC key in the plugin namespace (Hot Potato: `mcparty:hot_potato`). Exception: Mini Skywars kit and loot items are **not** tagged, because they are meant to drop and be looted. Drop, swap-hand (except where the off-hand is part of the kit), inventory click, item frame interaction and death drops are cancelled for tagged items. Drops are routed today; swap-hand and inventory click need new hooks (§4).

### 3.4 Player state defaults

| Setting | Default | Exceptions |
|---------|---------|------------|
| Gamemode | `ADVENTURE`, set in PREPARE (`MatchScope.open` leaves players in `SURVIVAL`) | `SURVIVAL` + region checks for `spleef`, `king_of_the_hill`, `mini_skywars`, `antwar` |
| Hunger | Locked full (cancel `FoodLevelChangeEvent`; needs a bus hook, §4) | — |
| Fall damage | Off (`scope.protectFromDamage()` cancels all player damage) | `mini_skywars`, `antwar` |
| PvP damage | Off. Where pushing matters, apply a manual knockback: the bus cancels the hit, and a cancelled hit has no vanilla knockback. | `mini_skywars`, `antwar`: real PvP |
| Player collision | Match team with `collisionRule=NEVER`, registered on every player's scoreboard (§3.2) | `hot_potato`, `spleef`, `color_chaos`, `king_of_the_hill`: `ALWAYS` (pushing is part of play) |
| Name tags | Visible | `warden_escape`: hidden |
| Night Vision | Off | Cave or dark maps: `laser_tag`, `antwar`, `elytra_race` cave sections |

Optional: Cytooxien now gives every player a helmet in every minigame, for cosmetics plus armor [O]. McParty keeps this off (`cosmetic-helmet: false`) until cosmetics exist.

### 3.5 Eliminated and finished players

- **Eliminated:**
  - `scope.spectate(id)` clears the inventory and switches to `SPECTATOR` immediately (exists).
  - Spectators stay inside the arena with no extra code: `PartyManager` makes the arena the party's active play area, and `BoundaryListener` clamps every move inside its boundary.
  - Show the title "Eliminated" with subtitle "#N".
  - Spectators keep the sidebar.
- **Finished (races):** Cytooxien gives finished racers Invisibility and Night Vision and lets them keep watching [V]. McParty switches them to `SPECTATOR` after a 2 s celebration (fireworks particle burst, title "#N · 0:44.586").
- Spectators cannot interact with entities, blocks or power-ups. There is no `MatchScope.isActive(player)`: each game checks its own state (`EliminationTracker.isAlive`, a race's active set), and `PowerUpBox` already skips spectators and dead players.

### 3.6 Coins inside a minigame

- **Placement coins:** `minigame.dummy-coin-rewards`, the shared table today despite its name. Ranks beyond the table get **1 coin**, as `EliminationTracker`, `DummyMinigame` and `ElytraMinigame` do. Every participant gets placement coins.
- **Bonus coins:** game-specific (pickups, kills, streaks, ring accuracy, golden targets). Every game that pays bonus coins caps them. The key is `bonus-coin-cap`, except where a v1 name already exists: `laser_tag.streak-bonus-cap`, `mini_skywars.kill-bonus-cap`, `king_of_the_hill.hill-coins.cap`. Games without bonus coins define no cap.
- Coins are accumulated in the match and shown instantly in the HUD ("+1 ⛁" in the action bar, a pickup sound), but they are **credited once** at `done` as part of the result.
- **Coin pickup:** a non-persistent `ItemDisplay` with item model `mcparty:coin` (new model, §3.3), spinning 6°/tick and bobbing ±0.15. Pickup uses the same AABB-overlap test as `PowerUpBox`, every 2 ticks. Never use a real `Item` entity: they merge, get picked up by hoppers and ignore our scope.
- **Reference [V]:** in the 2026-10 stream, the 7th and 8th places received 15 and 14 coins (placement + pickups). Cytooxien shop prices are 30 / 40 / 100 [O]. McParty uses the same prices and the 8-entry table in §7.

### 3.7 Ranking and ties

- Each game defines an ordered comparator (listed under "Ranking" in §6). The result always assigns **unique** placements 1…n, as the v1 contract requires.
- Cytooxien displays shared ranks ("#2 A & B") [V]. McParty can show ties in chat when the comparator's primary keys are equal (`shared-placement-display: true`). Result placements stay unique, using the tie-breakers.
- **Leavers** (one rule for all games):
  - A player who disconnects during INTRO or LIVE is ranked below every player still connected at resolve time.
  - Among leavers, games with a score metric (points, tags, jumps, checkpoint) rank by that metric frozen at leave time. Pure elimination games rank leavers by leave order: a later leave ranks higher.
  - Leaving **after** being eliminated or finishing does not change the placement already earned.
  - Today `MatchScope.detach` (quit or party leave) restores the player and calls `onQuit`, and each game eliminates or drops them at that moment. The rules above need a leaver list in the trackers.

### 3.8 Records (deferred)

Cytooxien shows per-minigame/per-map records in the intro sidebar and chat ("new record: 44s 586ms") [V]. This is deferred until MySQL. The hook is an optional `MinigameResult` record entry (best time or score per map), which a later stats service will consume.

---

## 4. Shared building blocks

Implement these once under `minigame/` (new names are indicative). Status is checked against the code as of 2026-10-11.

| Component | Status | Responsibility | Used by |
|-----------|--------|----------------|---------|
| `MinigameRegistry` | Exists | id → definition; `pickRandom()` (uniform, dummies included, §9); dummy fallback | Runner |
| `MinigameRunner` | Exists | One per party: reveal, arena clone load/unload, `ArenaTransitions`, one live session | All |
| `MatchScope` | Exists | Roster in party order, snapshots, `repeating`/`later` tasks, `onClose`, `track(entity)`, `protectFromDamage()`, `spectate`, `detach`, one-shot `finish`/`close` | All |
| `MinigameEventBus` + `MatchListener` | Exists, extend | The only Bukkit listener; routes events by player, and explosions by tracked entity. Missing hooks are listed below. | All |
| `PlayerStateSnapshot` | Exists, extend | Captures inventory, armor, off-hand, gamemode, health, food, XP, walk/fly speed, flight and effects. **Add:** max-health attribute (Antwar hearts), glowing, hidden players (race visibility toggle) and the player's scoreboard (§3.2). | All |
| `EliminationTracker` | Exists | Elimination order → placements + coins; ranks beyond the coin table get 1 coin | `hot_potato`, `spleef`, `color_chaos`, `mini_skywars`, `warden_escape`, `antwar` |
| `PowerUpBox` | Exists, extend | Spinning mystery box, AABB touch, optional respawn, dies with the scope. **Add:** a shared weighted roll + hotbar insert (§8). | `hot_potato`, `spleef`, `color_chaos`, `shooting_range`, `laser_tag` (optional) |
| `SlotBoundary` / `PartyPlayArea` | Exists | Arena AABB (`isInside`, `clampInside`) + spawn. Small zones (hill, pads) use Bukkit `BoundingBox`. | All arenas |
| `ScoreTracker` | New | UUID → score + secondary stats; rank by comparator | `shooting_range`, `laser_tag`, `king_of_the_hill`, `antwar` |
| `CheckpointTracker` | New | Ordered planes or regions, last safe pose, finish time. Plane crossing reuses the segment–plane math in `ElytraRing.collision`. | `speed_race`, `elytra_race`, `king_of_the_hill`, `hopper`, `warden_escape` |
| `ArenaSpawns` | New | Spawn list per arena; fair assignment (shuffle, then spread) | All |
| `MatchHud` | New | Per-player sidebar + match team (§3.2), boss bar, action-bar throttling | All |
| `IntroSequence` | New | Freeze, title countdown, rules box, GO sound/title | All |
| `CoinPickupService` | New | Coin displays, touch pickup, per-player bonus tally with cap | `hot_potato`, `speed_race`, `elytra_race`, `warden_escape`, `antwar` |
| `RaceKit` | New | Reset item + visibility toggle + finish-to-spectator flow | `speed_race`, `elytra_race` (and `hopper`'s reset) |
| `TrackProgress` | New | Remaining distance along a checkpoint polyline, for live race placement | `speed_race`, `elytra_race`, `warden_escape` |

Dropped from v1: `BlockChangeJournal` (every game runs on a disposable clone) and `SpectatorUtil` (`scope.spectate` + `BoundaryListener` already cover it).

Rule of thumb: a component is shared only when at least two games use it. Logic used by a single game (the Hot Potato fuse, the Color Chaos pattern generator, the Antwar ore table) lives in that game's package.

### Event hooks to add

The bus routes ten events today: interact, item drop, block break, item pickup, projectile hit (by shooter), damage (protection only), damage dealt by a match player, move, explosion of a tracked entity, and quit. Each need below is a `MatchListener` hook **plus** a bus handler (`AGENTS.md`); sessions never call `registerEvents`.

| Hook | Route by | Needed by |
|------|----------|-----------|
| `BlockPlaceEvent` | player | `king_of_the_hill`, `mini_skywars`, `antwar` (TNT) |
| `EntityDamageEvent` on a match player, any cause | victim | `mini_skywars`, `antwar` (lethal interception), `warden_escape` (Warden hits) |
| `EntityToggleGlideEvent` | player | `elytra_race` |
| `FoodLevelChangeEvent` | player | All (hunger lock, §3.4) |
| `InventoryClickEvent`, `PlayerSwapHandItemsEvent` | player | All (tagged items, §3.3) |
| `BlockDamageEvent` | player | `antwar` (core punches) |
| `PlayerToggleSprintEvent`, Paper `PlayerJumpEvent` | player | `warden_escape` (noise) |
| `BlockReceiveGameEvent` | player (event entity) | `warden_escape` (sculk sensors) |
| `EntityTargetEvent`, `EntityCombustEvent`, damage to match mobs | tracked entity | `spleef` creepers, `shooting_range`, `warden_escape` |
| Paper `WardenAngerChangeEvent` | tracked entity | `warden_escape` |
| `BlockSpreadEvent`, `BlockIgniteEvent` | arena world | `mini_skywars` (lava, fire) |

`byEntity` routing serves explosions only today, and it forgets an entity once it explodes. Mob hooks need the mapping for the entity's whole life (`track` already removes the entity when the scope closes). World routing needs a new arena world → scope map.

### Arena strategy

- **Every game runs on a disposable per-party arena clone:** `MinigameArenaSpec` → `loadCloneAsync` during REVEAL → play → unload. Hot Potato, Spleef and Elytra Race already work this way, so it is the default for all twelve.
- Arena config pattern (as shipped for `hot_potato` and `spleef`):

  ```yaml
  arena:
    template: <slime template name>
    spawn: {x, y, z, yaw, pitch}
    boundary: {minX, minY, minZ, maxX, maxY, maxZ}
  ```

  A missing or invalid arena disables only that game at enable or reload, as it does for Hot Potato and Spleef.
- **Markers** (rings, checkpoints, pads, targets, cores) live in one YAML store per game, written by that game's setup commands. `ElytraCourseStore` (`elytra-courses.yml`) is the pattern: template-relative coordinates, the setup world's name and the same `arena` block. A clone keeps the template's coordinates, so loading only swaps the world (like `BoardSlot.forWorld`); nothing is remapped.
- **Generated arenas** follow `/partyadmin spleef generate`: `SlimeWorldService.generateTemplate(name, painter)` paints an empty world once and saves it as the template. This fits the Color Chaos floor, the Block Jump arena and the Antwar mine.
- Per-template `min-players` / `max-players` variants (for example a 24×24 Color Chaos floor for 2–4 players) are **not implemented**; they come with Phase B selection (§9).

### Packet and display policy

| Use displays / packets | Use the real world |
|------------------------|-------------------|
| Elytra rings, holograms (`n/24`, "+3"), coin and power-up pickups, tracer particles, hill capture FX, outlines | Floors that can vanish (Spleef, Color Chaos), Block Jump blocks, bridges, ores, cores, all collision |
| Per-player visibility (`Entity#setVisibleByDefault(false)` + `Player#showEntity`) | Damage, projectiles, block break/place |

Collision is always server-authoritative.

### Optimization defaults

These are unchanged from v1:

- Block writes use `setBlockData(data, false)`, with no physics, and only when the material actually changes.
- `PlayerMoveEvent` ignores same-block moves except where a plane crossing is tested (Elytra, Speed Race). Use block-coordinate logic.
- Repeating tasks run every 2–5 ticks, not every tick, unless the game needs per-tick (the Hot Potato fuse bar is fine at 2 ticks).
- Clear projectiles, items and match-owned entities before unload.
- Entity spawns use `World#spawn(loc, Class, consumer)` so the entity is configured (AI, persistence, tags) before it is added to the world.

---

## 5. Launch minigames overview

| # | Id | Display name | Default length | Players (ideal / min–max) | Core loop | Ranking | Build difficulty |
|---|----|--------------|----------------|---------------------------|-----------|---------|------------------|
| 1 | `hot_potato` | Hot Potato | 45–75 s | 6–8 / 2–12 | Chase, pass by hit, survive fuse cycles | Elimination order | Low |
| 2 | `speed_race` | Speed Race | 45–75 s | 4–8 / 2–12 | Sprint course, coin boosts, shortcuts | Finish time → progress | Low–med |
| 3 | `spleef` | Spleef | 60–90 s | 4–8 / 2–12 | Break floor under rivals across 3 layers | Elimination order | Low–med |
| 4 | `elytra_race` | Elytra Race | 45–75 s | 4–8 / 2–12 | Glide through ordered rings | Finish time → progress | Medium |
| 5 | `shooting_range` | Shooting Range | 60 s | 4–8 / 2–12 | Shared gallery, moving mob targets | Points | Medium |
| 6 | `color_chaos` | Color Chaos | 45–75 s | 4–10 / 2–12 | Find the called colour before the floor drops | Elimination order | Medium |
| 7 | `laser_tag` | Laser Tag | 60 s | 4–8 / 2–12 | Hitscan blaster FFA with respawns | Tags | Medium |
| 8 | `king_of_the_hill` | King of the Hill | 60–90 s (+≤ 10 s overtime) | 4–8 / 2–12 | Bridge up an island chain, hold the summit | Capture → hill time | Medium–high |
| 9 | `hopper` | Block Jump | 45–75 s | 4–8 / 2–12 | Personal block chain, checkpoint trade-off | Finish → jumps | Medium–high |
| 10 | `mini_skywars` | Mini Skywars | 90 s | 6–8 / 2–12 | Loot, bridge, fight, border | Last alive → kills | High |
| 11 | `warden_escape` | Warden Escape | 75–90 s | 4–8 / 2–12 | Stealth course, noise vs Wardens | Exit order → progress | High |
| 12 | `antwar` | Antwar | 120 s | 4–8 / 2–12 | Mine for gear, raid queen cores | Last queen → core HP | High |

**Status:** `hot_potato`, `spleef` and `elytra_race` run today in their v1 form (§0.3); the other nine are not implemented.

**Implementation order** (reuse-first): `hot_potato` → `speed_race` → `spleef` → `elytra_race` → `shooting_range` → `color_chaos` → `laser_tag` → `king_of_the_hill` → `hopper` → `mini_skywars` → `warden_escape` → `antwar`. Maps are the main cost: the `color_chaos`, `hopper` and `antwar` arenas can be generated in code, while course games need hand-built maps, so a game may move up when its map is ready first.

---

## 6. Per-minigame specs

Every game follows the same template:

- Summary
- Cytooxien reference
- Level design
- Kit & HUD
- Timeline
- Rules & activities
- Power-ups
- Ranking & coins
- Tech
- Edge cases
- Config
- Divergences

Config blocks show only that game's keys under `minigame.<id>`. Tags: `# code` means the key exists in the shipped `config.yml` (the current default is noted when v2 changes it); `# v1` means the key comes from the v1 design doc and is **not implemented**; untagged keys are new in v2. Timelines count from GO (§3.1).

---

### 6.1 Hot Potato — `hot_potato`

**Fantasy:** Someone is holding a fizzing potato. Throw it at a player or hit them to get rid of it, and don't be the one holding it when it blows.

| Field | Value |
|-------|-------|
| Length | 45–75 s (3–6 fuse cycles) |
| Foundations | `MatchScope`, `EliminationTracker`, `PowerUpBox`, `CoinPickupService`, `MatchHud` |
| Cytooxien analog | Heiße Kartoffel, in rotation; map Pier since 2026-03 |
| Code today | One potato; a throw or a melee hit passes it; the same `bomb-seconds` fuse every cycle; no timeout, power-ups, coin drops, glowing or holder speed |

#### Cytooxien reference

- [O] Random players get the potato; hitting others passes it; whoever holds it when time runs out is eliminated.
- [O 2025-05] Holders visibly hold an exploding potato, and each explosion drops coins for others to collect.
- [O 2026-03] Power-ups are Time Freeze (freezes the potato for 5 s) and Speed Boots. The Pier map was added.
- [V c0by 2026-08, Pier] A sandy harbour market square with palms, market stalls, barrel/crate stacks and two-storey houses. Players flee upstairs ("let's all hide upstairs").
- [V] The fuse is a bar above the hotbar. It turns blue with the text "ZEIT ANGEHALTEN" (time stopped) while Time Freeze runs. Speed Boots show as Speed II.
- [V Gustaf 2023, Reddoons] Several players can hold a potato at the same time. Hiding spots include upper floors and underground passages.

#### Level design

- **Footprint:** a playable area of 36×36 (2–8 players) or 44×44 (9–12), on two levels:
  - **Ground plaza** (Y0): open sand with a central fountain or ring obstacle of 8–10 block diameter. It creates a "juke loop": a loop players can run around to shake off chasers.
  - **Upper level** (Y0 + 4): balconies, rooftops and 2-wide bridges between houses. Reachable by **at least 4** staircases or ladders spread to all sides.
  - **Optional cellar** (Y0 − 4): a tunnel loop under the plaza with at least 2 entrances.
- **Camping rule:** every hiding spot (room, balcony, cellar) must have **at least 2 exits**. There are no dead ends. The longest straight chase line is ≤ 20 blocks.
- **Obstacles:** market stalls (2×3), barrel stacks (1–2 high), palm trees and crates, placed so loops of 8–14 block circumference exist all over the map.
- **Boundary:** decorative walls, water or harbour edge, backed by an invisible barrier shell. There is no void, and nobody dies from falling.
- **Markers:**
  - 12 spawns on the plaza edge facing the center.
  - 6–8 power-up spots, at least 3 of them on the upper level (risk/reward).
  - 1 center point for "new potato" FX.
- **Themes:** Pier (harbour market, current Cytooxien), Garden (hedge maze), Snow Village.

```text
   ┌────────────── 36 ──────────────┐
   │ H══H   stall  stall    H══H    │   H = house (stairs inside, balcony on top)
   │ ║  ║                   ║  ║    │   ═ = upper-level bridge
   │ H  H    ┌──────┐       H  H    │
   │         │ ◯◯◯ │  barrels       │   ◯ = fountain loop (juke loop)
   │ stall   │ ◯  ◯ │       stall   │
   │         └──────┘               │
   │ H══H  crates   palm    H══H    │   ▣ = cellar entrance (≥2)
   │ ▣                          ▣   │
   └────────────────────────────────┘
```

#### Kit & HUD

| Role | Kit / state |
|------|-------------|
| Holder | Potato item (`BAKED_POTATO`, model `mcparty:hot_potato`, PDC key `mcparty:hot_potato`) in slot 1 (code). Potato helmet display. `Glowing`. Speed I [D, borrowed from TNT Tag so holders can catch up]. Fizzing smoke particles every 4 ticks (code: flame + smoke every tick). |
| Runner | Empty hand; power-ups fill slots 2–8 |

- Boss bar is the fuse: red, shrinking. It turns blue with the title "TIME FROZEN" during Time Freeze.
- Holder action bar: seconds left (code). v2: "You have the potato — throw it or hit someone!"
- Sidebar: alive list plus the number of potatoes in play.

#### Timeline

| t | Event |
|---|-------|
| INTRO (5 s) | Frozen at the spawns |
| Cycle start | Assign `max(1, floor(alive / players-per-potato))` potatoes to random alive players (never the same player twice in a row if avoidable). Title to holders: "You have the potato!" |
| Fuse | Cycle 1 uses `bomb-seconds` (15 s). Each later cycle is `bomb-step-seconds` (2 s) shorter, down to `min-bomb-seconds` (8 s). |
| Boom | All holders explode together. |
| Breather | `breather-seconds` (2 s) with no potatoes. Coins dropped by the explosions can be collected. |
| Repeat | Until 1 player is alive, `max-cycles` is reached, or the hard cap `timeout-seconds` |

At 2 players alive there is always exactly 1 potato.

**Expected length** with a ratio of 3 players per potato:

| Players | Alive after each cycle | Cycles | Total (fuses + breathers) |
|---------|------------------------|--------|---------------------------|
| 4 | 4 → 3 → 2 → 1 | 3 | 15 + 13 + 11 + 2×2 ≈ 43 s |
| 8 | 8 → 6 → 4 → 3 → 2 → 1 | 5 | 15 + 13 + 11 + 9 + 8 + 4×2 ≈ 64 s |
| 12 | 12 → 8 → 6 → 4 → 3 → 2 → 1 | 6 | ≈ 74 s |

#### Rules & activities

1. **Passing** (code, two ways):
   - **Throw:** right-click or the drop key throws the potato as a snowball with the `mcparty:hot_potato_3d` model at `throw-velocity`. Hitting an alive non-holder passes it; hitting a block or missing returns it to the thrower. Nobody holds it while it flies, and each holder has at most one potato in the air.
   - **Melee:** an in-match holder melee-hits an alive non-holder, and the potato moves to the victim.
   - Damage is cancelled (`protectFromDamage`). v2 adds a light manual knockback (velocity 0.35 horizontal, 0.25 up), since a cancelled hit has none.
   - Pass FX: a sound plus a smoke trail between the two players.
2. **No instant tag-back.** A new holder cannot pass back to the player who just gave them the potato for `tagback-immunity-ticks` (20).
3. **Holder hits holder** (melee or throw): nothing passes; a thrown potato returns to its thrower (feedback sound).
4. **Explosion.**
   - Holders are eliminated. The explosion is **particles and sound only**; never call `createExplosion`, because it would damage runners.
   - Nearby runners within 3 blocks get a manual knockback (velocity away from the blast, 0.8 horizontal, 0.4 up) but no elimination.
   - Each exploded holder drops `explosion-coin-drops` (3) coin pickups that scatter 2–4 blocks [O].
5. **Stalling** is limited by the fuse itself. The upper level and cellar always have 2 exits, so holders can follow.
6. **Disconnect.**
   - A holder who disconnects passes the potato to a random alive non-holder. The fuse continues and nobody is eliminated.
   - The leaver is ranked as a leaver (§3.7).

#### Power-ups

| Power-up | Effect | Spawn |
|----------|--------|-------|
| Speed Boots | Speed II for 6 s, plus boot particles | Every `powerups.interval-seconds` (8 s), max `powerups.max-on-map` (3) |
| Time Freeze | Pauses the **global** fuse for 5 s. Not additive: it resets the freeze to 5 s. Usable by anyone. | Same pool, 35% weight |

Both are picked up by touch only. A player may hold several.

#### Ranking & coins

- **Ranking:** the last player alive is 1st, then reverse elimination order.
- **Same-cycle explosions** are tie-broken by lower total ticks spent holding, then more coins collected, then earlier spawn index.
- **Survivors at `max-cycles` or timeout** rank above every eliminated player. Among themselves they use the same tie-breakers.
- **Coins:** placement + 1 per collected explosion coin (cap `bonus-coin-cap`: 6).

#### Tech

| Area | Plan |
|------|------|
| State | `Set<UUID> holders` (code: one `currentHolder`), the potato in flight per holder, fuse task (2-tick period updates the boss bar), `cycle`, `frozenUntilTick`, `lastGiver` map, hold-tick counters |
| Events | `PlayerInteractEvent` / `PlayerDropItemEvent` (throw, power-ups), `ProjectileHitEvent` (potato snowball), `EntityDamageByEntityEvent` (melee pass; cancel + manual knockback), `EntityPickupItemEvent` (stray potato item), `onQuit`, inventory events cancelled |
| Items | PDC-tagged potato; the helmet display is an `ItemDisplay` riding the player or a helmet-slot item |
| Packets | None required; glowing uses `setGlowing` |
| World | Disposable clone, no block changes |

#### Edge cases & cancel

- Never pass to spectators or non-match players. Only the match's own PDC-tagged potato snowball passes by projectile; other projectiles are ignored.
- A potato snowball that vanishes without a hit event (despawn, unload) goes back to its thrower (code).
- If a holder disconnects and no alive non-holder exists to take the potato, end the cycle immediately with no explosion.
- `cancel()`:
  - Stop the fuse.
  - Remove potatoes, power-up displays, coin displays and the boss bar.
  - Restore snapshots and unload the clone.

#### Config

```yaml
minigame:
  hot_potato:
    arena: { template: hot_potato_arena, spawn: {...}, boundary: {...} }   # code (v2 map theme: Pier)
    slime-template: hot_potato_arena   # code — legacy fallback for arena.template
    bomb-seconds: 15            # code (default 20) — first fuse
    throw-velocity: 0.9         # code
    max-cycles: 8               # code (default 10; parsed but not used yet)
    bomb-step-seconds: 2
    min-bomb-seconds: 8
    breather-seconds: 2
    players-per-potato: 3
    tagback-immunity-ticks: 20
    holder-speed-amplifier: 0   # Speed I; -1 disables
    explosion-coin-drops: 3
    bonus-coin-cap: 6
    timeout-seconds: 90
    powerups:
      interval-seconds: 8
      max-on-map: 3
      weights: { speed_boots: 65, time_freeze: 35 }
```

#### Divergences

- The fuse lengths, potato ratio and holder Speed I are [D]; Cytooxien does not publish them.
- The code's `bomb-seconds: 20` is lowered to 15, with shrinking fuses and a ratio of 3 players per potato. Today an 8-player game runs 7 cycles × 20 s ≈ 140 s; v2 fits it in about 64 s (see the expected-length table).
- Cytooxien passes the potato only by hitting [O]. McParty keeps the code's throw as well [D].

---

### 6.2 Speed Race — `speed_race`

**Fantasy:** Sprint a downhill obstacle track: grab coins for bursts of speed, launch off mushroom trampolines and find the shortcuts.

| Field | Value |
|-------|-------|
| Length | 45–75 s (cut 20 s after the first finisher) |
| Foundations | `CheckpointTracker`, `TrackProgress`, `RaceKit`, `CoinPickupService`, `MatchHud` |
| Cytooxien analog | Wettrennen, in rotation; maps Ashstone Peaks (new 2026-03) and Winter |

#### Cytooxien reference

- [O] Coins on the track give speed boosts. Trampolines and fans launch players up. Since 2025-01, players no longer respawn close to the edge.
- [V c0by 2026-08, Ashstone Peaks]:
  - The chat rules say: be first, use coins for a speed boost, mushroom trampolines catapult you forward.
  - Racers have **Speed II ∞** and **Invisibility ∞**; name tags stay visible.
  - Hotbar: slot 1 green reset arrow, slot 9 visibility toggle.
  - Terrain: basalt and blackstone ridges over lava channels.
  - There is no clear live position display, and the streamer said "I don't know what place I'm in".
- [V] Racing advice from footage:
  - Fans throw players into the air.
  - Don't jump on flat ground, because it is slower.
  - Shortcuts are "everywhere"; one 74 s video is just a tour of all shortcuts.
- [V Gustaf 2023] Boost pads and honey-block sections. [V Reddoons] A downhill course with cobwebs to avoid.

#### Level design

- **Centerline length:** 350–450 blocks. At Speed II a sprint runs at roughly 7.5–8 blocks/s, so a clean run takes about 45–55 s; an average run is about 60 s.
- **Width:** 5–9 blocks, narrowing to 3 only in risky sections.
- **Start grid:** 12 slots (2 rows × 6, spaced 2 blocks apart) in a 13-wide start plaza that funnels into the track, behind a barrier gate removed at GO.
- **Checkpoints:** 10 planes, about one every 35–45 blocks. Each plane spans **both** the main route and every shortcut, so shortcuts cannot skip a checkpoint. Each checkpoint has a respawn pose ≥ 2 blocks from any edge, facing forward [O fix].
- **Section template** (in order, adapt per theme):

| # | Section | Length | Content | Skill tested |
|---|---------|--------|---------|--------------|
| 1 | Launch straight | 40 | Wide; a coin line on the racing line | Clean start |
| 2 | Trampoline gap | 30 | 3 mushroom pads (red mushroom block caps) launch forward over a lava gap | Pad aiming |
| 3 | Switchbacks | 75 | Downhill hairpins, ice patch on the outside line | Cornering |
| 4 | Fan tower | 30 | Updraft column lifts 6–10 blocks to a high route (short); the low route is 15 blocks longer | Route choice |
| 5 | Slow zone | 40 | Lanes of honey, cobweb or soul sand; one clean lane needs a 2-block jump | Precision |
| 6 | Parkour shortcut | 25 | 3–4 jumps over void; saves ~3 s; failure means respawn + penalty | Risk |
| 7 | Boost chain | 50 | 3 boost pads in a row (magma or custom block) on a narrow bridge | Line holding |
| 8 | Final sprint | 80 | Wide finish straight under an arch; last coins | — |

- **Shortcuts:** 2–3 per track. Each saves 2–4 s and is visibly riskier: narrower, over void, or needing exact jumps.
- **Coins:** 25–40 per track, in clusters of 3–5. Put most on the optimal line and a few on risky lines.
- **Hazards:** lava or void below the track. Touching lava or falling below the section's kill-Y triggers a respawn.

```text
START ═══(coins)═══╗
                   ║ trampolines ▲▲▲ over lava
            ╔══════╝
            ╚═╗ switchbacks (ice)
          ╔═══╝
   fan ⇡ ═╣ high route ═══════╗
          ╚═ low route ══════╗║
                       slow zone (honey|web|clean)
                 ╔═══════════╝
   shortcut ···· ╣ (parkour over void)
                 ╚═ boost » » » ══ FINISH ⛳
```

#### Kit & HUD

- Slot 1: Reset item. Slot 9: visibility toggle.
- Effects: Speed II ∞ and Invisibility ∞ with particles hidden [V].
- Collision rule `NEVER`; no fall damage.
- Sidebar metric: remaining distance (blocks) to the finish, from `TrackProgress`. Finished players show ⭐ and their time.
- Action bar: "Checkpoint 6/10 · 212 m".

#### Timeline

| t | Event |
|---|-------|
| INTRO (5 s) | On the grid; the gate is closed |
| GO | "GO!"; gate barriers set to air |
| live | Race |
| first finish | Start `finish-grace-seconds` (20 s). The race ends early when everyone has finished. |
| cap | `timeout-seconds` (75 s) |

#### Rules & activities

1. **Coin pickup.**
   - Speed III for `coin-boost.ticks` (30). The timer refreshes and does not stack.
   - Plus 1 bonus coin.
2. **Trampoline.**
   - Trigger: the block under the feet is a marked pad.
   - Effect: set velocity = pad direction × `trampoline.forward` (1.6), Y `trampoline.up` (0.9).
   - Each pad has a 10-tick re-trigger lock per player.
3. **Fan column.**
   - While inside the column AABB, **set** Y velocity to `fan.lift` (0.6) every 2 ticks (not additive), up to the column top.
   - Particles show the column.
4. **Boost pad:** Speed III for 20 ticks, refreshed.
5. **Fall or lava.**
   - Freeze 0.5 s, then teleport to the last checkpoint pose.
   - Add `reset-time-penalty-seconds` (2): the player cannot move during the penalty, which counts as part of their time.
6. **Reset item.** Same as a fall, including the penalty. Cooldown 3 s; useful when stuck.
7. **Checkpoints.**
   - Progress is valid only in order. Re-entering a checkpoint never advances twice (v1).
   - A finish only counts if all checkpoints were crossed.

#### Ranking & coins

- **Ranking:**
  - Finished players by time.
  - Unfinished players by checkpoint index (descending), then remaining distance (ascending), then fewer resets.
- **Coins:**
  - Placement.
  - +1 per coin pickup (`pickup-bonus-coins`).
  - +2 clean-run bonus for finishing with 0 resets.
  - Bonus cap 10.

#### Tech

| Area | Plan |
|------|------|
| Progress | `CheckpointTracker` with plane checkpoints. Crossing reuses the segment–plane math of `ElytraRing.collision` (code), on feet position. |
| Live rank | `TrackProgress`: polyline through checkpoint centers. Distance = player → next checkpoint center + the sum of the remaining segments. Recompute every 10 ticks. |
| Pads | Marker list `{type: trampoline|boost|fan, region, direction}` from arena config; looked up by block key on block change |
| Events | `PlayerMoveEvent` (block change or plane test), `PlayerInteractEvent` (reset/toggle), damage (cancel all) |
| World | Disposable clone; no block edits by players |

#### Edge cases & cancel

- Ignore repeated finish entries (v1). Fan-launching past the finish counts only through a plane crossing.
- A player stuck in honey or webs can use the reset item.
- If the next checkpoint does not change for 20 s, the HUD hints "Use the reset item".
- A disconnected player is DNF and ranked as a leaver.

#### Config

```yaml
minigame:
  speed_race:
    arena: { template: speed_race_ashstone, spawn: {...}, boundary: {...} }
    timeout-seconds: 75             # v1
    checkpoint-count: 10            # v1 (validated against markers)
    reset-time-penalty-seconds: 2   # v1
    pickup-bonus-coins: 1           # v1
    finish-grace-seconds: 20
    base-speed-amplifier: 1         # Speed II
    invisible-racers: true
    coin-boost: { amplifier: 2, ticks: 30 }
    boost-pad: { amplifier: 2, ticks: 20 }
    trampoline: { forward: 1.6, up: 0.9 }
    fan: { lift: 0.6 }
    reset-cooldown-ticks: 60
    clean-run-bonus-coins: 2
    bonus-coin-cap: 10
```

#### Divergences

- `finish-grace-seconds` is [D], to keep the board loop fast. Cytooxien lets everyone keep racing until the timer ends.
- Live remaining-distance display is [D], borrowed from Cytooxien's Elytra Race sidebar [V], because Race players complained about not knowing their place.

---

### 6.3 Spleef — `spleef`

**Fantasy:** Three stacked snow floors hang over a mountain valley. Dig out the snow under your rivals, dodge the charged creepers, grab mystery boxes for TNT and wind charges, and be the last one standing.

| Field | Value |
|-------|-------|
| Length | 60–90 s |
| Foundations | `EliminationTracker`, `PowerUpBox`, `MatchHud`; disposable arena via `MinigameRunner` |
| Cytooxien analog | Spleef, in rotation; the multi-level shovel variant replaced the crossbow variant in 2025-01 |
| Code today | Display name "Snow Spleef". Baked layers, shovel, charged creepers, mystery boxes, 120 s timeout. No INTRO/END, decay or layer HUD yet. |

#### Cytooxien reference

- [O 2025-01] Snow shovels replace crossbows, and there are multiple levels.
  - **Wind Charge** switches levels quickly.
  - **Bomb** is thrown onto another level and destroys blocks there. If the ground beneath it breaks, it falls further and can hurt the thrower.
  - A **special bow** fires TNT arrows to knock distant players into the abyss.
- [O 2026-03] Power-ups are Creeper Arrows (creepers chase the target, explode, break blocks and knock back), Boxing Glove, TNT Arrow, Wind Charges and Bomb. Spleef was the first game to let players hold several power-ups at once.
- [V 2025-08 short] 3 snow layers stacked several blocks apart over a snowy valley with waterfalls. The hotbar holds a shovel plus a bow once the "Explosive Arrow" power-up is received.
- [V 2026-10 stream] A 1-layer temporary-floor variant exists in other games (Floor is Lava). Not used here.

#### Level design

- **Layers** are baked once into the template with `/partyadmin spleef generate [overwrite]` from `minigame.spleef.platform` (code):

  | Layer | Y (default config) | Shape |
  |-------|--------------------|-------|
  | L1 (top) | 84 | Disc, radius `platform.radius` (16) |
  | L2 | 74 | Same disc |
  | L3 | 64 (`platform.bottom-y`) | Same disc |

  - One block thick, made of the first `floor-materials` entry (`SNOW_BLOCK`), centered on the arena spawn X/Z.
  - Layers are `layer-gap` (10) apart. A drop is quick but readable, and a wind charge can climb back.
  - Optional [D]: pre-cut 3×3 holes in L2 and a smaller L3 (`R − 3`) for drop-through plays. This needs a `SpleefPlatform.paint` change; the default stays three full discs.
- **Size:** one radius for every player count today. Per-count templates (`R` = 12 / 15 / 18 for 2–4 / 5–8 / 9–12 players) wait for Phase B (§4).
- **Below L3:** the valley at `fall-y` (58). Reaching it, or leaving the arena world, eliminates.
- **Spawns:** on L1, on a ring of `spawn-radius` (7.0) around the arena spawn, evenly spaced and facing the center (code).
- **Power-up and creeper spots:** a random exposed floor block on any layer with two air blocks above it (code).
- **Scenery:** mountains, waterfalls and pine trees outside the boundary. Players must be able to see all three layers from the edge so drop plays are readable.

```text
 side view (default config)
 L1  y=84  ███████████████████████████████   ← spawn ring (r = 7)
 L2  y=74  ███████████████████████████████
 L3  y=64  ███████████████████████████████
 ~~~~~~~~~~~ valley, fall-y = 58 ~~~~~~~~~~~
```

#### Kit & HUD

- Slot 1: unbreakable diamond shovel, Efficiency V (instant snow break), model `mcparty:snow_shovel` (code). Power-up items go to the next free slots.
- All player damage is cancelled (`protectFromDamage`). Snowball hits and blasts push players with manual velocity; melee does nothing (code).
- Action bar: seconds left, once per second (code).
- Sidebar (v2): alive list with each player's current layer (L1/L2/L3).
- Boss bar (v2): time left. From `decay-start-seconds` on, it shows "Floor crumbling!".

#### Timeline

| t | Event |
|---|-------|
| INTRO (5 s) | On L1 (v2; the code starts at once) |
| GO | Breaking is enabled |
| every `powerup.spawn-interval-seconds` (8 s) | 1 mystery box on a random exposed floor block, max `powerup.max-active` (2) |
| every `creeper.spawn-interval-seconds` (6 s) | 1 charged creeper on a random exposed floor block, max `creeper.max-alive` (4) |
| GO+60 s (`decay-start-seconds`, v2) | **Sudden death:** random snow blocks on the lowest *occupied* layer turn to air at `decay-blocks-per-second` (6/s), each with a particle warning 10 ticks before |
| GO+90 s (`timeout-seconds`) | End |

#### Rules & activities

1. Only alive players can break, and only `floor-materials` inside the boundary. There are no drops (code).
2. Reaching `fall-y` or leaving the arena world eliminates the player (code).
3. Players drop to a lower layer through holes they dig; wind charges are the way back up.
4. **Charged creepers** (code): invulnerable, non-persistent and tracked by the scope.
   - Explosion radius `creeper.explosion-radius` (2, doubled because they are charged), fuse `creeper.fuse-ticks` (30).
   - An idle creeper ignites by itself after `creeper.auto-ignite-seconds` (10), so the floor keeps shrinking.
   - Creepers that fall to `fall-y` are removed.
5. **Blasts** from creepers and TNT (code):
   - No drops; only floor blocks inside the boundary are removed (`EntityExplodeEvent#blockList` filter). Scenery never breaks.
   - Alive players within `2 × blast power` are launched away with `knockback` (1.4) strength and distance falloff. Damage stays cancelled, so the launch is manual.
6. **Power-ups:** mystery boxes (§8). Touching one rolls a single power-up:

   | Power-up (weight) | Effect (code) |
   |-------------------|---------------|
   | **TNT** (3) | 3 throwable TNT, model `mcparty:throwing_tnt`. Right-click throws primed TNT (fuse 40 ticks, power 3, 10-tick cooldown); it is never placed. |
   | **Wind Charge** (3) | 3 vanilla wind charges; climb a layer or push others |
   | **Snowballs** (3) | 16 snowballs. A player hit is pushed (0.6 horizontal, 0.35 up); a floor block hit breaks. |
   | **Sugar Rush** (2) | Sugar, model `mcparty:sugar_rush`. Right-click: Speed II + Jump Boost II for 8 s |
   | **Creeper Surprise** (2) | A lit charged creeper drops behind a random opponent |

   Weights and amounts are constants in `SpleefMinigame`; moving them to config is optional.

#### Ranking & coins

- **Ranking:**
  - The last player standing is 1st, then reverse fall order (code).
  - At timeout the code ranks survivors in party order. v2: by current layer (higher is better), then blocks broken.
  - Falls in the same tick: v2 tie-breaks by blocks broken (code: processing order).
- **Coins:** placement only (Cytooxien lists no Spleef coin source [O]). No bonus coins.

#### Tech

| Area | Plan |
|------|------|
| State | Alive set, creepers, power-up boxes, timeout counter (code). v2 adds each player's layer and blocks broken. |
| Events | `BlockBreakEvent`, `PlayerInteractEvent` (TNT throw, sugar), `ProjectileHitEvent` (snowballs), `EntityExplodeEvent` (tracked creepers and TNT), `PlayerDropItemEvent` (cancelled); the fall check runs in the 1-tick task (code) |
| Blocks | Real break with no physics; the clone is disposable |
| Restore | `MinigameRunner` unloads the clone on end/cancel |
| Decay (v2) | Pick cells on the lowest occupied layer, show particles, then `setType(AIR, false)` 10 ticks later |

#### Edge cases & cancel

- Breaking outside the floor materials or the boundary is denied (code). v2 also denies breaking under the spawn ring during INTRO.
- Creepers and primed TNT are tracked and removed when the scope closes (code). Creepers must never target non-match players; this needs `EntityTargetEvent` routing (§4).
- Blasts near the edge only remove floor blocks inside the boundary (code).

#### Config

```yaml
minigame:
  spleef:
    arena:                                          # code
      template: spleef_arena
      spawn: { x: 0.5, y: 85.0, z: 0.5, yaw: 0.0, pitch: 0.0 }   # on L1: bottom-y + layer-gap × (layers − 1) + 1
      boundary: { minX: -40, minY: 40, minZ: -40, maxX: 40, maxY: 140, maxZ: 40 }
    platform: { radius: 16, bottom-y: 64, layer-gap: 10, layers: 3 }   # code — baked by /partyadmin spleef generate
    timeout-seconds: 90                             # code (default 120)
    fall-y: 58.0                                    # code — below L3, above boundary.minY
    spawn-radius: 7.0                               # code
    floor-materials: [SNOW_BLOCK]                   # code
    knockback: 1.4                                  # code — blast launch strength
    powerup: { spawn-interval-seconds: 8, max-active: 2 }   # code
    creeper: { spawn-interval-seconds: 6, max-alive: 4, explosion-radius: 2, fuse-ticks: 30, auto-ignite-seconds: 10 }   # code
    decay-start-seconds: 60
    decay-blocks-per-second: 6
```

#### Divergences

- McParty's power-ups replace Cytooxien's 2026-03 Spleef set [D]: roaming charged creepers, throwable TNT, snowballs, Sugar Rush and Creeper Surprise. Wind Charge is shared. TNT Arrow, Bomb, Boxing Glove and Creeper Arrows are deferred (§13).
- The legacy TNT/crossbow variant from the v1 design is not implemented and not planned.
- Sudden-death decay is [D]. Cytooxien relies on power-ups to end long rounds; we need a guaranteed end inside 90 s.
- `timeout-seconds` drops from the code's 120 to 90 to fit the 60–90 s target.

---

### 6.4 Elytra Race — `elytra_race`

**Fantasy:** Dive into a canyon, thread the rings, skim the walls for the tight lines, and fly through the finish ring first.

| Field | Value |
|-------|-------|
| Length | 45–75 s (a winning run is ≈ 45 s) |
| Foundations | `CheckpointTracker` (ring planes), `TrackProgress`, `RaceKit`, `CoinPickupService` (ring coins), `MatchHud` |
| Cytooxien analog | Elytrarennen, in rotation [I]; Shards map (2025-05) |
| Code today | Course store + setup commands, ordered rings, flat center bonus, 120 s timeout. A player who stops gliding or leaves the boundary is out. No INTRO, fireworks, reset item or respawn. |

#### Cytooxien reference

- [O] Fly through all rings with an Elytra. Each ring gives coins: the closer to the center, the more coins. The center is not always the fastest line. The first player through the finish ring wins.
- [V Marvelino 2025-11]:
  - Map: a canyon/cave map with water, cliffs and overgrown pillars.
  - Rings: octagonal orange/gold frames, about 5–6 blocks across. Each ring pops "+1 ⛁".
  - Sidebar metric: **remaining distance** to the finish (1038 → 727 → 131).
  - Hotbar: slot 1 reset, slot 9 visibility toggle. **No fireworks are visible.**
  - Pace: the winner finished in **44.586 s** (a "new record" message).
  - Finish: the finish ring is decorated with leaves. Finished players get Invisibility + Night Vision and chat announces "X reached the finish as #N".
- [V Gustaf 2023] Hitting a wall sent the player far back ("do it all again").

#### Level design

- **Course data** lives in `elytra-courses.yml` and is edited with `/partyadmin elytra ...` (code, §11): template, spawn, boundary and an ordered ring list. `minigame.elytra_race.course` names the active course (`/partyadmin elytra activate <id>`). A course is ready when the arena is valid, the spawn is inside it and it has at least 2 rings; the last ring is the finish.
- **Start platform** around the course spawn, at the course's highest point, 10–20 blocks above ring 1 and facing it. The code lines players up through the spawn, 1.5 blocks apart across the facing direction, so the platform must be at least 18 blocks wide for 12 players.
- **Course:**
  - Horizontal length 600–900 blocks; total drop 100–160 blocks.
  - **14–20 rings including the finish**, spaced 25–60 blocks apart.
  - The turn between consecutive rings is ≤ 60° so the next ring is visible from the previous one.
- **Ring sizes** (the radius argument of `ring`/`finish`; default `default-ring-radius`, 3.0):

  | Ring type | Radius |
  |-----------|--------|
  | Opener (rings 1–3) | 4 |
  | Standard | 3 |
  | Technical (tight, near walls) | 2.5 |
  | Finish | 5 |

- **Segment mix** (example, 16 rings):

| Rings | Segment | Notes |
|-------|---------|-------|
| 1–3 | Open dive | Wide valley, large rings; teaches pitch control |
| 4–6 | Canyon slalom | Walls 15–25 apart, alternating left/right |
| 7 | Boost ring | Cyan ring, ×1.25 velocity; rewards a clean line into it |
| 8–10 | Cave tunnel | 8–12 wide; pillars inside (Night Vision is given from the start on maps with caves) |
| 11 | Updraft | Particle column lifts +20 blocks to regain height |
| 12–15 | Technical | Rings close to walls/arches; the center line is slower than the racing line [O] |
| 16 | Finish | Large ring with leaves/banners |

- **Ring data** (code): `{x, y, z, radius, normal-x/y/z}`. `ring`/`finish` store the admin's eye location and use the look direction as the normal. v2 adds an optional `type: boost` per ring and updraft regions in the same course entry.
- **Ring displays** (code): one non-persistent `ItemDisplay` per ring with `mcparty:elytra_ring`, scaled so the visible opening equals `radius + 0.4` (the hitbox padding), view range 128.
- **Next-ring marker** (v2): a small arrow `ItemDisplay` over each player's next ring, visible only to that player (`setVisibleByDefault(false)` + `showEntity`).
- **Crash zones:** terrain is real. Code: any tick without gliding, or leaving the boundary, puts the player out as a spectator. v2: touching ground or water, or not gliding for more than 10 ticks, respawns the player instead (see Rules).
- **Boundary:** `BoundaryListener` clamps every move to the course boundary, so it acts as a hard wall. Keep it well clear of the racing line.

#### Kit & HUD

- Unbreakable Elytra in the chest slot (code). v2: slot 1 reset, slot 9 visibility toggle.
- Fireworks (v2): `fireworks-per-player`, default **3** [D]. Cytooxien appears to use 0 [V]; the code gives none.
- Effects (v2): Invisibility ∞; Night Vision ∞ from the start on maps that have a cave section.
- Chat line per ring and per center hit, action bar with seconds left (code). v2 action bar: "Ring 7/16 · +2 ⛁ (center)".
- Sidebar (v2): remaining distance plus ⭐ for finished players.

#### Timeline

| t | Event |
|---|-------|
| INTRO (5 s) | On the start platform, frozen and facing ring 1 (v2; the code launches 2 ticks after start) |
| GO | Players are placed on the launch line, given the Elytra, set gliding and launched: forward 0.65, up 0.75 (code values) |
| live | Race |
| first finish | `finish-grace-seconds` (20 s, v2); the race ends early when every player has finished or is out |
| GO+75 s | `timeout-seconds` (code default 120) |

#### Rules & activities

1. **Ring crossing** (code):
   - Movement from/to is converted to eye positions.
   - A move counts only for the **next** ring: a real plane crossing in the arena world while gliding, with the intersection inside `radius + 0.4`.
   - Several rings crossed in one move are validated in crossing order. Out-of-order rings are ignored.
2. **Ring coins** (v2) by the distance `d` from the intersection point to the center. They replace the code's flat `center-bonus-coins` (2 coins when `d ≤ 0.35·r`, else none):

   | Distance | Coins |
   |----------|-------|
   | `d ≤ 0.33·r` | 3 |
   | `d ≤ 0.66·r` | 2 |
   | otherwise, including the 0.4 padding band | 1 |

   The popup "+N ⛁" is shown at the ring. Values are [D]; the concept is [O].
3. **Boost ring** (v2): on crossing, velocity × `boost-ring-multiplier` (1.25), capped at 2.2 blocks/tick.
4. **Crash / respawn** (v2; the code puts the player out):
   - If the player is not gliding for more than 10 consecutive ticks while not at the finish (this covers bounces), freeze 10 ticks and teleport to the last ring's center, offset 3 blocks back along its normal.
   - The player faces the next ring and is relaunched gliding at `respawn-velocity` (0.8).
   - The reset item does the same, with a 3 s cooldown.
5. **Fireworks** (v2): vanilla boost while gliding; the supply is limited and there are no refills.
6. **Finish:** crossing the last ring with every ring done records the finish order (code). v2 adds the time title "#N · 0:44.586" and switches to spectator after 2 s (code: at once).

#### Ranking & coins

- **Ranking:**
  - Finished players by finish order.
  - Unfinished players by rings passed (descending), then remaining distance (ascending, v2). The code breaks ties by bonus coins, then party order.
- **Coins:**
  - Placement + ring coins (code: placement + the flat center bonus, uncapped).
  - +2 for a clean run: no crash or reset (v2).
  - The total bonus is capped by `bonus-coin-cap` (20, v2).

#### Tech

| Area | Plan |
|------|------|
| Flight | Elytra + launch (code). v2 watches `EntityToggleGlideEvent` for crash detection (new hook, §4). |
| Checkpoints | Ordered ring planes, `ElytraRing.collision` on eye positions (code) |
| Live rank | `TrackProgress` over ring centers (v2) |
| Events | `PlayerMoveEvent` (code). v2 adds the glide toggle and `PlayerInteractEvent` (reset, toggle, firework count). All damage, including `FLY_INTO_WALL`, is cancelled by `protectFromDamage` (code). |
| Displays | One ring `ItemDisplay` per ring (code); one per-player next-ring arrow (v2) |
| World | Prebuilt disposable clone; ring blocks are never created |

#### Edge cases & cancel

- A finish counts only after every ring was crossed in order (code).
- Ring displays are removed on end/cancel (code); `MatchScope` restores the chest slot.
- A disconnected player leaves the active set and is ranked by rings passed (code).
- The boundary is a hard wall (`BoundaryListener`); the v2 crash respawn frees players stuck against it.

#### Config

```yaml
minigame:
  elytra_race:
    course: default                 # code — active course in elytra-courses.yml
    default-ring-radius: 3.0        # code — default radius for /partyadmin elytra ring|finish
    timeout-seconds: 75             # code (default 120)
    # center-bonus-coins: 2         # code — replaced by center-coins
    fireworks-per-player: 3         # v1 (was 8)
    finish-grace-seconds: 20
    ring-padding: 0.4               # code constant (RING_HITBOX_PADDING)
    center-coins: { inner: 3, middle: 2, outer: 1 }   # d/r ≤ 0.33 → inner, ≤ 0.66 → middle, else (incl. padding) → outer
    boost-ring-multiplier: 1.25
    launch-velocity: { forward: 0.65, up: 0.75 }      # code constants
    respawn-velocity: 0.8
    clean-run-bonus-coins: 2
    bonus-coin-cap: 20
```

#### Divergences

- Fireworks: 3 instead of the v1 design's 8 (the code gives none). Cytooxien relies on course design (dive height, boost and updraft sections) instead of rockets. With 8 rockets the fastest line ignores the terrain.
- Respawn at the last ring is [D]. 2023 footage showed a restart, and the code puts the player out.
- The v1 keys `arena`, `checkpoint-count` and `boundary-grace-blocks` are gone: the course store provides the arena, the ring list defines the checkpoints, and `BoundaryListener` makes the boundary a hard wall.

---

### 6.5 Shooting Range — `shooting_range`

**Fantasy:** Everyone shares one gallery full of moving mobs. Pick the valuable shots, steal targets before others hit them, and avoid the penalty targets.

| Field | Value |
|-------|-------|
| Length | 60 s |
| Foundations | `ScoreTracker`, `PowerUpBox`, `MatchHud`; game-local `TargetDirector` |
| Cytooxien analog | Schießstand, in rotation; maps Trial Chamber (2025-05), Farm (2026-03) |

#### Cytooxien reference

- [O] Hit targets with a bow. Different monsters and animals give different points; the most points within the time limit wins.
- [O 2025-05] **Glowing golden monsters** award coins. Points appear when a target is hit.
- [V Marvelino 2025-09, Jungle]:
  - Layout: a lush-cave gallery with a river in front of the shooting platform and terraces with trees and mobs behind it.
  - Score pace: one player went from 29 to 76 points in about 25 s (≈ 2 points/s).
  - HUD: an orange charge bar above the hotbar and a custom bow.
- [V Grapfen 2023] **Special mobs** are worth "extremely many" points:
  - Parrots at the back are hard to hit.
  - Axolotls swim in the water channel.
  - Zombies are normal targets.
  - Values differ per map, and there is a power-up.
- [V 2025-09, Farm] Bats are the hardest targets.
- [V, unnamed game] Coloured sheep, a "−10" penalty target, and "double points".

#### Layout decision: shared gallery

Cytooxien's footage shows one shared gallery [V/I], and v1 planned per-player lanes. **v2 default: `layout: shared`.** It gives more interaction (target stealing), needs a single compact map, and matches the reference. `layout: lanes` stays supported for maps that want it: the targets are the same, but each lane's targets only score for the lane owner.

#### Level design (shared)

- **Firing platform:**
  - Size 26×4. 12 standing marks, 2 blocks apart.
  - Waist-high fence at the front, barrier behind and at the sides. Players may walk along the platform but not leave it.
  - Power-ups spawn **on the platform** (touch pickup).
- **Target field:** 44 wide (±22 from the platform center), in 3 depth bands:

| Band | Depth from platform | Height | Content | Movement |
|------|---------------------|--------|---------|----------|
| Near | 10–18 | ground | Livestock pens, hay, crops | Pen-wander (AI on inside a fence) |
| Water | 18–22 | channel −1 | A river crossing the whole width | Swimmers (axolotl, fish) on rails |
| Mid | 22–34 | terraces +3 / +6 | Monsters on walkways, behind trees and arches | Rail (scripted back-and-forth) |
| Far | 36–50 | +8 / +14 | Flyers in a glass-free volume; a balcony at the back | Fly paths (bats, parrots) |

- **Occluders:** trees, arches and windmill blades crossing sightlines, which create timing windows.
- **Markers:**
  - 16–24 target spawn markers, each with type pool, movement mode and path (rail start/end or pen region).
  - 1 golden-spawn marker set (any band).
  - 4 platform power-up spots.
- **Themes:** Farm (current), Trial Chamber (current), Jungle cave (retired at Cytooxien).

```text
 far   ~ ~ bats ~ ~ parrots ~ ~   (+8..+14)          d=36..50
 mid   ═ zombie ═══ skeleton ═══  terraces (+3/+6)   d=22..34
 water ≈≈≈≈ axolotl ≈≈≈≈≈≈ fish ≈≈≈                  d=18..22
 near  [cow] [pig] [sheep] [chicken]  pens           d=10..18
       ───────────── fence ─────────────
 PLATFORM  ●  ●  ●  ●  ●  ●  ●  ●  ●  ●  ●  ●    (power-ups spawn here)
```

#### Target table (Farm defaults, [D] values)

| Target | Band | Movement | Points | Notes |
|--------|------|----------|--------|-------|
| Chicken | Near | Pen | 1 | Small hitbox |
| Sheep (white) | Near | Pen | 1 | |
| Sheep (coloured) | Near | Pen | 2 | Random dye |
| Pig / Cow | Near | Pen | 2 | |
| Zombie | Mid | Rail | 3 | Must not burn: cancel `EntityCombustEvent` |
| Skeleton | Mid | Rail | 3 | AI off; never shoots |
| Axolotl / Tropical fish | Water | Rail | 5 | Visible just under the surface |
| Bat | Far | Fly path | 6 | Hardest [V] |
| Parrot | Far | Fly path | 8 | "Hard to hit at the back" [V] |
| **Golden zombie** | Any | Rail, fast | 5 + **2 coins** | Glowing, gold armor; 1 every 10 s, lives 6 s [O concept] |
| **Penalty: villager** | Near/Mid | Pen | **−5** | ~10% of spawns; a red "−5" popup |

#### Kit & HUD

- Slot 1: unbreakable bow with Infinity and 1 arrow. Players cannot damage each other (cancel).
- Sidebar metric: points.
- On every hit, a `TextDisplay` "+3" in the shooter's colour floats up from the target for 20 ticks [O "points shown on hit"].
- Action bar: "Hits 14 · Accuracy 61% · 2× (4 s)".

#### Timeline

| t | Event |
|---|-------|
| INTRO (5 s) | The field is already populated (targets frozen) |
| GO | Targets start moving |
| every `target-refresh-ticks` (20) | `TargetDirector` refills to `6 + 2 × players` alive targets (cap 24), picking band and type by weights |
| every 10 s | Spawn 1 golden target |
| every 15 s | Power-up on the platform (max 2) |
| GO+60 s (`timeout-seconds`) | End: targets despawn with a poof wave |

#### Rules & activities

1. **Hitting a target:**
   - Trigger: an arrow from an in-match shooter hits a registered target. **The first hit wins it.**
   - The target is removed (poof + sound), points are added, and the arrow is removed.
2. **Penalty targets** subtract points. The score may go negative (v1 has no floor; [D] floor at 0 is optional).
3. **Arrows hitting players** are cancelled and removed. Arrows hitting blocks are removed after 20 ticks.
4. **Power-ups** (touch on the platform):

   | Power-up | Effect |
   |----------|--------|
   | **Double Points** | 8 s, points ×2, golden coins unaffected |
   | **Rapid Fire** | 8 s; bow charge is full after 50% of the time (arrow velocity scaled to full on release) |

5. **Lanes mode only:** a target only accepts hits from its lane owner. Other arrows pass through (cancel the hit, keep the arrow flying).

#### Ranking & coins

- **Ranking:** score (descending), then hit count (descending), then the earlier final scored hit (v1).
- **Coins:** placement + 2 per golden target. Optional accuracy bonus: +2 for ≥ 60% with ≥ 15 shots. Bonus cap 8.

#### Tech

| Area | Plan |
|------|------|
| Targets | `Map<UUID, Target{type, points, mode, path}>` |
| Spawning | `World#spawn(..., consumer)` sets `setPersistent(false)`, `setRemoveWhenFarAway(false)`, `setSilent(false)` and a PDC match tag |
| Pen mode | AI on, fenced region |
| Rail / fly mode | `Mob#setAware(false)` + `setGravity(false)` (bats and parrots would otherwise fall); a 2-tick task teleports along the path with yaw. Client interpolation keeps it smooth. |
| Events | `ProjectileHitEvent` (routed by shooter, code), plus target hooks routed by tracked entity (new, §4): `EntityDamageEvent` cancel, `EntityCombustEvent` cancel, `EntityTargetEvent` cancel, `EntityDeathEvent` drops cleared |
| Weapons | Bow, Infinity. Projectiles not from match players are ignored (v1). |
| Cleanup | Despawn every registered target and arrow, then unload the clone (v1) |

#### Edge cases & cancel

- Ignore hits after END or cancel (v1).
- Two arrows hitting the same tick: the first processed wins; the second arrow is removed with no score.
- Golden targets that leave their path (pushed by water) are removed.
- A disconnected player is ranked as a leaver by their score at leave time (§3.7).

#### Config

```yaml
minigame:
  shooting_range:
    arena: { template: shooting_range_farm, spawn: {...}, boundary: {...} }
    timeout-seconds: 60              # v1
    target-refresh-ticks: 20         # v1
    layout: shared                   # shared | lanes
    targets-base: 6
    targets-per-player: 2
    targets-cap: 24
    points:                          # v1 (extended)
      chicken: 1
      sheep: 1
      colored_sheep: 2
      pig: 2
      cow: 2
      zombie: 3                      # v1 (was 1)
      skeleton: 3
      axolotl: 5
      tropical_fish: 5
      bat: 6
      parrot: 8
      golden_zombie: 5
      villager: -5
      bullseye: 5                    # v1 (target-block variant maps)
    golden: { interval-seconds: 10, lifetime-seconds: 6, coins: 2 }
    penalty-weight: 10
    score-floor-zero: false
    powerups: { interval-seconds: 15, max-on-map: 2, double-points-seconds: 8, rapid-fire-seconds: 8 }
    accuracy-bonus: { min-shots: 15, min-ratio: 0.6, coins: 2 }
    bonus-coin-cap: 8
```

#### Divergences

- The default switches from lanes to a **shared** gallery (see the layout decision).
- The point table is [D], tuned so that far and special targets are worth 3–8× a farm animal, matching the "special mobs give extremely many" pattern [V].

---

### 6.6 Color Chaos — `color_chaos`

**Fantasy:** A painted plaza floor. A colour is called; everything else drops away. Find your colour, faster every round.

| Field | Value |
|-------|-------|
| Length | 45–75 s (≈ 10–14 rounds) |
| Foundations | `EliminationTracker`, `PowerUpBox`, `MatchHud`; game-local `PatternGenerator` |
| Cytooxien analog | Buntes Chaos, in rotation; overhauled 2026-03 (new look, pattern system, timing) |

#### Cytooxien reference

- [O] Stand on the displayed colour before the other blocks vanish. Intervals get shorter. Collect power-ups early for the final phases.
  - Power-ups no longer despawn (2025-01).
  - **Chicken Glider:** a controllable slow descent with a chicken on your head.
  - **Paint Bomb:** colours nearby blocks in the selected colour; players near the blast are guaranteed to reach the next round (2026-03).
- [V EnderKnight 2026-10, current version]:
  - **Map:** a town plaza; the floor is about 40×40 of saturated concrete-like colours in **large irregular patches and diagonal bands**, the new pattern system [I].
  - **Colour call:** shown three ways — a boss bar labelled with the colour name (e.g. "Rot", red) that drains from green to red, a ">>> ROT <<<" line above the hotbar, and the colour's block in hotbar slot 1.
  - **Drop:** when the bar empties, every other colour vanishes, leaving scattered islands (1×2 to 3×3) of the called colour. A lower plaza floor sits about 4 blocks below; falling there means you are out.
  - **Sidebar:** MINISPIEL / ZEIT (time; 2:00 left early on) / PLATZIERUNG (placement), listing rounds survived, with collapsed "N Spieler" (N players) rows.
- [V Grapfen 2023] Tip: jump exactly when the bar runs out to keep momentum toward the nearest patch.

#### Level design

- **Floor:**
  - One layer of concrete at `floor-y`.
  - Size by player count: 24×24 (2–4), **32×32 (5–8, default)**, 40×40 (9–12). Square, or an octagon via a corner mask.
- **Palette** (high contrast, colour-blind friendly ordering):
  - Start colours: RED, YELLOW, LIME, LIGHT_BLUE, BLUE, PURPLE.
  - Added from round 5: ORANGE, PINK.
  - Added from round 9: WHITE, BLACK.
  - Each colour has a fixed display name and chat colour.
- **Below the floor:** a decorative ground plaza at `floor-y − 4`. A player is eliminated when their feet Y < `floor-y − 1.5`; no damage.
- **Surroundings:** plaza buildings on all sides at least 6 blocks from the floor edge so nobody can jump off onto them, plus a barrier shell. Eliminated spectators can float above.
- **Power-up markers:** none fixed. Power-ups float 1 block above random floor cells.

#### Pattern generator (the "pattern system")

Each round picks one generator by weight, then assigns colours:

| Generator | Description | Feel |
|-----------|-------------|------|
| `cells` | Square cells with an edge of N blocks (N = 2–4), random colours | Classic Block Party |
| `stripes` | Straight or diagonal bands, width 2–4 | Lines to run along |
| `rings` | Concentric rings, width 2 | Pulls everyone to center or edge |
| `sectors` | Radial pie slices from the center | Spread out |
| `blobs` | Value noise thresholded into k colours | Organic patches [V] |
| `stamps` | Pre-authored 32×32 masks (star, heart, arrow, McParty logo), nearest-neighbour scaled to the floor size | Show-off rounds (rarely) |

**Choosing the called colour.** The colour is chosen **after** the pattern is drawn: the generator picks the palette colour whose layout best meets the constraints. Shape generators (`rings`, `sectors`, `stamps`) also get a **sprinkle pass** that recolours small 1×1–2×2 spots of the called colour until the constraints hold.

**Fairness constraints** (checked after generation; regenerate at most 5 times, then use the guaranteed fallback):

- **Coverage:** the called colour covers `coverage(round) ± 5%`, where `coverage(round) = max(coverage.min, coverage.start − (round − 1) × coverage.step)`.
- **Reach:** every floor cell lies within `reach(round)` blocks of a called-colour cell (same formula with the `reach.*` keys). This is checked with a BFS distance transform over the grid (1,600 cells max), which is cheap.
- **Patches** (`cells`, `blobs` and `stripes` only): at least `min(ceil(alive × 1.5), 12)` separate called-colour patches.
- **Guaranteed fallback:** `cells` with N = 2, where the called colour is placed on a jittered lattice with spacing `reach(round)`. This satisfies reach by construction; coverage is then trimmed or grown with random cells.

#### Kit & HUD

- Called colour: its block is placed in slot 1 [V]. Slots 2–8 hold power-ups.
- Boss bar: colour name in that colour; progress drains over the call time; the bar colour follows the called colour where possible.
- Action bar: ">>> RED <<<".
- Sidebar metric: rounds survived.

#### Round timeline

| Step | Duration | Behaviour |
|------|----------|-----------|
| PAINT | 10 ticks | New pattern written (only changed cells). Sound: brush. |
| CALL | `call-ticks(round)` | Colour announced (boss bar + action bar + slot 1). Players run. |
| DROP | `drop-hold-ticks` (40) | Every non-called cell is set to AIR. Eliminations are checked every 2 ticks. |
| RESTORE | — | Next round PAINT |

- **Call time:**
  - `call-ticks(round) = max(min-delay-ticks, first-delay-ticks − (round − 1) × delay-step-ticks)`.
  - With defaults 80 / 5 / 24 that runs 4.0 s in round 1 down to 1.25 s in round 12.
  - One round (paint + call + drop) lasts 6.5 s at first and 3.75 s by round 12, so 12 rounds take ≈ 61 s.
- **Game end:** 1 player alive, `max-rounds` reached, or `timeout-seconds`.
- **After `max-rounds` with several alive:** sudden-death rounds at minimum timing until 1 remains or the timeout hits.

#### Rules & activities

1. **Elimination:** feet Y < `floor-y − 1.5` during DROP. Edge standing works naturally, because vanilla collision supports a player whose hitbox overlaps any solid block.
2. Pushing is allowed (collision on). Melee hits apply a manual knockback, because the bus cancels the hit and with it vanilla knockback (§3.4). Shoving someone off a 1×2 patch is part of the game.
3. **Power-ups:**
   - Spawn every 3 rounds from round 2, max 2 on the floor at once.
   - They **do not despawn** [O] and stay floating even when the cell under them drops.

   | Power-up | Effect | Notes |
   |----------|--------|-------|
   | **Chicken Glider** | 4 s of Slow Falling plus an initial hop (Y velocity 0.42). Horizontal steering stays normal. Chicken helmet display. | Use it during DROP to glide onto a patch you could not reach |
   | **Paint Bomb** | Throwable. On landing, every floor cell within radius 3 becomes the **called colour**. | Usable only during CALL; otherwise "Wait for the next colour". Players near it are effectively safe [O]. |

#### Ranking & coins

- **Ranking:**
  - The last alive is 1st, then reverse elimination order.
  - Same-round eliminations are tie-broken by distance from the player's position at DROP start to the nearest called cell (closer is better), then by the lower average of that distance over all rounds.
  - **Survivors at `max-rounds` or timeout** rank above all eliminated players. Among themselves they are ranked by the lower average DROP-start distance, which rewards being in position early.
- **Coins:** placement only [O lists no Color Chaos coin source]. No bonus coins.

#### Tech

| Area | Plan |
|------|------|
| Floor | **Mode A (v1 recommendation):** real blocks with `setBlockData(..., false)`, an in-memory `byte[]` colour grid, and only changed cells written (≤ 1,600 writes per paint, done in 1 tick) |
| Restore | Disposable clone; no journal needed |
| Events | Move check only during DROP (block Y change), `PlayerInteractEvent` (power-ups), `ProjectileHitEvent` (paint bomb) |
| Generator | Pure function `(seed, round, size, palette) → byte[]`. Deterministic seed per match for reproducible bugs. |

#### Edge cases & cancel

- A player who is mid-jump at the DROP check is judged on later ticks: eliminations are checked across the whole hold, not once.
- A Paint Bomb landing off the floor does nothing (refund).
- `cancel()` mid-DROP is safe: the clone is unloaded.

#### Config

```yaml
minigame:
  color_chaos:
    arena: { template: color_chaos_plaza, spawn: {...}, boundary: {...} }
    first-delay-ticks: 80        # v1 (was 60)
    min-delay-ticks: 24          # v1 (was 20)
    delay-step-ticks: 5          # v1
    max-rounds: 12               # v1
    timeout-seconds: 75
    floor-y: 64
    floor-size: auto             # auto | 24 | 32 | 40
    drop-hold-ticks: 40
    paint-ticks: 10
    palette: [RED, YELLOW, LIME, LIGHT_BLUE, BLUE, PURPLE, ORANGE, PINK, WHITE, BLACK]
    palette-start: 6
    palette-add: { 5: 2, 9: 2 }  # round → extra colours
    coverage: { start: 0.25, step: 0.015, min: 0.08 }
    reach: { start: 8.0, step: 0.5, min: 3.0 }
    generator-weights: { cells: 25, stripes: 20, rings: 10, sectors: 10, blobs: 30, stamps: 5 }
    powerups: { first-round: 2, every-rounds: 3, max-on-floor: 2, weights: { chicken_glider: 55, paint_bomb: 45 } }
```

#### Divergences

- The call timings are slightly longer than v1 (80 vs 60 ticks to start), because larger patterned floors need more travel time than a uniform grid.
- The fairness checks (coverage and reach) are [D].

---

### 6.7 Laser Tag — `laser_tag`

**Fantasy:** A neon-lit port yard. Every hit sends the target back to a spawn. Keep moving, chain tags into streaks, and top the board when the clock runs out.

| Field | Value |
|-------|-------|
| Length | 60 s |
| Foundations | `ScoreTracker`, `ArenaSpawns`, `MatchHud`; optional `PowerUpBox` |
| Cytooxien analog | Lasertag, in rotation; maps Neon (revamped 2025-05), Port and Factory (reworked 2025-01); new reload sound (2026-03) |

#### Cytooxien reference

- [O] Most hits with a laser blaster wins.
  - Longer killstreaks give more coins.
  - The blaster recharges faster after a hit than after a miss.
  - Players can punch nearby opponents.
- [V c0by 2026-08]:
  - **Map:** an industrial yard with shipping containers, rails, catwalks and brick warehouses.
  - **Blaster:** a dark custom gun with a cyan core. Each shot draws a cyan beam.
  - **Chat:** "Minecraft Party: X hat eine Streak von 3/4 Kills" (X has a streak of 3/4 kills).
  - **Firing:** right-click shoots. A miss starts a cooldown; a hit allows an immediate re-shot.
  - **Tags:** a left-click (melee) tag also counts. Tagged players respawn ("I keep dying"). Night Vision is on.
- [V Grapfen] Strafe diagonally and never stand still; campers on rooftops are easy targets.

#### Level design

- **Arena:** 44×44 for 5–8 players; variants 32×32 (2–4) and 56×56 (9–12).
- **Levels:**

  | Level | Height | Content |
  |-------|--------|---------|
  | L0 | ground | Container lanes |
  | L1 | +3 | Container tops, connected by planks |
  | L2 | +6 | Catwalks / crane arms |

  Connected by ≥ 6 stairs or ladders and 2 jump pads (L0 → L1).
- **Sightline rules:**
  - The longest uninterrupted sightline is ≤ `range` (30). There are no corridors longer than 30.
  - Cover within 3 blocks for at least 60% of walkable floor.
  - No room with a single entrance. The high ground (L2) is fully exposed from at least 2 directions, so camping is risky [V].
- **Spawns:** 16 spawn points spread around the perimeter and mid-zones, at least 8 blocks apart.
- **Optional pickups** (v1.1): 3 pads in contested central spots.
- **Themes:**

  | Theme | Look |
  |-------|------|
  | Port | Containers, cranes, water edges as a boundary |
  | Factory | Conveyors, pipes, furnaces |
  | Neon | Dark blocks, glowing edges, Night Vision off for contrast |

```text
 ┌────────────── 44 ──────────────┐
 │ S   ▭▭▭   ▭▭     crane══════ S │   ▭ = container (L0 cover, L1 top)
 │     ▭▭▭        ▭▭▭  ║         │   ═ = L2 catwalk
 │ ▭▭      ⇡pad  ⊕       ▭▭▭     S│   ⊕ = optional pickup pad
 │ ▭▭  ▭▭▭▭       ▭▭▭▭     ⇡pad   │   S = spawn (16 total)
 │ S       ═══════════      ▭▭    │
 │   ▭▭▭    ⊕      ▭▭▭  ⊕    S    │
 └────────────────────────────────┘
```

#### Kit & HUD

- Slot 1: blaster (unbreakable, model `mcparty:laser_gun`, PDC tag).
- Effects: Night Vision (except on Neon). Adventure mode; no damage.
- Cooldown shown with the vanilla item cooldown overlay plus the XP bar as a charge meter.
- Sidebar metric: tags.
- Action bar: "Streak 3 · next +1 ⛁ at 5".

#### Timeline

| t | Event |
|---|-------|
| INTRO (5 s) | At spread spawns |
| GO | — |
| GO → GO+60 s | Play; streak announcements; at 10 s left the boss bar flashes |
| GO+60 s | End (`duration-seconds`) |

#### Rules & activities

1. **Shoot:**
   - Right-click, main hand, blaster ready.
   - Ray from the eye along the look direction: `World#rayTrace(eye, dir, range, FluidCollisionMode.NEVER, ignorePassable=true, raySize=0.2, filter = in-match, alive, not spawn-protected, not self)`.
   - Blocks stop the ray, so glass stops it too. Make windows open frames, not glass.
2. **Hit:**
   - Shooter +`hit-score` (1). Cooldown `cooldown-hit-ticks` (4) [V: "can shoot again immediately"].
   - Victim is **tagged**: a red particle burst and a "zap" sound. The victim is teleported after 10 ticks to the best spawn: the spawn that maximizes the minimum distance to alive enemies and is not in any enemy's line of sight within 15 blocks.
   - Spawn protection: `spawn-protection-ticks` (40). The victim can neither be tagged nor fire, and is shown with a translucent shield particle.
3. **Miss:** cooldown `cooldown-miss-ticks` (20).
4. **Tracer:** cyan dust particles every 0.5 blocks along the ray, plus a hit marker sound for the shooter.
5. **Melee tag:** left-click hit within vanilla reach. Same as a hit, but no cooldown change [O].
6. **Streaks:**
   - A streak counts tags made without being tagged.
   - Chat announces at 3, 5, 7… [V].
   - Coins are paid once per milestone reached within a streak: reaching 3 pays +1, reaching 5 pays +1 more, reaching 7 pays +1 more. There is nothing further beyond 7.
   - The match total is capped by `streak-bonus-cap` (3).
7. **Optional pickups** (`pickups-enabled: false` by default):

   | Pickup | Effect |
   |--------|--------|
   | Overcharge | 5 s, cooldowns halved |
   | Pierce | Next shot passes through 1 player |

#### Ranking & coins

- **Ranking:** tags (descending), then fewer times tagged, then the earlier time the final score was reached (v1: first/last scorer tie-break → earliest wins).
- **Coins:** placement + streak coins (cap `streak-bonus-cap` 3) [O].

#### Tech

| Area | Plan |
|------|------|
| Weapon | `World#rayTrace` hitscan, max `range`; the first in-match player hit (v1) |
| Events | `PlayerInteractEvent` (right-click), `EntityDamageByEntityEvent` (melee tag; cancel damage), all other damage cancelled, `PlayerQuitEvent` |
| State | `ScoreTracker` (tags, times tagged, last-score tick), per-player cooldown tick, streak, protection-until tick |
| Spawns | `ArenaSpawns.best(scope, player)`, using a cheap distance + `rayTraceBlocks` LOS check (≤ 16 spawns × 8 enemies) |
| UI | Sidebar, action bar, item cooldown, XP bar |
| Packets | Tracer and hit particles only; never fake cover (v1) |

#### Edge cases & cancel

- Ignore non-match entities and hits through the boundary (v1). Rays are clipped at the arena AABB.
- A disconnected player is ranked as a leaver by their tags at leave time (§3.7, v1).
- A shot fired on the same tick as the shooter gets tagged: process tags in event order, and a tagged shooter's pending shot is dropped.

#### Config

```yaml
minigame:
  laser_tag:
    arena: { template: laser_tag_port, spawn: {...}, boundary: {...} }
    duration-seconds: 60          # v1
    hit-score: 1                  # v1
    cooldown-miss-ticks: 20       # v1 (was 15)
    cooldown-hit-ticks: 4         # v1 (was 8)
    range: 30                     # v1
    streak-bonus-cap: 3           # v1
    ray-size: 0.2
    respawn-delay-ticks: 10
    spawn-protection-ticks: 40
    spawn-los-check-blocks: 15
    melee-tag: true
    streak-coins: { 3: 1, 5: 1, 7: 1 }   # milestone → coins paid on reaching it
    pickups-enabled: false
```

#### Divergences

- A tag forces a **respawn** (Cytooxien [V]) instead of v1's "short invulnerability window in place". This removes "trade" fights and rewards map movement.
- The cooldowns are adjusted (hit 4, miss 20 ticks) to match "re-shoot immediately after a hit".

---

### 6.8 King of the Hill — `king_of_the_hill`

**Fantasy:** Bridge your way up a chain of floating islands with a limited stack of blocks, punch rivals off your bridge, and hold the summit hill until it's yours.

| Field | Value |
|-------|-------|
| Length | 60–90 s |
| Foundations | `CheckpointTracker` (islands), `ScoreTracker` (hill ticks), `MatchHud` |
| Cytooxien analog | Kletterkönig (2023 name "Gipfelstürmer"); **removed from public rotation 2026-03**, private rounds only |

#### Cytooxien reference

- [O] Reach the far side without falling into the abyss. Players can place blocks, and others can punch them back. The first to arrive wins.
  - New map Frosty (2025-01).
  - Players can no longer be trapped (2025-01 fix).
  - Removed from rotation 2026-03.
- [V Grapfen 2023, "Gipfelstürmer"]:
  - A **vertical** climb between giant trees and structures over a sky void.
  - Each player holds **64 blocks of their own wool colour** (shown in hand).
  - Sidebar "Verbleibende Zeit 2:35" (remaining time), so a round is ≈ 3 min; the intro sidebar shows records ("Glanzleistungen", top performances).
  - Tip: enemy punches can knock you *forward/up*. Fighting while behind gains nothing.
- **Status note:** Cytooxien dropped this game from public rotation. McParty keeps it because v1's hill-capture twist (below) adds a finale the original lacked [D].

#### Level design

- **Launch islands:**
  - One per player (5×5 top, rocky underside), arranged on an arc 4 blocks apart, all facing the course.
  - Each is tinted with the player's wool colour.
- **Course:**
  - 4 checkpoint islands (C1–C4), each 7×7 to 9×9, rising **+3 to +6 Y** each step.
  - There are 5 gaps: start → C1 → C2 → C3 → C4 → summit. Each direct gap is 4–7 blocks, and the 5 gaps together need **24–28 blocks**. With the 32-block budget that leaves 4–8 spare for mistakes and blocking.
  - **Bridges are flat.** The +3 to +6 rise per island is built into the islands as natural stairs or ramps, so climbing costs no blocks.
  - Each gap has 2 routes:
    - A **direct bridge**: shortest, but the most blocks.
    - **Stepping stones**: 1×1 to 2×2 pillars that need jumps but fewer blocks.
- **Build corridors:**
  - One AABB per gap: 9 wide × 8 high, following the route.
  - Placement outside the corridors is denied, so nobody can build a private staircase into the sky.
- **Summit island:**
  - 15×15, with a raised hill (5×5 top, 2 blocks above the island) and stairs on 4 sides.
  - The capture zone is the AABB of the hill top (5×5×3). A beacon beam or `BlockDisplay` glow ring marks it.
- **Void:** below all islands. Template guidance: set `void-y` (config) about 8 blocks below the lowest island bottom.
- **Theme options:** Frosty (ice islands, snow; Cytooxien), Jungle canopy, Sky ruins.

```text
 side view
                                          ┌─hill─┐
                                   C4 ▄▄▄▄█summit█▄▄▄
                          C3 ▄▄▄▄   ·  ·
                 C2 ▄▄▄▄    ·
        C1 ▄▄▄▄   ═════  (direct bridge)  /  ▪ ▪ ▪ (stepping stones)
 starts ▄▄ ▄▄ ▄▄
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~ void ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
```

#### Kit & HUD

- Off-hand: `bridge-blocks` (32) of the player's own wool colour (v1 off-hand stack). Main hand: empty (punch).
- Survival mode with placing allowed and breaking denied (`BlockBreakEvent` cancelled).
- Melee: no damage, plus a manual knockback because the cancelled hit has none (§3.4).
- Boss bar: the leading capture, e.g. "Alice capturing 7/12 s", in Alice's colour.
- Action bar: "Blocks 18 · Hill 0/12 s".
- Sidebar metric: hill seconds, then checkpoint.

#### Timeline

| t | Event |
|---|-------|
| INTRO (5 s) | On the launch islands |
| GO | — |
| live | Bridge and climb; checkpoints recorded |
| first player at the summit | Title broadcast: "Alice reached the summit!" |
| capture | First player to `hill-hold-seconds` (12) uncontested wins; the game ends |
| `timeout-seconds` (90) | End. **Overtime:** if someone is currently capturing uncontested, extend until they lose the zone or finish, max `overtime-max-seconds` (10). |

#### Rules & activities

1. **Placing:** only player-colour wool from the off-hand, only inside a build corridor, and never inside the hill zone or above `max-build-y`.
2. **Anti-trap** [O fix]: deny a placement if the block's AABB intersects any other player's hitbox expanded by 1 block. Players cannot be boxed in.
3. **Falling into the void:** controlled by `void-mode`.
   - `respawn` (default [D]): 2 s spectator view, then teleport to the last checkpoint island. Blocks are **not** refilled (v1). The player can still use others' bridges.
   - `eliminate` (v1 behaviour): the player is eliminated and ranked by checkpoint.
4. **Others' bridges persist.** Followers can ride the leaders' bridges, which is the trade-off for being first.
   - Optional `bridge-decay-seconds` (0 = off) crumbles placed blocks N seconds after placement.
5. **Hill capture** (v1 rule):
   - Each player has their own progress, which ticks only while they are the **sole** occupant of the zone.
   - With 2 or more occupants the zone is contested: nobody progresses (a red particle ring shows this).
   - When a player leaves the zone, their progress resets to 0.
6. **Punching** is the only interaction. On the hill, knock rivals down the stairs to break their capture.

#### Ranking & coins

- **Ranking:**
  - Capturer first.
  - Then by total uncontested hill ticks (descending), then summit arrival time (ascending), then checkpoint reached (descending), then distance to the summit (ascending).
  - With `void-mode: eliminate`, eliminated players are ranked after all survivors by checkpoint (v1).
- **Coins:** placement + 1 coin per 4 s of total hill time (cap 3) [D].

#### Tech

| Area | Plan |
|------|------|
| Course | Prebuilt disposable clone with start islands, checkpoint islands and summit (v1) |
| Bridging | `BlockPlaceEvent` (new hook, §4): corridor AABB check, material = the player's wool, anti-trap check, `max-build-y` |
| Progress | `PlayerMoveEvent` (block change) for checkpoints and void; a 4-tick task for hill occupancy |
| Scoring | `ScoreTracker` hill ticks; the capture progress map |
| World | Disposable clone (v1) |
| UI | Boss bar (leader), action bar (self), sidebar |

#### Edge cases & cancel

- Deny placement outside the build corridor, into the hill or above the height limit (v1).
- A disconnected player loses hill progress and is placed after active players (v1).
- Running out of blocks gives no hidden refill (v1). The HUD hints "Use other players' bridges".
- The capturer disconnecting mid-capture resets that progress and contests nothing.

#### Config

```yaml
minigame:
  king_of_the_hill:
    arena: { template: koth_frosty, spawn: {...}, boundary: {...} }
    timeout-seconds: 90           # v1
    hill-hold-seconds: 12         # v1
    bridge-blocks: 32             # v1
    checkpoint-count: 4           # v1
    void-y: 0.0                   # v1
    void-mode: respawn            # respawn | eliminate
    respawn-delay-ticks: 40
    anti-trap-margin: 1.0
    max-build-y: 140
    bridge-decay-seconds: 0
    overtime-max-seconds: 10
    hill-coins: { per-seconds: 4, cap: 3 }
```

#### Divergences

- `void-mode: respawn` is the new default. v1 eliminated on void, but a 90 s race where an early fall ends your game is too punishing for a party mode, and Cytooxien's climb also lets fallers continue [V].
- The hill finale is ours [D]. Cytooxien ends the game at first arrival.

---

### 6.9 Block Jump — `hopper`

**Fantasy:** Your own chain of floating blocks, one jump at a time. Each landing reveals the next block. Plant a flag to be safe, but every flag slows your next block.

| Field | Value |
|-------|-------|
| Length | 45–75 s |
| Foundations | `CheckpointTracker` (jump index), `MatchHud`; game-local `JumpGenerator` |
| Cytooxien analog | Blockhüpfer, back in rotation since 2025-05 (visual update; shows remaining jumps) |

#### Cytooxien reference

- [O] Jump between blocks and master every jump. You can set checkpoints for safety, but each checkpoint **delays generating the next jump**. Since 2025-05 the game shows how many jumps remain to the goal.
- [V Marvelino 2025-09]:
  - **Chains:** every player has their **own chain of single blocks in their colour** (blue, red, yellow…), generated mid-air above a big arena (a city skyline around a terracotta bowl).
  - **Next-block marker:** a hologram **"3/24"** with a green down-arrow sits over the next block. The next block first appears as a white "forming" outline.
  - **Checkpoint item:** slot 1 holds a **red flag**; holding it shows "Checkpoint setzen" (set checkpoint).
  - **Pace:** the sidebar metric is jumps completed; leaders reached about 20 jumps in about 45 s. The total is **24 jumps**, and the round is ≈ 2 min (1:57 left early).
- [V Reddoons] "You can use a flag to save your position, but it slows down the block generation."

#### Level design

- **Arena:** 96×96 open volume, ~60 high, over a decorative "fall plane" (city / bowl).
  - Falling below `current block Y − 6` or touching the fall plane triggers a reset.
  - The fall plane is solid, so players land and are teleported. There is no void death.
- **Per-player chains:**
  - Chains are generated **from one seeded sequence per match** (identical difficulty for everyone).
  - Each chain is rotated or offset so chains never overlap: start pads sit on a circle of radius 24 around the arena center, and every chain is rotated by `360°/players`.
  - Chains move outward and around in a spiral so they stay inside the arena.
- **Jump count:** `jump-count` (24) [V].
- **Difficulty curve:** "gap" = air blocks between the edges; "Δy" = target top minus current top.

| Jumps | Tier | Allowed shapes |
|-------|------|----------------|
| 1–6 | Easy | (gap 1–2, Δy 0), (gap 1, Δy +1); straight |
| 7–14 | Medium | (gap 2–3, Δy 0 / −1), (gap 2, Δy +1); side offset ≤ 1 |
| 15–20 | Hard | (gap 3, Δy 0), (gap 2, Δy +1), diagonal (2 + side 1), (gap 3, Δy −2) |
| 21–23 | Expert | (gap 3, Δy 0, side 1), (gap 2, Δy +1, side 1), (gap 3, Δy −1, side 1) |
| 24 | Finish | Gold block 3×3 platform, fireworks |

- **Reachability rules** (validated per generated jump):
  - Standing start on a single block, sprint jump, no effects.
  - Max gap: Δy 0 ≤ 3, Δy +1 ≤ 2, Δy −1 ≤ 3, Δy −2 ≤ 3. In centre-to-centre terms, that is a distance ≤ 4, which is `max-horizontal-gap`.
  - No ceiling within 3 blocks above the takeoff or landing.
  - The landing block must be visible from the takeoff position.
  - These limits are stricter than momentum parkour on purpose: 1-block takeoffs give no run-up.
- **Block look:**
  - Each player's blocks use their colour's concrete.
  - The next block appears after `generate-delay-ticks` as a white glass `BlockDisplay` outline that "fills in" (scale 0.2 → 1.0) before the real block is set [V].
  - Already completed blocks stay. Blocks more than 3 behind fade to the colour's stained glass (cosmetic).

#### Kit & HUD

- Slot 1: **checkpoint flag** (model `mcparty:checkpoint_flag`) [V]. While held, the action bar says "Right-click: set checkpoint (+1.0 s delay)".
- Slot 9: reset (back to checkpoint, same as a fall).
- Adventure mode; no fall damage; collision `NEVER`. Players cannot reach other chains anyway.
- Hologram: a `TextDisplay` "n/24" + an arrow `ItemDisplay` (`mcparty:jump_marker`) above the next block, **visible only to the owner**.
- Sidebar metric: validated jumps. Finished players show ⭐ and their time.

#### Timeline

| t | Event |
|---|-------|
| INTRO (5 s) | On each player's start pad; jump 1 is already shown |
| GO | — |
| live | Jumping |
| first finish | `finish-grace-seconds` (15) |
| cap | `timeout-seconds` (75) |

#### Rules & activities

1. **Validated landing:** the player is standing on top of their **current target block**.
   - Feet Y within 0.05 of the block top, block coordinates match, and the player is not inside the block.
   - Being in the air above it does not count; landing from below is impossible by geometry.
   - The index advances, and the next block starts generating after `generate-delay-ticks` (6) **+ the checkpoint penalty, if one is active**.
2. **Checkpoint:**
   - Right-click the flag while standing on your latest validated block. That block becomes the checkpoint (a banner `BlockDisplay` on it).
   - Max `max-checkpoints` (3) per run.
   - **Penalty:** the generation of the *next* block is delayed by `checkpoint-delay-ticks` (20), applied once per checkpoint (v1).
3. **Fall or reset:**
   - Freeze `reset-freeze-ticks` (10), then teleport onto the checkpoint block, or the start pad if none.
   - `reset-time-penalty-seconds` (2): input stays frozen for the penalty.
   - The index returns to the checkpoint index.
   - Blocks after the checkpoint are **removed** (set to air) and come back one by one as the player re-validates. The same seeded positions are used, with `generate-delay-ticks` each time; checkpoint penalties are not charged again.
   - Jumps between the checkpoint and the fall point must be redone and are not counted twice (v1: "preserve validated progress" = up to the checkpoint).
4. **Finish:** landing on the finish platform records the time, plays fireworks and switches to spectator after 2 s.

#### Ranking & coins

- **Ranking:**
  - Finished players by time.
  - Unfinished players by current validated index (descending), then the tick that index was reached (ascending), then fewer resets.
- **Coins:** placement + `clean-finish-bonus-coins` (2) for finishing with no checkpoint and no reset (v1 idea).

#### Tech

| Area | Plan |
|------|------|
| Generation | `JumpGenerator(seed, tier table, rules) → List<Offset>`, validated per jump against the reachability rules; regenerate a jump if invalid. Per-player transform = rotation + translation. |
| Blocks | Real blocks (collision) set with no physics; `BlockDisplay` for the forming outline and banner. Disposable clone (v1). |
| Progress | `CheckpointTracker` holds the index, checkpoint index, checkpoints used and finish tick |
| Events | `PlayerMoveEvent` (block or Y change → landing / fall checks), `PlayerInteractEvent` (flag, reset) |
| Displays | Per-owner hologram via `setVisibleByDefault(false)` + `showEntity` |

#### Edge cases & cancel

- Do not validate a landing from outside the target top or after skipping the sequence (v1). Only the *current* target is ever solid ahead of the player, because future blocks do not exist yet.
- A checkpoint costs its delay only once (v1).
- A disconnected player is ranked as a leaver by their last validated index (§3.7, v1).
- `cancel()` clears displays and unloads the clone (v1).

#### Config

```yaml
minigame:
  hopper:
    arena:
      template: block_jump_arena     # v1
    jump-count: 24                   # v1 (was 20, under arena)
    max-horizontal-gap: 4            # v1 (was 5) — center-to-center blocks, flat
    max-vertical-gap: 1              # v1 (was 3) — max upward Δy per jump
    timeout-seconds: 75              # v1
    checkpoint-delay-ticks: 20       # v1
    max-checkpoints: 3               # v1
    reset-time-penalty-seconds: 2    # v1
    generate-delay-ticks: 6
    reset-freeze-ticks: 10
    finish-grace-seconds: 15
    start-radius: 24
    clean-finish-bonus-coins: 2
```

#### Divergences

- `jump-count` goes 20 → 24 to match Cytooxien [V].
- The gap limits are tightened to what is reliably reachable from a single block. The v1 values (5 horizontal, 3 vertical) would generate impossible jumps.

---

### 6.10 Mini Skywars — `mini_skywars`

**Fantasy:** Loot your island, bridge to the center for better gear, and be the last player above the void while the border closes in.

| Field | Value |
|-------|-------|
| Length | 90 s |
| Foundations | `EliminationTracker`, `ScoreTracker` (kills), `MatchHud`; game-local loot tables |
| Cytooxien analog | Mini Skywars (added 2025-05, map Oasis) / Skywars (map Wild West since 2026-03) |

#### Cytooxien reference

- [O] Move between islands, loot chests and defeat opponents.
  - A shrinking border reduces the play area.
  - Last standing wins; at timeout, the most kills wins.
  - Kill levels upgrade gear at the **central enchanting table**.
  - **Building too high breaks the blocks and deals damage.**
- [O 2025-05] Kills award coins **instantly**. Map Oasis; Wild West removed then (and returned 2026-03).
- [V c0by 2026-08, Wild West]:
  - Map: sand and terracotta islands with cacti and mesa spires.
  - Kit and loot: a starting wooden sword and a stack of bricks; chests on islands; a "good chest in the middle".
  - Play: block clutches, and a lava bucket from loot used offensively.
  - Chat warns "the game ends in 30 seconds"; the red world-border stripes close in.
  - Eliminated players spectate. Helmets are visible.
- [V Gustaf] Height limit; stalling until the timeout works; the sidebar shows your live placement.
- [V Grapfen] Tips: loot an empty neighbour island; avoid fighting on edge islands; gear up first, then go to the middle.

#### Level design

- **Player islands:**
  - 8 on a ring of radius 28 around the center, at the same Y. The 12-player variant uses radius 39 with 6 satellites at radius 22.
  - Each island: 7×7 top, 3–4 layers of tapered rock underneath, 1 chest, 1–2 decorations (cactus, barrel, cart).
  - Spawn in a 1×1 glass cage, removed at GO.
- **Satellites:** 4 mini islands (5×5, 1 chest each) at radius 16, between every second pair of player islands.
- **Center island:**
  - 15×15 on 2 levels.
  - 4 tier-2 chests on the ground.
  - Upper platform (+4) with a tier-3 supply chest, an enchanting table and 8 bookshelves.
- **Gaps** (edge to edge, from this geometry): player island → satellite ≈ 8–9 blocks; satellite → center ≈ 6; player island → neighbour ≈ 13–14. Templates should stay within ±1 of these.
- **Height cap:** center Y + 18. Blocks placed above it break after 20 ticks and deal 4 damage to the placer [O].
- **Void:** below the islands; kill-Y = lowest island bottom − 10.
- **Border:** the clone world's own vanilla `WorldBorder`, which is **square**. Full width 96 at the start (matching the ±48 boundary), shrinking from `border-shrink-start-seconds` (45) to width 20 by `duration-seconds`. Native damage applies: `damage-amount` per block beyond `damage-buffer` (0), per second.

```text
                P           P
          P         s           P        P = player island (chest)
                                         s = satellite (chest)
        s        ┌───────┐        s      C = center (4× T2, T3 supply,
    P            │   C   │            P       enchanting table)
        s        └───────┘        s
          P                     P
                P           P
        □ border width 96 → 20 (45 s → 90 s after GO)
```

#### Kit & HUD

- Start kit: wooden sword, 32 theme blocks (bricks or terracotta), 1 golden apple. No food items: hunger is locked full (§3.4), so natural regeneration still works.
- Survival mode; PvP on after grace; fall damage on.
- Sidebar: alive count, kills, own placement.
- Boss bar: "Grace 0:15" → "PvP" → "Border shrinking".

#### Loot tables ([D], weights)

| Tier | Rolls | Items |
|------|-------|-------|
| T1 (island) | 4–5 | Stone sword 40, iron chestplate 30, iron helmet 25, 24 blocks 60, snowballs ×6 30, bow + 6 arrows 15, golden apple 20, water bucket 15, lava bucket 10, iron boots 25 |
| T2 (satellite, center) | 4–6 | Iron sword 45, iron leggings 35, diamond helmet 15, 32 blocks 50, bow + 12 arrows 30, ender pearl 25, golden apple ×2 30, lava bucket 15, Fire Aspect book 10 |
| T3 (supply) | 3 | Diamond sword 50, diamond chestplate 50, Power II bow + 16 arrows 40, golden apple ×3 40, ender pearl ×2 30 |

Chests are filled at PREPARE. They are part of the clone, so nothing persists.

#### Timeline

| t | Event |
|---|-------|
| INTRO (5 s) | In the cages |
| GO | Cages open |
| GO → GO+15 s | **Grace** (`grace-seconds` 15): PvP off, loot and bridge |
| GO+15 s | PvP on (title + sound) |
| GO+45 s | Border starts shrinking (`border-shrink-start-seconds`) |
| GO+60 s | Chat: "The game ends in 30 seconds" [V] |
| GO+90 s | End (`duration-seconds`) |

#### Rules & activities

1. **Lethal damage:**
   - When a hit would bring health to ≤ 0, cancel it.
   - Drop the victim's inventory at their location as real items, so it can be looted.
   - The victim becomes a spectator. The killer gets credit, `kill-bonus-coins`, and `levels-per-kill` (3) XP levels for the enchanting table [O].
2. **Void fall:** credit goes to the last player who damaged the victim within 8 s.
3. **Enchanting:** the vanilla table at center, using levels from kills only (start at 0). Every T2 and T3 chest has a guaranteed lapis ×4 on top of its rolls.
4. **Height cap:** see level design.
5. **No respawns.**

#### Ranking & coins

- **Ranking:**
  - The last alive is 1st, then reverse elimination order.
  - At timeout, survivors are ranked by kills (descending), then health (descending), then distance to center (ascending) (v1).
- **Coins:** placement + `kill-bonus-coins` (1) per kill, capped by `kill-bonus-cap` (3) so combat doesn't outweigh placement (v1).

#### Tech

| Area | Plan |
|------|------|
| Arena | Disposable floating-island clone (v1) |
| Loot | Tier tables from config, rolled with a seeded RNG at PREPARE |
| PvP | No `protectFromDamage()`. A victim-side `EntityDamageEvent` hook for the lethal check (new, §4), `EntityDamageByEntityEvent` for kill credit, projectile hits, `BlockPlaceEvent` (new hook: height cap, boundary), void check on move |
| Border | `WorldBorder` of the clone world; damage settings via the API |
| State | `EliminationTracker`, kill counts, last-damager map with timestamps |
| Cleanup | Unload the clone; never journal player edits (v1) |

#### Edge cases & cancel

- Clear dropped items and projectiles before unload (v1).
- A disconnected player is eliminated, and their items are discarded with the clone (v1).
- Deny block placement outside the arena boundary (v1).
- Lava and fire never spread outside the boundary: cancel `BlockSpreadEvent` and `BlockIgniteEvent` outside it.

#### Config

```yaml
minigame:
  mini_skywars:
    arena:                                   # v1
      template: mini_skywars_wildwest
      spawn-radius: 0.0
      boundary: { minX: -48, minY: 40, minZ: -48, maxX: 48, maxY: 160, maxZ: 48 }
    grace-seconds: 15                        # v1
    duration-seconds: 90                     # v1
    border-shrink-start-seconds: 45          # v1
    kill-bonus-coins: 1                      # v1
    kill-bonus-cap: 3
    levels-per-kill: 3
    border: { start-size: 96, end-size: 20, damage-amount: 1.0, damage-buffer: 0.0 }   # full width (square)
    height-cap-above-center: 18
    height-cap-damage: 4.0
    void-credit-seconds: 8
    loot: { t1: {...}, t2: {...}, t3: {...} }  # tables as in §6.10
```

#### Divergences

- None in the rules: v1 already matched the reference. Kill levels for the enchanting table and the height-cap damage are added from [O].

---

### 6.11 Warden Escape — `warden_escape`

**Fantasy:** Sneak through a buried ancient city while blind Wardens hunt by sound. Every sprint, jump and sensor you trip draws them closer. Throw snowballs to lure them away, and be the first through the ancient gate.

| Field | Value |
|-------|-------|
| Length | 75–90 s |
| Foundations | `CheckpointTracker`, `TrackProgress`, `EliminationTracker`, `CoinPickupService`, `MatchHud`; game-local `NoiseSystem`, `WardenDirector` |
| Cytooxien analog | None. McParty original [D], built from two Cytooxien escape patterns plus vanilla Warden rules. |

#### Reference

- **Dragon Escape / Drachenflucht** [O/V]:
  - A chaser destroys the path behind the players and speeds up over time.
  - Coins and power-ups lie along the route. Players can throw or place bombs at others.
  - The dragon can be **heard approaching**.
  - Ranking is by distance survived.
- **Nether Escape / Höllenschacht** [O 2026-03]:
  - A rising threat (lava).
  - The first player to reach the surface wins; if everyone is caught, the highest player wins.
- **Vanilla Warden** (Minecraft 1.19+):
  - Blind. It detects vibrations: steps, jumps and landings, block place/break, and projectiles landing.
  - **Sneaking** emits no step vibrations, and walking on **wool or carpet** (`dampens_vibrations`) emits none either. Wool blocks also occlude vibrations.
  - Anger runs 0–150; at ≥ 80 it attacks the target.
  - Melee hits for ~30 on Normal. The ranged sonic boom ignores armor.
  - Inflicts Darkness pulses.

#### Level design

- **Course:** a compact Ancient-City-style path, **140–180 blocks** from entrance to exit.
  - Corridors 10–20 wide, ceilings 6–14.
  - Deepslate bricks, sculk patches, soul lanterns at low light.
- **Checkpoints:** 5 zones, each ending in a checkpoint plane:

| Zone | Name | Length | Layout | Threat | Quiet route vs loud route |
|------|------|--------|--------|--------|---------------------------|
| Z0 | Entrance Hall | 15 | Per-player start alcoves (gates); a wool-carpet tutorial strip | None (opening phase) | — |
| Z1 | Sensor Gallery | 30 | Floor dotted with sculk sensors | Sensors add noise | Winding carpet path (+40% length) vs straight sculk floor |
| Z2 | Patrol Hall | 40 | Pillared hall; Warden A patrols 4 waypoints; side alcoves for hiding | Warden A | Side corridor (longer, dark) vs central nave (short, open) |
| Z3 | Shrieker Bridge | 25 | Bridge over a chasm, lined with sculk shriekers | A shrieker step triggers Darkness for all + alerts Warden B | Narrow 1-wide beam (no shriekers) vs 3-wide bridge (shriekers) |
| Z4 | Gate Chamber | 30 | Wide room before the exit; Warden B guards it; 4 echo alcoves (distraction targets) | Warden B | Wool-lined crawl ledge vs straight run |
| Exit | Ancient Gate | — | Reinforced-deepslate portal frame with a light curtain; the exit plane | — | — |

- **Chasm (Z3):** falling sends the player back to the Z2 checkpoint with noise +40. It is not an elimination.
- **Echo shards:** 12–16 coin-like pickups (an `ItemDisplay` of the vanilla `ECHO_SHARD` item; no custom model) placed on the **loud** routes, so the risk pays [D, Cytooxien "coins along the track" pattern].
- **Light:** most areas sit at light level 0–4, so Night Vision is *not* given. Exit beacons and lanterns provide orientation. Darkness pulses add pressure.
- **Markers:**
  - Start alcoves (12).
  - Checkpoint planes (5) and the exit plane.
  - Warden spawns (A, B, optional C).
  - Patrol waypoints per Warden.
  - Echo shard spots.
  - Shrieker blocks (as markers; their behaviour is plugin-driven).

```text
 [Z0 starts]→ Z1 sensors ··· carpet path ···→ Z2 patrol hall (W-A ↻ 4 waypoints)
                                              │ side corridor │ nave │
                                              ▼
           Z3 shrieker bridge ═══ (beam ─── quiet) over chasm
                                              ▼
           Z4 gate chamber (W-B guards; echo alcoves ◌ ◌ ◌ ◌) → ▓ ANCIENT GATE ▓
```

#### Kit & HUD

- Slot 1: `distraction-items` (8, v1) snowballs with the `mcparty:noise_maker` model.
- No weapons. Adventure mode; PvP off; collision `NEVER`; **name tags hidden** (stealth).
- Action bar **noise meter**: `Noise ▮▮▮▯▯▯▯▯▯▯ 32`, coloured green / yellow / red. A heartbeat sound plays above 60.
- Boss bar: zone name + time ("Patrol Hall · 0:48").
- Sidebar metric: checkpoint reached, no exact positions. Avoid revealing everyone's location (v1).

#### Noise system [D]

Each player has noise from 0 to 100. A task updates it every 5 ticks.

**Noise sources** (sprinting and jumps also come from move/stat checks):

| Source | Noise |
|--------|-------|
| Standing still or sneaking (any surface) | −10/s |
| Walking on wool or carpet | −6/s |
| Walking on any other block | +2/s |
| Sprinting (any surface) | +8/s |
| Jump or land | +8 |
| Sculk sensor activation (`BlockReceiveGameEvent`, entity = player) | +15 |
| Shrieker step | +40, **plus Darkness 4 s for everyone and Warden B alerted** |
| Chasm fall | +40 |

**Thresholds** (act on the nearest Warden within 32 blocks):

| Noise | Warden reaction |
|-------|-----------------|
| ≥ 40 | `setDisturbanceLocation(player location)`: the Warden investigates |
| ≥ 75 | `setAnger(player, 80)`: chase |
| 100 | `setAnger(player, 150)`: locked on. After 4 s the anger is set to 60 (investigating) unless the noise is still ≥ 75. |

**Calming:** when the player's noise falls below 30, reduce that Warden's anger toward them by 20 per second (`setAnger` to the reduced value) so stealth can shake a chase.

**Vanilla anger is suppressed.** Real Wardens also gain anger from vibrations and sniffing on their own. `WardenDirector` cancels Paper's `WardenAngerChangeEvent` unless the change is its own: a guard flag is set around its `setAnger` calls. The noise meter is therefore the only thing that drives the Wardens.

#### Wardens

- **Count:** `warden-count` (2), +1 at `extra-warden-at-players` (7) or more players. The 12 start alcoves allow up to 12 players. All are match-owned and PDC-tagged.
- **Spawn:** at the end of the opening phase. Darkness pulse, emerge sound, and a title "The Wardens are awake".
- **Patrol:** when a Warden has no disturbance for 3 s, `setDisturbanceLocation(next waypoint)` makes it walk the patrol loop.
- **Contact:** any Warden damage to a player (melee or `SONIC_BOOM`) is cancelled. Then:
  - `eliminate-on-warden-hit: true` (v1): the player is eliminated.
  - `false`: teleport to the last checkpoint, noise reset to 50, Blindness 3 s.
- **Invulnerable:** all damage *to* Wardens is cancelled; killing a Warden is never a shortcut (v1).
- **Targeting:** `EntityTargetEvent` restricts Wardens to alive in-match players. Never target spectators or outsiders.
- **No digging away:** Wardens are removed only by the plugin at END or cancel.

#### Timeline

| t | Event |
|---|-------|
| INTRO (5 s) | In the start alcoves (gates closed) |
| GO | Gates open |
| GO → GO+8 s | **Opening phase** (`opening-seconds` 8): no Wardens; learn the carpet routes |
| GO+8 s | Wardens spawn |
| live | Sneak, lure, run |
| GO+90 s (`timeout-seconds`) | End |
| early end | Everyone escaped or eliminated |

#### Rules & activities

1. **Distraction:** when a snowball lands, every Warden within `lure-radius` (24) is lured to the landing point.
   - First, any anger above `lure-anger-cap` (60) is lowered to 60. Vanilla ignores disturbances while anger is ≥ 80.
   - Then `setDisturbanceLocation(landing point)` is applied.
2. **Checkpoints** are sequential planes. Exiting through the gate plane after all checkpoints records the finish time; the player becomes a spectator after 2 s.
3. **Echo shards:** +1 coin each, bonus cap 5.
4. **Breaking or placing blocks** is denied. The course is fixed.

#### Ranking & coins

- **Ranking:**
  - Escaped players by exit time.
  - Alive non-escaped players by checkpoint (descending), then remaining distance to the next checkpoint (ascending).
  - Eliminated players by checkpoint at elimination (descending), then elimination time (later is better).
- **Coins:**
  - Placement + echo shards (cap 5).
  - +2 **silent escape**: escaping without the player's noise ever reaching 75 (v1 "no warning threshold" bonus).

#### Tech

| Area | Plan |
|------|------|
| Arena | Disposable deep-dark clone with fixed entrances, checkpoints, exit, wool routes and sculk (v1) |
| Wardens | `World#spawn(Warden.class, ...)`, `setPersistent(false)`, PDC match tag; `setDisturbanceLocation`, `setAnger`, `clearAnger` |
| Noise | Per-player float; 5-tick task; sources from `PlayerMoveEvent` (sneak/on-ground transitions) plus new hooks (§4): `PlayerToggleSprintEvent`, Paper `PlayerJumpEvent`, `BlockReceiveGameEvent` (sensors); marker-block steps (shriekers) |
| Events | Move/checkpoint/finish, `ProjectileHitEvent` (snowballs), victim-side damage hook (Warden → player), tracked-entity hooks for Wardens (`EntityDamageEvent` cancel, `EntityTargetEvent`, `WardenAngerChangeEvent`; new, §4), `onQuit` |
| State | `CheckpointTracker`, `EliminationTracker`, noise map, max-noise map (for the silent bonus), finish ticks |
| UI | Action bar noise meter; boss bar; warning sounds and titles; exit beacon (v1) |

#### Edge cases & cancel

- Remove all match-owned Wardens, projectiles and effects before unload (v1).
- Cancel must restore players even if a Warden is attacking them (v1). `clearAnger` comes first, then the teleport.
- On disconnect, `clearAnger(player)` on every Warden immediately (v1), and the player is eliminated as a leaver.
- Wardens stuck more than 6 s (no movement) are teleported to their nearest patrol waypoint.

#### Config

```yaml
minigame:
  warden_escape:
    arena:                                  # v1
      template: warden_escape_arena
      checkpoint-count: 5
    timeout-seconds: 90                     # v1
    warden-count: 2                         # v1
    extra-warden-at-players: 7
    distraction-items: 8                    # v1
    eliminate-on-warden-hit: true           # v1
    opening-seconds: 8
    noise:
      sprint-per-second: 8
      walk-per-second: 2
      walk-dampened-per-second: -6
      jump-land: 8
      sensor: 15
      shrieker: 40
      still-or-sneak-per-second: -10
      lock-seconds: 4
      lock-release-anger: 60
      investigate-at: 40
      chase-at: 75
      lock-at: 100
    lure-radius: 24
    lure-anger-cap: 60
    echo-shard-coins: 1
    echo-shard-cap: 5
    silent-escape-bonus-coins: 2
    bonus-coin-cap: 7
```

#### Divergences

- The whole game is [D]; there is no Cytooxien counterpart.
- The noise meter replaces relying only on vanilla sculk behaviour. That keeps the game readable and controllable in 90 s, while native sensors still feed it.

---

### 6.12 Antwar — `antwar`

**Fantasy:** You're an ant queen's last worker, sealed in solid stone. Dig for ore to arm yourself, find the rival burrows, and break their queen cores before they break yours.

| Field | Value |
|-------|-------|
| Length | 120 s (exception to the 45–90 s rule) |
| Foundations | `EliminationTracker`, `ScoreTracker`, `MatchHud`; game-local `OreField`, `QueenCore` |
| Cytooxien analog | Minengefecht (Mine Battle), in rotation [I]. McParty adds queen cores (v1). |

#### Cytooxien reference (Mine Battle)

- [O] Players start trapped underground with a pickaxe.
  - **Iron ore → equipment, coal → explosives, gold ore → coins.**
  - Gold ore in Mine Battle drops bonus coins (2025-05).
- [V Marvelino 2025-09]:
  - **Terrain:** a solid stone mass with red (redstone) and gold ore veins. Ores show highlighted outlines.
  - **Ore reward:** mining one ore type popped **"+1 ♥"** (max hearts go up; the transcript mentions up to 12 hearts).
  - **Reveal phase:** Night Vision is on. The boss bar first reads "Spieler werden in Kürze sichtbar…" (players will soon be visible), then "Spieler sind nun sichtbar" (players are now visible).
  - **Sidebar & hotbar:** the sidebar metric is kills; the hotbar fills with sword, pickaxe, armour and TNT; chat posts kill messages. The round is ≈ 2:30.
- [V Reddoons] "Just mine, then you get stuff". Random gear drops (boots, leggings, iron sword), then fighting; the last player standing wins.

#### Level design

- **Volume:** 64 × 64 × 20 (x, z, y; block range −32…31, Y 40…59) of solid stone/deepslate/tuff with ore veins, enclosed by an unbreakable shell (bedrock + barrier).
- **Ore distribution** by ring (distance from center), ≈ 6% of the volume, veins of 2–5 blocks:

| Ring | Radius | Ores |
|------|--------|------|
| Outer | 20–32 | Coal, copper, iron (common) |
| Middle | 10–20 | Iron, gold, redstone |
| Center | 0–10 | Diamond, amethyst (scout); a pre-carved **neutral cavern** 9×9×5 with 2 regenerating diamond nodes |

- **Burrows:**
  - Up to 12 (one per player), on a circle of radius 26, evenly spaced. With 12 burrows, neighbours are about 13.6 blocks apart.
  - Each burrow is a carved chamber 5×5×3 with the **queen core** against the back wall and a 2×3 entrance tunnel pointing toward the center, 6 blocks long and ending in solid stone.
- **Burrow protection** (`burrow-region`: 7×7×5 around the chamber):
  - During the dig phase only the owner may break blocks inside it.
  - Afterwards anyone can, except the core block itself, which is never broken; it takes damage instead.
- **Queen core:**
  - A 1-block core (`queen-core-material`, default `RESPAWN_ANCHOR`) marked by a floating `ItemDisplay` with the `mcparty:queen_core` model.
  - A `TextDisplay` above it: "♛ Alice ███████░░░ 14/20".

```text
 top view (y-slice)
        B           B           B = burrow (core ♛ at back, tunnel → center)
    B                   B
            ┌─────┐
  B         │ ◆◆  │         B   ◆ = diamond node in neutral cavern
            └─────┘
    B                   B
        B           B
 outer: coal/copper/iron · middle: iron/gold/redstone · center: diamond/amethyst
```

#### Ore rewards (instant, no shop) [O + D]

| Ore | Reward on break | Source |
|-----|-----------------|--------|
| Coal | +1 TNT (placed TNT auto-primes, fuse 40 ticks) | [O] coal → explosives |
| Iron | Next step of the gear ladder: iron sword → iron chestplate → iron leggings → iron boots → iron helmet → shield | [O] iron → equipment |
| Gold | +2 bonus coins | [O] |
| Redstone | +1 max heart (max 12 hearts) | [V] |
| Copper | Heal 4 HP + Regeneration I for 3 s | v1 (copper → healing) |
| Diamond | Upgrade: diamond sword, then diamond chestplate, then Efficiency II on the pickaxe | v1 (top tier) |
| Amethyst | +1 extra **Scout Pulse** item (see rule 6) | v1 (scout pulse) |

**Regeneration:** a mined ore turns to air (it's a mine). After `ore-regeneration-seconds` (8), the **same ore type** is placed into a random stone block of the same ring that has **no adjacent air** (hidden).

#### Kit & HUD

- Slot 1: stone sword. Slot 2: iron pickaxe (Efficiency I). Later slots fill with TNT and scout items. Armour is auto-equipped.
- Effects: Night Vision ∞.
- Survival mode; PvP after the dig phase; fall damage on.
- Boss bar: "Dig phase · players visible in 0:12" → "Players are now visible" → time left.
- Sidebar metric: kills, plus own core HP.
- Action bar: "+1 ♥" / "Iron chestplate!" / core alerts ("Your queen is under attack!").

#### Timeline

| t | Event |
|---|-------|
| INTRO (5 s) | Each player inside their burrow |
| GO | — |
| GO → GO+25 s | **Dig phase** (`preparation-seconds` 25): PvP off, cores invulnerable, players **not** glowing, other burrows protected |
| GO+25 s | **Reveal**: every player gets Glowing for the rest of the match [V]; PvP on; cores damageable |
| GO+100 s | Final 20 s: cores lose 1 HP every 2 s (sudden death) |
| GO+120 s | End (`duration-seconds`) |

#### Rules & activities

1. **Mining:**
   - `BlockBreakEvent` is allowed only for mine materials and ores inside `resource-region`, and never on the shell or a core.
   - There are no drops; rewards are granted directly.
   - Each ore position is in an `activeOres` set and is consumed **once** before regeneration is scheduled. This prevents duplication (v1).
2. **TNT:**
   - Placed TNT becomes a primed TNT entity (source = player).
   - The explosion breaks mine blocks in radius 2 and deals 6 damage to players within radius 3.
   - It deals **4 damage to any core block within radius 3** of the TNT.
   - It never breaks the shell or other burrows' core blocks (filter `blockList`).
3. **Core damage:**
   - A non-owner punching or mining the core block deals 1 damage, with a 10-tick cooldown per attacker. TNT deals 4.
   - Owners cannot damage their own core.
   - The core has `core-health` (20) HP.
4. **Core destroyed:** the owner is **eliminated immediately** (v1), even while alive, and becomes a spectator. Their items are removed.
5. **Death while the core is alive:**
   - The player respawns in their burrow after 3 s, **once** (`respawn-lives` 1, v1).
   - They lose their top gear step and all TNT.
   - A second death eliminates them.
6. **Scout pulse:**
   - Every player receives a free Scout Pulse every `scout-pulse-seconds` (20, v1 interval); amethyst gives extra ones.
   - Right-click to make every enemy glow for the user only, for `scout-glow-seconds` (5). Before the reveal it is the only way to find players. After the reveal it shows enemy core positions instead, as `BlockDisplay` outlines for 5 s.

#### Ranking & coins

- **Ranking:**
  - The last player alive with a core is 1st.
  - At timeout, alive players are ranked by core HP (descending), then kills (descending), then mined ore value (descending).
  - Ore values: coal 1, copper 1, iron 2, redstone 2, gold 3, amethyst 3, diamond 5.
  - Eliminated players are ranked in reverse elimination order.
- **Coins:** placement + gold-ore coins + 1 per kill. Bonus cap `bonus-coin-cap` (6), so mining cannot outweigh placement (v1).

#### Tech

| Area | Plan |
|------|------|
| Arena | Disposable mine clone; generated ore veins stored as markers or produced from the seed at PREPARE |
| Mining | `BlockBreakEvent` (allow-list + regions), `setDropItems(false)`, `activeOres` set, regeneration task |
| TNT | `BlockPlaceEvent` (new hook, §4) → cancel + spawn `TNTPrimed` with source, registered with `scope.track`; `EntityExplodeEvent` filter + core damage calculation |
| Cores | `QueenCore{owner, pos, hp}`; damage via `PlayerInteractEvent(LEFT_CLICK_BLOCK)` (routed today) or a new `BlockDamageEvent` hook, with cooldown; `TextDisplay` HP bar |
| Glow | Reveal uses `setGlowing(true)`. Paper has no per-viewer glow API, so the scout glow sends the entity metadata glowing flag to the scout through PacketEvents (already used by holograms and seamless world change). Without PacketEvents, scout pulses fall back to core outlines only. |
| Combat | Lethal-damage interception through the victim-side damage hook (same as Mini Skywars), respawn handling, kill credit |
| State | `ScoreTracker` (kills, ore value), `EliminationTracker`, lives map |
| Cleanup | Unload the clone; tunnels, TNT and displays never leak (v1) |

#### Edge cases & cancel

- Deny block changes outside the mine, build and core regions (v1).
- A disconnected player loses their queen and is eliminated; their drops are removed (v1).
- Prevent resource duplication by consuming the node before regenerating it (v1).
- A TNT chain near the shell only affects mine materials.
- Two cores reaching 0 on the same tick: rank the owners by kills, then mined ore value.

#### Config

```yaml
minigame:
  antwar:
    arena:                                   # v1
      template: antwar_arena
      resource-region: { minX: -32, minY: 40, minZ: -32, maxX: 31, maxY: 59, maxZ: 31 }   # v1 (was ±48, y 40..100)
    preparation-seconds: 25                  # v1 (was 20)
    duration-seconds: 120                    # v1 (was 90)
    respawn-lives: 1                         # v1
    ore-regeneration-seconds: 8              # v1
    scout-pulse-seconds: 20                  # v1 — free pulse interval
    scout-glow-seconds: 5
    burrow-count-max: 12
    burrow-radius: 26
    core-health: 20
    core-hit-cooldown-ticks: 10
    tnt: { fuse-ticks: 40, radius: 2, player-damage: 6.0, core-damage: 4 }
    sudden-death: { last-seconds: 20, core-loss-every-ticks: 40 }
    max-hearts: 12
    ore-weights:
      outer:  { COAL_ORE: 35, COPPER_ORE: 30, IRON_ORE: 35 }
      middle: { IRON_ORE: 40, GOLD_ORE: 30, REDSTONE_ORE: 30 }
      center: { DIAMOND_ORE: 60, AMETHYST_BLOCK: 40 }
    ore-density: 0.06
    bonus-coin-cap: 6
```

#### Divergences

- The length is raised to 120 s and the dig phase to 25 s. Mine Battle runs ≈ 150 s [V], and 90 s left no time to raid.
- There is no shop GUI; v1 planned "keep the v1 shop small". Ore rewards are instant, as in Cytooxien. This is simpler and faster to read mid-fight.
- `scout-pulse-seconds` keeps its v1 meaning (an interval). The glow duration is the new `scout-glow-seconds`, and amethyst gives extra pulses.
- The resource-region example is resized to match the 64×64×20 volume.

---

## 7. Coin & placement model

This model is shared by all twelve games and stays config-driven (code):

```yaml
minigame:
  dummy-coin-rewards: [20, 16, 14, 12, 10, 9, 8, 7]  # code — 1st..8th; ranks beyond the table get 1 coin
```

- Always set a placement for every participant still in the party at resolve time (v1).
- Offline mid-minigame: ranked as a leaver (§3.7).
- **Bonus coins** are added on top in the same coin map. Each game's sources and caps:

| Game | Bonus source | Cap |
|------|--------------|-----|
| `hot_potato` | Explosion coins collected | 6 |
| `speed_race` | Track coins + clean run | 10 |
| `spleef` | — | 0 |
| `elytra_race` | Ring center accuracy + clean run | 20 |
| `shooting_range` | Golden targets + accuracy | 8 |
| `color_chaos` | — | 0 |
| `laser_tag` | Streaks | 3 |
| `king_of_the_hill` | Hill time | 3 |
| `hopper` | Clean finish | 2 |
| `mini_skywars` | Kills | 3 |
| `warden_escape` | Echo shards + silent escape | 7 |
| `antwar` | Gold ore + kills | 6 |

- **Board interaction:**
  - **Double Coins** (shop item, code) doubles "coins earned in the next minigame" [O]: the player's total in the coin map, so the result contract needs no split. A result without coins keeps it armed.
  - A placement/bonus breakdown is only needed if the end screen should show it. In that case add an optional `bonusCoins` map (a localized change in `MinigameResult` + `PartyInstance`, not a foundation change).
- **Economy (decided):** Cytooxien scale. Shop prices are Double Coins 30, Chaos Die 40, Warp Pipe 100 [O] plus bronze/silver/gold dice 20/35/50 [D] (`board.shop.prices`), and the placement table is `[20, 16, 14, 12, 10, 9, 8, 7]` (a 7th–8th place earned 14–15 coins at Cytooxien [V]). Bonus-coin caps in §6 stay as written; retune them against these prices after playtests.

---

## 8. Power-up catalog (shared `PowerUpBox`)

### Rules (all games)

`PowerUpBox` (code) is the pickup for every game. It follows Cytooxien's 2026-03 rules [O]:

- **Spawn:** a tilted translucent box (`mcparty:power_up_box`) spinning around a camera-facing question mark (`mcparty:power_up_question`), both non-persistent full-brightness `ItemDisplay`s. The box never shows what is inside.
- **Pickup:** touch only. The player's bounding box must overlap the box's AABB (`scale` blocks wide), checked every tick; spectators and dead players are skipped. **Projectiles never pick up** [O].
- **Roll:** touching rolls one type from the game's weights. Spleef rolls inside its own enum today; v2 moves the weighted roll and the hotbar insert into a shared helper next to `PowerUpBox`.
- **Holding:** several power-ups at once [O]. Each type takes the first free hotbar slot (the game tool keeps slot 1), and duplicates stack.
- **Use:** right-click with the main hand. Each held item has its own model (for example `mcparty:throwing_tnt`, `mcparty:sugar_rush`, `mcparty:speed_boost`). 10-tick global use cooldown per player. The action bar confirms the use.
- **Lifetime:** `respawnTicks ≤ 0` makes a box one-shot (Spleef); otherwise it reappears after the delay. A box never despawns before pickup or the end of the match [O fix for Color Chaos].
- **Cleanup:** displays die with the scope (`onClose`). Projectiles, creepers and primed TNT are registered with `scope.track`, so they are removed too.

### Catalog

| Id | Name | Games | Effect | Duration / numbers [D] |
|----|------|-------|--------|------------------------|
| `speed_boots` | Speed Boots | `hot_potato` | Speed II + boot particles | 6 s |
| `time_freeze` | Time Freeze | `hot_potato` | Pauses the global potato fuse; the boss bar turns blue, "TIME FROZEN" | 5 s [O] |
| `tnt` | TNT | `spleef` (code) | Throwable primed TNT; the blast removes floor and launches players | 3 TNT, fuse 40 ticks, power 3 |
| `wind_charge` | Wind Charge | `spleef` (code) | Vanilla wind charge items | ×3 |
| `snowballs` | Snowballs | `spleef` (code) | Push players; break a floor block on hit | ×16, push 0.6 |
| `sugar_rush` | Sugar Rush | `spleef` (code) | Speed II + Jump Boost II | 8 s |
| `creeper_surprise` | Creeper Surprise | `spleef` (code) | A lit charged creeper drops behind a random opponent | Instant |
| `chicken_glider` | Chicken Glider | `color_chaos` | Slow Falling + hop; chicken helmet display [O] | 4 s |
| `paint_bomb` | Paint Bomb | `color_chaos` | Floor within radius 3 becomes the called colour; usable during CALL only [O] | Radius 3 |
| `double_points` | Double Points | `shooting_range` | Points ×2 | 8 s |
| `rapid_fire` | Rapid Fire | `shooting_range` | Bow full-charge time halved | 8 s |
| `overcharge` | Overcharge | `laser_tag` (optional) | Cooldowns halved | 5 s |
| `pierce` | Pierce | `laser_tag` (optional) | Next shot passes through 1 player | 1 shot |

Deferred from Cytooxien's list: Spleef's TNT Arrow, Bomb, Boxing Glove and Creeper Arrows, which McParty's own Spleef set replaces (§6.3); Rocket Boots and Bomb (Floor is Lava) and the Horse Race items (Banana, Fireball, Boost, Lightning), whose games are not in the launch twelve.

---

## 9. Manager & selection plan

### Phase A (done, one gap)

- `DummyMinigame` is the fallback. Five dummy entries (`lightning-dash`, `block-bash`, `color-match`, `hot-potato`, `sky-race`) give the reveal roulette names to spin through.
- `MinigameRegistry.pickRandom()` is uniform over **everything** registered, dummies included, so a board round can land on a dummy while real games exist. The dummy `hot-potato` also shares the display name "Hot Potato" with the real `hot_potato`.
- `/partyadmin minigame <id> [player]` test-runs one game for one player.

### Phase B

- Pick only enabled real games; use a dummy only when none is available. Dummies stay as roulette names.
- Weighted random, with "no repeat of the last 2" (v1).
- Enable/disable per id (v1).
- **Player-count gating:** skip games whose `max-players` is below the party size, or whose maps have no variant for it.
- **Freshness boost:** new or recently updated games get a slight weight boost (Cytooxien does this [O]). `selection.new-boost: 1.25` for N parties after enable.

### Phase C (later)

- **Vote for the first minigame.**
  - Cytooxien: during the waiting period, 5 random candidates are shown; standard players get 1 vote, Premium 2, Premium+ 3; the top vote is guaranteed first and ties are broken randomly [O].
  - McParty: same flow, with rank weights from permissions.
- **Ratings:** players rate a game 1–5 stars after playing it; the average feeds the selection weight [O]. Needs MySQL.
- **Map rotation:** each game picks among its map templates, with "no repeat last map".

---

## 10. Config surface (summary)

```yaml
minigame:
  dummy-coin-rewards: [20, 16, 14, 12, 10, 9, 8, 7]   # code — shared placement table; ranks beyond it get 1 coin
  dummy-duration-seconds: 5            # code
  intro-seconds: 5
  end-seconds: 3
  shared-placement-display: false
  cosmetic-helmet: false
  enabled:                             # v1
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
  selection:
    no-repeat-last: 2
    new-boost: 1.25
    weights: {}                        # id → weight, default 1.0
  hot_potato: { ... }                  # §6.1 (code)
  speed_race: { ... }                  # §6.2
  spleef: { ... }                      # §6.3 (code)
  elytra_race: { ... }                 # §6.4 (code; courses in elytra-courses.yml)
  shooting_range: { ... }              # §6.5
  color_chaos: { ... }                 # §6.6
  laser_tag: { ... }                   # §6.7
  king_of_the_hill: { ... }            # §6.8
  hopper: { ... }                      # §6.9
  mini_skywars: { ... }                # §6.10
  warden_escape: { ... }               # §6.11
  antwar: { ... }                      # §6.12
```

Wire new keys through `PluginConfig` and the default `config.yml` together (per `AGENTS.md`). A game that reads config is registered at enable **and** re-registered in `reloadPluginConfig()` (unregister the old id first).

### Default values changed in v2

| Key | Current | v2 | Reason |
|-----|---------|----|--------|
| `hot_potato.bomb-seconds` | 20 (code) | 15 | With shrinking fuses and 3 players per potato, an 8-player game ends in ≈ 64 s |
| `spleef.timeout-seconds` | 120 (code) | 90 | Fits 60–90 s; decay guarantees the end |
| `elytra_race.timeout-seconds` | 120 (code) | 75 | A winning run is ≈ 45 s, plus finish grace |
| `shooting_range.points.zombie` | 1 (v1) | 3 | Mid-band target value (§6.5) |
| `elytra_race.fireworks-per-player` | 8 (v1) | 3 | Course-driven flying (§6.4) |
| `color_chaos.first-delay-ticks` / `min-delay-ticks` | 60 / 20 (v1) | 80 / 24 | Patterned floors need travel time |
| `laser_tag.cooldown-miss-ticks` / `cooldown-hit-ticks` | 15 / 8 (v1) | 20 / 4 | "Re-shoot immediately after a hit" [V] |
| `hopper.jump-count` | 20 (v1) | 24 | Matches Cytooxien [V] |
| `hopper.max-horizontal-gap` / `max-vertical-gap` | 5 / 3 (v1) | 4 / 1 | Only reachable jumps |
| `antwar.preparation-seconds` / `duration-seconds` | 20 / 90 (v1) | 25 / 120 | Room to raid |
| `antwar.arena.resource-region` | ±48, Y 40–100 (v1) | −32…31, Y 40–59 | Matches the 64×64×20 mine |

---

## 11. Arena / admin setup

| Game | Setup data (per-game YAML store, template-relative) |
|------|-----------------------------------------------------|
| All | `arena.template`, `arena.spawn`, `arena.boundary`, spawn list |
| `hot_potato` | Power-up spots, cellar/upper-level tags (for analytics only) |
| `speed_race` | Ordered checkpoint planes + respawn poses, pads `{type, region, direction}`, coin spots, gate region, finish plane |
| `spleef` | `minigame.spleef.platform` in `config.yml`, baked into the template by `/partyadmin spleef generate` (code); `fall-y` |
| `elytra_race` | Course in `elytra-courses.yml` (code): spawn, boundary, ordered rings `{center, normal, radius}`. v2 adds ring `type` and updraft regions. |
| `shooting_range` | Platform region, target markers `{pool, mode, path/pen}`, golden markers, platform power-up spots, lanes (optional) |
| `color_chaos` | Floor origin + size, `floor-y`, stamp masks (files) |
| `laser_tag` | Spawns (≥ 16), optional pickup pads, jump pads |
| `king_of_the_hill` | Launch islands, checkpoint island regions + respawn poses, build corridors, hill zone, void-y |
| `hopper` | Arena center, start radius, fall plane Y (chains are generated) |
| `mini_skywars` | Island cages, chest positions with tier, enchanting table, center, border radii, height cap |
| `warden_escape` | Start alcoves, checkpoint planes, exit plane, Warden spawns + patrol waypoints, shrieker markers, echo shard spots, chasm region |
| `antwar` | Mine region, burrow centers + core positions, neutral cavern, ore ring radii (veins generated at PREPARE) |

**Admin commands.** The existing commands set the pattern: one `/partyadmin <game>` group per game, with the course or arena id as the first argument.

```text
# exists
/partyadmin minigame <id> [player]                 # test-run one game for one player
/partyadmin spleef generate [overwrite]            # bake the snow layers into the template
/partyadmin elytra create <id> <template>          # new course in the current world
/partyadmin elytra spawn|pos1|pos2|boundary <id>   # spawn = your location; boundary from pos1 + pos2
/partyadmin elytra ring|finish <id> [radius]       # eye location + look direction; the last ring is the finish
/partyadmin elytra undo|activate|delete <id>
/partyadmin elytra list

# new, same pattern
/partyadmin speedrace create <id> <template> | spawn | pos1 | pos2 | boundary | checkpoint | finish | pad <trampoline|boost|fan> | coin | undo
/partyadmin shooting ... target <pool> <mode> | golden | powerup
/partyadmin koth ... checkpoint | corridor | hill
/partyadmin skywars ... cage | chest <t1|t2|t3> | center
/partyadmin warden ... checkpoint | exit | spawn <A|B|C> | waypoint <A|B|C> | shrieker | shard
/partyadmin lasertag ... spawn | pad
/partyadmin antwar generate [overwrite] | burrow | core
/partyadmin colorchaos generate [overwrite]
/partyadmin hopper generate [overwrite]
/partyadmin minigame <id> [players...]             # v2: test with several players
```

Each game keeps its markers in its own YAML store, like `elytra-courses.yml`. Coordinates are template-relative and used as-is on the clone; only the world changes (§4).

---

## 12. Testing checklist

### Every game (v1, extended)

- [ ] Starts only with ≥ 2 online players in the party
- [ ] INTRO freezes position but not rotation; the countdown title and rules box appear once
- [ ] `cancel()` in each phase (PREPARE, INTRO, LIVE, END) leaves no tasks, listeners, displays, entities or boss bars
- [ ] Inventory, gamemode, effects, max health, glowing and visibility are restored for all participants
- [ ] Placements are unique 1…n for participants
- [ ] Coins (placement + bonus ≤ cap) are applied once on the main thread; a cancelled match awards none
- [ ] Disconnect mid-game does not soft-lock the runner (`active` cleared)
- [ ] Party end during a minigame cancels cleanly
- [ ] No block leaks outside the pad; the clone is unloaded
- [ ] No leftover match items, targets, Wardens, creepers or projectiles on the board after return
- [ ] Works at 2 players and at the template's `max-players`

### Per game

| Game | Specific checks |
|------|-----------------|
| `hot_potato` | No tag-back within 20 ticks; a missed throw returns the potato to the thrower; Time Freeze does not stack beyond 5 s; a holder disconnect transfers the potato; 2-player endgame has 1 potato |
| `speed_race` | A shortcut cannot skip a checkpoint; the respawn pose is never at an edge; the finish counts only once; grace ends the race |
| `spleef` | Blasts never remove scenery or drop items; creepers that fall are removed; decay starts on the lowest occupied layer |
| `elytra_race` | An out-of-order ring is ignored; the center coin tiers are correct at the ring edge; crash → last ring respawn facing the next ring |
| `shooting_range` | Two arrows on the same target score once; penalty targets subtract; zombies never burn; lanes mode blocks foreign hits |
| `color_chaos` | The generator always meets coverage/reach (fuzz 10,000 seeds); edge standing is not eliminated; Paint Bomb is only usable during CALL |
| `laser_tag` | Rays stop at blocks and the boundary; spawn protection blocks tags and firing; the respawn is never in enemy LOS within 15 blocks |
| `king_of_the_hill` | Anti-trap denies placement near players; contested hill pauses capture; overtime ends when the zone is lost |
| `hopper` | Every generated jump passes the reachability rules (fuzz seeds); a checkpoint penalty applies once; a reset never double-counts jumps |
| `mini_skywars` | Lethal-damage interception drops loot; void kill credit within 8 s; height cap breaks blocks; border damage applies |
| `warden_escape` | Wardens never target spectators or outsiders; the snowball lure redirects; a disconnect clears anger; Wardens are removed on cancel |
| `antwar` | An ore cannot be mined twice before regeneration; TNT never breaks the shell or cores; core destruction eliminates the owner; the respawn happens only once |

---

## 13. Deferred (not in this plan)

- Teams (2v2, 1v3) and Duels (v1).
- Parkour as a separate ranked course pack (v1).
- Lucky Towers, One in the Chamber, Memorize (v1). Cytooxien's newer games (Tumble Tactics, Nether Escape, Frozen Path, Horse Race, Floor is Lava, Musical Chairs, Red Light) are listed in `mcparty.md` §6.3 for later waves.
- Records per map (§3.8), ratings and vote UI (§9 Phase C), and MySQL minigame history (v1).
- Cosmetic helmets in minigames (§3.4).
- Cytooxien's Spleef items (TNT Arrow, Bomb, Boxing Glove, Creeper Arrows), the v1 Spleef TNT/crossbow variant, and the Laser Tag pickups.
- FAWE clipboard service and the full Cytooxien power-up meta (v1).

---

## 14. Suggested build milestones

| Milestone | Deliverable |
|-----------|-------------|
| **M0** (done) | Registry + dummy fallback, `MinigameRunner` + arena clones, `MatchScope`, `MinigameEventBus`, `EliminationTracker`, `PowerUpBox`; v1-level `hot_potato`, `spleef` and `elytra_race` |
| **M1** | **`MatchHud` + `IntroSequence`** + `CoinPickupService` + shared power-up roll + snapshot and bus extensions (§4); `hot_potato` v2 |
| **M2** | **`RaceKit` + `TrackProgress`** + `speed_race`; `spleef` v2 (decay, layer HUD) |
| **M3** | `elytra_race` v2 (ring coin tiers, respawn, kit) + `shooting_range` (`TargetDirector`) |
| **M4** | `color_chaos` (`PatternGenerator` + fuzz test) + `laser_tag` |
| **M5** | `king_of_the_hill` + `hopper` (`JumpGenerator` + fuzz test) |
| **M6** | `mini_skywars` (loot tables, border, lethal interception) |
| **M7** | `warden_escape` (`NoiseSystem`, `WardenDirector`) |
| **M8** | `antwar` (`OreField`, `QueenCore`) |
| **M9** | Phase B selection (enabled list, weights, freshness, dummies out of rotation), remaining admin setup commands, polish FX |

---

## 15. Quick id reference

| Id | One-line rule |
|----|---------------|
| `hot_potato` | Pass the potato by throwing it or hitting someone; holders at the boom are out |
| `speed_race` | Sprint the track, grab coins for speed, take shortcuts, finish first |
| `spleef` | Dig through three snow layers under your rivals; last one up wins |
| `elytra_race` | Glide through ordered rings, hitting centers for coins; finish first |
| `shooting_range` | Shared gallery: hit the most valuable mobs first, avoid penalty targets |
| `color_chaos` | Stand on the called colour before the rest of the floor drops |
| `laser_tag` | Hitscan tags send rivals back to spawn; most tags wins |
| `king_of_the_hill` | Bridge up the islands with limited blocks, then hold the summit |
| `hopper` | Jump your personal block chain; flags save you but slow the next block |
| `mini_skywars` | Loot, bridge, fight, survive the border; last alive wins |
| `warden_escape` | Sneak past Wardens that hunt by sound; first through the gate wins |
| `antwar` | Mine ore for gear and TNT, break rival queen cores, keep yours alive |

---

## 16. Sources

**Official Cytooxien** (accessed 2026-10-11):

- Minigame overview (EN) — https://www.cytooxien.net/en/help/minecraft-party-games
- Minigame overview (DE) — https://www.cytooxien.net/de/help/minecraft-party-games
- How to play Minecraft Party (edited 2026-04-15) — https://cytooxien.net/wiki/minecraft-party
- New Party Update Rotation (2025-01-10) — https://www.cytooxien.net/en/mp-update-rotation
- Introducing Coins in Minecraft Party (2025-05-30) — https://www.cytooxien.net/en/minecraft-party-coin-update
- Minecraft Party Gets Even More Chaotic (2026-03-07) — https://www.cytooxien.net/en/minecraft-party-chaos-update
- Blog, update category (no newer Party post through 2026-08-02) — https://www.cytooxien.net/en/blog?category=update

**Gameplay footage** (frames and/or transcripts reviewed):

- EnderKnight_MC, "Minecraft Party Grind! (Cytooxien)", live ≈2026-10-07 — https://www.youtube.com/watch?v=dYf5hGg7oLM (Color Chaos, board HUD, coin payouts, Floor is Lava, Walls intro)
- c0by, "MARIO PARTY (in Minecraft)", 2026-08-07 — https://www.youtube.com/watch?v=pUMWeyJUQ2w (Hot Potato Pier, Laser Tag, Race Ashstone Peaks, Skywars Wild West)
- Cytooxien, chaos-update short, 2026-03 — https://www.youtube.com/watch?v=HvictSOVBcQ
- Marvelino, "Dieses MINIGAME hat mich gebrochen… | MC Party Minigames #02", 2025-11-01 — https://www.youtube.com/watch?v=RRF5z7Y88S8 (Elytra Race)
- Marvelino, MC Party Minigames series, 2025-09 — https://www.youtube.com/watch?v=bDgEsDigzuU (Block Jump, Shooting Range, Mine Battle)
- Spleef short, 2025-08 — https://www.youtube.com/watch?v=uhc5h5f73os
- Borrriisss, "Cytooxien Mario Party alle shortcuts (Wettrennen)", 2025-11 — https://www.youtube.com/watch?v=RNH7qWtDiN8
- Cytooxien shorts 2025-01 / 2025-05 — https://www.youtube.com/watch?v=lDCGd-CgVOc, https://www.youtube.com/watch?v=mIVhUGnL3Dg
- Reddoons, "Minecraft Mario Party is Hilarious" — https://www.youtube.com/watch?v=0ZM_s89WTdc
- Grapfen, "13 Tipps in Minecraft Mario Party auf Cytooxien", 2023-07-18 — https://www.youtube.com/watch?v=WZKAu_PX8fE (King of the Hill "Gipfelstürmer", tips)
- EinfachGustaf, "Ich habe MINECRAFT PARTY gegen 20 YOUTUBER gespielt…", 2023 — https://www.youtube.com/watch?v=VWmi42EAEu4

**Design references** (v1, retained):

- Spleef_reloaded — https://github.com/steve4744/Spleef
- lmk02/BlockParty — https://github.com/lmk02/BlockParty
- ElytraRace — https://modrinth.com/plugin/elytrarace
- Warden — https://www.minecraft.net/en-us/article/warden
- Exploring an ancient city — https://minecraft.fandom.com/wiki/Tutorials/Exploring_an_ancient_city
- Hypixel Pixel Party (power-up ideas) — https://hypixel.fandom.com/wiki/Pixel_Party
- Hypixel TNT Tag (holder buffs) — https://hypixel.fandom.com/wiki/TNT_Tag
- MC Championship (Ace Race) — https://en.wikipedia.org/wiki/MC_Championship

---

This spec is the working contract for implementing the launch twelve. Update it when a game's rules or arena model change. Keep `mcparty.md` for product-level vision and `AGENTS.md` for bootstrap/SPI wiring.
