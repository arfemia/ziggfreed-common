package com.ziggfreed.common.loot;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.progress.gate.GateSpec;

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
 * "leave a little to luck"), and {@code Base} for the chance the roll has with no bonuses at all. An optional
 * {@link Pity} group grows the chance for a player each Claim the roll misses ({@link Pity} says how).
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
    @Nullable protected Boolean expected;
    @Nullable protected Boolean hideInPreview;
    @Nullable protected Pity pity;

    /**
     * The table gates this roll carries, never authored: the live {@code Requires} of the table it was
     * resolved from, and of the file that contributed it ({@link #gatedBy}). Empty for an authored roll.
     */
    @Nonnull private GateSpec[] gates = NO_GATES;

    private static final GateSpec[] NO_GATES = new GateSpec[0];

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
                .appendInherited(new KeyedCodec<>("Expected", Codec.BOOLEAN, false),
                        (o, v) -> o.expected = v, o -> o.expected, (o, p) -> o.expected = p.expected)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True when what this roll hands over is the moment's EXPECTED payout, a wage or a "
                        + "return the player is owed, rather than a find: a granting site that tells the two apart "
                        + "shows an expected item as ordinary output and a find as a windfall. It changes nothing "
                        + "about what the roll pays or when; a Chance or a Ladder still applies. Default false, so "
                        + "every roll reads as a find unless it says otherwise.").add()
                .appendInherited(new KeyedCodec<>("HideInPreview", Codec.BOOLEAN, false),
                        (o, v) -> o.hideInPreview = v, o -> o.hideInPreview,
                        (o, p) -> o.hideInPreview = p.hideInPreview)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True keeps this roll, its ladder included, out of the list a table shows of what "
                        + "it pays (hovering a reward that pays the table): the list ends with one line saying a "
                        + "few more rare finds are left unsaid. It changes nothing about what the roll pays or "
                        + "when. Default false.").add()
                .appendInherited(new KeyedCodec<>("Pity", Pity.CODEC, false),
                        (o, v) -> o.pity = v, o -> o.pity, (o, p) -> o.pity = p.pity)
                .documentation("Bad-luck protection: each Claim a player is paid from this roll's table without "
                        + "the roll firing raises its Chance by Step for that player, up to Max, and the count "
                        + "starts over once it fires. Counted per player per Id, so several tables may share "
                        + "one count. Only a payout written as a Claim reads or counts it; a world drop or a "
                        + "bonus row rolls the plain Chance, and a table's preview shows the plain odds. Omit "
                        + "for a roll whose odds never grow.").add()
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

    /** The raw authored {@code Expected} knob; null = the roll reads as a find. */
    @Nullable
    public Boolean getExpected() {
        return expected;
    }

    /**
     * Whether what this roll hands over is the moment's EXPECTED payout (a wage, a return the
     * player is owed) rather than a find. Presentation only: a granting site that tells the two
     * apart shows an expected item as ordinary output and a find as a windfall, and the roll pays
     * exactly the same either way. Reader-defaulted to {@code false}.
     */
    public boolean isExpected() {
        return expected != null && expected;
    }

    /** Java-side setter for a roll built in code (mirrors the codec's {@code Expected} leaf). */
    @Nonnull
    public Roll withExpected(@Nullable Boolean expected) {
        this.expected = expected;
        return this;
    }

    /** The raw authored {@code HideInPreview} knob; null = the roll shows in a preview of its table. */
    @Nullable
    public Boolean getHideInPreview() {
        return hideInPreview;
    }

    /**
     * Whether a preview of what this roll's table pays leaves the roll out, and says instead that a few
     * more rare finds are left unsaid. Presentation only: the roll pays exactly the same either way.
     * Reader-defaulted to {@code false}.
     */
    public boolean hidesInPreview() {
        return hideInPreview != null && hideInPreview;
    }

    /** The authored {@code Pity} group, or null for a roll whose odds never grow. */
    @Nullable
    public Pity getPity() {
        return pity;
    }

    /** Java-side setter for a roll built in code (mirrors the codec's {@code Pity} group). */
    @Nonnull
    public Roll withPity(@Nullable Pity pity) {
        this.pity = pity;
        return this;
    }

    /** The table gates this roll carries ({@link #gatedBy}); never null, empty for an authored roll. */
    @Nonnull
    public GateSpec[] gates() {
        return gates;
    }

    /**
     * A copy of this roll that also answers to {@code gate} (a table's live {@code Requires}): it hits only
     * when every gate it carries passes for the moment's player ({@link FactorGate#passesGates}), before its
     * conditions and before any chance sample. Null answers this roll itself.
     */
    @Nonnull
    public Roll gatedBy(@Nullable GateSpec gate) {
        if (gate == null) {
            return this;
        }
        Roll copy = of(trigger, conditions, chance, ladder, grants, cue);
        copy.expected = expected;
        copy.hideInPreview = hideInPreview;
        copy.pity = pity;
        GateSpec[] more = new GateSpec[gates.length + 1];
        System.arraycopy(gates, 0, more, 0, gates.length);
        more[gates.length] = gate;
        copy.gates = more;
        return copy;
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

    // ==================== Pity ====================

    /**
     * Bad-luck protection on a roll's {@code Chance}: {@code {Id, Step, Max}}.
     *
     * <pre>{@code
     * "Chance": { "Base": 4 },
     * "Pity":   { "Id": "Rare_Find", "Step": 0.5, "Max": 20 }
     * }</pre>
     *
     * <p>Each Claim a player is paid from a payout that held this roll, in reach (its table's gates and its
     * conditions passed) but not fired, is one miss for that player under {@code Id}; a payout whose Claim holds
     * a pick of a roll under {@code Id} starts the count over. The chance the roll reads is its own (the formula
     * and the moment's bonus) plus {@code Step} per miss, the growth held at {@code Max} (never pulling a chance
     * already above it down), then inside 0..100 as every chance is. The count lives in
     * {@code pity/PityLedger}, which only a payout written as a Claim reads ({@link FactorLookup#pityMisses});
     * every other lookup answers no misses, so the roll reads its plain odds there, a preview included.
     *
     * <p>A blank {@code Id} or a {@code Step} of 0 or less never grows; a roll with no {@code Chance} always fires,
     * so its count never grows either.
     */
    public static final class Pity {

        /** The chance a growth is held at when {@code Max} is unwritten: the ceiling every chance has. */
        public static final double DEFAULT_MAX_PERCENT = MAX_CHANCE_PERCENT;

        @Nullable protected String id;
        @Nullable protected Double step;
        @Nullable protected Double max;

        public static final BuilderCodec<Pity> CODEC = BuilderCodec.builder(Pity.class, Pity::new)
                .appendInherited(new KeyedCodec<>("Id", Codec.STRING, false),
                        (o, v) -> o.id = v, o -> o.id, (o, p) -> o.id = p.id)
                .documentation("The count this roll's misses go to, kept per player and matched in any case "
                        + "(Is_Like_This). Rolls in several tables naming one Id share one count, and any of "
                        + "them firing starts it over. Blank turns the group off.").add()
                .appendInherited(new KeyedCodec<>("Step", Codec.DOUBLE, false),
                        (o, v) -> o.step = v, o -> o.step, (o, p) -> o.step = p.step)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("Percent points the chance grows by for each Claim paid without this roll firing "
                        + "(0.5 is half a percent a miss). 0 or less never grows.").add()
                .appendInherited(new KeyedCodec<>("Max", Codec.DOUBLE, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .metadata(EditorSchema.defaultValue(DEFAULT_MAX_PERCENT))
                .documentation("The chance, in percent, the growth stops at. It never lowers a chance already "
                        + "above it. Unwritten grows up to 100.").add()
                .build();

        public Pity() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Pity of(@Nullable String id, @Nullable Double step, @Nullable Double max) {
            Pity p = new Pity();
            p.id = id;
            p.step = step;
            p.max = max;
            return p;
        }

        @Nullable
        public String getId() {
            return id;
        }

        @Nullable
        public Double getStep() {
            return step;
        }

        @Nullable
        public Double getMax() {
            return max;
        }

        /** The count's key ({@link #keyOf} of the Id), or null when this group is off (a blank Id). */
        @Nullable
        public String key() {
            return keyOf(id);
        }

        /** The one spelling a pity Id is counted under: trimmed and lower-cased, null for a blank one. */
        @Nullable
        public static String keyOf(@Nullable String id) {
            return id == null || id.isBlank() ? null : id.trim().toLowerCase(Locale.ROOT);
        }

        /** {@code Step}, reader-defaulted to 0 when absent, not a number, or negative. */
        public double effectiveStep() {
            return step != null && Double.isFinite(step) && step > 0.0 ? step : 0.0;
        }

        /** {@code Max}, reader-defaulted to {@link #DEFAULT_MAX_PERCENT} when absent or not a number. */
        public double effectiveMax() {
            return max != null && Double.isFinite(max) ? max : DEFAULT_MAX_PERCENT;
        }

        /**
         * {@code percent} (the roll's chance before the 0..100 hold) grown by {@code misses}: plus
         * {@code Step x misses}, the growth held at {@code Max}, never below {@code percent} itself. Unchanged with
         * no misses, a blank Id or no step. Pure; the caller holds the answer inside 0..100.
         */
        public double grown(double percent, int misses) {
            double perMiss = effectiveStep();
            if (misses <= 0 || perMiss <= 0.0 || key() == null || !Double.isFinite(percent)) {
                return percent;
            }
            double grown = Math.min(percent + perMiss * misses, effectiveMax());
            return Math.max(percent, grown);
        }
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
