package com.ziggfreed.common.track.boost;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;
import com.ziggfreed.common.track.asset.TrackAsset;

/**
 * A boost token: starts a boost for the player using it, as a native interaction Type an item's chain names.
 *
 * <pre>{@code
 * "Interactions": { "Secondary": { "Interactions": [ {
 *     "Type": "ZigBoost", "Boost": "Yourpack_Harvest_Boost",
 *     "Next": { "Type": "ModifyInventory", "AdjustHeldItemQuantity": -1 } } ] } }
 * }</pre>
 *
 * <p>{@code Percent} and {@code Seconds} start it at a percent or a length of its own instead of the boost's, and
 * {@code Track} points it at one track of those the boost lifts. A stack carrying its own numbers
 * ({@link BoostToken}, minted by a command or a reward) starts what the stack says, so one item stands for a token of
 * any size. A personal boost lifts the user; a server boost lifts everyone, and counts against the server's limit.
 *
 * <p><b>Resolves Failed whenever the boost did not start</b> (a wait not over, a limit reached, out of season, a
 * requirement not met, no such boost here), telling the player why, so the chain's {@code Next} (the consume, as
 * vanilla's {@code LearnRecipe} chains it) runs only for a boost that started and a refused token is kept. Deciding
 * that needs the server's answer, hence {@link WaitForDataFrom#Server}.
 */
public final class ZigBoostInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigBoost";

    @Nullable protected String boost;
    @Nullable protected Double percent;
    @Nullable protected Long seconds;
    @Nullable protected String track;

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigBoostInteraction> CODEC = BuilderCodec.builder(
                        ZigBoostInteraction.class, ZigBoostInteraction::new, SimpleInstantInteraction.CODEC)
                .appendInherited(new KeyedCodec<>("Boost", Codec.STRING, false),
                        (i, v) -> i.boost = v, i -> i.boost, (i, p) -> i.boost = p.boost)
                .metadata(EditorSchema.assetRef(BoostAsset.class))
                .documentation("The boost it starts, by id (a file under Server/ZiggfreedCommon/Boosts). A boost "
                        + "this server does not have starts nothing, and the chain resolves Failed.").add()
                .appendInherited(new KeyedCodec<>("Percent", Codec.DOUBLE, false),
                        (i, v) -> i.percent = v, i -> i.percent, (i, p) -> i.percent = p.percent)
                .documentation("How much this token's boost lifts by, as a percent, in place of the boost's own. "
                        + "Leave it out for the boost's own.").add()
                .appendInherited(new KeyedCodec<>("Seconds", Codec.LONG, false),
                        (i, v) -> i.seconds = v, i -> i.seconds, (i, p) -> i.seconds = p.seconds)
                .documentation("How long this token's boost runs, in seconds, in place of the boost's own. Leave it "
                        + "out for the boost's own.").add()
                .appendInherited(new KeyedCodec<>("Track", Codec.STRING, false),
                        (i, v) -> i.track = v, i -> i.track, (i, p) -> i.track = p.track)
                .metadata(EditorSchema.assetRef(TrackAsset.class))
                .documentation("The one track this token's boost lifts, out of those the boost lifts (a token for "
                        + "one skill of a boost that lifts every skill). Leave it out for all of them.").add()
                .build();
    }

    /** The codec, built on first call. Only registration invokes this. */
    @Nonnull
    public static BuilderCodec<ZigBoostInteraction> getCODEC() {
        return Holder.CODEC;
    }

    /** This Type can resolve Failed, so the client waits for the server's state. */
    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    /** Only the server starts a boost; a simulated pass would start it twice. */
    @Override
    protected void simulateFirstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        // Server tick only.
    }

    @Override
    protected void firstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        InteractionOutcome.guard(ctx, TYPE_NAME, () -> {
            Ref<EntityStore> user = InteractionCtx.owner(ctx);
            PlayerRef player = InteractionCtx.player(ctx, user);
            if (player == null) {
                return false;
            }
            BoostToken held = BoostToken.of(ctx.getHeldItem());
            Boosts.Start start = held == null
                    ? Boosts.start(player.getUuid(), boost, percent, seconds, track, player.getUsername())
                    : Boosts.start(player.getUuid(), held.boost(), held.percent(), held.seconds(), held.track(),
                            player.getUsername());
            Message refusal = BoostText.refusal(start);
            if (refusal != null) {
                player.sendMessage(refusal);
            }
            return start.started();
        });
    }
}
