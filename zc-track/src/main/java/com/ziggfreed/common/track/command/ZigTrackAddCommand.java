package com.ziggfreed.common.track.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.track.TrackChange;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.price.TrackPricing;

/**
 * {@code add [--player=<name>] --track=<id> --amount=<n> [--exact]}: move an online player's climb by {@code n}
 * (negative to take some away), through the one write path a reward takes ({@code TrackService.add}): the gain seam,
 * the cap, the unlock Claims, the mirror and the change event all apply; {@code --exact} pays the amount past every
 * gain factor that scales it ({@code TrackService.addExact}), as a reward priced in minutes pays. In place of
 * {@code --amount}, {@code --price=<id>}, {@code --minutes=<n>}, {@code --level=<n>} and {@code --maxlevel=<n>} price
 * minutes of play at the player's level on the track now and pay them exactly. The line a Track reward's Claim replays
 * where the reward kind itself cannot pay. Left out, the sender is the player.
 */
final class ZigTrackAddCommand extends TrackTargetCommand {

    /** What an add moves by when it names its source. */
    static final String SOURCE = "admin";

    private final OptionalArg<Double> amountArg;
    private final FlagArg exactArg;
    private final OptionalArg<String> priceArg;
    private final OptionalArg<Double> minutesArg;
    private final OptionalArg<Integer> levelArg;
    private final OptionalArg<Integer> maxLevelArg;

    ZigTrackAddCommand() {
        super(ZigTrackCommand.ADD, false);
        this.amountArg = withOptionalArg("amount", TrackCommandMessages.desc("arg.amount"), ArgTypes.DOUBLE);
        this.exactArg = withFlagArg("exact", TrackCommandMessages.desc("arg.exact"));
        this.priceArg = withOptionalArg(TrackPricing.PARAM_PRICE, TrackCommandMessages.desc("arg.price"),
                ArgTypes.STRING);
        this.minutesArg = withOptionalArg(TrackPricing.PARAM_MINUTES, TrackCommandMessages.desc("arg.minutes"),
                ArgTypes.DOUBLE);
        this.levelArg = withOptionalArg(TrackPricing.PARAM_LEVEL, TrackCommandMessages.desc("arg.price.level"),
                ArgTypes.INTEGER);
        this.maxLevelArg = withOptionalArg(TrackPricing.PARAM_MAX_LEVEL, TrackCommandMessages.desc("arg.maxlevel"),
                ArgTypes.INTEGER);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        TrackDef def = namedTrack(ctx);
        if (def == null) {
            return;
        }
        boolean priced = priceArg.provided(ctx) || minutesArg.provided(ctx);
        Double amount;
        if (priced) {
            long paid = TrackPricing.priceFor(target.id(), def.asset().getId(), TrackPricing.resolve(
                    priceArg.provided(ctx) ? priceArg.get(ctx) : null,
                    minutesArg.provided(ctx) ? minutesArg.get(ctx) : null,
                    levelArg.provided(ctx) ? levelArg.get(ctx) : null,
                    maxLevelArg.provided(ctx) ? maxLevelArg.get(ctx) : null));
            if (paid == TrackPricing.UNPRICED) {
                TrackCommandMessages.refused(ctx, "player.offline");
                return;
            }
            if (paid <= 0L) {
                TrackCommandMessages.refused(ctx, "add.price.nothing", TrackText.name(def.asset()));
                return;
            }
            amount = (double) paid;
        } else {
            amount = amountArg.provided(ctx) ? amountArg.get(ctx) : null;
        }
        if (amount == null || !Double.isFinite(amount) || amount == 0.0) {
            TrackCommandMessages.refused(ctx, "amount.needed");
            return;
        }
        TrackKey key = TrackKey.player(target.id(), def.asset().getId());
        TrackService.Result result = priced || exactArg.provided(ctx) ? TrackService.addExact(key, amount, SOURCE)
                : TrackService.add(key, amount, SOURCE);
        TrackChange change = result.change();
        switch (result.status()) {
            // A derived track's gain lands on one of its sources, so the line names the track that moved.
            case CHANGED -> TrackCommandMessages.done(ctx, "add.done",
                    TrackText.name(change == null ? def.asset() : change.def().asset()), target.name(),
                    change == null ? 0L : change.delta(), change == null ? 1 : change.levelAfter(),
                    change == null ? 0 : change.claims());
            case UNCHANGED -> TrackCommandMessages.detail(ctx, "add.unchanged", TrackText.name(def.asset()),
                    target.name());
            case LOCKED -> TrackCommandMessages.refused(ctx, "add.locked", TrackText.name(def.asset()), target.name());
            case NOT_LIVE -> TrackCommandMessages.refused(ctx, "player.offline");
            case DERIVED_SUM -> TrackCommandMessages.refused(ctx, "add.sums", TrackText.name(def.asset()));
            default -> TrackCommandMessages.refused(ctx, "track.unknown", def.asset().getId());
        }
    }
}
