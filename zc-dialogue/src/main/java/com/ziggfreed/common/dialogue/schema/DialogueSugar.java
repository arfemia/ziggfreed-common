package com.ziggfreed.common.dialogue.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;

import com.ziggfreed.common.dialogue.type.DialogueAction;

/**
 * The option-level sugar FOLD: turns the decoded {@link DialogueSugarValues} on an option into the
 * canonical {@link DialogueAction} list, appended after whatever the option authored under
 * {@code Actions}.
 *
 * <p>It is a fold over decoded data, NOT a rewrite of raw JSON. Each registered
 * {@link DialogueSugarLeaf} owns a real keyed field on the option codec, so by the time this runs
 * the values are typed and an authoring mistake has already been reported by the codec that
 * expected something else. The DRIVER here (bare-key ordering, the {@code Do} escape hatch) is
 * generic; the TABLE of keys is whatever the consumers registered, which is how a consumer adds a
 * shorthand together with its action.
 *
 * <p><b>{@code Do} wins outright.</b> An option that authors a {@code Do} array folds only the
 * atoms, in array order, and its bare sugar keys are left alone - the array IS the author saying
 * "run these, in this order". Within one atom the leaves fold in {@link DialogueSugarLeaf#order()},
 * so an atom carrying two shorthands still has a defined order rather than a JSON-key-order one.
 *
 * <p>Built by {@link DialogueTypeTable} from the registered action types' leaves.
 */
public final class DialogueSugar {

    private final List<DialogueSugarLeaf<?>> ordered;

    DialogueSugar(@Nonnull Collection<DialogueSugarLeaf<?>> leaves) {
        Map<String, DialogueSugarLeaf<?>> keyed = new LinkedHashMap<>();
        for (DialogueSugarLeaf<?> leaf : leaves) {
            keyed.putIfAbsent(leaf.key(), leaf);
        }
        List<DialogueSugarLeaf<?>> sorted = new ArrayList<>(keyed.values());
        sorted.sort(Comparator.comparingInt(DialogueSugarLeaf::order));
        this.ordered = List.copyOf(sorted);
    }

    /** Every registered leaf, in fold order (the codec assembler walks this to build its fields). */
    @Nonnull
    public List<DialogueSugarLeaf<?>> leaves() {
        return ordered;
    }

    /**
     * The option's effective action list: everything it authored under {@code Actions}, then the
     * actions its shorthand stands for.
     *
     * @param authored the option's own {@code Actions}, or null
     * @param own      the sugar values authored directly on the option, or null
     * @param atoms    the option's {@code Do} atoms, or null; a non-empty array replaces {@code own}
     */
    @Nonnull
    List<DialogueAction> fold(@Nullable DialogueAction[] authored, @Nullable DialogueSugarValues own,
                              @Nullable DialogueSugarValues[] atoms) {
        List<DialogueAction> out = new ArrayList<>();
        if (authored != null) {
            for (DialogueAction action : authored) {
                if (action != null) {
                    out.add(action);
                }
            }
        }
        if (atoms != null && atoms.length > 0) {
            for (DialogueSugarValues atom : atoms) {
                if (atom != null) {
                    // Only MODIFIERS reach past the atom to the option around it; a shorthand written
                    // bare beside a Do array stays shadowed, which is what makes Do the whole story.
                    atom.fallbackTo(own);
                    foldOne(atom, out);
                }
            }
        } else if (own != null) {
            foldOne(own, out);
        }
        return List.copyOf(out);
    }

    private void foldOne(@Nonnull DialogueSugarValues values, @Nonnull List<DialogueAction> out) {
        for (DialogueSugarLeaf<?> leaf : ordered) {
            Object value = values.own(leaf.key());
            if (value == null) {
                continue;
            }
            DialogueAction action = build(leaf, value, values);
            if (action != null) {
                out.add(action);
            }
        }
    }

    /**
     * The one unchecked hop: the table stores leaves heterogeneously, and the value under a leaf's
     * key was decoded by that same leaf's codec, so the cast is sound by construction.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private static DialogueAction build(@Nonnull DialogueSugarLeaf<?> leaf, @Nonnull Object value,
                                        @Nonnull DialogueSugarValues values) {
        return ((DialogueSugarLeaf<Object>) leaf).toAction(value, values);
    }

    // ==================== reusable leaf factories ====================

    /**
     * A string-valued shorthand ({@code "Goto": "next"}, {@code "Accept": "intro"}) whose value maps
     * straight to one action. The factory may return null to emit nothing (e.g. a blank id).
     */
    @Nonnull
    public static DialogueSugarLeaf<String> string(@Nonnull String key, int order,
                                                   @Nonnull Function<String, DialogueAction> factory) {
        return new DialogueSugarLeaf<>(key, order, Codec.STRING) {
            @Override
            @Nullable
            public DialogueAction toAction(@Nonnull String value, @Nonnull DialogueSugarValues values) {
                return factory.apply(value);
            }
        };
    }

    /**
     * A boolean-valued shorthand ({@code "Close": true}) that emits its action only when true, so
     * authoring {@code false} reads as "not this one" rather than as an error.
     */
    @Nonnull
    public static DialogueSugarLeaf<Boolean> flag(@Nonnull String key, int order,
                                                  @Nonnull Supplier<DialogueAction> factory) {
        return new DialogueSugarLeaf<>(key, order, Codec.BOOLEAN) {
            @Override
            @Nullable
            public DialogueAction toAction(@Nonnull Boolean value, @Nonnull DialogueSugarValues values) {
                return value ? factory.get() : null;
            }
        };
    }

    /**
     * A shorthand with its own value shape, and optionally its own MODIFIER keys - the general form
     * the two convenience factories above are special cases of.
     */
    @Nonnull
    public static <V> DialogueSugarLeaf<V> of(@Nonnull String key, int order, @Nonnull Codec<V> codec,
                                              @Nonnull Map<String, Codec<?>> modifiers,
                                              @Nonnull BiFunction<V, DialogueSugarValues, DialogueAction> factory) {
        Map<String, Codec<?>> declared = Map.copyOf(modifiers);
        return new DialogueSugarLeaf<>(key, order, codec) {
            @Override
            @Nonnull
            public Map<String, Codec<?>> modifiers() {
                return declared;
            }

            @Override
            @Nullable
            public DialogueAction toAction(@Nonnull V value, @Nonnull DialogueSugarValues values) {
                return factory.apply(value, values);
            }
        };
    }

    /** {@link #of(String, int, Codec, Map, BiFunction)} with no modifier keys. */
    @Nonnull
    public static <V> DialogueSugarLeaf<V> of(@Nonnull String key, int order, @Nonnull Codec<V> codec,
                                              @Nonnull BiFunction<V, DialogueSugarValues, DialogueAction> factory) {
        return of(key, order, codec, Map.of(), factory);
    }

    /** The {@code Close} shorthand: {@code true} ends the conversation. */
    @Nonnull
    public static DialogueSugarLeaf<Boolean> close(@Nonnull String key, int order) {
        return flag(key, order, DialogueAction.Close::new);
    }
}
