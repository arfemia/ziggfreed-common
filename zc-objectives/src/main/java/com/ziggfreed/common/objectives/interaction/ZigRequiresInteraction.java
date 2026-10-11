package com.ziggfreed.common.objectives.interaction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;
import com.ziggfreed.common.objectives.gate.RequiresCheck;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * A condition over a {@code Requires} block, for the front of any interaction chain (a door, a lever, an
 * entrance block): {@code Next} runs for a player who meets it, {@code Failed} for one who does not.
 *
 * <pre>{@code
 * { "Type": "ZigRequires",
 *   "Requires": { "Quests": ["Yourpack_Intro"] },
 *   "Next": "Yourpack_Crypt_Door_Open" }
 * }</pre>
 *
 * <p>The branches are the native condition's ({@code GameFlagConditionInteraction}): Finished runs the
 * inherited {@code Next}, Failed runs the inherited {@code Failed}. The block is the shared one
 * ({@code Factors}, {@code Permission}, {@code Quests}, {@code Custom}, grouped with {@code AllOf},
 * {@code AnyOf} and {@code Not}), read for the runtime's own subject by the same evaluator a quest's gate
 * is ({@link RequiresCheck}); it only reads.
 *
 * <p><b>A refusal is a deliberate Failed branch</b>, and the player is told why on the spot: the lock line
 * naming the first thing they lack, shown where they are looking, the way a used item's payout is
 * ({@link InteractionRewards#toPlayer}). A chain no player owns, or a player the runtime cannot name yet,
 * takes the Failed branch silently. Deciding needs the server's answer, hence
 * {@link WaitForDataFrom#Server}.
 */
public final class ZigRequiresInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigRequires";

    @Nullable
    protected GateSpec requires;

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigRequiresInteraction> CODEC = BuilderCodec.builder(
                        ZigRequiresInteraction.class, ZigRequiresInteraction::new, SimpleInstantInteraction.CODEC)
                .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                        (i, v) -> i.requires = v, i -> i.requires, (i, p) -> i.requires = p.requires)
                .documentation("What a player must already have or have done for the chain to carry on into "
                        + "Next. A player who falls short is told what they lack and the chain takes its Failed "
                        + "branch instead. An unauthored block asks for nothing, so every player passes.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration invokes this. */
    @Nonnull
    public static BuilderCodec<ZigRequiresInteraction> codec() {
        return Holder.CODEC;
    }

    /** This Type resolves Failed on a refusal, so the client waits for the server's state. */
    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    /** Only the server decides; a simulated pass would tell the player their lock line twice. */
    @Override
    protected void simulateFirstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        // Server tick only.
    }

    @Override
    protected void firstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        InteractionOutcome.guard(ctx, TYPE_NAME, () -> {
            Ref<EntityStore> owner = InteractionCtx.owner(ctx);
            PlayerRef playerRef = InteractionCtx.player(ctx, owner);
            if (owner == null || playerRef == null) {
                SafeLog.fine("[interaction] " + TYPE_NAME + ": no player owns this chain, so it takes the"
                        + " Failed branch");
                return false;
            }
            Subject subject = RequiresCheck.playerSubject(owner.getStore(), owner);
            if (subject == null) {
                SafeLog.fine("[interaction] " + TYPE_NAME + ": the runtime cannot name this player yet, so the chain"
                        + " takes the Failed branch");
                return false;
            }
            if (RequiresCheck.passes(subject, requires)) {
                return true;
            }
            InteractionRewards.toPlayer(playerRef)
                    .accept(ToastSpec.of(ToastKind.ERROR, RequiresCheck.lockLine(subject, requires)));
            return false;
        });
    }
}
