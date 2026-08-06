package dev.epicc.hologram;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

public final class HologramService implements Listener {

    private final JavaPlugin plugin;
    private final YamlHologramRepository repository;
    private HologramRenderer renderer;
    private final Map<String, RuntimeHologram> holograms = new ConcurrentHashMap<>();
    private final Map<String, HologramDefinition> partyTemplates = new ConcurrentHashMap<>();
    private final Map<String, TemplateBundle> partyTemplateBundles = new ConcurrentHashMap<>();
    private final Map<String, HologramDefinition> lobbyTemplates = new ConcurrentHashMap<>();
    private final Map<String, TemplateBundle> lobbyTemplateBundles = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, RuntimeHologram>> partyScopes = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, RuntimeHologram>> lobbyScopes = new ConcurrentHashMap<>();
    private final Map<UUID, World> partyScopeWorlds = new ConcurrentHashMap<>();
    private final Map<UUID, World> lobbyScopeWorlds = new ConcurrentHashMap<>();
    private final Map<String, HologramPlaceholder> placeholders = new ConcurrentHashMap<>();
    private BiPredicate<UUID, Player> scopeVisibility = (scopeId, player) -> true;
    private BukkitTask tickTask;
    private boolean enabled;
    private int scanIntervalTicks;
    private float defaultViewRange;
    private long tick;

    public HologramService(JavaPlugin plugin, boolean enabled, String fileName, int scanIntervalTicks, float defaultViewRange) {
        this.plugin = plugin;
        this.repository = new YamlHologramRepository(plugin, fileName);
        this.enabled = enabled;
        this.scanIntervalTicks = Math.max(1, scanIntervalTicks);
        this.defaultViewRange = validRange(defaultViewRange) ? defaultViewRange : 32.0f;
        registerBuiltins();
        if (enabled) {
            renderer = createRenderer();
            if (renderer == null) {
                this.enabled = false;
            }
        }
    }

    public void start() {
        if (!enabled) {
            plugin.getLogger().info("Holograms disabled by configuration");
            return;
        }
        reloadDefinitions();
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void reconfigure(boolean enabled, int scanIntervalTicks, float defaultViewRange) {
        if (!enabled) {
            hideAll();
            if (tickTask != null) {
                tickTask.cancel();
                tickTask = null;
            }
            this.enabled = false;
            holograms.clear();
            partyTemplates.clear();
            partyTemplateBundles.clear();
            lobbyTemplates.clear();
            lobbyTemplateBundles.clear();
            partyScopes.clear();
            lobbyScopes.clear();
            return;
        }

        if (renderer == null) {
            renderer = createRenderer();
            if (renderer == null) {
                this.enabled = false;
                return;
            }
        }
        this.enabled = enabled;
        this.scanIntervalTicks = Math.max(1, scanIntervalTicks);
        this.defaultViewRange = validRange(defaultViewRange) ? defaultViewRange : 32.0f;
        reloadDefinitions();
        if (tickTask == null) {
            tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    public void registerPlaceholder(String name, HologramPlaceholder resolver) {
        if (name == null || name.isBlank() || resolver == null) {
            throw new IllegalArgumentException("placeholder name and resolver are required");
        }
        placeholders.put(normalize(name), resolver);
    }

    public void setScopeVisibility(BiPredicate<UUID, Player> visibility) {
        scopeVisibility = visibility == null ? (scopeId, player) -> true : visibility;
    }

    public void reloadDefinitions() {
        if (!enabled) {
            return;
        }
        hideAll();
        holograms.clear();
        partyTemplates.clear();
        partyTemplateBundles.clear();
        lobbyTemplates.clear();
        lobbyTemplateBundles.clear();
        partyScopes.clear();
        lobbyScopes.clear();
        for (HologramDefinition definition : repository.load().values()) {
            switch (definition.scope()) {
                case "party" -> {
                    partyTemplates.put(definition.id(), definition);
                    partyTemplateBundles.put(definition.id(), compile(definition));
                }
                case "lobby" -> {
                    lobbyTemplates.put(definition.id(), definition);
                    lobbyTemplateBundles.put(definition.id(), compile(definition));
                }
                default -> holograms.put(definition.id(), new RuntimeHologram(definition));
            }
        }
        for (Map.Entry<UUID, World> entry : partyScopeWorlds.entrySet()) {
            bindPartyScope(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<UUID, World> entry : lobbyScopeWorlds.entrySet()) {
            bindLobbyScope(entry.getKey(), entry.getValue());
        }
    }

    /** Binds the file's {@code scope: party} definitions to a loaded board world. */
    public void openPartyScope(UUID partyId, World world) {
        if (partyId == null || world == null) {
            return;
        }
        partyScopeWorlds.put(partyId, world);
        if (enabled) {
            bindPartyScope(partyId, world);
        }
    }

    /** Binds the file's {@code scope: lobby} definitions to a loaded lobby clone. */
    public void openLobbyScope(UUID partyId, World world) {
        if (partyId == null || world == null) {
            return;
        }
        lobbyScopeWorlds.put(partyId, world);
        if (enabled) {
            bindLobbyScope(partyId, world);
        }
    }

    public void closePartyScope(UUID partyId) {
        if (partyId == null) {
            return;
        }
        partyScopeWorlds.remove(partyId);
        Map<String, RuntimeHologram> scope = partyScopes.remove(partyId);
        if (scope != null) {
            scope.values().forEach(RuntimeHologram::hideAll);
        }
    }

    public void closeLobbyScope(UUID partyId) {
        if (partyId == null) {
            return;
        }
        lobbyScopeWorlds.remove(partyId);
        Map<String, RuntimeHologram> scope = lobbyScopes.remove(partyId);
        if (scope != null) {
            scope.values().forEach(RuntimeHologram::hideAll);
        }
    }

    public List<String> ids() {
        return holograms.keySet().stream().sorted().toList();
    }

    public List<String> allIds() {
        java.util.Set<String> ids = new java.util.TreeSet<>(holograms.keySet());
        ids.addAll(partyTemplates.keySet());
        ids.addAll(lobbyTemplates.keySet());
        return List.copyOf(ids);
    }

    public Optional<String> scopeOf(String id) {
        String normalized = normalize(id);
        if (holograms.containsKey(normalized)) return Optional.of("global");
        if (partyTemplates.containsKey(normalized)) return Optional.of("party");
        if (lobbyTemplates.containsKey(normalized)) return Optional.of("lobby");
        return Optional.empty();
    }

    public Optional<HologramDefinition> definition(String id) {
        RuntimeHologram runtime = holograms.get(normalize(id));
        return runtime == null ? Optional.empty() : Optional.of(runtime.definition);
    }

    /**
     * Finds a visible packet hologram under the player's crosshair. Bukkit cannot ray trace
     * these displays because they exist only in packets, so this uses the display position.
     */
    public Optional<HologramInteractionTarget> raycast(Player player, double maxDistance) {
        if (!enabled || player == null || maxDistance <= 0.0) {
            return Optional.empty();
        }
        Location eye = player.getEyeLocation();
        Vector origin = eye.toVector();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() == 0.0) {
            return Optional.empty();
        }
        direction.normalize();

        HologramInteractionTarget closest = null;
        for (RuntimeHologram hologram : runtimeSnapshot()) {
            if (!hologram.viewers.containsKey(player.getUniqueId())) {
                continue;
            }
            Location location = hologram.definition.location().resolve();
            if (location == null || !location.getWorld().equals(player.getWorld())) {
                continue;
            }
            Vector offset = location.toVector().subtract(origin);
            double distance = offset.dot(direction);
            if (distance < 0.0 || distance > maxDistance) {
                continue;
            }
            double radius = Math.max(0.35, hologram.definition.style().scale() * 0.5);
            Vector nearest = direction.clone().multiply(distance);
            if (offset.clone().subtract(nearest).lengthSquared() > radius * radius) {
                continue;
            }
            if (closest == null || distance < closest.distance()) {
                closest = new HologramInteractionTarget(hologram.definition.id(), hologram.scopeId,
                        location, distance);
            }
        }
        return Optional.ofNullable(closest);
    }

    public boolean create(String id, Location location) {
        if (!enabled || location == null) {
            return false;
        }
        String normalized = normalize(id);
        if (!validId(normalized) || holograms.containsKey(normalized) || partyTemplates.containsKey(normalized)
                || lobbyTemplates.containsKey(normalized)
                || location.getWorld() == null) {
            return false;
        }
        HologramDefinition definition = new HologramDefinition(
                normalized,
                HologramLocation.from(location),
                List.of("<gold>New hologram</gold>"),
                List.of(),
                HologramStyle.defaults(defaultViewRange),
                20,
                "all",
                "",
                "global"
        );
        holograms.put(normalized, new RuntimeHologram(definition));
        save();
        return true;
    }

    public boolean remove(String id) {
        if (!enabled) return false;
        String normalized = normalize(id);
        RuntimeHologram removed = holograms.remove(normalized);
        if (removed != null) {
            removed.hideAll();
            save();
            return true;
        }
        if (partyTemplates.remove(normalized) != null) {
            partyTemplateBundles.remove(normalized);
            rebindPartyScopes();
            save();
            return true;
        }
        if (lobbyTemplates.remove(normalized) != null) {
            lobbyTemplateBundles.remove(normalized);
            rebindLobbyScopes();
            save();
            return true;
        }
        return false;
    }

    private void rebindPartyScopes() {
        for (Map.Entry<UUID, World> entry : partyScopeWorlds.entrySet()) {
            bindPartyScope(entry.getKey(), entry.getValue());
        }
    }

    private void rebindLobbyScopes() {
        for (Map.Entry<UUID, World> entry : lobbyScopeWorlds.entrySet()) {
            bindLobbyScope(entry.getKey(), entry.getValue());
        }
    }

    private Optional<HologramDefinition> editableDefinition(String id) {
        String normalized = normalize(id);
        RuntimeHologram runtime = holograms.get(normalized);
        if (runtime != null) {
            return Optional.of(runtime.definition);
        }
        HologramDefinition party = partyTemplates.get(normalized);
        if (party != null) {
            return Optional.of(party);
        }
        return Optional.ofNullable(lobbyTemplates.get(normalized));
    }

    private boolean replaceEditableDefinition(HologramDefinition definition) {
        String id = definition.id();
        RuntimeHologram runtime = holograms.get(id);
        if (runtime != null) {
            runtime.replace(definition);
            return true;
        }
        if (partyTemplates.containsKey(id)) {
            partyTemplates.put(id, definition);
            partyTemplateBundles.put(id, compile(definition));
            rebindPartyScopes();
            return true;
        }
        if (lobbyTemplates.containsKey(id)) {
            lobbyTemplates.put(id, definition);
            lobbyTemplateBundles.put(id, compile(definition));
            rebindLobbyScopes();
            return true;
        }
        return false;
    }

    private boolean replaceLines(String id, java.util.function.UnaryOperator<List<String>> updater) {
        if (!enabled) return false;
        Optional<HologramDefinition> current = editableDefinition(id);
        if (current.isEmpty()) return false;
        List<String> lines = new ArrayList<>(current.get().lines());
        List<String> updated = updater.apply(lines);
        if (updated == null) return false;
        if (!replaceEditableDefinition(copy(current.get(), current.get().location(), updated))) {
            return false;
        }
        save();
        return true;
    }

    public boolean move(String id, Location location) {
        if (!enabled || location == null) return false;
        if (location.getWorld() == null) return false;
        Optional<HologramDefinition> current = editableDefinition(id);
        if (current.isEmpty()) return false;
        HologramLocation moved = HologramLocation.from(location);
        if (!current.get().scope().equals("global")) {
            moved = moved.inWorld(current.get().location().world());
        }
        if (!replaceEditableDefinition(copy(current.get(), moved, current.get().lines()))) {
            return false;
        }
        save();
        return true;
    }

    public boolean setLine(String id, int oneBasedLine, String text) {
        if (oneBasedLine < 1) return false;
        return replaceLines(id, lines -> {
            if (oneBasedLine > lines.size()) return null;
            lines.set(oneBasedLine - 1, text == null ? "" : text);
            return lines;
        });
    }

    public boolean addLine(String id, String text) {
        return replaceLines(id, lines -> {
            lines.add(text == null ? "" : text);
            return lines;
        });
    }

    public boolean removeLine(String id, int oneBasedLine) {
        if (oneBasedLine < 1) return false;
        return replaceLines(id, lines -> {
            if (oneBasedLine > lines.size()) return null;
            lines.remove(oneBasedLine - 1);
            return lines;
        });
    }

    public void save() {
        if (!enabled) return;
        List<HologramDefinition> definitions = new ArrayList<>(partyTemplates.values());
        definitions.addAll(lobbyTemplates.values());
        definitions.addAll(holograms.values().stream().map(runtime -> runtime.definition).toList());
        repository.save(definitions);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        hideAll();
        holograms.clear();
        partyTemplates.clear();
        partyTemplateBundles.clear();
        lobbyTemplates.clear();
        lobbyTemplateBundles.clear();
        partyScopes.clear();
        lobbyScopes.clear();
        partyScopeWorlds.clear();
        lobbyScopeWorlds.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (enabled) {
            plugin.getServer().getScheduler().runTask(plugin, () -> scanPlayer(event.getPlayer()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forEachRuntime(hologram -> hologram.hide(event.getPlayer()));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        forEachRuntime(hologram -> hologram.hide(event.getPlayer()));
        if (enabled) {
            plugin.getServer().getScheduler().runTask(plugin, () -> scanPlayer(event.getPlayer()));
        }
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        forEachRuntime(hologram -> hologram.hide(event.getPlayer()));
        if (enabled) {
            plugin.getServer().getScheduler().runTask(plugin, () -> scanPlayer(event.getPlayer()));
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        String worldName = event.getWorld().getName();
        forEachRuntime(hologram -> {
            if (hologram.definition.location().world().equals(worldName)) {
                hologram.hideAll();
            }
        });
    }

    private void tick() {
        if (!enabled) return;
        tick++;
        if (tick % scanIntervalTicks == 0) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                scanPlayer(player);
            }
        }
        forEachRuntime(hologram -> {
            if (tick % hologram.definition.refreshTicks() == 0 || hologram.hasFramesAt(tick)) {
                hologram.updateVisible(tick);
            }
        });
    }

    private void scanPlayer(Player player) {
        forEachRuntime(hologram -> {
            if (shouldShow(player, hologram)) {
                hologram.show(player, tick);
            } else {
                hologram.hide(player);
            }
        });
    }

    private boolean shouldShow(Player player, RuntimeHologram hologram) {
        HologramDefinition definition = hologram.definition;
        if (hologram.scopeId != null && !scopeVisibility.test(hologram.scopeId, player)) {
            return false;
        }
        if (!player.getWorld().getName().equals(definition.location().world())) return false;
        Location location = definition.location().resolve();
        float range = definition.style().viewRange();
        if (location == null || location.distanceSquared(player.getLocation()) > range * range) {
            return false;
        }
        if (definition.visibilityMode().equals("permission")
                && !definition.permission().isBlank()
                && !player.hasPermission(definition.permission())) {
            return false;
        }
        return !definition.visibilityMode().equals("manual");
    }

    private void hideAll() {
        for (RuntimeHologram hologram : holograms.values()) {
            hologram.hideAll();
        }
        for (Map<String, RuntimeHologram> scope : partyScopes.values()) {
            scope.values().forEach(RuntimeHologram::hideAll);
        }
        for (Map<String, RuntimeHologram> scope : lobbyScopes.values()) {
            scope.values().forEach(RuntimeHologram::hideAll);
        }
    }

    private void registerBuiltins() {
        registerPlaceholder("mcparty.player_name", context -> Component.text(context.player().getName()));
        registerPlaceholder("mcparty.player_uuid", context -> Component.text(context.player().getUniqueId().toString()));
        registerPlaceholder("mcparty.world", context -> Component.text(context.player().getWorld().getName()));
        registerPlaceholder("mcparty.online_players", context -> Component.text(Bukkit.getOnlinePlayers().size()));
        registerPlaceholder("mcparty.time", context -> Component.text(java.time.LocalTime.now().withNano(0).toString()));
        registerPlaceholder("mcparty.tick", context -> Component.text(context.tick()));
    }

    private void bindPartyScope(UUID partyId, World world) {
        bindScope(partyId, world, partyTemplates, partyTemplateBundles, partyScopes);
    }

    private void bindLobbyScope(UUID partyId, World world) {
        bindScope(partyId, world, lobbyTemplates, lobbyTemplateBundles, lobbyScopes);
    }

    private void bindScope(
            UUID scopeId,
            World world,
            Map<String, HologramDefinition> templates,
            Map<String, TemplateBundle> bundles,
            Map<UUID, Map<String, RuntimeHologram>> scopes
    ) {
        Map<String, RuntimeHologram> previous = scopes.remove(scopeId);
        if (previous != null) {
            previous.values().forEach(RuntimeHologram::hideAll);
        }
        Map<String, RuntimeHologram> scope = new ConcurrentHashMap<>();
        for (HologramDefinition template : templates.values()) {
            HologramDefinition bound = new HologramDefinition(
                    template.id(), template.location().inWorld(world.getName()), template.lines(), template.frames(),
                    template.style(), template.refreshTicks(), template.visibilityMode(), template.permission(), template.scope()
            );
            scope.put(bound.id(), new RuntimeHologram(bound, scopeId, bundles.get(template.id())));
        }
        scopes.put(scopeId, scope);
    }

    private void forEachRuntime(Consumer<RuntimeHologram> consumer) {
        holograms.values().forEach(consumer);
        for (Map<String, RuntimeHologram> scope : partyScopes.values()) {
            scope.values().forEach(consumer);
        }
        for (Map<String, RuntimeHologram> scope : lobbyScopes.values()) {
            scope.values().forEach(consumer);
        }
    }

    private List<RuntimeHologram> runtimeSnapshot() {
        List<RuntimeHologram> result = new ArrayList<>(holograms.values());
        for (Map<String, RuntimeHologram> scope : partyScopes.values()) {
            result.addAll(scope.values());
        }
        for (Map<String, RuntimeHologram> scope : lobbyScopes.values()) {
            result.addAll(scope.values());
        }
        return result;
    }

    private static TemplateBundle compile(HologramDefinition definition) {
        return new TemplateBundle(
                new HologramTemplateSet(definition.lines()),
                definition.frames().stream()
                        .map(frame -> new FrameTemplate(frame.durationTicks(), new HologramTemplateSet(frame.lines())))
                        .toList()
        );
    }

    private HologramRenderer createRenderer() {
        try {
            return new PacketTextDisplayRenderer();
        } catch (NoClassDefFoundError | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not initialize PacketEvents hologram renderer; holograms are disabled", e);
            return null;
        }
    }

    private static String normalize(String id) {
        return id == null ? "" : id.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean validId(String id) {
        return id.matches("[a-z0-9_-]{1,64}");
    }

    private static boolean validRange(float range) {
        return Float.isFinite(range) && range > 0.0f;
    }

    private static HologramDefinition copy(HologramDefinition old, HologramLocation location, List<String> lines) {
        return new HologramDefinition(
                old.id(), location, lines, old.frames(), old.style(), old.refreshTicks(),
                old.visibilityMode(), old.permission(), old.scope()
        );
    }

    private final class RuntimeHologram {
        private final UUID scopeId;
        private HologramDefinition definition;
        private HologramTemplateSet baseTemplate;
        private List<FrameTemplate> frames;
        private final Map<UUID, ViewerState> viewers = new ConcurrentHashMap<>();
        private long lastFrameTick = Long.MIN_VALUE;

        private RuntimeHologram(HologramDefinition definition) {
            this(definition, null, null);
        }

        private RuntimeHologram(HologramDefinition definition, UUID scopeId, TemplateBundle templates) {
            this.scopeId = scopeId;
            replace(definition, templates == null ? compile(definition) : templates);
        }

        private void replace(HologramDefinition definition) {
            replace(definition, compile(definition));
        }

        private void replace(HologramDefinition definition, TemplateBundle templates) {
            hideAll();
            this.definition = definition;
            this.baseTemplate = templates.base();
            this.frames = templates.frames();
            this.lastFrameTick = Long.MIN_VALUE;
        }

        private boolean hasFramesAt(long currentTick) {
            if (frames.isEmpty()) return false;
            if (lastFrameTick == Long.MIN_VALUE) return true;
            return currentFrame(currentTick) != currentFrame(lastFrameTick);
        }

        private void show(Player player, long currentTick) {
            ViewerState state = viewers.get(player.getUniqueId());
            HologramView view = render(player, currentTick);
            if (state == null) {
                viewers.put(player.getUniqueId(), new ViewerState(renderer.show(player, view), view));
                return;
            }
            if (!state.view().equals(view)) {
                renderer.update(player, state.handle(), view);
                viewers.put(player.getUniqueId(), new ViewerState(state.handle(), view));
            }
        }

        private void updateVisible(long currentTick) {
            lastFrameTick = currentTick;
            for (UUID id : viewers.keySet()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null && player.isOnline()) {
                    show(player, currentTick);
                } else {
                    viewers.remove(id);
                }
            }
        }

        private void hide(Player player) {
            ViewerState state = viewers.remove(player.getUniqueId());
            if (state != null) renderer.hide(player, state.handle());
        }

        private void hideAll() {
            for (UUID id : new ArrayList<>(viewers.keySet())) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) hide(player);
                else viewers.remove(id);
            }
        }

        private HologramView render(Player player, long currentTick) {
            HologramTemplateSet template = baseTemplate;
            if (!frames.isEmpty()) {
                template = frames.get(currentFrame(currentTick)).template();
            }
            HologramViewerContext context = new HologramViewerContext(player, definition, currentTick);
            return new HologramView(definition, template.render(context, placeholders));
        }

        private int currentFrame(long currentTick) {
            long total = frames.stream().mapToLong(FrameTemplate::durationTicks).sum();
            long position = Math.floorMod(currentTick, total);
            for (int i = 0; i < frames.size(); i++) {
                long duration = frames.get(i).durationTicks();
                if (position < duration) return i;
                position -= duration;
            }
            return frames.size() - 1;
        }
    }

    private record FrameTemplate(long durationTicks, HologramTemplateSet template) {
    }

    private record TemplateBundle(HologramTemplateSet base, List<FrameTemplate> frames) {
    }

    private record ViewerState(HologramRenderer.Handle handle, HologramView view) {
    }
}
