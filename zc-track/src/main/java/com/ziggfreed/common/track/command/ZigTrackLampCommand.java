package com.ziggfreed.common.track.command;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.lamp.TrackLamp;
import com.ziggfreed.common.track.lamp.TrackLamps;

/**
 * {@code lamp [--player=<name>] [--price=<id>] [--minutes=<n>] [--level=<n>] [--maxlevel=<n>] [--picks=<n>]
 * [--tracks=<id,id>] [--category=<id>] [--minlevel=<n>] [--quantity=<n>]}: hand an online player lamps of those numbers
 * ({@link TrackLamp}, at most one stack), the way any pickup lands. A lamp needs a price row or minutes of its own.
 * The line a {@code Track_Lamp} reward's Claim replays where the reward kind itself cannot pay. Left out, the sender is
 * the player.
 */
final class ZigTrackLampCommand extends AbstractTargetPlayerCommand<ZigTrackLampCommand.Holder> {

    /** Where a hand-over's overflow says it came from. */
    private static final String SOURCE = "zigtrack-lamp";

    /** The player the lamps go to, on their world thread. */
    record Holder(@Nonnull PlayerRef playerRef, @Nonnull Player player) {
    }

    private final OptionalArg<String> priceArg;
    private final OptionalArg<Double> minutesArg;
    private final OptionalArg<Integer> levelArg;
    private final OptionalArg<Integer> maxLevelArg;
    private final OptionalArg<Integer> picksArg;
    private final OptionalArg<String> tracksArg;
    private final OptionalArg<String> categoryArg;
    private final OptionalArg<Integer> minLevelArg;
    private final OptionalArg<Integer> quantityArg;

    ZigTrackLampCommand() {
        super(ZigTrackCommand.LAMP, TrackCommandMessages.desc(ZigTrackCommand.LAMP),
                TrackCommandMessages.desc("arg.player"), TrackCommandMessages::refused, false);
        this.priceArg = withOptionalArg("price", TrackCommandMessages.desc("arg.price"), ArgTypes.STRING);
        this.minutesArg = withOptionalArg("minutes", TrackCommandMessages.desc("arg.minutes"), ArgTypes.DOUBLE);
        this.levelArg = withOptionalArg("level", TrackCommandMessages.desc("arg.price.level"), ArgTypes.INTEGER);
        this.maxLevelArg = withOptionalArg("maxlevel", TrackCommandMessages.desc("arg.maxlevel"), ArgTypes.INTEGER);
        this.picksArg = withOptionalArg("picks", TrackCommandMessages.desc("arg.picks"), ArgTypes.INTEGER);
        this.tracksArg = withOptionalArg("tracks", TrackCommandMessages.desc("arg.tracks"), ArgTypes.STRING);
        this.categoryArg = withOptionalArg("category", TrackCommandMessages.desc("arg.category"), ArgTypes.STRING);
        this.minLevelArg = withOptionalArg("minlevel", TrackCommandMessages.desc("arg.minlevel"), ArgTypes.INTEGER);
        this.quantityArg = withOptionalArg("quantity", TrackCommandMessages.desc("arg.quantity"), ArgTypes.INTEGER);
    }

    @Override
    protected Holder buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        Player player = store.getComponent(ref, Player.getComponentType());
        return player == null ? null : new Holder(playerRef, player);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Holder target) {
        List<String> tracks = new ArrayList<>();
        String typed = tracksArg.provided(ctx) ? tracksArg.get(ctx) : null;
        if (typed != null) {
            for (String id : typed.split(",")) {
                tracks.add(id);
            }
        }
        TrackLamp lamp = new TrackLamp(priceArg.provided(ctx) ? priceArg.get(ctx) : null,
                minutesArg.provided(ctx) ? minutesArg.get(ctx) : null,
                levelArg.provided(ctx) ? levelArg.get(ctx) : null,
                maxLevelArg.provided(ctx) ? maxLevelArg.get(ctx) : null,
                picksArg.provided(ctx) ? picksArg.get(ctx) : null, tracks,
                categoryArg.provided(ctx) ? categoryArg.get(ctx) : null,
                minLevelArg.provided(ctx) ? minLevelArg.get(ctx) : null);
        if (!lamp.pays()) {
            TrackCommandMessages.refused(ctx, "lamp.needed");
            return;
        }
        int asked = quantityArg.provided(ctx) && quantityArg.get(ctx) != null ? quantityArg.get(ctx) : 1;
        Subject subject = Subject.of(target.playerRef(), target.player());
        int landed = subject == null ? 0 : TrackLamps.give(subject, lamp, asked, SOURCE);
        if (landed <= 0) {
            TrackCommandMessages.refused(ctx, "lamp.refused", target.playerRef().getUsername());
            return;
        }
        TrackCommandMessages.done(ctx, "lamp.done", landed, target.playerRef().getUsername());
    }
}
