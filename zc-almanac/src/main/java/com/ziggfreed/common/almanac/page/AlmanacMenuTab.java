package com.ziggfreed.common.almanac.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;

/**
 * When the shared menu (and a consumer's hub tile) offers the Almanac: two owner knobs, read from
 * {@code almanac.json}'s {@code $MenuTab} group by {@code AlmanacOwnerLayers}, and the one rule over them,
 * {@link #visible}, which {@link AlmanacPages#menuTabVisible} asks. Two booleans, not a mode: whether the
 * tab shows at all, and whether it waits for a season to be running.
 */
public final class AlmanacMenuTab {

    /** {@code Show} and {@code OnlyWhileLive}. */
    public record Knobs(boolean show, boolean onlyWhileLive) {

        /** Shown whenever the Almanac lists a season. */
        public static final Knobs DEFAULTS = new Knobs(true, false);
    }

    private static volatile Knobs knobs = Knobs.DEFAULTS;

    private AlmanacMenuTab() {
    }

    /** The knobs in force. */
    @Nonnull
    public static Knobs knobs() {
        return knobs;
    }

    /** Set by the owner file's reader and writer; null is the defaults. */
    public static void set(@Nullable Knobs next) {
        knobs = next == null ? Knobs.DEFAULTS : next;
    }

    /** The rule, as a pure function of whether the Almanac lists a season, the knobs, and whether one is running. */
    public static boolean visible(boolean listed, @Nonnull Knobs knobs, boolean anySeasonLive) {
        return listed && knobs.show() && (!knobs.onlyWhileLive() || anySeasonLive);
    }

    /** The Almanac's tab in the shared menu. */
    @Nonnull
    public static MenuEntry entry() {
        return MenuSlot.ALMANAC.entry(AlmanacText.line("title"), null, AlmanacDestinations.ALMANAC,
                viewer -> AlmanacPages.menuTabVisible());
    }

    /** Back to the defaults, for a test starting from nothing. */
    public static void resetForTests() {
        knobs = Knobs.DEFAULTS;
    }
}
