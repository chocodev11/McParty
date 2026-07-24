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
| Soft plugin depend | **PacketEvents** (`softdepend: [packetevents]`) — seamless same-env world teleports (no dirt screen) |
| Optional path | If `slime.enabled: false` or ASP API init fails, parties use permanent board-slot worlds (no clone/unload) |

**Not plain Paper.** ASP API (`com.infernalsuite.asp.api`) is provided by the server. Loaders are **not** on the server — they must be shaded into this plugin.

Deployed template files (when slime is on):

```text
plugins/McParty/<slime.worlds-directory>/<template-name>.slime
# examples:
plugins/McParty/slime_worlds/party_board.slime
plugins/McParty/slime_worlds/beach.slime
```

Each board slot may store which template to clone (`slots.yml` → `slime-template`).
Path setup does not assign it (template is generated later). Empty field uses `slime.template-world`.

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
| `packetevents-spigot` 2.7.0 | `compileOnly` | Soft-depend; not shaded — install PacketEvents on server for seamless world TP |

Repositories: Maven Central, PaperMC, InfernalSuite, CodeMC releases.

### Packaging

The `jar` task fat-jars `runtimeClasspath` (currently the file-loader) into `McParty-*-full.jar` with `DuplicatesStrategy.EXCLUDE`. Do not switch to a bare thin jar without keeping loaders shaded.

**ProGuard** (`com.guardsquare:proguard-gradle:7.9.1`) then shrinks and optimizes that fat jar (no obfuscation by default — see `proguard-rules.pro`). Deploy artifact:

```text
build/libs/McParty-<version>.jar
```

- `./gradlew.bat jar` — shaded only (`*-full.jar`)
- `./gradlew.bat proguard` / `assemble` / `packagePlugin` — optimized deploy jar

`plugin.yml` version is expanded from Gradle `version` via `processResources`.

### Verify after code changes

```bash
./gradlew.bat compileJava
# or full package (includes ProGuard):
./gradlew.bat assemble
```

Output: `build/libs/McParty-1.0.0-SNAPSHOT.jar` (version may change).

---

## 4. Source layout

```text
src/main/java/dev/epicc/
  McPartyPlugin.java          # Bootstrap: wire services, register commands/listeners
  board/                      # Board slots, path, turns, dice
    setup/                    # Path stick (blaze rod) builder (gold/yellow 3x3 pads)
  command/                    # /party, /partyadmin
  config/PluginConfig.java    # Typed config from config.yml
  containment/                # Slot boundary clamp (move/teleport)
  minigame/                   # Minigame interface + dummy + manager
  party/                      # PartyInstance, PartyManager, state, settings
  player/PlayerSessionService.java  # player UUID → party UUID
  slime/SlimeWorldService.java      # ASP load / clone / unload
  seamless/                   # Optional PacketEvents RESPAWN cancel (no dirt screen)
  resourcepack/               # Local/external pack host + player prompt
  store/                      # InstanceStore + in-memory impl

src/main/resources/
  plugin.yml
  config.yml

resourcepack/                 # Dice models (bundled into jar; extracted for local mode)
```

Persistent data at runtime:

| File / path | Purpose |
|-------------|---------|
| `plugins/McParty/config.yml` | Defaults from resources; `PluginConfig` reads once on enable |
| `plugins/McParty/messages.yml` | All player-facing text (MiniMessage); `MessageService` — reloaded with `/partyadmin reload` |
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
5. `SeamlessWorldChangeService` (PacketEvents hook if present)
6. `ResourcePackService` (local HTTP or external URL; optional)
7. `MinigameManager` (+ `DummyMinigame`)
8. `PartyManager`
9. `BoundaryListener`, resource-pack listener, commands

`onDisable`: `partyManager.shutdown()` → unload slime worlds → stop resource-pack HTTP → save slots.

### Domain model

```text
PartyManager
  ├── InstanceStore (PartyInstance by UUID)
  ├── PlayerSessionService (player → instance)
  ├── BoardSlotRegistry (template slots; claim/release)
  ├── SlimeWorldService (per-instance world names)
  ├── BoardTurnController (per playing instance)
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
   - Resolve template from `slot.slimeTemplate()` (else config default)
   - Async: `prepareClone(instanceId, template)` (`readWorld` read-only + `clone`)
   - Sync: `loadClone` (`asp.loadWorld` — **main thread only**)
   - `templateSlot.forWorld(cloneWorld)` → runtime slot with same coords
5. Else: use permanent setup-world slot (no clone).
6. Countdown → `beginPlaying` (teleport spawn, attach `BoardTurnController`).

**Board turns** (`BoardTurnController` + `board/dice/*`)

1. **Everyone rolls at once** each round: each player gets a private visual die (`DicePresenter` ItemDisplay passenger; `setVisibleByDefault(false)` + `showEntity` only for the roller). Result is rolled up front.
2. Spin until **that player clicks** (or `/party roll`) or **timeout** (`board.dice-interact-seconds`, default 5s). No settle from being hit by others.
3. On settle: land upright, set **public** dice hat (`DiceHatService`), hold **1 second**, then callback. When **all** rollers have settled → hop phase.
4. Hops in party order: `PathHopMover` upward velocity → at apex (`vy ≤ 0`) TP to target XZ at that Y → fall (fall damage cancelled).
5. After hops → `MinigameManager.runRandom` (reveal titles → start, no teleport) → coin rewards → next round.
6. After `maxTurns` rounds → `instance.requestEnd` → podium → cleanup.
7. Custom look: resource pack `resourcepack/` models `mcparty:dice_1`…`dice_6` (`DiceItems`), prompted by `ResourcePackService`.

**Resource pack** (`resourcepack/ResourcePackService`)

- Config: `resource-pack.*` — `mode: local` (zip data-folder pack + JDK `HttpServer`) or `mode: external` (URL + SHA-1).
- Bundled pack ships in the jar under `resourcepack/`; extracted to `plugins/McParty/resourcepack/` if missing.
- Local: zip written to `plugins/McParty/output/<zip-name>`; SHA-1 of zip; serve `GET /mcparty.zip`; set `local.public-url` to a client-reachable URL (open firewall port).
- `/partyadmin reload` reloads `config.yml` + `messages.yml`, re-applies party/board/minigame settings, and restarts the resource pack (re-zip + HTTP). Slime loader and seamless PE hook stay as at enable (server restart to change those).
- Prompt via `Player#setResourcePack(UUID, url, sha1, prompt, required)`; prompt/kick text from `messages.yml`. Status via `PlayerResourcePackStatusEvent`.
- `send-on: party` (create/join) or `join` (login). Fail-open if disabled or setup fails.

**Cleanup**

- Clear sessions, release template slot (by id in registry), `slime.unloadForInstance` (teleports players out, `unloadWorld(..., false)`), remove from store.

### Board slots vs slime worlds

- **Board slot** (in `slots.yml`): one path + spawn + bounds + optional **`slime-template`** (ASP file basename; empty → config default until generated). Setup world name is only where the path was built.
- **Runtime slot**: `BoardSlot.forWorld(World)` rebinds the **same coordinates** onto the party's slime clone of that template.
- Claiming uses the **registry** slot; cleanup releases via `slots.get(id)`. Multiple free slots = concurrent capacity (each can point at different templates, or the same template if you want more capacity on one map).

Admin setup:

```text
/partyadmin path create <board-id>
# Path Stick → spawn + pads → /partyadmin path end
# slime-template is filled later when the .slime is generated
```

### Containment

- `SlotBoundary` — axis-aligned box; `isInside` / `clampInside`.
- `BoundaryListener` — clamps moves/teleports outside boundary during STARTING/PLAYING/ENDING (unless `mcparty.admin.bypass`). No fake barrier blocks.

### Minigames

```java
public interface Minigame {
    String id();
    void start(MinigameContext context, Consumer<MinigameResult> done);
    void cancel();
}
```

- `MinigameRegistry` holds games; `pickRandom()` selects one (only `DummyMinigame` registered today).
- After each board round: **reveal** (title roulette, config ticks) → then `start()` **in place** (no minigame teleport).
- `MinigameManager` owns reveal + one `active` minigame; `cancelActive()` aborts both.
- New minigames: implement `Minigame` (+ `displayName()`), `registry.register(...)` in bootstrap; do not put game logic in `PartyManager`.

### Commands & permissions

| Command | Permission | Role |
|---------|------------|------|
| `/party create\|join\|leave\|start\|list\|roll` | `mcparty.party` (default true) | Players |
| `/party end [id]` | `mcparty.admin` | Force end |
| `/partyadmin path\|slot\|minigame\|reload` (alias `padmin`) | `mcparty.admin` | Board setup, minigame testing + config reload |
| Bypass boundary | `mcparty.admin.bypass` | Ops |

Admin board setup (path builder):

- `path create <name>` — start setup; gives **Path Stick** (no slime template yet)  
- **1st break** with Path Stick — set **spawn** only (no pad blocks). At game start players teleport randomly within 4 blocks of that point  
- **Later breaks** — place flat 3×3 pad (center `GOLD_BLOCK`, ring `YELLOW_WOOL`), append path space  
- `path undo` — drop last path pad (or clear spawn if no pads yet)  
- `path end` — needs spawn + ≥1 path space; boundary = AABB of pads + spawn + Y padding; save ready `BoardSlot` with empty `slime-template`  
- `path remove <name>` — delete board from `slots.yml`  
- `path slime <name> <template>` — set `slime-template` from a basename present in `slime_worlds/`  
- `slot list` / `slot delete <id>` — manage saved boards (list shows template; `*` = using config default)  
- Quit / disable / world change mid-setup cancels session, restores pads, removes Path Stick  

Commands return `Optional<String>` errors from `PartyManager` / setup → red chat prefix `[McParty]`.

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
| `prepareClone(instanceId, template)` | Async OK | read named template + clone |
| `loadClone(instanceId, clone)` | Main only | register world, map instance → name |
| `loadForInstance(instanceId, template)` | Main only (full path) | convenience sync load |
| `listTemplates()` | Any | scan `*.slime` basenames (tab-complete) |
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
- `minigame.dummy-*`, `minigame.reveal-duration-ticks`, `minigame.reveal-interval-ticks`  
- `seamless-world-change.enabled` — cancel RESPAWN on McParty same-env world teleports (needs PacketEvents)  
- `resource-pack.*` — local HTTP or external URL, send-on join/party, required/kick-on-decline (prompt/kick text in `messages.yml`)  
- `messages.yml` — all player chat/title/item strings (MiniMessage)
- `slime.*` — ASP template and world naming  

Add new config only through `PluginConfig` + default `config.yml` together.
Add player-facing text only through `MessageService` + default `messages.yml` together (MiniMessage; placeholders as `<name>`).

### Seamless world change

`SeamlessWorldChangeService` marks a player only when McParty teleports them (`PartyManager.beginPlaying`, slime unload evacuations). PacketEvents cancels the next `RESPAWN` if environments and world heights match. No chunk-unload fan-out. Fail-open if PE missing or config off. Same-world board steps (`BoardTurnController`) do not need this.

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
| Board slots + Path Stick setup + path + spawn | Done |
| Turn controller + dice + dummy minigame | Done |
| Boundary clamp (no fake walls) | Done |
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
| `PathSetupService` / `PathSetupListener` | Path Stick break-block path builder |
| `PathSetupWand` | Blaze rod stick (name + custom_data PDC) |
| `SlimeWorldService` | ASP worlds |
| `InstanceStore` | Party persistence (memory) |
| `PlayerSessionService` | Membership index |
| `Minigame` / `MinigameManager` | Minigame SPI |
| `PluginConfig` | Typed settings |
| `MessageService` | `messages.yml` MiniMessage lookup + placeholders |
| `ResourcePackService` | Dice pack host + prompt |

When in doubt: **put orchestration in `PartyManager`, world IO in `SlimeWorldService`, board rules in `board/`, minigame rules in `minigame/`.**
