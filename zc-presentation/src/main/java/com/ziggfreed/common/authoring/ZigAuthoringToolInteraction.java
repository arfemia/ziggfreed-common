package com.ziggfreed.common.authoring;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.ziggfreed.common.interaction.type.InteractionOutcome;

/**
 * The authoring tool step: what every authoring tool runs on a click (through the root interaction
 * {@code Zig_Authoring_Tool} its base item names), from any native chain.
 *
 * <pre>{@code
 * { "Type": "ZigAuthoringTool" }                    the tool the held item's ZigAuthorTool tag names
 * { "Type": "ZigAuthoringTool", "Tool": "Quest" }   that tool, whatever is held
 * }</pre>
 *
 * <p>On the server it names the tool, checks the player is in creative mode and holds the tool's permission
 * ({@link AuthoringTools#refusal}: a refusal is told to the player and the chain goes on), reads what the click
 * points at ({@link WorldPick}) and, once the tick is done, either fills the pick a wizard is waiting on or opens the
 * tool's own wizard. Failed for a chain no player runs or an item that names no tool; deciding that needs the
 * server, hence {@link WaitForDataFrom#Server}.
 */
public final class ZigAuthoringToolInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigAuthoringTool";

    @Nullable protected String tool;

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigAuthoringToolInteraction> CODEC = BuilderCodec.builder(
                        ZigAuthoringToolInteraction.class, ZigAuthoringToolInteraction::new,
                        SimpleInstantInteraction.CODEC)
                .appendInherited(new KeyedCodec<>("Tool", Codec.STRING, false),
                        (i, v) -> i.tool = v, i -> i.tool, (i, p) -> i.tool = p.tool)
                .documentation("The authoring tool to use, by its tool id (Inspect, or one a mod registers). Leave it "
                        + "out on a tool item: the held item's ZigAuthorTool tag names it. The player needs creative "
                        + "mode and the ziggfreedcommon.author.<tool> permission, checked on the server.").add()
                .build();
    }

    /** The codec, built on first call. Only registration invokes this. */
    @Nonnull
    public static BuilderCodec<ZigAuthoringToolInteraction> codec() {
        return Holder.CODEC;
    }

    /** This Type can resolve Failed, so the client waits for the server's state. */
    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    /** Only the server checks, reads and opens. */
    @Override
    protected void simulateFirstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        // Server tick only.
    }

    @Override
    protected void firstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        InteractionOutcome.guard(ctx, TYPE_NAME, () -> AuthoringTools.click(ctx, tool));
    }
}
