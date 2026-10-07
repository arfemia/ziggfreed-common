package com.ziggfreed.common.objectives.book.achievement;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * The milestone card's track: one rung marker per milestone placed along the card's bar at its threshold's share of
 * the ladder (the top rung at the bar's end), lit once the points reach it, the rung being worked toward marked, the
 * rest waiting. The way vanilla's Memories page places its chest markers along its bar: append a marker, then send
 * its whole {@code Anchor} with the computed {@code Left} ({@code MemoriesPage.java:237-242}) into a host with no
 * layout of its own ({@code MemoriesCategoryPanel.ui}'s {@code #ChestMarkers}).
 *
 * <p>While the track shows (a ladder of two rungs or more) the bar above it reads on the ladder's scale
 * ({@link #barFraction}), so a marker sits where the fill will reach it; the count beside the bar still says how far
 * the next rung is. A ladder of one rung shows no track: the bar is that rung.
 *
 * <p>This class paints its own template ({@code Pages/ZigMilestoneRung.ui}), so it is the one place that spells the
 * marker's child ids.
 */
final class MilestoneTrack {

    /** The marker template, appended into the card's rung host. */
    static final String TEMPLATE = "Pages/ZigMilestoneRung.ui";

    /**
     * The bar's drawn width in {@code Pages/ZigBookAchievements.ui}: the milestone card (630) less its padding both
     * sides (12 each) less the count label (160). The track row ends in a spacer of the count's width, so the rung
     * host spans exactly the bar. {@code MilestoneTrackTest} holds this to the document.
     */
    static final int WIDTH = 446;

    /** A marker's authored size, square. */
    static final int MARKER = 12;

    /**
     * The one switch: false keeps the card a plain bar to the next rung (no markers, the bar on the next rung's
     * scale).
     */
    static final boolean ENABLED = true;

    /** How a rung reads. */
    enum State {
        /** The points reached it (collected or waiting to be). */
        REACHED,
        /** The rung the card is working toward. */
        NEXT,
        /** Further up the ladder. */
        AHEAD
    }

    /** One marker: its threshold, its left edge inside the rung host, and how it reads. */
    record Rung(int threshold, int left, @Nonnull State state) {
    }

    private MilestoneTrack() {
    }

    /**
     * The markers for {@code thresholds} (any order; zero and below dropped) at {@code points}, placed across
     * {@code width}: each centred on {@code threshold * width / top} and kept inside the host.
     *
     * @param next the threshold the card works toward, or any value no rung has (every rung collected)
     */
    @Nonnull
    static List<Rung> rungs(@Nullable List<Integer> thresholds, long points, int next, int width) {
        List<Integer> sorted = sorted(thresholds);
        List<Rung> out = new ArrayList<>(sorted.size());
        if (sorted.isEmpty() || width <= MARKER) {
            return out;
        }
        long top = sorted.get(sorted.size() - 1);
        for (int threshold : sorted) {
            long centre = threshold * (long) width / top;
            int left = (int) Math.max(0L, Math.min(width - MARKER, centre - MARKER / 2));
            State state = points >= threshold ? State.REACHED : threshold == next ? State.NEXT : State.AHEAD;
            out.add(new Rung(threshold, left, state));
        }
        return out;
    }

    /** Whether the track shows for this ladder: two rungs or more, and the switch on. */
    static boolean shows(@Nullable List<Integer> thresholds) {
        return ENABLED && sorted(thresholds).size() >= 2;
    }

    /** The bar's fill on the ladder's scale: the points over the top rung, 0 to 1. */
    static float barFraction(@Nullable List<Integer> thresholds, long points) {
        List<Integer> sorted = sorted(thresholds);
        if (sorted.isEmpty()) {
            return 0f;
        }
        long top = sorted.get(sorted.size() - 1);
        return (float) Math.max(0L, Math.min(points, top)) / (float) top;
    }

    /**
     * Paint the track for {@code thresholds} at {@code points}: the track row shown or hidden, the rung host cleared
     * and refilled, each marker placed by its whole {@code Anchor}. Call it in a build or a partial update; it binds
     * nothing.
     *
     * @param track the track row's selector (shown only while {@link #shows})
     * @param host  the rung host's selector, inside the track row
     * @param next  the threshold the card works toward, or -1 when every rung is collected
     * @return whether the track shows (the bar then reads {@link #barFraction})
     */
    static boolean paint(@Nonnull UICommandBuilder cmd, @Nonnull String track, @Nonnull String host,
            @Nullable List<Integer> thresholds, long points, int next) {
        boolean shows = shows(thresholds);
        cmd.set(track + ".Visible", shows);
        cmd.clear(host);
        if (!shows) {
            return false;
        }
        List<Rung> rungs = rungs(thresholds, points, next, WIDTH);
        for (int i = 0; i < rungs.size(); i++) {
            Rung rung = rungs.get(i);
            cmd.append(host, TEMPLATE);
            String marker = host + "[" + i + "]";
            Anchor anchor = new Anchor();
            anchor.setLeft(Value.of(rung.left()));
            anchor.setTop(Value.of(0));
            anchor.setWidth(Value.of(MARKER));
            anchor.setHeight(Value.of(MARKER));
            cmd.setObject(marker + ".Anchor", anchor);
            cmd.set(marker + " #Reached.Visible", rung.state() == State.REACHED);
            cmd.set(marker + " #Next.Visible", rung.state() == State.NEXT);
            cmd.set(marker + " #Ahead.Visible", rung.state() == State.AHEAD);
        }
        return true;
    }

    @Nonnull
    private static List<Integer> sorted(@Nullable List<Integer> thresholds) {
        List<Integer> out = new ArrayList<>();
        if (thresholds == null) {
            return out;
        }
        for (Integer threshold : thresholds) {
            if (threshold != null && threshold > 0) {
                out.add(threshold);
            }
        }
        out.sort(Integer::compare);
        return out;
    }
}
