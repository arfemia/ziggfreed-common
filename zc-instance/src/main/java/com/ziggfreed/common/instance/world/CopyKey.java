package com.ziggfreed.common.instance.world;

import java.util.Locale;
import java.util.Objects;

import javax.annotation.Nonnull;

/**
 * One area of one engine instance copy: the key a copy's runs, members and resets are filed under. A
 * copy holding one party ({@code AreasPerCopy} 1) has the single area {@link #SOLE_AREA}.
 *
 * <p>The world name is kept lower-cased ({@link Locale#ROOT}), because the engine's {@code Universe}
 * keys its worlds that way: two spellings of one world's name are one key here too.
 */
public record CopyKey(@Nonnull String world, int area) {

    /** The only area of a copy that holds one party. */
    public static final int SOLE_AREA = 0;

    public CopyKey {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("a copy key needs a world name");
        }
        if (area < 0) {
            throw new IllegalArgumentException("a copy's area is never negative: " + area);
        }
        world = world.toLowerCase(Locale.ROOT);
    }

    /** The key of a copy that holds one party. */
    @Nonnull
    public static CopyKey sole(@Nonnull String world) {
        return new CopyKey(world, SOLE_AREA);
    }
}
