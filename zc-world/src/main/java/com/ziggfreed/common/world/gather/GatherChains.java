package com.ziggfreed.common.world.gather;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.util.SafeLog;

/**
 * Each player's break chain, which the {@code GatherRhythm} stat reads: a break within the gather rules' rhythm window
 * ({@link GatherRulesAsset#rhythmWindowMs}) of the last one adds a step, up to their step cap
 * ({@link GatherRulesAsset#rhythmMaxSteps}), and a longer pause starts the chain again. In memory only (a chain lasts
 * seconds, so a restart costs nothing), forgotten when the player leaves and pruned as it grows. Thread-safe; the
 * clock and the rules are passed in, so the rule is pure to test.
 */
public final class GatherChains {

    /** Past this many players held, chains older than the window are swept. */
    private static final int SWEEP_AT = 512;

    private static final Map<UUID, long[]> CHAINS = new ConcurrentHashMap<>();

    private GatherChains() {
    }

    /** {@code playerId} broke a block at {@code nowMs}: the chain steps on, or starts again after a pause. */
    public static void broke(@Nullable UUID playerId, long nowMs) {
        if (playerId == null) {
            return;
        }
        GatherRulesAsset rules = GatherRulesConfig.getInstance().rules();
        long window = rules.rhythmWindowMs();
        int max = rules.rhythmMaxSteps();
        CHAINS.compute(playerId, (id, chain) -> next(chain, nowMs, window, max));
        if (CHAINS.size() > SWEEP_AT) {
            CHAINS.values().removeIf(chain -> nowMs - chain[0] > window);
        }
    }

    /** The steps {@code playerId}'s chain counts at {@code nowMs}: 0 once the window since their last break passed. */
    public static int steps(@Nullable UUID playerId, long nowMs) {
        long[] chain = playerId == null ? null : CHAINS.get(playerId);
        if (chain == null) {
            return 0;
        }
        GatherRulesAsset rules = GatherRulesConfig.getInstance().rules();
        return steps(chain, nowMs, rules.rhythmWindowMs(), rules.rhythmMaxSteps());
    }

    /** Forget a player's chain (they left). */
    public static void clear(@Nullable UUID playerId) {
        if (playerId != null) {
            CHAINS.remove(playerId);
        }
    }

    /** A player left the server: their chain goes. */
    public static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef player = event.getPlayerRef();
            if (player != null) {
                clear(player.getUuid());
            }
        } catch (Throwable t) {
            SafeLog.fine("[gather] a departing player's break chain could not be forgotten: " + t.getMessage());
        }
    }

    /** The chain {@code {lastBreakMs, steps}} after a break at {@code nowMs}, under a window and step cap. Pure. */
    @Nonnull
    static long[] next(@Nullable long[] chain, long nowMs, long windowMs, int maxSteps) {
        if (chain == null || nowMs - chain[0] > windowMs) {
            return new long[] {nowMs, 0L};
        }
        return new long[] {nowMs, Math.min(maxSteps, chain[1] + 1)};
    }

    /** The steps a chain counts at {@code nowMs}, under a window and step cap. Pure. */
    static int steps(@Nullable long[] chain, long nowMs, long windowMs, int maxSteps) {
        if (chain == null || nowMs - chain[0] > windowMs) {
            return 0;
        }
        return (int) Math.min(maxSteps, chain[1]);
    }
}
