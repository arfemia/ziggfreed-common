package com.ziggfreed.common.loot;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Where an EARNED {@link Roll} cue goes when the granting site has no presenter of its own.
 *
 * <p>A {@code Cue} is an opaque id: this layer never plays anything, and the smart-cue rule in
 * {@link LootEngine} has already decided which cues were earned before one ever reaches here. What
 * remains is only WHO presents them. A site with its own presentation (a station, a spoils screen)
 * reads {@link LootEngine.Result#getCues()} and presents them itself; the shared reward path (a
 * quest, a shop offer, a dialogue paying out a {@code Lootable} reward) is not a surface and has
 * nothing to present WITH, so it forwards its earned cues here instead.
 *
 * <p>Exactly ONE presenter, deliberately, the same rule the stamp registry follows: two presenters
 * would celebrate the same cue twice at the same player, so the last registration WINS outright
 * rather than the two coexisting. A mod with a richer presentation replaces the plain one instead
 * of running beside it.
 *
 * <p>This library installs one at its own setup, and it is the obvious one: the cue id IS the
 * {@code FeedbackMoment} id, so a table writes {@code "Cue": "Rare_Find"}, a
 * {@code Server/ZiggfreedCommon/FeedbackMoments/Rare_Find.json} says what that means, and no
 * consumer writes any Java at all. Replace it during your own setup only to present a cue some
 * other way. Nothing here refuses loudly: an unregistered presenter, or a cue nobody authored a
 * moment for, simply goes unpresented - the grants beside the cue are untouched either way,
 * because presentation must never be the reason a payout did not happen.
 */
public final class LootCues {

    /**
     * Presents ONE earned cue to whoever the grant was for. The id is opaque - the presenter maps
     * it to its own sound, toast or particle - and {@code sourceId} says what paid out
     * (conventionally {@code "reward:<tableId>"}), for a presenter that wants to log or vary by
     * origin.
     */
    @FunctionalInterface
    public interface Presenter {
        void present(@Nonnull String cueId, @Nonnull Subject subject, @Nonnull String sourceId);
    }

    private static final AtomicReference<Presenter> ACTIVE = new AtomicReference<>();

    private LootCues() {
    }

    /** Install {@code presenter} as the one this server uses, replacing whatever was there. */
    public static void register(@Nullable Presenter presenter) {
        ACTIVE.set(presenter);
    }

    /** True when something can actually present a cue. */
    public static boolean isRegistered() {
        return ACTIVE.get() != null;
    }

    /** Drop the registration (test reset, and the shutdown path). */
    public static void clear() {
        ACTIVE.set(null);
    }

    /**
     * Present every cue in {@code cues}, in the order the pass earned them. Quiet on every edge: no
     * presenter registered means no presentation, a blank cue is skipped, and a presenter that
     * throws costs its own cue and never the grant that earned it.
     */
    public static void presentAll(@Nonnull List<String> cues, @Nonnull Subject subject,
            @Nonnull String sourceId) {
        Presenter presenter = ACTIVE.get();
        if (presenter == null || cues.isEmpty()) {
            return;
        }
        for (String cue : cues) {
            if (cue == null || cue.isBlank()) {
                continue;
            }
            try {
                presenter.present(cue, subject, sourceId);
            } catch (Throwable t) {
                // Presentation must never be the reason a payout failed, but a presenter that is
                // permanently broken should not be invisible either: the payout still stands and
                // the next cue still gets its turn.
                SafeLog.fine("loot cue '" + cue + "' presenter failed: " + t.getMessage());
            }
        }
    }
}
