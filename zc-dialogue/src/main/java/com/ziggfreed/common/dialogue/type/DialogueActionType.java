package com.ziggfreed.common.dialogue.type;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;

import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.schema.DialogueSugarLeaf;
import com.ziggfreed.common.dialogue.style.DialogueOptionStyle;

/**
 * A registrable dialogue action type, bundling everything a {@link DialogueEngine}
 * needs so schema + behavior + presentation cannot drift: the {@code Type}
 * discriminator, the action class, its field codec, the executor
 * {@link DialogueActionHandler}, an optional {@link DialogueOptionStyle} (the
 * decisive look an option gets when it carries this action), and an optional
 * {@link DialogueSugarLeaf}. A consumer registers one of these per action and
 * the engine wires it into the dispatch codec, the handler map, the style
 * classifier, and the sugar pass.
 *
 * <p>A {@link DialogueEngine.Builder} sandbox is keyed by {@link #typeId}, and a
 * later {@code action(...)} for an id it already holds replaces it - which is what
 * lets a test stand up a deliberately different vocabulary. The server's shared
 * engine is keyed by {@link #actionClass} instead and is first-wins, so a second
 * contributor for a class another mod already registered is refused and reported
 * rather than quietly taking over what that mod's options do.
 */
public final class DialogueActionType<A extends DialogueAction> {

    private final String typeId;
    private final Class<A> actionClass;
    private final Codec<A> codec;
    private final DialogueActionHandler<A> handler;
    @Nullable private final DialogueOptionStyle style;
    @Nullable private final DialogueSugarLeaf<?> sugar;

    private DialogueActionType(@Nonnull String typeId, @Nonnull Class<A> actionClass,
                              @Nonnull Codec<A> codec, @Nonnull DialogueActionHandler<A> handler,
                              @Nullable DialogueOptionStyle style, @Nullable DialogueSugarLeaf<?> sugar) {
        this.typeId = typeId;
        this.actionClass = actionClass;
        this.codec = codec;
        this.handler = handler;
        this.style = style;
        this.sugar = sugar;
    }

    @Nonnull
    public static <A extends DialogueAction> DialogueActionType<A> of(
            @Nonnull String typeId, @Nonnull Class<A> actionClass,
            @Nonnull Codec<A> codec, @Nonnull DialogueActionHandler<A> handler) {
        return new DialogueActionType<>(typeId, actionClass, codec, handler, null, null);
    }

    /** A copy that also gives an option carrying this action the given decisive look. */
    @Nonnull
    public DialogueActionType<A> withStyle(@Nonnull DialogueOptionStyle style) {
        return new DialogueActionType<>(typeId, actionClass, codec, handler, style, sugar);
    }

    /** A copy that also registers the option-level shorthand this action is written as. */
    @Nonnull
    public DialogueActionType<A> withSugar(@Nonnull DialogueSugarLeaf<?> sugar) {
        return new DialogueActionType<>(typeId, actionClass, codec, handler, style, sugar);
    }

    @Nonnull public String typeId() { return typeId; }

    @Nonnull public Class<A> actionClass() { return actionClass; }

    @Nonnull public Codec<A> codec() { return codec; }

    @Nonnull public DialogueActionHandler<A> handler() { return handler; }

    @Nullable public DialogueOptionStyle style() { return style; }

    @Nullable public DialogueSugarLeaf<?> sugar() { return sugar; }
}
