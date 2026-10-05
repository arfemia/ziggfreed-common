package com.ziggfreed.common.world;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.NonTicking;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.ziggfreed.common.util.SafeLog;

/**
 * Whether an entity added at a block stays in the world, and how to make it stay: the chunk SECTION
 * under it must be ticking.
 *
 * <p><b>Why the section.</b> On Update 7 a chunk section loads asleep (carrying {@code NonTicking})
 * whatever its column's state, and the column's ticking flag no longer reaches its sections. An entity
 * added into a section that is not ticking is parked on the spot: the engine moves it into the section's
 * saved entity list, {@code Store.addEntity} answers null ({@code NPCPlugin.spawnEntity} logs "Unable to
 * handle non-spawned entity" and skips its post-spawn), and the entity comes back as a load once the
 * section ticks. Only two things make a section tick: a section request carrying
 * {@code GetChunkFlags.SET_TICKING} ({@link #wake}) and a player's hot sphere. With no player near it,
 * the engine puts a section back to sleep once its active timer runs out (about 7.5 seconds, and at the
 * next poll for a section whose timer already ran out once).
 *
 * <p><b>What a read means.</b> {@link State#TICKING}: an entity added there stays. {@link State#PARKING}:
 * the section is in memory and asleep, so an add would be parked; a wake on the world thread makes it
 * tick before the wake returns. {@link State#ABSENT}: the section is not in memory, or cannot be read; a
 * wake loads it first. A wake brings the section's parked entities back into the world before it
 * returns, so a caller that may have parked its own copy there (an add refused into a sleeping section
 * by an older build) looks for that copy before adding another; the placement sweep does, with its next
 * round's adoption pass.
 *
 * <p><b>World thread only, and never inside a system's processing window for a wake</b>
 * ({@link #wake}, {@link #ensureTicking}, {@link #whenTicking}): a wake re-adds entities, which a store
 * refuses while it processes. {@link #holdTicking} only reads the section and resets two timers, so an
 * entity-store system may call it. Nothing here throws: a read that fails reads {@link State#ABSENT}, a
 * wake that fails answers a failed future, and a step that throws is logged. A {@code LinkageError} (a
 * member this server build does not have) logs one WARNING per server; any other failure logs at FINE.
 */
public final class TickingSections {

    /** What an entity added at a block would do. */
    public enum State {
        /** The section ticks: an entity added there stays. */
        TICKING,
        /** The section is in memory and asleep: an entity added there is parked until it ticks. */
        PARKING,
        /** The section is not in memory, or cannot be read: an entity cannot be added there yet. */
        ABSENT
    }

    /**
     * The chunk section a block is filed under, as the engine resolves it: {@code ChunkUtil.chunkCoordinate}
     * on each axis, which floors, so blocks -1 and -32 are in section -1 and block -33 in section -2.
     */
    public record SectionPos(int x, int y, int z) {

        /** The section holding the block at world {@code (x, y, z)}. */
        @Nonnull
        public static SectionPos ofBlock(double x, double y, double z) {
            return new SectionPos(ChunkUtil.chunkCoordinate(x), ChunkUtil.chunkCoordinate(y),
                    ChunkUtil.chunkCoordinate(z));
        }
    }

    /** The engine side: what a section reads, how it is woken and held. Package-private for the test. */
    interface Sections {

        @Nonnull
        State stateOf(@Nonnull SectionPos section);

        @Nonnull
        CompletableFuture<Ref<ChunkStore>> wake(@Nonnull SectionPos section);

        /** Reset a ticking section's active timer, and its column's; answers whether it held one. */
        boolean hold(@Nonnull SectionPos section);
    }

    /** One WARNING per server for a read that does not link on this server build. */
    private static final AtomicBoolean LINKAGE_REPORTED = new AtomicBoolean();

    private TickingSections() {
    }

    // ==================== the live forms ====================

    /** What an entity added at world {@code (x, y, z)} would do. Never loads, never wakes. */
    @Nonnull
    public static State stateAt(@Nonnull World world, double x, double y, double z) {
        return stateOf(of(world), SectionPos.ofBlock(x, y, z));
    }

    /**
     * Ask the chunk store for the section holding {@code (x, y, z)}, loading it if needed, and start it
     * ticking ({@code SET_TICKING | HIGH_PRIORITY}). On the world thread a section already in memory ticks,
     * and its parked entities are back, before this returns, with the answer complete. Never throws.
     */
    @Nonnull
    public static CompletableFuture<Ref<ChunkStore>> wake(@Nonnull World world, double x, double y, double z) {
        return wakeOf(of(world), SectionPos.ofBlock(x, y, z));
    }

    /**
     * Whether an entity added at {@code (x, y, z)} now stays: true when its section ticks, or was in memory
     * and woke during this call (the world thread); false when it is not in memory, which this never loads.
     */
    public static boolean ensureTicking(@Nonnull World world, double x, double y, double z) {
        return ensureTicking(of(world), SectionPos.ofBlock(x, y, z));
    }

    /**
     * Run {@code next} once the section holding {@code (x, y, z)} ticks: during this call when it already
     * ticks or wakes here on the world thread, else on the world thread once a wake lands, after reading the
     * section again. A wake that fails, or lands with the section asleep again, reaches {@code refused}
     * with the reason and {@code next} never runs. Neither throws into the caller or the loading thread.
     */
    public static void whenTicking(@Nonnull World world, double x, double y, double z, @Nonnull Runnable next,
            @Nonnull Consumer<String> refused) {
        whenTicking(of(world), world, SectionPos.ofBlock(x, y, z), next, refused);
    }

    /**
     * Keep the section holding {@code (x, y, z)} ticking a while longer: reset its active timer and its
     * column's, the lever the engine pulls for a section a player's hot sphere covers. Answers whether the
     * section was ticking. Never wakes, so an entity-store system may call it.
     */
    public static boolean holdTicking(@Nonnull World world, double x, double y, double z) {
        return holdTicking(of(world), SectionPos.ofBlock(x, y, z));
    }

    // ==================== the rules, over the seam (package-private for the test) ====================

    @Nonnull
    static State stateOf(@Nonnull Sections sections, @Nonnull SectionPos section) {
        try {
            State state = sections.stateOf(section);
            return state == null ? State.ABSENT : state;
        } catch (Throwable t) {
            report("read", t);
            return State.ABSENT;
        }
    }

    @Nonnull
    static CompletableFuture<Ref<ChunkStore>> wakeOf(@Nonnull Sections sections, @Nonnull SectionPos section) {
        try {
            CompletableFuture<Ref<ChunkStore>> woken = sections.wake(section);
            return woken != null ? woken
                    : CompletableFuture.failedFuture(new IllegalStateException("the chunk store answered no request"));
        } catch (Throwable t) {
            report("wake", t);
            return CompletableFuture.failedFuture(t);
        }
    }

    static boolean ensureTicking(@Nonnull Sections sections, @Nonnull SectionPos section) {
        State state = stateOf(sections, section);
        if (state != State.PARKING) {
            return state == State.TICKING;
        }
        wakeOf(sections, section);
        return stateOf(sections, section) == State.TICKING;
    }

    static void whenTicking(@Nonnull Sections sections, @Nonnull Executor worldThread, @Nonnull SectionPos section,
            @Nonnull Runnable next, @Nonnull Consumer<String> refused) {
        if (stateOf(sections, section) == State.TICKING) {
            run(next, section);
            return;
        }
        CompletableFuture<Ref<ChunkStore>> woken = wakeOf(sections, section);
        if (woken.isDone() && stateOf(sections, section) == State.TICKING) {
            run(next, section);
            return;
        }
        woken.whenCompleteAsync((sectionRef, error) -> {
            if (error == null && stateOf(sections, section) == State.TICKING) {
                run(next, section);
            } else {
                refuse(refused, error != null
                        ? "the chunk section " + section + " could not be brought up: " + error
                        : "the chunk section " + section + " is still not ticking");
            }
        }, worldThread);
    }

    static boolean holdTicking(@Nonnull Sections sections, @Nonnull SectionPos section) {
        if (stateOf(sections, section) != State.TICKING) {
            return false;
        }
        try {
            return sections.hold(section);
        } catch (Throwable t) {
            report("hold", t);
            return false;
        }
    }

    // ==================== helpers ====================

    private static void run(@Nonnull Runnable next, @Nonnull SectionPos section) {
        try {
            next.run();
        } catch (Throwable t) {
            SafeLog.warn("[TickingSections] a step waiting on chunk section " + section + " failed", t);
        }
    }

    private static void refuse(@Nonnull Consumer<String> refused, @Nonnull String why) {
        try {
            refused.accept(why);
        } catch (Throwable t) {
            SafeLog.warn("[TickingSections] reporting a refused step failed", t);
        }
    }

    private static void report(@Nonnull String what, @Nonnull Throwable t) {
        if (t instanceof LinkageError) {
            if (LINKAGE_REPORTED.compareAndSet(false, true)) {
                SafeLog.warn("[TickingSections] the chunk-section " + what + " does not link on this server build: " + t);
            }
            return;
        }
        SafeLog.fine("[TickingSections] chunk-section " + what + " failed: " + t);
    }

    @Nonnull
    private static Sections of(@Nonnull World world) {
        try {
            return new EngineSections(world);
        } catch (Throwable t) {
            report("lookup", t);
            return new Unreadable();
        }
    }

    /** What a world whose chunk store cannot be read answers: nothing in memory, nothing to wake or hold. */
    private static final class Unreadable implements Sections {

        @Nonnull
        @Override
        public State stateOf(@Nonnull SectionPos section) {
            return State.ABSENT;
        }

        @Nonnull
        @Override
        public CompletableFuture<Ref<ChunkStore>> wake(@Nonnull SectionPos section) {
            return CompletableFuture.failedFuture(new IllegalStateException("the world's chunk store cannot be read"));
        }

        @Override
        public boolean hold(@Nonnull SectionPos section) {
            return false;
        }
    }

    /** The live engine reads (Engine facts 2 and 7), kept apart so a unit JVM never links them. */
    private static final class EngineSections implements Sections {

        @Nonnull
        private final ChunkStore chunks;
        @Nonnull
        private final Store<ChunkStore> store;
        @Nonnull
        private final ComponentType<ChunkStore, NonTicking<ChunkStore>> nonTicking;

        EngineSections(@Nonnull World world) {
            this.chunks = world.getChunkStore();
            this.store = chunks.getStore();
            this.nonTicking = ChunkStore.REGISTRY.getNonTickingComponentType();
        }

        @Nonnull
        @Override
        public State stateOf(@Nonnull SectionPos section) {
            Ref<ChunkStore> ref = chunks.getChunkSectionReference(section.x(), section.y(), section.z());
            if (ref == null || !ref.isValid()) {
                return State.ABSENT;
            }
            return store.getComponent(ref, nonTicking) == null ? State.TICKING : State.PARKING;
        }

        @Nonnull
        @Override
        public CompletableFuture<Ref<ChunkStore>> wake(@Nonnull SectionPos section) {
            return chunks.getChunkSectionReferenceAsync(section.x(), section.y(), section.z(),
                    GetChunkFlags.SET_TICKING | GetChunkFlags.HIGH_PRIORITY);
        }

        @Override
        public boolean hold(@Nonnull SectionPos section) {
            Ref<ChunkStore> ref = chunks.getChunkSectionReference(section.x(), section.y(), section.z());
            if (ref == null || !ref.isValid()) {
                return false;
            }
            ChunkSection chunkSection = store.getComponent(ref, ChunkSection.getComponentType());
            if (chunkSection == null) {
                return false;
            }
            chunkSection.resetActiveTimer();
            Ref<ChunkStore> columnRef = chunks.getChunkReference(ChunkUtil.indexChunk(section.x(), section.z()));
            if (columnRef != null && columnRef.isValid()) {
                WorldChunk column = store.getComponent(columnRef, WorldChunk.getComponentType());
                if (column != null) {
                    column.resetActiveTimer();
                }
            }
            return true;
        }
    }
}
