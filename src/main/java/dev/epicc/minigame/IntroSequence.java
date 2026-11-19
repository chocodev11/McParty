package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * INTRO phase shared by every minigame: the rules go to chat and players stay frozen while a
 * title counts down to GO. Scheduled on the scope, so a cancel or an early conclude stops it.
 */
public final class IntroSequence {

    private static final Title.Times COUNT_TIMES =
            Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(200));
    private static final Title.Times GO_TIMES =
            Title.Times.times(Duration.ZERO, Duration.ofMillis(700), Duration.ofMillis(300));

    private IntroSequence() {
    }

    /**
     * @param onGo     starts the game's LIVE logic; every timer of the game counts from here
     * @param rulesKey message with the rules box, sent once to every player
     */
    public static void run(
            MatchScope scope,
            String displayName,
            Runnable onGo,
            String rulesKey,
            TagResolver... placeholders
    ) {
        scope.broadcast(rulesKey, placeholders);
        int seconds = scope.introSeconds();
        if (seconds <= 0) {
            onGo.run();
            return;
        }

        scope.freeze();
        for (int elapsed = 0; elapsed < seconds; elapsed++) {
            int left = seconds - elapsed;
            scope.later(elapsed * 20L, () -> countdown(scope, displayName, left));
        }
        scope.later(seconds * 20L, () -> {
            scope.unfreeze();
            MessageService messages = scope.messages();
            Title go = Title.title(messages.get("minigame.intro-go-title"), messages.get("minigame.intro-go-subtitle"), GO_TIMES);
            for (Player player : scope.onlinePlayers()) {
                player.showTitle(go);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
            }
            onGo.run();
        });
    }

    private static void countdown(MatchScope scope, String displayName, int left) {
        MessageService messages = scope.messages();
        Title title = Title.title(
                messages.get("minigame.intro-title", "name", displayName),
                messages.get("minigame.intro-subtitle", "seconds", Integer.toString(left)),
                COUNT_TIMES
        );
        for (Player player : scope.onlinePlayers()) {
            player.showTitle(title);
            if (left <= 3) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.0f);
            }
        }
    }
}
