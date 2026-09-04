package com.ziggfreed.common.encounter.payout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.run.EncounterLifecycle;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.encounter.run.EncounterSubjects;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;
import com.ziggfreed.common.worldmap.WorldMapMarkers;

/**
 * The world-map marker a fight puts over its subject: placed at the engage, moved every
 * {@code FollowSeconds} by the tick, removed at the reset. Nothing here is a scheduler; the tick
 * asks {@link #follow} and this decides whether it is time.
 */
public final class EncounterDiscovery {

    private EncounterDiscovery() {
    }

    /** The marker id for {@code run}. */
    @Nonnull
    public static String markerId(@Nonnull ZigEncounterRun run) {
        return "zc_encounter_" + EncounterRun.shortId(run.runId());
    }

    /** Place the marker at the engage when the row asks for one. */
    public static void onEngaged(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef,
            @Nonnull ZigEncounterRun run, @Nullable EncounterBindingAsset row, long nowMs) {
        EncounterBindingAsset.Discovery discovery = row == null ? null : row.getDiscovery();
        if (discovery == null || !discovery.mapMarker()) {
            return;
        }
        place(store, encounterRef, run, row, discovery, nowMs);
    }

    /** Move the marker to the subject when {@code FollowSeconds} have passed since it last moved. */
    public static void follow(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef,
            @Nonnull ZigEncounterRun run, @Nullable EncounterBindingAsset row, long nowMs) {
        EncounterBindingAsset.Discovery discovery = row == null ? null : row.getDiscovery();
        if (discovery == null || !discovery.mapMarker() || !run.isMarkerPlaced() || discovery.followSeconds() <= 0) {
            return;
        }
        if (nowMs - run.markerMovedAtMs() < discovery.followSeconds() * 1000L) {
            return;
        }
        place(store, encounterRef, run, row, discovery, nowMs);
    }

    /** Take the marker down when the run ends. */
    public static void onReset(@Nonnull Store<EntityStore> store, @Nonnull ZigEncounterRun run) {
        if (!run.isMarkerPlaced()) {
            return;
        }
        World world = EncounterLifecycle.worldOf(store);
        if (world != null) {
            WorldMapMarkers.remove(world, markerId(run));
        }
        run.clearMarker();
    }

    private static void place(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef,
            @Nonnull ZigEncounterRun run, @Nullable EncounterBindingAsset row,
            @Nonnull EncounterBindingAsset.Discovery discovery, long nowMs) {
        World world = EncounterLifecycle.worldOf(store);
        if (world == null) {
            return;
        }
        Ref<EntityStore> subject = EncounterSubjects.resolve(store, encounterRef, row == null ? null : row.getSubject(),
                row != null);
        TransformComponent at = EncounterLifecycle.anchorOf(store, encounterRef, subject);
        if (at == null) {
            return;
        }
        String encounterId = row == null ? markerId(run) : row.encounterAsset();
        boolean placed = WorldMapMarkers.place(world, markerId(run), at.getPosition().x, at.getPosition().y,
                at.getPosition().z, discovery.markerIcon(), EncounterLifecycle.titleOf(encounterId, row));
        if (placed) {
            run.noteMarker(nowMs);
        }
    }
}
