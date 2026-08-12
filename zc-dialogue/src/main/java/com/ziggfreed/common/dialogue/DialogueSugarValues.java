package com.ziggfreed.common.dialogue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The decoded values of the option-level SUGAR keys on one option (or on one {@code Do} atom):
 * {@code "Goto": "next"}, {@code "Accept": "intro"}, {@code "Reward": {...}} and whatever else a
 * consumer registered, each already decoded by its own leaf codec rather than left as raw JSON.
 *
 * <p>It exists so sugar can be part of the SCHEMA instead of a rewrite that runs before it. Every
 * registered {@link DialogueSugarLeaf} contributes a real keyed field to the option codec, the
 * decoded value lands here, and the canonical {@code Actions} list is folded out of it afterwards -
 * so an authoring mistake is a decode error naming the key, and the in-game asset editor can see
 * the shorthand the same way it sees everything else.
 *
 * <p>A leaf may also declare MODIFIER keys it reads beside its own ({@code Reward} reads
 * {@code RewardOnce}); those land here too, which is why a leaf is handed the whole set rather than
 * just its own value.
 */
public final class DialogueSugarValues {

    /** The empty set, for an option that authored no sugar at all. */
    public static final DialogueSugarValues EMPTY = new DialogueSugarValues();

    @Nullable private Map<String, Object> values;

    /**
     * Where a modifier this set does not carry is looked up next: for a {@code Do} atom, the option
     * around it. An option that writes {@code "RewardOnce": false} beside its {@code Do} array plainly
     * means it for the reward inside, and having to repeat it in the atom would be a trap rather than
     * a rule. An atom that states the modifier itself still wins.
     */
    @Nullable private DialogueSugarValues fallback;

    public DialogueSugarValues() {
    }

    /** Point this set at the one to consult for a modifier it does not carry. */
    void fallbackTo(@Nullable DialogueSugarValues outer) {
        this.fallback = outer == this ? null : outer;
    }

    /** Record one decoded leaf/modifier value. A null value is a no-op, so an absent key stays absent. */
    void put(@Nonnull String key, @Nullable Object value) {
        if (value == null) {
            return;
        }
        if (values == null) {
            values = new LinkedHashMap<>();
        }
        values.put(key, value);
    }

    /** The decoded value authored under {@code key} here, ignoring any fallback. */
    @Nullable
    Object own(@Nonnull String key) {
        return values == null ? null : values.get(key);
    }

    /**
     * The decoded value authored under {@code key}, or null when nothing authored it. A {@code Do}
     * atom falls back to the option around it, so a modifier written once beside the array applies
     * to what is inside.
     */
    @Nullable
    public Object raw(@Nonnull String key) {
        Object here = own(key);
        return here != null || fallback == null ? here : fallback.own(key);
    }

    /** The value under {@code key} as a string, or null when absent or another type. */
    @Nullable
    public String string(@Nonnull String key) {
        Object value = raw(key);
        return value instanceof String s ? s : null;
    }

    /** The value under {@code key} as a boolean, or {@code fallback} when absent or another type. */
    public boolean flag(@Nonnull String key, boolean fallback) {
        Object value = raw(key);
        return value instanceof Boolean b ? b : fallback;
    }

    /** True when nothing was authored here. */
    public boolean isEmpty() {
        return values == null || values.isEmpty();
    }

    /** The authored keys, in the order the codec filled them. */
    @Nonnull
    public Set<String> keys() {
        return values == null ? Collections.emptySet() : Collections.unmodifiableSet(values.keySet());
    }
}
