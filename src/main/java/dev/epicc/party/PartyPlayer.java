package dev.epicc.party;

import java.util.UUID;

public final class PartyPlayer {

    private final UUID uuid;
    private final String name;
    private int coins;
    private int stars;
    private int boardIndex;

    public PartyPlayer(UUID uuid, String name, int startingCoins) {
        this.uuid = uuid;
        this.name = name;
        this.coins = startingCoins;
        this.stars = 0;
        this.boardIndex = 0;
    }

    public UUID uuid() { return uuid; }
    public String name() { return name; }
    public int coins() { return coins; }
    public int stars() { return stars; }
    public int boardIndex() { return boardIndex; }

    public void addCoins(int amount) {
        coins = Math.max(0, coins + amount);
    }

    public void setBoardIndex(int index) {
        boardIndex = Math.max(0, index);
    }
}
