package dev.epicc.board.dice;

import dev.epicc.board.Dice;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.Locale;
import java.util.Optional;

/**
 * Die look and face range. Every face needs its own texture, model, item definition and glyph,
 * so the ranges are fixed here rather than in config. {@link #STANDARD} rolls the party's
 * configured range but only ships faces 1–6.
 */
public enum DiceSkin {
    STANDARD(1, 6, NamedTextColor.WHITE),
    BRONZE(1, 3, TextColor.color(0xCD7F32)),
    SILVER(2, 5, TextColor.color(0xC0C8D2)),
    GOLD(4, 6, TextColor.color(0xFFC81E)),
    CHAOS(-3, 6, TextColor.color(0xB04CE6));

    private final int minFace;
    private final int maxFace;
    private final TextColor color;

    DiceSkin(int minFace, int maxFace, TextColor color) {
        this.minFace = minFace;
        this.maxFace = maxFace;
        this.color = color;
    }

    public Dice dice() {
        return new Dice(minFace, maxFace);
    }

    public int maxFace() {
        return maxFace;
    }

    /** Text colour used when a face has no glyph. */
    public TextColor color() {
        return color;
    }

    /** Model and glyph id of a shipped face: {@code dice_4}, {@code dice_gold_4}, {@code dice_chaos_m2}. */
    public Optional<String> assetId(int face) {
        if (face < minFace || face > maxFace) {
            return Optional.empty();
        }
        if (this == STANDARD) {
            return Optional.of("dice_" + face);
        }
        String suffix = face < 0 ? "m" + -face : Integer.toString(face);
        return Optional.of("dice_" + name().toLowerCase(Locale.ROOT) + "_" + suffix);
    }
}
