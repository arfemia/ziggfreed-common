package com.ziggfreed.common.reputation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.FactorNames;

/**
 * The three reputation readings, contributed to every vocabulary on the server. {@code Param} names the
 * reputation (its native group id, any case), and for the rank reading the rank after a slash:
 * <ul>
 *   <li>{@value #STANDING}: effective standing, gear included;</li>
 *   <li>{@value #EARNED}: earned standing alone;</li>
 *   <li>{@value #RANK} (Param {@code <Id>/<Rank>}): 1 at or above that rank's floor by effective standing, else 0;
 *       the rank may be one of the reputation's own above the top.</li>
 * </ul>
 * Null (fail closed) for an unknown, disabled or switched-off reputation, an unknown rank, a ladder of
 * fewer than two ranks (rank reading only), or no live player; a known untouched reputation reads its
 * starting value. Every authored condition carries a {@code Min}.
 */
public final class ReputationFactors {

    public static final String OWNER = "ziggfreedcommon";

    public static final String STANDING = "ziggfreedcommon:reputation";

    public static final String EARNED = "ziggfreedcommon:reputation_earned";

    public static final String RANK = "ziggfreedcommon:reputation_rank";

    private ReputationFactors() {
    }

    /** Claim the three ids process-wide, and name a rank gate's lock line. Once, at library setup. */
    public static void contribute(@Nonnull ReputationService service) {
        FactorContributions.register(STANDING, OWNER, ctx -> standing(service, ctx));
        FactorContributions.register(EARNED, OWNER, ctx -> earned(service, ctx));
        FactorContributions.register(RANK, OWNER, ctx -> rank(service, ctx));
        FactorNames.registerParamNamer(RANK, param -> rankLockName(service, param));
    }

    @Nullable
    static Double standing(@Nonnull ReputationService service, @Nonnull FactorContext ctx) {
        ReputationService.Standing standing = service.standing(ctx.store(), ctx.subject(), ctx.param());
        return standing == null ? null : (double) standing.effective();
    }

    @Nullable
    static Double earned(@Nonnull ReputationService service, @Nonnull FactorContext ctx) {
        ReputationService.Standing standing = service.standing(ctx.store(), ctx.subject(), ctx.param());
        return standing == null ? null : (double) standing.earned();
    }

    @Nullable
    static Double rank(@Nonnull ReputationService service, @Nonnull FactorContext ctx) {
        String param = ctx.param();
        if (param == null) {
            return null;
        }
        int slash = param.lastIndexOf('/');
        if (slash <= 0 || slash == param.length() - 1) {
            return null;
        }
        String reputationId = param.substring(0, slash).trim();
        ReputationLadder ladder = service.ladderOf(reputationId);
        ReputationLadder.Rank rank = ladder.usable() ? ladder.byId(param.substring(slash + 1)) : null;
        if (rank == null) {
            return null;
        }
        ReputationService.Standing standing = service.standing(ctx.store(), ctx.subject(), reputationId);
        return standing == null ? null : standing.effective() >= rank.min() ? 1.0 : 0.0;
    }

    /**
     * What a lock line on {@code <Id>/<Rank>} calls the gate: the rank's name with that reputation (its
     * {@code Ranks.<Rank>.Name}, else the library's word) and the reputation's own name, as
     * {@code factor.rank_with}. Null, so the generic line reads, when the reputation names itself nowhere
     * ({@code Text.TitleKey}) or the rank is not on its ladder. A pack's ParamNames entry for the exact
     * Param is asked first and still wins.
     */
    @Nullable
    static Message rankLockName(@Nonnull ReputationService service, @Nonnull String param) {
        int slash = param.lastIndexOf('/');
        if (slash <= 0 || slash == param.length() - 1) {
            return null;
        }
        ReputationDef def = service.known(param.substring(0, slash).trim());
        if (def == null || def.titleKey() == null) {
            return null;
        }
        ReputationLadder ladder = service.ladderFor(def);
        ReputationLadder.Rank rank = ladder.usable() ? ladder.byId(param.substring(slash + 1)) : null;
        return rank == null ? null
                : ReputationText.line("factor.rank_with", ReputationText.rankName(def, rank), ReputationText.name(def));
    }
}
