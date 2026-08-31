package com.ziggfreed.common.world.placed;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.util.SafeLog;

/**
 * Wires the placed-block ledger at plugin {@code setup()}. One phase, called once from
 * {@code ZiggfreedCommonPlugin.setup()}, which stays the one authority on call ORDER.
 */
public final class PlacedBlockBootstrap {

    private PlacedBlockBootstrap() {
    }

    /**
     * Wire the placed-block ledger: the ONE writer into it, plus the saved placements from the last
     * run. The ledger is the library's answer to "did the breaker put that there themselves", read
     * by this library's own producers and by every consumer's XP path, so common owns both the
     * recording system and the file rather than asking a consumer to register another mod's
     * plumbing.
     *
     * <p>The system registers at {@code setup()} because an ECS system is a setup-time
     * registration, and the load happens here so the first break after a restart is answered from
     * the same ledger the last one was.
     *
     * <p>The two halves are guarded SEPARATELY on purpose. Shared under one try, a failed recorder
     * registration would skip the load as well, and the ledger would then answer "nobody placed
     * that" for every position saved by the last run - the exploit the ledger exists to close,
     * silently reopened for one boot by a failure in the other half.
     */
    public static void setupPlacedBlockLedger(@Nonnull PluginBase plugin) {
        // The chunk component FIRST, and before any world loads: it is where a placement is
        // recorded, so a recorder registered without it would remember nothing.
        try {
            PlacedBlockSection.register(plugin.getChunkStoreRegistry());
        } catch (Throwable t) {
            SafeLog.warn("[placed] the placed-block chunk component could not be registered:"
                    + " nothing will be remembered this boot, so place-then-break pays out", t);
        }
        try {
            plugin.getEntityStoreRegistry().registerSystem(new PlacedBlockRecorder());
        } catch (Throwable t) {
            SafeLog.warn("[placed] the placed-block recorder could not be registered: nothing will"
                    + " be remembered this boot, so place-then-break pays out", t);
        }
        try {
            PlacedBlockLedger.getInstance().retireLegacyFile();
        } catch (Throwable t) {
            SafeLog.warn("[placed] the retired placements file could not be renamed aside", t);
        }
    }
}
