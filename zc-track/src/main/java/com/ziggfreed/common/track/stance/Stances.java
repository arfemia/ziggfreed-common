package com.ziggfreed.common.track.stance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.commerce.fold.CommerceFold;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.progress.gate.GateEpochs;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.track.TrackDerived;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackStore;
import com.ziggfreed.common.track.TrackStores;
import com.ziggfreed.common.track.event.TrackEvents;
import com.ziggfreed.common.track.event.ZigStanceChangedEvent;
import com.ziggfreed.common.track.pick.PickPayments;
import com.ziggfreed.common.track.pick.PickService;
import com.ziggfreed.common.track.pick.PickTrainers;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one reader and writer of what a player holds in their stances ({@link TrackStanceAsset}): picks owned for good
 * once taken that count only while their face is held in one of the stance's slots. Every write ({@link #hold},
 * {@link #release}, their admin forms, {@link #settle}) is the live player's own world thread, keeps the record
 * through the track store's stance seam ({@code TrackStore.putStances}), moves the player's gate epoch (the
 * {@code ziggfreedcommon:track_pick}, {@code track_stance} and {@code track_stance_slots} readings, Volatility
 * Player), rebuilds the player's grants ({@code TrackDerived.rebuild}) and then fires {@code ZigStanceChangedEvent}
 * once per face moved.
 *
 * <ul>
 *   <li><b>Holding</b> a face fills the lowest open slot holding nothing that counts, at the stance's {@code Hold}
 *       price (free unwritten): refused for a pick that is no face of the stance ({@link Status#UNKNOWN}), one the
 *       player does not own ({@link Status#NOT_OWNED}), one held already ({@link Status#ALREADY_HELD}), a kind held
 *       as often as its {@code Max} allows ({@link Status#KIND_FULL}), no open slot free ({@link Status#NO_SLOT}),
 *       away from the placements its {@code Where} names that stand ({@link Status#NOT_HERE}).</li>
 *   <li><b>Releasing</b> one is the player's own choice, at the stance's {@code Break} price, refused in combat or
 *       mid-encounter by the rule a respec and a build swap keep ({@code PickService.respecRefusal}). A face that no
 *       longer counts (its pick switched off) is released free.</li>
 *   <li><b>A settle</b> frees, for nothing, every face that stopped counting ({@link StanceRules#freed}: no longer
 *       owned after an undo, a respec or an admin's change, its slot closed, its kind past a lowered cap): at every
 *       ready settle, after every pick change, and for everyone online after a stance fold.</li>
 * </ul>
 *
 * <p>An admin's forms ({@link #holdAsAdmin}, {@link #releaseAsAdmin}) ask neither the place nor a price nor the
 * fight. What a held face adds is {@link StanceView}'s, which the grant fold and the readings read.
 */
public final class Stances {

    /** What a hold or a release did, or would do. */
    public enum Status {
        /** Done. */
        DONE,
        /** No such stance switched on, or the pick is no face of it. */
        UNKNOWN,
        /** The player is not live here. */
        NOT_LIVE,
        /** The player does not own the face. */
        NOT_OWNED,
        /** The face is held already. */
        ALREADY_HELD,
        /** As many faces of its kind are held as the stance allows. */
        KIND_FULL,
        /** Every open slot holds a face. */
        NO_SLOT,
        /** The face is not held. */
        NOT_HELD,
        /** It is done at the placements the stance's Where names, not here. */
        NOT_HERE,
        /** The player is in combat. */
        IN_COMBAT,
        /** The player is in an encounter (a boss fight). */
        IN_ENCOUNTER,
        /** The price cannot be paid. */
        CANNOT_PAY
    }

    /** What a hold or a release did: its status, and the stance, the face and the slot it was about. */
    public record Result(@Nonnull Status status, @Nullable String stanceId, @Nullable String faceId,
                         @Nullable String slotId) {

        @Nonnull
        static Result of(@Nonnull Status status) {
            return new Result(status, null, null, null);
        }

        public boolean done() {
            return status == Status.DONE;
        }
    }

    /** One face moved by a write, for the event once the grants are rebuilt. */
    private record Moved(@Nonnull String stanceId, @Nonnull String slotId, @Nonnull String faceId,
                         @Nonnull ZigStanceChangedEvent.Change change) {
    }

    /** A live player as a write reads them: their entity, the store and the record it holds. */
    private record Live(@Nonnull UUID player, @Nonnull Ref<EntityStore> ref, @Nonnull TrackStore store,
                        @Nonnull TrackKey.Subject subject, @Nonnull HeldStances record) {

        @Nonnull
        Predicate<String> owns() {
            return owner(player);
        }

        @Nonnull
        Predicate<GateSpec> passes() {
            return passer(ref);
        }
    }

    /** The slot counts being read on this thread, so a slot's Requires reading its own stance's slots ends. */
    private static final ThreadLocal<Set<String>> READING_SLOTS = ThreadLocal.withInitial(HashSet::new);

    private Stances() {
    }

    // ==================== reads ====================

    /** What {@code player} holds, as kept ({@link HeldStances#EMPTY} when nothing, or when they are not live). */
    @Nonnull
    public static HeldStances held(@Nullable UUID player) {
        if (player == null) {
            return HeldStances.EMPTY;
        }
        TrackStore store = TrackStores.of(TrackKey.Subject.PLAYER);
        HeldStances held = store == null ? null : store.stances(TrackKey.Subject.player(player));
        return held == null ? HeldStances.EMPTY : held;
    }

    /**
     * Which of {@code player}'s held faces count now ({@link StanceView}): what the grant fold and the
     * {@code ziggfreedcommon:track_pick} reading ask. {@link StanceView#NONE} for nobody. Their world thread.
     */
    @Nonnull
    public static StanceView view(@Nullable UUID player) {
        HeldStances record = held(player);
        if (record.isEmpty()) {
            return StanceView.NONE;
        }
        return StanceView.of(record, TrackStanceConfig.getInstance()::live, TrackPicks.all(), owner(player));
    }

    /**
     * Whether a pick lives for {@code player} now ({@link StanceView#counts}), the view read on the first ask and
     * kept for the predicate's life (one reading's): what {@code TrackPicks.liveCount} is handed, so a track with no
     * pick in a stance never reads the view at all.
     */
    @Nonnull
    public static Predicate<TrackPicks.PickDef> counts(@Nullable UUID player) {
        StanceView[] view = new StanceView[1];
        return pick -> {
            if (view[0] == null) {
                view[0] = view(player);
            }
            return view[0].counts(pick);
        };
    }

    /** Does {@code faceId} count for {@code player} now (held, owned, its slot open)? */
    public static boolean holds(@Nonnull UUID player, @Nullable String faceId) {
        return view(player).holds(faceId);
    }

    /**
     * How many of {@code stanceId}'s slots are open for {@code player} now, each row's {@code Requires} asked of
     * them; null when the stance is unknown or off, the player is not live, or a slot's {@code Requires} asks this
     * very count (it cannot answer itself). What {@code ziggfreedcommon:track_stance_slots} reads. Their world thread.
     */
    @Nullable
    public static Integer slotsOpen(@Nonnull UUID player, @Nullable String stanceId) {
        TrackStanceAsset stance = TrackStanceConfig.getInstance().live(stanceId);
        Ref<EntityStore> ref = stance == null ? null : PlayerAccess.liveRef(player);
        if (ref == null) {
            return null;
        }
        String key = player + "|" + TrackKey.normalize(stance.getId());
        Set<String> reading = READING_SLOTS.get();
        if (!reading.add(key)) {
            return null;
        }
        try {
            return StanceRules.open(stance, owner(player), passer(ref)).size();
        } finally {
            reading.remove(key);
        }
    }

    /**
     * The slots of {@code stanceId} open for {@code player} now, in fill order (each row's {@code Requires} asked);
     * empty when none, the stance is unknown or off, or the player is not live. What the stance page lists.
     */
    @Nonnull
    public static List<String> openSlots(@Nonnull UUID player, @Nullable String stanceId) {
        TrackStanceAsset stance = TrackStanceConfig.getInstance().live(stanceId);
        Ref<EntityStore> ref = stance == null ? null : PlayerAccess.liveRef(player);
        if (ref == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        StanceRules.open(stance, owner(player), passer(ref)).forEach(row -> out.add(row.key()));
        return List.copyOf(out);
    }

    /**
     * What a hold of {@code faceId} in {@code stanceId} by {@code player} at {@code placement} (the page's trainer,
     * null for none) would answer now, read without writing: the order {@link #hold} asks, the price checked, never
     * charged. Their world thread.
     */
    @Nonnull
    public static Status holdStatus(@Nonnull UUID player, @Nullable String stanceId, @Nullable String faceId,
            @Nullable String placement) {
        TrackStanceAsset stance = TrackStanceConfig.getInstance().live(stanceId);
        if (stance == null) {
            return Status.UNKNOWN;
        }
        Live live = live(player);
        if (live == null) {
            return Status.NOT_LIVE;
        }
        StanceRules.Decision decision = StanceRules.hold(stance, live.record(), faceId, TrackPicks::pick,
                live.owns(), live.passes());
        if (!decision.done()) {
            return decision.status();
        }
        if (!here(stance, placement)) {
            return Status.NOT_HERE;
        }
        return PickPayments.canPay(live.ref(), holdPrice(stance)) ? Status.DONE : Status.CANNOT_PAY;
    }

    /**
     * What {@code player}'s own release of {@code faceId} from {@code stanceId} at {@code placement} would answer now,
     * read without writing: not held, in a fight or an encounter, not here, or a price they cannot pay. Their world
     * thread.
     */
    @Nonnull
    public static Status releaseStatus(@Nonnull UUID player, @Nullable String stanceId, @Nullable String faceId,
            @Nullable String placement) {
        TrackStanceAsset stance = TrackStanceConfig.getInstance().live(stanceId);
        if (stance == null) {
            return Status.UNKNOWN;
        }
        Live live = live(player);
        if (live == null) {
            return Status.NOT_LIVE;
        }
        if (live.record().slotOf(stance.getId(), faceId) == null) {
            return Status.NOT_HELD;
        }
        Status refused = refusal(player);
        if (refused != null) {
            return refused;
        }
        if (!here(stance, placement)) {
            return Status.NOT_HERE;
        }
        return !counts(stance, live, faceId) || PickPayments.canPay(live.ref(), breakPrice(stance)) ? Status.DONE
                : Status.CANNOT_PAY;
    }

    /** What filling a slot of {@code stance} costs ({@code Hold.Cost}, folded); {@link Cost#FREE} for none. */
    @Nonnull
    public static Cost holdPrice(@Nonnull TrackStanceAsset stance) {
        return price(stance.holdCost(), "holding a face of the stance " + stance.getId());
    }

    /** What a player's own release from {@code stance} costs ({@code Break.Cost}, folded); {@link Cost#FREE} for none. */
    @Nonnull
    public static Cost breakPrice(@Nonnull TrackStanceAsset stance) {
        return price(stance.breakCost(), "releasing a face of the stance " + stance.getId());
    }

    /**
     * Is {@code placement} somewhere {@code stance}'s faces are held and released: one of the placements its
     * {@code Where} names that stand now ({@code PickTrainers}), or anywhere for a stance naming none that stands.
     */
    public static boolean here(@Nonnull TrackStanceAsset stance, @Nullable String placement) {
        List<String> trainers = PickTrainers.teachingAmong(stance.placements());
        return trainers.isEmpty() || (placement != null && trainers.contains(TrackKey.normalize(placement)));
    }

    // ==================== writes ====================

    /**
     * Hold {@code faceId} in {@code stanceId} for {@code player}, at {@code placement} (the page's trainer, null for
     * none): into the lowest open slot holding nothing that counts, at the stance's {@code Hold} price, given back if
     * the record cannot be kept. World thread.
     */
    @Nonnull
    public static Result hold(@Nonnull UUID player, @Nullable String stanceId, @Nullable String faceId,
            @Nullable String placement) {
        return hold(player, stanceId, faceId, placement, false);
    }

    /** An admin's hold: {@link #hold} by every rule a slot keeps, but anywhere and free. World thread. */
    @Nonnull
    public static Result holdAsAdmin(@Nonnull UUID player, @Nullable String stanceId, @Nullable String faceId) {
        return hold(player, stanceId, faceId, null, true);
    }

    /**
     * {@code player}'s own release of {@code faceId} from {@code stanceId}, at {@code placement}: at the stance's
     * {@code Break} price (free for a face that no longer counts), refused in combat or mid-encounter, given back if
     * the record cannot be kept. World thread.
     */
    @Nonnull
    public static Result release(@Nonnull UUID player, @Nullable String stanceId, @Nullable String faceId,
            @Nullable String placement) {
        return release(player, stanceId, faceId, placement, false);
    }

    /** An admin's release: free, anywhere, in a fight or not. World thread. */
    @Nonnull
    public static Result releaseAsAdmin(@Nonnull UUID player, @Nullable String stanceId, @Nullable String faceId) {
        return release(player, stanceId, faceId, null, true);
    }

    /**
     * Free, for nothing, every face {@code player} holds that stopped counting ({@link StanceRules#freed}), in every
     * stance switched on: kept, the epoch moved, the grants rebuilt and one {@code ZigStanceChangedEvent}
     * ({@code FREED}) per face. Run at every ready settle, after every change of a player's picks, and for everyone
     * online after a stance fold. World thread.
     *
     * @return how many faces it freed, 0 when the player is not live or nothing stopped counting
     */
    public static int settle(@Nullable UUID player) {
        Live live = player == null ? null : live(player);
        if (live == null || live.record().isEmpty()) {
            return 0;
        }
        Predicate<String> owns = live.owns();
        Predicate<GateSpec> passes = live.passes();
        HeldStances next = live.record();
        List<Moved> moved = new ArrayList<>();
        Set<String> stanceIds = new LinkedHashSet<>();
        live.record().rows().forEach(row -> stanceIds.add(row.stance()));
        for (String stanceId : stanceIds) {
            TrackStanceAsset stance = TrackStanceConfig.getInstance().live(stanceId);
            if (stance == null) {
                continue;
            }
            for (HeldStances.Held gone : StanceRules.freed(stance, next, TrackPicks::pick, owns, passes)) {
                next = next.without(gone.stance(), gone.slot());
                moved.add(new Moved(gone.stance(), gone.slot(), gone.face(), ZigStanceChangedEvent.Change.FREED));
            }
        }
        if (moved.isEmpty() || !live.store().putStances(live.subject(), next)) {
            return 0;
        }
        moved(live, moved);
        return moved.size();
    }

    /** Queue {@link #settle} for every online player, each on their own world thread. Any thread. */
    public static void settleAllOnline() {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            for (PlayerRef player : universe.getPlayers()) {
                Ref<EntityStore> ref = player == null ? null : player.getReference();
                UUID id = player == null ? null : player.getUuid();
                if (ref == null || id == null || !ref.isValid()) {
                    continue;
                }
                WorldEvictors.worldOf(ref).execute(() -> {
                    try {
                        settle(id);
                    } catch (Throwable t) {
                        SafeLog.warn("[track] a player's stances could not be settled: " + t.getMessage());
                    }
                });
            }
        } catch (Throwable t) {
            // FINE: a stopping world refuses every task, and its players settle where they land.
            SafeLog.fine("[track] could not queue settling every online player's stances: " + t.getMessage());
        }
    }

    /**
     * A stance folded: the gate server epoch moves (every stance reading may read otherwise), every online player's
     * held faces are settled and their grants rebuilt, each on their own world thread, so a lowered cap or a closed
     * slot frees what it no longer holds and a retuned {@code WhileHeld} pays its new amount.
     */
    public static void invalidate() {
        GateEpochs.bumpServer();
        settleAllOnline();
        TrackDerived.rebuildAllOnline();
    }

    // ==================== the pieces ====================

    @Nonnull
    private static Result hold(@Nonnull UUID player, @Nullable String stanceId, @Nullable String faceId,
            @Nullable String placement, boolean admin) {
        TrackStanceAsset stance = TrackStanceConfig.getInstance().live(stanceId);
        if (stance == null) {
            return Result.of(Status.UNKNOWN);
        }
        Live live = live(player);
        if (live == null) {
            return Result.of(Status.NOT_LIVE);
        }
        String id = TrackKey.normalize(stance.getId());
        String face = TrackKey.normalize(faceId);
        StanceRules.Decision decision = StanceRules.hold(stance, live.record(), face, TrackPicks::pick, live.owns(),
                live.passes());
        if (!decision.done()) {
            return new Result(decision.status(), id, face, decision.slotId());
        }
        if (!admin && !here(stance, placement)) {
            return new Result(Status.NOT_HERE, id, face, null);
        }
        PickPayments.Paid paid = admin ? PickPayments.Paid.FREE
                : PickPayments.charge(live.ref(), holdPrice(stance), "holding a face of the stance " + id);
        if (paid == null) {
            return new Result(Status.CANNOT_PAY, id, face, null);
        }
        String slot = decision.slotId();
        if (!live.store().putStances(live.subject(), live.record().with(id, slot, face))) {
            paid.refund();
            return Result.of(Status.NOT_LIVE);
        }
        moved(live, List.of(new Moved(id, slot, face, ZigStanceChangedEvent.Change.HELD)));
        return new Result(Status.DONE, id, face, slot);
    }

    @Nonnull
    private static Result release(@Nonnull UUID player, @Nullable String stanceId, @Nullable String faceId,
            @Nullable String placement, boolean admin) {
        TrackStanceAsset stance = TrackStanceConfig.getInstance().live(stanceId);
        if (stance == null) {
            return Result.of(Status.UNKNOWN);
        }
        Live live = live(player);
        if (live == null) {
            return Result.of(Status.NOT_LIVE);
        }
        String id = TrackKey.normalize(stance.getId());
        String face = TrackKey.normalize(faceId);
        String slot = live.record().slotOf(id, face);
        if (slot == null) {
            return new Result(Status.NOT_HELD, id, face, null);
        }
        if (!admin) {
            Status refused = refusal(player);
            if (refused != null) {
                return new Result(refused, id, face, slot);
            }
            if (!here(stance, placement)) {
                return new Result(Status.NOT_HERE, id, face, slot);
            }
        }
        boolean charged = !admin && counts(stance, live, face);
        PickPayments.Paid paid = charged
                ? PickPayments.charge(live.ref(), breakPrice(stance), "releasing a face of the stance " + id)
                : PickPayments.Paid.FREE;
        if (paid == null) {
            return new Result(Status.CANNOT_PAY, id, face, slot);
        }
        if (!live.store().putStances(live.subject(), live.record().without(id, slot))) {
            paid.refund();
            return Result.of(Status.NOT_LIVE);
        }
        moved(live, List.of(new Moved(id, slot, face, charged ? ZigStanceChangedEvent.Change.RELEASED
                : ZigStanceChangedEvent.Change.FREED)));
        return new Result(Status.DONE, id, face, slot);
    }

    /** Does {@code faceId} count in {@code stance} for {@code live}, its slot's Requires asked? */
    private static boolean counts(@Nonnull TrackStanceAsset stance, @Nonnull Live live, @Nullable String faceId) {
        String face = TrackKey.normalize(faceId);
        for (StanceRules.LiveFace one : StanceRules.live(stance, live.record(), TrackPicks::pick, live.owns(),
                live.passes())) {
            if (one.faceId().equals(face)) {
                return true;
            }
        }
        return false;
    }

    /** Keep a write's knock-on: the epoch, the grants, then one event per face moved. */
    private static void moved(@Nonnull Live live, @Nonnull List<Moved> moved) {
        GateEpochs.bumpPlayer(live.player());
        TrackDerived.rebuild(live.ref().getStore(), live.ref());
        for (Moved one : moved) {
            TrackEvents.fireStanceChanged(live.player(), one.stanceId(), one.slotId(), one.faceId(), one.change());
        }
    }

    /** Why a player's own release is refused now: in combat or mid-encounter (the respec's rule), or null. */
    @Nullable
    private static Status refusal(@Nonnull UUID player) {
        PickService.Status refused = PickService.respecRefusal(player);
        if (refused == PickService.Status.IN_COMBAT) {
            return Status.IN_COMBAT;
        }
        return refused == PickService.Status.IN_ENCOUNTER ? Status.IN_ENCOUNTER : null;
    }

    @Nonnull
    private static Cost price(@Nullable CostAsset asset, @Nonnull String source) {
        return asset == null ? Cost.FREE : CommerceFold.cost(asset, source);
    }

    /** {@code player} live here, with the record their store keeps; null when not. */
    @Nullable
    private static Live live(@Nonnull UUID player) {
        TrackStore store = TrackStores.of(TrackKey.Subject.PLAYER);
        Ref<EntityStore> ref = PlayerAccess.liveRef(player);
        if (store == null || ref == null) {
            return null;
        }
        TrackKey.Subject subject = TrackKey.Subject.player(player);
        HeldStances record = store.stances(subject);
        return record == null ? null : new Live(player, ref, store, subject, record);
    }

    /** Does {@code player} own a pick (by id, any case): taken at least once on its own track? Asked once a pick. */
    @Nonnull
    static Predicate<String> owner(@Nullable UUID player) {
        Map<String, Boolean> asked = new HashMap<>();
        return pickId -> {
            String id = TrackKey.normalize(pickId);
            if (player == null || id.isEmpty()) {
                return false;
            }
            return asked.computeIfAbsent(id, key -> {
                TrackPicks.PickDef pick = TrackPicks.pick(key);
                return pick != null && PickService.count(player, pick.trackId(), key) > 0;
            });
        };
    }

    /** A slot row's {@code Requires} asked of the player at {@code ref}, through the server's one evaluator. */
    @Nonnull
    private static Predicate<GateSpec> passer(@Nonnull Ref<EntityStore> ref) {
        GateQuery query = GateQuery.of(FactorContext.about(ref.getStore(), ref));
        return spec -> StoreGates.passes(spec, query);
    }
}
