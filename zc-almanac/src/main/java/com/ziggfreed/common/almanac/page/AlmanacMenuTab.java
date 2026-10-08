package com.ziggfreed.common.almanac.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.almanac.AlmanacCalendar;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.OccurrenceAlmanacCalendar;
import com.ziggfreed.common.almanac.view.AlmanacView;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.MenuSubline;

/**
 * When the shared menu (and a consumer's hub tile) offers the Almanac: two owner knobs, read from
 * {@code almanac.json}'s {@code $MenuTab} group by {@code AlmanacOwnerLayers}, and the one rule over them,
 * {@link #visible}, which {@link AlmanacPages#menuTabVisible} asks. Two booleans, not a mode: whether the
 * tab shows at all, and whether it waits for a season to be running. While a season runs, the tab's
 * second line names it with its picture.
 */
public final class AlmanacMenuTab {

    /** {@code Show} and {@code OnlyWhileLive}. */
    public record Knobs(boolean show, boolean onlyWhileLive) {

        /** Shown whenever the Almanac lists a season. */
        public static final Knobs DEFAULTS = new Knobs(true, false);
    }

    private static volatile Knobs knobs = Knobs.DEFAULTS;

    /** The tab's picture: a scroll, apart from the Quests tab's book. */
    static final String ICON = "Deco_Scroll";

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

    /** The Almanac's tab in the shared menu, with the season on now as its second line. */
    @Nonnull
    public static MenuEntry entry() {
        return MenuSlot.ALMANAC.entry(AlmanacText.line("title"), IconSpec.ofItem(ICON), AlmanacDestinations.ALMANAC,
                viewer -> AlmanacPages.menuTabVisible()).withSubline(viewer -> subline());
    }

    /**
     * The tab's second line (the maintainer's ruling M485): the season on now, by the name and picture its Almanac
     * page gives it, asked on every paint so it comes and goes with the season. Null while no season runs, while
     * the Almanac is off, when that season's page names no title or no picture (never its raw id), or when the
     * read fails: the tab is then one line.
     */
    @Nullable
    static MenuSubline subline() {
        try {
            return subline(OccurrenceAlmanacCalendar.INSTANCE);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    static MenuSubline subline(@Nonnull AlmanacCalendar calendar) {
        AlmanacView.Season season = AlmanacPages.seasonOnNow(calendar);
        if (season == null || missing(season.titleKey()) || missing(season.icon())) {
            return null;
        }
        return new MenuSubline(AlmanacText.authored(season.titleKey(), season.eventId()),
                IconSpec.ofItem(season.icon().trim()));
    }

    private static boolean missing(@Nullable String authored) {
        return authored == null || authored.isBlank();
    }

    /** Back to the defaults, for a test starting from nothing. */
    public static void resetForTests() {
        knobs = Knobs.DEFAULTS;
    }
}
