package com.ziggfreed.common.proc;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;

/**
 * A counter: stacks a player builds and spends (Flow, Momentum, Rage, Hot Streak, Static, Resolve), filled and
 * emptied by procs' {@code Counter} actions, never saved. One per file at
 * {@code Server/ZiggfreedCommon/Counters/<Id>.json}, the filename its id; a server owner retunes one by id in
 * {@code mods/ziggfreedcommon/counters.json}. Not zc-core's lifetime tallies ({@code ziggfreedcommon:counter}): a
 * counter here lives only while its holder fights.
 *
 * <pre>{@code
 * {
 *   "Size": { "Base": 4 },
 *   "OneTarget": true,
 *   "ClearedBy": [ "HitTaken" ],
 *   "PerStack": [ { "Stat": "My_CritChance", "Amount": 2 } ],
 *   "Effects": [ "My_Flow_Shown" ]
 * }
 * }</pre>
 *
 * <p>Each stack gives its {@code PerStack} amounts as one native modifier per stat on the holder, keyed
 * {@value Counters#MODIFIER_PREFIX}{@code <id>} (under the track grants' owner, so the 1.7.x rollback sweep clears one
 * left on a stat that release knows, R755). Its stacks read as {@code ziggfreedcommon:counter_stacks} (Param the id),
 * and show to the player through {@code Effects}.
 */
public final class CounterAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, CounterAsset>> {

    /** Where the counters live under a pack's {@code Server/} folder. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Counters";

    /** The Decay group: how stacks fall away once nothing has changed them for a while. */
    public static final class Decay {

        @Nullable protected FactorFormula after;
        @Nullable protected FactorFormula every;
        @Nullable protected Double lose;

        public static final BuilderCodec<Decay> CODEC = BuilderCodec.builder(Decay.class, Decay::new)
                .appendInherited(new KeyedCodec<>("After", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.after = v, o -> o.after, (o, p) -> o.after = p.after)
                .documentation("Seconds after the counter last changed that its stacks begin to fall, a "
                        + "formula.").add()
                .appendInherited(new KeyedCodec<>("Every", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.every = v, o -> o.every, (o, p) -> o.every = p.every)
                .documentation("Seconds between each fall after that, a formula. 0 or less: every stack goes at "
                        + "once. Every stack at once when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Lose", Codec.DOUBLE, false),
                        (o, v) -> o.lose = v, o -> o.lose, (o, p) -> o.lose = p.lose)
                .metadata(EditorSchema.defaultValue(1.0))
                .documentation("Stacks each fall takes. 1 when unauthored.").add()
                .build();

        public Decay() {
        }

        @Nullable
        public FactorFormula after() {
            return after;
        }

        @Nullable
        public FactorFormula every() {
            return every;
        }

        public double lose() {
            return lose == null || !Double.isFinite(lose) ? 1.0 : Math.max(0.0, lose);
        }
    }

    /** One {@code PerStack} row: a stat and what one stack adds to its maximum. */
    public static final class PerStack {

        @Nullable protected String stat;
        @Nullable protected Double amount;

        public static final BuilderCodec<PerStack> CODEC = BuilderCodec.builder(PerStack.class, PerStack::new)
                .appendInherited(new KeyedCodec<>("Stat", Codec.STRING, false),
                        (o, v) -> o.stat = v, o -> o.stat, (o, p) -> o.stat = p.stat)
                .metadata(EditorSchema.assetRef(EntityStatType.class))
                .documentation("The native stat each stack raises, by id.").add()
                .appendInherited(new KeyedCodec<>("Amount", Codec.DOUBLE, false),
                        (o, v) -> o.amount = v, o -> o.amount, (o, p) -> o.amount = p.amount)
                .documentation("What one stack adds, in the stat's own units (2 is +2 on a whole percent "
                        + "stat).").add()
                .build();

        public PerStack() {
        }

        @Nullable
        public String stat() {
            return stat == null || stat.isBlank() ? null : stat.trim();
        }

        public double amount() {
            return amount == null || !Double.isFinite(amount) ? 0.0 : amount;
        }
    }

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean isAbstract;
    @Nullable protected Boolean enabled;
    @Nullable protected GateSpec requires;
    @Nullable protected FactorFormula size;
    @Nullable protected Decay decay;
    @Nullable protected String[] clearedBy;
    @Nullable protected Boolean clearOverTime;
    @Nullable protected Boolean oneTarget;
    @Nullable protected Boolean perTarget;
    @Nullable protected PerStack[] perStack;
    @Nullable protected String[] effects;
    @Nullable private volatile GateSpec[] live;

    public static final AssetBuilderCodec<String, CounterAsset> CODEC = AssetBuilderCodec.builder(
                    CounterAsset.class,
                    CounterAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Abstract", Codec.BOOLEAN, false), (a, v) -> a.isAbstract = v, a -> a.isAbstract)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("A skeleton other counters take their fields from through Parent. Never inherited. False "
                    + "when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false to switch the counter off: nothing adds to it. True when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation("What a player must meet to keep its stacks (the talent that builds it), asked a few times "
                    + "a second: the pass after it fails (a respec, a build swapped away), every stack goes. A plain "
                    + "hytale:mod_installed condition with Min 1 keeps the file out where that mod is missing. Kept "
                    + "always when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Size", FactorFormula.codec(EditorDataSets.FACTORS), false),
                    (a, v) -> a.size = v, a -> a.size, (a, p) -> a.size = p.size)
            .documentation("The most stacks it holds, a formula (a talent's rank raises it), raised by the holder's "
                    + "CounterSize stat. 1 when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Decay", Decay.CODEC, false),
                    (a, v) -> a.decay = v, a -> a.decay, (a, p) -> a.decay = p.decay)
            .documentation("How its stacks fall away once nothing changes them. They stay until spent or cleared "
                    + "when unauthored.").add()
            .appendInherited(new KeyedCodec<>("ClearedBy", Codec.STRING_ARRAY, false),
                    (a, v) -> a.clearedBy = v, a -> a.clearedBy, (a, p) -> a.clearedBy = p.clearedBy)
            .metadata(EditorSchema.oneOf(ProcTrigger.ids()))
            .documentation("Moments that empty it on the holder (HitTaken: a hit on you clears it). A "
                    + "damage-over-time tick (a burn, a poison) never does unless ClearOverTime says so. None when "
                    + "unauthored.").add()
            .appendInherited(new KeyedCodec<>("ClearOverTime", Codec.BOOLEAN, false),
                    (a, v) -> a.clearOverTime = v, a -> a.clearOverTime, (a, p) -> a.clearOverTime = p.clearOverTime)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True: a damage-over-time tick on the holder counts as a ClearedBy hit too. False when "
                    + "unauthored.").add()
            .appendInherited(new KeyedCodec<>("OneTarget", Codec.BOOLEAN, false),
                    (a, v) -> a.oneTarget = v, a -> a.oneTarget, (a, p) -> a.oneTarget = p.oneTarget)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True: its stacks are about one foe: an add from a moment about another foe starts it "
                    + "over. False when unauthored.").add()
            .appendInherited(new KeyedCodec<>("PerTarget", Codec.BOOLEAN, false),
                    (a, v) -> a.perTarget = v, a -> a.perTarget, (a, p) -> a.perTarget = p.perTarget)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True: a count kept for each foe apart (the third hit on the same foe): a change adds to "
                    + "or takes from the stacks about the moment's target, ziggfreedcommon:counter_stacks reads the "
                    + "stacks about the asking moment's target, and the stacks about a foe go when it does. Such a "
                    + "counter gives no PerStack modifiers and shows no Effects. False when unauthored.").add()
            .appendInherited(new KeyedCodec<>("PerStack", new ArrayCodec<>(PerStack.CODEC, PerStack[]::new), false),
                    (a, v) -> a.perStack = v, a -> a.perStack, (a, p) -> a.perStack = p.perStack)
            .documentation("What each stack gives its holder, as native stat modifiers. Nothing when "
                    + "unauthored.").add()
            .appendInherited(new KeyedCodec<>("Effects", Codec.STRING_ARRAY, false),
                    (a, v) -> a.effects = v, a -> a.effects, (a, p) -> a.effects = p.effects)
            .metadata(EditorSchema.assetRef(EntityEffect.class))
            .documentation("The native effect shown while it holds stacks: the first at one stack, the second at "
                    + "two, the last for every count past the list. Nothing shown when unauthored.").add()
            .build();

    public CounterAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    public boolean isAbstract() {
        return Boolean.TRUE.equals(isAbstract);
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public GateSpec requires() {
        return requires;
    }

    /** The part of {@link #requires()} asked of the player, lifted once; null when nothing is. */
    @Nullable
    public GateSpec liveRequires() {
        GateSpec[] held = live;
        if (held == null) {
            held = new GateSpec[] {StoreGates.live(requires)};
            live = held;
        }
        return held[0];
    }

    /** Does a damage-over-time tick count as a clearing hit? */
    public boolean clearOverTime() {
        return Boolean.TRUE.equals(clearOverTime);
    }

    @Nullable
    public FactorFormula size() {
        return size;
    }

    @Nullable
    public Decay decay() {
        return decay;
    }

    /** The clearing moments as written, unknown spellings dropped. */
    @Nonnull
    public List<ProcTrigger> clearedBy() {
        List<ProcTrigger> out = new ArrayList<>();
        for (String raw : ProcAction.trimmed(clearedBy)) {
            ProcTrigger trigger = ProcTrigger.parse(raw);
            if (trigger != null && !out.contains(trigger)) {
                out.add(trigger);
            }
        }
        return out;
    }

    /** The clearing moments exactly as written, for the audit. */
    @Nonnull
    public List<String> rawClearedBy() {
        return ProcAction.trimmed(clearedBy);
    }

    public boolean oneTarget() {
        return Boolean.TRUE.equals(oneTarget);
    }

    /** Is a count kept for each foe apart? */
    public boolean perTarget() {
        return Boolean.TRUE.equals(perTarget);
    }

    @Nonnull
    public List<PerStack> perStack() {
        List<PerStack> out = new ArrayList<>();
        if (perStack != null) {
            for (PerStack row : perStack) {
                if (row != null && row.stat() != null) {
                    out.add(row);
                }
            }
        }
        return out;
    }

    @Nonnull
    public List<String> effects() {
        return ProcAction.trimmed(effects);
    }
}
