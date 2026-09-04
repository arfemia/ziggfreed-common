package com.ziggfreed.common.encounter.run;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.support.StateSupport;

/**
 * What another mod asks about a live encounter, in one place: which run an entity carries, whether
 * an entity is a fight's bound subject (the question a mob-scaling companion asks before it rolls
 * its own tier onto a boss), what state the script is in, and the admin force-state.
 *
 * <p>World thread for anything that reads the store; {@link #isBoundSubject} is a lock-free index
 * read safe from a system tick.
 */
public final class EncounterRuntime {

    private EncounterRuntime() {
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
