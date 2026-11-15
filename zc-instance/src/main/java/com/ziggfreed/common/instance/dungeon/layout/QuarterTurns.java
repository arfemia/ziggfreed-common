package com.ziggfreed.common.instance.dungeon.layout;

/**
 * The one rule a layout turns by: a quarter turn about the vertical axis through a room's anchor, the way the
 * engine's prefab paste turns a prefab ({@code PrefabRotation}, whose {@code VALUES[n]} is {@code n} quarter turns:
 * one turn maps {@code (x, z)} to {@code (z, -x)}). A placed room's rotation is the index the painter hands the
 * engine, so a door, a box and a facing turned here land where the pasted blocks do.
 */
public final class QuarterTurns {

    private QuarterTurns() {
    }

    /** {@code turns} folded into 0 to 3. */
    public static int normalize(int turns) {
        return Math.floorMod(turns, 4);
    }

    /** The X of {@code (x, z)} after {@code turns} quarter turns. */
    public static int x(int x, int z, int turns) {
        return switch (normalize(turns)) {
            case 1 -> z;
            case 2 -> -x;
            case 3 -> -z;
            default -> x;
        };
    }

    /** The Z of {@code (x, z)} after {@code turns} quarter turns. */
    public static int z(int x, int z, int turns) {
        return switch (normalize(turns)) {
            case 1 -> -x;
            case 2 -> -z;
            case 3 -> x;
            default -> z;
        };
    }
}
