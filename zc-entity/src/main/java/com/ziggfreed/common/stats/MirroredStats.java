package com.ziggfreed.common.stats;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorProvider;
import com.ziggfreed.common.util.SafeLog;

/**
 * The stat ids a consumer answers from a record it keeps: a number whose truth is the record (a skill's level, a
 * track's climb), so a modifier from gear, an effect or a grant never counts.
 *
 * <p>Every reading of a claimed id AS A VALUE asks the owner: {@code hytale:stat} answers the owner's
 * {@link Claim#reading()} for it ({@code HytaleFactors.resolveStat}), so a gate, a formula term or a threshold step
 * reads the record whatever is worn; {@code ziggfreedcommon:station_stat} reads exactly that, with no bound share
 * added or taken out, and {@code station_share} reads 0 on one ({@code condition/StationStatReading}); a proc's stat
 * ({@code Procs.statPoints}) and a consumer's own reader of an authored stat ({@link #claimOf}, {@link #readAt}) ask
 * the claim too. None of these looks the id up as a stat first, so a claimed id is a READING that needs no stat file:
 * an owner may claim ids no stat type carries (a skill's level ships none), which costs no living entity a slot, and
 * the audits that would call an id unknown (a gear set tier, a held effect, a track's {@code Mirror}) ask the claim
 * first. Where a stat of the id does load, the owner may keep it in step ({@link StatMirror}) for a reader that reads
 * it as something else (a pool a cost spends, an effect family's length stat, a conditional stat's own payout),
 * which still reads its fold: a reading is never one. An audit of a surface that puts a stat on gear (a gear set
 * tier) warns on a line naming a claimed id, with the owner's own reason ({@link Claim#why()}), since that line does
 * nothing.
 *
 * <p>Claimed once at setup by the owner, matched on the stat's current id ({@link StatIndexCache#currentId}, so a
 * renamed id reads as its successor). With no claim, nothing changes: a zc-only server reads every stat as the
 * engine folds it. Any thread; the claims are an immutable-on-read list.
 */
public final class MirroredStats {

    /**
     * One owner's claim: the stats it mirrors, how a live subject's value of one reads off the owner's record (handed
     * the question with its {@code Param} set to the stat's current id; null for "cannot tell", never the folded
     * stat), and why a gear line on one does nothing, in raw English for an audit line.
     */
    public record Claim(@Nonnull String owner, @Nonnull Predicate<String> stats, @Nonnull FactorProvider reading,
            @Nonnull String why) {
    }

    private static final List<Claim> CLAIMS = new CopyOnWriteArrayList<>();

    private MirroredStats() {
    }

    /**
     * Claim the stats {@code stats} accepts for {@code owner}, at setup. A second claim by the same owner replaces
     * its first.
     */
    public static void claim(@Nonnull String owner, @Nonnull Predicate<String> stats, @Nonnull FactorProvider reading,
            @Nonnull String why) {
        CLAIMS.removeIf(claim -> claim.owner().equalsIgnoreCase(owner));
        CLAIMS.add(new Claim(owner, stats, reading, why));
    }

    /** The claim on {@code statId} (its current id), or null when no owner mirrors it. Never throws. */
    @Nullable
    public static Claim claimOf(@Nullable String statId) {
        if (CLAIMS.isEmpty() || statId == null || statId.isBlank()) {
            return null;
        }
        String id = StatIndexCache.currentId(statId.trim());
        for (Claim claim : CLAIMS) {
            try {
                if (claim.stats().test(id)) {
                    return claim;
                }
            } catch (Throwable t) {
                SafeLog.fine("[stats] a mirrored-stat claim by " + claim.owner() + " failed to answer for " + id
                        + ": " + t.getMessage());
            }
        }
        return null;
    }

    /**
     * What the claimed stat {@code ctx} names reads for its live subject: the owner's reading, asked with the stat's
     * current id; null when it cannot tell or throws. The one call {@code hytale:stat} makes for a mirrored stat.
     */
    @Nullable
    public static Double read(@Nonnull Claim claim, @Nonnull FactorContext ctx) {
        String statId = ctx.param();
        try {
            FactorContext asked = statId == null ? ctx : ctx.withParam(StatIndexCache.currentId(statId.trim()));
            return claim.reading().resolve(asked);
        } catch (Throwable t) {
            SafeLog.fine("[stats] the mirrored-stat reading by " + claim.owner() + " failed for " + statId + ": "
                    + t.getMessage());
            return null;
        }
    }

    /**
     * {@link #read} for a reader holding a live entity rather than a question (an authored stat a cast scales by):
     * the claim's reading of {@code statId} for {@code ref}, null when it cannot tell.
     */
    @Nullable
    public static Double readAt(@Nonnull Claim claim, @Nonnull Ref<EntityStore> ref, @Nonnull String statId) {
        return read(claim, FactorContext.about(ref.getStore(), ref).withParam(statId));
    }

    /** Drop every claim. Tests only. */
    public static void clearForTests() {
        CLAIMS.clear();
    }
}
