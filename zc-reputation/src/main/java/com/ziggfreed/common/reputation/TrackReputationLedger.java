package com.ziggfreed.common.reputation;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.playerdata.PlayerRecords;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackReading;
import com.ziggfreed.common.track.TrackRecord;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.util.SafeLog;

/**
 * Standing kept on the reputation's track (R602): the track holds the truth, and the engine's own value (a
 * native {@code ReputationGroup}'s per-player number, which NPC attitudes, native dialogue choices and the
 * admin {@code /reputation} read) is a copy kept in step, held inside the engine's bounds.
 *
 * <ul>
 *   <li><b>The import</b>, once per player and reputation, at the player's first world entry or first change
 *       after the update, never on a reload, and only in a world where the engine keeps the player's own value
 *       (in a {@code ReputationStorage PerWorld} world it holds the world's one shared number, which is no
 *       player's standing, so the import waits, unmarked, for the player's first world that keeps it per player):
 *       the engine's value becomes the climb, with the best level and the Beyond payouts already parked or
 *       collected read from the higher of it and the highest standing on the player's reputation record
 *       ({@link ReputationTracks#imported}). An empty climb takes it through {@code TrackService.seed}; a climb that
 *       moves already landed on (a Track reward before the first world entry, a gain in a per-world world) holds
 *       the starting value plus those moves, and becomes the engine's value plus those moves. The player's
 *       reputation record keeps that it ran ({@code ReputationComponent.markImported}).</li>
 *   <li><b>The copy</b> is written after every move of the track, the library's own and any other (a Track
 *       reward, {@code /zigtrack}, a prestige), on the player's thread: the engine is moved by the difference.
 *       A copy that cannot be written (a move made in a {@code PerWorld} world, which the boot warning names
 *       ({@link #warnPerWorldOnce}), or a write the engine refused) is marked behind on the player's reputation
 *       record ({@code ReputationComponent.unsynced}), which survives a restart: while it is, readings read the
 *       track alone, and the next chance in a world that keeps the player's own value writes the copy again,
 *       never folds the stale one back, so the move is never taken off the track.</li>
 *   <li><b>An outside write</b> (the admin {@code /reputation} add or set, a native objective's
 *       {@code ReputationCompletion}) moves the copy and not the track. A reading sees it at once
 *       ({@link ReputationTracks#reading}), and it is folded into the track at the player's next change, world
 *       entry or move of the track made elsewhere (measured against the copy before that move, so a Track reward
 *       never writes over it), past the gain seam, with source {@value #NATIVE_SOURCE} ({@code TrackService.set}).
 *       The track commits it like any move (its {@code ZigTrackChangedEvent} fires, and the mirror and the gate
 *       epoch move when a reading moved), but the fold is this ledger's own move ({@link #ownMove}), which the
 *       service does not follow, so on the reputation side it is SILENT, as the engine's own write always was: no
 *       reputation change event, no bar move and no Beyond payout for it, so a set past several Beyond steps pays
 *       none of them, and the next change counts its steps from the set standing, as before.</li>
 * </ul>
 * A player whose track cannot be read (the track record never registered, or the reputation's track not
 * provided) keeps the engine's value as the truth through {@link NativeReputationLedger}, said once
 * ({@link #warnFallbackOnce}).
 */
public final class TrackReputationLedger implements ReputationLedger {

    /** The source an outside write to the engine's value is folded into the track under. */
    public static final String NATIVE_SOURCE = "native";

    /** The source the one-time import is kept under. */
    public static final String IMPORT_SOURCE = "reputation:import";

    /** The source a reputation's starting value is put on an empty climb under. */
    public static final String START_SOURCE = "reputation:start";

    /** Set while this ledger moves a track itself, so the service does not follow its own move twice. */
    private static final ThreadLocal<Boolean> OWN_MOVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final AtomicBoolean WARNED_PER_WORLD = new AtomicBoolean();

    private static final AtomicBoolean WARNED_FALLBACK = new AtomicBoolean();

    private final ReputationNative engine;
    private final ReputationLedger fallback;
    private final BiFunction<Store<EntityStore>, Ref<EntityStore>, ReputationComponent> records;

    /**
     * {@code <player>|<reputation>} of every copy whose last write did not land, for a player who carries no
     * reputation record (it never registered): single-process. A player with a record keeps it there, which a
     * restart keeps.
     */
    private final Set<String> unsynced = ConcurrentHashMap.newKeySet();

    public TrackReputationLedger(@Nonnull ReputationNative engine) {
        this(engine, TrackReputationLedger::recordOf);
    }

    /** A ledger reading each player's reputation record through {@code records} (a test holds one). */
    TrackReputationLedger(@Nonnull ReputationNative engine,
            @Nonnull BiFunction<Store<EntityStore>, Ref<EntityStore>, ReputationComponent> records) {
        this.engine = engine;
        this.fallback = new NativeReputationLedger(engine);
        this.records = records;
    }

    /** Is a track move on this thread this ledger's own (the service tells it already)? */
    public static boolean ownMove() {
        return OWN_MOVE.get();
    }

    /** One player's climb on one reputation's track, read live. */
    private record Climb(@Nonnull UUID player, @Nonnull TrackKey key, @Nonnull TrackDef track,
                         @Nonnull TrackRecord record) {
    }

    @Nullable
    @Override
    public Reading read(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder) {
        Climb climb = climb(store, ref, def);
        if (climb == null) {
            fellBack(store, ref, def);
            return fallback.read(store, ref, def, ladder);
        }
        Integer copy = copyNow(store, ref, def, climb);
        return ReputationTracks.reading(climb.record(), climb.track().table(), def.initial(), copy,
                engine.hasEntry(store, ref, def.id()), ladder);
    }

    @Nullable
    @Override
    public Reading add(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder, int delta, @Nonnull String source) {
        Climb climb = climb(store, ref, def);
        if (climb == null) {
            fellBack(store, ref, def);
            return fallback.add(store, ref, def, ladder, delta, source);
        }
        climb = inStep(store, ref, def, ladder, climb);
        if (climb == null) {
            return null;
        }
        Climb moving = climb;
        TrackService.Result result = own(() -> TrackService.add(moving.key(), delta, source));
        switch (result.status()) {
            case UNKNOWN, NOT_LIVE, LOCKED -> {
                return null;
            }
            default -> {
                // Moved, or landed as nothing: read what is kept now either way.
            }
        }
        Climb after = climb(store, ref, def);
        if (after == null) {
            return null;
        }
        copy(store, ref, def, ladder, after);
        return ReputationTracks.reading(after.record(), after.track().table(), def.initial(), null, true, ladder);
    }

    @Override
    public void settle(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder) {
        Climb climb = climb(store, ref, def);
        if (climb != null) {
            inStep(store, ref, def, ladder, climb);
        }
    }

    /**
     * The track moved outside the service. A move that landed on a fresh climb ({@code before} null) started at 0,
     * so the reputation's starting value is put under it first, as a write through the service has it
     * ({@link #inStep}). When the player's standing was not imported yet (a Track reward paid between their connect
     * and their first world entry), the import runs now and keeps the move on top of the standing the player held.
     * An outside write to the copy not folded in yet (an admin's {@code /reputation} set, then a Track reward) is
     * folded in now, measured against the copy {@code before} held, so the copy written next keeps it. Then the copy.
     */
    @Override
    public void followed(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder, @Nullable Reading before) {
        Climb climb = climb(store, ref, def);
        if (climb == null) {
            return;
        }
        boolean startedEmpty = before == null;
        if (startedEmpty && def.initial() != 0) {
            TrackKey key = climb.key();
            long started = (long) def.initial() + climb.record().value();
            own(() -> TrackService.set(key, started, START_SOURCE));
            climb = climb(store, ref, def);
            if (climb == null) {
                return;
            }
        }
        climb = importOnce(store, ref, def, climb, startedEmpty);
        if (climb == null) {
            return;
        }
        if (fold(store, ref, def, ladder, climb, startedEmpty ? def.initial() : before.held())) {
            climb = climb(store, ref, def);
            if (climb == null) {
                return;
            }
        }
        copy(store, ref, def, ladder, climb);
    }

    /**
     * Bring {@code climb} in step before it is read for a write: the one-time import, the starting value on an
     * empty climb, then an outside write to the copy folded in (or a copy that is behind written again). Answers
     * the climb as kept now, or null when it can no longer be read.
     */
    @Nullable
    private Climb inStep(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder, @Nonnull Climb climb) {
        Climb now = importOnce(store, ref, def, climb, false);
        if (now == null) {
            return null;
        }
        if (now.record().isEmpty() && def.initial() != 0) {
            TrackService.seed(now.key(), new TrackRecord(def.initial(), 0.0, 0, 0, Map.of()), START_SOURCE);
            now = climb(store, ref, def);
            if (now == null) {
                return null;
            }
        }
        if (fold(store, ref, def, ladder, now, heldOf(now, def))) {
            now = climb(store, ref, def);
        }
        return now;
    }

    /**
     * The one-time import of {@code def}'s standing for this player (the class javadoc's first point), answering
     * the climb as kept after it, or null when it can no longer be read. Once the reputation record marks it, never
     * again. Where the engine keeps reputation per world it waits, unmarked. An untouched reputation (no engine
     * entry, the starting value) on an empty climb imports nothing and is marked all the same. With no reputation
     * record to remember the import, a climb already on record is taken as imported (only an empty one is seeded),
     * unless {@code onlyMoves} says it holds nothing but the starting value and the move that just landed on it
     * fresh. A climb the import merged into is marked behind, so the copy is written from it, never folded back.
     */
    @Nullable
    private Climb importOnce(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull Climb climb, boolean onlyMoves) {
        ReputationComponent record = records.apply(store, ref);
        boolean empty = climb.record().isEmpty();
        if ((record != null && record.imported(def.id())) || (record == null && !empty && !onlyMoves)
                || !engine.copiesTo(store, ref)) {
            return climb;
        }
        Integer standing = engine.earned(store, ref, def.id());
        if (standing == null) {
            return climb;
        }
        TrackRecord imported = ReputationTracks.imported(standing, record == null ? null : record.highest(def.id()),
                climb.track().table());
        TrackKey key = climb.key();
        if (empty) {
            if (standing != def.initial() || engine.hasEntry(store, ref, def.id())) {
                TrackService.Result result = TrackService.seed(key, imported, IMPORT_SOURCE);
                if (unwritten(result)) {
                    return climb;
                }
            }
        } else {
            long merged = (long) standing + climb.record().value() - def.initial();
            TrackService.Result result = own(() -> TrackService.set(key, merged, IMPORT_SOURCE));
            if (unwritten(result)) {
                return climb;
            }
            TrackService.raiseBest(key, imported.best());
            int mark = imported.paidThrough(ReputationTracks.BEYOND_ROW);
            if (mark > 0) {
                TrackService.markPaidThrough(key, List.of(ReputationTracks.BEYOND_ROW), mark);
            }
            behind(store, ref, climb, def, true);
        }
        if (record != null) {
            record.markImported(def.id());
            PlayerRecords.changed(store, ref, ReputationComponent.REGISTRY_ID);
        }
        return climb(store, ref, def);
    }

    /** Did a write find no usable track or no live player, so nothing was kept? */
    private static boolean unwritten(@Nonnull TrackService.Result result) {
        return result.status() == TrackService.Status.NOT_LIVE || result.status() == TrackService.Status.UNKNOWN;
    }

    /**
     * Fold an outside write into the track: when the engine's copy no longer holds the copy of {@code heldWritten}
     * (the standing the ranks read when the copy was last written: {@code climb}'s own, or, for a move the track
     * just made elsewhere, the one before it), something else moved it, and the track follows by the same amount,
     * silently (this ledger's own move, which the service does not announce). True when it moved. A copy that is
     * behind is written again instead, never folded.
     */
    private boolean fold(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder, @Nonnull Climb climb, long heldWritten) {
        if (!engine.copiesTo(store, ref)) {
            return false;
        }
        if (isBehind(store, ref, climb, def)) {
            copy(store, ref, def, ladder, climb);
            return false;
        }
        Integer now = engine.earned(store, ref, def.id());
        if (now == null) {
            return false;
        }
        long expected = ReputationTracks.copyOf(heldWritten, ladder);
        if (now == expected) {
            return false;
        }
        long folded = valueOf(climb, def) + (now - expected);
        TrackKey key = climb.key();
        return own(() -> TrackService.set(key, folded, NATIVE_SOURCE)).moved();
    }

    /** {@code climb}'s current standing: its value, or the reputation's starting value while nothing is on record. */
    private static long valueOf(@Nonnull Climb climb, @Nonnull ReputationDef def) {
        return climb.record().isEmpty() ? def.initial() : climb.record().value();
    }

    /** The standing {@code climb}'s ranks read, which the engine's copy shows ({@link ReputationTracks#held}). */
    private static long heldOf(@Nonnull Climb climb, @Nonnull ReputationDef def) {
        return ReputationTracks.held(climb.record(), valueOf(climb, def), climb.track().table());
    }

    /**
     * Write the engine's copy of {@code climb}: the standing its ranks read, held inside the engine's bounds,
     * moved into the engine by the difference. Where the engine keeps reputation per world nothing is written and
     * the copy is marked behind; it is cleared once a write lands.
     */
    private void copy(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder, @Nonnull Climb climb) {
        if (!engine.copiesTo(store, ref)) {
            behind(store, ref, climb, def, true);
            return;
        }
        try {
            Integer now = engine.earned(store, ref, def.id());
            if (now == null) {
                return;
            }
            int target = (int) ReputationTracks.copyOf(heldOf(climb, def), ladder);
            if (now == target) {
                behind(store, ref, climb, def, false);
                return;
            }
            Integer after = engine.add(store, ref, def.id(), target - now);
            if (after != null && after == target) {
                behind(store, ref, climb, def, false);
            } else {
                behind(store, ref, climb, def, true);
                SafeLog.warn("[reputation] the engine's copy of '" + def.id() + "' for " + climb.player()
                        + " could not be written (it holds " + after + ", the track " + target
                        + "); it is written again at the next change or world entry");
            }
        } catch (Throwable t) {
            behind(store, ref, climb, def, true);
            SafeLog.warn("[reputation] the engine's copy of '" + def.id() + "' could not be written: "
                    + t.getMessage());
        }
    }

    /** Is the engine's copy of {@code climb} behind it (on the player's record, or here for a player with none)? */
    private boolean isBehind(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull Climb climb, @Nonnull ReputationDef def) {
        if (unsynced.contains(unsyncedKey(climb, def))) {
            return true;
        }
        ReputationComponent record = records.apply(store, ref);
        return record != null && record.unsynced(def.id());
    }

    /** Mark the engine's copy of {@code climb} behind it, or caught up, on the player's record and here. */
    private void behind(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull Climb climb,
            @Nonnull ReputationDef def, boolean behind) {
        ReputationComponent record = records.apply(store, ref);
        if (record != null && ReputationComponent.keeps(def.id()) && record.unsynced(def.id()) != behind) {
            record.unsynced(def.id(), behind);
            PlayerRecords.changed(store, ref, ReputationComponent.REGISTRY_ID);
        }
        String key = unsyncedKey(climb, def);
        if (behind) {
            unsynced.add(key);
        } else {
            unsynced.remove(key);
        }
    }

    /** The live climb on {@code def}'s track, or null when the player or their track cannot be read. */
    @Nullable
    private Climb climb(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def) {
        UUID player = engine.playerId(store, ref);
        TrackDef track = player == null ? null : ReputationTracks.track(def);
        if (track == null) {
            return null;
        }
        TrackKey key = ReputationTracks.key(player, def);
        TrackReading reading = TrackService.read(key);
        return reading == null ? null : new Climb(player, key, track, reading.record());
    }

    /**
     * The engine's value now, when a copy is kept here and it is not behind; else null, so a reading reads the
     * track alone.
     */
    @Nullable
    private Integer copyNow(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull Climb climb) {
        if (!engine.copiesTo(store, ref) || isBehind(store, ref, climb, def)) {
            return null;
        }
        return engine.earned(store, ref, def.id());
    }

    /**
     * A standing for a live player is about to be read or written in the engine's own value, its track unreadable:
     * say so once, naming why.
     */
    private void fellBack(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def) {
        if (engine.playerId(store, ref) == null) {
            return;
        }
        warnFallbackOnce(def, ReputationTracks.track(def) == null
                ? "it has no track (a ladder of fewer than two ranks, an id no track can take, or the tracks not "
                        + "built yet)"
                : "its track cannot be read (the track record never registered, the player not live on it yet, or "
                        + "its curve not loaded)");
    }

    /**
     * The fallback's own report, made the first time a standing falls back to the engine's own value (the way
     * zc's seams report a fallback): every reputation that falls back after it does the same, unsaid.
     *
     * @return true when this call is the one that reported it
     */
    static boolean warnFallbackOnce(@Nonnull ReputationDef def, @Nonnull String why) {
        if (!WARNED_FALLBACK.compareAndSet(false, true)) {
            return false;
        }
        SafeLog.warn("[reputation] the standing with '" + def.id() + "' is read and written in the engine's own "
                + "value, not its track, since " + why + "; any other reputation that falls back does the same, "
                + "and this is said once");
        return true;
    }

    @Nonnull
    private static String unsyncedKey(@Nonnull Climb climb, @Nonnull ReputationDef def) {
        return climb.player() + "|" + def.id().toLowerCase(Locale.ROOT);
    }

    /** Run {@code move} as this ledger's own track move. */
    @Nonnull
    private static TrackService.Result own(@Nonnull Supplier<TrackService.Result> move) {
        Boolean before = OWN_MOVE.get();
        OWN_MOVE.set(Boolean.TRUE);
        try {
            return move.get();
        } finally {
            OWN_MOVE.set(before);
        }
    }

    /** The reputation record the player at {@code ref} carries, or null for none (or no live player). */
    @Nullable
    private static ReputationComponent recordOf(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        return store == null || ref == null ? null : ReputationComponent.of(store, ref);
    }

    /**
     * Say once, at boot, which gameplay configs keep reputation per world: in their worlds the library keeps each
     * player's standing on its track and writes no copy into the world's one shared value, so NPC attitudes there
     * do not follow it.
     */
    static void warnPerWorldOnce(@Nonnull ReputationNative engine) {
        try {
            List<String> perWorld = engine.perWorldConfigs();
            if (!perWorld.isEmpty() && WARNED_PER_WORLD.compareAndSet(false, true)) {
                SafeLog.warn("[reputation] the gameplay config(s) " + perWorld + " keep reputation per world "
                        + "(ReputationStorage PerWorld): in those worlds each player's standing stays on their "
                        + "reputation tracks and is not copied into the world's shared value, so NPC attitudes "
                        + "there do not follow it");
            }
        } catch (Throwable t) {
            SafeLog.fine("[reputation] the per-world check failed: " + t.getMessage());
        }
    }
}
