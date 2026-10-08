package com.ziggfreed.common.progress;

import java.util.Collection;
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
 * A qualifier has a comparison of its own ({@link #qualifierMatches(String, MatchMode, String)}),
 * the same three shapes the target has, compared whole unless the objective says otherwise, and
 * an objective may accept several qualifiers at once
 * ({@link #qualifierMatches(String, Collection, MatchMode, String)}: any one of them counts).
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
     * The whole predicate for one objective against one event: target AND qualifier, the qualifier
     * compared whole ({@link MatchMode#EXACT}). Zone scoping is checked separately (it needs the
     * event's location, which a caller resolves at most once per dispatch) - see
     * {@link #zoneMatches}.
     */
    public static boolean matches(@Nonnull String authoredTarget, @Nonnull MatchMode mode,
                                  @Nullable String authoredQualifier,
                                  @Nonnull String eventTarget, @Nullable String eventQualifier) {
        return matches(authoredTarget, mode, authoredQualifier, MatchMode.EXACT, eventTarget, eventQualifier);
    }

    /**
     * The whole predicate with the qualifier's own comparison: target under {@code mode} AND
     * qualifier under {@code qualifierMode}.
     */
    public static boolean matches(@Nonnull String authoredTarget, @Nonnull MatchMode mode,
                                  @Nullable String authoredQualifier, @Nonnull MatchMode qualifierMode,
                                  @Nonnull String eventTarget, @Nullable String eventQualifier) {
        return targetMatches(authoredTarget, mode, eventTarget)
                && qualifierMatches(authoredQualifier, qualifierMode, eventQualifier);
    }

    /**
     * The whole predicate with several accepted qualifiers: target under {@code mode} AND the
     * event's qualifier matching {@code authoredQualifier} or any of {@code authoredQualifiers},
     * each under {@code qualifierMode} (see
     * {@link #qualifierMatches(String, Collection, MatchMode, String)}). An empty list is exactly
     * the single-qualifier form.
     */
    public static boolean matches(@Nonnull String authoredTarget, @Nonnull MatchMode mode,
                                  @Nullable String authoredQualifier, @Nonnull Collection<String> authoredQualifiers,
                                  @Nonnull MatchMode qualifierMode,
                                  @Nonnull String eventTarget, @Nullable String eventQualifier) {
        return targetMatches(authoredTarget, mode, eventTarget)
                && qualifierMatches(authoredQualifier, authoredQualifiers, qualifierMode, eventQualifier);
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
     * band), compared WHOLE ({@link MatchMode#EXACT}). A null authored qualifier means "any", a
     * non-empty one compares case-insensitively, and an EMPTY authored one accepts only an event
     * with no qualifier at all - "specifically the unqualified kind".
     */
    public static boolean qualifierMatches(@Nullable String authoredQualifier,
                                           @Nullable String eventQualifier) {
        return qualifierMatches(authoredQualifier, MatchMode.EXACT, eventQualifier);
    }

    /**
     * Qualifier comparison under the qualifier's OWN {@code mode}, the same three shapes the
     * target offers: {@link MatchMode#EXACT} compares it whole, {@link MatchMode#CONTAINS} accepts
     * an event qualifier that carries it anywhere, {@link MatchMode#PREFIX} one that starts with
     * it, so one objective authored {@code Elite_Pack} with {@code PREFIX} counts an
     * {@code Elite_Pack} and an {@code Elite_Pack_Alpha} qualifier alike. The
     * null and empty rules are the mode's independent of the mode: null means "any", and an EMPTY
     * authored qualifier accepts only an event with no qualifier at all, whatever the mode says.
     */
    public static boolean qualifierMatches(@Nullable String authoredQualifier, @Nonnull MatchMode mode,
                                           @Nullable String eventQualifier) {
        if (authoredQualifier == null) {
            return true;
        }
        if (authoredQualifier.isEmpty()) {
            return eventQualifier == null;
        }
        if (eventQualifier == null) {
            return false;
        }
        String event = eventQualifier.toLowerCase(Locale.ROOT);
        String authored = authoredQualifier.toLowerCase(Locale.ROOT);
        return switch (mode) {
            case EXACT -> authored.equals(event);
            case CONTAINS -> event.contains(authored);
            case PREFIX -> event.startsWith(authored);
        };
    }

    /**
     * Qualifier comparison against SEVERAL accepted qualifiers: {@code authoredQualifier} (the
     * {@code Qualifier} leaf) plus every entry of {@code authoredQualifiers} (the
     * {@code Qualifiers} leaf). The event matches when its qualifier matches ANY of them under the
     * one {@code mode}, each entry by {@link #qualifierMatches(String, MatchMode, String)}'s rule. A
     * union, because both leaves read "accept these" and a union never silently ignores an
     * authored value.
     *
     * <ul>
     *   <li>A null, empty or blank list entry is ignored: it is never read as the empty
     *       "specifically unqualified" value, which only {@code Qualifier} can author.</li>
     *   <li>With nothing accepted (no {@code Qualifier} and no real entry) there is no filter, so
     *       any qualifier matches, as an objective that authors neither leaf always has.</li>
     *   <li>With an empty list this is exactly the single-qualifier form, so a file that authors
     *       only {@code Qualifier} keeps its meaning byte for byte.</li>
     * </ul>
     */
    public static boolean qualifierMatches(@Nullable String authoredQualifier,
                                           @Nonnull Collection<String> authoredQualifiers,
                                           @Nonnull MatchMode mode, @Nullable String eventQualifier) {
        boolean namedAny = false;
        if (authoredQualifier != null) {
            if (qualifierMatches(authoredQualifier, mode, eventQualifier)) {
                return true;
            }
            namedAny = true;
        }
        for (String entry : authoredQualifiers) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            if (qualifierMatches(entry, mode, eventQualifier)) {
                return true;
            }
            namedAny = true;
        }
        return !namedAny;
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
