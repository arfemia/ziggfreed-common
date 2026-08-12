package com.ziggfreed.common.loot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;

/**
 * ONE conditional payout: a gate ({@code Conditions} then {@code Chance}), a payoff
 * ({@code Grants}, plus whatever {@code Ladder} floor the moment reached), and an optional
 * celebration ({@code Cue}).
 *
 * <pre>{@code
 * {
 *   "Trigger": "Cycle",
 *   "Conditions": [ { "Factor": "hytale:tool_quality", "Min": 2 } ],
 *   "Chance":  { "Base": 0, "Factors": [ { "Factor": "mymod:fortune", "Weight": 5 } ],
 *                "Clamp": { "Max": 90 } },
 *   "Ladder":  { "Factors": [ { "Factor": "mymod:fortune" } ],
 *                "Floors": [ { "Min": 50,  "Grants": { "DropLists": ["Finds_T1"] } },
 *                            { "Min": 100, "Grants": { "DropLists": ["Finds_T2"] }, "Cue": "jackpot" } ] },
 *   "Grants":  { "Items": [ { "Item": "Coin_Gold", "Count": 1 } ] },
 *   "Cue": "rare_find"
 * }
 * }</pre>
 *
 * <h2>How one roll is read, in order</h2>
 * <ol>
 *   <li><b>Conditions</b> - every entry must pass, and an unanswerable factor shuts the gate. No
 *       conditions means no gate.</li>
 *   <li><b>Chance</b> - a percentage, rolled once. It gates the WHOLE roll including the ladder, so a
 *       failed chance means the ladder is never even looked at. No chance authored means it always
 *       fires.</li>
 *   <li><b>Grants</b> - the top-level payout, handed over whenever the two gates passed.</li>
 *   <li><b>Ladder</b> - the floors are climbed with a summed factor value and the HIGHEST reached one
 *       pays out TOO. Top-level and floor grants STACK; they are not alternatives.</li>
 * </ol>
 *
 * <h2>Chance is in percent, and always held inside 0..100</h2>
 *
 * <p>{@code Chance} is the shared {@code {Base, Factors, Clamp}} formula read as a PERCENTAGE:
 * {@code Base} is the flat chance before any factor, each term adds its weighted reading, and the
 * result is held inside {@code Clamp} and then inside {@code 0..100} regardless. Author
 * {@code Clamp.Max} for the ceiling a stacking bonus may never push past (a 90 there is the usual
 * "leave a little to luck"), and {@code Base} for the chance the roll has with no bonuses at all.
 *
 * <h2>The ladder is deliberately uncapped</h2>
 *
 * <p>Floor thresholds are compared against the RAW summed value with no ceiling, so a floor above a
 * factor's normal range stays reachable by stacking several sources. A floor's {@code Min} omitted
 * reads as 0, and a 0 floor IS reachable - that is how a baseline tier is authored. Two floors
 * sharing a {@code Min} resolve to the LAST one written, matching every other later-wins rule.
 *
 * <h2>The cue never celebrates over nothing</h2>
 *
 * <p>{@code Cue} is an opaque id the granting site maps to its own sound, particle, or toast: this
 * layer never plays anything. It is judged against the grants group beside it. A cue with NO grants
 * authored next to it is pure presentation and rides on the plain hit (or the plain floor reach). A
 * cue authored BESIDE grants rides only when applying those grants genuinely produced something - so
 * a drop table whose own weights rolled empty stays silent instead of firing a fanfare over an empty
 * hand. The roll's cue is judged against the roll's own {@code Grants}, a floor's cue against that
 * floor's own, and both can play.
 */
public final class Roll {

    /** The trigger a roll authoring none is read with: it fires on whatever the site's plain moment is. */
    public static final String DEFAULT_TRIGGER = "Default";

    /** The lowest chance that can ever fire, and the highest, in percent. */
    public static final double MIN_CHANCE_PERCENT = 0.0;
    public static final double MAX_CHANCE_PERCENT = 100.0;

    @Nullable protected String trigger;
    @Nullable protected FactorCondition[] conditions;
    @Nullable protected FactorFormula chance;
    @Nullable protected Ladder ladder;
    @Nullable protected LootGrants grants;
    @Nullable protected String cue;

    /** The plain codec: a factor id stays a free text field. */
    public static final BuilderCodec<Roll> CODEC = codec(null);

    /**
     * A codec whose factor fields offer the Asset Editor pick list served under
     * {@code editorDropdownDataSetId}; {@code null}/blank builds the plain free-text form.
     */
    @Nonnull
    public static BuilderCodec<Roll> codec(@Nullable String editorDropdownDataSetId) {
        return BuilderCodec.builder(Roll.class, Roll::new)
                .appendInherited(new KeyedCodec<>("Trigger", Codec.STRING, false),
                        (o, v) -> o.trigger = v, o -> o.trigger, (o, p) -> o.trigger = p.trigger)
                .documentation("Which moment this roll answers to. The granting site names the moments it "
                        + "offers; omit to fire on that site's plain default moment.").add()
                .appendInherited(new KeyedCodec<>("Conditions",
                                new ArrayCodec<>(FactorCondition.codec(editorDropdownDataSetId),
                                        FactorCondition[]::new), false),
                        (o, v) -> o.conditions = v, o -> o.conditions, (o, p) -> o.conditions = p.conditions)
                .documentation("Every entry must pass before the roll is considered. A factor nobody can answer "
                        + "shuts the gate, so a roll gated on an absent mod stays hidden.").add()
                .appendInherited(new KeyedCodec<>("Chance",
                                FactorFormula.codec(editorDropdownDataSetId), false),
                        (o, v) -> o.chance = v, o -> o.chance, (o, p) -> o.chance = p.chance)
                .documentation("The percentage chance the whole roll fires, rolled once. Base is the flat chance "
                        + "with no bonuses; each factor term adds its weighted reading; Clamp.Max is the ceiling "
                        + "a stacking bonus may never pass. Omit the whole group for a roll that always fires.").add()
                .appendInherited(new KeyedCodec<>("Ladder", Ladder.codec(editorDropdownDataSetId), false),
                        (o, v) -> o.ladder = v, o -> o.ladder, (o, p) -> o.ladder = p.ladder)
                .documentation("Tiers over a summed factor value; the highest reached floor pays out ON TOP of "
                        + "the top-level Grants.").add()
                .appendInherited(new KeyedCodec<>("Grants", LootGrants.CODEC, false),
                        (o, v) -> o.grants = v, o -> o.grants, (o, p) -> o.grants = p.grants)
                .documentation("What this roll hands over whenever its gates passed, whether or not a ladder "
                        + "floor was also reached.").add()
                .appendInherited(new KeyedCodec<>("Cue", Codec.STRING, false),
                        (o, v) -> o.cue = v, o -> o.cue, (o, p) -> o.cue = p.cue)
                .documentation("An opaque celebration id the granting site plays (a sound, a toast). With no "
                        + "Grants beside it, it always plays on the hit; with Grants beside it, only once they "
                        + "actually produced something.").add()
                .build();
    }

    public Roll() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static Roll of(@Nullable String trigger, @Nullable FactorCondition[] conditions,
            @Nullable FactorFormula chance, @Nullable Ladder ladder, @Nullable LootGrants grants,
            @Nullable String cue) {
        Roll r = new Roll();
        r.trigger = trigger;
        r.conditions = conditions;
        r.chance = chance;
        r.ladder = ladder;
        r.grants = grants;
        r.cue = cue;
        return r;
    }

    @Nullable
    public String getTrigger() {
        return trigger;
    }

    @Nullable
    public FactorCondition[] getConditions() {
        return conditions;
    }

    @Nullable
    public FactorFormula getChance() {
        return chance;
    }

    @Nullable
    public Ladder getLadder() {
        return ladder;
    }

    @Nullable
    public LootGrants getGrants() {
        return grants;
    }

    /** The opaque celebration id, judged against {@link #getGrants()} by the smart-cue rule. */
    @Nullable
    public String getCue() {
        return cue;
    }

    /** {@link #getTrigger()}, reader-defaulted to {@link #DEFAULT_TRIGGER} when absent or blank. */
    @Nonnull
    public String effectiveTrigger() {
        return trigger == null || trigger.isBlank() ? DEFAULT_TRIGGER : trigger;
    }

    /**
     * True when this roll answers to {@code trigger} (case-insensitive). A {@code null} trigger asks
     * for EVERY roll, which is what a site with only one moment passes.
     */
    public boolean answersTo(@Nullable String trigger) {
        return trigger == null || trigger.equalsIgnoreCase(effectiveTrigger());
    }

    // ==================== Ladder ====================

    /**
     * Tiers over a summed factor value: {@code Factors} are added up (each with its own weight) and
     * the highest {@code Floors} entry that value reaches pays out.
     *
     * <p>The sum is a bare weighted term list rather than a full formula on purpose - a ladder has no
     * base to stand on and no ceiling to hold it, so a {@code Base}/{@code Clamp} pair here would be
     * two knobs that never do anything. An absent or empty list resolves to 0, which still reaches a
     * {@code Min: 0} floor: that is how a constant tier is authored.
     */
    public static final class Ladder {

        @Nullable protected FactorFormula.Term[] factors;
        @Nullable protected Floor[] floors;

        /** The plain codec: a factor id stays a free text field. */
        public static final BuilderCodec<Ladder> CODEC = codec(null);

        /** The factory form, so every level of the group is built with the same dropdown dataset. */
        @Nonnull
        public static BuilderCodec<Ladder> codec(@Nullable String editorDropdownDataSetId) {
            return BuilderCodec.builder(Ladder.class, Ladder::new)
                    .appendInherited(new KeyedCodec<>("Factors",
                                    new ArrayCodec<>(FactorFormula.Term.codec(editorDropdownDataSetId),
                                            FactorFormula.Term[]::new), false),
                            (o, v) -> o.factors = v, o -> o.factors, (o, p) -> o.factors = p.factors)
                    .documentation("The readings summed into the ladder value before the floor lookup. One entry "
                            + "is the common case; several let a tier be earned from more than one source. Omit "
                            + "for a value of 0, which still reaches a Min 0 floor.").add()
                    .appendInherited(new KeyedCodec<>("Floors",
                                    new ArrayCodec<>(Floor.CODEC, Floor[]::new), false),
                            (o, v) -> o.floors = v, o -> o.floors, (o, p) -> o.floors = p.floors)
                    .documentation("The tiers. The HIGHEST floor whose Min the summed value reaches pays out, and "
                            + "only that one - floors are not cumulative.").add()
                    .build();
        }

        public Ladder() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Ladder of(@Nullable FactorFormula.Term[] factors, @Nullable Floor[] floors) {
            Ladder l = new Ladder();
            l.factors = factors;
            l.floors = floors;
            return l;
        }

        @Nullable
        public FactorFormula.Term[] getFactors() {
            return factors;
        }

        @Nullable
        public Floor[] getFloors() {
            return floors;
        }

        /** ONE tier: {@code {Min, Grants, Cue}}. Its grants are its only payout path. */
        public static final class Floor {

            @Nullable protected Double min;
            @Nullable protected LootGrants grants;
            @Nullable protected String cue;

            public static final BuilderCodec<Floor> CODEC = BuilderCodec.builder(Floor.class, Floor::new)
                    .appendInherited(new KeyedCodec<>("Min", Codec.DOUBLE, false),
                            (o, v) -> o.min = v, o -> o.min, (o, p) -> o.min = p.min)
                    .documentation("The summed value this tier needs, inclusive. Omit for 0, and a 0 tier IS "
                            + "reachable - author one as the baseline everybody gets.").add()
                    .appendInherited(new KeyedCodec<>("Grants", LootGrants.CODEC, false),
                            (o, v) -> o.grants = v, o -> o.grants, (o, p) -> o.grants = p.grants)
                    .documentation("What reaching this tier hands over, on top of the roll's own Grants.").add()
                    .appendInherited(new KeyedCodec<>("Cue", Codec.STRING, false),
                            (o, v) -> o.cue = v, o -> o.cue, (o, p) -> o.cue = p.cue)
                    .documentation("An opaque celebration id for reaching this tier, judged against THIS floor's "
                            + "own Grants exactly as the roll-level cue is judged against the roll's.").add()
                    .build();

            public Floor() {
            }

            /** Java-side factory; sets the same fields the codec fills. */
            @Nonnull
            public static Floor of(@Nullable Double min, @Nullable LootGrants grants, @Nullable String cue) {
                Floor f = new Floor();
                f.min = min;
                f.grants = grants;
                f.cue = cue;
                return f;
            }

            @Nullable
            public Double getMin() {
                return min;
            }

            @Nullable
            public LootGrants getGrants() {
                return grants;
            }

            @Nullable
            public String getCue() {
                return cue;
            }

            /** {@link #getMin()}, reader-defaulted to 0 when absent or not a finite number. */
            public double effectiveMin() {
                return min != null && Double.isFinite(min) ? min : 0.0;
            }
        }
    }
}
