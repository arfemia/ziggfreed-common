package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;

import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.quest.RequiresGates;
import com.ziggfreed.common.util.SafeLog;

/**
 * THE requirement evaluator this server answers a {@code Requires} block with, and the ONE gate both
 * progression engines are registered with over it.
 *
 * <p><b>Why there is exactly one.</b> A gate is a decision, and two decisions over one requirement
 * model disagree the first time either is fixed. The failure is quiet and specific: a shop row
 * rendered from saved records and the same requirement on a quest read the same authored block and
 * answer differently, and nothing says so. So every surface that answers a {@code Requires} block -
 * both engines, the commerce engines, a consumer's own lock-reason wording - reads this one.
 *
 * <p><b>Nothing here is registered, which is what lets it exist this early.</b> The factor
 * vocabulary, the factor context and the requirement kinds are all read LIVE off
 * {@link ProgressionRuntime} at the moment a gate is evaluated, so a consumer registering its own
 * vocabulary long after this was first asked for feeds this same instance. A surface asking during
 * another mod's setup and one asking in play are on the same evaluator, and neither call built it a
 * second time.
 */
public final class ProgressionGates {

    private static volatile GateEvaluator evaluator;

    private static volatile RequiresGates gates;

    private ProgressionGates() {
    }

    /** The one evaluator, built on first ask. */
    @Nonnull
    public static GateEvaluator evaluator() {
        GateEvaluator local = evaluator;
        if (local == null) {
            synchronized (ProgressionGates.class) {
                local = evaluator;
                if (local == null) {
                    local = build();
                    evaluator = local;
                }
            }
        }
        return local;
    }

    /** The one gate over it, which both engines are registered with. */
    @Nonnull
    public static RequiresGates gates() {
        RequiresGates local = gates;
        if (local == null) {
            synchronized (ProgressionGates.class) {
                local = gates;
                if (local == null) {
                    local = RequiresGates.of(evaluator());
                    gates = local;
                }
            }
        }
        return local;
    }

    /**
     * A {@code Requires} block's {@code Permission} leaf is a {@code hytale:permission} factor bound,
     * so the vocabulary and the context below are the whole of what answers it.
     */
    @Nonnull
    private static GateEvaluator build() {
        GateEvaluator gate = GateEvaluator.builder()
                .factorsLive(ProgressionRuntime::factors)
                .factorContext(ProgressionRuntime::gateFactorContext)
                .gateKindsLive(ProgressionRuntime::gateKinds)
                .warn(msg -> SafeLog.warn("[gate] " + msg))
                .build();
        gate.completedQuests(RequiresGates.completionProbe(ProgressionRuntime.questStore()));
        return gate;
    }

    /** Forget both, so a test starting from a clean runtime is not holding one built over the last. */
    public static synchronized void resetForTests() {
        evaluator = null;
        gates = null;
    }
}
