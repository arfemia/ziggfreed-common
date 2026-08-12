package com.ziggfreed.common.progress;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One objective's progress for one player: how far along, how far it has to go, and whether it is
 * done. Mutable, because a dispatch pass advances several of these and writes the whole set back at
 * once.
 *
 * <p><b>Two ways to move it, and picking the wrong one corrupts the count.</b> {@link #advance} ADDS
 * a delta, which is what a "do this N times" objective wants. {@link #applyValue} raises a
 * high-water mark, which is what an objective tracking a player's CURRENT value wants (a run of 5
 * followed by a run of 4 must leave the objective at 5, not 9). Which one a dispatch uses is decided
 * by the {@code valueBased} flag on the objective's registered
 * {@link ObjectiveKind}, never guessed at the call site.
 *
 * <p>The {@link #serialize()} form ({@code "current/required"}) is a WIRE format: a consumer's store
 * may persist it verbatim, so keep it byte-stable. {@link #deserialize} never throws - unreadable
 * input yields an untouched {@code 0/1} rather than losing the player's whole record.
 */
public final class ObjectiveProgressState {

    private int current;
    private final int required;
    private boolean completed;

    /** A state at {@code current} out of {@code required}; already-met input is born completed. */
    public ObjectiveProgressState(int current, int required) {
        this.current = current;
        this.required = required;
        this.completed = current >= required;
    }

    public int current() {
        return current;
    }

    public int required() {
        return required;
    }

    public boolean isCompleted() {
        return completed;
    }

    /** Advance by one. Returns true only when THIS call finished the objective. */
    public boolean increment() {
        return advance(1L);
    }

    /**
     * Add {@code delta} to the count, clamped at {@code required}. A non-positive delta and an
     * already-finished objective are both no-ops. Returns true only when THIS call finished the
     * objective, so a caller can fire a completion exactly once.
     */
    public boolean advance(long delta) {
        if (completed || delta <= 0) {
            return false;
        }
        long next = (long) current + delta;
        current = next >= required ? required : (int) next;
        if (current >= required) {
            completed = true;
            return true;
        }
        return false;
    }

    /**
     * Raise the count to {@code value} (clamped at {@code required}) if that is higher than what is
     * already recorded, for an objective tracking a current value rather than accumulating one. It
     * never decreases, and a non-positive value is a no-op. Returns true only when THIS call
     * finished the objective.
     */
    public boolean applyValue(long value) {
        if (completed || value <= 0) {
            return false;
        }
        int clamped = value >= required ? required : (int) value;
        if (clamped > current) {
            current = clamped;
        }
        if (current >= required) {
            completed = true;
            return true;
        }
        return false;
    }

    /** The persisted form: {@code "current/required"}. Keep this stable - stores write it as-is. */
    @Nonnull
    public String serialize() {
        return current + "/" + required;
    }

    /**
     * Read back a {@link #serialize()} string. Anything unparseable (null, empty, no separator, a
     * non-number) yields a fresh {@code 0/1} instead of throwing, so one corrupted entry cannot take
     * a player's whole save with it.
     */
    @Nonnull
    public static ObjectiveProgressState deserialize(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return new ObjectiveProgressState(0, 1);
        }
        int slash = text.indexOf('/');
        if (slash <= 0) {
            return new ObjectiveProgressState(0, 1);
        }
        try {
            int current = Integer.parseInt(text.substring(0, slash));
            int required = Integer.parseInt(text.substring(slash + 1));
            return new ObjectiveProgressState(current, required);
        } catch (NumberFormatException e) {
            return new ObjectiveProgressState(0, 1);
        }
    }
}
