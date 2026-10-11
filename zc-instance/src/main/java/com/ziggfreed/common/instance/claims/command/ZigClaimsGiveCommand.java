package com.ziggfreed.common.instance.claims.command;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.instance.claims.ClaimsText;
import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootFactors;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * {@code give --player=<name> --lootable=<id>}: roll a loot table for a player and put what it lands in their
 * Claims, as a boss's payout would, so the Claims tab can be seen and tested on any server. The table is decided
 * here, once, the way Kweebec Nightmare decides a round's payout ({@code LootEngine.select}, then
 * {@code DeferredRewards}), and the Claim pays exactly that when it is taken. It rolls as a won run with no score
 * ({@code LootFactors.lookupFor(0, true)}), so a table gated on a win pays its floor. A roll that lands nothing
 * writes no Claim. The source is {@code admin:<lootable>}, and every give is a Claim of its own.
 *
 * <p>{@code --player} names a player online or not ({@link ClaimTargets}); one who is not online finds the Claim
 * waiting, and the notice, at their next login. Left out, the sender is the player.
 */
final class ZigClaimsGiveCommand extends AbstractTargetPlayerCommand<ZigClaimsGiveCommand.Target> {

    /** What a give's source and its Claim's id start with. */
    static final String SOURCE_PREFIX = "admin:";

    /** The player a give was pointed at: whose Claims, and the name the answers speak (data, never translated). */
    record Target(@Nonnull UUID id, @Nonnull String name) {
    }

    private final OptionalArg<String> lootableArg;

    ZigClaimsGiveCommand() {
        super(ZigClaimsCommand.GIVE, ClaimsCommandMessages.desc(ZigClaimsCommand.GIVE),
                ClaimsCommandMessages.desc("arg.player"), ClaimsCommandMessages::refused, true);
        this.lootableArg = withOptionalArg("lootable", ClaimsCommandMessages.desc("arg.lootable"), ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected Target buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull PlayerRef playerRef) {
        return new Target(playerRef.getUuid(), playerRef.getUsername());
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        give(ctx, target);
    }

    /** A typed name: an online player takes the shared walk to their world, anyone else is given to from here. */
    @Override
    @Nonnull
    protected CompletableFuture<Void> executeNamed(@Nonnull CommandContext ctx, @Nonnull String typed) {
        return ClaimTargets.resolve(typed).thenCompose(named -> {
            if (named == null) {
                ClaimsCommandMessages.refused(ctx, "player.unknown", typed);
                return CompletableFuture.completedFuture(null);
            }
            Ref<EntityStore> online = PlayerAccess.liveRef(named.id());
            if (online != null) {
                return actOn(ctx, online);
            }
            give(ctx, new Target(named.id(), named.name()));
            return CompletableFuture.completedFuture(null);
        });
    }

    /** Roll the named table for {@code target} and award what it lands as one Claim. */
    private void give(@Nonnull CommandContext ctx, @Nonnull Target target) {
        String lootable = lootableArg.provided(ctx) ? lootableArg.get(ctx) : null;
        if (lootable == null || lootable.isBlank()) {
            ClaimsCommandMessages.refused(ctx, "lootable.needed");
            return;
        }
        String tableId = lootable.trim();
        LootableAsset table = LootableConfig.getInstance().resolve(tableId);
        if (table == null) {
            ClaimsCommandMessages.refused(ctx, "lootable.unknown", tableId);
            return;
        }
        long now = System.currentTimeMillis();
        String source = SOURCE_PREFIX + tableId;
        Claim claim = new Claim(source + ":" + target.id() + ":" + now, source,
                ClaimsText.PREFIX + "title.admin", List.of(tableId), null, roll(table, target.id(), source), now);
        if (ClaimStore.shared().award(target.id(), claim, null, source, now)) {
            ClaimsCommandMessages.done(ctx, "give.done", tableId, target.name(), claim.rewards().size());
        } else {
            ClaimsCommandMessages.detail(ctx, "give.nothing", tableId, target.name());
        }
    }

    /**
     * What one pass of {@code table} decides for {@code player}, as rewards kept for later: each command reward
     * keeps {@code {player}} for whoever claims it, and repeated picks of one item read as one reward.
     */
    @Nonnull
    private static List<InstanceReward> roll(@Nonnull LootableAsset table, @Nonnull UUID player,
            @Nonnull String source) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<LootEngine.Selected> decided = LootEngine.select(table.rollsOrEmpty(), table.poolOrEmpty(), null,
                LootFactors.lookupFor(0, true), random::nextDouble);
        return InstanceReward.merge(DeferredRewards.fromSelection(decided, RewardKinds.shared(),
                claimant(player), source, SafeLog::warn));
    }

    /** The subject a kept payout is decided for, named {@code {player}} so a command fills in its claimer. */
    @Nonnull
    private static Subject claimant(@Nonnull UUID player) {
        return Subject.of(player, "{player}");
    }
}
