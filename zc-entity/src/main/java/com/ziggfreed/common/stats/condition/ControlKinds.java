package com.ziggfreed.common.stats.condition;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * Every kind of control's rule, read off its file ({@link ControlKindAsset}) and the shared settings
 * ({@link ControlSettingsAsset}), as one immutable snapshot rebuilt on the first read after either folds. The one
 * read the landing (zc-effects' {@code ControlLanding}), the effect-length pass (a kind's cap, a fixed control's
 * skips) and the foe's rules ({@link BossRules}: each kind's {@code OnBossOrElite} is the family default a boss or an
 * elite starts from) share; nothing about a kind's behaviour is code. A kind with no file reads {@link Rule#NONE}: no
 * immunity, no shrink, no ramp, no cap, usual on a boss. Needs nothing wired: a server running CommonLib alone reads
 * the library's own files.
 */
public final class ControlKinds {

    private static final Object LOCK = new Object();

    private static final AtomicBoolean BUILD_FAILURE_LOGGED = new AtomicBoolean();

    /** One kind's rule, as its file folds. */
    public record Rule(boolean refusedByImmune, boolean shrinks, boolean ramps, @Nullable Double rampAmount,
            double maxSeconds, @Nullable ControlReading onBossOrElite) {

        /** A kind with no file: nothing of the landing's own, usual on a boss. */
        public static final Rule NONE = new Rule(false, false, false, null, 0.0, null);

        /** The rule {@code asset} writes. */
        @Nonnull
        public static Rule of(@Nonnull ControlKindAsset asset) {
            return new Rule(asset.refusedByImmune(), asset.shrinks(), asset.ramps(), asset.rampAmount(),
                    asset.maxSeconds(), asset.onBossOrElite());
        }
    }

    /**
     * What one landing of a control of several kinds at once follows (an effect whose families name two): refused by
     * a foe's immunity if any of its kinds is, shorter for the foe's Immunity if any of them shrinks, the largest ramp
     * among those that ramp (0 for none), and the lowest cap among those that cap (0 for none).
     */
    public record Landing(boolean refusedByImmune, boolean shrinks, double rampAmount, double maxSeconds) {

        /** A control of no kind: nothing of the landing's own. */
        public static final Landing NONE = new Landing(false, false, 0.0, 0.0);

        /** Does a landing add Immunity? */
        public boolean ramps() {
            return rampAmount > 0.0;
        }
    }

    /** The snapshot: each kind's rule by bit, the family default as a rule, the capped kinds, and the settings. */
    private record Snapshot(@Nonnull Rule[] byBit, @Nonnull ControlRule family, int capped, double rampAmount,
            boolean fixedSkipsImmunity, boolean fixedSkipsResist, boolean fixedSkipsAddedSeconds) {
        static final Snapshot EMPTY = new Snapshot(new Rule[0], ControlRule.NONE, 0,
                ControlSettingsAsset.DEFAULT_RAMP_AMOUNT, true, true, true);
    }

    @Nonnull
    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    private static volatile boolean dirty = true;

    private ControlKinds() {
    }

    /**
     * Drop the snapshot so the next read rebuilds it, after declaring every kind the files name (so an effect family
     * or a rule tag naming a pack's kind reads it at once), and mark the tables that parse kind names.
     */
    public static void markDirty() {
        dirty = true;
        try {
            for (ControlKindAsset asset : ControlKindConfig.getInstance().all().values()) {
                if (asset != null) {
                    ControlKind.declare(asset.getId(), asset.description());
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[control] could not declare the kinds of control: " + t.getMessage());
        }
        EffectFamilies.markDirty();
        ControlRuleTables.kindsChanged();
    }

    /** {@code kind}'s rule, {@link Rule#NONE} for a kind with no file. */
    @Nonnull
    public static Rule rule(@Nonnull ControlKind kind) {
        Rule[] byBit = current().byBit();
        int bit = Integer.numberOfTrailingZeros(kind.bit());
        Rule rule = bit < byBit.length ? byBit[bit] : null;
        return rule == null ? Rule.NONE : rule;
    }

    /** What a landing of the kinds in {@code kindMask} follows; {@link Landing#NONE} for none. */
    @Nonnull
    public static Landing landing(int kindMask) {
        Snapshot local = current();
        return combine(local.byBit(), kindMask, local.rampAmount());
    }

    /** The longest a control of the kinds in {@code kindMask} lasts, in seconds: the lowest cap among them, 0 none. */
    public static double maxSeconds(int kindMask) {
        Snapshot local = current();
        return (kindMask & local.capped()) == 0 ? 0.0 : combine(local.byBit(), kindMask, 0.0).maxSeconds();
    }

    /** Every kind with a cap, as a mask: an effect of none of them is never held to one. */
    public static int cappedMask() {
        return current().capped();
    }

    /**
     * The family default a boss or an elite starts from, as one rule layer: every kind whose {@code OnBossOrElite}
     * is {@code Lands} or {@code Shrugs}.
     */
    @Nonnull
    public static ControlRule familyRule() {
        return current().family();
    }

    /** The Immunity one landing of a ramping kind adds unless the kind writes its own. */
    public static double rampAmount() {
        return current().rampAmount();
    }

    /** Does a fixed control skip the foe's immunity (no refusal, no shrink, no ramp)? */
    public static boolean fixedSkipsImmunity() {
        return current().fixedSkipsImmunity();
    }

    /** Does a fixed control skip the carrier's resistance? */
    public static boolean fixedSkipsResist() {
        return current().fixedSkipsResist();
    }

    /** Does a fixed control skip its owner's added seconds? */
    public static boolean fixedSkipsAddedSeconds() {
        return current().fixedSkipsAddedSeconds();
    }

    /**
     * The landing the kinds in {@code kindMask} follow, each kind's rule at its bit in {@code byBit} (a missing one
     * reads {@link Rule#NONE}) and {@code sharedRamp} the amount a ramping kind without its own adds. Pure.
     */
    @Nonnull
    public static Landing combine(@Nonnull Rule[] byBit, int kindMask, double sharedRamp) {
        if (kindMask == 0) {
            return Landing.NONE;
        }
        boolean refused = false;
        boolean shrinks = false;
        double ramp = 0.0;
        double cap = 0.0;
        for (int bit = 0; bit < Integer.SIZE; bit++) {
            if ((kindMask & (1 << bit)) == 0) {
                continue;
            }
            Rule rule = bit < byBit.length && byBit[bit] != null ? byBit[bit] : Rule.NONE;
            refused |= rule.refusedByImmune();
            shrinks |= rule.shrinks();
            if (rule.ramps()) {
                double amount = rule.rampAmount() != null ? rule.rampAmount() : sharedRamp;
                ramp = Math.max(ramp, Double.isFinite(amount) ? Math.max(0.0, amount) : 0.0);
            }
            if (rule.maxSeconds() > 0.0) {
                cap = cap > 0.0 ? Math.min(cap, rule.maxSeconds()) : rule.maxSeconds();
            }
        }
        return new Landing(refused, shrinks, ramp, cap);
    }

    @Nonnull
    private static Snapshot current() {
        if (dirty) {
            rebuild();
        }
        return snapshot;
    }

    private static void rebuild() {
        synchronized (LOCK) {
            if (!dirty) {
                return;
            }
            // Cleared before the walk, so a fold landing mid-walk marks it again and the next read rebuilds.
            dirty = false;
            try {
                snapshot = build();
            } catch (Throwable t) {
                if (BUILD_FAILURE_LOGGED.compareAndSet(false, true)) {
                    SafeLog.warn("[control] could not read the kinds of control; each reads no rule of its own until "
                            + "the next kind load", t);
                }
                snapshot = Snapshot.EMPTY;
            }
        }
    }

    @Nonnull
    private static Snapshot build() {
        Map<String, ControlKindAsset> files = ControlKindConfig.getInstance().all();
        Rule[] byBit = new Rule[ControlKind.MAX];
        int lands = 0;
        int shrugs = 0;
        int capped = 0;
        for (ControlKindAsset asset : files.values()) {
            ControlKind kind = asset == null ? null : ControlKind.declare(asset.getId(), asset.description());
            if (kind == null) {
                continue;
            }
            Rule rule = Rule.of(asset);
            byBit[Integer.numberOfTrailingZeros(kind.bit())] = rule;
            if (rule.onBossOrElite() == ControlReading.LANDS) {
                lands |= kind.bit();
            } else if (rule.onBossOrElite() == ControlReading.SHRUGS) {
                shrugs |= kind.bit();
            }
            if (rule.maxSeconds() > 0.0) {
                capped |= kind.bit();
            }
        }
        ControlSettingsAsset settings = ControlSettingsConfig.getInstance().settings();
        return new Snapshot(byBit, lands == 0 && shrugs == 0 ? ControlRule.NONE : new ControlRule(lands, shrugs),
                capped, settings.rampAmount(), settings.fixedSkipsImmunity(), settings.fixedSkipsResist(),
                settings.fixedSkipsAddedSeconds());
    }
}
