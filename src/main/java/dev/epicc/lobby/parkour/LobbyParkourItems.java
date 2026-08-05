package dev.epicc.lobby.parkour;

import dev.epicc.config.MessageService;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.persistence.PersistentDataType;

public final class LobbyParkourItems {

    private static final String KEY_ACTION = "lobby_parkour_action";
    static final String RESTART = "restart";
    static final String CHECKPOINT = "checkpoint";
    static final String LEAVE = "leave";

    private LobbyParkourItems() {
    }

    public static ItemStack restart(Plugin plugin, MessageService messages) {
        return create(plugin, Material.PRISMARINE_SHARD, RESTART, messages.get("parkour.item-restart"));
    }

    public static ItemStack checkpoint(Plugin plugin, MessageService messages) {
        return create(plugin, Material.AMETHYST_SHARD, CHECKPOINT, messages.get("parkour.item-checkpoint"));
    }

    public static ItemStack leave(Plugin plugin, MessageService messages) {
        return create(plugin, Material.ECHO_SHARD, LEAVE, messages.get("parkour.item-leave"));
    }

    public static String action(Plugin plugin, ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(key(plugin), PersistentDataType.STRING);
    }

    private static ItemStack create(Plugin plugin, Material material, String action, net.kyori.adventure.text.Component name) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, action);
        });
        return stack;
    }

    private static NamespacedKey key(Plugin plugin) {
        return new NamespacedKey(plugin, KEY_ACTION);
    }
}
