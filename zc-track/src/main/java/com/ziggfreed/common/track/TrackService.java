package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.progress.gate.GateEpochs;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.stats.StatMirror;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.track.curve.CurveTable;
import com.ziggfreed.common.track.curve.TrackCurves;
import com.ziggfreed.common.track.event.TrackEvents;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one writer and reader of climbs. Every write is {@link #add} (a gain or a loss, through the gain seam),
 * {@link #prestige} or {@link #markPaidBelow}; every read is {@link #read} (a live subject, on its thread) or
 * {@link #load} (anyone, from any thread). All of them go through the {@link TrackStore} the key's subject kind
 * has ({@link TrackStores}).
 *
 * <p><b>A committed move</b> keeps the record with the marks of the unlock Claims it now owes, then writes those
 * Claims ({@link TrackUnlocks}: marks saved first, a Claim that cannot be written put back and saved again) and
 * fires {@code ZigTrackChangedEvent}. When a reading moved (the level, the best level or the prestige count;
 * {@link TrackChange#readingsMoved}) it also writes the level onto the track's {@code Mirror} stat and moves the
 * player's gate epoch, since the track readings are {@code Player}-volatile and a verdict kept over them must be
 * read again; a move inside one level (the value or its fraction alone) leaves both alone, which keeps a stream
 * of small gains off the gate's hot path. A move that changes nothing does none of it.
 *
 * <p><b>The value</b> is kept whole, with the fraction a gain left over carried into the next one, so
 * fractional gains add up exactly. A gain stops at the start of the track's {@code Cap} level (the fraction
 * dropped there, as the MMO's accumulator does); a loss stops at the curve's level 1. Writes are the live
 * subject's own thread only; an offline player's climb is read, never written.
 */
public final class TrackService {

    /** The prefix of a mirror's modifier key on the native stat, followed by the track id. */
    public static final String MIRROR_KEY_PREFIX = "ziggfreedcommon:track:";

    /** What a write did. */
    public enum Status {
        /** The climb moved. */
        CHANGED,
        /** Nothing moved: a gain at the cap, a loss at the bottom, a gain the seam landed as nothing. */
        UNCHANGED,
        /** No such track on this server: unknown, switched off, gated out, or its curve not loaded. */
        UNKNOWN,
        /** The player does not meet the track's {@code Requires}. */
        LOCKED,
        /** The subject is not live here (offline, between worlds), or no store keeps its kind. */
        NOT_LIVE,
        /** The write asked for nothing (a zero, or not a number). */
        ZERO,
        /** A prestige the track does not allow: no prestige, or every rank already taken. */
        NOT_ALLOWED,
        /** A prestige whose level the current climb has not reached yet. */
        NOT_READY
    }

    /** What a write did, and the move when one was committed. */
    public record Result(@Nonnull Status status, @Nullable TrackChange change) {

        static Result of(@Nonnull Status status) {
            return new Result(status, null);
        }

        /** Did the climb move? */
        public boolean moved() {
            return status == Status.CHANGED;
        }
    }

    /** The gain seam ({@link TrackGain}); the library's default lands every gain as asked. */
    @Nonnull
    private static volatile TrackGain gain = TrackGain.AS_ASKED;

    private TrackService() {
    }

    // ==================== the seam ====================

    /**
     * Put {@code pipeline} in front of every {@link #add}; null restores the library's default, which lands every
     * gain as asked. One pipeline per server: a second install replaces the first.
     */
    public static void gain(@Nullable TrackGain pipeline) {
        gain = pipeline == null ? TrackGain.AS_ASKED : pipeline;
    }

    /** The gain seam in force. */
    @Nonnull
    public static TrackGain gain() {
        return gain;
    }

    // ==================== reads ====================

    /**
     * The usable track {@code trackId} names (any case): switched on, its id usable, its curve tabulated; null
     * otherwise.
     */
    @Nullable
    public static TrackDef def(@Nullable String trackId) {
        TrackAsset track = TrackConfig.getInstance().live(trackId);
        CurveTable table = track == null ? null : TrackCurves.table(track.curveId());
        return table == null ? null : new TrackDef(track, table);
    }

    /** Every usable track, by {@code Order} then id. */
    @Nonnull
    public static List<TrackDef> defs() {
        List<TrackDef> out = new ArrayList<>();
        for (TrackAsset track : TrackConfig.getInstance().liveTracks()) {
            CurveTable table = TrackCurves.table(track.curveId());
            if (table != null) {
                out.add(new TrackDef(track, table));
            }
        }
        return out;
    }

    /**
     * {@code key}'s climb read on its live subject's own thread, or null when the track is not usable, the
     * subject is not live here, or no store keeps its kind.
     */
    @Nullable
    public static TrackReading read(@Nonnull TrackKey key) {
        TrackDef def = def(key.track());
        TrackStore store = def == null ? null : TrackStores.forKey(key);
        TrackRecord record = store == null ? null : store.get(key);
        return record == null ? null : new TrackReading(key, def, record);
    }

    /**
     * {@code key}'s climb, live or not, from any thread; null (completed) when the track is not usable or no store
     * keeps its subject's kind. An offline player's climb is read from their saved file.
     */
    @Nonnull
    public static CompletableFuture<TrackReading> load(@Nonnull TrackKey key) {
        TrackDef def = def(key.track());
        TrackStore store = def == null ? null : TrackStores.forKey(key);
        if (store == null) {
            return CompletableFuture.completedFuture(null);
        }
        return store.load(key).thenApply(record -> new TrackReading(key, def, record));
    }

    // ==================== writes ====================

    /**
     * Move {@code key}'s climb by {@code amount} (negative to lose), through the gain seam: the one write path
     * every gain takes. On the live subject's own thread.
     *
     * @param source what asked: a reward's source id, {@code admin}, a moment's own label
     */
    @Nonnull
    public static Result add(@Nonnull TrackKey key, double amount, @Nonnull String source) {
        TrackDef def = def(key.track());
        if (def == null) {
            return Result.of(Status.UNKNOWN);
        }
        if (!Double.isFinite(amount) || amount == 0.0) {
            return Result.of(Status.ZERO);
        }
        TrackStore store = TrackStores.forKey(key);
        TrackRecord before = store == null ? null : store.get(key);
        if (before == null) {
            return Result.of(Status.NOT_LIVE);
        }
        Ref<EntityStore> ref = liveRef(key);
        GateSpec requires = def.asset().liveRequires();
        if (requires != null && ref != null
                && !StoreGates.passes(requires, GateQuery.of(FactorContext.about(ref.getStore(), ref)))) {
            return Result.of(Status.LOCKED);
        }
        double landed;
        try {
            landed = gain.apply(key, def.asset(), amount, source);
        } catch (Throwable t) {
            SafeLog.warn("[track] the gain seam failed on '" + key.track() + "', so the gain lands as asked: "
                    + t.getMessage());
            landed = amount;
        }
        if (!Double.isFinite(landed) || landed == 0.0) {
            return Result.of(Status.UNCHANGED);
        }
        TrackRecord after = moved(def, before, landed);
        if (after.equals(before)) {
            return Result.of(Status.UNCHANGED);
        }
        return commit(store, key, def, before, after, source, ref);
    }

    /**
     * Start {@code key}'s climb again (R582, R615): the current value back to where a new climb starts, the
     * prestige count up one, the best level ever reached kept, so every gate and every unlock paid stays. The nth
     * prestige needs the current level at the track's {@code Prestige.Base} plus n. What happens to the picks
     * made on it is the track's build setting ({@link TrackDef#prestigeBuild}), the picks' own to act on. On the
     * live subject's own thread.
     */
    @Nonnull
    public static Result prestige(@Nonnull TrackKey key, @Nonnull String source) {
        TrackDef def = def(key.track());
        if (def == null) {
            return Result.of(Status.UNKNOWN);
        }
        TrackStore store = TrackStores.forKey(key);
        TrackRecord before = store == null ? null : store.get(key);
        if (before == null) {
            return Result.of(Status.NOT_LIVE);
        }
        TrackAsset track = def.asset();
        int done = before.prestige();
        if (!track.prestiges() || done >= track.prestigeRanks()) {
            return Result.of(Status.NOT_ALLOWED);
        }
        int level = def.level(before);
        if (level < track.prestigeLevel(done + 1)) {
            return Result.of(Status.NOT_READY);
        }
        TrackRecord after = before.withBest(def.best(before)).withValue(def.start(), 0.0).withPrestige(done + 1);
        return commit(store, key, def, before, after, source, liveRef(key));
    }

    /**
     * Mark every unlock row of {@code key}'s track paid through the level below {@code level}, so no Claim is
     * written for a level under it: what a one-time migration calls for a player whose rewards an older system
     * already paid. Marks only rise; nothing is paid or announced. On the live subject's own thread.
     *
     * @return how many rows' marks moved, or -1 when the track is not usable or the subject is not live here
     */
    public static int markPaidBelow(@Nonnull TrackKey key, int level) {
        TrackDef def = def(key.track());
        TrackStore store = def == null ? null : TrackStores.forKey(key);
        TrackRecord before = store == null ? null : store.get(key);
        if (before == null) {
            return -1;
        }
        Set<String> rows = new LinkedHashSet<>();
        for (TrackAsset.Unlock unlock : def.asset().unlocks()) {
            rows.add(unlock.key());
        }
        TrackRecord after = before.withAllPaidThrough(rows, level - 1);
        if (after.equals(before)) {
            return 0;
        }
        int moved = 0;
        for (String row : rows) {
            if (after.paidThrough(row) != before.paidThrough(row)) {
                moved++;
            }
        }
        return store.put(key, after) ? moved : -1;
    }

    /**
     * A player's settle at login (and after any world entry): every usable track's level written onto its mirror
     * stat, and every unlock Claim owed and not yet parked (a row added since, a pass that stopped at its limit,
     * or a Claim that could not be written) parked now. On the player's world thread.
     */
    public static void settle(@Nonnull UUID playerId) {
        Ref<EntityStore> ref = PlayerAccess.liveRef(playerId);
        if (ref == null) {
            return;
        }
        for (TrackDef def : defs()) {
            TrackKey key = TrackKey.player(playerId, def.asset().getId());
            TrackStore store = TrackStores.forKey(key);
            TrackRecord record = store == null ? null : store.get(key);
            if (record == null) {
                continue;
            }
            mirror(ref, def, record);
            if (!def.asset().unlocks().isEmpty()) {
                keepPaying(store, key, def, record, record, playerId, queryOf(ref));
            }
        }
    }

    // ==================== the pieces ====================

    /**
     * {@code before} moved by {@code amount}, pure: the fraction carried, a gain stopped at the track's ceiling
     * (the fraction dropped there), a loss stopped at the curve's level 1, and the best level raised to wherever
     * the value now stands.
     */
    @Nonnull
    static TrackRecord moved(@Nonnull TrackDef def, @Nonnull TrackRecord before, double amount) {
        long ceiling = def.ceiling();
        long floor = def.table().minimum();
        double accumulated = before.fraction() + amount;
        long whole = (long) accumulated;
        double fraction = accumulated - whole;
        long value = before.value();
        if (amount > 0) {
            if (value >= ceiling) {
                return before.withValue(value, 0.0);
            }
            value = CurveTable.saturatingAdd(value, whole);
            if (value >= ceiling) {
                value = ceiling;
                fraction = 0.0;
            }
        } else {
            if (value <= floor) {
                return before.withValue(value, 0.0);
            }
            value = CurveTable.saturatingAdd(value, whole);
            if (value <= floor) {
                value = floor;
                fraction = 0.0;
            }
        }
        TrackRecord moved = before.withValue(value, fraction);
        return moved.withBest(def.table().levelOf(value));
    }

    /**
     * Keep a move and announce it: the record and the Claims it owes, then, when a reading moved, the mirror and
     * the epoch; the event always.
     */
    @Nonnull
    private static Result commit(@Nonnull TrackStore store, @Nonnull TrackKey key, @Nonnull TrackDef def,
            @Nonnull TrackRecord before, @Nonnull TrackRecord after, @Nonnull String source,
            @Nullable Ref<EntityStore> ref) {
        UUID playerId = key.playerId();
        TrackRecord kept = after;
        int claims = 0;
        if (playerId != null && key.isDefault() && !def.asset().unlocks().isEmpty()) {
            TrackUnlocks.Parked parked = keepPaying(store, key, def, before, after, playerId,
                    ref == null ? null : queryOf(ref));
            if (parked == null) {
                return Result.of(Status.NOT_LIVE);
            }
            kept = parked.record();
            claims = parked.claims();
        } else if (!store.put(key, kept)) {
            return Result.of(Status.NOT_LIVE);
        }
        TrackChange change = new TrackChange(key, def, before, kept, source, claims);
        if (change.readingsMoved()) {
            if (ref != null && key.isDefault()) {
                mirror(ref, def, kept);
            }
            GateEpochs.bumpPlayer(playerId);
        }
        TrackEvents.fireChanged(change);
        return new Result(Status.CHANGED, change);
    }

    /**
     * Keep {@code record} with the unlock Claims it owes up to its best level, in the order that never loses or
     * doubles one: the moved marks saved first (when they differ from {@code stored}, what the store holds now),
     * then each Claim written, and the record saved again when a Claim could not be written and its row's mark
     * was put back, so the next pass writes it.
     *
     * @return what was kept and how many Claims were written, or null when the store refused the first save
     */
    @Nullable
    private static TrackUnlocks.Parked keepPaying(@Nonnull TrackStore store, @Nonnull TrackKey key,
            @Nonnull TrackDef def, @Nonnull TrackRecord stored, @Nonnull TrackRecord record, @Nonnull UUID playerId,
            @Nullable GateQuery query) {
        TrackUnlocks.Plan plan = TrackUnlocks.plan(playerId, query, def, record, def.best(record));
        if (!plan.record().equals(stored) && !store.put(key, plan.record())) {
            return null;
        }
        if (plan.owed().isEmpty()) {
            return new TrackUnlocks.Parked(plan.record(), 0);
        }
        TrackUnlocks.Parked paid = TrackUnlocks.pay(playerId, def, plan, System.currentTimeMillis());
        if (!paid.record().equals(plan.record()) && !store.put(key, paid.record())) {
            SafeLog.warn("[track] a put-back unlock mark of '" + def.id() + "' for " + playerId + " could not be "
                    + "saved, so that Claim waits for the player's next login");
        }
        return paid;
    }

    /** Write {@code record}'s level onto the track's mirror stat, when it names one. World thread. */
    static void mirror(@Nonnull Ref<EntityStore> ref, @Nonnull TrackDef def, @Nonnull TrackRecord record) {
        String stat = def.asset().mirrorStat();
        if (stat == null || !ref.isValid()) {
            return;
        }
        int level = def.asset().mirrorsCurrent() ? def.level(record) : def.best(record);
        try {
            StatMirror.set(ref.getStore(), ref, stat, MIRROR_KEY_PREFIX + def.id(), level);
        } catch (Throwable t) {
            SafeLog.fine("[track] the mirror of '" + def.id() + "' could not be written: " + t.getMessage());
        }
    }

    /** The player's live entity for {@code key}, or null for another kind of subject or a player not here. */
    @Nullable
    private static Ref<EntityStore> liveRef(@Nonnull TrackKey key) {
        UUID playerId = key.playerId();
        return playerId == null ? null : PlayerAccess.liveRef(playerId);
    }

    @Nonnull
    private static GateQuery queryOf(@Nonnull Ref<EntityStore> ref) {
        return GateQuery.of(FactorContext.about(ref.getStore(), ref));
    }

}
