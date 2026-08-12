package com.ziggfreed.common.progress;

import javax.annotation.Nullable;

/**
 * Where a progress event happened, as the two names an objective can be scoped against: the
 * player-facing zone name and the broader region (folder) name it sits in. Both are plain strings,
 * so a consumer-authored zone works exactly like an engine one.
 *
 * <p>Supplied by the consumer at dispatch time and passed straight through to
 * {@link ObjectiveMatch#zoneMatches}, which accepts a match against EITHER name so an author can
 * scope narrowly (one zone) or broadly (a whole region) with the same field. Immutable, so it is
 * safe to hand to an off-thread listener.
 */
public record ZoneRef(@Nullable String zoneName, @Nullable String regionName) {

    /** True when neither name resolved, i.e. the event happened outside any named zone. */
    public boolean isEmpty() {
        return (zoneName == null || zoneName.isBlank())
                && (regionName == null || regionName.isBlank());
    }
}
