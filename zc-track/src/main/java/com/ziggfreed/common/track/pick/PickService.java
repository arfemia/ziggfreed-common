package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.entity.CombatState;
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
 * {@link #take}, {@link #undo}, {@link #respec}, {@link #buyRespec}, {@link #swapLoadout},
 * {@link #respendRemembered}, {@link #spendInOrder}) is the live player's own world thread, keeps the record through
 * the track's store, moves the player's gate epoch (the {@code ziggfreedcommon:track_pick} and
 * {@code ziggfreedcommon:track_branch_spent} readings, Volatility Player), rebuilds the player's grants
 * ({@code TrackDerived.rebuild}) and fires {@code ZigTrackPicksChangedEvent}.
 *
 * <p><b>What is owed is derived, never kept</b> ({@link #waiting}): the slots a pick's groups have opened up to the
 * player's pick level, less the ones made; a free pick bought outright not yet taken as often as it may be; and the
 * points a track has earned and not spent. Nothing is ever parked as a Claim, and the Claims tab lists it through
 * {@code PickClaims}. A pick whose {@code Where} names a trainer that teaches ({@link PickTrainers}) is that trainer
 * page's to offer ({@link #trainerPicks}), so it is never owed here; one whose trainers are all switched off or gone
 * is owed and taken on the track's own page like any other (R608).
 *
 * <p><b>The pick level</b> ({@link #pickLevel}) is the level slots open up to: the best level ever under a
 * {@code Keep} prestige build (the picks stay, and nothing new opens until the climb passes them), the current
 * climb's under {@code Remember} and {@code Clear} (a re-levelled level offers its slot again, the old pick
 * preselected under {@code Remember}). Points follow it the same way: a track earns its {@code Points.PerLevel} for
 * each level past the first, up to its {@code Points.To}.
 *
 * <p><b>A tree</b> (R736): a pick's {@code Branch} counts its points toward that branch, which a later row's
 * {@code Requires} reads; the picks sharing a {@code Node} on a track are one choice, so taking one face locks the
 * others ({@link Status#NODE_TAKEN}) until it is undone or the track is respecced.
 *
 * <p><b>Undo</b> (R650): every choice and take can be undone until the page it was made on closes
 * ({@link #closeUndo}), never more than five minutes after, newest first; the server holds that state
 * ({@code PickUndo}), a batch of takes made in one go ({@link #spendInOrder}, {@link #respendRemembered}) as one
 * entry, undone whole. An undo that would leave a pick the player still holds short of what it needed when it was
 * taken (its {@code Requires} or prerequisites) is refused ({@link Status#NEEDED}), so no gate is ever left unmet.
 *
 * <p><b>A respec</b> ({@link #respec}, {@link #buyRespec}) keeps only what a trainer taught (a pick whose
 * {@code Where} names placements, its gate a level that stays met): every pick bought with points, Mastery and
 * {@code KeptThroughPrestige} picks included, is cleared and its points come back to spend again.
 * {@code KeptThroughPrestige} governs a prestige alone, where a kept pick stays held by design even when a gate it
 * once met (a branch's points) no longer is: nothing re-gates a pick already held.
 *
 * <p><b>Saved builds</b> (R736): a track's {@code Loadouts.Max} builds, saved ({@link #saveLoadout}) and swapped
 * ({@link #swapLoadout}) for the track's swap price, never in combat or mid-encounter. A pick a prestige keeps (a
 * trainer's, or one whose file keeps it) stays in use through every swap and is never saved into a build. A swap
 * away from the build in use writes the picks in use back over it first, keeping every saved pick that could not be
 * loaded ({@code PickState.Loadout.writtenOver}), so a swap never loses a saved pick; with no build in use it is
 * refused ({@link Status#UNSAVED}) while picks no build holds would be lost, unless the caller forces it. A build is
 * spent again in the order its picks were made, choices and takes interleaved.
 */
public final class PickService {

    /** What a pick, an undo, a respec or a swap did. */
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
        NOTHING,
        /** Another face of its choice node ({@code Node}) is taken. */
        NODE_TAKEN,
        /** Undoing it would leave a pick still held short of what it needed (a later row on its branch points). */
        NEEDED,
        /** The track keeps no saved builds, or not one by that number. */
        NO_LOADOUT,
        /** The player is in combat. */
        IN_COMBAT,
        /** The player is in an encounter (a boss fight). */
        IN_ENCOUNTER,
        /** The price cannot be paid. */
        CANNOT_PAY,
        /** No saved build is in use and a swap would lose picks no build holds: save them first, or force it. */
        UNSAVED
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

    /** What a write did, what it was about, and how many picks a respec cleared or a swap or re-spend put back. */
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

    /** One saved build as a page lists it: its number, how many picks it holds, and whether it is in use. */
    public record LoadoutView(int slot, int picks, boolean active) {

        /** Is anything saved there? */
        public boolean saved() {
            return picks > 0;
        }
    }

    /** A track's saved builds for one player: how many it keeps, the one in use (0 for none), and each slot. */
    public record Loadouts(@Nonnull String trackId, int max, int active, @Nonnull List<LoadoutView> slots) {

        public Loadouts {
            slots = List.copyOf(slots);
        }

        /** No saved builds on {@code trackId}. */
        @Nonnull
        public static Loadouts none(@Nonnull String trackId) {
            return new Loadouts(trackId, 0, 0, List.of());
        }
    }

    /**
     * One pick a trainer's page offers a player ({@link #trainerPicks}): the pick and its track, the level it opens
     * at and the player's pick level there, the points one take costs and the track they come from (0 and null for a
     * free pick), how many times it was taken and may be ({@link Integer#MAX_VALUE} for no limit), and what a take
     * at that trainer would answer now: {@link Status#DONE} when it can be learned, {@link Status#MAXED} once learned
     * as often as it may be.
     */
    public record TrainerPick(@Nonnull String trackId, @Nonnull String pickId, int opensAt, int level,
                              int costPoints, @Nullable String costTrack, int taken, int max,
                              @Nonnull Status status) {

        /** Has it been learned as often as it may be? */
        public boolean learned() {
            return status == Status.MAXED;
        }
    }

    /** One live climb read for a write: its track, key, store, record and the player's entity. */
    private record Climb(@Nonnull TrackDef def, @Nonnull TrackKey key, @Nonnull TrackStore store,
                         @Nonnull TrackRecord record, @Nonnull Ref<EntityStore> ref) {

        /** This climb holding {@code next} instead (after a write that kept it). */
        @Nonnull
        Climb with(@Nonnull TrackRecord next) {
            return new Climb(def, key, store, next, ref);
        }
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

    /**
     * The points {@code record} has earned on {@code def}: its {@code Points.PerLevel} each level past the first, up
     * to its {@code Points.To}.
     */
    public static int pointsEarned(@Nonnull TrackDef def, @Nonnull TrackRecord record) {
        int level = Math.min(pickLevel(def, record), def.asset().pointsTo());
        long earned = (long) def.asset().pointsPerLevel() * Math.max(0, level - 1);
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
            if (!PickTrainers.of(pick.asset()).isEmpty() || open(pick, state, level, query) != Status.DONE) {
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

    /**
     * The points {@code player} has spent in the branch {@code branch} of {@code trackId}'s tree (what a row's
     * {@code ziggfreedcommon:track_branch_spent} gate reads); 0 when not live.
     */
    public static int branchSpent(@Nonnull UUID player, @Nullable String trackId, @Nullable String branch) {
        TrackReading reading = trackId == null ? null : TrackService.read(TrackKey.player(player, trackId));
        return reading == null ? 0
                : TrackPicks.branchSpent(TrackPicks.onTrack(reading.def().id()), reading.record().picks(), branch);
    }

    /**
     * The starter build of {@code trackId}: pick ids in the order they are spent, a repeat for each further rank
     * ({@code Starter}). Read only by a one-time claim of points earned before the tree existed (R727), which spends
     * it through {@link #spendInOrder}; empty for none or no such track.
     */
    @Nonnull
    public static List<String> starter(@Nullable String trackId) {
        TrackDef def = TrackService.def(trackId);
        return def == null ? List.of() : def.asset().starter();
    }

    /** The takes a Remember prestige put aside on {@code trackId}, in the order they are re-spent; empty when none. */
    @Nonnull
    public static List<String> rememberedTakes(@Nonnull UUID player, @Nullable String trackId) {
        TrackReading reading = trackId == null ? null : TrackService.read(TrackKey.player(player, trackId));
        return reading == null ? List.of() : reading.record().picks().rememberedTakes();
    }

    /** {@code player}'s saved builds on {@code trackId}: none when the track keeps none or they are not live. */
    @Nonnull
    public static Loadouts loadouts(@Nonnull UUID player, @Nullable String trackId) {
        TrackReading reading = trackId == null ? null : TrackService.read(TrackKey.player(player, trackId));
        if (reading == null) {
            return Loadouts.none(TrackKey.normalize(trackId));
        }
        int max = reading.def().loadoutsMax();
        PickState state = reading.record().picks();
        List<LoadoutView> slots = new ArrayList<>();
        for (int slot = 1; slot <= max; slot++) {
            slots.add(new LoadoutView(slot, state.loadout(slot).size(), state.activeLoadout() == slot));
        }
        return new Loadouts(reading.def().id(), max, state.activeLoadout() <= max ? state.activeLoadout() : 0, slots);
    }

    /**
     * Every pick bought outright whose {@code Where} names {@code placement}, as {@code player} meets it now: what that
     * trainer's page lists, track by track in the tracks' order and each track's picks in theirs. Each one's status is
     * what a take there would answer ({@link #take} with that placement), read without writing, a pick learned as
     * often as it may be reading {@link Status#MAXED} first. A choice whose {@code Where} names the placement is left
     * out: a trainer's page takes picks bought outright, and a choice's slots are made on the track's own page. A
     * track the player is not live on lists its picks as {@link Status#NOT_LIVE}. World thread.
     */
    @Nonnull
    public static List<TrainerPick> trainerPicks(@Nonnull UUID player, @Nullable String placement) {
        String at = TrackKey.normalize(placement);
        List<TrainerPick> out = new ArrayList<>();
        if (at.isEmpty() || !TrackPicks.any()) {
            return out;
        }
        for (TrackDef def : TrackService.defs()) {
            List<TrackPicks.PickDef> taught = new ArrayList<>();
            for (TrackPicks.PickDef pick : TrackPicks.onTrack(def.id())) {
                if (!pick.isChoice() && pick.asset().placements().contains(at)) {
                    taught.add(pick);
                }
            }
            if (taught.isEmpty()) {
                continue;
            }
            Climb climb = climb(player, def.id());
            GateQuery query = climb == null ? null : queryOf(climb.ref());
            for (TrackPicks.PickDef pick : taught) {
                out.add(trainerPick(player, climb, query, pick, at));
            }
        }
        return out;
    }

    /** {@code pick} as the trainer page at {@code placement} reads it for {@code player}. */
    @Nonnull
    private static TrainerPick trainerPick(@Nonnull UUID player, @Nullable Climb climb, @Nullable GateQuery query,
            @Nonnull TrackPicks.PickDef pick, @Nonnull String placement) {
        TrackPickAsset asset = pick.asset();
        int max = asset.repeatMax();
        int cost = asset.costPoints();
        String costTrack = cost > 0 ? TrackKey.normalize(asset.costTrack()) : null;
        if (climb == null) {
            return new TrainerPick(pick.trackId(), pick.id(), asset.opensAt(), 0, cost, costTrack, 0, max,
                    Status.NOT_LIVE);
        }
        int taken = climb.record().picks().takesOf(pick.id());
        Status status = taken >= max ? Status.MAXED : takeStatus(player, climb, pick, placement, query);
        return new TrainerPick(pick.trackId(), pick.id(), asset.opensAt(), pickLevel(climb.def(), climb.record()),
                cost, costTrack, taken, max, status);
    }

    /**
     * How many of {@code player}'s picks on {@code trackId} a respec would clear (every pick but what a trainer
     * taught): what a respec offer says before it charges, 0 when there is nothing to clear or the player is not
     * live. World thread.
     */
    public static int respecClears(@Nonnull UUID player, @Nullable String trackId) {
        Climb climb = climb(player, trackId);
        if (climb == null) {
            return 0;
        }
        PickState state = climb.record().picks();
        return Math.max(0, state.size() - respecced(climb.def(), state).size());
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
     * a pick id in any case. The same picks stay in use through every swap of a saved build. A respec reads
     * {@link #taughtByTrainer} instead.
     */
    @Nonnull
    public static Predicate<String> keptThroughPrestige(@Nonnull TrackDef def) {
        return byPick(def, pick -> pick.asset().keptThroughPrestige());
    }

    /**
     * Which of {@code def}'s picks a respec leaves be, read off the folded pick files: a pick a trainer taught (its
     * {@code Where} names placements, its gate a level that stays met), and one the fold does not know on this track
     * (switched off or gone, so nothing is lost while it cannot be read). Every other pick, Mastery and
     * {@code KeptThroughPrestige} picks included, is cleared by a respec. Takes a pick id in any case.
     */
    @Nonnull
    public static Predicate<String> taughtByTrainer(@Nonnull TrackDef def) {
        return byPick(def, pick -> !pick.asset().placements().isEmpty());
    }

    /**
     * Per pick id on {@code def} (any case), the other faces of its choice node ({@code PickDef.otherFaces}): what a
     * swap's write-back reads, so a face taken since stands in the saved face's place. None for a pick in no node or
     * one the fold does not know on this track.
     */
    @Nonnull
    private static Function<String, List<String>> facesOn(@Nonnull TrackDef def) {
        Map<String, List<String>> faces = new HashMap<>();
        for (TrackPicks.PickDef pick : TrackPicks.onTrack(def.id())) {
            faces.put(pick.id(), pick.otherFaces());
        }
        return pickId -> faces.getOrDefault(TrackKey.normalize(pickId), List.of());
    }

    /** What {@code answer} says of each pick on {@code def}, by id in any case; true for one the fold does not know. */
    @Nonnull
    private static Predicate<String> byPick(@Nonnull TrackDef def,
            @Nonnull Predicate<TrackPicks.PickDef> answer) {
        Map<String, Boolean> known = new HashMap<>();
        for (TrackPicks.PickDef pick : TrackPicks.onTrack(def.id())) {
            known.put(pick.id(), answer.test(pick));
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
        List<String> wanted = normalized(options);
        Status status = chooseStatus(player, climb, parts, wanted, placement, queryOf(climb.ref()));
        if (status != Status.DONE) {
            return Result.of(status);
        }
        TrackPicks.PickDef pick = TrackPicks.pick(parts.pickId());
        String key = pick.slotKey(pick.group(parts.groupKey()), parts.level());
        PickState state = climb.record().picks();
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
        Status status = takeStatus(player, climb, pick, placement, queryOf(climb.ref()));
        if (status != Status.DONE) {
            return Result.of(status);
        }
        PickState state = climb.record().picks();
        if (!climb.store().put(climb.key(), climb.record().withPicks(state.withTakes(pick.id(), 1)))) {
            return Result.of(Status.NOT_LIVE);
        }
        PickUndo.record(player, new PickUndo.Entry(climb.def().id(), pick.id(), null, List.of(), List.of(),
                System.currentTimeMillis()));
        changed(player, climb, ZigTrackPicksChangedEvent.Change.TAKEN, pick.id(), null, List.of());
        return new Result(Status.DONE, climb.def().id(), pick.id(), null, List.of(), 1);
    }

    /**
     * Undo {@code player}'s latest pick still in its window, newest first: the slot unmade, or the take given back
     * (a re-spent remembered take goes back to the head of what is remembered). Refused, and kept undoable, when it
     * would leave a pick the player still holds short of what it met when it was taken ({@link Status#NEEDED}).
     * World thread.
     */
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
        if (entry.isBatch()) {
            next = PickUndo.undoBatch(state, entry);
            if (next == null) {
                return Result.of(Status.NOTHING);
            }
        } else if (entry.slot() != null) {
            if (!state.chosen(entry.slot()).equals(entry.options())) {
                return Result.of(Status.NOTHING);
            }
            next = state.withChoice(entry.slot(), List.of()).withRemembered(entry.slot(), entry.remembered());
        } else {
            if (state.takesOf(entry.pickId()) < 1) {
                return Result.of(Status.NOTHING);
            }
            next = state.withTakes(entry.pickId(), -1);
            if (!entry.remembered().isEmpty()) {
                next = next.withRememberedTakeFirst(entry.pickId());
            }
        }
        GateQuery query = queryOf(climb.ref());
        Map<String, Boolean> held = gatesHeld(player, query);
        if (!climb.store().put(climb.key(), climb.record().withPicks(next))) {
            PickUndo.putBack(player, entry);
            return Result.of(Status.NOT_LIVE);
        }
        GateEpochs.bumpPlayer(player);
        if (losesAGate(player, query, held)) {
            climb.store().put(climb.key(), climb.record());
            GateEpochs.bumpPlayer(player);
            PickUndo.putBack(player, entry);
            return new Result(Status.NEEDED, climb.def().id(), entry.pickId(), entry.slot(), entry.options(), 0);
        }
        if (entry.isBatch()) {
            changed(player, climb, ZigTrackPicksChangedEvent.Change.UNDONE, null, null, List.of());
            return new Result(Status.DONE, climb.def().id(), null, null, List.of(), entry.batch().size());
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
     * Respec {@code player}'s picks on {@code trackId}, for free: only what a trainer taught stays
     * ({@link #taughtByTrainer}); every other pick, Mastery and {@code KeptThroughPrestige} picks included, is
     * cleared, each slot reopening with the same offer it had (the road reads the files alone, R724), and every point
     * they spent comes back to spend again. What a prestige put aside and every saved build stay, and the picks in
     * use no longer count as any saved build's. A player's own respec is {@link #buyRespec}, at the track's price;
     * this is the clearing alone (an admin's). World thread.
     *
     * @return the result, its {@code count} the picks cleared ({@link Status#NOTHING} when there were none to clear)
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
        PickState next = respecced(climb.def(), state);
        int cleared = state.size() - next.size();
        if (cleared == 0) {
            return Result.of(Status.NOTHING);
        }
        if (!climb.store().put(climb.key(), climb.record().withPicks(next))) {
            return Result.of(Status.NOT_LIVE);
        }
        PickUndo.closeTrack(player, climb.def().id());
        changed(player, climb, ZigTrackPicksChangedEvent.Change.RESPEC, null, null, List.of());
        return new Result(Status.DONE, climb.def().id(), null, null, List.of(), cleared);
    }

    /**
     * A player's own respec of {@code trackId}: the track's respec price ({@link #respecPrice}: its
     * {@code Respec.Cost}, else its category's, else free, any wallet or item, paid {@code Respec.PerLevel} times per
     * level of the best level; R665, R733) taken, then every pick but what a trainer taught cleared as
     * {@link #respec} does. Refused in combat ({@link Status#IN_COMBAT}) and mid-encounter
     * ({@link Status#IN_ENCOUNTER}), as a swap is. Nothing is taken when there is nothing to clear, and the receipt
     * is given back when the clearing cannot be kept or throws. World thread.
     */
    @Nonnull
    public static Result buyRespec(@Nonnull UUID player, @Nullable String trackId) {
        TrackDef def = TrackService.def(trackId);
        if (def == null) {
            return Result.of(Status.UNKNOWN);
        }
        Climb climb = climb(player, trackId);
        if (climb == null) {
            return Result.of(Status.NOT_LIVE);
        }
        Ref<EntityStore> ref = climb.ref();
        if (CombatState.inCombat(ref.getStore(), ref)) {
            return Result.of(Status.IN_COMBAT);
        }
        if (inEncounter(ref)) {
            return Result.of(Status.IN_ENCOUNTER);
        }
        PickState state = climb.record().picks();
        if (respecced(def, state).size() == state.size()) {
            return Result.of(Status.NOTHING);
        }
        PickPayments.Paid paid = PickPayments.charge(ref,
                PickPayments.respecPrice(def, def.best(climb.record())), "the respec of " + def.id());
        if (paid == null) {
            return Result.of(Status.CANNOT_PAY);
        }
        Result result;
        try {
            result = respec(player, trackId);
        } catch (Throwable t) {
            paid.refund();
            throw t;
        }
        if (!result.done()) {
            paid.refund();
        }
        return result;
    }

    /**
     * What {@code player}'s own respec of {@code trackId} costs right now ({@link #buyRespec}'s price at their best
     * level); free when the track is unknown or they are not live. World thread.
     */
    @Nonnull
    public static Cost respecPrice(@Nonnull UUID player, @Nullable String trackId) {
        Climb climb = climb(player, trackId);
        return climb == null ? Cost.FREE : PickPayments.respecPrice(climb.def(), climb.def().best(climb.record()));
    }

    /**
     * Save the picks {@code player} has in use on {@code trackId} as the build {@code slot} (1 to the track's
     * {@code Loadouts.Max}), replacing what was saved there, and mark it the build in use. A pick a prestige keeps
     * is never saved: it stays in use through every swap. Free. World thread.
     *
     * @return the result, its {@code count} the picks saved
     */
    @Nonnull
    public static Result saveLoadout(@Nonnull UUID player, @Nullable String trackId, int slot) {
        TrackDef def = TrackService.def(trackId);
        if (def == null) {
            return Result.of(Status.UNKNOWN);
        }
        Climb climb = climb(player, trackId);
        if (climb == null) {
            return Result.of(Status.NOT_LIVE);
        }
        if (slot < 1 || slot > def.loadoutsMax()) {
            return Result.of(Status.NO_LOADOUT);
        }
        PickState state = climb.record().picks();
        PickState.Loadout build = state.snapshot(keptThroughPrestige(def));
        if (!climb.store().put(climb.key(), climb.record().withPicks(state.withLoadout(slot, build)
                .withActiveLoadout(slot)))) {
            return Result.of(Status.NOT_LIVE);
        }
        return new Result(Status.DONE, def.id(), null, null, List.of(), build.size());
    }

    /**
     * Swap {@code player}'s picks on {@code trackId} for the saved build {@code slot}, at the track's swap price
     * ({@code Loadouts.SwapCost}, else its category's, else free). Refused in combat ({@link Status#IN_COMBAT}) and
     * mid-encounter ({@link Status#IN_ENCOUNTER}). The picks in use are written back over the build they came from
     * first (unless that is {@code slot}, when the swap puts that build back as it was saved), every saved pick
     * they lack kept in its place ({@code PickState.Loadout.writtenOver}: one a lower climb, points not yet back or
     * a pick switched off kept from loading), so a swap never loses a saved pick. With no build in use (never saved,
     * or unlinked by a respec or a prestige) a swap would lose the picks in use, so it is refused
     * ({@link Status#UNSAVED}, nothing charged) while any would be lost, unless {@code force} says to swap anyway (a
     * page asks the player first). Then every pick a prestige would not keep is cleared and the build is spent again
     * in the order its picks were made, choices and takes interleaved as they were made, each by every rule a pick
     * obeys, so a pick the track no longer allows is passed over and no gate is left unmet. An unsaved slot is an
     * empty build. A swap to the build in use, unchanged since it was saved, does nothing and costs nothing
     * ({@link Status#NOTHING}); changed since, it puts that build back as it was saved, for free (nothing is gained
     * by paying). The track's undo window closes. World thread.
     *
     * @param force swap even though no build is in use and picks no build holds would be lost
     * @return the result, its {@code count} the picks the build put back
     */
    @Nonnull
    public static Result swapLoadout(@Nonnull UUID player, @Nullable String trackId, int slot, boolean force) {
        TrackDef def = TrackService.def(trackId);
        if (def == null) {
            return Result.of(Status.UNKNOWN);
        }
        Climb climb = climb(player, trackId);
        if (climb == null) {
            return Result.of(Status.NOT_LIVE);
        }
        int max = def.loadoutsMax();
        if (slot < 1 || slot > max) {
            return Result.of(Status.NO_LOADOUT);
        }
        Ref<EntityStore> ref = climb.ref();
        if (CombatState.inCombat(ref.getStore(), ref)) {
            return Result.of(Status.IN_COMBAT);
        }
        if (inEncounter(ref)) {
            return Result.of(Status.IN_ENCOUNTER);
        }
        Predicate<String> kept = keptThroughPrestige(def);
        PickState state = climb.record().picks();
        int active = state.activeLoadout();
        PickState.Loadout inUse = state.snapshot(kept);
        if (active == slot && inUse.equals(state.loadout(slot))) {
            // That build is in use exactly as it was saved: nothing to swap, so nothing is charged.
            return Result.of(Status.NOTHING);
        }
        boolean linked = active >= 1 && active <= max;
        if (!linked && !force && !inUse.isEmpty()) {
            // No build holds the picks in use, so the swap would lose them: the caller asks first.
            return Result.of(Status.UNSAVED);
        }
        // A swap to the build in use only puts it back as it was saved: nothing is gained by paying, so it is free.
        PickPayments.Paid paid = active == slot ? PickPayments.Paid.FREE
                : PickPayments.charge(ref, def.swapCost(), "a build swap on " + def.id());
        if (paid == null) {
            return Result.of(Status.CANNOT_PAY);
        }
        PickState base = state;
        if (linked && active != slot) {
            base = base.withLoadout(active, inUse.writtenOver(state.loadout(active), facesOn(def)));
        }
        PickState.Loadout build = base.loadout(slot);
        TrackRecord start = climb.record().withPicks(base.keptOnly(kept).withActiveLoadout(slot));
        if (!climb.store().put(climb.key(), start)) {
            paid.refund();
            return Result.of(Status.NOT_LIVE);
        }
        GateEpochs.bumpPlayer(player);
        Climb now = climb.with(start);
        GateQuery query = queryOf(ref);
        int loaded = 0;
        for (String step : build.order()) {
            // In the order the picks were made, so a choice a take opened (its points, a prerequisite) follows it.
            Climb after = PickState.isSlotKey(step)
                    ? chooseInto(player, now, step, build.slots().getOrDefault(step, List.of()), query)
                    : takeInto(player, now, TrackPicks.pick(step), query);
            if (after != null) {
                now = after;
                loaded++;
            }
        }
        PickUndo.closeTrack(player, def.id());
        changed(player, now, ZigTrackPicksChangedEvent.Change.SWAPPED, null, null, List.of());
        return new Result(Status.DONE, def.id(), null, null, List.of(), loaded);
    }

    /**
     * Spend again, in order, the takes a Remember prestige put aside on {@code trackId} (R641), as far as the points
     * that have come back allow: each is taken by every rule a pick obeys, and the walk stops at the first that must
     * wait (for points, a level, a gate or a prerequisite), keeping it and every later one for the next time; one
     * that can never come back as it was (taken as often as it may be, or its node taken another way) is dropped,
     * and one switched off is kept and passed over. A choice the player confirms, never automatic (R598); the whole
     * re-spend is one undo, every take back to the head of what is remembered in its order. World thread.
     *
     * @return the result, its {@code count} the takes re-spent ({@link Status#NOTHING} when none could be)
     */
    @Nonnull
    public static Result respendRemembered(@Nonnull UUID player, @Nullable String trackId) {
        TrackDef def = TrackService.def(trackId);
        if (def == null) {
            return Result.of(Status.UNKNOWN);
        }
        Climb climb = climb(player, trackId);
        if (climb == null) {
            return Result.of(Status.NOT_LIVE);
        }
        List<String> remembered = climb.record().picks().rememberedTakes();
        if (remembered.isEmpty()) {
            return Result.of(Status.NOTHING);
        }
        Walk walk = walk(player, climb, remembered);
        Climb now = walk.climb();
        TrackRecord kept = now.record().withPicks(now.record().picks().withRememberedTakes(walk.left()));
        if (!now.store().put(now.key(), kept)) {
            return Result.of(Status.NOT_LIVE);
        }
        if (walk.spent().isEmpty() && walk.left().equals(remembered)) {
            return Result.of(Status.NOTHING);
        }
        if (!walk.spent().isEmpty()) {
            PickUndo.record(player, PickUndo.Entry.batch(def.id(), walk.spent(), true, System.currentTimeMillis()));
        }
        changed(player, now.with(kept), ZigTrackPicksChangedEvent.Change.RESPENT, null, null, List.of());
        return new Result(walk.spent().isEmpty() ? Status.NOTHING : Status.DONE, def.id(), null, null, List.of(),
                walk.spent().size());
    }

    /**
     * Take each of {@code picks} (pick ids bought outright, a repeat for each further rank) once on
     * {@code trackId}, in order, by every rule a pick obeys, stopping at the first that must wait (for points, a
     * level, a gate or a prerequisite) and passing over one that can never be taken (unknown here, taken as often as
     * it may be, its node taken another way, or a trainer's): the walk a one-time claim of points earned before the
     * tree existed spends a track's {@link #starter} with (R727). The whole walk is one undo, however many takes it
     * made, so a 99-point starter build comes back in one press. World thread.
     *
     * @return the result, its {@code count} the takes made ({@link Status#NOTHING} when none could be)
     */
    @Nonnull
    public static Result spendInOrder(@Nonnull UUID player, @Nullable String trackId, @Nonnull List<String> picks) {
        TrackDef def = TrackService.def(trackId);
        if (def == null) {
            return Result.of(Status.UNKNOWN);
        }
        Climb climb = climb(player, trackId);
        if (climb == null) {
            return Result.of(Status.NOT_LIVE);
        }
        Walk walk = walk(player, climb, picks);
        if (walk.spent().isEmpty()) {
            return Result.of(Status.NOTHING);
        }
        PickUndo.record(player, PickUndo.Entry.batch(def.id(), walk.spent(), false, System.currentTimeMillis()));
        changed(player, walk.climb(), ZigTrackPicksChangedEvent.Change.TAKEN, null, null, List.of());
        return new Result(Status.DONE, def.id(), null, null, List.of(), walk.spent().size());
    }

    /** What a walk along an ordered list of takes did: the climb after it, the takes made, and the ids left. */
    private record Walk(@Nonnull Climb climb, @Nonnull List<String> spent, @Nonnull List<String> left) {
    }

    /**
     * Take each of {@code picks} once on {@code climb}, in order, keeping each take: one taken goes; one that must
     * wait (points, a level, a gate, a prerequisite, a store that would not keep it) stops the walk, it and every
     * later one left; one that can never come back as it is (taken as often as it may be, its node taken another
     * way) is dropped; one unknown here or a trainer's is left and passed over. No undo, grant or event: the caller's,
     * once.
     */
    @Nonnull
    private static Walk walk(@Nonnull UUID player, @Nonnull Climb climb, @Nonnull List<String> picks) {
        GateQuery query = queryOf(climb.ref());
        Climb now = climb;
        List<String> spent = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (int i = 0; i < picks.size(); i++) {
            String node = TrackKey.normalize(picks.get(i));
            TrackPicks.PickDef pick = TrackPicks.pick(node);
            Status status = takeStatus(player, now, pick, null, query);
            if (status == Status.UNKNOWN || status == Status.ELSEWHERE) {
                left.add(node);
                continue;
            }
            if (status == Status.MAXED || status == Status.NODE_TAKEN) {
                continue;
            }
            Climb after = status == Status.DONE ? takeInto(player, now, pick, query) : null;
            if (after == null) {
                for (int j = i; j < picks.size(); j++) {
                    left.add(TrackKey.normalize(picks.get(j)));
                }
                break;
            }
            now = after;
            spent.add(pick.id());
        }
        return new Walk(now, List.copyOf(spent), List.copyOf(left));
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

    /**
     * Is {@code pick} open to a record at {@code level}: its level reached, no other face of its node taken, its
     * Requires met, its prerequisites taken.
     */
    @Nonnull
    static Status open(@Nonnull TrackPicks.PickDef pick, @Nonnull PickState state, int level,
            @Nullable GateQuery query) {
        if (level < pick.asset().opensAt()) {
            return Status.NOT_OPEN;
        }
        for (String face : pick.otherFaces()) {
            if (state.count(face) > 0) {
                return Status.NODE_TAKEN;
            }
        }
        return gates(pick, state, query);
    }

    /**
     * Does {@code pick} meet what it asks on {@code state}: its live Requires, asked of {@code query}'s player
     * ({@link Status#LOCKED}; not asked without one), then its prerequisites ({@link Status#NEEDS_PREREQUISITE}).
     */
    @Nonnull
    private static Status gates(@Nonnull TrackPicks.PickDef pick, @Nonnull PickState state,
            @Nullable GateQuery query) {
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

    /** {@link #gates} as a yes or no: what an undo weighs each held pick by. */
    private static boolean meets(@Nonnull TrackPicks.PickDef pick, @Nonnull PickState state,
            @Nullable GateQuery query) {
        return gates(pick, state, query) == Status.DONE;
    }

    /** May {@code pick} be taken once more on {@code climb} at {@code placement}: every rule a take obeys. */
    @Nonnull
    private static Status takeStatus(@Nonnull UUID player, @Nonnull Climb climb, @Nullable TrackPicks.PickDef pick,
            @Nullable String placement, @Nullable GateQuery query) {
        if (pick == null || pick.isChoice() || !pick.trackId().equals(climb.def().id())) {
            return Status.UNKNOWN;
        }
        PickState state = climb.record().picks();
        Status open = open(pick, state, pickLevel(climb.def(), climb.record()), query);
        if (open != Status.DONE) {
            return open;
        }
        if (!at(pick, placement)) {
            return Status.ELSEWHERE;
        }
        if (state.takesOf(pick.id()) >= pick.asset().repeatMax()) {
            return Status.MAXED;
        }
        return costStatus(player, pick, new HashMap<>());
    }

    /** May the slot {@code parts} names be chosen with {@code wanted} on {@code climb}: every rule a choice obeys. */
    @Nonnull
    private static Status chooseStatus(@Nonnull UUID player, @Nonnull Climb climb, @Nullable TrackPicks.Slot parts,
            @Nonnull List<String> wanted, @Nullable String placement, @Nullable GateQuery query) {
        TrackPicks.PickDef pick = parts == null ? null : TrackPicks.pick(parts.pickId());
        TrackPicks.GroupDef group = pick == null ? null : pick.group(parts.groupKey());
        if (group == null || !pick.isChoice() || !pick.trackId().equals(climb.def().id())) {
            return Status.UNKNOWN;
        }
        int level = pickLevel(climb.def(), climb.record());
        if (parts.level() > level || !group.opensAt(parts.level())) {
            return Status.NOT_OPEN;
        }
        PickState state = climb.record().picks();
        if (!state.chosen(pick.slotKey(group, parts.level())).isEmpty()) {
            return Status.ALREADY_MADE;
        }
        Status open = open(pick, state, level, query);
        if (open != Status.DONE) {
            return open;
        }
        if (!at(pick, placement)) {
            return Status.ELSEWHERE;
        }
        List<String> offer = group.offerAt(parts.level());
        if (wanted.size() != Math.min(group.choose(), offer.size()) || wanted.isEmpty()) {
            return Status.WRONG_COUNT;
        }
        if (!offer.containsAll(wanted)) {
            return Status.NOT_OFFERED;
        }
        return costStatus(player, pick, new HashMap<>());
    }

    /**
     * Take {@code pick} once more on {@code climb} when every rule allows it, keep it and move the gate epoch so the
     * next ask reads it; no undo, grant or event (the caller's, once, after a walk). Null when it was not taken.
     */
    @Nullable
    private static Climb takeInto(@Nonnull UUID player, @Nonnull Climb climb, @Nullable TrackPicks.PickDef pick,
            @Nullable GateQuery query) {
        if (takeStatus(player, climb, pick, null, query) != Status.DONE) {
            return null;
        }
        TrackRecord next = climb.record().withPicks(climb.record().picks().withTakes(pick.id(), 1));
        if (!climb.store().put(climb.key(), next)) {
            return null;
        }
        GateEpochs.bumpPlayer(player);
        return climb.with(next);
    }

    /** {@link #takeInto} for a choice: {@code options} chosen at {@code slot} when every rule allows it. */
    @Nullable
    private static Climb chooseInto(@Nonnull UUID player, @Nonnull Climb climb, @Nonnull String slot,
            @Nonnull List<String> options, @Nullable GateQuery query) {
        TrackPicks.Slot parts = TrackPicks.slot(slot);
        List<String> wanted = normalized(options);
        if (chooseStatus(player, climb, parts, wanted, null, query) != Status.DONE) {
            return null;
        }
        TrackPicks.PickDef pick = TrackPicks.pick(parts.pickId());
        String key = pick.slotKey(pick.group(parts.groupKey()), parts.level());
        TrackRecord next = climb.record().withPicks(climb.record().picks().withChoice(key, wanted));
        if (!climb.store().put(climb.key(), next)) {
            return null;
        }
        GateEpochs.bumpPlayer(player);
        return climb.with(next);
    }

    /**
     * {@code state} after a respec on {@code def}: only what a trainer taught stays ({@link #taughtByTrainer}), and
     * the picks in use no longer count as any saved build's. Nothing held is re-gated.
     */
    @Nonnull
    private static PickState respecced(@Nonnull TrackDef def, @Nonnull PickState state) {
        return state.keptOnly(taughtByTrainer(def)).withActiveLoadout(0);
    }

    /**
     * Whether each pick {@code player} holds on any track meets what it asks right now (its Requires and
     * prerequisites), by pick id: what an undo is weighed against.
     */
    @Nonnull
    private static Map<String, Boolean> gatesHeld(@Nonnull UUID player, @Nonnull GateQuery query) {
        Map<String, Boolean> out = new TreeMap<>();
        Map<String, TrackRecord> records = new HashMap<>();
        for (TrackPicks.PickDef pick : TrackPicks.all().values()) {
            TrackRecord record = record(player, pick.trackId(), records);
            if (record != null && record.picks().count(pick.id()) > 0) {
                out.put(pick.id(), meets(pick, record.picks(), query));
            }
        }
        return out;
    }

    /** Does a pick that met what it asks in {@code before}, and is still held, no longer meet it now? */
    private static boolean losesAGate(@Nonnull UUID player, @Nonnull GateQuery query,
            @Nonnull Map<String, Boolean> before) {
        Map<String, TrackRecord> records = new HashMap<>();
        for (Map.Entry<String, Boolean> held : before.entrySet()) {
            if (!held.getValue()) {
                continue;
            }
            TrackPicks.PickDef pick = TrackPicks.pick(held.getKey());
            TrackRecord record = pick == null ? null : record(player, pick.trackId(), records);
            if (record != null && record.picks().count(pick.id()) > 0 && !meets(pick, record.picks(), query)) {
                return true;
            }
        }
        return false;
    }

    /** Is the player at {@code ref} in a live encounter's roster (a boss fight under way)? */
    private static boolean inEncounter(@Nonnull Ref<EntityStore> ref) {
        try {
            return EncounterRuns.runOfMember(ref) != null || EncounterRuns.runRostering(ref.getStore(), ref) != null;
        } catch (Throwable t) {
            SafeLog.fine("[track] could not read whether a player is in an encounter: " + t.getMessage());
            return false;
        }
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
        return TrackPicks.spentOn(costTrackId, TrackPicks.all().values(), id -> record(player, id, records));
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

    /**
     * Is {@code placement} somewhere {@code pick} is taken: one of the trainers its {@code Where} names that teach
     * now, or anywhere for a pick naming none that teaches ({@link PickTrainers#of}).
     */
    private static boolean at(@Nonnull TrackPicks.PickDef pick, @Nullable String placement) {
        List<String> trainers = PickTrainers.of(pick.asset());
        return trainers.isEmpty() || (placement != null && trainers.contains(TrackKey.normalize(placement)));
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
