package com.ziggfreed.common.reputation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.reputation.asset.ReputationAsset;
import com.ziggfreed.common.reputation.asset.ReputationConfig;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackRecord;
import com.ziggfreed.common.track.event.ZigTrackChangedEvent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Every reputation reading, the ONE zc writer and the rank checks. A reputation is a native
 * ReputationGroup, found by its id without regard to case; it is absent (unknown) while the engine's
 * plugin is off, the owner's switch is off or its companion says {@code Enabled: false}. Standing is what
 * the player earned, kept where the service's {@link ReputationLedger} keeps it (in production the
 * reputation's track, with the engine's own number a copy kept in step for NPC attitudes); worn gear adds
 * nothing to it, and instead raises how much each gain pays ({@link ReputationGain}). World thread for every
 * per-player call; every {@code store}/{@code ref} is asked of the engine seam first, so no live player means no
 * answer.
 */
public final class ReputationService {

    /** What a change did. CHANGED and UNCHANGED are successes; the rest are refusals. */
    public enum Status {
        CHANGED, UNCHANGED, UNKNOWN, ZERO, NOT_LIVE, NOT_WRITTEN
    }

    /** A change's status, and what landed when it CHANGED. */
    public record Result(@Nonnull Status status, @Nullable ReputationChange change) {

        public boolean refused() {
            return status != Status.CHANGED && status != Status.UNCHANGED;
        }
    }

    /**
     * What one player stands at with one reputation, read live: {@code earned} the current climb's standing,
     * {@code held} the standing the ranks read (the same, or after a prestige at least the floor of the best rank
     * ever reached), and {@code rank} where {@code held} falls.
     */
    public record Standing(@Nonnull ReputationDef reputation, int earned, boolean entry,
                           @Nullable ReputationLadder.Rank rank, int held) {

        /** A standing whose ranks read what was earned. */
        public Standing(@Nonnull ReputationDef reputation, int earned, boolean entry,
                @Nullable ReputationLadder.Rank rank) {
            this(reputation, earned, entry, rank, earned);
        }

        /** The standing every reading compares: what was earned (gear adds none), or what a prestige kept. */
        public long effective() {
            return held;
        }

        /** Met: something is recorded for them, or their standing is not 0. */
        public boolean met() {
            return entry || earned != 0;
        }
    }

    private final ReputationNative engine;
    private final ReputationFanOut fanOut;
    private final ReputationLedger ledger;
    private final ReputationRankWatch watch = new ReputationRankWatch();

    /** A service whose standing is the engine's own value alone ({@link NativeReputationLedger}). */
    public ReputationService(@Nonnull ReputationNative engine, @Nonnull ReputationFanOut fanOut) {
        this(engine, fanOut, new NativeReputationLedger(engine));
    }

    /** A service whose standing {@code ledger} keeps. */
    public ReputationService(@Nonnull ReputationNative engine, @Nonnull ReputationFanOut fanOut,
            @Nonnull ReputationLedger ledger) {
        this.engine = engine;
        this.fanOut = fanOut;
        this.ledger = ledger;
    }

    @Nonnull
    public ReputationNative engine() {
        return engine;
    }

    /** Where this service keeps standing. */
    @Nonnull
    public ReputationLedger ledger() {
        return ledger;
    }

    /** Is the module switched on and the engine's reputation plugin running? */
    public boolean isOn() {
        return ReputationConfig.getInstance().isGlobalEnabled() && engine.available();
    }

    /**
     * The server's shared ladder (the ranks every reputation shares), or {@link ReputationLadder#EMPTY} while
     * the module is off. One reputation's own ladder is {@link #ladderOf}.
     */
    @Nonnull
    public ReputationLadder ladder() {
        return isOn() ? ReputationLadder.of(engine.ranks()) : ReputationLadder.EMPTY;
    }

    /**
     * The ladder {@code reputationId} (any case) reads: the shared ranks plus its own tiers above the top
     * ({@link ReputationLadder#of(Collection, Map)}); the shared ladder for an unknown id, and
     * {@link ReputationLadder#EMPTY} while the module is off.
     */
    @Nonnull
    public ReputationLadder ladderOf(@Nullable String reputationId) {
        return isOn() ? ladderFor(known(reputationId)) : ReputationLadder.EMPTY;
    }

    /** {@link #ladderOf} for a reputation already in hand; the shared ladder for null. */
    @Nonnull
    public ReputationLadder ladderFor(@Nullable ReputationDef def) {
        if (!isOn()) {
            return ReputationLadder.EMPTY;
        }
        return ReputationLadder.of(engine.ranks(), def == null ? Map.of() : def.tierFloors());
    }

    /** The switched-on reputation {@code id} names (any case), or null. */
    @Nullable
    public ReputationDef known(@Nullable String id) {
        if (id == null || id.isBlank() || !isOn()) {
            return null;
        }
        String wanted = id.trim();
        for (ReputationNative.Group group : engine.groups()) {
            if (group.id().equalsIgnoreCase(wanted)) {
                return defOf(group);
            }
        }
        return null;
    }

    /** Every switched-on reputation. */
    @Nonnull
    public List<ReputationDef> all() {
        List<ReputationDef> out = new ArrayList<>();
        if (!isOn()) {
            return out;
        }
        for (ReputationNative.Group group : engine.groups()) {
            ReputationDef def = defOf(group);
            if (def != null) {
                out.add(def);
            }
        }
        return out;
    }

    /** What the live player at {@code ref} stands at with {@code id}; null when unknown or nobody is there. */
    @Nullable
    public Standing standing(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nullable String id) {
        ReputationDef def = known(id);
        if (def == null || !engine.live(store, ref)) {
            return null;
        }
        return standingOf(store, ref, def, ladderFor(def));
    }

    /** Every switched-on reputation the player has met, by Order, then id. */
    @Nonnull
    public List<Standing> met(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        List<Standing> out = new ArrayList<>();
        if (!engine.live(store, ref)) {
            return out;
        }
        for (ReputationDef def : all()) {
            Standing standing = standingOf(store, ref, def, ladderFor(def));
            if (standing != null && standing.met()) {
                out.add(standing);
            }
        }
        out.sort(Comparator.comparingInt((Standing s) -> s.reputation().order())
                .thenComparing(s -> s.reputation().id().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Has the player met any switched-on reputation? */
    public boolean anyMet(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        return !met(store, ref).isEmpty();
    }

    /**
     * The player's worn gain for {@code def}, in whole percent points: the global stat plus the reputation's
     * own, each its folded maximum, the total held by {@link ReputationGain#held}; 0 where neither stat is
     * loaded or the player is not live. {@link #change} scales a gain by it and the Reputation page shows it,
     * so both read one number. World thread.
     */
    public long wornGain(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def) {
        if (!engine.live(store, ref)) {
            return 0L;
        }
        return ReputationGain.held(engine.statMax(store, ref, ReputationGain.GLOBAL_STAT)
                + engine.statMax(store, ref, ReputationGain.statFor(def.id())));
    }

    /**
     * The one zc writer: refuse an unknown reputation or a zero delta, raise a gain by the player's worn
     * reputation gain ({@link #wornGain}, through {@link ReputationGain#scale}; a loss is never raised), cut it
     * at the Cap, write it where the ledger keeps standing (in production the reputation's track, through its
     * gain seam, then the engine's copy; with the engine as the truth its clamp applies), tell the fan-out, then
     * check the rank. The change it answers carries what actually moved. World thread.
     */
    @Nonnull
    public Result change(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nullable String id,
            int delta, @Nonnull String source) {
        ReputationDef def = known(id);
        if (def == null) {
            return new Result(Status.UNKNOWN, null);
        }
        if (delta == 0) {
            return new Result(Status.ZERO, null);
        }
        if (!engine.live(store, ref)) {
            return new Result(Status.NOT_LIVE, null);
        }
        UUID player = engine.playerId(store, ref);
        ReputationLadder ladder = ladderFor(def);
        ReputationLedger.Reading before = ledger.read(store, ref, def, ladder);
        if (player == null || before == null) {
            return new Result(Status.NOT_LIVE, null);
        }
        int gained = delta > 0 ? ReputationGain.scale(delta, wornGain(store, ref, def)) : delta;
        int applied = capped(before.earned(), gained, def.cap());
        if (applied == 0) {
            return new Result(Status.UNCHANGED, null);
        }
        ReputationLedger.Reading after = ledger.add(store, ref, def, ladder, applied, source);
        if (after == null) {
            return new Result(Status.NOT_WRITTEN, null);
        }
        if (after.earned() == before.earned() && after.held() == before.held()) {
            return new Result(Status.UNCHANGED, null);
        }
        return new Result(Status.CHANGED, announce(store, ref, player, def, ladder, before, after, source));
    }

    /**
     * Bring the live player's standing in step once they are in a world, for every switched-on reputation: the
     * one-time import onto its track and any outside write folded in ({@link ReputationLedger#settle}). The login
     * rank check runs after it. World thread.
     */
    public void settle(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (!isOn() || !engine.live(store, ref)) {
            return;
        }
        for (ReputationDef def : all()) {
            try {
                ledger.settle(store, ref, def, ladderFor(def));
            } catch (Throwable t) {
                SafeLog.warn("[reputation] bringing '" + def.id() + "' in step failed: " + t.getMessage());
            }
        }
    }

    /**
     * A climb moved through the track module: when it is a reputation's track and the ledger did not move it
     * itself (a Track reward, {@code /zigtrack}, a prestige; never an import or an outside write folded in, which
     * the ledger keeps silent), keep the engine's copy in step and tell it like any change: the event, the bar, a
     * Beyond payout it crossed and the rank check. On the player's own thread, where the track fires it.
     */
    public void trackChanged(@Nonnull ZigTrackChangedEvent event) {
        if (TrackReputationLedger.ownMove() || !event.isDefaultClimb() || event.playerId() == null
                || !ReputationTracks.provides(event.trackId())) {
            return;
        }
        ReputationDef def = known(event.trackId());
        Ref<EntityStore> ref = def == null ? null : PlayerAccess.liveRef(event.playerId());
        if (ref == null) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        TrackDef track = ReputationTracks.track(def);
        if (track == null || !engine.live(store, ref)) {
            return;
        }
        ReputationLadder ladder = ladderFor(def);
        // What the move did, read off the event; what it landed on, read fresh (a late import, or an outside write
        // to the copy folded in first, shifts both).
        ReputationLedger.Reading from = movedReading(event.valueBefore(), event.bestBefore(), event.prestigeBefore(),
                track);
        ReputationLedger.Reading to = movedReading(event.valueAfter(), event.bestAfter(), event.prestigeAfter(),
                track);
        ledger.followed(store, ref, def, ladder, event.startedEmpty() ? null : from);
        ReputationLedger.Reading after = ledger.read(store, ref, def, ladder);
        if (after == null) {
            return;
        }
        int earnedMove = to.earned() - from.earned();
        int heldMove = to.held() - from.held();
        if (earnedMove != 0 || heldMove != 0) {
            ReputationLedger.Reading before = new ReputationLedger.Reading(after.earned() - earnedMove,
                    after.held() - heldMove, true);
            announce(store, ref, event.playerId(), def, ladder, before, after, event.source());
        }
    }

    /** One side of a track move as a reading: its value, and the standing its ranks read. */
    @Nonnull
    private static ReputationLedger.Reading movedReading(long value, int best, int prestige, @Nonnull TrackDef track) {
        TrackRecord record = new TrackRecord(value, 0.0, best, prestige, Map.of());
        return new ReputationLedger.Reading(ReputationTracks.toInt(value),
                ReputationTracks.toInt(ReputationTracks.held(record, value, track.table())), true);
    }

    /** Tell a move that landed: the change it was, the fan-out (guarded), then the rank check. */
    @Nonnull
    private ReputationChange announce(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull UUID player, @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder,
            @Nonnull ReputationLedger.Reading before, @Nonnull ReputationLedger.Reading after,
            @Nonnull String source) {
        ReputationLadder.Rank top = ladder.top();
        ReputationChange change = new ReputationChange(player, def, before.earned(), after.earned(),
                ladder.rankFor(before.held()), ladder.rankFor(after.held()),
                ladder.progress(after.earned(), def.beyondEvery()), top == null ? null : top.min(), source,
                before.held(), after.held());
        try {
            fanOut.changed(store, ref, change);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] telling the change to '" + def.id() + "' failed; the standing stands: "
                    + t.getMessage());
        }
        observe(store, ref, player, def, change.effectiveAfter(), ladder, ReputationRankWatch.Trigger.CHANGE);
        return change;
    }

    /** Check every switched-on reputation's rank for the live player at {@code ref}. World thread. */
    public void checkAll(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationRankWatch.Trigger trigger) {
        if (!isOn() || !engine.live(store, ref)) {
            return;
        }
        UUID player = engine.playerId(store, ref);
        if (player == null) {
            return;
        }
        for (ReputationDef def : all()) {
            ReputationLadder ladder = ladderFor(def);
            Standing standing = standingOf(store, ref, def, ladder);
            if (standing != null) {
                observe(store, ref, player, def, standing.effective(), ladder, trigger);
            }
        }
    }

    /** Forget what the rank checks remember about {@code player} (they left). */
    public void forget(@Nonnull UUID player) {
        watch.forget(player);
    }

    /** A gain cut so earned standing never passes {@code cap}; a loss and an uncapped gain pass whole. */
    static int capped(int before, int delta, @Nullable Integer cap) {
        if (cap == null || delta <= 0) {
            return delta;
        }
        if (before >= cap) {
            return 0;
        }
        return (int) Math.min(delta, (long) cap - before);
    }

    private void observe(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull UUID player,
            @Nonnull ReputationDef def, long effective, @Nonnull ReputationLadder ladder,
            @Nonnull ReputationRankWatch.Trigger trigger) {
        try {
            watch.observe(store, ref, player, def, effective, ladder, trigger, fanOut);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the rank check for '" + def.id() + "' failed: " + t.getMessage());
        }
    }

    @Nullable
    private Standing standingOf(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder) {
        ReputationLedger.Reading reading = ledger.read(store, ref, def, ladder);
        if (reading == null) {
            return null;
        }
        return new Standing(def, reading.earned(), reading.entry(), ladder.rankFor(reading.held()), reading.held());
    }

    @Nullable
    private static ReputationDef defOf(@Nonnull ReputationNative.Group group) {
        ReputationAsset companion = ReputationConfig.getInstance().resolve(group.id());
        if (companion != null && !companion.isEnabled()) {
            return null;
        }
        return new ReputationDef(group.id(), group.initial(), companion);
    }
}
