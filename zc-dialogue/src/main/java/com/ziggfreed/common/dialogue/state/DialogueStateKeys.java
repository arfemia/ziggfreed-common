package com.ziggfreed.common.dialogue.state;

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
 * <p>Three modifiers wrap those base shapes, and the ORDER they wrap in is load-bearing:
 * <ul>
 *   <li>a memory declared {@code Session} PREFIXES {@code ses:} onto the whole key. It is the
 *       OUTERMOST wrap, because it is what {@link DialogueMemories} reads to decide which of the
 *       two backends the key belongs to, and a router that had to parse past another namespace to
 *       find out would be a router that could get it wrong;</li>
 *   <li>a memory's {@code ResetWithQuest} PREFIXES {@code q:<questId>:} onto the rest, so clearing
 *       a quest's state by a leading-prefix match ({@code q:<questId>:*}) clears the memory with
 *       it. A key that is BOTH reads {@code ses:q:<questId>:...}, which is why the quest clear
 *       asks for the un-prefixed form and lets the router apply the session namespace itself;</li>
 *   <li>a {@code World} scope wraps only the FINAL segment (see
 *       {@link DialogueFlagScope#scopedKey}), so it lands INSIDE both prefixes and can never
 *       escape either clear.</li>
 * </ul>
 *
 * <p>A {@code Once} key carries no lifetime prefix at all, which is the same statement as
 * "unauthored means persistent": there is no {@code Memories} declaration behind a {@code Once} to
 * read a lifetime from, and a first-visit beat that came back after a restart is exactly the thing
 * the persistent default exists to prevent.
 *
 * <p>Every id/name is trimmed + lower-cased so authoring case can never split one piece of state
 * into two, and any {@code :} inside a name is folded to {@code .} so a name can never invent an
 * extra segment.
 */
public final class DialogueStateKeys {

    private static final char SEP = DialogueFlagScope.SEPARATOR;

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

    /**
     * The namespace EVERY quest-scoped key is filed under, whichever quest owns it: the leading
     * piece {@link #questPrefix} builds each individual quest's prefix on top of.
     *
     * <p>Clearing THIS reaches every memory some quest owns and nothing else, because no other key
     * shape here begins with it: a {@code Once} opens {@code once:}, a memory that no quest owns
     * opens {@code mem:}, and a session key opens {@code ses:} (whose quest-scoped half is reached
     * by wrapping this the same way {@link #withSession} wraps anything else). That is what an
     * administrator resetting a player's QUESTS needs and what forgetting every memory would
     * overreach: a greeting a conversation remembers is not quest data, and no quest reset should
     * take it.
     */
    public static final String QUEST_NAMESPACE = QUEST_PREFIX + SEP;

    /**
     * The namespace a memory declared {@code Session} is filed under, and the one thing
     * {@link DialogueMemories} reads to route a key to the session backend rather than the
     * persistent one. It wraps everything else, so it is a plain {@code startsWith} test.
     */
    public static final String SESSION_PREFIX = "ses";

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

    /**
     * The leading prefix everything belonging to {@code questId} is filed under, ending in the
     * separator so the id segment is EXACT: clearing {@code q1} can never reach {@code q:q10:...}.
     * The id is normalized the same way the key writer normalizes it, or a clear would look for a
     * spelling nothing was ever written under.
     *
     * <p>It is built ON {@link #QUEST_NAMESPACE}, so the clear for one quest and the clear for every
     * quest at once cannot come to disagree about where a quest's state lives.
     */
    @Nonnull
    public static String questPrefix(@Nonnull String questId) {
        return QUEST_NAMESPACE + segment(questId) + SEP;
    }

    /**
     * Prefix {@code key} with {@code ses:} when its declaration says it lives only as long as this
     * session. Unauthored means persistent, so a false here returns {@code key} unchanged.
     */
    @Nonnull
    public static String withSession(boolean session, @Nonnull String key) {
        return session ? SESSION_PREFIX + SEP + key : key;
    }

    /** True when {@code key} belongs to the session backend rather than the persistent one. */
    public static boolean isSession(@Nonnull String key) {
        return key.startsWith(SESSION_PREFIX + SEP);
    }

    /** Trim + lower-case a key piece, folding any separator inside it so it cannot add a segment. */
    @Nonnull
    private static String segment(@Nonnull String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replace(SEP, '.');
    }
}
