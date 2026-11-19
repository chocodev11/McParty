package dev.epicc.minigame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Survival ranking shared by the elimination minigames: last alive places 1st, everyone else
 * by reverse elimination order. Coins come from the configured placement table.
 */
public final class EliminationTracker {

    private final List<Integer> coinRewards;
    /** Insertion-ordered so the live standing keeps a stable roster order. */
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final List<UUID> eliminated = new ArrayList<>();

    public EliminationTracker(List<UUID> players, List<Integer> coinRewards) {
        this.alive.addAll(players);
        this.coinRewards = coinRewards == null || coinRewards.isEmpty() ? List.of(10, 7, 5, 3) : coinRewards;
    }

    /** @return false if the player was already out or never in the match */
    public boolean eliminate(UUID playerId) {
        if (!alive.remove(playerId)) {
            return false;
        }
        eliminated.add(playerId);
        return true;
    }

    public boolean isAlive(UUID playerId) {
        return alive.contains(playerId);
    }

    public int aliveCount() {
        return alive.size();
    }

    public Set<UUID> alive() {
        return Set.copyOf(alive);
    }

    public UUID randomAlive() {
        if (alive.isEmpty()) {
            return null;
        }
        List<UUID> pool = new ArrayList<>(alive);
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /** Alive players in roster order, then reverse elimination order. */
    public List<UUID> standing() {
        List<UUID> standing = new ArrayList<>(alive);
        for (int i = eliminated.size() - 1; i >= 0; i--) {
            standing.add(eliminated.get(i));
        }
        return standing;
    }

    /** Survivors first (tied, so in random order), then reverse elimination order; coins by placement. */
    public MinigameResult result() {
        return result((first, second) -> 0);
    }

    /**
     * Same ranking, with survivors ordered by the game's own tie-break (best first). Survivors the
     * tie-break cannot separate land in random order — never roster order, which favours the host.
     */
    public MinigameResult result(Comparator<UUID> survivorOrder) {
        List<UUID> ranked = new ArrayList<>(alive);
        Collections.shuffle(ranked, ThreadLocalRandom.current());
        ranked.sort(survivorOrder);
        for (int i = eliminated.size() - 1; i >= 0; i--) {
            ranked.add(eliminated.get(i));
        }

        MinigameResult result = new MinigameResult();
        for (int i = 0; i < ranked.size(); i++) {
            result.setPlacement(ranked.get(i), i + 1);
            result.setCoins(ranked.get(i), i < coinRewards.size() ? coinRewards.get(i) : 1);
        }
        return result;
    }
}
