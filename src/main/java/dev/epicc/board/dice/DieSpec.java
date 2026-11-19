package dev.epicc.board.dice;

import dev.epicc.board.Dice;

import java.util.ArrayList;
import java.util.List;

/** One die of a roll: the range it rolls and the skin it shows. */
public record DieSpec(Dice dice, DiceSkin skin) {

    public static DieSpec standard(Dice dice) {
        return new DieSpec(dice, DiceSkin.STANDARD);
    }

    public static DieSpec of(DiceSkin skin) {
        return new DieSpec(skin.dice(), skin);
    }

    public static List<Integer> rollAll(List<DieSpec> specs) {
        List<Integer> faces = new ArrayList<>(specs.size());
        for (DieSpec spec : specs) {
            faces.add(spec.dice().roll());
        }
        return faces;
    }

    public static int total(List<Integer> faces) {
        int total = 0;
        for (int face : faces) {
            total += face;
        }
        return total;
    }
}
