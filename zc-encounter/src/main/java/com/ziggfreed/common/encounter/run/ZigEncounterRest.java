package com.ziggfreed.common.encounter.run;

import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.util.SafeLog;

/**
 * The rest between fights, on the ENCOUNTER ENTITY: the world-clock instant before which the site
 * raises no boss, stamped at a defeat from the binding row's {@code Timing.Rest} and saved with
 * the chunk the entity sleeps in.
 *
 * <p><b>Registered WITH a codec, unlike the run.</b> The engine persists nothing of a script but
 * its id and its rebind slots, so every reload restarts the script at its start state with fresh
 * timers; a rest that lived in the script, or on a manually triggered spawn marker (which never
 * reads its own respawn gate), would be gone on the next chunk load. This component is the one
 * thing about a fight that must outlive a reload, and it rides the entity so two placed copies of
 * one fight rest independently and a world without the fight writes nothing.
 *
 * <p>Measured on {@code WorldTimeResource}'s game time, the same clock the engine's own automatic
 * spawn markers gate on, so a game day is a game day however the server has been up. A stamp is
 * never cleared: once the clock is past it the site reads as rested, and the next defeat writes
 * over it.
 */
public final class ZigEncounterRest implements Component<EntityStore> {

    /** The registered type; null when registration failed, and every reader guards on that. */
    @Nullable
    public static ComponentType<EntityStore, ZigEncounterRest> TYPE;

    /** The registration id (namespaced, stable). */
    public static final String REGISTRY_ID = "ZiggfreedCommon:EncounterRest";

    @Nonnull
    public static final BuilderCodec<ZigEncounterRest> CODEC = BuilderCodec
            .builder(ZigEncounterRest.class, ZigEncounterRest::new)
            .append(new KeyedCodec<>("RestUntil", Codec.INSTANT), (c, v) -> c.restUntil = v, c -> c.restUntil)
            .documentation("The world-clock instant the rest ends at; absent means the site has never rested.")
            .add()
            .build();

    @Nullable private Instant restUntil;

    public ZigEncounterRest() {
    }

    /** A rest ending at {@code restUntil} on the world clock. */
    @Nonnull
    public static ZigEncounterRest until(@Nonnull Instant restUntil) {
        ZigEncounterRest rest = new ZigEncounterRest();
        rest.restUntil = restUntil;
        return rest;
    }

    /**
     * Register the component type with the entity-store registry, codec-backed so a chunk save
     * carries it. Called once at library setup, before any world loads. Never throws: a failure
     * logs and leaves {@link #TYPE} unset, and every reader guards on that.
     */
    @Nullable
    public static ComponentType<EntityStore, ZigEncounterRest> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(ZigEncounterRest.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " could not register ZigEncounterRest", t);
            return null;
        }
    }

    /** The world-clock instant the rest ends at, or null when nothing was ever stamped. */
    @Nullable
    public Instant restUntil() {
        return restUntil;
    }

    /** True when the site may raise its boss again: nothing stamped, or {@code gameTime} at or past it. */
    public boolean isRested(@Nonnull Instant gameTime) {
        return restUntil == null || !gameTime.isBefore(restUntil);
    }

    /** Whole world-clock seconds of rest left at {@code gameTime}; zero once rested. */
    public long secondsLeft(@Nonnull Instant gameTime) {
        if (restUntil == null || !gameTime.isBefore(restUntil)) {
            return 0L;
        }
        return Math.max(0L, restUntil.getEpochSecond() - gameTime.getEpochSecond());
    }

    /**
     * The one question a sensor asks, answered for an entity that may carry no rest at all: a
     * site with nothing stamped has rested, and a site whose stamp the clock has passed has too.
     */
    public static boolean rested(@Nullable ZigEncounterRest rest, @Nonnull Instant gameTime) {
        return rest == null || rest.isRested(gameTime);
    }

    /** A copied entity keeps the rest: the site it stands on has still not rested. */
    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        ZigEncounterRest copy = new ZigEncounterRest();
        copy.restUntil = restUntil;
        return copy;
    }
}
