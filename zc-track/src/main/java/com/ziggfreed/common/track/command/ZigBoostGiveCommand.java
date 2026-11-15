package com.ziggfreed.common.track.command;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.boost.BoostAsset;
import com.ziggfreed.common.track.boost.BoostConfig;
import com.ziggfreed.common.track.boost.BoostText;
import com.ziggfreed.common.track.boost.BoostToken;
import com.ziggfreed.common.util.SafeLog;

/**
 * {@code give [--player=<name>] --boost=<id> [--percent=<n>] [--seconds=<n>] [--track=<id>] [--quantity=<n>]}: hand
 * an online player the library's boost token ({@value #TOKEN_ITEM}) carrying that boost and, in place of its own, the
 * numbers given ({@link BoostToken}), at most one stack's worth (the item file's {@code MaxStack}), the way any pickup
 * lands: the bag first, the rest at their feet. The token
 * starts the boost when they use it, every limit and wait applying then. Left out, the sender is the player. A
 * consumer with its own token item mints onto that one instead; this is the surface a server with no such mod has.
 */
final class ZigBoostGiveCommand extends AbstractTargetPlayerCommand<ZigBoostGiveCommand.Holder> {

    /** The library's own boost token item. */
    static final String TOKEN_ITEM = "Zig_Boost_Token";

    /** Where a hand-over's overflow says it came from. */
    private static final String SOURCE = "zigboost-give";

    /** The player the tokens go to, on their world thread. */
    record Holder(@Nonnull PlayerRef playerRef, @Nonnull Player player) {
    }

    private final OptionalArg<String> boostArg;
    private final OptionalArg<Double> percentArg;
    private final OptionalArg<Integer> secondsArg;
    private final OptionalArg<String> trackArg;
    private final OptionalArg<Integer> quantityArg;

    ZigBoostGiveCommand() {
        super(ZigBoostCommand.GIVE, BoostCommandMessages.desc(ZigBoostCommand.GIVE),
                BoostCommandMessages.desc("arg.player"), BoostCommandMessages::refused, false);
        this.boostArg = withOptionalArg("boost", BoostCommandMessages.desc("arg.boost"), ArgTypes.STRING);
        this.percentArg = withOptionalArg("percent", BoostCommandMessages.desc("arg.percent"), ArgTypes.DOUBLE);
        this.secondsArg = withOptionalArg("seconds", BoostCommandMessages.desc("arg.seconds"), ArgTypes.INTEGER);
        this.trackArg = withOptionalArg("track", BoostCommandMessages.desc("arg.track"), ArgTypes.STRING);
        this.quantityArg = withOptionalArg("quantity", BoostCommandMessages.desc("arg.quantity"), ArgTypes.INTEGER);
    }

    @Override
    protected Holder buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        Player player = store.getComponent(ref, Player.getComponentType());
        return player == null ? null : new Holder(playerRef, player);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Holder target) {
        String typed = boostArg.provided(ctx) ? boostArg.get(ctx) : null;
        if (typed == null || typed.isBlank()) {
            BoostCommandMessages.refused(ctx, "boost.needed");
            return;
        }
        BoostAsset boost = BoostConfig.getInstance().live(typed.trim());
        if (boost == null || boost.isStanding()) {
            BoostCommandMessages.refused(ctx, "give.unknown");
            return;
        }
        Double percent = percentArg.provided(ctx) ? percentArg.get(ctx) : null;
        Integer seconds = secondsArg.provided(ctx) ? secondsArg.get(ctx) : null;
        String track = trackArg.provided(ctx) ? trackArg.get(ctx) : null;
        int asked = quantityArg.provided(ctx) && quantityArg.get(ctx) != null ? quantityArg.get(ctx) : 1;
        BoostToken token = new BoostToken(boost.getId(), percent,
                seconds == null ? null : (long) Math.max(0, seconds), track);
        // At most one stack's worth of the token item, as its item file allows.
        int quantity = Math.max(1, Math.min(asked, maxStackOf(BoostToken.mint(TOKEN_ITEM, token, 1))));
        ItemStack stack = BoostToken.mint(TOKEN_ITEM, token, quantity);
        Subject subject = Subject.of(target.playerRef(), target.player());
        int landed = 0;
        if (subject != null) {
            for (int count : LootRewardKinds.handToPlayer(subject, SOURCE, SafeLog::warn).build()
                    .spill(List.of(stack)).values()) {
                landed += count;
            }
        }
        if (landed <= 0) {
            BoostCommandMessages.refused(ctx, "give.refused", target.playerRef().getUsername());
            return;
        }
        BoostCommandMessages.done(ctx, "give.done", landed, BoostText.name(boost),
                target.playerRef().getUsername());
    }

    /** How many of {@code stack}'s item one stack holds, as its item file says; 1 when it cannot be read. */
    private static int maxStackOf(@Nonnull ItemStack stack) {
        try {
            Item item = stack.getItem();
            return item == null ? 1 : Math.max(1, item.getMaxStack());
        } catch (Throwable unreadable) {
            return 1;
        }
    }
}
