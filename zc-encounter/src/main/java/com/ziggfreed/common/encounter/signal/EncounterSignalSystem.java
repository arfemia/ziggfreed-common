package com.ziggfreed.common.encounter.signal;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.worldevents.component.WorldEventSignal;
import com.hypixel.hytale.builtin.encountermanager.EncounterManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.event.ResetReason;
import com.ziggfreed.common.encounter.run.EncounterLifecycle;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;
import com.ziggfreed.common.util.SafeLog;

/**
 * The bridge from a script to this library: every {@code SignalWorldEvent} an encounter script
 * fires is an ECS event on the encounter's own entity, and the engine delivers it to every system
 * registered for that event class whose query the entity matches. This one is queried on the
 * encounter component, so it hears every script's beats and nothing else's, parses the reserved
 * {@code zc:} grammar and tells the run's story on.
 *
 * <p>Registered through {@code registerSystem}, which registers the event type on its own if
 * nothing has; the type is never registered directly, because a second registration throws.
 * Identity is read off the signalling entity's own component, never off the signal string.
 */
public final class EncounterSignalSystem extends EntityEventSystem<EntityStore, WorldEventSignal> {

    public EncounterSignalSystem() {
        super(WorldEventSignal.class);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return EncounterManager.getComponentType();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull WorldEventSignal event) {
        try {
            EncounterSignal signal = EncounterSignal.parse(event.signalId());
            if (signal == null) {
                return;
            }
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            String encounterId = EncounterRuns.encounterIdOn(store, ref);
            if (encounterId == null) {
                return;
            }
            EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(encounterId);
            if (row != null && !row.isEnabled()) {
                return;
            }
            ZigEncounterRun run = EncounterRuns.runOn(store, ref);
            if (run == null || run.isEnded()) {
                return;
            }
            run.bindWorld(EncounterLifecycle.worldUuid(store));
            dispatch(store, ref, run, encounterId, signal);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " handling the signal '" + event.signalId() + "' failed", t);
        }
    }

    private static void dispatch(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ZigEncounterRun run, @Nonnull String encounterId, @Nonnull EncounterSignal signal) {
        switch (signal.moment()) {
            case ENGAGED -> EncounterLifecycle.engage(store, ref, run, encounterId, "signal");
            case PHASE -> {
                if (signal.detail() == null) {
                    SafeLog.warn(Encounters.LOG_PREFIX + " '" + encounterId + "' signalled '" + signal.raw()
                            + "' with no state name after it, so no phase was recorded");
                    return;
                }
                if (!run.isEngaged()) {
                    EncounterLifecycle.engage(store, ref, run, encounterId, "phase before engaged");
                }
                EncounterLifecycle.phase(store, ref, run, encounterId, signal.detail());
            }
            case DEFEATED -> EncounterLifecycle.defeat(store, ref, run, encounterId, null, "signal");
            case RESET -> EncounterLifecycle.reset(store, ref, run, encounterId, ResetReason.RESET_SIGNAL, true);
            case WAVE, CUSTOM -> EncounterLifecycle.signal(store, ref, run, encounterId, signal);
        }
    }
}
