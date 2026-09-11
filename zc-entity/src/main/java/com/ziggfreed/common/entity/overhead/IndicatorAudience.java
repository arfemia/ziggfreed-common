package com.ziggfreed.common.entity.overhead;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * WHO an overhead indicator is for: one viewer, a set of viewers, or everyone who can see the host.
 *
 * <p>The two shapes layer rather than compete. An indicator shown to everyone is what a viewer sees
 * unless one was shown to THAT viewer, which wins for them alone; hiding takes down exactly what was
 * shown the same way, so a per-viewer cue over a shared one goes back to the shared one when it is
 * hidden. A host shows one indicator per viewer at a time - which one, when a consumer has several
 * reasons to show something, is that consumer's own precedence to settle before it calls.
 *
 * @param viewers the viewers this addresses; empty when it is shared with everyone
 * @param shared  true for every viewer who can see the host, now and later
 */
public record IndicatorAudience(@Nonnull Set<UUID> viewers, boolean shared) {

    private static final IndicatorAudience EVERYONE = new IndicatorAudience(Set.of(), true);

    public IndicatorAudience {
        viewers = Set.copyOf(viewers);
    }

    /** Everyone who can see the host, including viewers who arrive later. */
    @Nonnull
    public static IndicatorAudience everyone() {
        return EVERYONE;
    }

    /** One viewer. */
    @Nonnull
    public static IndicatorAudience of(@Nonnull UUID viewer) {
        return new IndicatorAudience(Set.of(viewer), false);
    }

    /** A set of viewers; an empty set addresses nobody, never everyone. */
    @Nonnull
    public static IndicatorAudience of(@Nonnull Collection<UUID> viewers) {
        return new IndicatorAudience(Set.copyOf(viewers), false);
    }

    /** True when this addresses nobody at all (an empty viewer set), so showing it changes nothing. */
    public boolean isEmpty() {
        return !shared && viewers.isEmpty();
    }
}
