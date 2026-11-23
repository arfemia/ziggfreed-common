package com.ziggfreed.common.track.stance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.asset.SlotRow;
import com.ziggfreed.common.track.pick.TrackPicks;

/**
 * The stance rules, pure: which slots are open, which held faces count, where a face would be held and what a settle
 * frees. {@link Stances} asks them of a live player (the record, ownership off their track records, a slot's
 * {@code Requires} through the server's one evaluator); a test asks them of plain data.
 *
 * <p>A held face <b>counts</b> while its stance's slot that holds it is a row of the stance and open, the face is a
 * known pick that is a face of that stance, the player owns it, and no more faces of its kind count in the slots
 * before it than its kind's {@code Max} allows. A face that does not count adds nothing and opens nothing; whether its
 * record row is freed is the settle's word ({@link #freed}).
 */
public final class StanceRules {

    /** One held face that counts: its stance, the slot holding it, its pick id and its kind (each lower-cased). */
    public record LiveFace(@Nonnull String stanceId, @Nonnull String slotId, @Nonnull String faceId,
                           @Nullable String kind) {
    }

    /** What a hold would do: its status, and the slot it fills when it is {@link Stances.Status#DONE}. */
    public record Decision(@Nonnull Stances.Status status, @Nullable String slotId) {

        public boolean done() {
            return status == Stances.Status.DONE;
        }
    }

    private StanceRules() {
    }

    /**
     * The rows of {@code stance} open now, in fill order: each whose key pick {@code owns} answers for and whose
     * {@code Requires} {@code passes}.
     */
    @Nonnull
    public static List<SlotRow> open(@Nonnull TrackStanceAsset stance, @Nonnull Predicate<String> owns,
            @Nonnull Predicate<GateSpec> passes) {
        List<SlotRow> out = new ArrayList<>();
        for (SlotRow row : stance.slots()) {
            if (row.opens(owns, passes)) {
                out.add(row);
            }
        }
        return List.copyOf(out);
    }

    /** Is {@code pick} a face of the stance {@code stanceId} (lower-cased)? False for null. */
    public static boolean isFaceOf(@Nullable TrackPicks.PickDef pick, @Nonnull String stanceId) {
        return pick != null && pick.asset().isStanceFace() && stanceId.equals(pick.asset().stanceId());
    }

    /**
     * Every face {@code held} holds in {@code stance} that counts, in the stance's slot order: its slot a row of the
     * stance and open, the face a face of the stance ({@code picks} by lower-cased id), owned ({@code owns}), and
     * within its kind's cap counted in slot order.
     */
    @Nonnull
    public static List<LiveFace> live(@Nonnull TrackStanceAsset stance, @Nonnull HeldStances held,
            @Nonnull Function<String, TrackPicks.PickDef> picks, @Nonnull Predicate<String> owns,
            @Nonnull Predicate<GateSpec> passes) {
        String stanceId = TrackKey.normalize(stance.getId());
        List<LiveFace> out = new ArrayList<>();
        Map<String, Integer> perKind = new HashMap<>();
        for (SlotRow row : stance.slots()) {
            String face = held.faceIn(stanceId, row.key());
            if (face == null || !row.opens(owns, passes)) {
                continue;
            }
            TrackPicks.PickDef pick = picks.apply(face);
            if (!isFaceOf(pick, stanceId) || !owns.test(face)) {
                continue;
            }
            String kind = pick.asset().stanceKind();
            if (kind != null) {
                TrackStanceAsset.Kind declared = stance.kind(kind);
                int max = declared == null ? Integer.MAX_VALUE : declared.max();
                if (perKind.getOrDefault(kind, 0) >= max) {
                    continue;
                }
                perKind.merge(kind, 1, Integer::sum);
            }
            out.add(new LiveFace(stanceId, row.key(), face, kind));
        }
        return List.copyOf(out);
    }

    /**
     * Where a hold of {@code faceId} in {@code stance} would go, read without writing: {@link Stances.Status#UNKNOWN}
     * for a pick that is no face of it, {@link Stances.Status#NOT_OWNED} for one the player does not own,
     * {@link Stances.Status#ALREADY_HELD} for one that counts already, {@link Stances.Status#KIND_FULL} when its kind
     * counts as many as its {@code Max} allows, {@link Stances.Status#NO_SLOT} when every open slot holds a face that
     * counts; else {@link Stances.Status#DONE} and the lowest open slot holding none (a slot whose face no longer
     * counts is free again).
     */
    @Nonnull
    public static Decision hold(@Nonnull TrackStanceAsset stance, @Nonnull HeldStances held, @Nullable String faceId,
            @Nonnull Function<String, TrackPicks.PickDef> picks, @Nonnull Predicate<String> owns,
            @Nonnull Predicate<GateSpec> passes) {
        String stanceId = TrackKey.normalize(stance.getId());
        String face = TrackKey.normalize(faceId);
        TrackPicks.PickDef pick = face.isEmpty() ? null : picks.apply(face);
        if (!isFaceOf(pick, stanceId)) {
            return new Decision(Stances.Status.UNKNOWN, null);
        }
        if (!owns.test(face)) {
            return new Decision(Stances.Status.NOT_OWNED, null);
        }
        List<LiveFace> live = live(stance, held, picks, owns, passes);
        Set<String> taken = new HashSet<>();
        for (LiveFace one : live) {
            if (one.faceId().equals(face)) {
                return new Decision(Stances.Status.ALREADY_HELD, one.slotId());
            }
            taken.add(one.slotId());
        }
        String kind = pick.asset().stanceKind();
        if (kind != null) {
            TrackStanceAsset.Kind declared = stance.kind(kind);
            int max = declared == null ? Integer.MAX_VALUE : declared.max();
            int count = 0;
            for (LiveFace one : live) {
                if (kind.equals(one.kind())) {
                    count++;
                }
            }
            if (count >= max) {
                return new Decision(Stances.Status.KIND_FULL, null);
            }
        }
        for (SlotRow row : open(stance, owns, passes)) {
            if (!taken.contains(row.key())) {
                return new Decision(Stances.Status.DONE, row.key());
            }
        }
        return new Decision(Stances.Status.NO_SLOT, null);
    }

    /**
     * The rows of {@code held} in {@code stance} a settle frees: each that does not count ({@link #live}) but for one
     * whose face is no pick the fold knows (switched off or gone) in a slot still open, which is kept, unread, until
     * the pick is back. So a face is freed when the player no longer owns it, when it is no longer a face of the
     * stance, when its slot is gone or closed, and when its kind counts more than its cap allows (the later slots
     * first).
     */
    @Nonnull
    public static List<HeldStances.Held> freed(@Nonnull TrackStanceAsset stance, @Nonnull HeldStances held,
            @Nonnull Function<String, TrackPicks.PickDef> picks, @Nonnull Predicate<String> owns,
            @Nonnull Predicate<GateSpec> passes) {
        String stanceId = TrackKey.normalize(stance.getId());
        Set<String> counting = new HashSet<>();
        for (LiveFace one : live(stance, held, picks, owns, passes)) {
            counting.add(one.slotId());
        }
        Map<String, SlotRow> rows = new HashMap<>();
        for (SlotRow row : stance.slots()) {
            rows.put(row.key(), row);
        }
        List<HeldStances.Held> out = new ArrayList<>();
        for (HeldStances.Held one : held.of(stanceId)) {
            if (counting.contains(one.slot())) {
                continue;
            }
            SlotRow row = rows.get(one.slot());
            boolean waiting = picks.apply(one.face()) == null && row != null && row.opens(owns, passes);
            if (!waiting) {
                out.add(one);
            }
        }
        return List.copyOf(out);
    }
}
