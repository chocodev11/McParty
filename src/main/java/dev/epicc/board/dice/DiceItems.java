package dev.epicc.board.dice;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

/**
 * ItemStacks for dice faces. Clients need the {@code mcparty} resource pack
 * ({@code item_model} {@code mcparty:dice_1} … {@code dice_6} and the skin variants).
 */
public final class DiceItems {

    public static final String NAMESPACE = "mcparty";

    private DiceItems() {
    }

    public static ItemStack face(int face) {
        return face(DiceSkin.STANDARD, face);
    }

    /** Faces without a shipped model stay plain paper. */
    public static ItemStack face(DiceSkin skin, int face) {
        ItemStack stack = new ItemStack(Material.PAPER);
        stack.editMeta(meta -> {
            meta.itemName(Component.text("Dice " + face));
            skin.assetId(face).ifPresent(id -> meta.setItemModel(new NamespacedKey(NAMESPACE, id)));
        });
        return stack;
    }
}
