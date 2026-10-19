package com.ziggfreed.common.reputation;

import javax.annotation.Nonnull;

/**
 * The production service: the engine seam over {@code ReputationPlugin}, the engine fan-out, and the ledger
 * that keeps each standing on its reputation's track with the engine's value a copy kept in step. Every seam
 * ships filled here, so nothing has to wire them. Building it touches no engine class.
 */
public final class ReputationRuntime {

    private static final ReputationService SERVICE = service(new EngineReputationNative());

    private ReputationRuntime() {
    }

    @Nonnull
    public static ReputationService service() {
        return SERVICE;
    }

    @Nonnull
    private static ReputationService service(@Nonnull ReputationNative engine) {
        return new ReputationService(engine, new EngineReputationFanOut(), new TrackReputationLedger(engine));
    }
}
