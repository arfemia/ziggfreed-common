package com.ziggfreed.common.encounter.run;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.seam.EncounterSeams;
import com.ziggfreed.common.encounter.wave.WaveRules;
import com.ziggfreed.common.util.SafeLog;

/**
 * A fight's endless wave run, as this module keeps it: the rules the seam answers for it
 * ({@link EncounterSeams#waveRulesAt}), asked once the script reads an endless reading or beats a wave clear and
 * asked again about once a second, so the fight halts the moment its run ends (the seam answers nothing for it
 * again), and the wave standing now, which every endless reading and the ramp on its adds read.
 *
 * <p>The wave standing is the run's own count of {@code zc:cleared} beats plus one: during a breather it is the next
 * wave to rise. World thread only.
 */
public final class EncounterWaves {

    /** How often a fight that runs endless asks the seam again, in milliseconds. */
    static final long ASK_EVERY_MS = 1_000L;

    private EncounterWaves() {
    }

    /**
     * Ask the seam again for {@code run}'s rules when the script has asked for them and a second has passed: bind what
     * it answers, or halt a run it answered before and answers nothing for now (its dungeon run ended). Never throws.
     */
    public static void refresh(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef,
            @Nonnull ZigEncounterRun run, @Nonnull String encounterId, long nowMs) {
        if (!run.endlessWanted() || run.isEndlessHalted() || nowMs - run.endlessAskedAtMs() < ASK_EVERY_MS) {
            return;
        }
        run.noteEndlessAsked(nowMs);
        try {
            TransformComponent at = store.getComponent(encounterRef, TransformComponent.getComponentType());
            if (at == null) {
                return;
            }
            UUID world = run.worldUuid() != null ? run.worldUuid() : EncounterLifecycle.worldUuid(store);
            WaveRules rules = EncounterSeams.waveRulesAt(world, new Vector3d(at.getPosition()));
            if (rules != null) {
                boolean first = !run.wasEndless();
                run.bindEndless(rules);
                if (first) {
                    SafeLog.info(Encounters.LOG_PREFIX + " endless run=" + EncounterRun.shortId(run.runId())
                            + " encounter=" + encounterId + " runs endless waves" + describe(rules));
                }
            } else if (run.wasEndless() && run.haltEndless()) {
                SafeLog.info(Encounters.LOG_PREFIX + " endless run=" + EncounterRun.shortId(run.runId())
                        + " encounter=" + encounterId + " halted after " + run.wavesCleared()
                        + " cleared waves: its run ended");
            }
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " the endless run of '" + encounterId + "' could not be read", t);
        }
    }

    /** The wave standing in {@code run} now (the next to rise in a breather), or null when it runs no endless waves. */
    @Nullable
    public static WaveRules.Wave standing(@Nonnull ZigEncounterRun run) {
        WaveRules rules = run.endless();
        return rules == null ? null : rules.at(run.wavesCleared() + 1);
    }

    @Nonnull
    private static String describe(@Nonnull WaveRules rules) {
        return (rules.bossEvery() == null ? "" : ", a boss every " + rules.bossEvery()) + ", bands " + rules.bandSlots()
                + " of " + rules.bandWaves() + " waves, up to size " + rules.sizeMax()
                + (rules.ramp().grows() ? ", ramped to x" + rules.ramp().cap() : ", no ramp");
    }
}
