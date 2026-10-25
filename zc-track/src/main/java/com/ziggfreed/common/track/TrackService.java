package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.Collection;
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
import com.ziggfreed.common.track.asset.DerivedReading;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.track.curve.CurveTable;
import com.ziggfreed.common.track.curve.TrackCurves;
import com.ziggfreed.common.track.event.TrackEvents;
import com.ziggfreed.common.track.gain.GainPipeline;
import com.ziggfreed.common.track.gain.RestedFactor;
import com.ziggfreed.common.track.pick.PickService;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one writer and reader of climbs. Every write is {@link #add} (a gain or a loss, through the gain seam),
 * {@link #set} (an exact value, past the seam; held in the track's range for a value that already moved somewhere
 * else), {@link #prestige}, a migration's silent {@link #seed} into an empty climb and {@link #raiseBest}, or
 * {@link #markPaidBelow} and {@link #markPaidThrough}; every read is
 * {@link #read} (a live subject, on its thread) or
 * {@link #load} (anyone, from any thread). All of them go through the {@link TrackStore} the key's subject kind
 * has ({@link TrackStores}).
 *
 * <p><b>A gain</b> runs through the gain seam first ({@link TrackGain}, the library's {@code gain.GainPipeline}:
 * the track's named factors, compiled once per player and track); {@link #explain} is the same write saying what
 * each factor did.
 *
 * <p><b>A committed move</b> keeps the new record first. When a reading moved (the level, the best level or the
 * prestige count; {@link TrackChange#readingsMoved}) it then writes the level onto the track's {@code Mirror} stat
 * and moves the player's gate epoch, since the track readings are {@code Player}-volatile and a verdict kept over
 * them must be read again; a move inside one level (the value or its fraction alone) leaves both alone, which
 * keeps a stream of small gains off the gate's hot path. Only then are the unlock Claims it owes worked out, so a
 * reward gated on the track's own reading reads the move that pays it, and written ({@link TrackUnlocks}: marks
 * saved first, a Claim that cannot be written put back and saved again), and {@code ZigTrackChangedEvent} fires.
 * A move that changes nothing does none of it.
 *
 * <p><b>The value</b> is kept whole, with the fraction a gain left over carried into the next one, so
 * fractional gains add up exactly. A gain stops at the start of the track's {@code Cap} level, or of a lower cap a
 * factor set for this player ({@link GainCall#capAtLevel}), the fraction dropped there as the MMO's accumulator
 * does; a loss stops at the curve's level 1. Writes are the live
 * subject's own thread only; an offline player's climb is read, never written.
 *
 * <p><b>A derived track</b> ({@code Derived}) keeps no value: its readings read its sources live
 * ({@link TrackReading#isDerived}, {@link #derivation}), a gain or a set on a Highest one lands on its highest source
 * (the first listed on a tie) and is that source's move in every way, one on a Sum one is refused
 * ({@code DERIVED_SUM}), and it never prestiges.
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
        NOT_READY,
        /** A write on a derived track that sums its sources: it holds nothing of its own, so nothing is written. */
        DERIVED_SUM
    }

    /**
     * What a write did, the move when one was committed, and what each gain factor did when {@link #explain} asked
     * (null otherwise).
     */
    public record Result(@Nonnull Status status, @Nullable TrackChange change, @Nullable GainBreakdown breakdown) {

        static Result of(@Nonnull Status status) {
            return new Result(status, null, null);
        }

        /** Did the climb move? */
        public boolean moved() {
            return status == Status.CHANGED;
        }

        /** This result with {@code explained} as its breakdown. */
        @Nonnull
        Result explained(@Nullable GainBreakdown explained) {
            return explained == null ? this : new Result(status, change, explained);
        }
    }

    /** The gain seam ({@link TrackGain}); the library's default is the gain pipeline. */
    @Nonnull
    private static volatile TrackGain gain = GainPipeline.INSTANCE;

    private TrackService() {
    }

    // ==================== the seam ====================

    /**
     * Put {@code pipeline} in front of every {@link #add} in place of the library's gain pipeline; null restores
     * the pipeline. One seam per server: a second install replaces the first. A consumer adding one factor
     * registers it ({@code gain.GainFactors}) instead.
     */
    public static void gain(@Nullable TrackGain pipeline) {
        gain = pipeline == null ? GainPipeline.INSTANCE : pipeline;
    }

    /** The gain seam in force. */
    @Nonnull
    public static TrackGain gain() {
        return gain;
    }

    // ==================== reads ====================

    /**
     * The usable track {@code trackId} names (any case): switched on, its id usable, its curve tabulated, and, for a
     * derived track, at least one source usable; null otherwise.
     */
    @Nullable
    public static TrackDef def(@Nullable String trackId) {
        TrackAsset track = TrackConfig.getInstance().live(trackId);
        CurveTable table = track == null ? null : TrackCurves.table(track.curveId());
        return table == null || !usable(track, table) ? null : new TrackDef(track, table);
    }

    /** Does {@code trackId} (any case) name a usable track, the one {@link #def} answers? */
    public static boolean isUsable(@Nullable String trackId) {
        return def(trackId) != null;
    }

    /** Every usable track, by {@code Order} then id. */
    @Nonnull
    public static List<TrackDef> defs() {
        List<TrackDef> out = new ArrayList<>();
        for (TrackAsset track : TrackConfig.getInstance().liveTracks()) {
            CurveTable table = TrackCurves.table(track.curveId());
            if (table != null && usable(track, table)) {
                out.add(new TrackDef(track, table));
            }
        }
        return out;
    }

    /** Is a loaded {@code track} usable: an ordinary one always, a derived one while one of its sources is? */
    private static boolean usable(@Nonnull TrackAsset track, @Nonnull CurveTable table) {
        return !track.isDerived() || TrackDerivations.of(track, table) != null;
    }

    /**
     * The derivation of the derived track {@code trackId} names (any case): how it reads its sources and which of
     * them are usable, in order. Null for an ordinary track and an unusable one. Kept until a track or curve fold.
     */
    @Nullable
    public static TrackDerivation derivation(@Nullable String trackId) {
        TrackAsset track = TrackConfig.getInstance().live(trackId);
        if (track == null || !track.isDerived()) {
            return null;
        }
        CurveTable table = TrackCurves.table(track.curveId());
        return table == null ? null : TrackDerivations.of(track, table);
    }

    /**
     * The usable derived tracks that read {@code trackId} (any case), by {@code Order} then id: whose reading a move
     * of {@code trackId} may have moved. Empty for none. Kept until a track or curve fold.
     */
    @Nonnull
    public static List<TrackDef> derivedOver(@Nullable String trackId) {
        return TrackDerivations.over(trackId);
    }

    /**
     * {@code key}'s climb read on its live subject's own thread, or null when the track is not usable, the
     * subject is not live here, or no store keeps its kind. A derived track's reading carries each source's, read
     * for the same subject.
     */
    @Nullable
    public static TrackReading read(@Nonnull TrackKey key) {
        TrackDef def = def(key.track());
        TrackStore store = def == null ? null : TrackStores.forKey(key);
        TrackRecord record = store == null ? null : store.get(key);
        if (record == null) {
            return null;
        }
        TrackDerivation derivation = def.asset().isDerived() ? derivation(def.asset().getId()) : null;
        if (derivation == null) {
            return new TrackReading(key, def, record);
        }
        List<TrackReading> sources = new ArrayList<>(derivation.sources().size());
        for (TrackDef source : derivation.sources()) {
            TrackKey sourceKey = key.onTrack(source.id());
            TrackRecord kept = store.get(sourceKey);
            if (kept == null) {
                return null;
            }
            sources.add(new TrackReading(sourceKey, source, kept));
        }
        return new TrackReading(key, def, record, sources);
    }

    /**
     * {@code key}'s climb, live or not, from any thread; null (completed) when the track is not usable or no store
     * keeps its subject's kind. An offline player's climb is read from their saved file; a derived track's sources
     * are read the same way.
     */
    @Nonnull
    public static CompletableFuture<TrackReading> load(@Nonnull TrackKey key) {
        TrackDef def = def(key.track());
        TrackStore store = def == null ? null : TrackStores.forKey(key);
        if (store == null) {
            return CompletableFuture.completedFuture(null);
        }
        TrackDerivation derivation = def.asset().isDerived() ? derivation(def.asset().getId()) : null;
        if (derivation == null) {
            return store.load(key).thenApply(record -> new TrackReading(key, def, record));
        }
        List<TrackKey> keys = new ArrayList<>();
        List<CompletableFuture<TrackRecord>> loads = new ArrayList<>();
        for (TrackDef source : derivation.sources()) {
            TrackKey sourceKey = key.onTrack(source.id());
            keys.add(sourceKey);
            loads.add(store.load(sourceKey));
        }
        CompletableFuture<TrackRecord> own = store.load(key);
        return CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[0])).thenCombine(own,
                (ignored, record) -> {
                    List<TrackReading> sources = new ArrayList<>(keys.size());
                    for (int i = 0; i < keys.size(); i++) {
                        sources.add(new TrackReading(keys.get(i), derivation.sources().get(i), loads.get(i).join()));
                    }
                    return new TrackReading(key, def, record, sources);
                });
    }

    /**
     * Where a write on the derived track {@code key} names lands: its highest source's climb under Highest (the first
     * listed on a tie, read live), or a refusal: {@code DERIVED_SUM} under Sum, {@code NOT_LIVE} when a source's
     * climb cannot be read here.
     */
    @Nonnull
    private static Forward forward(@Nonnull TrackKey key, @Nonnull TrackDerivation derivation) {
        if (derivation.reading() == DerivedReading.SUM) {
            return new Forward(null, Status.DERIVED_SUM);
        }
        TrackStore store = TrackStores.forKey(key);
        if (store == null) {
            return new Forward(null, Status.NOT_LIVE);
        }
        boolean[] unread = {false};
        TrackDef target = derivation.writeTarget(source -> {
            TrackRecord kept = store.get(key.onTrack(source.id()));
            if (kept == null) {
                unread[0] = true;
                return 0;
            }
            return source.level(kept);
        });
        return unread[0] || target == null ? new Forward(null, Status.NOT_LIVE)
                : new Forward(key.onTrack(target.id()), Status.CHANGED);
    }

    /** Where a derived write lands ({@code key}), or why it lands nowhere ({@code refused}). */
    private record Forward(@Nullable TrackKey key, @Nonnull Status refused) {
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
        return add(key, amount, source, false);
    }

    /**
     * {@link #add}, saying what each gain factor did ({@link Result#breakdown()}): the breakdown is built only on
     * this path, never on an ordinary gain.
     */
    @Nonnull
    public static Result explain(@Nonnull TrackKey key, double amount, @Nonnull String source) {
        return add(key, amount, source, true);
    }

    @Nonnull
    private static Result add(@Nonnull TrackKey key, double amount, @Nonnull String source, boolean explain) {
        TrackDef def = def(key.track());
        if (def == null) {
            return Result.of(Status.UNKNOWN);
        }
        if (!Double.isFinite(amount) || amount == 0.0) {
            return Result.of(Status.ZERO);
        }
        TrackDerivation derivation = def.asset().isDerived() ? derivation(def.asset().getId()) : null;
        if (derivation != null) {
            // A derived track holds no value: the gain lands on its highest source, through that one's own factors.
            Forward to = forward(key, derivation);
            return to.key() == null ? Result.of(to.refused()) : add(to.key(), amount, source, explain);
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
        GainCall call = new GainCall(key, def, before, ref, amount, source, explain);
        double landed;
        try {
            landed = gain.apply(call);
        } catch (Throwable t) {
            SafeLog.warn("[track] the gain seam failed on '" + key.track() + "', so the gain lands as asked: "
                    + t.getMessage());
            call.record(before);
            landed = amount;
        }
        if (!Double.isFinite(landed)) {
            landed = 0.0;
        }
        TrackRecord base = call.record();
        TrackRecord after = landed == 0.0 ? base : moved(def, base, landed, call.capLevel());
        if (after.equals(before)) {
            return Result.of(Status.UNCHANGED).explained(explain ? breakdown(call, landed, before, after) : null);
        }
        Result result = commit(store, key, def, before, after, source, ref, amount);
        return result.explained(explain ? breakdown(call, landed, before, after) : null);
    }

    /**
     * Put {@code key}'s climb at exactly {@code value}, with {@code fraction} carried into the next gain, past the
     * gain seam: what an admin's set, a consumer's raw write and a one-time move from an older system call, where no
     * factor may scale the number and the track's own {@code Requires} is not asked. A value below the curve's level
     * 1 reads as that floor; the track's {@code Cap} does not hold it, so a move from a system that let a climb run
     * further keeps every point. The best level is raised to where the value now stands and never lowered. Then the
     * move is kept and announced as a gain is (the mirror, the gate epoch, the unlock Claims it owes, and
     * {@code ZigTrackChangedEvent}, whose {@code asked} is how far the value moved): a caller moving a climb an older
     * system already paid for marks those rows first ({@link #markPaidBelow}). A set on a derived track lands on its
     * highest source as a gain does, and one on a Sum track is refused ({@code DERIVED_SUM}). On the live subject's
     * own thread.
     *
     * @param source what asked: {@code admin}, a consumer's own label, a migration's id
     */
    @Nonnull
    public static Result set(@Nonnull TrackKey key, long value, double fraction, @Nonnull String source) {
        TrackDef def = def(key.track());
        if (def == null) {
            return Result.of(Status.UNKNOWN);
        }
        TrackDerivation derivation = def.asset().isDerived() ? derivation(def.asset().getId()) : null;
        if (derivation != null) {
            Forward to = forward(key, derivation);
            return to.key() == null ? Result.of(to.refused()) : set(to.key(), value, fraction, source);
        }
        TrackStore store = TrackStores.forKey(key);
        TrackRecord before = store == null ? null : store.get(key);
        if (before == null) {
            return Result.of(Status.NOT_LIVE);
        }
        long held = Math.max(def.table().minimum(), value);
        double carried = Double.isFinite(fraction) && fraction > 0.0 && fraction < 1.0 ? fraction : 0.0;
        TrackRecord after = before.withValue(held, carried)
                .withBest(Math.max(def.best(before), def.table().levelOf(held)));
        if (after.equals(before)) {
            return Result.of(Status.UNCHANGED);
        }
        return commit(store, key, def, before, after, source, liveRef(key), (double) (held - before.value()));
    }

    @Nonnull
    private static GainBreakdown breakdown(@Nonnull GainCall call, double landed, @Nonnull TrackRecord before,
            @Nonnull TrackRecord after) {
        return new GainBreakdown(call.key(), call.asked(), landed, after.value() - before.value(), call.explained());
    }

    /**
     * Start {@code key}'s climb again (R582, R615): the current value back to where a new climb starts, the
     * prestige count up one, the best level ever reached kept, so every gate and every unlock paid stays. The nth
     * prestige needs the current level at the track's {@code Prestige.Base} plus n. The picks its
     * {@code Prestige.Resets} group resets, kind by kind (R852, R876: by default the tree's picks and Techniques),
     * follow the track's build setting ({@link TrackDef#prestigeBuild}, R641): {@code Keep} leaves them,
     * {@code Remember} puts each slot aside to be offered again preselected as its level comes back, {@code Clear}
     * clears them; every other pick ({@code PickService.keptThroughPrestige}: by default the Mastery row, what a
     * trainer taught and learned abilities) stays under every build, held by design even when a gate it once met (a
     * branch's points) no longer is: nothing re-gates it, at the rebuild or after. The Resets group governs a
     * prestige alone; a respec clears every pick but what a trainer taught. Then the player's grants are rebuilt and
     * the track's undo window closes ({@code PickService.prestiged}). On the live subject's own thread.
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
        if (track.isDerived() || !track.prestiges() || done >= track.prestigeRanks()) {
            return Result.of(Status.NOT_ALLOWED);
        }
        int level = def.level(before);
        if (level < track.prestigeLevel(done + 1)) {
            return Result.of(Status.NOT_READY);
        }
        TrackRecord after = before.withBest(def.best(before)).withValue(def.start(), 0.0).withPrestige(done + 1)
                .withPicks(before.picks().afterPrestige(def.prestigeBuild(), PickService.keptThroughPrestige(def)));
        Result result = commit(store, key, def, before, after, source, liveRef(key), Double.NaN);
        UUID playerId = key.playerId();
        if (result.moved() && playerId != null && key.isDefault()) {
            PickService.prestiged(playerId, def, !after.picks().equals(before.picks()));
        }
        return result;
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
        if (def == null) {
            return -1;
        }
        Set<String> rows = new LinkedHashSet<>();
        for (TrackAsset.Unlock unlock : def.asset().unlocks()) {
            rows.add(unlock.key());
        }
        return markPaidThrough(key, rows, level - 1);
    }

    /**
     * Mark the rows {@code rows} keys (a row's key, as {@link TrackAsset.Unlock#key()} spells it, or a mark a
     * module keeps on the record under a key of its own) paid through {@code level} on {@code key}'s climb.
     * Marks only rise; nothing is paid or announced. On the live subject's own thread.
     *
     * @return how many rows' marks moved, or -1 when the track is not usable or the subject is not live here
     */
    public static int markPaidThrough(@Nonnull TrackKey key, @Nonnull Collection<String> rows, int level) {
        TrackDef def = def(key.track());
        TrackStore store = def == null ? null : TrackStores.forKey(key);
        TrackRecord before = store == null ? null : store.get(key);
        if (before == null) {
            return -1;
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String row : rows) {
            String normalized = TrackKey.normalize(row);
            if (!normalized.isEmpty()) {
                keys.add(normalized);
            }
        }
        TrackRecord after = before.withAllPaidThrough(keys, level);
        if (after.equals(before)) {
            return 0;
        }
        int moved = 0;
        for (String row : keys) {
            if (after.paidThrough(row) != before.paidThrough(row)) {
                moved++;
            }
        }
        return store.put(key, after) ? moved : -1;
    }

    /**
     * Raise {@code key}'s best level to {@code level} when it stands lower: what a one-time import calls when the
     * older system's record says the subject once stood higher than the climb it merged into shows. The best level
     * only rises; nothing is paid or announced, so an unlock Claim the higher best now owes is parked at the
     * player's next settle, as after a {@link #seed}; the mirror stat is written and the player's gate epoch moved,
     * since a reading moved. On the live subject's own thread.
     *
     * @return true when it rose
     */
    public static boolean raiseBest(@Nonnull TrackKey key, int level) {
        TrackDef def = def(key.track());
        TrackStore store = def == null ? null : TrackStores.forKey(key);
        TrackRecord before = store == null ? null : store.get(key);
        if (before == null) {
            return false;
        }
        TrackRecord after = before.withBest(level);
        if (after.equals(before) || !store.put(key, after)) {
            return false;
        }
        Ref<EntityStore> ref = liveRef(key);
        if (ref != null && key.isDefault()) {
            mirror(ref, def, after);
        }
        GateEpochs.bumpPlayer(key.playerId());
        return true;
    }

    /**
     * Put {@code key}'s current climb at {@code value} held between the curve's level 1 and the track's ceiling, the
     * fraction dropped: what a write that already happened somewhere else is folded in as (an engine value an admin
     * moved, which the track's own value follows, never past the track's top). Otherwise the exact write
     * {@link #set(TrackKey, long, double, String)} is: kept, paid and announced, with no {@code Requires} asked,
     * since it already happened. On the live subject's own thread.
     *
     * @param source what moved it: {@code native}, {@code admin}
     */
    @Nonnull
    public static Result set(@Nonnull TrackKey key, long value, @Nonnull String source) {
        TrackDef def = def(key.track());
        return def == null ? Result.of(Status.UNKNOWN) : set(key, inRange(def, value), 0.0, source);
    }

    /**
     * Keep {@code record} as {@code key}'s climb when nothing is on record for it yet: the one-time import of a
     * climb an older system kept. Its value is held between the curve's level 1 and the track's ceiling, its best
     * level raised to where that value stands, and the marks it carries kept as the older system's word on what
     * it already paid. Nothing is paid, announced or credited, so an import is silent; the mirror stat is written
     * and the player's gate epoch moved, since a reading may have moved. A climb already on record is never
     * overwritten ({@code UNCHANGED}). On the live subject's own thread.
     *
     * @param source what imported it
     */
    @Nonnull
    public static Result seed(@Nonnull TrackKey key, @Nonnull TrackRecord record, @Nonnull String source) {
        TrackDef def = def(key.track());
        if (def == null) {
            return Result.of(Status.UNKNOWN);
        }
        TrackStore store = TrackStores.forKey(key);
        TrackRecord before = store == null ? null : store.get(key);
        if (before == null) {
            return Result.of(Status.NOT_LIVE);
        }
        if (!before.isEmpty()) {
            return Result.of(Status.UNCHANGED);
        }
        long value = inRange(def, record.value());
        TrackRecord after = record.withValue(value, 0.0).withBest(def.table().levelOf(value));
        if (after.equals(before)) {
            return Result.of(Status.UNCHANGED);
        }
        if (!store.put(key, after)) {
            return Result.of(Status.NOT_LIVE);
        }
        Ref<EntityStore> ref = liveRef(key);
        if (ref != null && key.isDefault()) {
            mirror(ref, def, after);
        }
        GateEpochs.bumpPlayer(key.playerId());
        return new Result(Status.CHANGED, new TrackChange(key, def, before, after, source, 0), null);
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
        // How long the player was away, asked once per login: a world change counts none.
        TrackComponent held = PlayerTrackStore.live(ref.getStore(), ref);
        long awayMs = held == null ? 0L : held.takeAwayMs(System.currentTimeMillis());
        for (TrackDef def : defs()) {
            if (def.asset().isDerived()) {
                // A derived track keeps no climb: nothing of its own to mirror, fill or pay.
                continue;
            }
            TrackKey key = TrackKey.player(playerId, def.asset().getId());
            TrackStore store = TrackStores.forKey(key);
            TrackRecord record = store == null ? null : store.get(key);
            if (record == null) {
                continue;
            }
            TrackAsset.Rested rested = def.asset().rested();
            if (rested != null && awayMs > 0L) {
                TrackRecord filled = record.withRested(RestedFactor.fillFor(def, record, rested, awayMs));
                if (filled != record && store.put(key, filled)) {
                    record = filled;
                }
            }
            mirror(ref, def, record);
            if (!def.asset().unlocks().isEmpty()) {
                keepPaying(store, key, def, record, record, playerId, queryOf(ref));
            }
        }
    }

    // ==================== the pieces ====================

    /** {@code value} held between the curve's level 1 and the track's ceiling. */
    private static long inRange(@Nonnull TrackDef def, long value) {
        return Math.max(def.table().minimum(), Math.min(def.ceiling(), value));
    }

    /**
     * {@code before} moved by {@code amount}, pure: the fraction carried, a gain stopped at the track's ceiling
     * (the fraction dropped there), a loss stopped at the curve's level 1, and the best level raised to wherever
     * the value now stands.
     */
    @Nonnull
    static TrackRecord moved(@Nonnull TrackDef def, @Nonnull TrackRecord before, double amount) {
        return moved(def, before, amount, 0);
    }

    /**
     * {@link #moved(TrackDef, TrackRecord, double)} with a gain also stopped at the start of {@code capLevel} (a
     * factor's per-player cap, {@link GainCall#capAtLevel}; 0 for none), under the track's own ceiling.
     */
    @Nonnull
    static TrackRecord moved(@Nonnull TrackDef def, @Nonnull TrackRecord before, double amount, int capLevel) {
        long ceiling = capLevel >= 1 ? Math.min(def.ceiling(), def.table().ceiling(capLevel)) : def.ceiling();
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
        // The best the record holds READS as its level when nothing is on record, so it is kept as read: a loss
        // never lowers it.
        return moved.withBest(Math.max(def.best(before), def.table().levelOf(value)));
    }

    /**
     * Keep a move and announce it: the move itself first, and, when a reading moved, the mirror and the epoch, so
     * the unlock pass after it reads the new climb (a reward gated on this track's own level sees the level that
     * pays it); then the Claims it owes, marks saved before Claims; the event always.
     */
    @Nonnull
    private static Result commit(@Nonnull TrackStore store, @Nonnull TrackKey key, @Nonnull TrackDef def,
            @Nonnull TrackRecord before, @Nonnull TrackRecord after, @Nonnull String source,
            @Nullable Ref<EntityStore> ref, double asked) {
        UUID playerId = key.playerId();
        if (!store.put(key, after)) {
            return Result.of(Status.NOT_LIVE);
        }
        // A move of the value is what any reading reads; a move of the rested pool alone (a gain the cap stopped
        // after the pool paid) is kept and announced the same way.
        TrackChange moved = new TrackChange(key, def, before, after, source, 0);
        if (moved.readingsMoved()) {
            if (ref != null && key.isDefault()) {
                mirror(ref, def, after);
            }
            GateEpochs.bumpPlayer(playerId);
        }
        TrackRecord kept = after;
        int claims = 0;
        if (playerId != null && key.isDefault() && !def.asset().unlocks().isEmpty()) {
            // A refused save of the marks writes no Claim; the settle at the next login pays what is owed.
            TrackUnlocks.Parked parked = keepPaying(store, key, def, after, after, playerId,
                    ref == null ? null : queryOf(ref));
            if (parked != null) {
                kept = parked.record();
                claims = parked.claims();
            }
        }
        TrackChange change = claims == 0 && kept == after ? moved
                : new TrackChange(key, def, before, kept, source, claims);
        TrackEvents.fireChanged(change, asked);
        return new Result(Status.CHANGED, change, null);
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
