package com.ziggfreed.common.instance.dungeon.layout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The way a door faces, out of its room, on the engine's compass ({@code Vector3iUtil}: North is -Z, East +X,
 * South +Z, West -X). A closed vocabulary, authored as its word and read without regard to case.
 */
public enum Facing {
    NORTH("North", 0, -1),
    EAST("East", 1, 0),
    SOUTH("South", 0, 1),
    WEST("West", -1, 0);

    private static final Facing[] VALUES = values();

    private final String word;
    private final int dx;
    private final int dz;

    Facing(@Nonnull String word, int dx, int dz) {
        this.word = word;
        this.dx = dx;
        this.dz = dz;
    }

    /** The word a file writes, such as {@code North}. */
    @Nonnull
    public String word() {
        return word;
    }

    /** One step this way along X. */
    public int dx() {
        return dx;
    }

    /** One step this way along Z. */
    public int dz() {
        return dz;
    }

    /** The facing a door must have to meet this one face to face. */
    @Nonnull
    public Facing opposite() {
        return VALUES[(ordinal() + 2) % 4];
    }

    /** This facing after {@code turns} quarter turns ({@link QuarterTurns}). */
    @Nonnull
    public Facing turned(int turns) {
        Facing out = of(QuarterTurns.x(dx, dz, turns), QuarterTurns.z(dx, dz, turns));
        return out == null ? this : out;
    }

    /** The quarter turns (0 to 3) that bring this facing to {@code target}. */
    public int turnsTo(@Nonnull Facing target) {
        for (int turns = 0; turns < 4; turns++) {
            if (turned(turns) == target) {
                return turns;
            }
        }
        return 0;
    }

    /** The facing a file names, matched without regard to case; null for a blank or unknown word. */
    @Nullable
    public static Facing parse(@Nullable String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        String trimmed = word.trim();
        for (Facing facing : VALUES) {
            if (facing.word.equalsIgnoreCase(trimmed)) {
                return facing;
            }
        }
        return null;
    }

    /** Every word, in compass order, for the Asset Editor's dropdown. */
    @Nonnull
    public static String[] words() {
        String[] out = new String[VALUES.length];
        for (int i = 0; i < VALUES.length; i++) {
            out[i] = VALUES[i].word;
        }
        return out;
    }

    @Nullable
    private static Facing of(int dx, int dz) {
        for (Facing facing : VALUES) {
            if (facing.dx == dx && facing.dz == dz) {
                return facing;
            }
        }
        return null;
    }
}
