package com.ziggfreed.common.objectives.interaction;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.util.SafeLog;

/**
 * Pays rewards to the player whose chain this is, from inside any native interaction chain (the prize
 * for cracking something open, say):
 *
 * <pre>{@code
 * { "Type": "ZigGrantReward",
 *   "Rewards": [ { "Kind": "Lootable", "Params": { "Lootable": "Yourpack_Geode" } } ],
 *   "Chance": 0.5 }
 * }</pre>
 *
 * <p>The {@code Rewards} entries are the very shape a quest pays in, handed to the same registered
 * kinds through the same grant pass, retry queue and subject a quest payout uses
 * ({@link InteractionRewards}), so a kind another mod registers pays here the day it is installed.
 * The player is then shown what the use handed over ({@link InteractionRewards#payAndShow}): a rolled
 * table as what it actually paid, in the page they have open or else the corner feed.
 *
 * <p><b>Always resolves Finished.</b> Nothing authored, a lost roll and a chain no player owns are
 * skips: a crack still uses up what it cracked.
 */
public final class ZigGrantRewardInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigGrantReward";

    @Nullable
    protected RewardEntryAsset[] rewards;
    /** Boxed, so an absent leaf keeps the default instead of failing the decode. */
    @Nullable
    protected Float chance;

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigGrantRewardInteraction> CODEC = BuilderCodec.builder(
                        ZigGrantRewardInteraction.class, ZigGrantRewardInteraction::new,
                        SimpleInstantInteraction.CODEC)
                .appendInherited(new KeyedCodec<>("Rewards",
                                new ArrayCodec<>(RewardEntryAsset.CODEC, RewardEntryAsset[]::new)),
                        (i, v) -> i.rewards = v, i -> i.rewards, (i, p) -> i.rewards = p.rewards)
                .documentation("What one use pays the player whose chain this is, in the entry shape a "
                        + "quest pays in: a Kind plus its Params. One leaf: a child that authors it "
                        + "replaces the parent's list whole.")
                .add()
                .appendInherited(new KeyedCodec<>("Chance", Codec.FLOAT),
                        (i, v) -> i.chance = v, i -> i.chance, (i, p) -> i.chance = p.chance)
                .metadata(EditorSchema.defaultValue(1.0))
                .documentation("The chance from 0 to 1 that a use pays at all, rolled once per use. "
                        + "Absent, every use pays.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration invokes this. */
    @Nonnull
    public static BuilderCodec<ZigGrantRewardInteraction> getCODEC() {
        return Holder.CODEC;
    }

    /** Paying happens on the server tick alone, or a simulated pass would pay twice. */
    @Override
    protected void simulateFirstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        // Server tick only.
    }

    @Override
    protected void firstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        InteractionOutcome.guard(ctx, TYPE_NAME, () -> {
            List<RewardSpec> specs = InteractionRewards.specs(rewards);
            if (specs.isEmpty() || !InteractionRewards.rolls(chance, ThreadLocalRandom.current()::nextDouble)) {
                return true;
            }
            Ref<EntityStore> owner = InteractionCtx.owner(ctx);
            PlayerRef playerRef = InteractionCtx.player(ctx, owner);
            if (owner == null || playerRef == null) {
                SafeLog.fine("[interaction] " + TYPE_NAME + ": no player owns this chain, so nothing is paid");
                return true;
            }
            InteractionRewards.payAndShow(specs, InteractionRewards.subjectFor(owner.getStore(), owner, playerRef),
                    InteractionRewards.sourceId(UsedItems.id(ctx), TYPE_NAME), RewardKinds.shared(),
                    ProgressionRuntime.rewardRetryQueue(), InteractionRewards.chips(),
                    InteractionRewards.toPlayer(playerRef));
            return true;
        });
    }
}
