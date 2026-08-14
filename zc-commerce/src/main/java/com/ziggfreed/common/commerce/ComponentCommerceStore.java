package com.ziggfreed.common.commerce;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's DEFAULT commerce persistence: resolve the subject's {@link CommerceComponent} and
 * delegate. Everything a shop, a board and a wallet remember about a player survives a restart with
 * the world that holds it.
 *
 * <p><b>The player comes off the subject's own handle</b>, exactly as the item wallet resolves one,
 * so nothing here learns a consumer's player representation. A handle answering for a live
 * {@link Player} is all this needs, and every engine in the library builds subjects that do.
 *
 * <p><b>A read never creates.</b> A subject with no handle, no live entity or no component reads
 * neutral - no balance, no counts, no rerolls - and a WRITE is dropped with one fine-level line. The
 * component is created once, at connect, by {@code CommerceDefaults}; a store that created one on
 * demand would stamp a component onto anything that so much as asked a question, including an
 * offline subject that cannot own one.
 *
 * <p><b>Which also settles the offline question.</b> A counter balance is per-entity state, so an
 * admin edit or a payout for a player who is not standing in a world has nowhere to land and is
 * refused rather than silently dropped somewhere it will not be read back from.
 *
 * <p>{@code flush} stays the interface no-op: the component's own codec persists a live component
 * with its world, so there is no transaction boundary to report.
 */
public final class ComponentCommerceStore implements CommerceStore {

    /** The one instance. It holds no state of its own - the component does. */
    public static final ComponentCommerceStore INSTANCE = new ComponentCommerceStore();

    private ComponentCommerceStore() {
    }

    /**
     * Attach the component if this player has none, the one moment a {@link Holder} is in hand.
     * Called from the connect hook; never throws.
     */
    public static void ensureOn(@Nonnull Holder<EntityStore> holder) {
        try {
            if (CommerceComponent.TYPE == null) {
                return;
            }
            holder.ensureAndGetComponent(CommerceComponent.TYPE);
        } catch (Throwable t) {
            SafeLog.warn("[commerce] could not ensure the commerce component", t);
        }
    }

    /** This subject's component, or null when there is none to read. */
    @Nullable
    public static CommerceComponent componentOf(@Nonnull Subject subject) {
        if (CommerceComponent.TYPE == null) {
            return null;
        }
        Player player = subject.handleAs(Player.class);
        if (player == null) {
            return null;
        }
        try {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null || !ref.isValid()) {
                return null;
            }
            Store<EntityStore> store = ref.getStore();
            return store == null ? null : store.getComponent(ref, CommerceComponent.TYPE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void dropped(@Nonnull Subject subject, @Nonnull String what) {
        SafeLog.fine("[commerce] dropped a " + what + " for '" + subject.name()
                + "': no commerce component");
    }

    // ==================== the wallet ====================

    @Override
    public long balance(@Nonnull Subject subject, @Nonnull String currencyId) {
        CommerceComponent component = componentOf(subject);
        return component == null ? 0L : component.balance(currencyId);
    }

    @Override
    public void setBalance(@Nonnull Subject subject, @Nonnull String currencyId, long value) {
        CommerceComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "balance");
            return;
        }
        component.setBalance(currencyId, value);
    }

    @Override
    @Nonnull
    public Map<String, Long> balances(@Nonnull Subject subject) {
        CommerceComponent component = componentOf(subject);
        return component == null ? Map.of() : component.balances();
    }

    @Override
    public void recordSpend(@Nonnull Subject subject, @Nonnull String currencyId, long amount) {
        CommerceComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "spend");
            return;
        }
        component.addLifetimeSpent(currencyId, amount);
    }

    @Override
    public long lifetimeSpent(@Nonnull Subject subject, @Nonnull String currencyId) {
        CommerceComponent component = componentOf(subject);
        return component == null ? 0L : component.lifetimeSpent(currencyId);
    }

    @Override
    public void refundSpend(@Nonnull Subject subject, @Nonnull String currencyId, long amount) {
        CommerceComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "spend refund");
            return;
        }
        component.refundLifetimeSpent(currencyId, amount);
    }

    @Override
    public void setLifetimeSpent(@Nonnull Subject subject, @Nonnull String currencyId, long amount) {
        CommerceComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "lifetime spend");
            return;
        }
        component.setLifetimeSpent(currencyId, amount);
    }

    // ==================== purchase limits ====================

    @Override
    public int purchasesToday(@Nonnull Subject subject, @Nonnull String offerId, long epochDay) {
        CommerceComponent component = componentOf(subject);
        return component == null ? 0 : component.purchasesToday(offerId, epochDay);
    }

    @Override
    public int purchasesTotal(@Nonnull Subject subject, @Nonnull String offerId) {
        CommerceComponent component = componentOf(subject);
        return component == null ? 0 : component.purchasesTotal(offerId);
    }

    @Override
    public void recordPurchase(@Nonnull Subject subject, @Nonnull String offerId, long epochDay) {
        CommerceComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "purchase");
            return;
        }
        component.recordPurchase(offerId, epochDay);
    }

    @Override
    public void setPurchases(@Nonnull Subject subject, @Nonnull String offerId, long epochDay,
            int today, int total) {
        CommerceComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "purchase count");
            return;
        }
        component.setPurchases(offerId, epochDay, today, total);
    }

    @Override
    public void clearPurchases(@Nonnull Subject subject) {
        CommerceComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "purchase reset");
            return;
        }
        component.clearPurchases();
    }

    /** Every offer this subject has a purchase record for, for an admin listing. */
    @Nonnull
    public Set<String> purchasedOfferIds(@Nonnull Subject subject) {
        CommerceComponent component = componentOf(subject);
        return component == null ? Set.of() : component.purchasedOfferIds();
    }

    // ==================== rotating-pool rerolls ====================

    @Override
    @Nonnull
    public Map<Integer, String> rerollOverrides(@Nonnull Subject subject, @Nonnull String poolId,
            long period) {
        CommerceComponent component = componentOf(subject);
        return component == null ? Map.of() : component.rerollOverrides(poolId, period);
    }

    @Override
    public int rerollsSpent(@Nonnull Subject subject, @Nonnull String poolId, long period) {
        CommerceComponent component = componentOf(subject);
        return component == null ? 0 : component.rerollsSpent(poolId, period);
    }

    @Override
    @Nonnull
    public Set<String> rerollSeenAt(@Nonnull Subject subject, @Nonnull String poolId, long period,
            int position) {
        CommerceComponent component = componentOf(subject);
        return component == null ? Set.of() : component.rerollSeenAt(poolId, period, position);
    }

    @Override
    public int rerollNextCount(@Nonnull Subject subject, @Nonnull String poolId, long period,
            int position) {
        CommerceComponent component = componentOf(subject);
        return component == null ? 1 : component.rerollNextCount(poolId, period, position);
    }

    @Override
    public boolean commitReroll(@Nonnull Subject subject, @Nonnull String poolId, long period,
            int maxPerPeriod, int position, @Nullable String replacedId, @Nonnull String newId) {
        CommerceComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "reroll");
            return false;
        }
        return component.commitReroll(poolId, period, maxPerPeriod, position, replacedId, newId);
    }

    @Override
    @Nonnull
    public RerollState rerollState(@Nonnull Subject subject, @Nonnull String poolId, long period) {
        CommerceComponent component = componentOf(subject);
        return component == null ? RerollState.none(period) : component.rerollState(poolId, period);
    }

    @Override
    public void setRerolls(@Nonnull Subject subject, @Nonnull String poolId,
            @Nonnull RerollState state) {
        CommerceComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "reroll state");
            return;
        }
        component.setRerolls(poolId, state);
    }

    @Override
    public void clearRerolls(@Nonnull Subject subject) {
        CommerceComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "reroll reset");
            return;
        }
        component.clearRerolls();
    }

    /** Every pool this subject has reroll state in, for an admin listing. */
    @Nonnull
    public Set<String> rerolledPoolIds(@Nonnull Subject subject) {
        CommerceComponent component = componentOf(subject);
        return component == null ? Set.of() : component.rerolledPoolIds();
    }

    /** The period this subject's state for {@code poolId} belongs to, or 0. */
    public long rerollPeriod(@Nonnull Subject subject, @Nonnull String poolId) {
        CommerceComponent component = componentOf(subject);
        return component == null ? 0L : component.rerollPeriod(poolId);
    }

    // ==================== one-time migrations ====================

    /**
     * {@inheritDoc}
     *
     * <p>A subject with no component answers FALSE, which refuses the migration rather than running
     * it against state that cannot be written - and, because a component is attached at connect, the
     * only subjects that see that answer are ones no migration could have helped anyway.
     */
    @Override
    public boolean claimMigration(@Nonnull Subject subject, @Nonnull String migrationId) {
        CommerceComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "migration claim");
            return false;
        }
        return component.claimMigration(migrationId);
    }
}
