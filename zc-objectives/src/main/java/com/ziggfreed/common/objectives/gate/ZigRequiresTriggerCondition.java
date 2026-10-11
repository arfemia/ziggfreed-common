package com.ziggfreed.common.objectives.gate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerCondition;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * A trigger-volume condition over a {@code Requires} block: the volume's effects run only for a player
 * who meets it, so a doorway, a portal or a teleport pad lets in only those who have done what it asks.
 *
 * <pre>{@code
 * "Conditions": [ { "Type": "ZigRequires", "Event": "ENTER",
 *                   "Requires": { "Quests": ["Yourpack_Intro"] } } ]
 * }</pre>
 *
 * <p>The block is the shared one ({@code Factors}, {@code Permission}, {@code Quests}, {@code Custom},
 * grouped with {@code AllOf}, {@code AnyOf} and {@code Not}), answered by the same evaluator a quest's
 * gate is ({@link RequiresCheck}). Anything that is not a player answers false. It speaks no line: a
 * volume is walked through, not pressed, so a refusal said on every tick would only be noise. A block
 * chain that should explain itself starts with the {@code ZigRequires} interaction instead.
 *
 * <p>Registered on the TriggerVolumes plugin at setup ({@code ProgressInteractionsBootstrap}), which is
 * why the library's manifest depends on that plugin.
 */
public final class ZigRequiresTriggerCondition extends TriggerCondition {

    /** The authored {@code "Type"} name, the same word the interaction uses. */
    public static final String TYPE_NAME = "ZigRequires";

    @Nullable
    private GateSpec requires;

    /** Lazy holder, the same shape as an interaction Type's: only registration builds the codec. */
    private static final class Holder {
        static final BuilderCodec<ZigRequiresTriggerCondition> CODEC = BuilderCodec.builder(
                        ZigRequiresTriggerCondition.class, ZigRequiresTriggerCondition::new,
                        TriggerCondition.BASE_CODEC)
                .append(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                        (c, v) -> c.requires = v, c -> c.requires)
                .documentation("What a player must already have or have done for the volume to act on them. "
                        + "Anything that is not a player never passes. An unauthored block asks for nothing, "
                        + "so every player passes.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration invokes this. */
    @Nonnull
    public static BuilderCodec<ZigRequiresTriggerCondition> codec() {
        return Holder.CODEC;
    }

    @Override
    public boolean test(@Nonnull TriggerContext context) {
        Subject subject = RequiresCheck.playerSubject(context.getStore(), context.getEntityRef());
        return subject != null && RequiresCheck.passes(subject, requires);
    }
}
