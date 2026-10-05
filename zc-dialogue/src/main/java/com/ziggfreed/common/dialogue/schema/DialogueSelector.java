package com.ziggfreed.common.dialogue.schema;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Which conversations a dialogue extension's lines go into, written on its {@code Dialogues} leaf:
 * {@code Ids} names conversations by id, {@code Exclude} takes named ones back out.
 *
 * <pre>{@code
 * "Dialogues": { "Ids": ["Camp_Guide"] }          only these
 * "Dialogues": { "Exclude": ["Round_Lobby"] }     every conversation but these
 * }</pre>
 *
 * <p>Unlike a screen selector, naming no {@code Ids} selects EVERY conversation: reaching every
 * character is what an extension is for, so it is the unauthored answer and {@code Ids} narrows it.
 * {@code Exclude} always wins. Ids are matched without regard to case, like every id in the family.
 */
public final class DialogueSelector {

    public static final BuilderCodec<DialogueSelector> CODEC =
            BuilderCodec.builder(DialogueSelector.class, DialogueSelector::new)
                    .appendInherited(new KeyedCodec<>("Ids", Codec.STRING_ARRAY, false),
                            (s, v) -> s.ids = v, s -> s.ids, (child, parent) -> child.ids = parent.ids)
                    .documentation("The conversations, by id, that get the lines. Leave it out for every "
                            + "conversation on the server.").add()
                    .appendInherited(new KeyedCodec<>("Exclude", Codec.STRING_ARRAY, false),
                            (s, v) -> s.exclude = v, s -> s.exclude,
                            (child, parent) -> child.exclude = parent.exclude)
                    .documentation("Conversations, by id, that never get the lines, whatever Ids says.").add()
                    .build();

    @Nullable String[] ids;
    @Nullable String[] exclude;

    public DialogueSelector() {
    }

    /** Java-side construction (tests, a consumer building an extension in code). */
    @Nonnull
    public static DialogueSelector of(@Nullable String[] ids, @Nullable String[] exclude) {
        DialogueSelector selector = new DialogueSelector();
        selector.ids = ids;
        selector.exclude = exclude;
        return selector;
    }

    /** The conversations named, non-blank, in the order written. Empty means every conversation. */
    @Nonnull
    public List<String> getIds() {
        return named(ids);
    }

    /** The conversations taken back out, non-blank, in the order written. */
    @Nonnull
    public List<String> getExclude() {
        return named(exclude);
    }

    /** Whether the conversation {@code dialogueId} gets the lines. */
    public boolean selects(@Nonnull String dialogueId) {
        if (NodeSelector.containsIgnoreCase(getExclude(), dialogueId)) {
            return false;
        }
        List<String> named = getIds();
        return named.isEmpty() || NodeSelector.containsIgnoreCase(named, dialogueId);
    }

    @Nonnull
    private static List<String> named(@Nullable String[] values) {
        if (values == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(values.length);
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }
}
