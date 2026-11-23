package com.ziggfreed.common.track.stance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.grant.Grant;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.pick.TrackPicks;

/**
 * Which of one player's held faces count right now, across every stance, as the grant fold and the readings ask it:
 * {@link #counts} says whether a pick's grants and readings live (a pick in no stance always does; one in a stance
 * while its face counts), and {@link #faces} lists each face that counts with what holding it adds (its kind's
 * {@code WhileHeld}). Built from the record, ownership and the stances alone ({@link StanceRules#live}); a slot's
 * {@code Requires} is the settle's to read ({@link Stances#settle}), so building a view never asks a gate.
 */
public final class StanceView {

    /** Nothing held: every pick in a stance counts nowhere, every other pick as it always did. */
    public static final StanceView NONE = new StanceView(Set.of(), List.of());

    /**
     * One face that counts: its stance, the slot holding it, the face's pick id, the track and category it sits on (a
     * world's grant rules read them), its kind, and the grants its kind adds while it is held.
     */
    public record HeldFace(@Nonnull String stanceId, @Nonnull String slotId, @Nonnull String faceId,
                           @Nonnull String trackId, @Nullable String categoryId, @Nullable String kind,
                           @Nonnull List<Grant> whileHeld) {

        public HeldFace {
            whileHeld = List.copyOf(whileHeld);
        }
    }

    @Nonnull private final Set<String> held;
    @Nonnull private final List<HeldFace> faces;

    private StanceView(@Nonnull Set<String> held, @Nonnull List<HeldFace> faces) {
        this.held = Set.copyOf(held);
        this.faces = List.copyOf(faces);
    }

    /**
     * The view of {@code record}: per live stance {@code stances} answers (switched on; null for one unknown or
     * off), the faces that count ({@link StanceRules#live}, no gate asked), each face's pick out of {@code picks} (by
     * lower-cased id), owned where {@code owns} says so.
     */
    @Nonnull
    public static StanceView of(@Nonnull HeldStances record, @Nonnull Function<String, TrackStanceAsset> stances,
            @Nonnull Map<String, TrackPicks.PickDef> picks, @Nonnull Predicate<String> owns) {
        if (record.isEmpty()) {
            return NONE;
        }
        Set<String> seen = new HashSet<>();
        Set<String> held = new HashSet<>();
        List<HeldFace> faces = new ArrayList<>();
        for (HeldStances.Held row : record.rows()) {
            if (!seen.add(row.stance())) {
                continue;
            }
            TrackStanceAsset stance = stances.apply(row.stance());
            if (stance == null) {
                continue;
            }
            for (StanceRules.LiveFace face : StanceRules.live(stance, record, picks::get, owns, spec -> true)) {
                TrackPicks.PickDef pick = picks.get(face.faceId());
                TrackStanceAsset.Kind kind = face.kind() == null ? null : stance.kind(face.kind());
                held.add(face.faceId());
                faces.add(new HeldFace(face.stanceId(), face.slotId(), face.faceId(), pick.trackId(),
                        pick.categoryId(), face.kind(), kind == null ? List.of() : kind.whileHeld()));
            }
        }
        return new StanceView(held, faces);
    }

    /** Does the face {@code faceId} (any case) count now? */
    public boolean holds(@Nullable String faceId) {
        return faceId != null && held.contains(TrackKey.normalize(faceId));
    }

    /**
     * Do {@code pick}'s grants and readings live now: always for a pick in no stance, else while its face counts. A
     * pick the fold does not know counts as one in no stance.
     */
    public boolean counts(@Nullable TrackPicks.PickDef pick) {
        String face = pick == null ? null : pick.asset().stanceFace();
        return face == null || held.contains(face);
    }

    /** Every face that counts, stance by stance in the record's order, each in its stance's slot order. */
    @Nonnull
    public List<HeldFace> faces() {
        return faces;
    }

    /** The faces of {@code stanceId} (any case) that count, in slot order. */
    @Nonnull
    public List<HeldFace> faces(@Nullable String stanceId) {
        String wanted = TrackKey.normalize(stanceId);
        List<HeldFace> out = new ArrayList<>();
        for (HeldFace face : faces) {
            if (face.stanceId().equals(wanted)) {
                out.add(face);
            }
        }
        return List.copyOf(out);
    }
}
