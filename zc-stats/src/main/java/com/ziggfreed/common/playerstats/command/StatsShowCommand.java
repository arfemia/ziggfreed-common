package com.ziggfreed.common.playerstats.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.playerstats.PlayerStats;
import com.ziggfreed.common.playerstats.StatsSwitch;
import com.ziggfreed.common.playerstats.StatsView;

/**
 * {@code show [--player=<name>]}: every lifetime tally the player's stat record holds, sorted by key, as
 * the keys a {@code ziggfreedcommon:counter} condition would name, then every season-year copy
 * ({@code <event>@<year>/<key>}). Keys and names are printed raw on purpose; a count binds as a number the
 * reader's client groups.
 */
final class StatsShowCommand extends AbstractTargetPlayerCommand<StatsShowCommand.Target> {

    /** The player the verb was pointed at, resolved on their own world thread. */
    record Target(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef) {
    }

    StatsShowCommand() {
        super("show", StatsCommandMessages.desc("show"), StatsCommandMessages.desc("arg.player"),
                StatsCommandMessages::refused);
    }

    @Override
    @Nonnull
    protected Target buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        return new Target(store, ref, playerRef);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        String name = target.playerRef().getUsername();
        if (!StatsSwitch.isOn()) {
            StatsCommandMessages.refused(ctx, "show.off");
            return;
        }
        StatsView view = PlayerStats.view(target.store(), target.ref());
        if (view.isEmpty()) {
            StatsCommandMessages.detail(ctx, "show.none", name);
            return;
        }
        Map<String, Long> tallies = view.all();
        Map<String, Long> seasons = view.seasons();
        StatsCommandMessages.heading(ctx, "show.header", name, tallies.size() + seasons.size());
        rows(ctx, tallies);
        rows(ctx, seasons);
    }

    /** One row per tally in {@code tallies}, sorted by key. */
    private static void rows(@Nonnull CommandContext ctx, @Nonnull Map<String, Long> tallies) {
        List<String> keys = new ArrayList<>(tallies.keySet());
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        for (String key : keys) {
            StatsCommandMessages.detail(ctx, "show.row", key, tallies.get(key));
        }
    }
}
