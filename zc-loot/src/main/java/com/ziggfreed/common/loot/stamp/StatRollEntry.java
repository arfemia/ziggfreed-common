package com.ziggfreed.common.loot.stamp;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.factor.FactorFormula;

/**
 * ONE candidate outcome of a stat roll: {@code {Stat, Points, Weight, Always}}.
 *
 * <pre>{@code
 * { "Stat": "Damage", "Points": { "Min": 2, "Max": 6 }, "Weight": 3 }
 * { "Stat": "Durability", "Points": { "Min": 1, "Max": 1 }, "Always": true }
 * }</pre>
 *
 * <p>{@code Weight} and {@code Always} are independent, and between them they cover the three roll
 * shapes anyone actually authors. Weights alone give a pure lottery. {@code Always: true} on every
 * entry gives a fixed set that lands every time. Mixing them gives the common case: a guaranteed
 * baseline plus one or two lucky extras.
 *
 * <p>{@code Always} entries sit OUTSIDE the lottery entirely - they do not consume a pick and their
 * weight is never consulted. A weight of zero or less means an entry can never be drawn, which is a
 * legitimate way to park an entry without deleting it.
 *
 * <p>The stat id is opaque here: this engine never interprets what a stat means, it only rolls a
 * number for it and holds that number inside the budget. The registered stamper does the rest.
 */
public final class StatRollEntry {

    /** The weight an entry authoring none is drawn with. */
    public static final double DEFAULT_WEIGHT = 1.0;

    /** The point value an entry authoring no range rolls. */
    public static final double DEFAULT_POINTS = 1.0;

    @Nullable protected String stat;
    @Nullable protected Points points;
    @Nullable protected Double weight;
    @Nullable protected Boolean always;

    /** The plain codec: a factor id stays a free text field. */
    public static final BuilderCodec<StatRollEntry> CODEC = codec(null);

    /** The factory form, so a consumer can offer its own factor pick list on the Points terms. */
    @Nonnull
    public static BuilderCodec<StatRollEntry> codec(@Nullable String editorDropdownDataSetId) {
        return BuilderCodec.builder(StatRollEntry.class, StatRollEntry::new)
                .appendInherited(new KeyedCodec<>("Stat", Codec.STRING, false),
                        (o, v) -> o.stat = v, o -> o.stat, (o, p) -> o.stat = p.stat)
                .documentation("Which stat this entry awards points in. Opaque to the roll engine; whatever "
                        + "writes the stamp on this server decides what it does.").add()
                .appendInherited(new KeyedCodec<>("Points", Points.codec(editorDropdownDataSetId), false),
                        (o, v) -> o.points = v, o -> o.points, (o, p) -> o.points = p.points)
                .documentation("How many points a hit on this entry is worth. Omit for 1.").add()
                .appendInherited(new KeyedCodec<>("Weight", Codec.DOUBLE, false),
                        (o, v) -> o.weight = v, o -> o.weight, (o, p) -> o.weight = p.weight)
                .documentation("How likely this entry is relative to its siblings. Omit for 1; set 0 to park an "
                        + "entry so it is never drawn without deleting it.").add()
                .appendInherited(new KeyedCodec<>("Always", Codec.BOOLEAN, false),
                        (o, v) -> o.always = v, o -> o.always, (o, p) -> o.always = p.always)
                .documentation("When true this entry lands EVERY time, outside the lottery: it costs no pick and "
                        + "its Weight is ignored. Author it for the baseline every stamp should carry.").add()
                .build();
    }

    public StatRollEntry() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static StatRollEntry of(@Nullable String stat, @Nullable Points points, @Nullable Double weight,
            @Nullable Boolean always) {
        StatRollEntry e = new StatRollEntry();
        e.stat = stat;
        e.points = points;
        e.weight = weight;
        e.always = always;
        return e;
    }

    @Nullable
    public String getStat() {
        return stat;
    }

    @Nullable
    public Points getPoints() {
        return points;
    }

    @Nullable
    public Double getWeight() {
        return weight;
    }

    /**
     * The weight this entry is drawn with: {@link #DEFAULT_WEIGHT} when none was authored, otherwise
     * the authored number floored at 0.
     *
     * <p>An ABSENT weight and an authored {@code 0} deliberately mean different things. Absent means
     * "ordinary, same as its siblings"; a written 0 means "never drawn", which is what lets an author
     * park an entry without deleting it. Reading a written 0 as the default would silently do the
     * opposite of what the author asked for.
     */
    public double effectiveWeight() {
        if (weight == null || !Double.isFinite(weight)) {
            return DEFAULT_WEIGHT;
        }
        return Math.max(0.0, weight);
    }

    /** {@link #always}, reader-defaulted to false. */
    public boolean isAlways() {
        return always != null && always;
    }

    /** True when no stat id is authored, so this entry can never award anything. */
    public boolean isBlank() {
        return stat == null || stat.isBlank();
    }

    // ==================== Points ====================

    /**
     * The point value a hit rolls: somewhere in {@code [Min, Max]} inclusive, plus whatever
     * {@code Factors} adds on top.
     *
     * <p>{@code Min == Max} (or {@code Max} omitted) is a fixed value. {@code Factors} is a bare
     * weighted term list rather than a full formula because the base is already {@code Min}/{@code Max}
     * and the ceiling is already the stamp's budget - a second base and a second clamp here would be
     * knobs with nothing to do.
     */
    public static final class Points {

        @Nullable protected Double min;
        @Nullable protected Double max;
        @Nullable protected FactorFormula.Term[] factors;

        /** The plain codec: a factor id stays a free text field. */
        public static final BuilderCodec<Points> CODEC = codec(null);

        /** The factory form, so a consumer can offer its own factor pick list on the terms. */
        @Nonnull
        public static BuilderCodec<Points> codec(@Nullable String editorDropdownDataSetId) {
            return BuilderCodec.builder(Points.class, Points::new)
                    .appendInherited(new KeyedCodec<>("Min", Codec.DOUBLE, false),
                            (o, v) -> o.min = v, o -> o.min, (o, p) -> o.min = p.min)
                    .documentation("The lowest a hit can roll, inclusive. Omit for 1.").add()
                    .appendInherited(new KeyedCodec<>("Max", Codec.DOUBLE, false),
                            (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                    .documentation("The highest a hit can roll, inclusive. Omit (or match Min) for a fixed value.").add()
                    .appendInherited(new KeyedCodec<>("Factors",
                                    new ArrayCodec<>(FactorFormula.Term.codec(editorDropdownDataSetId),
                                            FactorFormula.Term[]::new), false),
                            (o, v) -> o.factors = v, o -> o.factors, (o, p) -> o.factors = p.factors)
                    .documentation("Weighted readings ADDED to the rolled value, so a better tool or a higher "
                            + "skill makes the same entry worth more. A reading nobody can answer adds 0.").add()
                    .build();
        }

        public Points() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Points of(@Nullable Double min, @Nullable Double max,
                @Nullable FactorFormula.Term[] factors) {
            Points p = new Points();
            p.min = min;
            p.max = max;
            p.factors = factors;
            return p;
        }

        @Nullable
        public Double getMin() {
            return min;
        }

        @Nullable
        public Double getMax() {
            return max;
        }

        @Nullable
        public FactorFormula.Term[] getFactors() {
            return factors;
        }

        /** {@link #min}, reader-defaulted to {@link #DEFAULT_POINTS} when absent or not finite. */
        public double effectiveMin() {
            return min != null && Double.isFinite(min) ? min : DEFAULT_POINTS;
        }

        /** {@link #max}, reader-defaulted to {@link #effectiveMin()} when absent or below it. */
        public double effectiveMax() {
            double lo = effectiveMin();
            return max != null && Double.isFinite(max) && max >= lo ? max : lo;
        }
    }
}
