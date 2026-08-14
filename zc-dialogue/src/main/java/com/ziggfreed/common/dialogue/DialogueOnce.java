package com.ziggfreed.common.dialogue;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.world.WorldSelector;

/**
 * The {@code Once} knob: keyless seen-ness, authored on a {@code Start} entry or on an option.
 *
 * <pre>{@code
 * "Once": true                                                    once per character
 * "Once": { "Where": { "Match": ["forgotten_temple"] } }           once per world
 * "Once": { "Where": { "GameplayConfig": ["ForgottenTemple"] } }   once per instance world
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
 * <h2>{@code Where}</h2>
 *
 * <p>The shared world selector - the same {@code {Match, GameplayConfig, ExcludeMatch}} group an NPC
 * placement carries - so an author who has written one has already learned this one.
 *
 * <p>Reach for {@code GameplayConfig} for an instance world. One is named
 * {@code instance-KweebecNightmare_Barn-<uuid>} and is destroyed when it empties, so "already seen
 * here" keyed by the literal name would come back on every fresh instance; the config key is
 * authored, carries no uuid, and survives the rebuild. A {@code Match} pattern still works and files
 * the state under the pattern's literal core.
 *
 * <p>In a world the selector does not match, the Once neither reads nor writes: the beat is offered
 * and stays offered. Pair a world-scoped {@code Once} with a {@code World} condition on the same
 * beat so it cannot be reached elsewhere in the first place. A selector matching no world the server
 * has loaded warns once and is a validator finding, because a typo would otherwise re-show a
 * first-visit beat forever.
 */
public final class DialogueOnce {

    /** The canonical "once per character" group, the decoded form of {@code "Once": true}. */
    public static final DialogueOnce GLOBAL = new DialogueOnce();

    /** The group form, {@code {"Where": {...}}}. */
    private static final BuilderCodec<DialogueOnce> GROUP =
            BuilderCodec.builder(DialogueOnce.class, DialogueOnce::new)
                    .append(new KeyedCodec<>("Where", WorldSelector.CODEC, false),
                            (o, v) -> { o.where = v; o.scope = null; }, o -> o.where)
                    .documentation(DialogueFlagScope.WHERE_DOC).add()
                    .append(new KeyedCodec<>("World", DialogueFlagScope.RETIRED_WORLD_LEAF, false),
                            (o, v) -> { /* never decoded: the leaf refuses and says what to write */ },
                            o -> null)
                    .documentation("Retired. Write Where instead.").add()
                    .build();

    /**
     * Accepts BOTH authored forms: the plain {@code true} an author reaches for first, and the group
     * form that names a world family. {@code false} reads as "no Once at all", so turning one off is
     * a one-character edit rather than deleting a block.
     */
    public static final Codec<DialogueOnce> CODEC = new Codec<>() {

        @Override
        @Nullable
        public DialogueOnce decode(BsonValue value, ExtraInfo extraInfo) {
            if (value.isBoolean()) {
                return value.asBoolean().getValue() ? new DialogueOnce() : null;
            }
            return GROUP.decode(value, extraInfo);
        }

        @Nonnull
        @Override
        public BsonValue encode(DialogueOnce once, ExtraInfo extraInfo) {
            return GROUP.encode(once, extraInfo);
        }

        @Override
        @Nullable
        public DialogueOnce decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
            int next = reader.peek();
            if (next == 't' || next == 'T' || next == 'f' || next == 'F') {
                return reader.readBooleanValue() ? new DialogueOnce() : null;
            }
            return GROUP.decodeJson(reader, extraInfo);
        }

        @Nonnull
        @Override
        public Schema toSchema(@Nonnull SchemaContext context) {
            return GROUP.toSchema(context);
        }
    };

    @Nullable protected WorldSelector where;

    /** The internal scope carrier; built lazily, dropped by the setter so it cannot go stale. */
    @Nullable private volatile DialogueFlagScope scope;

    public DialogueOnce() {
    }

    /** Java-side construction (tests, a consumer building a tree in code). */
    @Nonnull
    public static DialogueOnce ofWhere(@Nullable WorldSelector where) {
        DialogueOnce once = new DialogueOnce();
        once.where = where;
        return once;
    }

    /** The worlds this Once is remembered per, or null for once per character. */
    @Nullable
    public WorldSelector getWhere() {
        return where;
    }

    /**
     * The storage key {@code rawKey} resolves to for the player's CURRENT world, or null when this
     * Once's pattern does not match that world (the read is unset and the write a no-op). Warns
     * once per pattern that matches no loaded world; see {@link DialogueFlagScope}.
     */
    @Nullable
    String keyFor(@Nonnull String rawKey, @Nonnull DialogueContext ctx) {
        return DialogueFlagScope.keyFor(scope(), rawKey, ctx);
    }

    /**
     * The PURE resolver behind {@link #keyFor}: the key in the world named {@code worldName}, or
     * null when this Once's selector does not match it.
     */
    @Nullable
    public String resolveKey(@Nonnull String rawKey, @Nullable String worldName) {
        return resolveKey(rawKey, worldName, null);
    }

    /** {@link #resolveKey(String, String)} with the world's gameplay config too. */
    @Nullable
    public String resolveKey(@Nonnull String rawKey, @Nullable String worldName,
                             @Nullable String worldGameplayConfig) {
        return DialogueFlagScope.resolve(scope(), rawKey, worldName, worldGameplayConfig);
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
}
