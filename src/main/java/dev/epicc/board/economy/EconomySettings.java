package dev.epicc.board.economy;

import java.util.Map;

/** Board economy config captured when a party starts, so a reload never changes a running game. */
public record EconomySettings(
        boolean placementBonusDice,
        boolean shopEnabled,
        int prepSeconds,
        int priceIncreasePercent,
        int maxPurchasesPerRound,
        int maxUsesPerRound,
        Map<ShopItem, Integer> prices
) {
    public EconomySettings {
        prices = Map.copyOf(prices);
    }

    /** Base price, or 0 when the item is not offered. */
    public int basePrice(ShopItem item) {
        return Math.max(0, prices.getOrDefault(item, 0));
    }
}
