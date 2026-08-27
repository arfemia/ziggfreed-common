package com.ziggfreed.common.loot.stamp;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.factor.FactorFormula;

/**
 * A whole stamp, as an author writes it: where the candidate outcomes come from, how many are
 * drawn, and what holds the result down.
 *
 * <pre>{@code
 * "Stamp": {
 *   "Pool": "weaponstats",
 *   "Picks": { "Min": 1, "Max": 2 },
 *   "Unique": true,
 *   "Caps": {
 *     "Budgets": [ { "Points": 40 },
 *                  { "PointsPer": 4, "Factors": [ { "Factor": "hytale:tool_quality" } ] } ],
 *     "PerStat": { "Damage": 12 }
 *   }
 * }
 * }</pre>
 *
 * <h2>Where the outcomes come from</h2>
 *
 * <p>{@code Pool} names a shared {@link RollPoolAsset}; {@code Entries} writes outcomes here
 * directly. Author either, or both - the pool's entries come first, then the inline ones, and the
 * engine never cares which route an entry arrived by. Use the pool when other things stamp the same
 * outcomes, and inline entries for the ones that belong to this site alone.
 *
 * <h2>Two budgets, doing different jobs</h2>
 *
 * <p>{@code Budgets} is the TOTAL point ceiling for the item, and the effective one is the LOWEST of
 * every entry you write - never their sum, never the largest. That is what lets a flat hard ceiling
 * and a factor-scaled one coexist: write {@code {"Points": 40}} as the absolute maximum this item
 * may ever carry, and {@code {"PointsPer": 4, "Factors": [...]}} as what the CURRENT attempt has
 * earned, and the attempt is held by whichever is tighter right now.
 *
 * <p>{@code PerStat} is separate and additional: a ceiling on ONE stat id no matter how much total
 * budget is left, so a lucky streak cannot pile everything into damage.
 *
 * <p>Both are measured against what the item ALREADY carries, so budgets survive re-stamping: an
 * item at 38 of a 40 budget has two points left, not forty.
 */
public final class StampSpec {

    @Nullable protected String pool;
    @Nullable protected StatRollEntry[] entries;
    @Nullable protected Picks picks;
    @Nullable protected Boolean unique;
    @Nullable protected Caps caps;

    /** The plain codec: a factor id stays a free text field. */
    public static final BuilderCodec<StampSpec> CODEC = codec(null);

    /** The factory form, so a consumer can offer its own factor pick list throughout the group. */
    @Nonnull
    public static BuilderCodec<StampSpec> codec(@Nullable String editorDropdownDataSetId) {
        return BuilderCodec.builder(StampSpec.class, StampSpec::new)
                .appendInherited(new KeyedCodec<>("Pool", RollPoolAsset.CHILD_ASSET_CODEC, false),
                        (o, v) -> o.pool = v, o -> o.pool, (o, p) -> o.pool = p.pool)
                .documentation("A shared RollPool id to draw candidate outcomes from. May instead be a whole pool "
                        + "written inline. Combine it with Entries below to add site-specific outcomes.").add()
                .appendInherited(new KeyedCodec<>("Entries",
                                new ArrayCodec<>(StatRollEntry.codec(editorDropdownDataSetId),
                                        StatRollEntry[]::new), false),
                        (o, v) -> o.entries = v, o -> o.entries, (o, p) -> o.entries = p.entries)
                .documentation("Candidate outcomes written here directly, evaluated alongside any pool's.").add()
                .appendInherited(new KeyedCodec<>("Picks", Picks.CODEC, false),
                        (o, v) -> o.picks = v, o -> o.picks, (o, p) -> o.picks = p.picks)
                .documentation("How many entries the lottery draws. Omit for none, which leaves only the "
                        + "Always entries - a deliberate default, so a spec with no Picks is fully predictable.").add()
                .appendInherited(new KeyedCodec<>("Unique", Codec.BOOLEAN, false),
                        (o, v) -> o.unique = v, o -> o.unique, (o, p) -> o.unique = p.unique)
                .documentation("When true the same stat is never drawn twice in one stamp, so several picks mean "
                        + "several different stats. Omit to let a lucky stat come up twice and stack.").add()
                .appendInherited(new KeyedCodec<>("Caps", Caps.codec(editorDropdownDataSetId), false),
                        (o, v) -> o.caps = v, o -> o.caps, (o, p) -> o.caps = p.caps)
                .documentation("What holds the result down, measured against what the item already carries.").add()
                .build();
    }

    public StampSpec() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static StampSpec of(@Nullable String pool, @Nullable StatRollEntry[] entries,
            @Nullable Picks picks, @Nullable Boolean unique, @Nullable Caps caps) {
        StampSpec s = new StampSpec();
        s.pool = pool;
        s.entries = entries;
        s.picks = picks;
        s.unique = unique;
        s.caps = caps;
        return s;
    }

    @Nullable
    public String getPool() {
        return pool;
    }

    @Nullable
    public StatRollEntry[] getEntries() {
        return entries;
    }

    @Nullable
    public Picks getPicks() {
        return picks;
    }

    /** {@link #unique}, reader-defaulted to false (a stat may be drawn twice and stack). */
    public boolean isUnique() {
        return unique != null && unique;
    }

    @Nullable
    public Caps getCaps() {
        return caps;
    }

    // ==================== Picks ====================

    /** How many entries the lottery draws, as a range: {@code Min == Max} is a fixed count. */
    public static final class Picks {

        @Nullable protected Integer min;
        @Nullable protected Integer max;

        public static final BuilderCodec<Picks> CODEC = BuilderCodec.builder(Picks.class, Picks::new)
                .appendInherited(new KeyedCodec<>("Min", Codec.INTEGER, false),
                        (o, v) -> o.min = v, o -> o.min, (o, p) -> o.min = p.min)
                .metadata(EditorSchema.defaultValue(1))
                .documentation("The fewest entries drawn. Omit for 1.").add()
                .appendInherited(new KeyedCodec<>("Max", Codec.INTEGER, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .documentation("The most entries drawn. Omit (or match Min) for a fixed count.").add()
                .build();

        public Picks() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Picks of(@Nullable Integer min, @Nullable Integer max) {
            Picks p = new Picks();
            p.min = min;
            p.max = max;
            return p;
        }

        @Nullable
        public Integer getMin() {
            return min;
        }

        @Nullable
        public Integer getMax() {
            return max;
        }

        /** {@link #min}, reader-defaulted to 1 when absent or not positive. */
        public int effectiveMin() {
            return min != null && min > 0 ? min : 1;
        }

        /** {@link #max}, reader-defaulted to {@link #effectiveMin()} when absent or below it. */
        public int effectiveMax() {
            int lo = effectiveMin();
            return max != null && max >= lo ? max : lo;
        }
    }

    // ==================== Caps ====================

    /** The two independent ceilings: a total point {@code Budgets} list and a {@code PerStat} map. */
    public static final class Caps {

        @Nullable protected Budget[] budgets;
        @Nullable protected Map<String, Double> perStat;

        /** The plain codec: a factor id stays a free text field. */
        public static final BuilderCodec<Caps> CODEC = codec(null);

        /** The factory form, so a consumer can offer its own factor pick list on budget terms. */
        @Nonnull
        public static BuilderCodec<Caps> codec(@Nullable String editorDropdownDataSetId) {
            return BuilderCodec.builder(Caps.class, Caps::new)
                    .appendInherited(new KeyedCodec<>("Budgets",
                                    new ArrayCodec<>(Budget.codec(editorDropdownDataSetId), Budget[]::new), false),
                            (o, v) -> o.budgets = v, o -> o.budgets, (o, p) -> o.budgets = p.budgets)
                    .documentation("Total-point ceilings. The one that binds is the LOWEST of them, so a flat hard "
                            + "maximum and a factor-scaled earned allowance can sit side by side. Omit for no "
                            + "total ceiling at all.").add()
                    .appendInherited(new KeyedCodec<>("PerStat",
                                    new MapCodec<>(Codec.DOUBLE, LinkedHashMap::new), false),
                            (o, v) -> o.perStat = v, o -> o.perStat, (o, p) -> o.perStat = p.perStat)
                    .documentation("A ceiling on one stat id, on top of the total. Author it to stop a lucky run "
                            + "piling every point into the same stat.").add()
                    .build();
        }

        public Caps() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Caps of(@Nullable Budget[] budgets, @Nullable Map<String, Double> perStat) {
            Caps c = new Caps();
            c.budgets = budgets;
            c.perStat = perStat;
            return c;
        }

        @Nullable
        public Budget[] getBudgets() {
            return budgets;
        }

        @Nullable
        public Map<String, Double> getPerStat() {
            return perStat;
        }

        /** {@link #perStat} or an empty map, for the hot path. */
        @Nonnull
        public Map<String, Double> perStatOrEmpty() {
            return perStat == null ? Map.of() : perStat;
        }
    }

    // ==================== Budget ====================

    /**
     * ONE total-point ceiling, written EITHER as a flat {@code Points} number OR as
     * {@code PointsPer} multiplied by a summed factor reading.
     *
     * <p>Write exactly one of the two. Writing both, or neither, means the entry says nothing
     * measurable and it is skipped - the validator reports it rather than the engine guessing.
     */
    public static final class Budget {

        @Nullable protected Double points;
        @Nullable protected Double pointsPer;
        @Nullable protected FactorFormula.Term[] factors;

        /** The plain codec: a factor id stays a free text field. */
        public static final BuilderCodec<Budget> CODEC = codec(null);

        /** The factory form, so a consumer can offer its own factor pick list on the terms. */
        @Nonnull
        public static BuilderCodec<Budget> codec(@Nullable String editorDropdownDataSetId) {
            return BuilderCodec.builder(Budget.class, Budget::new)
                    .appendInherited(new KeyedCodec<>("Points", Codec.DOUBLE, false),
                            (o, v) -> o.points = v, o -> o.points, (o, p) -> o.points = p.points)
                    .documentation("A flat ceiling. Write this INSTEAD of PointsPer, not beside it.").add()
                    .appendInherited(new KeyedCodec<>("PointsPer", Codec.DOUBLE, false),
                            (o, v) -> o.pointsPer = v, o -> o.pointsPer, (o, p) -> o.pointsPer = p.pointsPer)
                    .documentation("How much ceiling each point of the summed Factors is worth. Write this with "
                            + "Factors INSTEAD of a flat Points.").add()
                    .appendInherited(new KeyedCodec<>("Factors",
                                    new ArrayCodec<>(FactorFormula.Term.codec(editorDropdownDataSetId),
                                            FactorFormula.Term[]::new), false),
                            (o, v) -> o.factors = v, o -> o.factors, (o, p) -> o.factors = p.factors)
                    .documentation("The readings summed and multiplied by PointsPer. A reading nobody can answer "
                            + "adds 0, which tightens this ceiling rather than removing it.").add()
                    .build();
        }

        public Budget() {
        }

        /** A flat ceiling; the Java-side twin of authoring only {@code Points}. */
        @Nonnull
        public static Budget flat(@Nullable Double points) {
            Budget b = new Budget();
            b.points = points;
            return b;
        }

        /** A factor-scaled ceiling; the Java-side twin of authoring {@code PointsPer} plus terms. */
        @Nonnull
        public static Budget scaled(@Nullable Double pointsPer, @Nullable FactorFormula.Term[] factors) {
            Budget b = new Budget();
            b.pointsPer = pointsPer;
            b.factors = factors;
            return b;
        }

        @Nullable
        public Double getPoints() {
            return points;
        }

        @Nullable
        public Double getPointsPer() {
            return pointsPer;
        }

        @Nullable
        public FactorFormula.Term[] getFactors() {
            return factors;
        }

        /** True when only the flat route is authored. */
        public boolean isFlat() {
            return points != null && pointsPer == null;
        }

        /** True when only the factor-scaled route is authored. */
        public boolean isFactorScaled() {
            return pointsPer != null && points == null;
        }

        /** True when EXACTLY one route is authored - what a usable entry has to be. */
        public boolean hasExactlyOneRoute() {
            return isFlat() ^ isFactorScaled();
        }
    }
}
