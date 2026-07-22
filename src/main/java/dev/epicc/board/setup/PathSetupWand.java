package dev.epicc.board.setup;

import dev.epicc.config.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Blaze rod used during path setup. Break a block while holding it to place a space
 * (break is cancelled). Identified via custom_data PDC, not display name alone.
 */
public final class PathSetupWand {

    private static final Material MATERIAL = Material.BLAZE_ROD;
    private static final String KEY_ID = "path_stick";

    private PathSetupWand() {
    }

    public static NamespacedKey key(Plugin plugin) {
        return new NamespacedKey(plugin, KEY_ID);
    }

    public static ItemStack create(Plugin plugin, MessageService messages) {
        ItemStack stack = new ItemStack(MATERIAL);
        stack.editMeta(meta -> {
            meta.displayName(messages.get("path.stick-name").decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    messages.get("path.stick-lore-1").decoration(TextDecoration.ITALIC, false),
                    messages.get("path.stick-lore-2").decoration(TextDecoration.ITALIC, false)
            ));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    public static boolean isWand(Plugin plugin, ItemStack stack) {
        if (stack == null || stack.getType() != MATERIAL || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta.getPersistentDataContainer().has(key(plugin), PersistentDataType.BYTE);
    }

    public static void give(Plugin plugin, Player player, MessageService messages) {
        ItemStack wand = create(plugin, messages);
        // Prefer empty main hand, else add to inventory
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            player.getInventory().setItemInMainHand(wand);
        } else {
            player.getInventory().addItem(wand).values().forEach(left ->
                    player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
    }

    public static void removeAll(Plugin plugin, Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isWand(plugin, contents[i])) {
                player.getInventory().setItem(i, null);
            }
        }
        if (isWand(plugin, player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
        }
    }
}
