package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sidebar and boss bar for one match. Every player gets a private scoreboard so the sidebar can
 * highlight their own row; the previous scoreboard returns when they leave or the scope closes.
 */
public final class MatchHud {

    /** One ranking line. {@code place} 0 means still tied with the other unplaced players. */
    public record Row(UUID playerId, String metric, int place, boolean out) {
    }

    private record Sidebar(Scoreboard previous, Scoreboard board, Objective objective, List<Component> shown) {
    }

    private static final int MAX_ROWS = 5;
    /** Sidebar lines sort by score, so line 0 gets the highest. */
    private static final int TOP_SCORE = 15;

    private final MatchScope scope;
    private final MessageService messages;
    private final String displayName;
    private final Map<UUID, Sidebar> sidebars = new HashMap<>();
    private List<Row> rows = List.of();
    private BossBar bossBar;
    private int shownSeconds = -1;

    private MatchHud(MatchScope scope, String displayName) {
        this.scope = scope;
        this.messages = scope.messages();
        this.displayName = displayName;
    }

    /**
     * @param playerCollision false lets players walk through each other (races)
     */
    public static MatchHud attach(MatchScope scope, String displayName, boolean playerCollision) {
        MatchHud hud = new MatchHud(scope, displayName);
        List<Player> players = scope.onlinePlayers();
        for (Player player : players) {
            Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective objective = board.registerNewObjective(
                    "mcparty", Criteria.DUMMY, hud.messages.get("minigame.hud-title")
            );
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            objective.numberFormat(NumberFormat.blank());
            if (!playerCollision) {
                // The client predicts pushing from the teams on its own scoreboard
                Team team = board.registerNewTeam("match");
                team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
                for (Player member : players) {
                    team.addEntry(member.getName());
                }
            }
            hud.sidebars.put(
                    player.getUniqueId(),
                    new Sidebar(player.getScoreboard(), board, objective, new ArrayList<>())
            );
            player.setScoreboard(board);
        }
        scope.onDetach(hud::remove);
        scope.onClose(hud::removeAll);
        hud.render();
        return hud;
    }

    /** Boss bar and sidebar clock. Cheap to call every tick: the bar only moves every 4 ticks. */
    public void timer(int ticksLeft, int totalTicks) {
        if (bossBar == null) {
            bossBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
            for (Player player : scope.onlinePlayers()) {
                player.showBossBar(bossBar);
            }
        }
        int seconds = Math.max(0, (ticksLeft + 19) / 20);
        if (seconds != shownSeconds) {
            shownSeconds = seconds;
            bossBar.name(messages.get("minigame.hud-bossbar", "name", displayName, "time", clock(seconds)));
            render();
        }
        if (ticksLeft % 4 == 0) {
            float progress = Math.clamp((float) ticksLeft / totalTicks, 0f, 1f);
            bossBar.progress(progress);
            bossBar.color(progress > 0.5f ? BossBar.Color.GREEN
                    : progress > 0.2f ? BossBar.Color.YELLOW : BossBar.Color.RED);
        }
    }

    /** Ranking rows, best first. */
    public void rows(List<Row> ranked) {
        rows = List.copyOf(ranked);
        render();
    }

    /** Alive list for elimination games: no metric, eliminated players struck through. */
    public void rows(EliminationTracker tracker) {
        List<UUID> standing = tracker.standing();
        int alive = tracker.aliveCount();
        List<Row> ranked = new ArrayList<>(standing.size());
        for (int i = 0; i < standing.size(); i++) {
            boolean out = i >= alive;
            ranked.add(new Row(standing.get(i), "", out ? i + 1 : 0, out));
        }
        rows(ranked);
    }

    private void render() {
        for (Player player : scope.onlinePlayers()) {
            Sidebar sidebar = sidebars.get(player.getUniqueId());
            if (sidebar != null) {
                show(sidebar, lines(player.getUniqueId()));
            }
        }
    }

    private List<Component> lines(UUID viewer) {
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("minigame.hud-minigame-label"));
        lines.add(messages.get("minigame.hud-value", "value", displayName));
        if (shownSeconds >= 0) {
            lines.add(messages.get("minigame.hud-time-label"));
            lines.add(messages.get("minigame.hud-value", "value", clock(shownSeconds)));
        }
        if (rows.isEmpty()) {
            return lines;
        }

        int own = -1;
        int alive = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).playerId().equals(viewer)) {
                own = i;
            }
            if (!rows.get(i).out()) {
                alive++;
            }
        }
        lines.add(own >= 0 && rows.get(own).place() > 0
                ? messages.get("minigame.hud-place", "place", Integer.toString(rows.get(own).place()))
                : messages.get("minigame.hud-alive",
                        "alive", Integer.toString(alive), "total", Integer.toString(rows.size())));

        int shown = Math.min(MAX_ROWS, rows.size());
        for (int i = 0; i < shown; i++) {
            // The viewer's own row is always visible: it takes the last slot when ranked lower
            int index = i == shown - 1 && own >= shown ? own : i;
            lines.add(rowLine(rows.get(index), index == own));
        }
        if (rows.size() > shown) {
            lines.add(messages.get("minigame.hud-more", "count", Integer.toString(rows.size() - shown)));
        }
        return lines;
    }

    private Component rowLine(Row row, boolean own) {
        String key = row.out() ? "minigame.hud-row-out" : own ? "minigame.hud-row-self" : "minigame.hud-row";
        String metric = row.metric().isEmpty() ? "" : row.metric() + " ";
        return messages.get(key, "metric", metric, "player", scope.name(row.playerId()));
    }

    /** Only lines that changed are sent again. */
    private static void show(Sidebar sidebar, List<Component> lines) {
        List<Component> shown = sidebar.shown();
        for (int i = 0; i < lines.size(); i++) {
            if (i < shown.size() && shown.get(i).equals(lines.get(i))) {
                continue;
            }
            Score score = sidebar.objective().getScore(entry(i));
            score.setScore(TOP_SCORE - i);
            score.customName(lines.get(i));
        }
        for (int i = lines.size(); i < shown.size(); i++) {
            sidebar.board().resetScores(entry(i));
        }
        shown.clear();
        shown.addAll(lines);
    }

    private static String entry(int line) {
        return "line" + line;
    }

    private static String clock(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private void remove(Player player) {
        Sidebar sidebar = sidebars.remove(player.getUniqueId());
        if (sidebar == null) {
            return;
        }
        player.setScoreboard(sidebar.previous());
        if (bossBar != null) {
            player.hideBossBar(bossBar);
        }
    }

    private void removeAll() {
        for (Player player : scope.onlinePlayers()) {
            remove(player);
        }
    }
}
