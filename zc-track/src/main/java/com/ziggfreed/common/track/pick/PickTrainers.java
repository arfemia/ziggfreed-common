package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * Which of the placements a pick's {@code Where} names stand as trainers on this server: the seam that decides
 * where a trainer pick is taken (R593, R608). A pick whose {@code Where} names at least one placement that teaches
 * is taken only at those trainers' pages; a pick naming none that teaches (every one switched off, gone, or no
 * trainer at all) is taken on its track's own page like any other, so an owner turns trainers off by switching their
 * placements off and nothing else. {@link PickService#waiting} and every take and choice read it, through
 * {@link #of}.
 *
 * <p>This module cannot see placements, so the seam ships filled with the answer a track alone can give: every named
 * placement teaches, as a pick's {@code Where} has always read. The library's trainer bootstrap fills it with the
 * placement store's answer (a placement that exists, is switched on and carries a trainer block). A respec and a
 * prestige still read the authored {@code Where} ({@link PickService#taughtByTrainer},
 * {@link TrackPickAsset#keptThroughPrestige}): a trainer pick learned on the track's page while its trainer was away
 * is still what a trainer teaches.
 */
public final class PickTrainers {

    /** Does a placement stand as a trainer on this server right now? */
    @FunctionalInterface
    public interface Teaching {

        /** Whether the placement {@code placementId} (lower-cased) teaches. Any thread; never slow. */
        boolean teaches(@Nonnull String placementId);
    }

    /** The answer a track alone gives: every placement a pick names teaches. */
    private static final Teaching EVERY = id -> true;

    private static volatile Teaching teaching = EVERY;

    /** Whether the unfilled default has said so, once. */
    private static final AtomicBoolean FALLBACK_REPORTED = new AtomicBoolean();

    private PickTrainers() {
    }

    /** Answer which placements teach with {@code answer}; null puts back the default, every named placement. */
    public static void install(@Nullable Teaching answer) {
        teaching = answer == null ? EVERY : answer;
    }

    /**
     * Whether {@code placementId} teaches. A blank id never does; an answer that throws counts as teaching, which
     * keeps the pick at the trainer its file names rather than moving it. Unfilled, every named placement teaches,
     * said once through the log.
     */
    public static boolean teaches(@Nullable String placementId) {
        if (placementId == null || placementId.isBlank()) {
            return false;
        }
        Teaching answer = teaching;
        if (answer == EVERY && FALLBACK_REPORTED.compareAndSet(false, true)) {
            SafeLog.info("[track] no trainer reader is installed, so every placement a pick's Where names counts as a "
                    + "trainer that teaches it");
        }
        try {
            return answer.teaches(placementId.trim().toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            SafeLog.fine("[track] could not read whether a trainer teaches: " + t.getMessage());
            return true;
        }
    }

    /**
     * The trainers among {@code pick}'s {@code Where} that teach now, lower-cased, in authored order: empty for a
     * pick naming none, or naming none that teaches, which is taken on its track's own page.
     */
    @Nonnull
    public static List<String> of(@Nonnull TrackPickAsset pick) {
        List<String> named = pick.placements();
        if (named.isEmpty()) {
            return named;
        }
        List<String> out = new ArrayList<>(named.size());
        for (String placement : named) {
            if (teaches(placement)) {
                out.add(placement);
            }
        }
        return List.copyOf(out);
    }
}
