package com.ziggfreed.common.factor;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * A lifetime tally as an ordinary factor reading: {@value #COUNTER}, whose {@code Param} is a
 * flat counter key and whose value is how often the player in the question has done that thing.
 *
 * <p><b>Why it exists.</b> Every lifetime counter a consumer keeps is recorded per player and per
 * thing, and until this reading nothing could ask about one: the only "you have done this"
 * readings were a finished quest and an earned achievement, so a boss that should unlock a shop row
 * or a dialogue branch had to carry a bookkeeping achievement nobody was meant to see. One id
 * serves bosses, blocks, fish and everything else the tallies hold, and it is contributed
 * process-wide, so a {@code Requires} block, a dialogue condition, a placement and a shop offer all
 * read it through whichever vocabulary they already read everything else through.
 *
 * <p><b>The param grammar is the counter's own.</b> A plain key reads a grand total; a category and
 * a name joined by {@code /} read one line of a breakdown, the shape the counter package files a
 * per-thing tally under beside its total. Which keys exist is the consumer's business: an unknown
 * key and a player who has never done the thing both read {@code 0}, so every authored condition
 * on this id needs a {@code Min}.
 *
 * <p><b>Fail-closed where it matters.</b> With no {@link CounterSource} installed, or with no live
 * player in the question, the reading is {@code null} (unanswerable), never {@code 0}: a gate must
 * not open because the mod that keeps the tallies is absent. An unfilled seam reports itself once,
 * the first time a condition actually asks.
 *
 * <p>Answered from the SAVED record the source keeps, never a live event, so a shop row or a locked
 * quest renders correctly for a player who is nowhere in particular.
 */
public final class CounterFactors {

    /** Who this contribution is attributed to in the ledger. */
    public static final String OWNER = "ziggfreedcommon";

    /**
     * {@code ziggfreedcommon:counter} - the player's lifetime tally under the key named by Param
     * ({@code mob_kills}, {@code mob_kills/Warden}, {@code encounters_defeated/Kweebec_Warden}).
     */
    public static final String COUNTER = "ziggfreedcommon:counter";

    private static final AtomicReference<CounterSource> SOURCE = new AtomicReference<>();
    private static final AtomicBoolean WARNED_UNFILLED = new AtomicBoolean();

    private CounterFactors() {
    }

    /** Claim the id process-wide. One call from the wiring root's {@code setup()}. */
    public static void contribute() {
        FactorContributions.register(COUNTER, OWNER, CounterFactors::resolve);
    }

    /**
     * Put the consumer that keeps the tallies in charge of answering them. One source per server:
     * the tallies are one record per player, whoever else reads them. Null clears it.
     */
    public static void source(@Nullable CounterSource source) {
        SOURCE.set(source);
    }

    /** Is a source installed? */
    public static boolean isSourceFilled() {
        return SOURCE.get() != null;
    }

    /** The reading itself: unanswerable with no key or no live player, else whatever the source says. */
    @Nullable
    static Double resolve(@Nonnull FactorContext ctx) {
        String key = ctx.param();
        if (key == null || key.isBlank() || !ctx.hasLiveSubject()) {
            return null;
        }
        return answer(SOURCE.get(), ctx, key.trim());
    }

    /**
     * The source's half of the reading, over a context already known to carry a live player.
     * Package-visible so a test can drive it over a stand-in source with no entity store.
     */
    @Nullable
    static Double answer(@Nullable CounterSource source, @Nonnull FactorContext ctx, @Nonnull String key) {
        if (source == null) {
            if (WARNED_UNFILLED.compareAndSet(false, true)) {
                SafeLog.warn("[factor] a condition asked " + COUNTER + " but no mod has installed a"
                        + " counter source, so every gate on it stays shut");
            }
            return null;
        }
        try {
            Long count = source.count(ctx, key);
            return count == null ? null : Double.valueOf(count);
        } catch (Throwable t) {
            SafeLog.warn("[factor] the counter source failed for '" + key + "': " + t.getMessage());
            return null;
        }
    }

    /** Drop the source and the once-only report; for a test starting from nothing. */
    public static void resetForTests() {
        SOURCE.set(null);
        WARNED_UNFILLED.set(false);
    }
}
