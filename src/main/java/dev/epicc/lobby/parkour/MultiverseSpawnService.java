package dev.epicc.lobby.parkour;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.logging.Level;

/** Resolves a world's Multiverse-Core spawn without making Multiverse-Core mandatory. */
public final class MultiverseSpawnService {

    private final JavaPlugin plugin;

    public MultiverseSpawnService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Location spawnFor(World world) {
        Plugin multiverse = plugin.getServer().getPluginManager().getPlugin("Multiverse-Core");
        if (multiverse == null || !multiverse.isEnabled()) {
            return world.getSpawnLocation();
        }

        try {
            ClassLoader classLoader = multiverse.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("org.mvplugins.multiverse.core.MultiverseCoreApi", true, classLoader);
            if (!(boolean) apiClass.getMethod("isLoaded").invoke(null)) {
                return world.getSpawnLocation();
            }

            Object api = apiClass.getMethod("get").invoke(null);
            Object worldManager = apiClass.getMethod("getWorldManager").invoke(api);
            Object result = worldManager.getClass()
                    .getMethod("getWorldByNameOrAlias", String.class)
                    .invoke(worldManager, world.getName());
            Method isDefined = result.getClass().getMethod("isDefined");
            if (!(boolean) isDefined.invoke(result)) {
                return world.getSpawnLocation();
            }

            Object multiverseWorld = result.getClass().getMethod("get").invoke(result);
            Class<?> worldClass = Class.forName(
                    "org.mvplugins.multiverse.core.world.MultiverseWorld", true, classLoader
            );
            return ((Location) worldClass.getMethod("getSpawnLocation").invoke(multiverseWorld)).clone();
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not resolve the Multiverse-Core spawn for " + world.getName() + "; using the Bukkit spawn.",
                    exception
            );
            return world.getSpawnLocation();
        }
    }
}
