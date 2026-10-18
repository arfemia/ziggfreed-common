package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;

import com.ziggfreed.common.progress.gate.GateEpochs;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateRuntime;
import com.ziggfreed.common.quest.LockReasons;
import com.ziggfreed.common.quest.RequiresGates;

/**
 * THE requirement evaluator this server answers a {@code Requires} block with, as the shared progression
 * runtime fills it, and the ONE gate both progression engines are registered with over it.
 *
 * <p><b>Why there is exactly one.</b> A gate is a decision, and two decisions over one requirement
 * model disagree the first time either is fixed. The failure is quiet and specific: a shop row
 * rendered from saved records and the same requirement on a quest read the same authored block and
 * answer differently, and nothing says so. So every surface that answers a {@code Requires} block -
 * both engines, the commerce engines, a loot table, a consumer's own lock-reason wording - reads this one,
 * which is zc-core's {@link GateRuntime#evaluator()}: this class fills its seams with the runtime's live
 * factor vocabulary, factor context and gate kinds and the quest completion probe, and wires this module's
 * refusal lines ({@link LockReasons#install}).
 *
 * <p><b>Nothing here is registered, which is what lets it exist this early.</b> The factor
 * vocabulary, the factor context and the requirement kinds are all read LIVE off
 * {@link ProgressionRuntime} at the moment a gate is evaluated, so a consumer registering its own
 * vocabulary long after this was first asked for feeds this same instance. A surface asking during
 * another mod's setup and one asking in play are on the same evaluator, and neither call built it a
 * second time.
 */
public final class ProgressionGates {

    private static volatile boolean installed;

    private static volatile RequiresGates gates;

    private ProgressionGates() {
    }

    /** The one evaluator, with this runtime's parts in its seams. */
    @Nonnull
    public static GateEvaluator evaluator() {
        install();
        return GateRuntime.evaluator();
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
     * Fill zc-core's gate seams with this runtime's parts, once. A {@code Requires} block's {@code Permission}
     * leaf is a {@code hytale:permission} factor bound, so the vocabulary and the context are the whole of
     * what answers it; a {@code Quests} leaf asks the quest store.
     */
    public static void install() {
        if (installed) {
            return;
        }
        synchronized (ProgressionGates.class) {
            if (installed) {
                return;
            }
            GateRuntime.install(ProgressionRuntime::factors, ProgressionRuntime::gateFactorContext,
                    ProgressionRuntime::gateKinds, RequiresGates.completionProbe(ProgressionRuntime.questStore()));
            LockReasons.install();
            installed = true;
        }
    }

    /**
     * A moment of a player's own moves their gate epoch, so a verdict kept over their {@code Player} readings
     * is read again: the listener the library's defaults register.
     */
    public static final MomentListener EPOCHS = moment -> {
        if (moment.questSubject() != null) {
            GateEpochs.bumpPlayer(moment.questSubject().id());
        }
        if (moment.achievementSubject() != null) {
            GateEpochs.bumpPlayer(moment.achievementSubject().id());
        }
    };

    /** Forget both, and zc-core's evaluator, so a test starting from a clean runtime holds nothing built over the last. */
    public static synchronized void resetForTests() {
        installed = false;
        gates = null;
        GateRuntime.resetForTests();
    }
}
