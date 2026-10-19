package com.ziggfreed.common.progress.gate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.ModGates;

/**
 * A whole {@code Requires} block: the four shared requirement leaves (see
 * {@link GateClause}) plus two ways to combine groups of them.
 *
 * <pre>{@code
 * "Requires": {
 *   "Factors": [ {"Factor": "yourmod:trade_rank", "Param": "smithing", "Min": 10} ],
 *   "AnyOf": [ { "Quests": ["intro_miner"] },
 *              { "Quests": ["intro_smith"] } ] }
 * }</pre>
 *
 * <p>Everything at the top level must pass, every {@code AllOf} group must pass, at least one
 * {@code AnyOf} group must pass, and no {@code Not} group may pass. An absent or empty block asks
 * for nothing.
 *
 * <p><b>{@code Not} is the negative form, and it is a group like the others.</b> Some requirements
 * are only expressible backwards - "while you are NOT yet in the guild", "only before you have
 * finished the tutorial" - and a numeric one can be written as a {@code Max} bound but a
 * prerequisite, a permission or a registered kind cannot. Each {@code Not} entry is one group that
 * must FAIL, so a group listing two things means "not both of those", and two separate groups mean
 * "neither of them".
 *
 * <p><b>Nesting stops here on purpose.</b> One level of grouping expresses "these, plus one of
 * those, and none of these", which is the shape real requirements take; arbitrary depth buys
 * expressiveness nobody can read back six months later, and a requirement that genuinely needs it
 * is better written as a registered {@code Custom} kind whose own rule lives in code.
 *
 * <p>Not final for one reason only: a store whose files spell their factor list under a name of their own
 * ({@link #codecReadingAsFactors}) declares a subclass that adds nothing, so its codec carries a type of its own
 * and the schema the engine exports keeps the store's block (with its extra leaf) apart from this shared one.
 */
public class GateSpec extends GateClause {

    @Nullable protected GateClause[] allOf;
    @Nullable protected GateClause[] anyOf;
    @Nullable protected GateClause[] not;

    /**
     * What a {@link #codecReadingAsFactors} leaf decoded, held only until the decode finishes and then folded
     * into {@link #factors}; never authored by the shared codec, never written back.
     */
    @Nullable private FactorCondition[] factorAlias;

    /**
     * The {@link #factors} this block took from its {@code Parent} when it began decoding, held only until the
     * decode finishes: {@link #foldFactorAlias} tells by identity whether the file restated {@code Factors} itself,
     * so the alias replaces what was inherited exactly as {@code Factors} does. Never authored, never written back.
     */
    @Nullable private FactorCondition[] inheritedFactors;

    public static final BuilderCodec<GateSpec> CODEC =
            appendGroups(appendLeaves(BuilderCodec.builder(GateSpec.class, GateSpec::new))).build();

    /**
     * The shared block's codec plus ONE more leaf, {@code key}, whose conditions are read as {@code Factors}:
     * for a store whose files wrote their own factor list under another name before joining the shared block
     * (a station's {@code Conditions}), so every file it shipped keeps meaning what it meant. The leaf decodes
     * through {@code conditionCodec} (the store's own editor pick list) and joins whatever {@code Factors} the
     * same file authors once the decode finishes; like {@code Factors}, a child that restates it replaces the
     * factors its {@code Parent} gave it, and a child that writes neither keeps them. A write back spells it
     * {@code Factors}. Every other
     * leaf, group and rule is the shared block's own (the codec is built on {@link #CODEC} as its parent), so the
     * block evaluates, explains and validates exactly as a quest's does.
     *
     * <p>{@code type} is the store's own subclass of this one, adding nothing: the engine's schema export names a
     * definition by the class a codec builds and keeps the first it meets, so a second codec over this class would
     * fold the store's block and the shared one into one definition (the shared {@code Requires} would show the
     * store's extra leaf everywhere). A type of its own keeps the two apart.
     */
    @Nonnull
    public static <S extends GateSpec> BuilderCodec<S> codecReadingAsFactors(@Nonnull Class<S> type,
            @Nonnull Supplier<S> constructor, @Nonnull String key, @Nonnull Codec<FactorCondition> conditionCodec,
            @Nonnull String documentation) {
        return BuilderCodec.builder(type, constructor, CODEC)
                .appendInherited(new KeyedCodec<>(key, new ArrayCodec<>(conditionCodec, FactorCondition[]::new), false),
                        (o, v) -> ((GateSpec) o).factorAlias = v, o -> ((GateSpec) o).factorAlias,
                        // A decoded parent's alias is already folded into its factors, which the shared leaf
                        // just inherited: remember them, so the fold can tell a restated Factors from them.
                        (o, p) -> ((GateSpec) o).inheritedFactors = ((GateSpec) p).factors)
                .documentation(documentation).add()
                .afterDecode((spec, extraInfo) -> ((GateSpec) spec).foldFactorAlias())
                .build();
    }

    /** The three groups, declared once for {@link #CODEC}. */
    @Nonnull
    private static BuilderCodec.Builder<GateSpec> appendGroups(@Nonnull BuilderCodec.Builder<GateSpec> builder) {
        return builder
                .appendInherited(new KeyedCodec<>("AllOf",
                                new ArrayCodec<>(GateClause.CODEC, GateClause[]::new), false),
                        (o, v) -> o.allOf = v, o -> o.allOf, (o, p) -> o.allOf = p.allOf)
                .documentation("Extra groups that must ALL pass, on top of the leaves above. Use it to keep "
                        + "unrelated requirements readable side by side.").add()
                .appendInherited(new KeyedCodec<>("AnyOf",
                                new ArrayCodec<>(GateClause.CODEC, GateClause[]::new), false),
                        (o, v) -> o.anyOf = v, o -> o.anyOf, (o, p) -> o.anyOf = p.anyOf)
                .documentation("Groups of which at least ONE must pass - the way to say 'either route into "
                        + "this will do'. An empty list asks for nothing.").add()
                .appendInherited(new KeyedCodec<>("Not",
                                new ArrayCodec<>(GateClause.CODEC, GateClause[]::new), false),
                        (o, v) -> o.not = v, o -> o.not, (o, p) -> o.not = p.not)
                .documentation("Groups that must NOT pass - the way to say 'only while this is not yet "
                        + "true'. Each entry is one group that has to fail, so listing two things in one "
                        + "group means 'not both', and two separate groups mean 'neither'.").add();
    }

    /**
     * Fold a {@link #codecReadingAsFactors} leaf into {@link #factors}, after the ones the same file authors. An
     * authored alias replaces what the block inherited, exactly as an authored {@code Factors} does: a child that
     * restates {@code Conditions} asks for its own list, never its parent's plus its own.
     */
    private void foldFactorAlias() {
        FactorCondition[] alias = factorAlias;
        FactorCondition[] inherited = inheritedFactors;
        factorAlias = null;
        inheritedFactors = null;
        if (alias == null || (alias.length == 0 && inherited == null)) {
            return;
        }
        // Still the inherited array (by identity): this file wrote no Factors of its own, so nothing goes before it.
        FactorCondition[] own = factors == null || factors == inherited ? new FactorCondition[0] : factors;
        FactorCondition[] merged = new FactorCondition[own.length + alias.length];
        System.arraycopy(own, 0, merged, 0, own.length);
        System.arraycopy(alias, 0, merged, own.length, alias.length);
        factors = merged;
    }

    /** The block content that authors no {@code Requires} is read as: it asks for nothing. */
    public static final GateSpec OPEN = new GateSpec();

    /**
     * The compiled form an evaluator made of this block ({@link GateEvaluator#check}), kept here so a hot path
     * finds it with no lookup and it goes with the block. Never authored, never inherited, never copied.
     */
    @Nullable volatile CompiledGate compiled;

    public GateSpec() {
    }

    /**
     * Java-side factory; sets the same fields the codec fills, so a consumer that reads requirements
     * out of its own authored format can produce the one model rather than keeping a second.
     *
     * <p>It is the peer of {@link GateClause#of}, and it exists for the same reason: without it a
     * whole block can only come from a codec decode, which forces any consumer with a non-codec
     * authoring surface to grow a requirement model of its own beside this one.
     */
    @Nonnull
    public static GateSpec of(@Nullable FactorCondition[] factors, @Nullable String permission,
            @Nullable String[] quests, @Nullable Map<String, Map<String, String>> custom,
            @Nullable GateClause[] allOf, @Nullable GateClause[] anyOf, @Nullable GateClause[] not) {
        GateSpec spec = new GateSpec();
        spec.factors = factors == null ? null : factors.clone();
        spec.permission = permission;
        spec.quests = quests == null ? null : quests.clone();
        spec.custom = custom == null ? null : new LinkedHashMap<>(custom);
        spec.allOf = allOf == null ? null : allOf.clone();
        spec.anyOf = anyOf == null ? null : anyOf.clone();
        spec.not = not == null ? null : not.clone();
        return spec;
    }

    /**
     * Does a file whose top-level block is {@code requires} load on this server? False only when a
     * plain top-level {@code hytale:mod_installed} condition with {@code Min: 1} names a mod this server
     * does not run (zc-core {@code ModGates}); every other condition, and any nested one, is left to the
     * fold and the gate. The keep filter a quest, achievement or commerce store's load handler passes.
     */
    public static boolean passesModGate(@Nullable GateSpec requires) {
        return missingMod(requires) == null;
    }

    /**
     * The mod ({@code Group:Name}) that keeps a file whose top-level block is {@code requires} off this
     * server, or null when it loads here (zc-core {@code ModGates.missingMod}): the read a gated store's
     * load handler and owner layer pass on, so a drop line can name the mod.
     */
    @Nullable
    public static String missingMod(@Nullable GateSpec requires) {
        return requires == null ? null : ModGates.missingMod(requires.factorsOrEmpty());
    }

    @Nullable
    public GateClause[] getAllOf() {
        return allOf == null ? null : allOf.clone();
    }

    @Nullable
    public GateClause[] getAnyOf() {
        return anyOf == null ? null : anyOf.clone();
    }

    @Nullable
    public GateClause[] getNot() {
        return not == null ? null : not.clone();
    }

    /** The authored {@code AllOf} groups without copying, for the evaluation path. */
    @Nonnull
    public GateClause[] allOfOrEmpty() {
        return allOf == null ? new GateClause[0] : allOf;
    }

    /** The authored {@code AnyOf} groups without copying, for the evaluation path. */
    @Nonnull
    public GateClause[] anyOfOrEmpty() {
        return anyOf == null ? new GateClause[0] : anyOf;
    }

    /** The authored {@code Not} groups without copying, for the evaluation path. */
    @Nonnull
    public GateClause[] notOrEmpty() {
        return not == null ? new GateClause[0] : not;
    }

    /**
     * True when nothing anywhere in the block asks for anything.
     *
     * <p>An EMPTY {@code Not} group is not "asks for nothing": a group with no requirements passes
     * for everyone, so a {@code Not} on it can never be satisfied and the content is shut to
     * everyone. That is a real (if unhelpful) requirement, so it counts, and the validator is where
     * an author is told about it rather than here.
     */
    @Override
    public boolean isEmpty() {
        if (!super.isEmpty()) {
            return false;
        }
        for (GateClause clause : allOfOrEmpty()) {
            if (clause != null && !clause.isEmpty()) {
                return false;
            }
        }
        return anyOfOrEmpty().length == 0 && notOrEmpty().length == 0;
    }
}
