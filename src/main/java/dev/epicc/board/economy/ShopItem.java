package dev.epicc.board.economy;

import dev.epicc.board.dice.DiceSkin;

import java.util.Optional;

/** Coin shop catalogue (Cytooxien "Münzladen"). Ids match config and message keys. */
public enum ShopItem {
    DOUBLE_COINS("double-coins", 30, null),
    CHAOS_DIE("chaos-die", 40, DiceSkin.CHAOS),
    WARP_PIPE("warp-pipe", 100, null),
    BRONZE_DIE("bronze-die", 20, DiceSkin.BRONZE),
    SILVER_DIE("silver-die", 35, DiceSkin.SILVER),
    GOLD_DIE("gold-die", 50, DiceSkin.GOLD);

    /** Held items occupy hotbar slots 3–8 during the prep phase. */
    private static final int FIRST_HOTBAR_SLOT = 3;

    private final String id;
    private final int defaultPrice;
    private final DiceSkin die;

    ShopItem(String id, int defaultPrice, DiceSkin die) {
        this.id = id;
        this.defaultPrice = defaultPrice;
        this.die = die;
    }

    public String id() {
        return id;
    }

    public int defaultPrice() {
        return defaultPrice;
    }

    /** The extra die this item adds to a roll, if it is a die item. */
    public Optional<DiceSkin> die() {
        return Optional.ofNullable(die);
    }

    public int hotbarSlot() {
        return FIRST_HOTBAR_SLOT + ordinal();
    }

    public static Optional<ShopItem> byId(String id) {
        for (ShopItem item : values()) {
            if (item.id.equals(id)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    /** The shop die matching a placement bonus skin, for shared names. */
    public static Optional<ShopItem> forDie(DiceSkin skin) {
        for (ShopItem item : values()) {
            if (item.die == skin) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }
}
