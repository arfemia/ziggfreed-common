package com.ziggfreed.common.track.command;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.pick.PickService;

/** What the saved-build verbs share: the {@code --slot} they name and the wording of a refusal. */
final class BuildCommands {

    private BuildCommands() {
    }

    /** The {@code --slot} as typed, or null after refusing (none named). */
    @Nullable
    static Integer slot(@Nonnull CommandContext ctx, @Nonnull OptionalArg<Integer> slotArg) {
        Integer slot = slotArg.provided(ctx) ? slotArg.get(ctx) : null;
        if (slot == null) {
            TrackCommandMessages.refused(ctx, "build.slot.needed");
        }
        return slot;
    }

    /** Say why a save or a swap was refused. */
    static void refused(@Nonnull CommandContext ctx, @Nonnull PickService.Status status, @Nonnull TrackDef def,
            @Nonnull TrackTarget target) {
        switch (status) {
            case NOT_LIVE -> TrackCommandMessages.refused(ctx, "player.offline");
            case NOTHING -> TrackCommandMessages.detail(ctx, "build.in_use", TrackText.name(def.asset()), target.name());
            case NO_LOADOUT -> TrackCommandMessages.refused(ctx, def.loadoutsMax() > 0 ? "build.no_slot" : "build.none",
                    TrackText.name(def.asset()), def.loadoutsMax());
            default -> TrackCommandMessages.refused(ctx, "build." + status.name().toLowerCase(Locale.ROOT),
                    TrackText.name(def.asset()), target.name());
        }
    }
}
