package com.ziggfreed.common.stats.damage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.ResistanceModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The engine's own outgoing-damage sum, read the way its outgoing step builds it: every active effect's
 * {@code OutgoingDamage} modifiers whose key the hit's cause is or inherits from, flats added and percents
 * summed, applied as {@code (amount + flat) * (1 + percent)} (R567). Enrage, a potion and this library's
 * own {@code Zig_DamageBonus} lines all land in the one sum.
 *
 * <p><b>Adding to a hit's sum.</b> A step running after the engine's that has a further percent to add (a
 * route line, a consumer's conditional term) calls {@link #addToSum}, which rescales the hit as if the
 * percent had been in the engine's sum from the start: {@code amount * (1 + P + r) / (1 + P)}, plus any flat
 * at {@code (1 + P + r)}. The sum so far rides the hit itself, so a second caller composes additively with
 * the first rather than multiplying on top of it.
 */
public final class NativeOutgoing {

    private NativeOutgoing() {
    }

    /** An outgoing sum: {@code percent} as a fraction (0.25 is +25%) and {@code flat} in damage points. */
    public record Sums(double percent, double flat) {

        public static final Sums NONE = new Sums(0.0, 0.0);

        public boolean isZero() {
            return percent == 0.0 && flat == 0.0;
        }
    }

    /** One active effect's outgoing modifiers on one cause key, already summed. */
    public record Entry(@Nonnull DamageCause key, double percent, double flat) {
    }

    /**
     * An entity's outgoing modifiers as they stood when read, for a display that folds many numbers of
     * different causes from one read. Immutable.
     */
    public record Snapshot(@Nonnull List<Entry> entries) {

        public static final Snapshot EMPTY = new Snapshot(List.of());

        /** The sum this snapshot gives a hit of {@code causeId}; {@link Sums#NONE} for an unknown cause. */
        @Nonnull
        public Sums sumsFor(@Nullable String causeId) {
            if (entries.isEmpty() || causeId == null) {
                return Sums.NONE;
            }
            DamageCause cause = loadedCause(causeId);
            if (cause == null) {
                return Sums.NONE;
            }
            double percent = 0.0;
            double flat = 0.0;
            for (Entry entry : entries) {
                if (cause.isOrInherits(entry.key())) {
                    percent += entry.percent();
                    flat += entry.flat();
                }
            }
            return new Sums(percent, flat);
        }
    }

    /** The hit's running sum, put there by the first {@link #sumOn} of the hit. */
    private static final class Meta {
        static final MetaKey<Double> SUM = Damage.META_REGISTRY.registerMetaObject();
    }

    /** The engine's own outgoing sum for a hit of {@code cause} by {@code attacker}. */
    @Nonnull
    public static Sums of(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> attacker,
            @Nullable DamageCause cause) {
        if (attacker == null || cause == null || !attacker.isValid()) {
            return Sums.NONE;
        }
        EffectControllerComponent ctrl = accessor.getComponent(attacker, EffectControllerComponent.getComponentType());
        if (ctrl == null) {
            return Sums.NONE;
        }
        double percent = 0.0;
        double flat = 0.0;
        for (int index : ctrl.getActiveEffects().keySet()) {
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(index);
            Map<DamageCause, ResistanceModifier[]> values = effect != null ? effect.getOutgoingDamageValues() : null;
            if (values == null) {
                continue;
            }
            for (Map.Entry<DamageCause, ResistanceModifier[]> e : values.entrySet()) {
                if (e.getKey() == null || !cause.isOrInherits(e.getKey())) {
                    continue;
                }
                for (ResistanceModifier modifier : e.getValue()) {
                    if (modifier.getCalculationType() == ResistanceModifier.ResistanceCalculationType.FLAT) {
                        flat += modifier.getAmount();
                    } else {
                        percent += modifier.getAmount();
                    }
                }
            }
        }
        return percent == 0.0 && flat == 0.0 ? Sums.NONE : new Sums(percent, flat);
    }

    /** {@link #of(ComponentAccessor, Ref, DamageCause)} for a cause id; {@link Sums#NONE} for an unknown one. */
    @Nonnull
    public static Sums of(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> attacker,
            @Nullable String causeId) {
        return causeId == null ? Sums.NONE : of(accessor, attacker, loadedCause(causeId));
    }

    /** The engine's own percent sum (a fraction) for a hit of {@code cause} by {@code attacker}. */
    public static double percentFor(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> attacker, @Nullable DamageCause cause) {
        return of(accessor, attacker, cause).percent();
    }

    /** Every outgoing modifier {@code ref} carries now, for {@link Snapshot#sumsFor}. Never throws. */
    @Nonnull
    public static Snapshot snapshot(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> ref) {
        try {
            if (ref == null || !ref.isValid()) {
                return Snapshot.EMPTY;
            }
            EffectControllerComponent ctrl = accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ctrl == null) {
                return Snapshot.EMPTY;
            }
            List<Entry> out = new ArrayList<>();
            for (int index : ctrl.getActiveEffects().keySet()) {
                EntityEffect effect = EntityEffect.getAssetMap().getAsset(index);
                Map<DamageCause, ResistanceModifier[]> values = effect != null ? effect.getOutgoingDamageValues() : null;
                if (values == null) {
                    continue;
                }
                for (Map.Entry<DamageCause, ResistanceModifier[]> e : values.entrySet()) {
                    double percent = 0.0;
                    double flat = 0.0;
                    for (ResistanceModifier modifier : e.getValue()) {
                        if (modifier.getCalculationType() == ResistanceModifier.ResistanceCalculationType.FLAT) {
                            flat += modifier.getAmount();
                        } else {
                            percent += modifier.getAmount();
                        }
                    }
                    if (e.getKey() != null && (percent != 0.0 || flat != 0.0)) {
                        out.add(new Entry(e.getKey(), percent, flat));
                    }
                }
            }
            return out.isEmpty() ? Snapshot.EMPTY : new Snapshot(List.copyOf(out));
        } catch (Throwable t) {
            return Snapshot.EMPTY;
        }
    }

    /**
     * The percent sum (a fraction) {@code damage} carries so far: the engine's outgoing sum for its cause
     * and attacker, plus every percent {@link #addToSum} added since. The first read of a hit records it.
     */
    public static double sumOn(@Nonnull Damage damage, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> attacker) {
        Double held = damage.getIfPresentMetaObject(Meta.SUM);
        if (held != null) {
            return held;
        }
        DamageCause cause = DamageCause.getAssetMap().getAsset(damage.getDamageCauseIndex());
        double sum = percentFor(accessor, attacker, cause);
        damage.putMetaObject(Meta.SUM, sum);
        return sum;
    }

    /**
     * Add {@code percent} (a fraction) and {@code flat} to a hit that took the engine's outgoing step, as if
     * they had been in its sum: the hit's amount becomes {@link #addedToSum}'s, the hit's recorded sum grows
     * by {@code percent}, and the new amount is returned. A zero pair leaves the hit untouched.
     */
    public static float addToSum(@Nonnull Damage damage, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> attacker, double percent, double flat) {
        if ((percent == 0.0 && flat == 0.0) || !Double.isFinite(percent) || !Double.isFinite(flat)) {
            return damage.getAmount();
        }
        double sum = sumOn(damage, accessor, attacker);
        float amount = (float) addedToSum(damage.getAmount(), sum, percent, flat);
        damage.setAmount(amount);
        damage.putMetaObject(Meta.SUM, sum + percent);
        return amount;
    }

    /**
     * The pure arithmetic of {@link #addToSum}: {@code amount}, which took the sum {@code sum}, rescaled to
     * {@code sum + percent} with {@code flat} added inside the sum, floored at zero. A hit whose sum had
     * already taken it to nothing ({@code 1 + sum <= 0}) stays as it is, since nothing is left to rescale.
     */
    public static double addedToSum(double amount, double sum, double percent, double flat) {
        if (percent == 0.0 && flat == 0.0) {
            return amount;
        }
        double before = 1.0 + sum;
        if (!(before > 1e-9)) {
            return amount;
        }
        double after = before + percent;
        return Math.max(0.0, amount * after / before + flat * after);
    }

    @Nullable
    private static DamageCause loadedCause(@Nonnull String causeId) {
        try {
            return DamageCause.getAssetMap().getAsset(causeId);
        } catch (Throwable t) {
            return null;
        }
    }
}
