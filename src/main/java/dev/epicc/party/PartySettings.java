package dev.epicc.party;

public final class PartySettings {

    private final int minPlayers;
    private final int maxPlayers;
    private final int startingCoins;
    private final int diceMin;
    private final int diceMax;

    public PartySettings(int minPlayers, int maxPlayers, int startingCoins, int diceMin, int diceMax) {
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.startingCoins = startingCoins;
        this.diceMin = diceMin;
        this.diceMax = diceMax;
    }

    public int minPlayers() { return minPlayers; }
    public int maxPlayers() { return maxPlayers; }
    public int startingCoins() { return startingCoins; }
    public int diceMin() { return diceMin; }
    public int diceMax() { return diceMax; }
}
