package com.ziggfreed.common.encounter.run;

import java.time.Duration;
import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.util.SafeLog;

/**
 * The rest between fights, read and written: the world clock a rest is measured on, the rest an
 * encounter entity carries, whether the site has rested, and the stamp a defeat writes from the
 * binding row's {@code Timing.Rest}.
 *
 * <p>World thread only. The stamp goes through the caller's command buffer, because a defeat is
 * decided inside a system (the death latch, the signal bridge) and the store refuses a structural
 * change from there; the component lands when the buffer flushes, before the next tick reads it.
 */
public final class EncounterRest {

    private EncounterRest() {
    }

    /** The world's game time, or null when the store has no clock to read. */
    @Nullable
    public static Instant gameTime(@Nonnull Store<EntityStore> store) {
        try {
            WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
            return time == null ? null : time.getGameTime();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The rest on {@code encounterRef}, or null when it carries none (or the type failed to register). */
    @Nullable
    public static ZigEncounterRest restOn(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> encounterRef) {
        if (ZigEncounterRest.TYPE == null || encounterRef == null || !encounterRef.isValid()) {
            return null;
        }
        return accessor.getComponent(encounterRef, ZigEncounterRest.TYPE);
    }

    /**
     * Whether the site {@code encounterRef} stands on has rested: no rest stamped, or the world
     * clock at or past it. A world with no clock cannot rest, so it answers rested.
     */
    public static boolean isRested(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> encounterRef) {
        ZigEncounterRest rest = restOn(store, encounterRef);
        if (rest == null) {
            return true;
        }
        Instant now = gameTime(store);
        return now == null || rest.isRested(now);
    }

    /**
     * Stamp the rest the row owes at a defeat: {@code restUntil = gameTime + Timing.Rest}, written
     * on the encounter entity through {@code buffer}. Nothing is written when the row authors no
     * rest, when the type failed to register, or when the world has no clock; each says why once.
     */
    public static void stamp(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer,
            @Nonnull Ref<EntityStore> encounterRef, @Nonnull ZigEncounterRun run, @Nonnull String encounterId,
            @Nullable EncounterBindingAsset row) {
        Duration rest = row == null || row.getTiming() == null ? null : row.getTiming().rest();
        if (rest == null || rest.isZero() || rest.isNegative()) {
            return;
        }
        if (ZigEncounterRest.TYPE == null || !encounterRef.isValid()) {
            SafeLog.warn(Encounters.LOG_PREFIX + " rest run=" + EncounterRun.shortId(run.runId()) + " encounter="
                    + encounterId + " could not be stamped: the rest component is not registered or the entity is gone");
            return;
        }
        Instant now = gameTime(store);
        if (now == null) {
            SafeLog.warn(Encounters.LOG_PREFIX + " rest run=" + EncounterRun.shortId(run.runId()) + " encounter="
                    + encounterId + " could not be stamped: this world has no game clock");
            return;
        }
        Instant until = now.plus(rest);
        buffer.putComponent(encounterRef, ZigEncounterRest.TYPE, ZigEncounterRest.until(until));
        SafeLog.info(Encounters.LOG_PREFIX + " rest run=" + EncounterRun.shortId(run.runId()) + " encounter="
                + encounterId + " until=" + until + " (" + rest + " of world time)");
    }
}
