# AGENTS.md — McParty

Instructions for AI coding agents working in this repository. Read this before planning, implementing, or refactoring.

---

## 1. Project overview

**McParty** is a Mario Party–style multiplayer minigame plugin for Minecraft.

| Item | Value |
|------|--------|
| Group / package root | `dev.epicc` |
| Main class | `dev.epicc.McPartyPlugin` |
| Artifact | `McParty-<version>.jar` |
| Design notes (product vision) | `mcparty.md` (Vietnamese; scale, Redis, multi-server — not all implemented yet) |

**Goal (current phase):** party rooms → load a board world → turn-based dice on a path → dummy minigame between rounds → podium → cleanup/unload.

**Long-term vision** (see `mcparty.md`): 400+ players, Velocity proxy, lobby + game servers, MySQL stats, Redis matchmaking, 10–12 minigames. Do not implement that full stack unless the user asks; keep changes scoped.

---

## 2. Runtime requirements

| Requirement | Detail |
|-------------|--------|
| Server | **AdvancedSlimePaper (ASP)** — Paper fork with Slime Region Format API |
| Minecraft / API | Paper API **26.1.2** (`api-version: '26.1.2'`) |
| Java | **25** (toolchain + `options.release`) |
| Hard plugin depend | **WorldEdit** (`depend: [WorldEdit]` in `plugin.yml`) |
| Optional path | If `slime.enabled: false` or ASP API init fails, parties use permanent board-slot worlds (no clone/unload) |

**Not plain Paper.** ASP API (`com.infernalsuite.asp.api`) is provided by the server. Loaders are **not** on the server — they must be shaded into this plugin.

Deployed template file (when slime is on):

```text
plugins/McParty/<slime.worlds-directory>/<slime.template-world>.slime
# default:
plugins/McParty/slime_worlds/party_board.slime
```

---

## 3. Build system

- Build tool: **Gradle** (Kotlin DSL), wrapper present (`gradlew` / `gradlew.bat`)
- Project files: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- Encoding: UTF-8 (no BOM)

### Dependencies

| Dependency | Scope | Notes |
|------------|--------|--------|
| `io.papermc.paper:paper-api:26.1.2.build.+` | `compileOnly` | Bukkit/Paper API |
| `com.infernalsuite.asp:api:4.2.0-SNAPSHOT` | `compileOnly` | ASP — server-provided |
| `com.infernalsuite.asp:file-loader:4.2.0-SNAPSHOT` | `implementation` | Shaded into jar |
| `worldedit-bukkit` / `worldedit-core` 7.3.14 | `compileOnly`, `isTransitive = false` | Guava/Gson clash with Paper 26 if transitive |

Repositories: Maven Central, PaperMC, EngineHub, InfernalSuite releases + snapshots.

### Packaging

The `jar` task fat-jars `runtimeClasspath` (currently the file-loader) into `McParty-*.jar` with `DuplicatesStrategy.EXCLUDE`. Do not switch to a bare thin jar without keeping loaders shaded.

`plugin.yml` version is expanded from Gradle `version` via `processResources`.

### Verify after code changes

```bash
./gradlew.bat compileJava
# or full package:
./gradlew.bat jar
```

Output: `build/libs/McParty-1.0.0-SNAPSHOT.jar` (version may change).

---

## 4. Source layout

```text
src/main/java/dev/epicc/
  McPartyPlugin.java          # Bootstrap: wire services, register commands/listeners
  board/                      # Board slots, path, turns, dice
    setup/WorldEditHook.java  # WE selection → SlotBoundary
  command/                    # /party, /partyadmin
  config/PluginConfig.java    # Typed config from config.yml
  containment/                # Boundary + fake barrier walls
  minigame/                   # Minigame interface + dummy + manager
  party/                      # PartyInstance, PartyManager, state, settings
  player/PlayerSessionService.java  # player UUID → party UUID
  slime/SlimeWorldService.java      # ASP load / clone / unload
  store/                      # InstanceStore + in-memory impl

src/main/resources/
  plugin.yml
  config.yml
```

Persistent data at runtime:

| File / path | Purpose |
|-------------|---------|
| `plugins/McParty/config.yml` | Defaults from resources; `PluginConfig` reads once on enable |
| `plugins/McParty/slots.yml` | Board slots (world name, bounds, path, spawn) — `BoardSlotRegistry` |
| `plugins/McParty/slime_worlds/*.slime` | Template worlds for ASP FileLoader |

---

## 5. Architecture (what exists today)

### Bootstrap (`McPartyPlugin`)

`onEnable` constructs (order matters for wiring):

1. `PluginConfig`
2. `PlayerSessionService`, `InMemoryInstanceStore`
3. `BoardSlotRegistry` → `load()`
4. `SlimeWorldService` (ASP + FileLoader)
5. `FakeWallService`, `MinigameManager` (+ `DummyMinigame`)
6. `PartyManager`
7. `BoundaryListener`, commands

`onDisable`: `partyManager.shutdown()` → unload slime worlds → save slots.

### Domain model

```text
PartyManager
  ├── InstanceStore (PartyInstance by UUID)
  ├── PlayerSessionService (player → instance)
  ├── BoardSlotRegistry (template slots; claim/release)
  ├── SlimeWorldService (per-instance world names)
  ├── BoardTurnController (per playing instance)
  ├── FakeWallService
  └── MinigameManager

PartyInstance
  ├── PartySettings (min/max players, turns, dice, coins)
  ├── PartyState
  ├── Map of PartyPlayer
  ├── BoardSlot (runtime; may be remapped to slime clone world)
  └── endRequestHandler → PartyManager.endInternal
```

### Party state machine

```text
WAITING → STARTING → PLAYING → ENDING → CLEANUP
              ↑ fail load / abort can return to WAITING (start path)
```

| State | Meaning |
|-------|---------|
| `WAITING` | Lobby in memory; no game world required |
| `STARTING` | Slot claimed; slime load + countdown |
| `PLAYING` | Turns + minigames |
| `ENDING` | Podium broadcast |
| `CLEANUP` | Sessions cleared, slot released, slime unloaded, store removed |

### Core flows

**Create / join / leave**

- `PartyManager.create` — host binds session, store puts instance.
- `join` — by short id (first 8 of UUID) or auto first open WAITING room.
- `leave` — unbind; if empty → `cleanup`; if in-game and &lt;2 players → `endInternal`.

**Start** (`PartyManager.start`)

1. Validate host, `canStart()`, state WAITING.
2. `slots.claimFree(instanceId)` — needs a **ready** free slot (spawn + path + boundary).
3. State → STARTING.
4. If `slime.isReady()`:
   - Async: `prepareClone` (`readWorld` template read-only + `clone`)
   - Sync: `loadClone` (`asp.loadWorld` — **main thread only**)
   - `templateSlot.forWorld(cloneWorld)` → runtime slot with same coords
5. Else: use permanent template slot world.
6. Countdown → `beginPlaying` (teleport spawn, walls, attach `BoardTurnController`).

**Board turns** (`BoardTurnController`)

1. Each player rolls (`/party roll` or auto-roll timer).
2. Dice advances `PartyPlayer.boardIndex` along `BoardSlot.path()`.
3. After all players acted once in a round → `MinigameManager.runDummy` → apply coin rewards → next round.
4. After `maxTurns` rounds → `instance.requestEnd` → podium → cleanup.

**Cleanup**

- Clear walls, sessions, release template slot (by id in registry), `slime.unloadForInstance` (teleports players out, `unloadWorld(..., false)`), remove from store.

### Board slots vs slime worlds

- **Template slot** (in `slots.yml`): setup on any loaded world (often a permanent build world). Stores integer bounds + path/spawn coords + world name for admin setup.
- **Runtime slot**: `BoardSlot.forWorld(World)` / `BoardPath.forWorld` / `SlotBoundary.forWorld` rebind the **same coordinates** onto the loaded slime clone.
- Claiming uses the **registry** slot; cleanup releases via `slots.get(id)`.

Admins still set up path/spawn/bounds with WorldEdit on a world that has matching geometry to the `.slime` template.

### Containment

- `SlotBoundary` — axis-aligned box; `isInside` / `clampInside`.
- `BoundaryListener` — cancels moves outside boundary during STARTING/PLAYING/ENDING (unless `mcparty.admin.bypass`).
- `FakeWallService` — client-side barrier shell via block send (not real blocks).

### Minigames

```java
public interface Minigame {
    String id();
    void start(MinigameContext context, Consumer<MinigameResult> done);
    void cancel();
}
```

- Only `DummyMinigame` is registered today (timed, awards coins by rank list from config).
- `MinigameManager` holds a single `active` minigame; always `cancelActive()` before starting another.
- New minigames: implement `Minigame`, wire through `MinigameManager` (and eventually a picker); do not put game logic in `PartyManager`.

### Commands & permissions

| Command | Permission | Role |
|---------|------------|------|
| `/party create\|join\|leave\|start\|list\|roll` | `mcparty.party` (default true) | Players |
| `/party end [id]` | `mcparty.admin` | Force end |
| `/partyadmin slot\|path\|walls` (alias `padmin`) | `mcparty.admin` | Board setup |
| Bypass walls | `mcparty.admin.bypass` | Ops |

Admin slot setup:

- `slot create <id>` — WorldEdit cuboid → boundary  
- `slot spawn <id>` — set spawn at feet  
- `path add/clear <id>` — board path points  
- `walls show/hide <id>` — preview barriers  
- Slot is **ready** only with spawn + non-empty path + boundary  

Commands return `Optional<String>` errors from `PartyManager` → red chat prefix `[McParty]`.

---

## 6. AdvancedSlimePaper integration

Docs reference: https://infernalsuite.com/docs/asp/api/using  
Javadocs: https://docs.infernalsuite.com/

### Rules

1. Get API: `AdvancedSlimePaperAPI.instance()`.
2. Own the `SlimeLoader` yourself — API does not manage loaders.
3. `FileLoader` constructor takes a **`File` directory** (creates dir if missing). Worlds are `<name>.slime` in that folder.
4. `readWorld` / clone / save may run **off main thread**.
5. `loadWorld(SlimeWorld, callWorldLoadEvent)` **must** run on the **main (server) thread**.
6. Temporary clones: `template.clone(uniqueName)` (read-only clone, not persisted). Do not save party instance worlds unless product requirements change.
7. Unload: evacuate players, then `Bukkit.unloadWorld(world, false)`. Track names in `SlimeWorldService.instanceWorlds`.

### Service API (`SlimeWorldService`)

| Method | Thread | Purpose |
|--------|--------|---------|
| `prepareClone(instanceId)` | Async OK | read template + clone |
| `loadClone(instanceId, clone)` | Main only | register world, map instance → name |
| `loadForInstance(instanceId)` | Main only (full path) | convenience sync load |
| `unloadForInstance` / `unloadAll` | Main | teleport out + unload |

Config keys under `slime:` — see `config.yml` and `PluginConfig`.

### When adding ASP features

- Prefer extending `SlimeWorldService` over scattering ASP calls in `PartyManager`.
- Keep `compileOnly` for `api`; only shade loader artifacts that are not on the server.
- Prefer InfernalSuite snapshots/releases repos already in `build.gradle.kts`.
- Do not invent a second world lifecycle; reuse `instanceWorlds` mapping.

---

## 7. Configuration

`PluginConfig` loads once in `onEnable` (no live reload API yet). After changing `config.yml` defaults in resources, remember existing servers keep their own file until deleted or manually merged.

Important groups:

- `party.*` — sizes, max instances, countdown, turns, starting coins  
- `board.dice-min/max`  
- `containment.wall-material/height`  
- `minigame.dummy-*`  
- `slime.*` — ASP template and world naming  

Add new config only through `PluginConfig` + default `config.yml` together.

---

## 8. Coding conventions

Match existing style; do not reformat unrelated code.

| Topic | Convention |
|-------|------------|
| Classes | Prefer `public final class`; package-private only when intentional |
| Fields | `private final` where possible; accessors as short methods `name()` not `getName()` for domain types |
| Nullability | Prefer `Optional` for fallible lookups; command errors as `Optional<String>` (empty = success) |
| Concurrency | `ConcurrentHashMap` for shared maps; Bukkit entity/world work on main thread |
| Async | Disk/ASP `readWorld` async → hop to main for `loadWorld` / player teleport |
| Messages | Adventure `Component` + `NamedTextColor`; prefix `[McParty]` |
| Errors | Log with `plugin.getLogger()`; do not swallow ASP/IO exceptions without a log line |
| Comments | Only for non-obvious lifecycle / threading / ASP constraints |
| Dependencies | Avoid new libraries unless necessary; check Guava/Gson conflicts with Paper 26 |
| Scope | Minimal diffs; no drive-by refactors; no unsolicited markdown docs unless asked |
| `plugin.yml` | Update when adding commands, permissions, or depend/softdepend |
| Tests | None yet; do not add a full test framework unless requested |

### Do not

- Depend on Spigot-only APIs when Paper/ASP equivalents exist.
- Call `asp.loadWorld` off the main thread.
- Leave players in a world that is about to unload.
- Make WorldEdit transitive again without verifying the Guava conflict.
- Implement Redis/MySQL/Velocity multi-server pieces without an explicit request (design lives in `mcparty.md` only for now).
- Commit secrets or absolute machine-specific paths.

---

## 9. Extending the plugin (recipes)

### Add a real minigame

1. Implement `Minigame` under `minigame/`.
2. Register/select in `MinigameManager` (replace or branch from `runDummy`).
3. Start from `BoardTurnController` after a full turn round (or new triggers).
4. Return `MinigameResult` with coin/star deltas; apply to `PartyPlayer` on the main thread.
5. Ensure `cancel()` is safe if party ends mid-minigame.

### Change world load timing

Today: load on **start**, unload on **cleanup**.  
Design doc also allows load on create — if changing, keep one clear owner (`PartyManager` + `SlimeWorldService`) and still unload on empty WAITING cleanup.

### Slot / board data

- Persistence only via `BoardSlotRegistry` ↔ `slots.yml`.
- Coordinate remapping for clones is via `forWorld`; do not bake clone world names into `slots.yml`.

### New commands

- Register in `plugin.yml` + set executor/tab completer in `McPartyPlugin`.
- Keep thin command classes; business logic in managers/services.

---

## 10. Implemented vs planned

| Feature | Status |
|---------|--------|
| Party create/join/leave/start/end/list/roll | Done |
| Board slots + WE setup + path + spawn | Done |
| Turn controller + dice + dummy minigame | Done |
| Boundary + fake walls | Done |
| ASP template clone load/unload | Done |
| In-memory instance store | Done |
| Redis / MySQL / Velocity multi-server | **Not implemented** |
| Multiple real minigames / shop / stars | **Not implemented** |
| Matchmaking dynamic countdown by queue size | **Not implemented** |
| Lobby vs game server split | **Not implemented** |

Prefer incremental features that fit the current single-process, in-memory design unless the user explicitly starts Phase 2+ infrastructure.

---

## 11. Agent workflow checklist

1. Read this file and the packages you will touch.
2. Keep changes minimal and consistent with naming/accessors above.
3. If touching ASP: respect main-thread `loadWorld` and shade rules.
4. If adding commands/perms/deps: update `plugin.yml` and/or `build.gradle.kts`.
5. Compile with `./gradlew.bat compileJava` (or `jar`) before finishing.
6. Do not rewrite `mcparty.md` unless documenting a deliberate product decision the user asked for.
7. Prefer updating **this** `AGENTS.md` when architecture or bootstrap wiring changes in a lasting way.

---

## 12. Quick reference — important types

| Type | Role |
|------|------|
| `McPartyPlugin` | DI-by-hand bootstrap |
| `PartyManager` | Orchestration center |
| `PartyInstance` / `PartyPlayer` / `PartyState` | Match state |
| `BoardSlotRegistry` / `BoardSlot` / `BoardPath` | Board geometry |
| `BoardTurnController` / `Dice` | Turn loop |
| `SlimeWorldService` | ASP worlds |
| `InstanceStore` | Party persistence (memory) |
| `PlayerSessionService` | Membership index |
| `Minigame` / `MinigameManager` | Minigame SPI |
| `PluginConfig` | Typed settings |
| `WorldEditHook` | Selection → boundary |

When in doubt: **put orchestration in `PartyManager`, world IO in `SlimeWorldService`, board rules in `board/`, minigame rules in `minigame/`.**
