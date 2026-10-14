package com.ziggfreed.common.objectives.dialogue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.dialogue.type.DialogueCondition;
import com.ziggfreed.common.dialogue.type.DialogueConditionType;
import com.ziggfreed.common.objectives.gate.RequiresCheck;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * {@code Requires}: a conversation line, screen or {@code Start} beat that only a player meeting a
 * {@code Requires} block sees. The block is the shared one a quest, a storefront row, a board and a
 * {@code ZigRequires} door read ({@code Factors}, {@code Permission}, {@code Quests}, {@code Custom},
 * grouped with {@code AllOf}, {@code AnyOf} and {@code Not}), keyed {@code Requires} as on every other
 * surface.
 *
 * <pre>{@code
 * { "LabelKey": "yourpack.npc.veteran",
 *   "Conditions": [ { "Type": "Requires", "Requires": { "Quests": ["Yourpack_Intro"] } } ] }
 * }</pre>
 *
 * <p>The block is read for the runtime's own subject through the server's one evaluator
 * ({@link RequiresCheck}), so a line and the quest it points at never disagree, a factor any mod
 * contributes answers here the day that mod is installed, and a server running this library alone needs
 * no consumer to answer it. An unauthored block asks for nothing, so every player passes; a block that
 * asks for something hides the line from anything that is not a player the runtime can name.
 *
 * <p><b>{@code Gate} is its deprecated spelling</b>, {@code {"Type": "Gate", "Requirements": {...}}}, the
 * shape a consumer once registered for itself. A {@code Type} is keyed by its class in the one decode
 * table, so the old spelling is its own class here, answered by the same evaluator: a conversation that
 * still names it keeps loading and keeps its meaning, and the dialogue audit reports each use
 * ({@link DialogueAudit#GATE_CONDITION_DEPRECATED}), naming {@code Requires}. Write {@code Requires} in new
 * content; {@code Gate} will be removed in a later release.
 */
public final class RequiresDialogueCondition {

    /** The authored {@code "Type"}. */
    public static final String TYPE_ID = "Requires";

    /** The deprecated spelling's {@code "Type"}, its block under {@code Requirements}. */
    public static final String GATE_TYPE_ID = "Gate";

    /** What the block means, said once for both spellings. */
    private static final String BLOCK_DOC = "What a player must already have or have done to see this. "
            + "The same Requires block a quest or a shop row reads: Factors, Permission, Quests and Custom, "
            + "grouped with AllOf, AnyOf and Not. An unauthored block asks for nothing, so every player passes.";

    private RequiresDialogueCondition() {
    }

    /** What both spellings are: one {@code Requires} block. */
    public abstract static class Block extends DialogueCondition {

        @Nullable protected GateSpec requires;

        /** The authored block, or {@link GateSpec#OPEN} when none was authored. */
        @Nonnull
        public GateSpec getRequires() {
            GateSpec authored = requires;
            return authored == null ? GateSpec.OPEN : authored;
        }
    }

    /** {@code {"Type": "Requires", "Requires": {...}}}. */
    public static final class Requires extends Block {

        public static final BuilderCodec<Requires> CODEC = BuilderCodec.builder(Requires.class, Requires::new)
                .append(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                        (c, v) -> c.requires = v, c -> c.requires)
                .documentation(BLOCK_DOC)
                .add()
                .build();
    }

    /**
     * {@code {"Type": "Gate", "Requirements": {...}}}: the deprecated spelling of {@link Requires}, read and
     * answered exactly as it is. Kept so a conversation written for it still loads; write {@code Requires}.
     */
    public static final class Gate extends Block {

        public static final BuilderCodec<Gate> CODEC = BuilderCodec.builder(Gate.class, Gate::new)
                .append(new KeyedCodec<>("Requirements", GateSpec.CODEC, false),
                        (c, v) -> c.requires = v, c -> c.requires)
                .documentation("Deprecated: write {\"Type\": \"Requires\", \"Requires\": {...}} with the same "
                        + "block instead. " + BLOCK_DOC)
                .add()
                .build();
    }

    /** The registration of {@code Requires}: the Type, its codec and its evaluator. */
    @Nonnull
    public static DialogueConditionType<Requires> type() {
        return DialogueConditionType.of(TYPE_ID, Requires.class, Requires.CODEC,
                (Requires condition, DialogueContext ctx) -> evaluate(condition, ctx));
    }

    /** The registration of the deprecated {@code Gate}: its own class, the same evaluator. */
    @Nonnull
    public static DialogueConditionType<Gate> gateType() {
        return DialogueConditionType.of(GATE_TYPE_ID, Gate.class, Gate.CODEC,
                (Gate condition, DialogueContext ctx) -> evaluate(condition, ctx));
    }

    /**
     * Does {@code subject} meet {@code requires}? A block that asks for nothing passes for anyone, and a
     * block that asks for something refuses a missing subject. World thread.
     */
    static boolean passes(@Nullable GateSpec requires, @Nullable Subject subject) {
        if (requires == null || requires.isEmpty()) {
            return true;
        }
        return subject != null && RequiresCheck.passes(subject, requires);
    }

    /** The player in {@code ctx} against the condition's block; the subject is named only when it is asked. */
    private static boolean evaluate(@Nonnull Block condition, @Nonnull DialogueContext ctx) {
        GateSpec requires = condition.getRequires();
        if (requires.isEmpty()) {
            return true;
        }
        return passes(requires, RequiresCheck.playerSubject(ctx.store(), ctx.ref()));
    }
}
