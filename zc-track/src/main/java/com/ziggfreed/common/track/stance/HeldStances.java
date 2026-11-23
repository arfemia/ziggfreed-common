package com.ziggfreed.common.track.stance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.track.TrackKey;

/**
 * What one player holds in their stances, immutable: each held face, in the stance and the slot that holds it, every
 * id lower-cased. A slot holds one face and a face sits in one slot of its stance; a row naming a stance, a slot or a
 * face that is no longer there is kept, unread, until it is again (what {@link Stances} reads as held is only what
 * still stands). Kept on the player's track record ({@code TrackComponent}'s {@code Stances} leaf) through the track
 * store's seam.
 *
 * @param rows every held face, sorted by stance then slot, so a save is stable
 */
public record HeldStances(@Nonnull List<Held> rows) {

    /** Nothing held. */
    public static final HeldStances EMPTY = new HeldStances(List.of());

    /** One held face: its stance, the slot holding it, and the face's pick id, each lower-cased. */
    public record Held(@Nonnull String stance, @Nonnull String slot, @Nonnull String face) {

        public Held {
            stance = TrackKey.normalize(stance);
            slot = TrackKey.normalize(slot);
            face = TrackKey.normalize(face);
        }
    }

    public HeldStances {
        rows = normalized(rows);
    }

    /** Is nothing held? */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** What {@code stance} (any case) holds, by slot order of the record (sorted by slot id). */
    @Nonnull
    public List<Held> of(@Nullable String stance) {
        String wanted = TrackKey.normalize(stance);
        List<Held> out = new ArrayList<>();
        for (Held held : rows) {
            if (held.stance().equals(wanted)) {
                out.add(held);
            }
        }
        return List.copyOf(out);
    }

    /** The face {@code slot} of {@code stance} holds, or null for an empty slot. */
    @Nullable
    public String faceIn(@Nullable String stance, @Nullable String slot) {
        String s = TrackKey.normalize(stance);
        String k = TrackKey.normalize(slot);
        for (Held held : rows) {
            if (held.stance().equals(s) && held.slot().equals(k)) {
                return held.face();
            }
        }
        return null;
    }

    /** The slot of {@code stance} holding {@code face}, or null when it is not held there. */
    @Nullable
    public String slotOf(@Nullable String stance, @Nullable String face) {
        String s = TrackKey.normalize(stance);
        String f = TrackKey.normalize(face);
        for (Held held : rows) {
            if (held.stance().equals(s) && held.face().equals(f)) {
                return held.slot();
            }
        }
        return null;
    }

    /** This record with {@code face} held in {@code slot} of {@code stance}, replacing what that slot held. */
    @Nonnull
    public HeldStances with(@Nonnull String stance, @Nonnull String slot, @Nonnull String face) {
        Held next = new Held(stance, slot, face);
        List<Held> out = new ArrayList<>(rows.size() + 1);
        out.add(next);
        for (Held held : rows) {
            boolean sameSlot = held.stance().equals(next.stance()) && held.slot().equals(next.slot());
            boolean sameFace = held.stance().equals(next.stance()) && held.face().equals(next.face());
            if (!sameSlot && !sameFace) {
                out.add(held);
            }
        }
        return new HeldStances(out);
    }

    /** This record with {@code slot} of {@code stance} emptied; itself when it held nothing. */
    @Nonnull
    public HeldStances without(@Nonnull String stance, @Nonnull String slot) {
        String s = TrackKey.normalize(stance);
        String k = TrackKey.normalize(slot);
        List<Held> out = new ArrayList<>(rows.size());
        for (Held held : rows) {
            if (!(held.stance().equals(s) && held.slot().equals(k))) {
                out.add(held);
            }
        }
        return out.size() == rows.size() ? this : new HeldStances(out);
    }

    /**
     * {@code raw} as a record keeps it: blank parts left out, one face per slot and one slot per face in a stance (the
     * first written kept), sorted by stance then slot.
     */
    @Nonnull
    private static List<Held> normalized(@Nullable Collection<Held> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> slots = new HashSet<>();
        Set<String> faces = new HashSet<>();
        List<Held> out = new ArrayList<>(raw.size());
        for (Held held : raw) {
            if (held == null || held.stance().isEmpty() || held.slot().isEmpty() || held.face().isEmpty()) {
                continue;
            }
            String slot = held.stance() + '|' + held.slot();
            String face = held.stance() + '|' + held.face();
            if (slots.contains(slot) || faces.contains(face)) {
                continue;
            }
            slots.add(slot);
            faces.add(face);
            out.add(held);
        }
        out.sort(Comparator.comparing(Held::stance).thenComparing(Held::slot));
        return List.copyOf(out);
    }
}
