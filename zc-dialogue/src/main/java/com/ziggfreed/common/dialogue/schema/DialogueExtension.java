package com.ziggfreed.common.dialogue.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One dialogue extension as the engine uses it: the lines it adds and where they land.
 *
 * <p>This is the one place a file puts lines into conversations it did not write, the deliberate
 * exception to the pull-only shared groups ({@link DialogueFragmentConfig}). A line lands on a screen
 * when the extension is enabled, its {@code Dialogues} selects the conversation, and its {@code On}
 * selects the screen; with no {@code On} it lands on the screens the conversation opens on
 * ({@link NpcDialogue#openingScreens()}).
 *
 * <p>Its lines are marked copies ({@link DialogueOption#getInjectedBy()}), so a line knows which
 * extension it came from: its {@code Once} keys by the extension, not by the screen it shows on, and
 * the audit reads it once, against its extension, instead of once per conversation it reaches.
 */
public final class DialogueExtension {

    @Nonnull private final String id;
    @Nonnull private final List<DialogueOption> options;
    @Nullable private final DialogueSelector dialogues;
    @Nullable private final NodeSelector on;
    private final boolean enabled;

    private DialogueExtension(@Nonnull String id, @Nonnull List<DialogueOption> options,
                              @Nullable DialogueSelector dialogues, @Nullable NodeSelector on,
                              boolean enabled) {
        this.id = id;
        this.options = options;
        this.dialogues = dialogues;
        this.on = on;
        this.enabled = enabled;
    }

    /** One extension, its id folded and its lines marked as its own. */
    @Nonnull
    public static DialogueExtension of(@Nonnull String id, @Nullable DialogueOption[] options,
                                       @Nullable DialogueSelector dialogues, @Nullable NodeSelector on,
                                       boolean enabled) {
        String folded = NodeSelector.fold(id);
        List<DialogueOption> lines = new ArrayList<>();
        if (options != null) {
            for (DialogueOption option : options) {
                if (option != null) {
                    lines.add(option.injectedCopy(folded));
                }
            }
        }
        return new DialogueExtension(folded, List.copyOf(lines), dialogues, on, enabled);
    }

    /** The extension's id: its file name, lower-cased. */
    @Nonnull
    public String getId() {
        return id;
    }

    /** The lines it adds, in order, each marked with this extension's id. */
    @Nonnull
    public List<DialogueOption> getOptions() {
        return options;
    }

    /** Which conversations it reaches, or null for every one. */
    @Nullable
    public DialogueSelector getDialogues() {
        return dialogues;
    }

    /** Which screens it reaches, or null for the screens each conversation opens on. */
    @Nullable
    public NodeSelector getOn() {
        return on;
    }

    /** In circulation? */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether this extension's lines land on the screen {@code nodeId} (carrying {@code nodeTags}) of
     * the conversation {@code dialogueId}; {@code opening} says whether that screen is one the
     * conversation opens on.
     */
    public boolean lands(@Nonnull String dialogueId, @Nonnull String nodeId,
                         @Nonnull Collection<String> nodeTags, boolean opening) {
        if (!enabled || options.isEmpty()) {
            return false;
        }
        if (dialogues != null && !dialogues.selects(dialogueId)) {
            return false;
        }
        return on == null ? opening : on.selects(nodeId, nodeTags);
    }
}
