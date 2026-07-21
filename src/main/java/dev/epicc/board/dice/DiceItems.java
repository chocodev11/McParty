package dev.epicc.board.dice;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

/**
 * ItemStacks for dice faces. Clients need the {@code mcparty} resource pack
 * ({@code item_model} {@code mcparty:dice_1} … {@code dice_6}).
 */
public final class DiceItems {

    public static final String NAMESPACE = "mcparty";

    private DiceItems() {
    }

    public static ItemStack face(int face) {
        int f = Math.max(1, face);
        ItemStack stack = new ItemStack(Material.PAPER);
        stack.editMeta(meta -> {
            meta.itemName(Component.text("Dice " + f));
            if (f <= 6) {
                meta.setItemModel(new NamespacedKey(NAMESPACE, "dice_" + f));
            }
        });
        return stack;
    }
}
