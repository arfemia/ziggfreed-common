package com.ziggfreed.common.pvp;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;

/**
 * The PvP ledger's two readings of the subject, contributed process-wide at setup so a gate anywhere reads them (a held
 * effect's {@code Requires}, a grant bound {@code While} a {@code ZigRequires} condition, a reward row):
 * <ul>
 *   <li>{@value #STREAK}: the credited kills of other players since the subject last died ({@link PvpRecord#streak});</li>
 *   <li>{@value #BOUNTY}: 1 while that streak carries a bounty under the subject's world's rules, else 0 (a bounty's
 *       mark is a held effect on {@code Min 1}; "kills pay more while a bounty stands" a grant bound on it).</li>
 * </ul>
 * Both answer null with no live subject and 0 for a creature. A reading of the subject's own record, so their
 * volatility is the default {@code Moment}.
 */
public final class PvpFactors {

    /** The subject's streak of credited kills. */
    public static final String STREAK = "ziggfreedcommon:pvp_streak";

    /** 1 while the subject carries a bounty. */
    public static final String BOUNTY = "ziggfreedcommon:pvp_bounty";

    /** Who the contributions are attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    private PvpFactors() {
    }

    /** Claim both ids process-wide. Once, from setup. */
    public static void contribute() {
        FactorContributions.register(STREAK, OWNER, PvpFactors::resolveStreak);
        FactorContributions.register(BOUNTY, OWNER, PvpFactors::resolveBounty);
    }

    @Nullable
    static Double resolveStreak(@Nonnull FactorContext ctx) {
        Store<EntityStore> store = ctx.store();
        Ref<EntityStore> subject = ctx.subject();
        if (!ctx.hasLiveSubject() || store == null || subject == null) {
            return null;
        }
        return (double) PvpLedger.streakOf(store, subject);
    }

    @Nullable
    static Double resolveBounty(@Nonnull FactorContext ctx) {
        Store<EntityStore> store = ctx.store();
        Ref<EntityStore> subject = ctx.subject();
        if (!ctx.hasLiveSubject() || store == null || subject == null) {
            return null;
        }
        return PvpLedger.hasBounty(store, subject) ? 1.0 : 0.0;
    }
}
