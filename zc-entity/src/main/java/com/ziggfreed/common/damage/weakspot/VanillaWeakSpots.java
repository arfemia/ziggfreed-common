package com.ziggfreed.common.damage.weakspot;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.AngledDamage;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCalculatorSystems;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.DamageEntityInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.combat.DamageCalculator;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The weak-spot bonuses vanilla pays itself, by the identity of the branch calculator that pays them, to the extra
 * fraction it pays over its interaction's own calculator: every {@code TargetedDamage} entry keyed {@code Head}
 * (the shortbow's full draw: 48 against 37, about +30%) and every {@code AngledDamage} entry whose arc covers
 * straight behind (a dagger's backstab branch), each with a calculator of its own. The engine runs a hit's branch
 * calculator and puts it on the hit's first damage ({@code DamageCalculatorSystems.DAMAGE_SEQUENCE}), so that
 * damage names the branch it took ({@link #paidOn}).
 *
 * <p><b>Once per hit.</b> A hit of several causes is several damages, only the first carrying the calculator, all
 * sharing one source object ({@code DamageEntityInteraction} builds one per target and hands it to each), and the
 * engine runs them one after the other on the world thread. {@link #paidForHit} decides on the first and answers the
 * same for every later damage of that source and target, so each cause nets out vanilla's bonus.
 *
 * <p>One immutable snapshot behind a volatile field, rebuilt on the first read after an interaction load or
 * removal marks it (an item reload reloads the interactions authored inside it). A calculator shared by two
 * interactions keeps the larger share, so a weak spot never stacks on the bigger one.
 */
final class VanillaWeakSpots {

    /** The detail box a headshot names, as the engine's models and vanilla's TargetedDamage spell it. */
    static final String HEAD = "Head";

    /** What one vanilla branch pays: its share over the body's, and whether it is a headshot or a backstab. */
    record Paid(double share, boolean head, boolean backstab) {
    }

    private static final Object LOCK = new Object();

    private static final AtomicBoolean BUILD_FAILURE_LOGGED = new AtomicBoolean();

    @Nonnull
    private static volatile Map<DamageCalculator, Paid> branches = Map.of();

    private static volatile boolean dirty = true;

    /** The last hit decided on this thread: its source object, its target and what vanilla paid on it. */
    private static final class LastHit {
        @Nullable Damage.Source source;
        @Nullable Ref<EntityStore> target;
        @Nullable Paid paid;
    }

    private static final ThreadLocal<LastHit> LAST = ThreadLocal.withInitial(LastHit::new);

    private VanillaWeakSpots() {
    }

    static void markDirty() {
        dirty = true;
    }

    /**
     * What vanilla paid on the hit {@code damage} belongs to, or null when the hit took no vanilla weak-spot branch
     * with damage of its own: decided on the hit's first damage (the one carrying the calculator) and answered the
     * same for every later damage of that hit. Never throws.
     */
    @Nullable
    static Paid paidForHit(@Nonnull Damage damage, @Nonnull Ref<EntityStore> target) {
        try {
            DamageCalculatorSystems.DamageSequence sequence =
                    damage.getIfPresentMetaObject(DamageCalculatorSystems.DAMAGE_SEQUENCE);
            LastHit last = LAST.get();
            if (sequence != null) {
                Map<DamageCalculator, Paid> current = current();
                Paid paid = current.isEmpty() ? null : current.get(sequence.getDamageCalculator());
                last.source = damage.getSource();
                last.target = target;
                last.paid = paid;
                return paid;
            }
            return last.source == damage.getSource() && target.equals(last.target) ? last.paid : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * What a branch pays over the body's, as a fraction: {@code branch / body - 1}, floored at 0; 0 when either
     * total is not a positive number. Pure.
     */
    static double share(double branchTotal, double bodyTotal) {
        if (!(branchTotal > 0.0) || !(bodyTotal > 0.0) || !Double.isFinite(branchTotal) || !Double.isFinite(bodyTotal)) {
            return 0.0;
        }
        return Math.max(0.0, branchTotal / bodyTotal - 1.0);
    }

    /**
     * Does an arc at {@code angle} (radians, 0 in front, as the engine measures it) wide {@code distance} either
     * side cover straight behind? Pure.
     */
    static boolean coversBehind(double angle, double distance) {
        double turn = Math.PI * 2.0;
        double diff = Math.PI - angle + Math.PI;
        diff = diff - turn * Math.floor(diff / turn) - Math.PI;
        return Math.abs(diff) < distance;
    }

    @Nonnull
    private static Map<DamageCalculator, Paid> current() {
        if (dirty) {
            rebuild();
        }
        return branches;
    }

    private static void rebuild() {
        synchronized (LOCK) {
            if (!dirty) {
                return;
            }
            dirty = false;
            try {
                branches = build();
            } catch (Throwable t) {
                if (BUILD_FAILURE_LOGGED.compareAndSet(false, true)) {
                    SafeLog.warn("[weakspot] could not read vanilla's own headshot and backstab branches; a weak spot"
                            + " reads every hit as unpaid until the next interaction load", t);
                }
                branches = Map.of();
            }
        }
    }

    @Nonnull
    private static Map<DamageCalculator, Paid> build() {
        IndexedLookupTableAssetMap<String, Interaction> map = Interaction.getAssetMap();
        Map<DamageCalculator, Paid> out = new IdentityHashMap<>();
        int end = map.getNextIndex();
        float[] range = new float[2];
        for (int index = 0; index < end; index++) {
            if (!(map.getAsset(index) instanceof DamageEntityInteraction damage)) {
                continue;
            }
            DamageCalculator body = damage.getDamageCalculator();
            if (body == null) {
                continue;
            }
            double bodyTotal = total(body, range);
            Map<String, DamageEntityInteraction.TargetedDamage> targeted = damage.getTargetedDamage();
            if (targeted != null) {
                for (Map.Entry<String, DamageEntityInteraction.TargetedDamage> entry : targeted.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(HEAD) && entry.getValue() != null) {
                        put(out, entry.getValue().getDamageCalculator(), body, bodyTotal, range, true);
                    }
                }
            }
            DamageEntityInteraction.AngledDamage[] angled = damage.getAngledDamage();
            if (angled != null) {
                for (DamageEntityInteraction.AngledDamage branch : angled) {
                    if (branch != null && branch.getDamageCalculator() != null && behind(branch)) {
                        put(out, branch.getDamageCalculator(), body, bodyTotal, range, false);
                    }
                }
            }
        }
        return out.isEmpty() ? Map.of() : out;
    }

    private static void put(@Nonnull Map<DamageCalculator, Paid> out, @Nullable DamageCalculator branch,
            @Nonnull DamageCalculator body, double bodyTotal, @Nonnull float[] range, boolean head) {
        if (branch == null || branch == body) {
            return;
        }
        double share = share(total(branch, range), bodyTotal);
        if (share <= 0.0) {
            return;
        }
        Paid before = out.get(branch);
        if (before == null || before.share() < share) {
            out.put(branch, new Paid(share, head || (before != null && before.head()),
                    !head || (before != null && before.backstab())));
        }
    }

    /** Does an angled branch's arc cover straight behind? Read through its packet (its angles are not public). */
    private static boolean behind(@Nonnull DamageEntityInteraction.AngledDamage branch) {
        try {
            AngledDamage packet = branch.toAngledDamagePacket();
            return coversBehind(packet.angle, packet.angleDistance);
        } catch (Throwable t) {
            // Unreadable: count it, so a weak spot nets it out rather than stacking on it.
            return true;
        }
    }

    /** A calculator's damage over a second, every cause summed, at the middle of its random range. */
    private static double total(@Nonnull DamageCalculator calculator, @Nonnull float[] range) {
        calculator.computeDamageRange(1.0, range);
        return (range[0] + range[1]) / 2.0;
    }
}
