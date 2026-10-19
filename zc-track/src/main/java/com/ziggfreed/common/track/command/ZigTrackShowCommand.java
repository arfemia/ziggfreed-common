package com.ziggfreed.common.track.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackReading;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.pick.PickService;

/**
 * {@code show [--player=<name or id>] [--track=<id>]}: read a player's climbs, every usable track or the one
 * named. An online player is read live; anyone else, named by their player id, is read from their saved file
 * ({@code TrackService.load}), the offline read every track reading has. Left out, the sender is the player.
 */
final class ZigTrackShowCommand extends TrackTargetCommand {

    ZigTrackShowCommand() {
        super(ZigTrackCommand.SHOW, true);
    }

    /** An online player by exact name (any case), else a player id read offline, else an online partial match. */
    @Override
    @Nonnull
    protected CompletableFuture<Void> executeNamed(@Nonnull CommandContext ctx, @Nonnull String typed) {
        PlayerRef exact = online(typed, NameMatching.EXACT_IGNORE_CASE);
        if (exact != null) {
            return actOn(ctx, exact.getReference());
        }
        UUID id = uuidOf(typed);
        if (id != null) {
            return showOffline(ctx, new TrackTarget(id, typed));
        }
        PlayerRef partial = online(typed, NameMatching.DEFAULT);
        if (partial == null) {
            TrackCommandMessages.refused(ctx, "player.unknown", typed);
            return CompletableFuture.completedFuture(null);
        }
        return actOn(ctx, partial.getReference());
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        List<TrackReading> readings = new ArrayList<>();
        for (TrackDef def : shown(ctx)) {
            TrackReading reading = TrackService.read(TrackKey.player(target.id(), def.asset().getId()));
            if (reading != null) {
                readings.add(reading);
            }
        }
        answer(ctx, target, readings);
        // An online player's picks waiting, on each track shown: the next few slots and their offers.
        for (TrackReading reading : readings) {
            showWaiting(ctx, target, reading.def());
        }
    }

    /** The picks {@code target} has waiting on {@code def}, the first few slots with their offers. */
    private static void showWaiting(@Nonnull CommandContext ctx, @Nonnull TrackTarget target,
            @Nonnull TrackDef def) {
        PickService.Waiting waiting = PickService.waiting(target.id(), def.id());
        if (waiting.isEmpty()) {
            return;
        }
        TrackCommandMessages.detail(ctx, "show.waiting", TrackText.name(def.asset()), waiting.count());
        int shown = 0;
        for (PickService.Owed owed : waiting.owed()) {
            if (shown++ >= SHOWN_SLOTS) {
                break;
            }
            if (owed.isChoice()) {
                TrackCommandMessages.detail(ctx, "show.offer", owed.level(), names(owed.offer()), owed.slot());
            } else {
                TrackCommandMessages.detail(ctx, "show.free", TrackText.pickName(owed.pickId()), owed.pickId());
            }
        }
        if (waiting.pointsLeft() > 0) {
            TrackCommandMessages.detail(ctx, "show.points", waiting.pointsLeft());
        }
    }

    /** {@code ids}' names, comma-joined as one composite message. */
    @Nonnull
    private static Message names(@Nonnull List<String> ids) {
        List<Message> parts = new ArrayList<>();
        for (String id : ids) {
            if (!parts.isEmpty()) {
                parts.add(Msg.raw(", "));
            }
            parts.add(Msg.cat(TrackText.pickName(id), Msg.raw(" (" + id + ")")));
        }
        return Msg.cat(parts.toArray(new Message[0]));
    }

    /** How many waiting slots a show lists per track. */
    private static final int SHOWN_SLOTS = 3;

    /** Every shown track read from the player's saved file, answered once all are read. */
    @Nonnull
    private CompletableFuture<Void> showOffline(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        List<CompletableFuture<TrackReading>> loads = new ArrayList<>();
        for (TrackDef def : shown(ctx)) {
            loads.add(TrackService.load(TrackKey.player(target.id(), def.asset().getId())));
        }
        return CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).thenRun(() -> {
            List<TrackReading> readings = new ArrayList<>();
            for (CompletableFuture<TrackReading> load : loads) {
                TrackReading reading = load.join();
                if (reading != null) {
                    readings.add(reading);
                }
            }
            answer(ctx, target, readings);
        });
    }

    /** The tracks this line asks about: the one {@code --track} names, else every usable one. */
    @Nonnull
    private List<TrackDef> shown(@Nonnull CommandContext ctx) {
        String typed = typedTrack(ctx);
        if (typed == null) {
            return TrackService.defs();
        }
        TrackDef def = TrackService.def(typed);
        return def == null ? List.of() : List.of(def);
    }

    private static void answer(@Nonnull CommandContext ctx, @Nonnull TrackTarget target,
            @Nonnull List<TrackReading> readings) {
        if (readings.isEmpty()) {
            TrackCommandMessages.detail(ctx, "show.none", target.name());
            return;
        }
        TrackCommandMessages.detail(ctx, "show.head", target.name());
        for (TrackReading reading : readings) {
            TrackCommandMessages.detail(ctx, "show.line", TrackText.name(reading.def().asset()), reading.level(),
                    reading.best(), reading.prestige(), reading.value());
        }
    }

    @Nullable
    private static PlayerRef online(@Nonnull String typed, @Nonnull NameMatching matching) {
        try {
            Universe universe = Universe.get();
            return universe == null ? null : universe.getPlayerByUsername(typed, matching);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static UUID uuidOf(@Nonnull String typed) {
        try {
            return UUID.fromString(typed.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
