package com.ziggfreed.common.progress.gate;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.codec.ScalarStringCodec;
import com.ziggfreed.common.factor.FactorCondition;

/**
 * One group of requirements, ALL of which must pass. It is the shared leaf set behind every
 * {@code Requires} block in this module and behind each entry of its {@code AnyOf} /
 * {@code AllOf} lists, so a requirement means the same thing wherever it is written - on a quest,
 * on an achievement, on whatever comes next.
 *
 * <pre>{@code
 * { "Factors":    [ {"Factor": "yourmod:trade_rank", "Param": "smithing", "Min": 10} ],
 *   "Permission": "yourmod.quest.advanced",
 *   "Quests":     [ "intro_1", "intro_2" ],
 *   "Custom":     { "yourmod:reputation": { "Faction": "miners", "Min": "500" } } }
 * }</pre>
 *
 * <p>Four leaves, each independently optional; an empty group passes.
 * <ul>
 *   <li><b>{@code Factors}</b> - the shared numeric gate over the factor vocabulary, identical to
 *       the one an NPC placement or a dialogue option is written with. A factor nobody can answer
 *       fails CLOSED, so content gated on a mod that is not installed stays gated.</li>
 *   <li><b>{@code Permission}</b> - a permission node the acting player must hold, read through
 *       the engine's own permission check. It is the {@code hytale:permission} factor bound spelled
 *       short, evaluated by that same lookup, so the two forms are one requirement with one answer.
 *       Where there is nobody to ask - no live player behind the subject, or a blank node - it
 *       refuses, like any other reading that cannot be taken.</li>
 *   <li><b>{@code Quests}</b> - quest ids the player must have finished AND collected the reward
 *       for (stored status {@code COMPLETED}); a quest still waiting in {@code
 *       COMPLETED_UNCLAIMED} does not satisfy it. It is a built-in leaf rather than a registered
 *       kind because a completed quest is something this library owns the answer to, through the
 *       completion probe a consumer wires once.</li>
 *   <li><b>{@code Custom}</b> - requirement kinds a mod registered under its own namespaced id,
 *       keyed by that id, each carrying whatever parameters the kind documents. This is how a
 *       friendlier form ("the player is this far along in this trade") is authored without every
 *       gate having to be spelled out as raw factor bounds.</li>
 * </ul>
 *
 * <p>Every leaf is {@code appendInherited}, so content that inherits from a {@code Parent} can add
 * a permission without losing the parent's factor bounds. {@code Factors} and {@code Quests} are
 * each ONE leaf: authoring either replaces the inherited list whole.
 */
public class GateClause {

    @Nullable protected FactorCondition[] factors;
    @Nullable protected String permission;
    @Nullable protected String[] quests;
    @Nullable protected Map<String, Map<String, String>> custom;

    public static final BuilderCodec<GateClause> CODEC =
            appendLeaves(BuilderCodec.builder(GateClause.class, GateClause::new)).build();

    /**
     * Register the four shared leaves on {@code builder}. The {@code Requires} group reuses this so
     * the leaves are declared once and cannot drift between the top level and a nested clause.
     */
    @Nonnull
    protected static <T extends GateClause, S extends BuilderCodec.BuilderBase<T, S>> S appendLeaves(
            @Nonnull S builder) {
        return builder
                .appendInherited(new KeyedCodec<>("Factors",
                                new ArrayCodec<>(FactorCondition.codec(EditorDataSets.FACTORS),
                                        FactorCondition[]::new), false),
                        (o, v) -> o.factors = v, o -> o.factors, (o, p) -> o.factors = p.factors)
                .documentation("Numeric bounds on the shared factor vocabulary; all of them must pass. A factor "
                        + "no installed mod can answer fails closed, so the content stays locked.").add()
                .appendInherited(new KeyedCodec<>("Permission", Codec.STRING, false),
                        (o, v) -> o.permission = v, o -> o.permission, (o, p) -> o.permission = p.permission)
                .documentation("A permission node the player must hold, read through the engine's own permission "
                        + "check. It is the short spelling of a hytale:permission factor bound, so both give one "
                        + "answer; where nobody can be asked, it refuses and the content stays locked.").add()
                .appendInherited(new KeyedCodec<>("Quests", Codec.STRING_ARRAY, false),
                        (o, v) -> o.quests = v, o -> o.quests, (o, p) -> o.quests = p.quests)
                .documentation("Quest ids the player must have finished AND collected the reward for (stored "
                        + "status COMPLETED); a quest sitting finished-but-unclaimed, which is where an "
                        + "AutoClaim: false quest waits, does not satisfy it. Use it to chain a story in order "
                        + "instead of hiding every later step behind a separate flag.").add()
                .appendInherited(new KeyedCodec<>("Custom",
                                new InheritMapCodec<>(new InheritMapCodec<>(ScalarStringCodec.INSTANCE)), false),
                        (o, v) -> o.custom = v, o -> o.custom, (o, p) -> o.custom = p.custom)
                .documentation("Requirement kinds registered by other mods, keyed by their namespaced id, each "
                        + "with that kind's own parameters. A kind nothing registered refuses. A parameter that "
                        + "is a number or true/false may be written bare; other values take quotes.").add();
    }

    public GateClause() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static GateClause of(@Nullable FactorCondition[] factors, @Nullable String permission,
            @Nullable String[] quests, @Nullable Map<String, Map<String, String>> custom) {
        GateClause c = new GateClause();
        c.factors = factors == null ? null : factors.clone();
        c.permission = permission;
        c.quests = quests == null ? null : quests.clone();
        c.custom = custom == null ? null : new LinkedHashMap<>(custom);
        return c;
    }

    @Nullable
    public FactorCondition[] getFactors() {
        return factors == null ? null : factors.clone();
    }

    @Nullable
    public String getPermission() {
        return permission;
    }

    @Nullable
    public String[] getQuests() {
        return quests == null ? null : quests.clone();
    }

    @Nullable
    public Map<String, Map<String, String>> getCustom() {
        return custom == null ? null : new LinkedHashMap<>(custom);
    }

    /** The authored factor bounds without copying, for the evaluation path. */
    @Nonnull
    public FactorCondition[] factorsOrEmpty() {
        return factors == null ? new FactorCondition[0] : factors;
    }

    /** The authored prerequisite ids without copying, for the evaluation path. */
    @Nonnull
    public String[] questsOrEmpty() {
        return quests == null ? new String[0] : quests;
    }

    /** The authored custom entries without copying, for the evaluation path. */
    @Nonnull
    public Map<String, Map<String, String>> customOrEmpty() {
        return custom == null ? Map.of() : custom;
    }

    /**
     * True when this group asks for nothing at all, so it passes for everyone.
     *
     * <p>A {@code Permission} authored as an empty string still counts as asking: an empty node is
     * not a node anybody can hold, so it refuses rather than quietly opening the content. Leave the
     * key out to ask for no permission.
     */
    public boolean isEmpty() {
        return factorsOrEmpty().length == 0
                && permission == null
                && questsOrEmpty().length == 0
                && customOrEmpty().isEmpty();
    }
}
