package com.ziggfreed.common.instance.dungeon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.core.util.PrefabUtil;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.ziggfreed.common.instance.world.CopyKey;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.TickingSections;

/**
 * Each dungeon area's prefab: pasted when a party is given the area, while its first player sits on the loading
 * screen, and pasted afresh for the next party once every player has left it.
 *
 * <p><b>A paste.</b> The prefab buffer loads off the world thread ({@code PrefabBufferUtil.getCached}). On the
 * copy's thread every chunk section the prefab covers is woken through zc-world's {@code TickingSections}: on
 * Update 7 a section loads asleep, and an entity added into a sleeping section is parked out of the world
 * until a player comes near, so a wake keeps the pasted entities live and brings back any the last party left
 * parked there. The region then loads ({@code PrefabUtil.loadPasteRegionAsync}, since a cell in a section
 * that is not in memory is skipped), and the paste runs as one world task with {@code FORCE}, WITH the
 * prefab's entities and block states: the prefab carries its fight's native encounter spawn marker, so each
 * fresh paste stages a fresh fight. The runtime paste never expands a {@code Prefab_Spawner_Block}.
 *
 * <p><b>A used area</b> is cleared in the same world task, before the paste: every entity in its cell except
 * players, found through {@code TargetUtil.getAllEntitiesInBox} (the list copied, since it is the engine's
 * thread-local one) and, because that spatial query never lists an {@code Intangible} entity (spawn markers,
 * dropped items and projectiles all carry it), through a second sweep of the store's intangible entities;
 * each removed with {@code RemoveReason.REMOVE}. The {@code FORCE} re-paste rewrites only the cells the prefab
 * lists, so a used area pastes back clean because no player changes a block there: zc's dungeon gameplay
 * config refuses placing, breaking and gathering. A template naming another gameplay config owns that rule.
 *
 * <p><b>Measured.</b> Every paste logs one INFO line under {@code [dungeon]}: its two stalls of the world
 * thread (the wake task, and the clear and paste task), each against the {@link #PASTE_BUDGET_MS} budget past
 * which a paste is to be sliced across ticks; how many entities and block states it placed; how many chunk
 * columns and sections its region holds and whether it loaded whole; and, for a used area, how many entities
 * the clear removed through the spatial query and how many intangible ones only the sweep found, and whether
 * its region was still in memory since the last paste (the source says a pasted chunk never unloads before its
 * world closes while the world saves nothing).
 *
 * <p>Threading: {@link #prepare} and {@link #released} may be called from any thread; every world read and
 * write here runs on the copy's own thread. Nothing here blocks or joins.
 */
public final class Areas {

    /** The time one paste may hold the world thread, in milliseconds, before it is to be sliced across ticks. */
    public static final double PASTE_BUDGET_MS = 5.0;

    private static final String LOG = "[dungeon] ";
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private static final Areas SHARED = new Areas();

    /** Lower-cased prefab ids already reported as reaching below the world's floor, so each is said once. */
    private static final Set<String> CUT_PREFABS = ConcurrentHashMap.newKeySet();

    /** The library's one area keeper. */
    @Nonnull
    public static Areas shared() {
        return SHARED;
    }

    /** What an area holds now: the world its prefab was pasted into, and that paste. */
    private static final class Slot {
        final World world;
        final CompletableFuture<Void> ready;
        /** A party has had the area and every player has left it, or its paste failed: paste afresh. */
        boolean used;

        Slot(World world, CompletableFuture<Void> ready) {
            this.world = world;
            this.ready = ready;
        }
    }

    /** What one paste did, for its log line. */
    private static final class Measure {
        long requestedAt;
        boolean reused;
        Boolean stillInMemory;
        int sectionsWoken;
        int sectionsNotWoken;
        int columns;
        int sections;
        boolean fullyLoaded;
        double wakeTaskMs;
        double loadMs;
        double clearMs;
        int removedTangible;
        int removedIntangible;
        double pasteMs;
        final AtomicInteger entitiesPlaced = new AtomicInteger();
        final AtomicInteger blockStatesPlaced = new AtomicInteger();
    }

    /** Each area's current paste. Guarded by {@code this}. */
    private final Map<CopyKey, Slot> slots = new HashMap<>();

    private Areas() {
    }

    /**
     * Make {@code area} of {@code world} ready for an arriving player: paste its prefab when the area is fresh,
     * clear and paste it afresh when a party used it before, or hand back the paste already made (or under
     * way) for the party now holding it. Completes once the paste is done, exceptionally when it fails.
     */
    @Nonnull
    public CompletableFuture<Void> prepare(@Nonnull World world, @Nonnull CopyKey area, @Nonnull AreaLayout layout,
            @Nonnull String prefabId, @Nonnull Path prefab) {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        CompletableFuture<Void> previous;
        boolean reused;
        synchronized (this) {
            Slot slot = slots.get(area);
            if (slot != null && slot.world == world && !slot.used) {
                return slot.ready;
            }
            reused = slot != null && slot.world == world;
            previous = reused ? slot.ready : CompletableFuture.completedFuture(null);
            slots.put(area, new Slot(world, ready));
        }
        previous.handle((ignored, error) -> null)
                .thenCompose(ignored -> paste(world, area, layout, prefabId, prefab, reused))
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        ready.complete(null);
                        return;
                    }
                    SafeLog.warn(LOG + "the prefab " + prefabId + " could not be pasted into " + world.getName()
                            + " area " + area.area(), error);
                    markUsed(area, ready);
                    ready.completeExceptionally(error);
                });
        return ready;
    }

    /**
     * Every player has left {@code area} of {@code world}: the next party it is given finds it cleared and pasted
     * afresh. Copy names repeat, so a paste into a newer copy of the same name is left alone; a null world (the
     * copy never came up for the player who left) marks whatever paste the area holds.
     */
    public synchronized void released(@Nullable World world, @Nonnull CopyKey area) {
        Slot slot = slots.get(area);
        if (slot != null && (world == null || slot.world == world)) {
            slot.used = true;
        }
    }

    /** Forget every area of a copy that has closed, so its world is let go. */
    synchronized void prune() {
        slots.values().removeIf(slot -> !slot.world.isAlive());
    }

    /** A failed paste leaves its area to be cleared and pasted again by the next party, if it is still current. */
    private synchronized void markUsed(@Nonnull CopyKey area, @Nonnull CompletableFuture<Void> ready) {
        Slot slot = slots.get(area);
        if (slot != null && slot.ready == ready) {
            slot.used = true;
        }
    }

    // ==================== one paste ====================

    @Nonnull
    private CompletableFuture<Void> paste(@Nonnull World world, @Nonnull CopyKey area, @Nonnull AreaLayout layout,
            @Nonnull String prefabId, @Nonnull Path prefab, boolean reused) {
        Measure measure = new Measure();
        measure.requestedAt = System.nanoTime();
        measure.reused = reused;
        Vector3i origin = layout.origin(area.area());
        return CompletableFuture.supplyAsync(() -> PrefabBufferUtil.getCached(prefab))
                .thenCompose(buffer -> CompletableFuture.supplyAsync(() -> wake(buffer, world, origin, measure,
                                prefabId), world)
                        .thenCompose(woken -> woken)
                        .thenCompose(ignored -> PrefabUtil.loadPasteRegionAsync(buffer, world, origin,
                                PrefabRotation.ROTATION_0, GetChunkFlags.HIGH_PRIORITY))
                        .thenAcceptAsync(region -> pasteNow(buffer, world, area, layout, origin, region, measure,
                                prefabId), world));
    }

    /**
     * On the world thread: note whether a used area's region is still all in memory, then wake every chunk
     * section the prefab covers. Completes once every wake has landed; a wake that fails is counted and passed
     * over (the region load says whether the paste will be whole).
     */
    @Nonnull
    private static CompletableFuture<Void> wake(@Nonnull IPrefabBuffer buffer, @Nonnull World world,
            @Nonnull Vector3i origin, @Nonnull Measure measure, @Nonnull String prefabId) {
        long start = System.nanoTime();
        int lowest = origin.y + buffer.getMinY();
        if (lowest < ChunkUtil.MIN_Y && CUT_PREFABS.add(prefabId.toLowerCase(Locale.ROOT))) {
            SafeLog.warn(LOG + "the prefab " + prefabId + " reaches down to row " + lowest + " once its anchor sits"
                    + " at Y " + origin.y + ", so its rows below Y " + ChunkUtil.MIN_Y + " are not pasted:"
                    + " re-anchor it nearer its floor");
        }
        if (measure.reused) {
            measure.stillInMemory = PrefabUtil.loadPasteRegionIfInMemory(buffer, world, origin,
                    PrefabRotation.ROTATION_0).isFullyLoaded();
        }
        PrefabRotation rotation = PrefabRotation.ROTATION_0;
        int minX = ChunkUtil.chunkCoordinate(origin.x + buffer.getMinX(rotation));
        int maxX = ChunkUtil.chunkCoordinate(origin.x + buffer.getMaxX(rotation));
        int minZ = ChunkUtil.chunkCoordinate(origin.z + buffer.getMinZ(rotation));
        int maxZ = ChunkUtil.chunkCoordinate(origin.z + buffer.getMaxZ(rotation));
        // A section above the world's top answers no reference, and its wake is counted as one that could not be.
        int minY = Math.max(ChunkUtil.MIN_SECTION, ChunkUtil.chunkCoordinate(origin.y + buffer.getMinY()));
        int maxY = ChunkUtil.chunkCoordinate(origin.y + buffer.getMaxY());
        List<CompletableFuture<Void>> wakes = new ArrayList<>();
        AtomicInteger notWoken = new AtomicInteger();
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                for (int cy = minY; cy <= maxY; cy++) {
                    wakes.add(TickingSections.wake(world, cx * (double) ChunkUtil.SIZE, cy * (double) ChunkUtil.SIZE,
                                    cz * (double) ChunkUtil.SIZE)
                            .handle((sectionRef, error) -> {
                                if (error != null || sectionRef == null) {
                                    notWoken.incrementAndGet();
                                }
                                return null;
                            }));
                }
            }
        }
        measure.wakeTaskMs = (System.nanoTime() - start) / NANOS_PER_MILLI;
        return CompletableFuture.allOf(wakes.toArray(new CompletableFuture<?>[0])).thenRun(() -> {
            measure.sectionsWoken = wakes.size() - notWoken.get();
            measure.sectionsNotWoken = notWoken.get();
        });
    }

    /** On the world thread: clear a used area, paste the prefab, and log what it cost. */
    private static void pasteNow(@Nonnull IPrefabBuffer buffer, @Nonnull World world, @Nonnull CopyKey area,
            @Nonnull AreaLayout layout, @Nonnull Vector3i origin, @Nonnull PrefabUtil.PasteRegion region,
            @Nonnull Measure measure, @Nonnull String prefabId) {
        long start = System.nanoTime();
        measure.loadMs = (start - measure.requestedAt) / NANOS_PER_MILLI;
        measure.columns = region.columns.length;
        measure.sections = region.sections.length;
        measure.fullyLoaded = region.isFullyLoaded();
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (measure.reused) {
            clear(store, layout, area.area(), measure);
        }
        long cleared = System.nanoTime();
        PrefabUtil.paste(buffer, world, new Vector3i(origin), Rotation.None, new Random(), PrefabUtil.Flags.FORCE,
                SetBlockSettings.NONE, region, holder -> measure.blockStatesPlaced.incrementAndGet(),
                holder -> measure.entitiesPlaced.incrementAndGet(), store);
        long pasted = System.nanoTime();
        measure.clearMs = (cleared - start) / NANOS_PER_MILLI;
        measure.pasteMs = (pasted - cleared) / NANOS_PER_MILLI;
        log(world, area, origin, prefabId, measure);
    }

    /**
     * Remove every entity but a player from {@code area}'s cell: the spatial query's entities, then the
     * intangible ones it never lists, each count kept apart. World thread only.
     */
    private static void clear(@Nonnull Store<EntityStore> store, @Nonnull AreaLayout layout, int area,
            @Nonnull Measure measure) {
        List<Ref<EntityStore>> tangible = new ArrayList<>(
                TargetUtil.getAllEntitiesInBox(layout.cellMin(area), layout.cellMax(area), store));
        List<Ref<EntityStore>> intangible = new ArrayList<>();
        Query<EntityStore> query = Query.and(TransformComponent.getComponentType(),
                Intangible.getComponentType(), Query.not(Player.getComponentType()));
        store.forEachChunk(query, (chunk, commands) -> {
            for (int i = 0; i < chunk.size(); i++) {
                TransformComponent at = chunk.getComponent(i, TransformComponent.getComponentType());
                if (at != null && layout.inClearBox(area, at.getPosition())) {
                    intangible.add(chunk.getReferenceTo(i));
                }
            }
        });
        measure.removedTangible = remove(store, tangible);
        measure.removedIntangible = remove(store, intangible);
    }

    /** Remove each entity still in the store that is not a player; answers how many went. */
    private static int remove(@Nonnull Store<EntityStore> store, @Nonnull List<Ref<EntityStore>> refs) {
        int removed = 0;
        for (Ref<EntityStore> ref : refs) {
            if (ref == null || !ref.isValid() || store.getComponent(ref, Player.getComponentType()) != null) {
                continue;
            }
            store.removeEntity(ref, RemoveReason.REMOVE);
            removed++;
        }
        return removed;
    }

    private static void log(@Nonnull World world, @Nonnull CopyKey area, @Nonnull Vector3i origin,
            @Nonnull String prefabId, @Nonnull Measure measure) {
        double held = measure.clearMs + measure.pasteMs;
        StringBuilder line = new StringBuilder(LOG).append(String.format(Locale.ROOT,
                "pasted %s into %s area %d at %d %d %d: %.2f ms on the world thread in one task (paste %.2f ms,"
                        + " clear %.2f ms), %.2f ms in the wake task before it; %d entities and %d block states"
                        + " placed; region of %d chunk columns and %d sections, %s, %d sections woken%s; the area"
                        + " was loaded %.1f ms after it was asked for",
                prefabId, world.getName(), area.area(), origin.x, origin.y, origin.z, held, measure.pasteMs,
                measure.clearMs, measure.wakeTaskMs, measure.entitiesPlaced.get(), measure.blockStatesPlaced.get(),
                measure.columns, measure.sections, measure.fullyLoaded ? "fully loaded" : "NOT fully loaded",
                measure.sectionsWoken, measure.sectionsNotWoken == 0 ? ""
                        : String.format(Locale.ROOT, " (%d could not be)", measure.sectionsNotWoken),
                measure.loadMs));
        if (measure.reused) {
            line.append(String.format(Locale.ROOT, "; a used area: cleared %d entities the spatial query lists and"
                    + " %d intangible ones it does not, and its region was %s", measure.removedTangible,
                    measure.removedIntangible, Boolean.TRUE.equals(measure.stillInMemory)
                            ? "still all in memory since its last paste"
                            : "NOT all in memory since its last paste"));
        } else {
            line.append("; a fresh area");
        }
        if (Math.max(held, measure.wakeTaskMs) > PASTE_BUDGET_MS) {
            line.append(String.format(Locale.ROOT, "; over the %.0f ms budget, so this paste is to be sliced across"
                    + " ticks", PASTE_BUDGET_MS));
        }
        SafeLog.info(line.toString());
    }

    /** The world's lower-cased name, the way a {@link CopyKey} spells it. */
    @Nonnull
    static String nameOf(@Nullable World world) {
        return world == null ? "" : world.getName().toLowerCase(Locale.ROOT);
    }
}
