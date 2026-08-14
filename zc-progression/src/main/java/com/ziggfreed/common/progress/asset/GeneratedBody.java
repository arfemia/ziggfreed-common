package com.ziggfreed.common.progress.asset;

import javax.annotation.Nonnull;

import com.google.gson.JsonObject;

/**
 * One piece of content a generator wrote: an id and a body that is ordinary content JSON,
 * {@code Parent} and all. It goes through the very same decode a hand-written file does, which is
 * what guarantees a generated entry can never behave in a way a hand-written one could not.
 *
 * @param id          the generated id, lower-cased
 * @param body        the body, tokens already substituted, carrying its {@code Parent}
 * @param baseId      the id the body inherits from, lower-cased
 * @param generatorId which generator wrote it
 */
public record GeneratedBody(@Nonnull String id, @Nonnull JsonObject body, @Nonnull String baseId,
                            @Nonnull String generatorId) {
}
