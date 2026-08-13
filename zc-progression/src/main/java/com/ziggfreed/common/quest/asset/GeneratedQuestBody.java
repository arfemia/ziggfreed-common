package com.ziggfreed.common.quest.asset;

import javax.annotation.Nonnull;

import com.google.gson.JsonObject;

/**
 * One quest a generator wrote: an id and a body that is ordinary quest JSON, {@code Parent} and
 * all. It goes through the very same decode a hand-written file does, which is what guarantees a
 * generated quest can never behave in a way a hand-written one could not.
 *
 * @param id          the generated quest id, lower-cased
 * @param body        the quest body, tokens already substituted, carrying its {@code Parent}
 * @param baseId      the quest id the body inherits from, lower-cased
 * @param generatorId which generator wrote it
 */
public record GeneratedQuestBody(@Nonnull String id, @Nonnull JsonObject body, @Nonnull String baseId,
                                 @Nonnull String generatorId) {
}
