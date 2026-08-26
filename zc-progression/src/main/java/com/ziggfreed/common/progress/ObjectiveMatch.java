package com.ziggfreed.common.progress;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The matching core: does the identifier (plus qualifier, plus zone) an event carries satisfy what
 * an objective authored? Every dispatch path in this engine funnels through here, so there is one
 * place the comparison rule can be read or changed.
 *
 * <p><b>ONE dialect, forgiving on purpose.</b> Targets compare case-INSENSITIVELY (an author who
 * copies an id in the wrong case still matches the thing they named), an EMPTY authored target
 * matches everything under every {@link MatchMode} (the match-all shorthand every broad tally
 * wants), and an EMPTY authored qualifier matches only an event that carries no qualifier at all.
 * Quest objectives and achievement criteria - and every other consumer of this engine family -
 * match by the same rule, so a criterion moved between content types never changes what it counts.
 *
 * <p><b>Zone scoping</b> ({@link #zoneMatches}): an objective with no zone passes everywhere, and
 * an objective WITH one never passes for an event whose location could not be resolved. That
 * asymmetry is intentional - a scoped objective must not credit progress the engine cannot place.
 *
 * <p>All methods are null-safe and side-effect free.
 */
public final class ObjectiveMatch {

    private ObjectiveMatch() {
    }

    /**
     * The whole predicate for one objective against one event: target AND qualifier. Zone scoping
     * is checked separately (it needs the event's location, which a caller resolves at most once
     * per dispatch) - see {@link #zoneMatches}.
     */
    public static boolean matches(@Nonnull String authoredTarget, @Nonnull MatchMode mode,
                                  @Nullable String authoredQualifier,
                                  @Nonnull String eventTarget, @Nullable String eventQualifier) {
        return targetMatches(authoredTarget, mode, eventTarget)
                && qualifierMatches(authoredQualifier, eventQualifier);
    }

    /**
     * Target comparison: case-insensitive, with an EMPTY authored target matching everything
     * outright, whatever the {@link MatchMode} says.
     */
    public static boolean targetMatches(@Nonnull String authoredTarget, @Nonnull MatchMode mode,
                                        @Nonnull String eventTarget) {
        if (authoredTarget.isEmpty()) {
            return true;
        }
        String event = eventTarget.toLowerCase(Locale.ROOT);
        String authored = authoredTarget.toLowerCase(Locale.ROOT);
        return switch (mode) {
            case EXACT -> authored.equals(event);
            case CONTAINS -> event.contains(authored);
            case PREFIX -> event.startsWith(authored);
        };
    }

    /**
     * Qualifier comparison (the secondary filter beside the target, e.g. a tier or a difficulty
     * band). A null authored qualifier means "any", a non-empty one compares case-insensitively,
     * and an EMPTY authored one accepts only an event with no qualifier at all - "specifically the
     * unqualified kind".
     */
    public static boolean qualifierMatches(@Nullable String authoredQualifier,
                                           @Nullable String eventQualifier) {
        if (authoredQualifier == null) {
            return true;
        }
        if (authoredQualifier.isEmpty()) {
            return eventQualifier == null;
        }
        return authoredQualifier.equalsIgnoreCase(eventQualifier);
    }

    /**
     * Zone scoping: an objective with no authored zone passes everywhere; otherwise the authored
     * string must match, case-insensitively, EITHER the event's zone name or its region name, so
     * one field covers both a narrow and a broad scope. An event with no resolvable location never
     * satisfies a zone-scoped objective.
     */
    public static boolean zoneMatches(@Nullable String authoredZone, @Nullable ZoneRef eventZone) {
        if (authoredZone == null || authoredZone.isBlank()) {
            return true;
        }
        if (eventZone == null) {
            return false;
        }
        return authoredZone.equalsIgnoreCase(eventZone.zoneName())
                || authoredZone.equalsIgnoreCase(eventZone.regionName());
    }
}
