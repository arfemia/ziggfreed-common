package com.ziggfreed.common.reputation;

import javax.annotation.Nonnull;

/**
 * The production service: the engine seam over {@code ReputationPlugin} and the engine fan-out. Both seams
 * ship filled here, so nothing has to wire them. Building it touches no engine class.
 */
public final class ReputationRuntime {

    private static final ReputationService SERVICE =
            new ReputationService(new EngineReputationNative(), new EngineReputationFanOut());

    private ReputationRuntime() {
    }

    @Nonnull
    public static ReputationService service() {
        return SERVICE;
    }
}
