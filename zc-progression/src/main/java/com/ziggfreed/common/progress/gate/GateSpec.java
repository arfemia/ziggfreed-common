package com.ziggfreed.common.progress.gate;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.factor.FactorCondition;

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
 */
public final class GateSpec extends GateClause {

    @Nullable protected GateClause[] allOf;
    @Nullable protected GateClause[] anyOf;
    @Nullable protected GateClause[] not;

    public static final BuilderCodec<GateSpec> CODEC =
            appendLeaves(BuilderCodec.builder(GateSpec.class, GateSpec::new))
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
                            + "group means 'not both', and two separate groups mean 'neither'.").add()
                    .build();

    /** The block content that authors no {@code Requires} is read as: it asks for nothing. */
    public static final GateSpec OPEN = new GateSpec();

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
