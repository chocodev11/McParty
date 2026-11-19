package dev.epicc.board.economy;

import dev.epicc.board.Dice;
import dev.epicc.board.dice.DiceSkin;
import dev.epicc.board.dice.DieSpec;
import dev.epicc.minigame.MinigameResult;
import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyPlayer;
import dev.epicc.party.PartySettings;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoardEconomyTest {

    private static final Dice STANDARD = new Dice(1, 6);

    @Test
    void priceRisesPerPlayerAndItem() {
        BoardEconomy economy = new BoardEconomy(settings(Map.of()));
        PartyPlayer rich = player(1000);

        assertEquals(30, economy.price(rich.uuid(), ShopItem.DOUBLE_COINS));
        assertEquals(BoardEconomy.Purchase.OK, economy.buy(rich, ShopItem.DOUBLE_COINS, 0));
        assertEquals(45, economy.price(rich.uuid(), ShopItem.DOUBLE_COINS));
        assertEquals(BoardEconomy.Purchase.OK, economy.buy(rich, ShopItem.DOUBLE_COINS, 1));
        assertEquals(60, economy.price(rich.uuid(), ShopItem.DOUBLE_COINS));

        assertEquals(40, economy.price(rich.uuid(), ShopItem.CHAOS_DIE));
        assertEquals(30, economy.price(UUID.randomUUID(), ShopItem.DOUBLE_COINS));
        assertEquals(1000 - 30 - 45, rich.coins());
    }

    @Test
    void notEnoughCoinsSpendsNothing() {
        BoardEconomy economy = new BoardEconomy(settings(Map.of()));
        PartyPlayer poor = player(25);

        assertEquals(BoardEconomy.Purchase.NOT_ENOUGH_COINS, economy.buy(poor, ShopItem.DOUBLE_COINS, 0));
        assertEquals(25, poor.coins());
        assertEquals(1, economy.purchasesLeft(poor.uuid(), 0));
    }

    @Test
    void onePurchasePerRound() {
        BoardEconomy economy = new BoardEconomy(settings(Map.of()));
        PartyPlayer buyer = player(500);

        assertEquals(BoardEconomy.Purchase.OK, economy.buy(buyer, ShopItem.BRONZE_DIE, 2));
        assertEquals(BoardEconomy.Purchase.LIMIT, economy.buy(buyer, ShopItem.SILVER_DIE, 2));
        assertEquals(BoardEconomy.Purchase.OK, economy.buy(buyer, ShopItem.SILVER_DIE, 3));
    }

    @Test
    void boughtItemIsLockedUntilNextRound() {
        BoardEconomy economy = new BoardEconomy(settings(Map.of()));
        PartyPlayer buyer = player(100);
        UUID id = buyer.uuid();

        economy.buy(buyer, ShopItem.GOLD_DIE, 4);
        assertEquals(0, economy.usable(id, ShopItem.GOLD_DIE, 4));
        assertEquals(1, economy.locked(id, ShopItem.GOLD_DIE, 4));
        assertEquals(BoardEconomy.Use.LOCKED, economy.use(id, ShopItem.GOLD_DIE, 4));

        assertEquals(1, economy.usable(id, ShopItem.GOLD_DIE, 5));
        assertEquals(BoardEconomy.Use.OK, economy.use(id, ShopItem.GOLD_DIE, 5));
        assertEquals(BoardEconomy.Use.NOT_OWNED, economy.use(id, ShopItem.GOLD_DIE, 6));
    }

    @Test
    void oneUsePerRoundAndFailedUsesKeepTheItem() {
        BoardEconomy economy = new BoardEconomy(settings(Map.of()));
        PartyPlayer buyer = player(500);
        UUID id = buyer.uuid();
        economy.buy(buyer, ShopItem.BRONZE_DIE, 0);
        economy.buy(buyer, ShopItem.SILVER_DIE, 1);

        assertEquals(BoardEconomy.Use.OK, economy.use(id, ShopItem.BRONZE_DIE, 2));
        assertEquals(BoardEconomy.Use.LIMIT, economy.use(id, ShopItem.SILVER_DIE, 2));
        assertEquals(1, economy.usable(id, ShopItem.SILVER_DIE, 2));
        assertEquals(BoardEconomy.Use.OK, economy.use(id, ShopItem.SILVER_DIE, 3));
    }

    @Test
    void doubleCoinsDoublesOnceAndSurvivesAnEmptyResult() {
        BoardEconomy economy = new BoardEconomy(settings(Map.of()));
        PartyPlayer doubler = player(200);
        PartyPlayer other = player(0);
        PartyInstance instance = party(doubler, other);
        economy.buy(doubler, ShopItem.DOUBLE_COINS, 0);
        economy.buy(doubler, ShopItem.DOUBLE_COINS, 1);
        assertEquals(BoardEconomy.Use.OK, economy.use(doubler.uuid(), ShopItem.DOUBLE_COINS, 2));
        assertEquals(BoardEconomy.Use.ALREADY_ACTIVE, economy.use(doubler.uuid(), ShopItem.DOUBLE_COINS, 3));
        int coinsBefore = doubler.coins();

        assertTrue(economy.payout(instance, new MinigameResult()).isEmpty());

        MinigameResult result = new MinigameResult();
        result.setCoins(doubler.uuid(), 20);
        result.setCoins(other.uuid(), 16);
        List<BoardEconomy.Payout> payouts = economy.payout(instance, result);
        assertEquals(new BoardEconomy.Payout(doubler.uuid(), 40, true), payouts.get(0));
        assertEquals(new BoardEconomy.Payout(other.uuid(), 16, false), payouts.get(1));
        assertEquals(coinsBefore + 40, doubler.coins());

        assertEquals(new BoardEconomy.Payout(doubler.uuid(), 20, false), economy.payout(instance, result).get(0));
    }

    @Test
    void topThreeGetBonusDiceForOneRoll() {
        BoardEconomy economy = new BoardEconomy(settings(Map.of()));
        PartyPlayer first = player(0);
        PartyPlayer second = player(0);
        PartyPlayer third = player(0);
        PartyPlayer fourth = player(0);
        PartyInstance instance = party(first, second, third, fourth);
        MinigameResult result = new MinigameResult();
        result.setPlacement(fourth.uuid(), 4);
        result.setPlacement(third.uuid(), 3);
        result.setPlacement(first.uuid(), 1);
        result.setPlacement(second.uuid(), 2);

        List<BoardEconomy.BonusDie> awarded = economy.awardBonusDice(instance, result);
        assertEquals(List.of(
                new BoardEconomy.BonusDie(first.uuid(), 1, DiceSkin.GOLD),
                new BoardEconomy.BonusDie(second.uuid(), 2, DiceSkin.SILVER),
                new BoardEconomy.BonusDie(third.uuid(), 3, DiceSkin.BRONZE)
        ), awarded);

        assertEquals(List.of(DiceSkin.STANDARD, DiceSkin.GOLD), skins(economy.takeRollDice(first.uuid(), STANDARD)));
        assertEquals(List.of(DiceSkin.STANDARD), skins(economy.takeRollDice(first.uuid(), STANDARD)));
        assertEquals(List.of(DiceSkin.STANDARD), skins(economy.takeRollDice(fourth.uuid(), STANDARD)));
    }

    @Test
    void singlePlacementAwardsNoBonusDie() {
        BoardEconomy economy = new BoardEconomy(settings(Map.of()));
        PartyPlayer solo = player(0);
        PartyInstance instance = party(solo);
        MinigameResult result = new MinigameResult();
        result.setPlacement(solo.uuid(), 1);

        assertTrue(economy.awardBonusDice(instance, result).isEmpty());
        assertEquals(List.of(DiceSkin.STANDARD), skins(economy.takeRollDice(solo.uuid(), STANDARD)));
    }

    @Test
    void rollDiceAreStandardThenBonusThenItem() {
        BoardEconomy economy = new BoardEconomy(settings(Map.of()));
        PartyPlayer winner = player(100);
        PartyPlayer loser = player(0);
        PartyInstance instance = party(winner, loser);
        MinigameResult result = new MinigameResult();
        result.setPlacement(loser.uuid(), 1);
        result.setPlacement(winner.uuid(), 2);
        economy.awardBonusDice(instance, result);
        economy.buy(winner, ShopItem.CHAOS_DIE, 0);
        economy.use(winner.uuid(), ShopItem.CHAOS_DIE, 1);

        assertEquals(List.of(DiceSkin.STANDARD, DiceSkin.SILVER, DiceSkin.CHAOS),
                skins(economy.takeRollDice(winner.uuid(), STANDARD)));
    }

    @Test
    void canActNeedsAnAffordableItemOrAUsableOne() {
        BoardEconomy economy = new BoardEconomy(settings(Map.of()));
        PartyPlayer buyer = player(10);
        assertFalse(economy.canAct(buyer, 0));

        buyer.addCoins(10);
        assertTrue(economy.canAct(buyer, 0));

        economy.buy(buyer, ShopItem.BRONZE_DIE, 0);
        assertFalse(economy.canAct(buyer, 0));
        assertTrue(economy.canAct(buyer, 1));
    }

    @Test
    void zeroPriceRemovesAnItem() {
        BoardEconomy economy = new BoardEconomy(settings(Map.of(ShopItem.WARP_PIPE, 0)));
        PartyPlayer buyer = player(500);

        assertFalse(economy.offered(ShopItem.WARP_PIPE));
        assertEquals(BoardEconomy.Purchase.CLOSED, economy.buy(buyer, ShopItem.WARP_PIPE, 0));
        assertEquals(500, buyer.coins());
    }

    private static EconomySettings settings(Map<ShopItem, Integer> overrides) {
        Map<ShopItem, Integer> prices = new EnumMap<>(ShopItem.class);
        for (ShopItem item : ShopItem.values()) {
            prices.put(item, overrides.getOrDefault(item, item.defaultPrice()));
        }
        return new EconomySettings(true, true, 10, 50, 1, 1, prices);
    }

    private static PartyPlayer player(int coins) {
        return new PartyPlayer(UUID.randomUUID(), "player", coins);
    }

    private static PartyInstance party(PartyPlayer... players) {
        PartyInstance instance = new PartyInstance(UUID.randomUUID(), Optional.empty(),
                new PartySettings(1, 8, 0, 1, 6, 0));
        for (PartyPlayer player : players) {
            instance.addPlayer(player);
        }
        return instance;
    }

    private static List<DiceSkin> skins(List<DieSpec> specs) {
        return specs.stream().map(DieSpec::skin).toList();
    }
}
