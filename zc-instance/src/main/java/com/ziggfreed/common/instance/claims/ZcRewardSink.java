package com.ziggfreed.common.instance.claims;

import java.util.Map;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.instance.reward.InstanceRewardGranter;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's executor for a Claim's non-item rewards, lifted from Kweebec Nightmare's
 * {@code KweebecRewardSink}. The library keeps no currency of its own, so a currency reward answers false; item
 * rewards go through the granter's bag guard and never reach this sink.
 *
 * <p>A command reward runs as the server console with {@code {player}} filled from the claiming player's
 * username, read at claim time. It is authored content (a loot table's {@code Commands}, a reward kind's
 * replayable line), so it goes through zc-core's {@code CommandRunner}, which also fixes a {@code give} line's
 * count; the granter has already filled {@code {amount}}.
 */
public final class ZcRewardSink implements InstanceRewardGranter.Sink {

    public static final ZcRewardSink INSTANCE = new ZcRewardSink();

    private ZcRewardSink() {
    }

    @Override
    public boolean grantCurrency(@Nonnull String currencyId, int amount,
                                 @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        return false; // the library keeps no currency of its own
    }

    @Override
    public boolean runCommand(@Nonnull String command,
                              @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        PlayerRef player = PlayerAccess.playerRef(store, ref);
        if (player == null) {
            // No claimer to fill in, and a line carrying a raw {player} would run as garbage.
            return false;
        }
        return CommandRunner.run(command, Map.of("player", player.getUsername()),
                failure -> SafeLog.warn("[claims] " + failure));
    }
}
