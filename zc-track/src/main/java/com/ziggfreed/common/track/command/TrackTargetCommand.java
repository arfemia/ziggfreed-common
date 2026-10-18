package com.ziggfreed.common.track.command;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackService;

/**
 * The half every {@code /zigtrack} verb that moves a climb shares: an online player (a climb moves only while
 * its player is here), the {@code --track} it names, and the refusal wording.
 */
abstract class TrackTargetCommand extends AbstractTargetPlayerCommand<TrackTarget> {

    private final OptionalArg<String> trackArg;

    TrackTargetCommand(@Nonnull String verb, boolean typedName) {
        super(verb, TrackCommandMessages.desc(verb), TrackCommandMessages.desc(typedName ? "arg.player.any"
                : "arg.player"), TrackCommandMessages::refused, typedName);
        this.trackArg = withOptionalArg("track", TrackCommandMessages.desc("arg.track"), ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected TrackTarget buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        return new TrackTarget(playerRef.getUuid(), playerRef.getUsername());
    }

    /** The {@code --track} as typed and trimmed, or null when none was. */
    @Nullable
    protected final String typedTrack(@Nonnull CommandContext ctx) {
        String typed = trackArg.provided(ctx) ? trackArg.get(ctx) : null;
        return typed == null || typed.isBlank() ? null : typed.trim();
    }

    /** The usable track {@code --track} names, or null after refusing (none named, or no such track here). */
    @Nullable
    protected final TrackDef namedTrack(@Nonnull CommandContext ctx) {
        String typed = typedTrack(ctx);
        if (typed == null) {
            TrackCommandMessages.refused(ctx, "track.needed");
            return null;
        }
        TrackDef def = TrackService.def(typed);
        if (def == null) {
            TrackCommandMessages.refused(ctx, "track.unknown", typed);
        }
        return def;
    }
}
