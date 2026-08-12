package com.ziggfreed.common.factor;

import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;

/**
 * The ONE authored VALUE leaf over the factor vocabulary, the arithmetic twin of
 * {@link FactorCondition}: a base number plus a weighted sum of factor readings, optionally
 * clamped.
 *
 * <pre>{@code
 * "Formula": {
 *   "Base": 1.0,
 *   "Factors": [ {"Factor": "hytale:tool_quality", "Weight": 0.25},
 *                {"Factor": "yourmod:reputation", "Param": "guild", "Weight": 0.1} ],
 *   "Clamp": {"Min": 1.0, "Max": 4.0}
 * }
 * }</pre>
 *
 * <h2>A gate fails closed; a formula degrades gracefully. The asymmetry is deliberate.</h2>
 *
 * <p>A {@link FactorCondition} that cannot resolve its factor FAILS - content gated on a mod that
 * is not installed must stay hidden rather than spring open. A formula term that cannot resolve
 * contributes <b>0</b> instead, and the surrounding sum still produces a number.
 *
 * <p>The two rules answer different questions and each is the safe answer to its own. A gate asks
 * "may this appear at all?", where the only conservative answer to "I cannot tell" is no. A formula
 * asks "how much?", where refusing to answer would mean a missing optional bonus takes the whole
 * value with it: one uninstalled mod contributing an optional {@code +0.25} term would otherwise
 * blank a speed multiplier, a reward count, or a price, and the content built on it would break
 * everywhere rather than merely lose that bonus. A term is an ADDEND, and the neutral addend is
 * zero. Author {@code Base} for the value the formula must have when nothing else resolves, and
 * {@code Clamp.Min} for the floor it may never fall through.
 *
 * <p>If a value genuinely must not be produced at all without some factor, that is a GATE, and it
 * belongs in the surrounding content's {@code Conditions} where the fail-closed rule applies -
 * never in a formula term.
 *
 * <p>Everything else here follows the vocabulary's existing rules: a non-finite provider answer is
 * unresolvable (so it contributes 0, mirroring {@link FactorRegistry}'s own guard), a term with no
 * factor id is skipped the way a blank {@link FactorCondition} is, and each term is resolved with
 * its OWN {@code Param} so one factor id can be read several ways in one formula.
 *
 * <p><b>The codec is a FACTORY</b>, {@link #codec(String)}, for the same reason
 * {@link FactorCondition}'s is: the Asset Editor pick list on a term's {@code Factor} field is the
 * consuming mod's own, and an unserved dataset id renders an EMPTY list, which is worse for an
 * author than plain free text. {@link #CODEC} is the no-dropdown instance. Every leaf at every
 * level is registered with {@code appendInherited}, so a file carrying {@code "Parent": "<id>"} and
 * overriding only {@code Clamp} keeps the inherited {@code Base} and {@code Factors}.
 */
public final class FactorFormula {

    /** The neutral weight a term that authors none is read with. */
    public static final double DEFAULT_WEIGHT = 1.0;

    // ==================== Term ====================

    /**
     * One addend: {@code Weight} times whatever {@code Factor} resolves to for this term's own
     * {@code Param}. An unresolvable factor contributes 0 (see the class javadoc for why), and a
     * term with no factor id at all is skipped entirely.
     */
    public static final class Term {

        @Nullable protected String factor;
        @Nullable protected String param;
        @Nullable protected Double weight;

        /** The plain codec: {@code Factor} stays a free text field. */
        public static final BuilderCodec<Term> CODEC = codec(null);

        /**
         * A codec whose {@code Factor} field offers the Asset Editor pick list served under
         * {@code editorDropdownDataSetId}; {@code null}/blank builds the plain free-text form.
         */
        @Nonnull
        public static BuilderCodec<Term> codec(@Nullable String editorDropdownDataSetId) {
            BuilderCodec.Builder<Term> builder = BuilderCodec.builder(Term.class, Term::new);

            var factorField = builder
                    .appendInherited(new KeyedCodec<>("Factor", Codec.STRING, false),
                            (o, v) -> o.factor = v, o -> o.factor, (o, p) -> o.factor = p.factor)
                    .documentation("The namespaced factor id to read. An id nobody can answer contributes 0 to "
                            + "the sum rather than voiding it, so an optional bonus stays optional.");
            if (editorDropdownDataSetId != null && !editorDropdownDataSetId.isBlank()) {
                factorField = factorField.metadata(new UIEditor(new UIEditor.Dropdown(editorDropdownDataSetId)));
            }

            return factorField.add()
                    .appendInherited(new KeyedCodec<>("Param", Codec.STRING, false),
                            (o, v) -> o.param = v, o -> o.param, (o, p) -> o.param = p.param)
                    .documentation("Optional provider-interpreted argument, opaque here - whatever the factor's "
                            + "own owner documents (a stat id, an item id, a tag). Each term is read with its "
                            + "own Param, so one factor id can appear twice with different arguments.").add()
                    .appendInherited(new KeyedCodec<>("Weight", Codec.DOUBLE, false),
                            (o, v) -> o.weight = v, o -> o.weight, (o, p) -> o.weight = p.weight)
                    .documentation("What one point of this factor is worth in the result. Omit for 1.0; author a "
                            + "fraction to make a factor a nudge, or a negative number to make it a penalty.").add()
                    .build();
        }

        public Term() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Term of(@Nullable String factor, @Nullable String param, @Nullable Double weight) {
            Term t = new Term();
            t.factor = factor;
            t.param = param;
            t.weight = weight;
            return t;
        }

        @Nullable
        public String getFactor() {
            return factor;
        }

        @Nullable
        public String getParam() {
            return param;
        }

        /** The authored weight, or null when none was authored (read {@link #weightOrDefault()}). */
        @Nullable
        public Double getWeight() {
            return weight;
        }

        /**
         * The weight this term actually multiplies by: the authored one when it is a finite number,
         * else {@link #DEFAULT_WEIGHT}. A non-finite authored weight would poison the whole sum, so
         * it is read as unauthored here and reported by {@code DerivedFactorValidator}.
         */
        public double weightOrDefault() {
            return weight != null && Double.isFinite(weight) ? weight : DEFAULT_WEIGHT;
        }

        /** True when no factor id is authored, so this term can never be read. */
        public boolean isBlank() {
            return factor == null || factor.isBlank();
        }
    }

    // ==================== Clamp ====================

    /**
     * The inclusive bounds the summed result is held between. {@code Min} and {@code Max} are
     * independently optional, so a formula can carry a floor without inventing a ceiling.
     */
    public static final class Clamp {

        @Nullable protected Double min;
        @Nullable protected Double max;

        /** The plain codec. */
        public static final BuilderCodec<Clamp> CODEC = codec(null);

        /**
         * The factory form, so the whole group is built the same way at every level. This type has
         * no factor-id field, so {@code editorDropdownDataSetId} has nothing to attach to and is
         * accepted only to keep {@link FactorFormula#codec(String)} uniform.
         */
        @Nonnull
        public static BuilderCodec<Clamp> codec(@Nullable String editorDropdownDataSetId) {
            return BuilderCodec.builder(Clamp.class, Clamp::new)
                    .appendInherited(new KeyedCodec<>("Min", Codec.DOUBLE, false),
                            (o, v) -> o.min = v, o -> o.min, (o, p) -> o.min = p.min)
                    .documentation("Inclusive floor: a result below this is raised to it. Omit for no floor.").add()
                    .appendInherited(new KeyedCodec<>("Max", Codec.DOUBLE, false),
                            (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                    .documentation("Inclusive ceiling: a result above this is lowered to it. Omit for no ceiling.").add()
                    .build();
        }

        public Clamp() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Clamp of(@Nullable Double min, @Nullable Double max) {
            Clamp c = new Clamp();
            c.min = min;
            c.max = max;
            return c;
        }

        @Nullable
        public Double getMin() {
            return min;
        }

        @Nullable
        public Double getMax() {
            return max;
        }

        /** True when both bounds are authored and the floor sits above the ceiling. */
        public boolean isInverted() {
            return min != null && max != null && Double.isFinite(min) && Double.isFinite(max) && min > max;
        }

        /**
         * {@code value} held inside the authored bounds. A non-finite bound is ignored rather than
         * applied, so a mis-authored number cannot turn a working result into a NaN.
         */
        public double apply(double value) {
            double out = value;
            if (min != null && Double.isFinite(min) && out < min) {
                out = min;
            }
            if (max != null && Double.isFinite(max) && out > max) {
                out = max;
            }
            return out;
        }
    }

    // ==================== FactorFormula ====================

    @Nullable protected Double base;
    @Nullable protected Term[] factors;
    @Nullable protected Clamp clamp;

    /** The plain codec: a term's {@code Factor} stays a free text field. */
    public static final BuilderCodec<FactorFormula> CODEC = codec(null);

    /**
     * A codec whose terms offer the Asset Editor pick list served under
     * {@code editorDropdownDataSetId} on their {@code Factor} field; {@code null}/blank builds the
     * plain free-text form. Only name a dataset your own mod actually serves.
     */
    @Nonnull
    public static BuilderCodec<FactorFormula> codec(@Nullable String editorDropdownDataSetId) {
        return BuilderCodec.builder(FactorFormula.class, FactorFormula::new)
                .appendInherited(new KeyedCodec<>("Base", Codec.DOUBLE, false),
                        (o, v) -> o.base = v, o -> o.base, (o, p) -> o.base = p.base)
                .documentation("The value before any factor is added. Omit for 0. This is what the formula is "
                        + "worth when nothing else resolves, so author it whenever the result must never be 0.").add()
                .appendInherited(new KeyedCodec<>("Factors",
                                new ArrayCodec<>(Term.codec(editorDropdownDataSetId), Term[]::new), false),
                        (o, v) -> o.factors = v, o -> o.factors, (o, p) -> o.factors = p.factors)
                .documentation("The weighted readings added to Base. A term whose factor cannot be answered "
                        + "contributes 0, so an optional bonus from an absent mod costs only that bonus.").add()
                .appendInherited(new KeyedCodec<>("Clamp", Clamp.codec(editorDropdownDataSetId), false),
                        (o, v) -> o.clamp = v, o -> o.clamp, (o, p) -> o.clamp = p.clamp)
                .documentation("Inclusive bounds applied to the finished sum. Author Min as the floor the value "
                        + "may never fall through, whatever is missing.").add()
                .build();
    }

    public FactorFormula() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static FactorFormula of(@Nullable Double base, @Nullable Term[] factors, @Nullable Clamp clamp) {
        FactorFormula f = new FactorFormula();
        f.base = base;
        f.factors = factors == null ? null : factors.clone();
        f.clamp = clamp;
        return f;
    }

    /** The authored base, or null when none was authored (it reads as 0). */
    @Nullable
    public Double getBase() {
        return base;
    }

    /** The authored terms, defensively copied, or null when none were authored. */
    @Nullable
    public Term[] getFactors() {
        return factors == null ? null : factors.clone();
    }

    @Nullable
    public Clamp getClamp() {
        return clamp;
    }

    /** The authored terms without copying, for the hot evaluation path. */
    @Nonnull
    public Term[] termsOrEmpty() {
        return factors == null ? new Term[0] : factors;
    }

    /** The authored base, or 0 when none was authored or it is not a finite number. */
    public double baseOrZero() {
        return base != null && Double.isFinite(base) ? base : 0.0;
    }

    /**
     * True when NOTHING is authored: no base and no usable term, so this formula says nothing at
     * all. Distinct from an authored {@code "Base": 0}, which is a deliberate constant zero. A
     * consumer treats an empty formula as no definition (fails closed) rather than as a constant,
     * because an empty file is an authoring accident and must not quietly answer a gate.
     */
    public boolean isEmpty() {
        return base == null && hasNoTerms();
    }

    /**
     * True when this formula can only ever produce its base: no term carries a factor id. An
     * authoring slip rather than a runtime failure, so it is a validator finding, not a throw.
     */
    public boolean hasNoTerms() {
        for (Term term : termsOrEmpty()) {
            if (term != null && !term.isBlank()) {
                return false;
            }
        }
        return true;
    }

    // ==================== Evaluation ====================

    /**
     * The finished value: {@link #baseOrZero()} plus every term's weighted reading, clamped when
     * {@code Clamp} is authored. Each term is resolved against {@code ctx} re-scoped to that term's
     * own {@code Param}, so a formula and a condition addressing one factor id read it identically.
     *
     * <p>Always a finite number, and never throws - an unresolvable term contributes 0 (class
     * javadoc), and {@code registry.resolve} already swallows a throwing provider.
     */
    public double evaluate(@Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        return evaluate(lookupThrough(registry, ctx));
    }

    /**
     * As {@link #evaluate(FactorRegistry, FactorContext)} but reading through a caller-supplied
     * {@code (factorId, param) -> value} lookup, for an engine that already threads its own
     * resolution function rather than a {@link FactorRegistry}. A {@code null} (or non-finite)
     * lookup result contributes 0, exactly as an unresolvable registry read does.
     */
    public double evaluate(@Nonnull BiFunction<String, String, Double> lookup) {
        double value = baseOrZero() + sum(factors, lookup);
        return clamp == null ? value : clamp.apply(value);
    }

    /**
     * Only the weighted term sum: no {@code Base}, no {@code Clamp}. For a caller composing this
     * formula's contribution into a larger expression of its own.
     */
    public double rawSum(@Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        return sum(factors, lookupThrough(registry, ctx));
    }

    /**
     * The pure weighted sum of {@code terms} through an arbitrary {@code (factorId, param) -> value}
     * lookup - the thread-through form, so an engine that resolves factors its own way can share
     * this arithmetic instead of re-deriving it.
     *
     * <p>A null/blank term, a term with no factor id, and a lookup answering {@code null} or a
     * non-finite number all contribute 0; a lookup that THROWS is treated the same way, since a
     * broken provider must not take the whole value with it.
     */
    public static double sum(@Nullable Term[] terms, @Nonnull BiFunction<String, String, Double> lookup) {
        if (terms == null || terms.length == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (Term term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            Double resolved;
            try {
                resolved = lookup.apply(term.getFactor(), term.getParam());
            } catch (Throwable t) {
                resolved = null;
            }
            if (resolved == null || !Double.isFinite(resolved)) {
                continue;
            }
            double contribution = resolved * term.weightOrDefault();
            if (Double.isFinite(contribution)) {
                total += contribution;
            }
        }
        return Double.isFinite(total) ? total : 0.0;
    }

    @Nonnull
    private static BiFunction<String, String, Double> lookupThrough(@Nonnull FactorRegistry registry,
            @Nonnull FactorContext ctx) {
        return (factorId, param) -> registry.resolve(factorId, ctx.withParam(param));
    }
}
