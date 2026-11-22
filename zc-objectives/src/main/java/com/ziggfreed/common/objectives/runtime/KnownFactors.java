package com.ziggfreed.common.objectives.runtime;

import javax.annotation.Nullable;

import com.ziggfreed.common.encounter.run.EncounterFactors;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.npc.placement.registry.PlacementFactorRegistry;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.util.SafeLog;

/**
 * Does any factor vocabulary on this server provide a factor id? What zc-core's always-on derived-factor
 * audit asks of a formula term before calling it unknown ({@code DerivedFactorConfig.knownFactors}, installed
 * by {@link ProgressionBootstrap}): the progression runtime's vocabulary (the one a {@code Requires} block is
 * answered with, a consumer's when it registered one), the placement, loot and encounter vocabularies the
 * library reads elsewhere, and every id a mod contributed process-wide. This module sees them all; zc-core
 * cannot.
 *
 * <p>Read live, never cached: the fold-time log runs after every plugin's setup, and the boot audit later
 * still. A vocabulary that cannot be asked answers no, never a throw.
 */
public final class KnownFactors {

    private KnownFactors() {
    }

    /** True when a factor vocabulary on this server registers or a mod contributed {@code factorId}. */
    public static boolean isKnown(@Nullable String factorId) {
        if (factorId == null || factorId.isBlank()) {
            return false;
        }
        try {
            if (FactorContributions.isContributed(factorId)
                    || registers(ProgressionRuntime.factors(), factorId)
                    || PlacementFactorRegistry.isRegistered(factorId)
                    || registers(LootRewardKinds.installedFactors(), factorId)) {
                return true;
            }
            return registers(EncounterFactors.registry(), factorId);
        } catch (Throwable t) {
            SafeLog.fine("[factor] could not ask the factor vocabularies about '" + factorId + "': "
                    + t.getMessage());
            return false;
        }
    }

    private static boolean registers(@Nullable FactorRegistry registry, String factorId) {
        return registry != null && registry.isRegistered(factorId);
    }
}
