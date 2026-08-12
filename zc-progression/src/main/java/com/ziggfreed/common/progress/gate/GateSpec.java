package com.ziggfreed.common.progress.gate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;

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
 * <p>Everything at the top level must pass, every {@code AllOf} group must pass, and at least one
 * {@code AnyOf} group must pass. An absent or empty block asks for nothing.
 *
 * <p><b>Nesting stops here on purpose.</b> One level of grouping expresses "these, plus one of
 * those", which is the shape real requirements take; arbitrary depth buys expressiveness nobody
 * can read back six months later, and a requirement that genuinely needs it is better written as a
 * registered {@code Custom} kind whose own rule lives in code.
 */
public final class GateSpec extends GateClause {

    @Nullable protected GateClause[] allOf;
    @Nullable protected GateClause[] anyOf;

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
                    .build();

    /** The block content that authors no {@code Requires} is read as: it asks for nothing. */
    public static final GateSpec OPEN = new GateSpec();

    public GateSpec() {
    }

    @Nullable
    public GateClause[] getAllOf() {
        return allOf == null ? null : allOf.clone();
    }

    @Nullable
    public GateClause[] getAnyOf() {
        return anyOf == null ? null : anyOf.clone();
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

    /** True when nothing anywhere in the block asks for anything. */
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
        return anyOfOrEmpty().length == 0;
    }
}
