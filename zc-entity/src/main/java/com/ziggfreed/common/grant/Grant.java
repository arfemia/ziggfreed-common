package com.ziggfreed.common.grant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.lookup.CodecMapCodec;

/**
 * One grant: a payload that becomes part of an entity while something holds it (a track pick made, later a class
 * or a perk), written by its kind's {@link GrantWriter} under a key its holder owns, and taken off again when it is
 * no longer held. Every grant is one arm of {@link #CODEC}, a native {@code Kind}-discriminated union, so a file
 * writes it inline, {@code { "Kind": "Stat", "Stat": "Health", "Add": 4 }}, and the Asset Editor lists every kind
 * registered.
 *
 * <p><b>The kinds are a registry</b> (the seam, {@link Grants#register}): a module registers its kind's codec into
 * this union through the engine's own codec registry ({@code getCodecRegistry(Grant.CODEC)}) together with the one
 * writer that puts that kind on an entity. The library ships {@code Stat} ({@link StatGrant}), which
 * {@link BuiltInGrants#ensure} also puts in the union before any setup, so a codec reading grants decodes it in any
 * JVM; resistances, outgoing
 * damage, abilities, titles and cosmetics are kinds a later module registers the same way.
 *
 * <p>A kind's {@link #kind()} is the word a world rule names to switch it off where a player stands, and its
 * {@link #family()} a broader word several kinds may share (the combat bonuses), so one switch can name them all.
 */
public abstract class Grant {

    /** The union every grant kind registers into, discriminated by {@code Kind}. */
    public static final CodecMapCodec<Grant> CODEC = new CodecMapCodec<>("Kind");

    /** A list of grants, as a file writes them. */
    public static final ArrayCodec<Grant> ARRAY_CODEC = new ArrayCodec<>(CODEC, Grant[]::new);

    protected Grant() {
    }

    /** The kind id, exactly as {@link Grants#register} registered it ({@code Stat}). */
    @Nonnull
    public abstract String kind();

    /**
     * The broader word a world rule may also name for this kind (the combat bonuses' {@code Combat}), or null when
     * only the kind's own id switches it.
     */
    @Nullable
    public String family() {
        return null;
    }
}
