package com.ziggfreed.common.stats.condition;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.ItemReadings;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.FactorProvider;
import com.ziggfreed.common.util.SafeLog;

/**
 * What an entity is working at now: the one seam behind {@link ZigWorkingAtCondition} (R948). The library knows no
 * station; a station mod answers through {@link #install} at its setup, from its own work sessions: the station, the
 * cycle being worked and the piece on it ({@link Work}). Unfilled, nobody is working at a station, so the condition
 * never holds: a server without a station mod still loads every file naming it, and the grants bound to it simply never
 * count. The first question asked of an unfilled seam is logged once, so a server owner can see why a station bonus
 * reads nothing.
 *
 * <p>Asked by the conditional holder pass for every holder of a grant bound to a station (a player every tick), so a
 * {@link Source} answers from a map it already keeps, never a world scan, and only once per entity per world tick
 * however many of its conditions ask ({@link #workAt}). A station mod calls {@link #workChanged} as a work begins and
 * ends, so the entity's bound grants are read at once rather than on the next tick. What a piece knob reads off the piece (its
 * level, quality, wear and stamped points, {@link Piece}) is read once per piece and kept per entity until the piece
 * changes or the work ends ({@link #pieceOf}).
 */
public final class StationWork {

    /** The library's reading of a stack's stamped points, read through the shared vocabulary (no module edge). */
    static final String STAMP_POINTS_FACTOR = "ziggfreedcommon:item_stamp_points";

    /**
     * One entity's work now: the station's own id as its mod's files name it ({@code Sawmill}), the cycle of the
     * session being worked (the first is 1; 0 when the station counts none) and the piece the work is about (the stack
     * placed on it, or the material a cycle consumes), null when it has none.
     */
    public record Work(@Nonnull String stationId, int cycle, @Nullable ItemStack piece) {
    }

    /** What a station mod answers. */
    @FunctionalInterface
    public interface Source {
        /**
         * What the entity at {@code ref} is working at now, or null when it works at none. World thread; cheap, since
         * the holder pass asks it every tick. A non-player answers null.
         */
        @Nullable
        Work workAt(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref);
    }

    /**
     * What a piece knob reads off a piece: its item level, its quality tier value (a quality stamped on the stack, else
     * its item's), its durability percent (100 for an item that tracks none) and its stamped enhancement points, each
     * null where it cannot be read, read exactly as {@code hytale:item_level}, {@code hytale:item_quality},
     * {@code hytale:item_durability_percent} and {@code ziggfreedcommon:item_stamp_points} read the same stack.
     */
    public record Piece(@Nullable ItemStack stack, @Nullable Double level, @Nullable Double quality,
            @Nullable Double durabilityPercent, @Nullable Double stampPoints) {

        /** No piece: every knob asking about one fails. */
        public static final Piece NONE = new Piece(null, null, null, null, null);

        /** {@code stack}'s readings; {@link #NONE} for no stack. Never throws. */
        @Nonnull
        public static Piece of(@Nullable ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return NONE;
            }
            try {
                return new Piece(stack, ItemReadings.itemLevel(stack), ItemReadings.quality(stack),
                        ItemReadings.durabilityPercent(stack), readStampPoints(stack));
            } catch (Throwable t) {
                return new Piece(stack, null, null, null, null);
            }
        }
    }

    private static volatile Source source;

    /** Each working entity's piece readings, kept until its piece changes or its work ends. */
    private static final Map<Ref<EntityStore>, Piece> PIECES = new ConcurrentHashMap<>();

    /** One entity's answer this world tick, so its many conditions ask the station mod once a tick. */
    private record Asked(long tick, @Nullable Work work) {
    }

    /** No world tick could be read (no world behind the accessor, a unit test): nothing is kept. */
    private static final long NO_TICK = Long.MIN_VALUE;

    /** Each asking entity's answer for the tick it was asked in, replaced on the next tick's first ask. */
    private static final Map<Ref<EntityStore>, Asked> ASKED = new ConcurrentHashMap<>();

    /** Whether a refresh at a change of work threw: logged once a process. */
    private static final AtomicBoolean REFRESH_FAILED_LOGGED = new AtomicBoolean();

    /** Whether the unfilled seam has been reported: once a process. */
    private static final AtomicBoolean UNFILLED_LOGGED = new AtomicBoolean();

    /** Whether a station's answer threw: logged once a process. */
    private static final AtomicBoolean FAILED_LOGGED = new AtomicBoolean();

    private StationWork() {
    }

    /** The station mod's answer, once at its setup; null takes it back out (a test). */
    public static void install(@Nullable Source answer) {
        source = answer;
        PIECES.clear();
        ASKED.clear();
    }

    /** Whether a station mod answers. */
    public static boolean installed() {
        return source != null;
    }

    /**
     * What the entity at {@code ref} is working at now, its station id trimmed, or null when it works at none, when no
     * station mod answers (reported once) or when the answer throws (logged once). The station mod is asked once per
     * entity per world tick, however many of its conditions ask ({@link #workChanged} asks again at once). World
     * thread; never throws.
     */
    @Nullable
    public static Work workAt(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        Source answer = source;
        if (answer == null) {
            if (UNFILLED_LOGGED.compareAndSet(false, true)) {
                SafeLog.info("[bound] no station mod answers ZigWorkingAt on this server, so a grant bound to working "
                        + "at a station never counts (logged once).");
            }
            return null;
        }
        long tick = tickOf(accessor);
        if (tick != NO_TICK) {
            Asked asked = ASKED.get(ref);
            if (asked != null && asked.tick() == tick) {
                return asked.work();
            }
        }
        Work work = ask(answer, accessor, ref);
        if (tick != NO_TICK) {
            ASKED.put(ref, new Asked(tick, work));
        }
        return work;
    }

    /**
     * The station mod's answer for the entity at {@code ref}, its station id trimmed; null for none or a throw. Drops
     * the entity's piece readings when it works at nothing.
     */
    @Nullable
    private static Work ask(@Nonnull Source answer, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref) {
        Work work;
        try {
            work = answer.workAt(accessor, ref);
        } catch (Throwable t) {
            if (FAILED_LOGGED.compareAndSet(false, true)) {
                SafeLog.warn("[bound] the station mod's ZigWorkingAt answer threw; nobody counts as working at a "
                        + "station (logged once): " + t);
            }
            work = null;
        }
        if (work == null || work.stationId() == null || work.stationId().isBlank()) {
            PIECES.remove(ref);
            return null;
        }
        String id = work.stationId().trim();
        return id.equals(work.stationId()) ? work : new Work(id, work.cycle(), work.piece());
    }

    /** The id of the station the entity at {@code ref} is working at now ({@link #workAt}), or null. */
    @Nullable
    public static String stationAt(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        Work work = workAt(accessor, ref);
        return work == null ? null : work.stationId();
    }

    /**
     * The readings of {@code piece}, the piece the entity at {@code ref} works on now, read once per piece: the kept
     * readings while the same stack stands on the work, read again when another takes its place. {@link Piece#NONE}
     * for no piece.
     */
    @Nonnull
    static Piece pieceOf(@Nonnull Ref<EntityStore> ref, @Nullable ItemStack piece) {
        if (piece == null || piece.isEmpty()) {
            PIECES.remove(ref);
            return Piece.NONE;
        }
        Piece kept = PIECES.get(ref);
        if (kept != null && kept.stack() == piece) {
            return kept;
        }
        Piece fresh = Piece.of(piece);
        PIECES.put(ref, fresh);
        return fresh;
    }

    /**
     * The entity at {@code ref} started or stopped working at a station (a station mod's call, on the world thread, as
     * the work begins and as it ends): its answer is asked again at once and its bound grants are read now rather than
     * on the holder pass's next tick, so the first thing the work reads (a ritual's pace, from a talent bound to the
     * station) already counts them, and a stopped work's share is gone before anything else reads it. Never throws.
     */
    public static void workChanged(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        ASKED.remove(ref);
        try {
            if (!ref.isValid() || !BoundGrants.has(ref)) {
                return;
            }
            EntityStatMap stats = accessor.getComponent(ref, EntityStatMap.getComponentType());
            if (stats != null) {
                BoundGrants.evaluate(accessor, ref, stats, ConditionalStats.now(accessor));
            }
        } catch (Throwable t) {
            if (REFRESH_FAILED_LOGGED.compareAndSet(false, true)) {
                SafeLog.warn("[bound] reading an entity's bound grants as its work changed threw; the holder pass "
                        + "reads them on its next tick instead (logged once): " + t);
            }
        }
    }

    /** Forget the entity at {@code ref} (it left its store). */
    public static void forget(@Nonnull Ref<EntityStore> ref) {
        PIECES.remove(ref);
        ASKED.remove(ref);
    }

    /** The accessor's world tick, or {@link #NO_TICK} where none can be read. */
    private static long tickOf(@Nullable ComponentAccessor<EntityStore> accessor) {
        try {
            return accessor.getExternalData().getWorld().getTick();
        } catch (Throwable t) {
            return NO_TICK;
        }
    }

    /** {@code stack}'s stamped points through the library's own reading, or null where nothing answers it. */
    @Nullable
    private static Double readStampPoints(@Nonnull ItemStack stack) {
        FactorProvider provider = FactorContributions.provider(STAMP_POINTS_FACTOR);
        return provider == null ? null : provider.resolve(FactorContext.builder().item(stack).build());
    }
}
