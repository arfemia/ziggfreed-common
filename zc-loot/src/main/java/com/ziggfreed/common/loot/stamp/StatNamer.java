package com.ziggfreed.common.loot.stamp;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * What one stamped stat is CALLED, for the mod whose stat ids these are.
 *
 * <p>The write and the tooltip are both settled in this library - one format, one renderer, so a
 * stamped item reads the same wherever it turns up. What the library cannot know is vocabulary: a
 * channel id like {@code MMO_CritChance} means "Critical Chance", in red, to exactly one mod, and a
 * library guessing at that would be inventing a language it does not speak.
 *
 * <p>So it asks. {@link StampTooltip} asks per stat while composing a line, and anything REPORTING
 * an enhancement asks through the stamper's own {@code describe}. Answer null for an id that is not
 * yours and the caller falls back to {@link DefaultStatNames}, which knows the engine's own stats
 * and, failing that, prints the id and its points plainly.
 *
 * <p>A consumer therefore registers a vocabulary, never a renderer and never a stamper. Exactly one
 * is registered, through {@link StatNamerRegistry}.
 */
public interface StatNamer {

    /**
     * A fully-styled, client-resolved line naming {@code statId} and its {@code points}, or null
     * when this vocabulary does not own that id.
     *
     * <p>Null is the normal answer for somebody else's stat and costs nothing: the caller falls
     * back to the library's own naming rather than showing a gap.
     */
    @Nullable
    Message name(@Nonnull String statId, double points);
}
