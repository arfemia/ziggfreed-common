package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.progress.gate.GateEpochs;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.track.PickState;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackDerived;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackReading;
import com.ziggfreed.common.track.TrackRecord;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.TrackStore;
import com.ziggfreed.common.track.TrackStores;
import com.ziggfreed.common.track.asset.PrestigeBuild;
import com.ziggfreed.common.track.event.TrackEvents;
import com.ziggfreed.common.track.event.ZigTrackPicksChangedEvent;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one reader and writer of the picks a player makes on their tracks. Every write ({@link #choose},
 * {@link #take}, {@link #undo}, {@link #respec}) is the live player's own world thread, keeps the record through the
 * track's store, moves the player's gate epoch (the {@code ziggfreedcommon:track_pick} reading, Volatility Player),
 * rebuilds the player's grants ({@code TrackDerived.rebuild}) and fires {@code ZigTrackPicksChangedEvent}.
 *
 * <p><b>What is owed is derived, never kept</b> ({@link #waiting}): the slots a pick's groups have opened up to the
 * player's pick level, less the ones made; a free pick bought outright not yet taken as often as it may be; and the
 * points a track has earned and not spent. Nothing is ever parked as a Claim, and the Claims tab lists it through
 * {@code PickClaims}. A pick whose {@code Where} names a trainer is that trainer page's to offer, so it is never
 * owed here.
 *
 * <p><b>The pick level</b> ({@link #pickLevel}) is the level slots open up to: the best level ever under a
 * {@code Keep} prestige build (the picks stay, and nothing new opens until the climb passes them), the current
 * climb's under {@code Remember} and {@code Clear} (a re-levelled level offers its slot again, the old pick
 * preselected under {@code Remember}). Points follow it the same way: a track earns its {@code Points.PerLevel} for
 * each level past the first.
 *
 * <p><b>Undo</b> (R650): every choice and take can be undone until the page it was made on closes
 * ({@link #closeUndo}), never more than five minutes after; the server holds that state ({@code PickUndo}).
 */
public final class PickService {

    /** What a pick, an undo or a respec did. */
    public enum Status {
        /** Done. */
        DONE,
        /** No such track or pick here (unknown, switched off, or on another track). */
        UNKNOWN,
        /** The player is not live here. */
        NOT_LIVE,
        /** The slot or pick has not opened yet at the player's pick level. */
        NOT_OPEN,
        /** That slot's choice is already made. */
        ALREADY_MADE,
        /** An option asked for is not in the slot's offer. */
        NOT_OFFERED,
        /** The slot takes another number of options. */
        WRONG_COUNT,
        /** The player does not meet the pick's {@code Requires}. */
        LOCKED,
        /** A pick or option the pick needs first has not been taken. */
        NEEDS_PREREQUISITE,
        /** The points it costs are not there. */
        NO_POINTS,
        /** Taking it would spend more of its pool than its {@code SpendCap} allows. */
        SPEND_CAP,
        /** It has been taken as often as it may be. */
        MAXED,
        /** It is taken at a trainer its {@code Where} names, not here. */
        ELSEWHERE,
        /** There is nothing to undo or clear. */
        NOTHING
    }

    /**
     * One pick owed: a choice slot ({@code slot} set, its offer, the options a Remember prestige preselects there
     * and how many the slot takes) or a free pick bought outright ({@code slot} null).
     */
    public record Owed(@Nonnull String trackId, @Nonnull String pickId, @Nullable String slot, int level,
                       @Nonnull List<String> offer, @Nonnull List<String> preselected, int choose) {

        public Owed {
            offer = List.copyOf(offer);
            preselected = List.copyOf(preselected);
        }

        /** Is it a choice slot rather than a pick bought outright? */
        public boolean isChoice() {
            return slot != null;
        }
    }

    /** What one track owes a player: the picks owed, lowest level first, and the points left to spend there. */
    public record Waiting(@Nonnull String trackId, @Nonnull List<Owed> owed, int pointsLeft) {

        public Waiting {
            owed = List.copyOf(owed);
            pointsLeft = Math.max(0, pointsLeft);
        }

        /** Nothing owed on {@code trackId}. */
        @Nonnull
        public static Waiting none(@Nonnull String trackId) {
            return new Waiting(trackId, List.of(), 0);
        }

        /** How many choices wait: every owed pick, and each point left to spend. */
        public int count() {
            return owed.size() + pointsLeft;
        }

        public boolean isEmpty() {
            return count() == 0;
        }
    }

    /** What a write did, what it was about, and how many picks a respec cleared. */
    public record Result(@Nonnull Status status, @Nullable String trackId, @Nullable String pickId,
                         @Nullable String slot, @Nonnull List<String> options, int count) {

        public Result {
            options = List.copyOf(options);
        }

        static Result of(@Nonnull Status status) {
            return new Result(status, null, null, null, List.of(), 0);
        }

        public boolean done() {
            return status == Status.DONE;
        }
    }

    /** One live climb read for a write: its track, key, store, record and the player's entity. */
    private record Climb(@Nonnull TrackDef def, @Nonnull TrackKey key, @Nonnull TrackStore store,
                         @Nonnull TrackRecord record, @Nonnull Ref<EntityStore> ref) {
    }

    private PickService() {
    }

    // ==================== reads ====================

    /**
     * The level {@code record}'s slots open up to on {@code def}: its best level ever under a {@code Keep}
     * prestige build, else its current climb's level.
     */
    public static int pickLevel(@Nonnull TrackDef def, @Nonnull TrackRecord record) {
        return def.prestigeBuild() == PrestigeBuild.KEEP ? def.best(record) : def.level(record);
    }

    /** The points {@code record} has earned on {@code def}: its {@code Points.PerLevel} each level past the first. */
    public static int pointsEarned(@Nonnull TrackDef def, @Nonnull TrackRecord record) {
        long earned = (long) def.asset().pointsPerLevel() * Math.max(0, pickLevel(def, record) - 1);
        return (int) Math.min(Integer.MAX_VALUE, earned);
    }

    /** What {@code player} owes themselves on {@code trackId}. Their world thread; nothing owed when not live. */
    @Nonnull
    public static Waiting waiting(@Nonnull UUID player, @Nullable String trackId) {
        Climb climb = climb(player, trackId);
        if (climb == null) {
            return Waiting.none(TrackKey.normalize(trackId));
        }
        String id = climb.def().id();
        int level = pickLevel(climb.def(), climb.record());
        GateQuery query = queryOf(climb.ref());
        PickState state = climb.record().picks();
        Map<String, TrackRecord> records = new HashMap<>();
        records.put(id, climb.record());
        List<Owed> owed = new ArrayList<>();
        for (TrackPicks.PickDef pick : TrackPicks.onTrack(id)) {
            if (!pick.asset().placements().isEmpty() || open(pick, state, level, query) != Status.DONE) {
                continue;
            }
            boolean costed = pick.asset().costPoints() > 0;
            if (costed && costStatus(player, pick, records) != Status.DONE) {
                continue;
            }
            if (pick.isChoice()) {
                for (TrackPicks.GroupDef group : pick.groups()) {
                    for (int at : group.levels(level)) {
                        String slot = pick.slotKey(group, at);
                        if (!state.chosen(slot).isEmpty()) {
                            continue;
                        }
                        List<String> offer = group.offerAt(at);
                        if (offer.isEmpty()) {
                            continue;
                        }
                        List<String> preselected = new ArrayList<>(state.rememberedAt(slot));
                        preselected.retainAll(offer);
                        owed.add(new Owed(id, pick.id(), slot, at, offer, preselected,
                                Math.min(group.choose(), offer.size())));
                    }
                }
            } else if (!costed && pick.asset().repeatMax() != Integer.MAX_VALUE
                    && state.takesOf(pick.id()) < pick.asset().repeatMax()) {
                // A free pick with no limit is always there to take, so it is never owed.
                owed.add(new Owed(id, pick.id(), null, pick.asset().opensAt(), List.of(), List.of(), 1));
            }
        }
        owed.sort(Comparator.comparingInt(Owed::level).thenComparing(Owed::pickId)
                .thenComparing(o -> o.slot() == null ? "" : o.slot()));
        int points = 0;
        if (climb.def().asset().pointsPerLevel() > 0 && pricedHere(id)) {
            points = pointsEarned(climb.def(), climb.record()) - spentOn(player, id, records);
        }
        return new Waiting(id, owed, points);
    }

    /** What {@code player} owes themselves on every usable track that carries a pick, empty ones left out. */
    @Nonnull
    public static List<Waiting> waiting(@Nonnull UUID player) {
        List<Waiting> out = new ArrayList<>();
        if (!TrackPicks.any()) {
            return out;
        }
        for (TrackDef def : TrackService.defs()) {
            if (TrackPicks.onTrack(def.id()).isEmpty() && !pricedHere(def.id())) {
                continue;
            }
            Waiting waiting = waiting(player, def.id());
            if (!waiting.isEmpty()) {
                out.add(waiting);
            }
        }
        return out;
    }

    /** How many times {@code player} took the pick or option {@code id} on {@code trackId}; 0 when not live. */
    public static int count(@Nonnull UUID player, @Nullable String trackId, @Nullable String id) {
        TrackReading reading = trackId == null ? null : TrackService.read(TrackKey.player(player, trackId));
        return reading == null ? 0 : reading.record().picks().count(id);
    }

    /** Can {@code player} undo a pick right now? */
    public static boolean canUndo(@Nonnull UUID player) {
        return PickUndo.any(player, System.currentTimeMillis());
    }

    /** The most minutes a pick stays undoable after it was made (its page closing ends it sooner). */
    public static long undoWindowMinutes() {
        return PickUndo.WINDOW_MS / 60_000L;
    }

    /**
     * Which of {@code def}'s picks a prestige leaves be, whatever the track's Build says (R641), read off the folded
     * pick files: a pick whose {@code Where} names a trainer, one whose {@code KeptThroughPrestige} says so, and one
     * the fold does not know on this track (switched off or gone, so nothing is lost while it cannot be read). Takes
     * a pick id in any case.
     */
    @Nonnull
    public static Predicate<String> keptThroughPrestige(@Nonnull TrackDef def) {
        Map<String, Boolean> known = new HashMap<>();
        for (TrackPicks.PickDef pick : TrackPicks.onTrack(def.id())) {
            known.put(pick.id(), pick.asset().keptThroughPrestige());
        }
        return pickId -> known.getOrDefault(TrackKey.normalize(pickId), Boolean.TRUE);
    }

    // ==================== writes ====================

    /**
     * Make the choice at {@code slot} ({@code <pick>/<group>/<level>}) on {@code trackId}: {@code options}, as many
     * as the slot takes, all from its offer. {@code placement} is the trainer the page belongs to (null for the
     * track's own page). World thread.
     */
    @Nonnull
    public static Result choose(@Nonnull UUID player, @Nullable String trackId, @Nullable String slot,
            @Nonnull List<String> options, @Nullable String placement) {
        if (TrackService.def(trackId) == null) {
            return Result.of(Status.UNKNOWN);
        }
        Climb climb = climb(player, trackId);
        if (climb == null) {
            return Result.of(Status.NOT_LIVE);
        }
        TrackPicks.Slot parts = TrackPicks.slot(slot);
        TrackPicks.PickDef pick = parts == null ? null : TrackPicks.pick(parts.pickId());
        TrackPicks.GroupDef group = pick == null ? null : pick.group(parts.groupKey());
        if (group == null || !pick.isChoice() || !pick.trackId().equals(climb.def().id())) {
            return Result.of(Status.UNKNOWN);
        }
        int level = pickLevel(climb.def(), climb.record());
        if (parts.level() > level || !group.opensAt(parts.level())) {
            return Result.of(Status.NOT_OPEN);
        }
        String key = pick.slotKey(group, parts.level());
        PickState state = climb.record().picks();
        if (!state.chosen(key).isEmpty()) {
            return Result.of(Status.ALREADY_MADE);
        }
        Status open = open(pick, state, level, queryOf(climb.ref()));
        if (open != Status.DONE) {
            return Result.of(open);
        }
        if (!at(pick, placement)) {
            return Result.of(Status.ELSEWHERE);
        }
        List<String> offer = group.offerAt(parts.level());
        List<String> wanted = normalized(options);
        if (wanted.size() != Math.min(group.choose(), offer.size()) || wanted.isEmpty()) {
            return Result.of(Status.WRONG_COUNT);
        }
        if (!offer.containsAll(wanted)) {
            return Result.of(Status.NOT_OFFERED);
        }
        Status cost = costStatus(player, pick, new HashMap<>());
        if (cost != Status.DONE) {
            return Result.of(cost);
        }
        List<String> remembered = state.rememberedAt(key);
        if (!climb.store().put(climb.key(), climb.record().withPicks(state.withChoice(key, wanted)))) {
            return Result.of(Status.NOT_LIVE);
        }
        PickUndo.record(player, new PickUndo.Entry(climb.def().id(), pick.id(), key, wanted, remembered,
                System.currentTimeMillis()));
        changed(player, climb, ZigTrackPicksChangedEvent.Change.CHOSEN, pick.id(), key, wanted);
        return new Result(Status.DONE, climb.def().id(), pick.id(), key, wanted, 1);
    }

    /**
     * Take the pick bought outright {@code pickId} on {@code trackId} once more, paying its {@code Cost}.
     * {@code placement} is the trainer the page belongs to (null for the track's own page). World thread.
     */
    @Nonnull
    public static Result take(@Nonnull UUID player, @Nullable String trackId, @Nullable String pickId,
            @Nullable String placement) {
        if (TrackService.def(trackId) == null) {
            return Result.of(Status.UNKNOWN);
        }
        Climb climb = climb(player, trackId);
        if (climb == null) {
            return Result.of(Status.NOT_LIVE);
        }
        TrackPicks.PickDef pick = TrackPicks.pick(pickId);
        if (pick == null || pick.isChoice() || !pick.trackId().equals(climb.def().id())) {
            return Result.of(Status.UNKNOWN);
        }
        PickState state = climb.record().picks();
        Status open = open(pick, state, pickLevel(climb.def(), climb.record()), queryOf(climb.ref()));
        if (open != Status.DONE) {
            return Result.of(open);
        }
        if (!at(pick, placement)) {
            return Result.of(Status.ELSEWHERE);
        }
        if (state.takesOf(pick.id()) >= pick.asset().repeatMax()) {
            return Result.of(Status.MAXED);
        }
        Status cost = costStatus(player, pick, new HashMap<>());
        if (cost != Status.DONE) {
            return Result.of(cost);
        }
        if (!climb.store().put(climb.key(), climb.record().withPicks(state.withTakes(pick.id(), 1)))) {
            return Result.of(Status.NOT_LIVE);
        }
        PickUndo.record(player, new PickUndo.Entry(climb.def().id(), pick.id(), null, List.of(), List.of(),
                System.currentTimeMillis()));
        changed(player, climb, ZigTrackPicksChangedEvent.Change.TAKEN, pick.id(), null, List.of());
        return new Result(Status.DONE, climb.def().id(), pick.id(), null, List.of(), 1);
    }

    /** Undo {@code player}'s latest pick still in its window: the slot unmade, or the take given back. World thread. */
    @Nonnull
    public static Result undo(@Nonnull UUID player) {
        PickUndo.Entry entry = PickUndo.pop(player, System.currentTimeMillis());
        if (entry == null) {
            return Result.of(Status.NOTHING);
        }
        Climb climb = climb(player, entry.trackId());
        if (climb == null) {
            PickUndo.putBack(player, entry);
            return Result.of(Status.NOT_LIVE);
        }
        PickState state = climb.record().picks();
        PickState next;
        if (entry.slot() != null) {
            if (!state.chosen(entry.slot()).equals(entry.options())) {
                return Result.of(Status.NOTHING);
            }
            next = state.withChoice(entry.slot(), List.of()).withRemembered(entry.slot(), entry.remembered());
        } else {
            if (state.takesOf(entry.pickId()) < 1) {
                return Result.of(Status.NOTHING);
            }
            next = state.withTakes(entry.pickId(), -1);
        }
        if (!climb.store().put(climb.key(), climb.record().withPicks(next))) {
            PickUndo.putBack(player, entry);
            return Result.of(Status.NOT_LIVE);
        }
        changed(player, climb, ZigTrackPicksChangedEvent.Change.UNDONE, entry.pickId(), entry.slot(),
                entry.options());
        return new Result(Status.DONE, climb.def().id(), entry.pickId(), entry.slot(), entry.options(), 1);
    }

    /** The page {@code player} made picks on closed: nothing made so far can be undone any more. */
    public static void closeUndo(@Nullable UUID player) {
        PickUndo.close(player);
    }

    /** A player left the server: their undo window closes. */
    public static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef player = event.getPlayerRef();
            closeUndo(player == null ? null : player.getUuid());
        } catch (Throwable t) {
            SafeLog.fine("[track] could not close a departing player's undo window: " + t.getMessage());
        }
    }

    /**
     * Clear every pick {@code player} made on {@code trackId} (a respec): each slot reopens with the same offer it
     * had (the road reads the files alone, R724), and every point spent comes back. What a prestige put aside stays. The price of a respec is the
     * caller's (a trainer's service); this is only the clearing. World thread.
     */
    @Nonnull
    public static Result respec(@Nonnull UUID player, @Nullable String trackId) {
        if (TrackService.def(trackId) == null) {
            return Result.of(Status.UNKNOWN);
        }
        Climb climb = climb(player, trackId);
        if (climb == null) {
            return Result.of(Status.NOT_LIVE);
        }
        PickState state = climb.record().picks();
        int cleared = state.size();
        if (cleared == 0) {
            return Result.of(Status.NOTHING);
        }
        if (!climb.store().put(climb.key(), climb.record().withPicks(state.cleared()))) {
            return Result.of(Status.NOT_LIVE);
        }
        PickUndo.closeTrack(player, climb.def().id());
        changed(player, climb, ZigTrackPicksChangedEvent.Change.RESPEC, null, null, List.of());
        return new Result(Status.DONE, climb.def().id(), null, null, List.of(), cleared);
    }

    /**
     * A prestige of {@code def} acted on {@code player}'s picks (the track service's to call, after the record was
     * kept): the track's undo window closes, the grants are rebuilt, and the change is announced when the picks
     * moved. World thread.
     */
    public static void prestiged(@Nonnull UUID player, @Nonnull TrackDef def, boolean picksMoved) {
        PickUndo.closeTrack(player, def.id());
        Ref<EntityStore> ref = PlayerAccess.liveRef(player);
        if (ref != null) {
            TrackDerived.rebuild(ref.getStore(), ref);
        }
        if (picksMoved) {
            GateEpochs.bumpPlayer(player);
            TrackEvents.firePicksChanged(player, def.id(), ZigTrackPicksChangedEvent.Change.PRESTIGE, null, null,
                    List.of());
        }
    }

    // ==================== the pieces ====================

    /** Is {@code pick} open to a record at {@code level}: its level reached, its Requires met, its prerequisites taken. */
    @Nonnull
    static Status open(@Nonnull TrackPicks.PickDef pick, @Nonnull PickState state, int level,
            @Nullable GateQuery query) {
        if (level < pick.asset().opensAt()) {
            return Status.NOT_OPEN;
        }
        GateSpec requires = pick.asset().liveRequires();
        if (requires != null && query != null && !StoreGates.passes(requires, query)) {
            return Status.LOCKED;
        }
        for (String needed : pick.asset().prerequisites()) {
            if (state.count(needed) < 1) {
                return Status.NEEDS_PREREQUISITE;
            }
        }
        return Status.DONE;
    }

    /**
     * Can {@code player} pay one more of {@code pick}: its points there on its cost track, and its spend cap not
     * passed. {@code records} caches each track's record for this one ask.
     */
    @Nonnull
    private static Status costStatus(@Nonnull UUID player, @Nonnull TrackPicks.PickDef pick,
            @Nonnull Map<String, TrackRecord> records) {
        int cost = pick.asset().costPoints();
        if (cost <= 0) {
            return Status.DONE;
        }
        TrackDef costDef = TrackService.def(pick.asset().costTrack());
        TrackRecord costRecord = costDef == null ? null : record(player, costDef.id(), records);
        if (costRecord == null) {
            return Status.NO_POINTS;
        }
        long spent = spentOn(player, costDef.id(), records);
        if (spent + cost > pointsEarned(costDef, costRecord)) {
            return Status.NO_POINTS;
        }
        int cap = pick.asset().spendCap();
        return cap > 0 && spent + cost > cap ? Status.SPEND_CAP : Status.DONE;
    }

    /** The points {@code player} has spent of {@code costTrackId}'s pool, on every pick priced in it. */
    private static int spentOn(@Nonnull UUID player, @Nonnull String costTrackId,
            @Nonnull Map<String, TrackRecord> records) {
        long spent = 0L;
        for (TrackPicks.PickDef pick : TrackPicks.all().values()) {
            int cost = pick.asset().costPoints();
            if (cost <= 0 || !costTrackId.equals(TrackKey.normalize(pick.asset().costTrack()))) {
                continue;
            }
            TrackRecord record = record(player, pick.trackId(), records);
            if (record == null) {
                continue;
            }
            PickState state = record.picks();
            spent += (long) cost * (pick.isChoice() ? state.slotsMade(pick.id()) : state.takesOf(pick.id()));
        }
        return (int) Math.min(Integer.MAX_VALUE, spent);
    }

    /** Does any pick price itself in {@code trackId}'s points? */
    private static boolean pricedHere(@Nonnull String trackId) {
        for (TrackPicks.PickDef pick : TrackPicks.all().values()) {
            if (pick.asset().costPoints() > 0 && trackId.equals(TrackKey.normalize(pick.asset().costTrack()))) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static TrackRecord record(@Nonnull UUID player, @Nonnull String trackId,
            @Nonnull Map<String, TrackRecord> records) {
        if (records.containsKey(trackId)) {
            return records.get(trackId);
        }
        TrackReading reading = TrackService.read(TrackKey.player(player, trackId));
        TrackRecord record = reading == null ? null : reading.record();
        records.put(trackId, record);
        return record;
    }

    /** Is {@code placement} somewhere {@code pick} is taken: anywhere for a pick naming no trainer. */
    private static boolean at(@Nonnull TrackPicks.PickDef pick, @Nullable String placement) {
        List<String> placements = pick.asset().placements();
        return placements.isEmpty() || (placement != null && placements.contains(TrackKey.normalize(placement)));
    }

    /** Keep a change's knock-on: the epoch, the grants, the event. */
    private static void changed(@Nonnull UUID player, @Nonnull Climb climb,
            @Nonnull ZigTrackPicksChangedEvent.Change change, @Nullable String pickId, @Nullable String slot,
            @Nonnull List<String> options) {
        GateEpochs.bumpPlayer(player);
        TrackDerived.rebuild(climb.ref().getStore(), climb.ref());
        TrackEvents.firePicksChanged(player, climb.def().id(), change, pickId, slot, options);
    }

    @Nullable
    private static Climb climb(@Nonnull UUID player, @Nullable String trackId) {
        TrackDef def = TrackService.def(trackId);
        if (def == null) {
            return null;
        }
        TrackKey key = TrackKey.player(player, def.asset().getId());
        TrackStore store = TrackStores.forKey(key);
        TrackRecord record = store == null ? null : store.get(key);
        Ref<EntityStore> ref = PlayerAccess.liveRef(player);
        return record == null || ref == null ? null : new Climb(def, key, store, record, ref);
    }

    @Nonnull
    private static GateQuery queryOf(@Nonnull Ref<EntityStore> ref) {
        return GateQuery.of(FactorContext.about(ref.getStore(), ref));
    }

    @Nonnull
    private static List<String> normalized(@Nonnull List<String> options) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String option : options) {
            String id = TrackKey.normalize(option);
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }
}
