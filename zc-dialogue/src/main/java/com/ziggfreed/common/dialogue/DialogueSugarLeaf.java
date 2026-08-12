package com.ziggfreed.common.dialogue;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;

/**
 * Option-level authoring shorthand as a SCHEMA LEAF: one flat key an author writes on an option
 * ({@code "Goto": "next"}) instead of the verbose canonical {@code Actions} entry, declared with
 * the value codec that decodes it and the factory that turns the decoded value into the canonical
 * {@link DialogueAction}.
 *
 * <p>Registered alongside an action type ({@link DialogueActionType#withSugar}), so the shorthand
 * and the action it stands for cannot drift apart. Every registered leaf becomes a real keyed field
 * on the option codec and on the {@code Do} atom codec, which is what makes an authoring mistake a
 * decode error naming the key rather than a silently ignored line.
 *
 * <p>Bare keys are folded in {@link #order()} (low first); a {@code Do} array is the explicit-order
 * escape hatch, whose atoms are folded in array order.
 *
 * @param <V> the authored value type ({@code String} for the common shape, a structured type for a
 *            richer one such as a reward)
 */
public abstract class DialogueSugarLeaf<V> {

    private final String key;
    private final int order;
    private final Codec<V> codec;

    protected DialogueSugarLeaf(@Nonnull String key, int order, @Nonnull Codec<V> codec) {
        this.key = key;
        this.order = order;
        this.codec = codec;
    }

    /** The PascalCase sugar key this leaf owns (e.g. {@code "Goto"}, {@code "Accept"}). */
    @Nonnull
    public final String key() {
        return key;
    }

    /** Fixed bare-key fold order; lower runs first. */
    public final int order() {
        return order;
    }

    /** The codec that decodes the authored value of {@link #key()}. */
    @Nonnull
    public final Codec<V> codec() {
        return codec;
    }

    /**
     * Extra keys this leaf READS beside its own, each with the codec that decodes it - the way
     * {@code Reward} reads {@code RewardOnce}. They become keyed fields alongside the primary, and
     * their decoded values reach {@link #toAction} in the same value set. Empty by default.
     *
     * <p>Two leaves must not claim the same modifier key, and a modifier must never be the primary
     * key of another leaf: the table keeps the first registration and warns on the clash.
     */
    @Nonnull
    public Map<String, Codec<?>> modifiers() {
        return Map.of();
    }

    /**
     * The canonical action this shorthand stands for, or null to emit nothing (a value that reads
     * as "off", such as {@code "Close": false}).
     *
     * @param value  the decoded value authored under {@link #key()}, never null
     * @param values every sugar value authored on the same option or {@code Do} atom, so a
     *               declared {@linkplain #modifiers() modifier} can be read
     */
    @Nullable
    public abstract DialogueAction toAction(@Nonnull V value, @Nonnull DialogueSugarValues values);
}
