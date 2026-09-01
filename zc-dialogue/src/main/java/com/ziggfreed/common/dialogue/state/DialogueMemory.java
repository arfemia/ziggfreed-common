package com.ziggfreed.common.dialogue.state;

import java.util.Locale;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.world.WorldSelector;

/**
 * One entry of a dialogue's top-level {@code Memories} map: a NAMED thing this conversation can
 * remember about the player, declared once with its scope and lifetime and then referred to by
 * bare name everywhere else.
 *
 * <pre>{@code
 * "Memories": {
 *   "helped_refugees": { "Where": { "Match": ["emerald_wilds"] }, "ResetWithQuest": "guide_trust" },
 *   "greeted_below":   { "Where": { "GameplayConfig": ["ForgottenTemple"] } },
 *   "heard_the_rules": { "Session": true },
 *   "knows_my_name":   {}
 * }
 * }</pre>
 *
 * <p>Use sites never repeat any of this: the actions {@code Remember} / {@code Forget} (option
 * sugar {@code "Remember": "helped_refugees"}) and the conditions {@code Remembered} /
 * {@code NotRemembered} all take the bare name.
 *
 * <p>Every leaf is nullable and independent - declare only the axes that matter:
 * <ul>
 *   <li><b>{@code Where}</b> - remember it per world instead of per character, using the shared
 *       world selector every other world-targeting field carries. {@code GameplayConfig} is the one
 *       to reach for with an instance world: it is authored, carries no uuid, and survives the
 *       instance being destroyed and re-created, so the memory is not forgotten each visit. A
 *       {@code Match} pattern files the memory under the pattern's literal core. In a world the
 *       selector does not match the memory reads as forgotten and writes are dropped, so pair it
 *       with a {@code World} condition where reaching the beat elsewhere would be odd.</li>
 *   <li><b>{@code ResetWithQuest}</b> - tie the memory's lifetime to a quest: it is filed inside
 *       that quest's own state, so resetting the quest forgets it too. Use this for anything the
 *       player should be able to experience again after a quest reset.</li>
 *   <li><b>{@code Shared}</b> - make it visible to every dialogue that declares it, rather than
 *       private to this one. Declare it identically in each dialogue that touches it; mismatched
 *       declarations are a validation error, because two dialogues would otherwise disagree about
 *       what the same name means.</li>
 *   <li><b>{@code Session}</b> - keep it only for as long as the player is connected, instead of
 *       for good. Reach for it where the memory belongs to something that ends: a round, a visit, a
 *       run. Leave it out and the memory survives a restart, which is the safe default - a memory
 *       that outlives its moment is a cosmetic surprise, while one that silently vanishes breaks an
 *       authored {@code ResetWithQuest} chain with nothing to say so.</li>
 * </ul>
 *
 * <p>The {@code Memories} map decodes through the per-key merging map codec, so a dialogue with a
 * {@code Parent} adds or re-declares ONE memory and inherits the rest.
 */
public final class DialogueMemory {

    /** The declaration used for an undeclared name at runtime: private to the dialogue, no scope. */
    public static final DialogueMemory DEFAULT = new DialogueMemory();

    public static final BuilderCodec<DialogueMemory> CODEC =
            BuilderCodec.builder(DialogueMemory.class, DialogueMemory::new)
                    .appendInherited(new KeyedCodec<>("Where", WorldSelector.CODEC, false),
                            (m, v) -> { m.where = v; m.scope = null; }, m -> m.where,
                            (child, parent) -> child.where = parent.where)
                    .documentation(DialogueFlagScope.WHERE_DOC)
                    .add()
                    .append(new KeyedCodec<>("World", DialogueFlagScope.RETIRED_WORLD_LEAF, false),
                            (m, v) -> { /* never decoded: the leaf refuses and says what to write */ },
                            m -> null)
                    .documentation("Retired. Write Where instead.")
                    .add()
                    .appendInherited(new KeyedCodec<>("ResetWithQuest", Codec.STRING, false),
                            (m, v) -> m.resetWithQuest = v, m -> m.resetWithQuest,
                            (child, parent) -> child.resetWithQuest = parent.resetWithQuest)
                    .documentation("Tie the memory's lifetime to a quest: it is filed inside that "
                            + "quest's own state, so resetting the quest forgets it too.")
                    .add()
                    .appendInherited(new KeyedCodec<>("Shared", Codec.BOOLEAN, false),
                            (m, v) -> m.shared = v, m -> m.shared,
                            (child, parent) -> child.shared = parent.shared)
                    .documentation("Make the memory visible to every dialogue that declares this name, "
                            + "rather than private to this one. Declare it identically in each.")
                    .add()
                    .appendInherited(new KeyedCodec<>("Session", Codec.BOOLEAN, false),
                            (m, v) -> m.session = v, m -> m.session,
                            (child, parent) -> child.session = parent.session)
                    .documentation("Keep the memory only for as long as the player is connected, "
                            + "instead of for good. For state that belongs to something that ends: a "
                            + "round, a visit, a run. Leave it out and it survives a restart.")
                    .add()
                    .build();

    @Nullable protected WorldSelector where;
    @Nullable protected String resetWithQuest;
    @Nullable protected Boolean shared;
    @Nullable protected Boolean session;

    @Nullable private volatile DialogueFlagScope scope;

    public DialogueMemory() {
    }

    /** Java-side construction (tests, a consumer declaring memories in code). */
    @Nonnull
    public static DialogueMemory of(@Nullable WorldSelector where, @Nullable String resetWithQuest,
                                    @Nullable Boolean shared) {
        return of(where, resetWithQuest, shared, null);
    }

    /** {@link #of(WorldSelector, String, Boolean)} with the declared lifetime too. */
    @Nonnull
    public static DialogueMemory of(@Nullable WorldSelector where, @Nullable String resetWithQuest,
                                    @Nullable Boolean shared, @Nullable Boolean session) {
        DialogueMemory memory = new DialogueMemory();
        memory.where = where;
        memory.resetWithQuest = resetWithQuest;
        memory.shared = shared;
        memory.session = session;
        return memory;
    }

    /** The worlds this memory is kept per, or null for one memory per character. */
    @Nullable
    public WorldSelector getWhere() {
        return where;
    }

    /** The quest whose reset also forgets this memory, or null when it is permanent. */
    @Nullable
    public String getResetWithQuest() {
        return resetWithQuest;
    }

    /** True when every dialogue declaring this name sees the same memory. */
    public boolean isShared() {
        return Boolean.TRUE.equals(shared);
    }

    /** True when this memory lives only as long as the player's session. */
    public boolean isSession() {
        return Boolean.TRUE.equals(session);
    }

    /**
     * True when {@code other} declares the same name the same way. Two dialogues sharing a memory
     * must agree on all four axes or they are silently talking about different state - the lifetime
     * included, since a name declared {@code Session} in one file and persistent in another is one
     * word naming two pieces of state in two different places.
     */
    public boolean sameDeclarationAs(@Nonnull DialogueMemory other) {
        return isShared() == other.isShared()
                && isSession() == other.isSession()
                && DialogueFlagScope.sameSelector(where, other.where)
                && Objects.equals(normalized(resetWithQuest), normalized(other.resetWithQuest));
    }

    /**
     * The storage key this memory resolves to for the player's CURRENT world, or null when it is
     * kept per world and this world is not one its pattern matches (reads are "forgotten", writes
     * drop).
     *
     * <p>Public for {@link com.ziggfreed.common.dialogue.DialogueEngine}, which resolves every
     * declared memory through it.
     */
    @Nullable
    public String keyFor(@Nonnull String dialogueId, @Nonnull String name, @Nonnull DialogueContext ctx) {
        return DialogueFlagScope.keyFor(scope(), baseKey(dialogueId, name), ctx);
    }

    /**
     * The PURE resolver behind {@link #keyFor}: the key in the world named {@code worldName}, or
     * null when this memory's selector does not match it.
     */
    @Nullable
    public String resolveKey(@Nonnull String dialogueId, @Nonnull String name,
                             @Nullable String worldName) {
        return resolveKey(dialogueId, name, worldName, null);
    }

    /** {@link #resolveKey(String, String, String)} with the world's gameplay config too. */
    @Nullable
    public String resolveKey(@Nonnull String dialogueId, @Nonnull String name,
                             @Nullable String worldName, @Nullable String worldGameplayConfig) {
        return DialogueFlagScope.resolve(scope(), baseKey(dialogueId, name), worldName,
                worldGameplayConfig);
    }

    /**
     * The key before any world scope: the namespace, an optional owning-quest prefix, and the
     * lifetime namespace outside both (which is what {@code DialogueMemories} routes on).
     */
    @Nonnull
    private String baseKey(@Nonnull String dialogueId, @Nonnull String name) {
        return DialogueStateKeys.withSession(isSession(),
                DialogueStateKeys.withQuest(resetWithQuest,
                        DialogueStateKeys.memory(dialogueId, name, isShared())));
    }

    @Nonnull
    private DialogueFlagScope scope() {
        DialogueFlagScope cached = scope;
        if (cached == null) {
            cached = DialogueFlagScope.ofWhere(where);
            scope = cached;
        }
        return cached;
    }

    @Nullable
    private static String normalized(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toLowerCase(Locale.ROOT);
    }
}
