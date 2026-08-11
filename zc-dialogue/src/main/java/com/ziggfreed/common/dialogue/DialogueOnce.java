package com.ziggfreed.common.dialogue;

import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The {@code Once} knob: keyless seen-ness, authored on a {@code Start} entry or on an option.
 *
 * <pre>{@code
 * "Once": true                                       once per character
 * "Once": { "WorldSelector": "forgotten_temple" }    once per world FAMILY
 * }</pre>
 *
 * <p>Both forms are the same group - {@code true} is shorthand for the empty group {@code {}} and
 * is normalized by the sugar pre-pass, so the codec only ever sees an object. Every leaf is
 * nullable and independent: a future axis is a new leaf, never a mode.
 *
 * <h2>What it does</h2>
 *
 * <p>On a {@code Start} entry, the entry stops matching once the player COMPLETES that beat -
 * chooses any option on the node it routed to, the implicit Farewell row included. Leaving with
 * Escape or the close button does not complete it, so an interrupted first-visit beat shows again.
 *
 * <p>On an option, the option is offered until its actions have run once. Its identity comes from
 * the option's {@code LabelKey} (or an explicit {@code OnceId}), never its position, so reordering
 * a node's options never resurrects a spent Once.
 *
 * <h2>{@code WorldSelector}</h2>
 *
 * <p>A selector NAME from the world-identity vocabulary ({@code Server/ZiggfreedCommon/
 * WorldSelectors/*.json}), never a raw world name. A dynamically created instance world is named
 * {@code instance-Forgotten_Temple-<uuid>} and is destroyed when it empties, so "already seen
 * here" keyed by the world's NAME would come back on every fresh instance; keyed by the selector
 * name it survives the instance being torn down and re-created.
 *
 * <p>In a world that does not carry the named selector, the Once neither reads nor writes: the
 * beat is offered and stays offered. Pair a world-scoped {@code Once} with a {@code World}
 * condition on the same entry so the beat cannot be reached elsewhere in the first place. A
 * selector name nothing in the loaded vocabulary contributes warns once per name and is a
 * validator finding, because a typo would otherwise re-show a first-visit beat forever.
 */
public final class DialogueOnce {

    /** The canonical "once per character" group, the decoded form of {@code "Once": true}. */
    public static final DialogueOnce GLOBAL = new DialogueOnce();

    public static final BuilderCodec<DialogueOnce> CODEC =
            BuilderCodec.builder(DialogueOnce.class, DialogueOnce::new)
                    .append(new KeyedCodec<>("WorldSelector", Codec.STRING, false),
                            (o, v) -> { o.worldSelector = v; o.scope = null; }, o -> o.worldSelector).add()
                    .build();

    @Nullable protected String worldSelector;

    /** The internal scope carrier; built lazily, dropped by the setter so it cannot go stale. */
    @Nullable private volatile DialogueFlagScope scope;

    public DialogueOnce() {
    }

    /** Java-side construction (tests, a consumer building a tree in code). */
    @Nonnull
    public static DialogueOnce ofWorldSelector(@Nullable String selectorName) {
        DialogueOnce once = new DialogueOnce();
        once.worldSelector = selectorName;
        return once;
    }

    /** The world-selector name this Once is remembered per, or null for once per character. */
    @Nullable
    public String getWorldSelector() {
        return worldSelector;
    }

    /**
     * The storage key {@code rawKey} resolves to for the player's CURRENT world, or null when this
     * Once names a selector that world does not carry (the read is unset and the write a no-op).
     * Warns once per unknown selector name; see {@link DialogueFlagScope}.
     */
    @Nullable
    String keyFor(@Nonnull String rawKey, @Nonnull DialogueContext ctx) {
        return DialogueFlagScope.keyFor(scope(), rawKey, ctx);
    }

    /**
     * The PURE resolver behind {@link #keyFor}: the key for a world carrying
     * {@code worldSelectorNames}, or null when it does not carry this Once's selector.
     */
    @Nullable
    public String resolveKey(@Nonnull String rawKey, @Nonnull Set<String> worldSelectorNames) {
        return DialogueFlagScope.resolve(scope(), rawKey, worldSelectorNames);
    }

    @Nonnull
    private DialogueFlagScope scope() {
        DialogueFlagScope cached = scope;
        if (cached == null) {
            cached = DialogueFlagScope.ofWorldSelector(worldSelector);
            scope = cached;
        }
        return cached;
    }
}
