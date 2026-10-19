package com.ziggfreed.common.grant;

import java.util.Objects;

import javax.annotation.Nonnull;

/**
 * One grant as a holder wants it on an entity: the key it is written under ({@code <owner>:<what holds it>}, a
 * track pick's {@code <owner>:<track>:<pick>}), the grant, and how many times it is held (a pick taken three times
 * writes three times its amount). Two placements under one key on one target are summed by their writer.
 */
public record Placed(@Nonnull String key, @Nonnull Grant grant, int count) {

    public Placed {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(grant, "grant");
        count = Math.max(0, count);
    }
}
