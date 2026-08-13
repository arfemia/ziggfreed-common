package com.ziggfreed.common.dialogue;

import java.util.Locale;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * One entry of a dialogue's top-level {@code Memories} map: a NAMED thing this conversation can
 * remember about the player, declared once with its scope and lifetime and then referred to by
 * bare name everywhere else.
 *
 * <pre>{@code
 * "Memories": {
 *   "helped_refugees": { "World": "emerald_wilds", "ResetWithQuest": "guide_trust" },
 *   "knows_my_name": {}
 * }
 * }</pre>
 *
 * <p>Use sites never repeat any of this: the actions {@code Remember} / {@code Forget} (option
 * sugar {@code "Remember": "helped_refugees"}) and the conditions {@code Remembered} /
 * {@code NotRemembered} all take the bare name.
 *
 * <p>Every leaf is nullable and independent - declare only the axes that matter:
 * <ul>
 *   <li><b>{@code World}</b> - remember it per world instead of per character. The value is a
 *       world NAME or a pattern ({@code Foo*}, {@code *Foo}, {@code *Foo*}), and the pattern is
 *       the exact-versus-family dial: {@code *KweebecNightmare*} files the memory under the stable
 *       core {@code KweebecNightmare}, so it survives an instance world being destroyed and
 *       re-created under a fresh name. In a world the pattern does not match the memory reads as
 *       forgotten and writes are dropped, so pair it with a {@code World} condition where reaching
 *       the beat elsewhere would be odd.</li>
 *   <li><b>{@code ResetWithQuest}</b> - tie the memory's lifetime to a quest: it is filed inside
 *       that quest's own state, so resetting the quest forgets it too. Use this for anything the
 *       player should be able to experience again after a quest reset.</li>
 *   <li><b>{@code Shared}</b> - make it visible to every dialogue that declares it, rather than
 *       private to this one. Declare it identically in each dialogue that touches it; mismatched
 *       declarations are a validation error, because two dialogues would otherwise disagree about
 *       what the same name means.</li>
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
                    .appendInherited(new KeyedCodec<>("World", Codec.STRING, false),
                            (m, v) -> { m.world = v; m.scope = null; }, m -> m.world,
                            (child, parent) -> child.world = parent.world)
                    .documentation("Remember this per world: an exact world name, or a pattern - Foo* "
                            + "(prefix), *Foo (suffix), *Foo* (contains). Use the contains form for a "
                            + "family of instance worlds. Leave it out for one memory per character.")
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
                    .build();

    @Nullable protected String world;
    @Nullable protected String resetWithQuest;
    @Nullable protected Boolean shared;

    @Nullable private volatile DialogueFlagScope scope;

    public DialogueMemory() {
    }

    /** Java-side construction (tests, a consumer declaring memories in code). */
    @Nonnull
    public static DialogueMemory of(@Nullable String world, @Nullable String resetWithQuest,
                                    @Nullable Boolean shared) {
        DialogueMemory memory = new DialogueMemory();
        memory.world = world;
        memory.resetWithQuest = resetWithQuest;
        memory.shared = shared;
        return memory;
    }

    /** The world name or pattern this memory is kept per, or null for one memory per character. */
    @Nullable
    public String getWorld() {
        return world;
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

    /**
     * True when {@code other} declares the same name the same way. Two dialogues sharing a memory
     * must agree on all three axes or they are silently talking about different state.
     */
    public boolean sameDeclarationAs(@Nonnull DialogueMemory other) {
        return isShared() == other.isShared()
                && Objects.equals(normalized(world), normalized(other.world))
                && Objects.equals(normalized(resetWithQuest), normalized(other.resetWithQuest));
    }

    /**
     * The storage key this memory resolves to for the player's CURRENT world, or null when it is
     * kept per world and this world is not one its pattern matches (reads are "forgotten", writes
     * drop).
     */
    @Nullable
    String keyFor(@Nonnull String dialogueId, @Nonnull String name, @Nonnull DialogueContext ctx) {
        return DialogueFlagScope.keyFor(scope(), baseKey(dialogueId, name), ctx);
    }

    /**
     * The PURE resolver behind {@link #keyFor}: the key in the world named {@code worldName}, or
     * null when this memory's pattern does not match it.
     */
    @Nullable
    public String resolveKey(@Nonnull String dialogueId, @Nonnull String name,
                             @Nullable String worldName) {
        return DialogueFlagScope.resolve(scope(), baseKey(dialogueId, name), worldName);
    }

    /** The key before any world scope: the namespace plus an optional owning-quest prefix. */
    @Nonnull
    private String baseKey(@Nonnull String dialogueId, @Nonnull String name) {
        return DialogueStateKeys.withQuest(resetWithQuest,
                DialogueStateKeys.memory(dialogueId, name, isShared()));
    }

    @Nonnull
    private DialogueFlagScope scope() {
        DialogueFlagScope cached = scope;
        if (cached == null) {
            cached = DialogueFlagScope.ofWorld(world);
            scope = cached;
        }
        return cached;
    }

    @Nullable
    private static String normalized(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toLowerCase(Locale.ROOT);
    }
}
