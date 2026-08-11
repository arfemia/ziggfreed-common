package com.ziggfreed.common.dialogue;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The INTERNAL storage-key encoding behind {@code Once} and {@code Memories}. No author ever
 * writes, reads, or sees one of these keys: they are what the engine hands the consumer's
 * {@link DialogueFlagStore}, and they exist as one class so every producer of a key agrees on the
 * format.
 *
 * <p>The shapes:
 * <pre>{@code
 * once:e:<dialogueId>:<nodeId>                        an entry-level Once (a first-visit beat)
 * once:o:<dialogueId>:<nodeId>:<labelKey|OnceId>      an option-level Once
 * mem:d:<dialogueId>:<name>                           a memory declared by one dialogue
 * mem:s:<name>                                        a memory declared Shared across dialogues
 * }</pre>
 *
 * <p>Two modifiers wrap those base shapes, and the ORDER they wrap in is load-bearing:
 * <ul>
 *   <li>a memory's {@code ResetWithQuest} PREFIXES {@code q:<questId>:} onto the whole key, so a
 *       consumer that clears a quest's state by a leading-prefix match ({@code q:<questId>:*})
 *       clears the memory with it;</li>
 *   <li>a {@code WorldSelector} scope wraps only the FINAL segment (see
 *       {@link DialogueFlagScope#scopedKey}), so it lands INSIDE any quest prefix and can never
 *       escape that clear.</li>
 * </ul>
 *
 * <p>Every id/name is trimmed + lower-cased so authoring case can never split one piece of state
 * into two, and any {@code :} inside a name is folded to {@code .} so a name can never invent an
 * extra segment.
 */
public final class DialogueStateKeys {

    /** The entry-level {@code Once} namespace. */
    public static final String ONCE_ENTRY_PREFIX = "once:e";

    /** The option-level {@code Once} namespace. */
    public static final String ONCE_OPTION_PREFIX = "once:o";

    /** The namespace of a memory declared by (and private to) one dialogue. */
    public static final String MEMORY_DIALOGUE_PREFIX = "mem:d";

    /** The namespace of a memory declared {@code Shared}, visible to every dialogue declaring it. */
    public static final String MEMORY_SHARED_PREFIX = "mem:s";

    /** The quest namespace a {@code ResetWithQuest} memory is filed under. */
    public static final String QUEST_PREFIX = "q";

    private static final char SEP = DialogueFlagScope.SEPARATOR;

    private DialogueStateKeys() {
    }

    /** The unscoped key for an entry-level {@code Once} on {@code nodeId}. */
    @Nonnull
    public static String entryOnce(@Nonnull String dialogueId, @Nonnull String nodeId) {
        return ONCE_ENTRY_PREFIX + SEP + segment(dialogueId) + SEP + segment(nodeId);
    }

    /**
     * The unscoped key for an option-level {@code Once}. The discriminator is the option's
     * {@code OnceId} when authored, else its {@code LabelKey} - never its index, so reordering a
     * node's options can never resurrect a spent Once or spend a fresh one.
     */
    @Nonnull
    public static String optionOnce(@Nonnull String dialogueId, @Nonnull String nodeId,
                                    @Nonnull String discriminator) {
        return ONCE_OPTION_PREFIX + SEP + segment(dialogueId) + SEP + segment(nodeId)
                + SEP + segment(discriminator);
    }

    /** The unscoped, un-prefixed key for memory {@code name} (shared or dialogue-private). */
    @Nonnull
    public static String memory(@Nonnull String dialogueId, @Nonnull String name, boolean shared) {
        return shared
                ? MEMORY_SHARED_PREFIX + SEP + segment(name)
                : MEMORY_DIALOGUE_PREFIX + SEP + segment(dialogueId) + SEP + segment(name);
    }

    /**
     * Prefix {@code key} with {@code q:<questId>:} when a quest owns its lifetime, so a
     * prefix-match quest reset clears it. A null/blank quest id returns {@code key} unchanged.
     */
    @Nonnull
    public static String withQuest(@Nullable String questId, @Nonnull String key) {
        if (questId == null || questId.isBlank()) {
            return key;
        }
        return QUEST_PREFIX + SEP + segment(questId) + SEP + key;
    }

    /** Trim + lower-case a key piece, folding any separator inside it so it cannot add a segment. */
    @Nonnull
    private static String segment(@Nonnull String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replace(SEP, '.');
    }
}
