package com.ziggfreed.common.objectives.gate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceRequirement;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.objectives.interaction.InteractionRewards;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * A choice requirement over a {@code Requires} block, for the engine's choice pages (a shop's elements, any
 * page built on its choice base): the choice goes through only for a player who meets the block.
 *
 * <pre>{@code
 * "Requirements": [ { "Type": "ZigRequires",
 *                     "Requires": { "Quests": ["Yourpack_Intro"] } } ]
 * }</pre>
 *
 * <p>The choosing player is the subject, read as the runtime's own ({@link RequiresCheck#playerSubject}).
 * The engine's page asks only when the player picks the choice, and on a refusal shows nothing, so the player
 * is told why there and then: the lock line naming the first thing they lack ({@link RequiresCheck#refusal},
 * the wording every gate shares), as the {@code ZigRequires} interaction tells it. A player the runtime cannot
 * name yet is refused without a line.
 *
 * <p>Registered into the engine's choice requirement map at setup ({@link ZigRequiresBootstrap}).
 */
public final class ZigRequiresChoiceRequirement extends ChoiceRequirement {

    /** The authored {@code "Type"} name, the same word every {@code ZigRequires} uses. */
    public static final String TYPE_NAME = "ZigRequires";

    @Nullable
    private GateSpec requires;

    /** Lazy holder: only registration and a codec test build it. */
    private static final class Holder {
        static final BuilderCodec<ZigRequiresChoiceRequirement> CODEC = BuilderCodec.builder(
                        ZigRequiresChoiceRequirement.class, ZigRequiresChoiceRequirement::new,
                        ChoiceRequirement.BASE_CODEC)
                .documentation("Lets the choice through only for a player who meets a Requires block (the shape "
                        + "quests use: Factors, Permission, Quests, Custom, AllOf, AnyOf, Not). A player who falls "
                        + "short is told what they lack when they pick it.")
                .append(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                        (c, v) -> c.requires = v, c -> c.requires)
                .documentation("What the player must already have or have done to take this choice. An unauthored "
                        + "block asks for nothing, so every player may take it.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigRequiresChoiceRequirement> codec() {
        return Holder.CODEC;
    }

    /** The authored block, null when none was written. */
    @Nullable
    public GateSpec getRequires() {
        return requires;
    }

    @Override
    public boolean canFulfillRequirement(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        if (requires == null || requires.isEmpty()) {
            return true;
        }
        Subject subject = RequiresCheck.playerSubject(store, ref);
        if (subject == null) {
            return false;
        }
        Message refusal = RequiresCheck.refusal(subject, requires);
        if (refusal == null) {
            return true;
        }
        InteractionRewards.toPlayer(playerRef).accept(ToastSpec.of(ToastKind.ERROR, refusal));
        return false;
    }
}
