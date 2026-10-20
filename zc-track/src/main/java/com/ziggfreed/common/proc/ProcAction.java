package com.ziggfreed.common.proc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.factor.FactorFormula;

/**
 * One entry of a proc's {@code Actions}: one or more of its groups, run in a fixed order ({@code RemoveEffect},
 * {@code ApplyEffect}, {@code ChangeStat}, {@code Counter}, {@code RunInteraction}). Each group aims at the moment's
 * target unless it writes {@code OnSelf} true (the holder); one aimed at a target the moment has none of does nothing.
 * The proc's strength scales what an action gives: an applied effect's damage, a stat change, a counter's add.
 *
 * <pre>{@code
 * "Actions": [
 *   { "RemoveEffect": { "Families": [ "Burning", "Poisoned", "Slowed" ], "OnSelf": true } },
 *   { "ApplyEffect": { "Effect": "My_Exposed", "Duration": { "Base": 5 } } },
 *   { "Counter": { "Of": "My_Flow", "Add": { "Base": 1 } } }
 * ]
 * }</pre>
 */
public final class ProcAction {

    @Nullable protected ApplyEffect applyEffect;
    @Nullable protected RemoveEffect removeEffect;
    @Nullable protected ChangeStat changeStat;
    @Nullable protected CounterChange counter;
    @Nullable protected RunInteraction runInteraction;

    public static final BuilderCodec<ProcAction> CODEC = BuilderCodec.builder(ProcAction.class, ProcAction::new)
            .appendInherited(new KeyedCodec<>("RemoveEffect", RemoveEffect.CODEC, false),
                    (o, v) -> o.removeEffect = v, o -> o.removeEffect, (o, p) -> o.removeEffect = p.removeEffect)
            .documentation("Take effects off: named ones, or ones of named effect families. Runs first.").add()
            .appendInherited(new KeyedCodec<>("ApplyEffect", ApplyEffect.CODEC, false),
                    (o, v) -> o.applyEffect = v, o -> o.applyEffect, (o, p) -> o.applyEffect = p.applyEffect)
            .documentation("Put a native effect on, owned by the holder, so its damage credits them.").add()
            .appendInherited(new KeyedCodec<>("ChangeStat", ChangeStat.CODEC, false),
                    (o, v) -> o.changeStat = v, o -> o.changeStat, (o, p) -> o.changeStat = p.changeStat)
            .documentation("Add to a stat's current value (stamina, mana, health), held between its minimum and "
                    + "maximum by the engine.").add()
            .appendInherited(new KeyedCodec<>("Counter", CounterChange.CODEC, false),
                    (o, v) -> o.counter = v, o -> o.counter, (o, p) -> o.counter = p.counter)
            .documentation("Add to, take from or clear one of the holder's counters (Server/ZiggfreedCommon/"
                    + "Counters).").add()
            .appendInherited(new KeyedCodec<>("RunInteraction", RunInteraction.CODEC, false),
                    (o, v) -> o.runInteraction = v, o -> o.runInteraction,
                    (o, p) -> o.runInteraction = p.runInteraction)
            .documentation("Run a native RootInteraction chain on the holder or the target. Runs last.").add()
            .build();

    public ProcAction() {
    }

    @Nullable public ApplyEffect applyEffect() { return applyEffect; }
    @Nullable public RemoveEffect removeEffect() { return removeEffect; }
    @Nullable public ChangeStat changeStat() { return changeStat; }
    @Nullable public CounterChange counter() { return counter; }
    @Nullable public RunInteraction runInteraction() { return runInteraction; }

    /** Does it name no group at all? */
    public boolean isEmpty() {
        return applyEffect == null && removeEffect == null && changeStat == null && counter == null
                && runInteraction == null;
    }

    /** The ApplyEffect group. */
    public static final class ApplyEffect {

        @Nullable protected String effect;
        @Nullable protected Boolean onSelf;
        @Nullable protected FactorFormula duration;
        @Nullable protected String overlap;
        @Nullable protected FactorFormula damageMultiplier;

        public static final BuilderCodec<ApplyEffect> CODEC = BuilderCodec.builder(ApplyEffect.class, ApplyEffect::new)
                .appendInherited(new KeyedCodec<>("Effect", Codec.STRING, false),
                        (o, v) -> o.effect = v, o -> o.effect, (o, p) -> o.effect = p.effect)
                .metadata(EditorSchema.assetRef(EntityEffect.class))
                .documentation("The native effect to put on, by id.").add()
                .appendInherited(new KeyedCodec<>("OnSelf", Codec.BOOLEAN, false),
                        (o, v) -> o.onSelf = v, o -> o.onSelf, (o, p) -> o.onSelf = p.onSelf)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: on the holder. False: on the moment's target. False when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Duration", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.duration = v, o -> o.duration, (o, p) -> o.duration = p.duration)
                .documentation("Seconds it lasts, a formula. The effect's own Duration when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Overlap", Codec.STRING, false),
                        (o, v) -> o.overlap = v, o -> o.overlap, (o, p) -> o.overlap = p.overlap)
                .metadata(EditorSchema.oneOfDocumented(
                        "Overwrite", "A second apply restarts its time",
                        "Extend", "A second apply adds its time",
                        "Ignore", "A second apply does nothing while it lasts"))
                .documentation("What a second apply does while the effect is still on. The effect's own "
                        + "OverlapBehavior when unauthored.").add()
                .appendInherited(new KeyedCodec<>("DamageMultiplier", FactorFormula.codec(EditorDataSets.FACTORS),
                                false),
                        (o, v) -> o.damageMultiplier = v, o -> o.damageMultiplier,
                        (o, p) -> o.damageMultiplier = p.damageMultiplier)
                .documentation("What this application's damage is multiplied by (an effect with a "
                        + "DamageCalculator), a formula, times the proc's strength. 1 when unauthored.").add()
                .build();

        public ApplyEffect() {
        }

        @Nullable
        public String effect() {
            return effect == null || effect.isBlank() ? null : effect.trim();
        }

        public boolean onSelf() {
            return Boolean.TRUE.equals(onSelf);
        }

        @Nullable
        public FactorFormula duration() {
            return duration;
        }

        /** The overlap as written, or null when unauthored or unknown. */
        @Nullable
        public OverlapBehavior overlap() {
            return overlapOf(overlap);
        }

        @Nullable
        public String rawOverlap() {
            return overlap;
        }

        @Nullable
        public FactorFormula damageMultiplier() {
            return damageMultiplier;
        }
    }

    /** The RemoveEffect group. */
    public static final class RemoveEffect {

        @Nullable protected String[] effects;
        @Nullable protected String[] families;
        @Nullable protected Boolean onSelf;
        @Nullable protected Integer count;

        public static final BuilderCodec<RemoveEffect> CODEC = BuilderCodec.builder(RemoveEffect.class,
                        RemoveEffect::new)
                .appendInherited(new KeyedCodec<>("Effects", Codec.STRING_ARRAY, false),
                        (o, v) -> o.effects = v, o -> o.effects, (o, p) -> o.effects = p.effects)
                .metadata(EditorSchema.assetRef(EntityEffect.class))
                .documentation("Native effects to take off, by id.").add()
                .appendInherited(new KeyedCodec<>("Families", Codec.STRING_ARRAY, false),
                        (o, v) -> o.families = v, o -> o.families, (o, p) -> o.families = p.families)
                .documentation("Effect families (Server/ZiggfreedCommon/EffectFamilies: Burning, Poisoned, "
                        + "Slowed) whose effects come off.").add()
                .appendInherited(new KeyedCodec<>("OnSelf", Codec.BOOLEAN, false),
                        (o, v) -> o.onSelf = v, o -> o.onSelf, (o, p) -> o.onSelf = p.onSelf)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: from the holder. False: from the moment's target. False when "
                        + "unauthored.").add()
                .appendInherited(new KeyedCodec<>("Count", Codec.INTEGER, false),
                        (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("How many matching effects come off; 0 for every one. 1 when unauthored.").add()
                .build();

        public RemoveEffect() {
        }

        @Nonnull
        public List<String> effects() {
            return trimmed(effects);
        }

        @Nonnull
        public List<String> families() {
            return trimmed(families);
        }

        public boolean onSelf() {
            return Boolean.TRUE.equals(onSelf);
        }

        /** How many come off, 0 for every one. */
        public int count() {
            return count == null ? 1 : Math.max(0, count);
        }
    }

    /** The ChangeStat group. */
    public static final class ChangeStat {

        @Nullable protected String stat;
        @Nullable protected FactorFormula amount;
        @Nullable protected Boolean onSelf;

        public static final BuilderCodec<ChangeStat> CODEC = BuilderCodec.builder(ChangeStat.class, ChangeStat::new)
                .appendInherited(new KeyedCodec<>("Stat", Codec.STRING, false),
                        (o, v) -> o.stat = v, o -> o.stat, (o, p) -> o.stat = p.stat)
                .metadata(EditorSchema.assetRef(EntityStatType.class))
                .documentation("The native stat whose current value moves, by id (Stamina, Mana).").add()
                .appendInherited(new KeyedCodec<>("Amount", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.amount = v, o -> o.amount, (o, p) -> o.amount = p.amount)
                .documentation("What is added, a formula, times the proc's strength; negative takes away.").add()
                .appendInherited(new KeyedCodec<>("OnSelf", Codec.BOOLEAN, false),
                        (o, v) -> o.onSelf = v, o -> o.onSelf, (o, p) -> o.onSelf = p.onSelf)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: the holder's stat. False: the target's. False when unauthored.").add()
                .build();

        public ChangeStat() {
        }

        @Nullable
        public String stat() {
            return stat == null || stat.isBlank() ? null : stat.trim();
        }

        @Nullable
        public FactorFormula amount() {
            return amount;
        }

        public boolean onSelf() {
            return Boolean.TRUE.equals(onSelf);
        }
    }

    /** The Counter group. */
    public static final class CounterChange {

        @Nullable protected String of;
        @Nullable protected FactorFormula add;
        @Nullable protected Boolean byAmount;
        @Nullable protected FactorFormula consume;
        @Nullable protected Boolean clear;

        public static final BuilderCodec<CounterChange> CODEC = BuilderCodec.builder(CounterChange.class,
                        CounterChange::new)
                .appendInherited(new KeyedCodec<>("Of", Codec.STRING, false),
                        (o, v) -> o.of = v, o -> o.of, (o, p) -> o.of = p.of)
                .documentation("The counter, by its file's id (Server/ZiggfreedCommon/Counters).").add()
                .appendInherited(new KeyedCodec<>("Add", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.add = v, o -> o.add, (o, p) -> o.add = p.add)
                .documentation("Stacks added, a formula, times the proc's strength, up to the counter's Size. "
                        + "Nothing added when unauthored.").add()
                .appendInherited(new KeyedCodec<>("ByAmount", Codec.BOOLEAN, false),
                        (o, v) -> o.byAmount = v, o -> o.byAmount, (o, p) -> o.byAmount = p.byAmount)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: Add is multiplied by the moment's amount (the damage of a hit dealt or "
                        + "taken), so 0.25 stores a quarter of it. False when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Consume", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.consume = v, o -> o.consume, (o, p) -> o.consume = p.consume)
                .documentation("Stacks taken away, a formula. Nothing taken when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Clear", Codec.BOOLEAN, false),
                        (o, v) -> o.clear = v, o -> o.clear, (o, p) -> o.clear = p.clear)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: every stack goes, after Add and Consume. False when unauthored.").add()
                .build();

        public CounterChange() {
        }

        @Nullable
        public String of() {
            return of == null || of.isBlank() ? null : of.trim();
        }

        @Nullable
        public FactorFormula add() {
            return add;
        }

        public boolean byAmount() {
            return Boolean.TRUE.equals(byAmount);
        }

        @Nullable
        public FactorFormula consume() {
            return consume;
        }

        public boolean clear() {
            return Boolean.TRUE.equals(clear);
        }
    }

    /** The RunInteraction group. */
    public static final class RunInteraction {

        @Nullable protected String interaction;
        @Nullable protected Boolean onSelf;

        public static final BuilderCodec<RunInteraction> CODEC = BuilderCodec.builder(RunInteraction.class,
                        RunInteraction::new)
                .appendInherited(new KeyedCodec<>("Interaction", Codec.STRING, false),
                        (o, v) -> o.interaction = v, o -> o.interaction, (o, p) -> o.interaction = p.interaction)
                .metadata(EditorSchema.assetRef(RootInteraction.class))
                .documentation("The RootInteraction to run, by id.").add()
                .appendInherited(new KeyedCodec<>("OnSelf", Codec.BOOLEAN, false),
                        (o, v) -> o.onSelf = v, o -> o.onSelf, (o, p) -> o.onSelf = p.onSelf)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("True: it runs on the holder, the moment's target as its target entity. False: it "
                        + "runs on the target, the holder as its target entity. True when unauthored.").add()
                .build();

        public RunInteraction() {
        }

        @Nullable
        public String interaction() {
            return interaction == null || interaction.isBlank() ? null : interaction.trim();
        }

        public boolean onSelf() {
            return onSelf == null || onSelf;
        }
    }

    /** The overlap {@code raw} names (any case), or null when blank or unknown. */
    @Nullable
    static OverlapBehavior overlapOf(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String folded = raw.trim().toUpperCase(Locale.ROOT);
        for (OverlapBehavior behavior : OverlapBehavior.values()) {
            if (behavior.name().equals(folded)) {
                return behavior;
            }
        }
        return null;
    }

    @Nonnull
    static List<String> trimmed(@Nullable String[] values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank() && !out.contains(value.trim())) {
                    out.add(value.trim());
                }
            }
        }
        return out;
    }
}
