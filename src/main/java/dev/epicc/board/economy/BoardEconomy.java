package dev.epicc.board.economy;

import dev.epicc.board.Dice;
import dev.epicc.board.dice.DiceSkin;
import dev.epicc.board.dice.DieSpec;
import dev.epicc.minigame.MinigameResult;
import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Board economy rules for one party: minigame payouts, placement bonus dice and the coin shop.
 * Holds no Bukkit state so the rules are unit-testable; {@link PrepPhase} renders it.
 */
public final class BoardEconomy {

    public enum Purchase { OK, CLOSED, LIMIT, NOT_ENOUGH_COINS }

    public enum Use { OK, NOT_OWNED, LOCKED, LIMIT, ALREADY_ACTIVE }

    public record Payout(UUID playerId, int coins, boolean doubled) {
    }

    public record BonusDie(UUID playerId, int place, DiceSkin skin) {
    }

    /** Index = placement − 1. */
    private static final DiceSkin[] PLACEMENT_DICE = {DiceSkin.GOLD, DiceSkin.SILVER, DiceSkin.BRONZE};

    private final EconomySettings settings;
    private final Map<UUID, Account> accounts = new HashMap<>();

    public BoardEconomy(EconomySettings settings) {
        this.settings = settings;
    }

    public EconomySettings settings() {
        return settings;
    }

    /**
     * Credit minigame coins; an armed Double Coins doubles that player's total once. A result
     * without coins (failed arena, nobody online) pays nothing and keeps Double Coins armed.
     */
    public List<Payout> payout(PartyInstance instance, MinigameResult result) {
        List<Payout> payouts = new ArrayList<>();
        result.coinRewards().forEach((id, coins) -> instance.player(id).ifPresent(player -> {
            Account account = account(id);
            boolean doubled = account.doubleCoins;
            int paid = doubled ? coins * 2 : coins;
            account.doubleCoins = false;
            player.addCoins(paid);
            payouts.add(new Payout(id, paid, doubled));
        }));
        return payouts;
    }

    /** 1st/2nd/3rd of a minigame with at least two placed players roll an extra gold/silver/bronze die next. */
    public List<BonusDie> awardBonusDice(PartyInstance instance, MinigameResult result) {
        if (!settings.placementBonusDice() || result.placements().size() < 2) {
            return List.of();
        }
        List<BonusDie> awarded = new ArrayList<>();
        result.placements().forEach((id, place) -> {
            if (place < 1 || place > PLACEMENT_DICE.length || instance.player(id).isEmpty()) {
                return;
            }
            DiceSkin skin = PLACEMENT_DICE[place - 1];
            account(id).bonusDie = skin;
            awarded.add(new BonusDie(id, place, skin));
        });
        awarded.sort(Comparator.comparingInt(BonusDie::place));
        return awarded;
    }

    /** This player's dice for the roll — standard, placement bonus, then used die items — consumed. */
    public List<DieSpec> takeRollDice(UUID id, Dice standard) {
        List<DieSpec> specs = new ArrayList<>(3);
        specs.add(DieSpec.standard(standard));
        Account account = accounts.get(id);
        if (account == null) {
            return specs;
        }
        if (account.bonusDie != null) {
            specs.add(DieSpec.of(account.bonusDie));
            account.bonusDie = null;
        }
        for (DiceSkin skin : account.itemDice) {
            specs.add(DieSpec.of(skin));
        }
        account.itemDice.clear();
        return specs;
    }

    public boolean offered(ShopItem item) {
        return settings.shopEnabled() && settings.basePrice(item) > 0;
    }

    /** Base price plus {@code price-increase-percent} of it per earlier purchase by this player. */
    public int price(UUID id, ShopItem item) {
        int base = settings.basePrice(item);
        Account account = accounts.get(id);
        int bought = account == null ? 0 : account.purchases.getOrDefault(item, 0);
        return base + base * settings.priceIncreasePercent() * bought / 100;
    }

    /** Bought items become usable from the next round. */
    public Purchase buy(PartyPlayer player, ShopItem item, int round) {
        if (!offered(item)) {
            return Purchase.CLOSED;
        }
        Account account = account(player.uuid());
        if (account.purchasesIn(round) >= settings.maxPurchasesPerRound()) {
            return Purchase.LIMIT;
        }
        if (!player.spendCoins(price(player.uuid(), item))) {
            return Purchase.NOT_ENOUGH_COINS;
        }
        account.held.computeIfAbsent(item, ignored -> new ArrayList<>()).add(round + 1);
        account.purchases.merge(item, 1, Integer::sum);
        account.countPurchase(round);
        return Purchase.OK;
    }

    /** Use one held unit. A Warp Pipe is only consumed here; the caller moves the player. */
    public Use use(UUID id, ShopItem item, int round) {
        if (usable(id, item, round) == 0) {
            return locked(id, item, round) > 0 ? Use.LOCKED : Use.NOT_OWNED;
        }
        Account account = accounts.get(id);
        if (account.usesIn(round) >= settings.maxUsesPerRound()) {
            return Use.LIMIT;
        }
        if (item == ShopItem.DOUBLE_COINS && account.doubleCoins) {
            return Use.ALREADY_ACTIVE;
        }
        // Units are stored oldest first, so the first one is usable
        account.held.get(item).removeFirst();
        account.countUse(round);
        if (item == ShopItem.DOUBLE_COINS) {
            account.doubleCoins = true;
        }
        item.die().ifPresent(account.itemDice::add);
        return Use.OK;
    }

    public int usable(UUID id, ShopItem item, int round) {
        int count = 0;
        for (int usableFrom : units(id, item)) {
            if (usableFrom <= round) {
                count++;
            }
        }
        return count;
    }

    public int locked(UUID id, ShopItem item, int round) {
        return units(id, item).size() - usable(id, item, round);
    }

    public int purchasesLeft(UUID id, int round) {
        Account account = accounts.get(id);
        int used = account == null ? 0 : account.purchasesIn(round);
        return Math.max(0, settings.maxPurchasesPerRound() - used);
    }

    public int usesLeft(UUID id, int round) {
        Account account = accounts.get(id);
        int used = account == null ? 0 : account.usesIn(round);
        return Math.max(0, settings.maxUsesPerRound() - used);
    }

    /** Whether the player has anything to buy or use in this round's prep phase. */
    public boolean canAct(PartyPlayer player, int round) {
        if (!settings.shopEnabled()) {
            return false;
        }
        UUID id = player.uuid();
        if (usesLeft(id, round) > 0) {
            for (ShopItem item : ShopItem.values()) {
                if (usable(id, item, round) > 0) {
                    return true;
                }
            }
        }
        if (purchasesLeft(id, round) == 0) {
            return false;
        }
        for (ShopItem item : ShopItem.values()) {
            if (offered(item) && player.coins() >= price(id, item)) {
                return true;
            }
        }
        return false;
    }

    public void remove(UUID id) {
        accounts.remove(id);
    }

    private List<Integer> units(UUID id, ShopItem item) {
        Account account = accounts.get(id);
        if (account == null) {
            return List.of();
        }
        return account.held.getOrDefault(item, List.of());
    }

    private Account account(UUID id) {
        return accounts.computeIfAbsent(id, ignored -> new Account());
    }

    private static final class Account {
        /** Per item, the round each held unit becomes usable, oldest first. */
        final Map<ShopItem, List<Integer>> held = new EnumMap<>(ShopItem.class);
        final Map<ShopItem, Integer> purchases = new EnumMap<>(ShopItem.class);
        final List<DiceSkin> itemDice = new ArrayList<>(1);
        DiceSkin bonusDie;
        boolean doubleCoins;
        int purchaseRound = -1;
        int purchasesThisRound;
        int useRound = -1;
        int usesThisRound;

        int purchasesIn(int round) {
            return purchaseRound == round ? purchasesThisRound : 0;
        }

        void countPurchase(int round) {
            purchasesThisRound = purchasesIn(round) + 1;
            purchaseRound = round;
        }

        int usesIn(int round) {
            return useRound == round ? usesThisRound : 0;
        }

        void countUse(int round) {
            usesThisRound = usesIn(round) + 1;
            useRound = round;
        }
    }
}
