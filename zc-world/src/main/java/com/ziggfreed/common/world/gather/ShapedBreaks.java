package com.ziggfreed.common.world.gather;

/**
 * Whether the block break the engine is reporting right now is one a gather shape makes on a player's behalf (one of
 * a vein's further ores, a felled tree's logs) rather than a swing of theirs. The engine fires its own break event for
 * each such block, with the player, so every listener sees it as a break by that player; a listener that should treat
 * it differently asks here: the library's break chain never counts it, and a mod's bonus drops roll on it only when
 * {@link #rowsAllowed} says the shape lets them (its {@code Rows} block), so a vein pays its bonus rows once, for the
 * ore in hand, unless the shape says otherwise. World-thread only: the answer belongs to the thread making the break.
 */
public final class ShapedBreaks {

    /** The shape running on this thread: null outside one, else whether its further blocks roll bonus rows. */
    private static final ThreadLocal<Boolean> RUNNING = new ThreadLocal<>();

    private ShapedBreaks() {
    }

    /** Is a gather shape breaking blocks on this thread right now? */
    public static boolean active() {
        return RUNNING.get() != null;
    }

    /** Should a bonus row roll for the break being reported: always outside a shape, inside one as the shape says. */
    public static boolean rowsAllowed() {
        Boolean rows = RUNNING.get();
        return rows == null || rows;
    }

    /** Run {@code work} as a shape's further breaks, with {@code rows} saying whether they roll bonus rows. */
    static void run(boolean rows, Runnable work) {
        Boolean outer = RUNNING.get();
        RUNNING.set(rows);
        try {
            work.run();
        } finally {
            if (outer == null) {
                RUNNING.remove();
            } else {
                RUNNING.set(outer);
            }
        }
    }
}
