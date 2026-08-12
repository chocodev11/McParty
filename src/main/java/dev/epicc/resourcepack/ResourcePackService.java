package dev.epicc.resourcepack;

import dev.epicc.config.MessageService;
import dev.epicc.config.PluginConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.logging.Level;

/**
 * Prepares and prompts the McParty dice resource pack.
 * <p>
 * Modes: {@code local} (zip + HTTP on the machine) or {@code external} (CDN URL + SHA-1).
 */
public final class ResourcePackService {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes(
            "mcparty-resource-pack".getBytes(StandardCharsets.UTF_8)
    );

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final MessageService messages;
    private final FontImageService fontImages;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    private String packUrl = "";
    private String packSha1 = "";
    private byte[] packBytes = new byte[0];
    private HttpServer httpServer;

    public ResourcePackService(
            JavaPlugin plugin,
            PluginConfig config,
            MessageService messages,
            FontImageService fontImages
    ) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.fontImages = fontImages;
    }

    public UUID packId() {
        return PACK_ID;
    }

    public boolean isReady() {
        return ready.get();
    }

    public boolean enabled() {
        return config.resourcePackEnabled();
    }

    public boolean sendOnJoin() {
        return "join".equalsIgnoreCase(config.resourcePackSendOn());
    }

    public boolean sendOnParty() {
        return "party".equalsIgnoreCase(config.resourcePackSendOn());
    }

    public boolean kickOnDecline() {
        return config.resourcePackKickOnDecline();
    }

    public Component kickMessage() {
        return messages.get("resource-pack.kick");
    }

    /** Remove the obsolete deployed source directory from older versions. */
    public void removeLegacySourcePack() {
        Path legacySource = plugin.getDataFolder().toPath().resolve("resourcepack");
        if (!Files.exists(legacySource, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            deleteTree(legacySource);
            plugin.getLogger().info("Removed obsolete deployed resource pack source: " + legacySource);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not remove obsolete deployed resource pack source: " + legacySource,
                    exception);
        }
    }

    public void start() {
        if (!config.resourcePackEnabled()) {
            plugin.getLogger().info("Resource pack disabled");
            return;
        }

        String mode = config.resourcePackMode() == null
                ? "local"
                : config.resourcePackMode().trim().toLowerCase(Locale.ROOT);

        try {
            if ("external".equals(mode)) {
                startExternal();
            } else if ("local".equals(mode)) {
                startLocal();
            } else {
                plugin.getLogger().warning("Unknown resource-pack.mode '" + mode + "' (use local|external)");
                return;
            }
            ready.set(true);
            plugin.getLogger().info("Resource pack ready (" + mode + "): " + packUrl + " sha1=" + packSha1);
        } catch (Exception e) {
            ready.set(false);
            plugin.getLogger().severe("Resource pack failed to start: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Stop HTTP (if any), re-zip the bundled pack, and start again using current config. */
    public void reload() {
        shutdown();
        start();
    }

    public void shutdown() {
        ready.set(false);
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        packBytes = new byte[0];
        packUrl = "";
        packSha1 = "";
    }

    /**
     * Schedule pack prompt after configured delay (client connection ready).
     */
    public void offerLater(Player player) {
        if (!isReady() || player == null || !player.isOnline()) {
            return;
        }
        int delay = config.resourcePackSendDelayTicks();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> offerNow(player), delay);
    }

    public void offerNow(Player player) {
        if (!isReady() || player == null || !player.isOnline()) {
            return;
        }
        Component prompt = messages.get("resource-pack.prompt");
        player.setResourcePack(
                PACK_ID,
                packUrl,
                packSha1,
                prompt,
                config.resourcePackRequired()
        );
    }

    private void startExternal() {
        String url = config.resourcePackExternalUrl();
        String sha1 = config.resourcePackExternalSha1();
        if (url.isEmpty()) {
            throw new IllegalStateException("resource-pack.external.url is empty");
        }
        if (!isValidSha1(sha1)) {
            throw new IllegalStateException(
                    "resource-pack.external.sha1 must be 40-char lowercase hex of the zip"
            );
        }
        try {
            URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("resource-pack.external.url is not a valid URI: " + url);
        }
        this.packUrl = url;
        this.packSha1 = sha1;
        fontImages.warnExternalPackRequirement();
    }

    private void startLocal() throws IOException, NoSuchAlgorithmException {
        Path data = plugin.getDataFolder().toPath();
        Files.createDirectories(data);

        String zipName = sanitizeZipName(config.resourcePackLocalZipName());
        // Written under plugins/McParty/output/ for easy fetch / external hosting.
        Path outDir = data.resolve("output");
        Files.createDirectories(outDir);
        Path zipPath = outDir.resolve(zipName);
        packBytes = zipBundledPack();
        Files.write(zipPath, packBytes);
        packSha1 = sha1Hex(packBytes);
        plugin.getLogger().info("Resource pack zip written to " + zipPath);

        String publicUrl = config.resourcePackLocalPublicUrl();
        if (publicUrl.isEmpty()) {
            publicUrl = "http://127.0.0.1:" + config.resourcePackLocalPort() + "/" + zipName;
            plugin.getLogger().warning(
                    "resource-pack.local.public-url is empty — using " + publicUrl
                            + " (only works for clients on this machine). Set public-url for remote players."
            );
        }
        this.packUrl = publicUrl;

        startHttpServer(zipName);
    }

    private void startHttpServer(String zipName) throws IOException {
        String bind = config.resourcePackLocalBind();
        if (bind == null || bind.isBlank()) {
            bind = "0.0.0.0";
        }
        int port = config.resourcePackLocalPort();
        InetSocketAddress address = new InetSocketAddress(bind, port);
        httpServer = HttpServer.create(address, 0);

        String path = "/" + zipName;
        byte[] bytes = packBytes;
        httpServer.createContext(path, exchange -> servePack(exchange, bytes, zipName));
        // Convenience: also serve at /
        httpServer.createContext("/", exchange -> {
            if ("/".equals(exchange.getRequestURI().getPath())
                    || exchange.getRequestURI().getPath().isEmpty()) {
                exchange.getResponseHeaders().add("Location", path);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        httpServer.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "McParty-ResourcePack-HTTP");
            t.setDaemon(true);
            return t;
        }));
        httpServer.start();
        plugin.getLogger().info("Resource pack HTTP listening on " + bind + ":" + port + path);
    }

    private static void servePack(HttpExchange exchange, byte[] bytes, String zipName) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + zipName + "\"");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        if ("HEAD".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Zip the resource-pack entries embedded in this plugin JAR. */
    private byte[] zipBundledPack() throws IOException {
        Path jarPath = pluginJarPath();
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            throw new IOException("Bundled resource pack is unavailable outside the plugin JAR");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Set<String> entries = new LinkedHashSet<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> jarEntries = jar.entries();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                while (jarEntries.hasMoreElements()) {
                    JarEntry entry = jarEntries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith("resourcepack/") || name.endsWith("/")) {
                        continue;
                    }
                    String relative = name.substring("resourcepack/".length());
                    if (relative.isEmpty() || relative.equals("README.md")
                            || relative.equals("assets/mcparty/font/images.json")) {
                        continue;
                    }
                    zos.putNextEntry(new ZipEntry(relative));
                    try (var in = jar.getInputStream(entry)) {
                        in.transferTo(zos);
                    }
                    zos.closeEntry();
                    entries.add(relative);
                }

                zos.putNextEntry(new ZipEntry("assets/mcparty/font/images.json"));
                zos.write(fontImages.resourcePackFontJson(entries).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        if (entries.isEmpty()) {
            throw new IOException("No bundled resource-pack entries found in " + jarPath);
        }
        return bos.toByteArray();
    }

    private Path pluginJarPath() {
        try {
            var cs = getClass().getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            Path path = Path.of(cs.getLocation().toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String sha1Hex(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] dig = md.digest(data);
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static boolean isValidSha1(String sha1) {
        if (sha1 == null || sha1.length() != 40) {
            return false;
        }
        for (int i = 0; i < 40; i++) {
            char c = sha1.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static String sanitizeZipName(String name) {
        if (name == null || name.isBlank()) {
            return "mcparty.zip";
        }
        String n = name.trim().replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        if (!n.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            n = n + ".zip";
        }
        return n;
    }

    public void notifyDeclined(Player player) {
        messages.send(player, "resource-pack.declined");
    }

    public void notifyFailed(Player player, String reason) {
        messages.send(player, "resource-pack.failed", "reason", reason == null ? "" : reason);
    }
}
