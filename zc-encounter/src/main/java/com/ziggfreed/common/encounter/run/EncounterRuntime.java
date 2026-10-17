package com.ziggfreed.common.encounter.run;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;

/**
 * What another mod asks about a live encounter, in one place: which run an entity carries, whether
 * an entity is a fight's bound subject (the question a mob-scaling companion asks before it rolls
 * its own tier onto a boss), what a run's binding row lets a companion scale on that subject
 * ({@link #companionScale}), what state the script is in, and the admin force-state.
 *
 * <p>World thread for anything that reads the store; {@link #isBoundSubject} is a lock-free index
 * read safe from a system tick.
 */
public final class EncounterRuntime {

    /**
     * What a live run's binding row lets a companion mod scale on its subject ({@code Scale.Companion}),
     * with the subject as the fight holds it right now. Only ever handed out with at least one switch on.
     *
     * @param runId       the run
     * @param encounterId the native script id
     * @param subject     the subject's current reference (an in-place role change reissues it, so a holder
     *                    keeps the run id and asks again rather than keeping this)
     * @param health      whether the row lets a companion grow the subject's maximum health
     * @param damage      whether the row lets a companion make the subject's hits harder
     * @param engaged     whether the fight is on and not yet settled: a companion scales an engaged fight's
     *                    subject, and leaves one still waiting for its engage alone
     */
    public record CompanionScale(@Nonnull UUID runId, @Nonnull String encounterId, @Nonnull Ref<EntityStore> subject,
            boolean health, boolean damage, boolean engaged) {
    }

    private EncounterRuntime() {
    }

    /**
     * What run {@code runId}'s row lets a companion scale on its subject, the subject read through the
     * script's own slot; null when the run is not live in {@code store}, its row is switched off or writes
     * no switch on, or no subject is bound. World thread.
     */
    @Nullable
    public static CompanionScale companionScale(@Nonnull Store<EntityStore> store, @Nonnull UUID runId) {
        EncounterRuns.Live live = EncounterRuns.live(runId);
        if (live == null || !live.encounterRef().isValid() || live.encounterRef().getStore() != store) {
            return null;
        }
        EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(live.encounterId());
        EncounterBindingAsset.Companion companion = companionOf(row);
        if (companion == null || live.run().isEnded()) {
            return null;
        }
        Ref<EntityStore> subject = EncounterSubjects.resolve(store, live.encounterRef(), row.getSubject(), true);
        UUID bound = live.run().subjectUuid();
        if (subject == null || (bound != null && !bound.equals(EncounterSubjects.uuidOf(store, subject)))) {
            return null;
        }
        return of(live, subject, companion);
    }

    /**
     * {@link #companionScale(Store, UUID)} asked from the subject's side: what the live run whose bound subject
     * {@code subjectRef} is lets a companion scale on it. Matched by the entity's uuid against every live run's
     * bound subject, so a reference the index has not learnt yet (a re-added holder, a reloaded boss) still
     * answers; null when it is no live run's subject or its row lets nothing be scaled. World thread.
     */
    @Nullable
    public static CompanionScale companionScaleOf(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> subjectRef) {
        if (!subjectRef.isValid()) {
            return null;
        }
        UUID uuid = EncounterSubjects.uuidOf(store, subjectRef);
        if (uuid == null) {
            return null;
        }
        for (EncounterRuns.Live live : EncounterRuns.allLive()) {
            if (!uuid.equals(live.run().subjectUuid()) || live.run().isEnded()
                    || !live.encounterRef().isValid() || live.encounterRef().getStore() != store) {
                continue;
            }
            EncounterBindingAsset.Companion companion =
                    companionOf(EncounterBindingConfig.getInstance().forEncounter(live.encounterId()));
            return companion == null ? null : of(live, subjectRef, companion);
        }
        return null;
    }

    /** The row's companion switches when the row is on and turns at least one on, else null. */
    @Nullable
    private static EncounterBindingAsset.Companion companionOf(@Nullable EncounterBindingAsset row) {
        if (row == null || !row.isEnabled() || row.getScale() == null) {
            return null;
        }
        EncounterBindingAsset.Companion companion = row.getScale().getCompanion();
        return companion == null || !companion.any() ? null : companion;
    }

    @Nonnull
    private static CompanionScale of(@Nonnull EncounterRuns.Live live, @Nonnull Ref<EntityStore> subject,
            @Nonnull EncounterBindingAsset.Companion companion) {
        ZigEncounterRun run = live.run();
        return new CompanionScale(run.runId(), live.encounterId(), subject, companion.health(), companion.damage(),
                run.isEngaged() && !run.isConcluded());
    }

    /** A snapshot of the run on {@code encounterRef}, or null when it carries none. */
    @Nullable
    public static EncounterRun runOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef) {
        ZigEncounterRun run = EncounterRuns.runOn(store, encounterRef);
        String encounterId = EncounterRuns.encounterIdOn(store, encounterRef);
        if (run == null || encounterId == null) {
            return null;
        }
        return EncounterRun.of(run, encounterId);
    }

    /** True when {@code ref} is the bound subject of a live run, as of the last encounter tick. */
    public static boolean isBoundSubject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return ref.isValid() && EncounterRuns.isBoundSubject(ref);
    }

    /** The script's composite state name ({@code State.SubState}) on {@code encounterRef}, or null. */
    @Nullable
    public static String state(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef) {
        if (!encounterRef.isValid()) {
            return null;
        }
        try {
            StateSupport support = store.getComponent(encounterRef, StateSupport.getComponentType());
            return support == null ? null : support.getStateName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Force the script into {@code state} (and {@code subState}, or the script's default when null).
     * A state the script does not declare is refused by the engine with one warning.
     *
     * @return whether the encounter carried a state machine to set
     */
    public static boolean setState(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef,
            @Nonnull String state, @Nullable String subState) {
        if (!encounterRef.isValid()) {
            return false;
        }
        StateSupport support = store.getComponent(encounterRef, StateSupport.getComponentType());
        if (support == null) {
            return false;
        }
        support.setState(encounterRef, state, subState, store);
        return true;
    }
}
