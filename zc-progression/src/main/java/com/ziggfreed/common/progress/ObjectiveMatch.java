package com.ziggfreed.common.progress;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The matching core: does the identifier (plus qualifier, plus zone) an event carries satisfy what
 * an objective authored? Every dispatch path in this engine funnels through here, so there is one
 * place a comparison rule can be read or changed.
 *
 * <p><b>Two dialects, deliberately kept apart.</b> See {@link MatchFlavor} for why they cannot be
 * merged. In short: {@link MatchFlavor#STRICT} compares targets case-sensitively and treats an
 * empty authored target as "only an empty identifier", while {@link MatchFlavor#LENIENT} compares
 * case-insensitively and treats an empty authored target as match-all. Their empty-QUALIFIER rules
 * differ too, and in the opposite direction from what you would guess: strict's empty qualifier
 * accepts an absent OR empty event qualifier, lenient's accepts only an absent one.
 *
 * <p><b>Zone scoping is shared</b> by both dialects ({@link #zoneMatches}): an objective with no
 * zone passes everywhere, and an objective WITH one never passes for an event whose location could
 * not be resolved. That asymmetry is intentional - a scoped objective must not credit progress the
 * engine cannot place.
 *
 * <p>All methods are null-safe and side-effect free.
 */
public final class ObjectiveMatch {

    private ObjectiveMatch() {
    }

    /**
     * The whole predicate for one objective against one event: target AND qualifier, in the given
     * dialect. Zone scoping is checked separately (it needs the event's location, which a caller
     * resolves at most once per dispatch) - see {@link #zoneMatches}.
     */
    public static boolean matches(@Nonnull MatchFlavor flavor, @Nonnull String authoredTarget,
                                  @Nonnull MatchMode mode, @Nullable String authoredQualifier,
                                  @Nonnull String eventTarget, @Nullable String eventQualifier) {
        return targetMatches(flavor, authoredTarget, mode, eventTarget)
                && qualifierMatches(flavor, authoredQualifier, eventQualifier);
    }

    /**
     * Target comparison in the given dialect.
     *
     * <p>Under {@link MatchFlavor#STRICT} the comparison is case-SENSITIVE and an empty authored
     * target is a literal empty string: {@code EXACT} then matches only an empty identifier, while
     * {@code PREFIX}/{@code CONTAINS} match anything (every string starts with, and contains, the
     * empty string). Under {@link MatchFlavor#LENIENT} an empty authored target matches everything
     * outright and the rest compare case-INSENSITIVELY.
     */
    public static boolean targetMatches(@Nonnull MatchFlavor flavor, @Nonnull String authoredTarget,
                                        @Nonnull MatchMode mode, @Nonnull String eventTarget) {
        if (flavor == MatchFlavor.LENIENT) {
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
        return switch (mode) {
            case EXACT -> eventTarget.equals(authoredTarget);
            case PREFIX -> eventTarget.startsWith(authoredTarget);
            case CONTAINS -> eventTarget.contains(authoredTarget);
        };
    }

    /**
     * Qualifier comparison in the given dialect (the secondary filter beside the target, e.g. a
     * tier or a difficulty band).
     *
     * <p>A null authored qualifier means "any" in BOTH dialects, and a non-empty one compares
     * case-insensitively in both. Only the EMPTY authored qualifier differs: under
     * {@link MatchFlavor#STRICT} it accepts an event qualifier that is null OR empty (an author
     * writing {@code ""} meant "unqualified", and a producer firing {@code ""} means the same
     * thing); under {@link MatchFlavor#LENIENT} it accepts only a null one.
     */
    public static boolean qualifierMatches(@Nonnull MatchFlavor flavor, @Nullable String authoredQualifier,
                                           @Nullable String eventQualifier) {
        if (authoredQualifier == null) {
            return true;
        }
        if (authoredQualifier.isEmpty()) {
            return flavor == MatchFlavor.STRICT
                    ? eventQualifier == null || eventQualifier.isEmpty()
                    : eventQualifier == null;
        }
        return authoredQualifier.equalsIgnoreCase(eventQualifier);
    }

    /**
     * Zone scoping, shared by both dialects: an objective with no authored zone passes everywhere;
     * otherwise the authored string must match, case-insensitively, EITHER the event's zone name or
     * its region name, so one field covers both a narrow and a broad scope. An event with no
     * resolvable location never satisfies a zone-scoped objective.
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
