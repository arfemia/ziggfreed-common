package com.ziggfreed.common.achievement.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.validation.Finding;

/**
 * Works out what each selector capstone stands on, once the whole pool is folded (yearly copies
 * included). A capstone keeps its explicit children first and gains every pick after them, sorted by
 * id, none twice.
 *
 * <p>A selector never picks the capstone itself or ANY capstone (one listing children by id, or one
 * selecting), so no two capstones can ever wait on each other. Without {@code AnyYear} it picks only
 * inside the capstone's own occurrence, so a yearly copy stands on its own year's copies of the same
 * event and an ordinary capstone on ordinary achievements, never on a set that grows every year.
 *
 * <p>{@code AnyYear} (an ordinary capstone only) lets every year's copy stand for its base and counts
 * the picks in GROUPS keyed by the calendar event a copy comes back with
 * ({@link Achievement.MetaGroup#seasonKey}, a key space no pick's own id meets): two years of one season
 * are one group, an explicit child that is one year's copy stands in its season's group too, and a group
 * whose event this server switched off is out of the count, asked live.
 * {@code Needs} says how many groups must be earned (every counted one when unauthored), and
 * {@code AtLeast} is a floor under that number. A selector writing any of the three is grouped; one
 * writing none stands on a plain list, exactly as before.
 */
final class MetaSelection {

    /** Always in the count: a group that is no calendar event's. */
    private static final BooleanSupplier ALWAYS = () -> true;

    private MetaSelection() {
    }

    /** Rewrite every selector capstone in {@code out}; {@code selectors} is keyed by folded id. */
    static void apply(@Nonnull Map<String, AchievementDefinition> out,
            @Nonnull Map<String, AchievementAsset.MetaSelector> selectors, @Nonnull OccurrenceReader calendar,
            @Nonnull List<Finding> issues) {
        if (selectors.isEmpty()) {
            return;
        }
        Map<String, AchievementDefinition> folded = new LinkedHashMap<>(out);
        Set<String> reported = new HashSet<>();
        for (Map.Entry<String, AchievementAsset.MetaSelector> entry : selectors.entrySet()) {
            AchievementDefinition capstone = folded.get(entry.getKey());
            if (capstone == null) {
                continue;
            }
            AchievementAsset.MetaSelector selector = entry.getValue();
            if (selector.isEmpty()) {
                issues.add(Finding.warning(AchievementPoolValidator.DOMAIN, "EMPTY_META_SELECTOR",
                        "MetaSelector writes no Category, Subcategory or Tags, so it picks nothing; a selector"
                                + " picking everything would make this a capstone over the whole catalogue",
                        capstone.id()));
                continue;
            }
            boolean anyYear = anyYear(capstone, selector, reported, issues);
            Integer needs = needs(capstone, selector, reported, issues);
            Map<String, List<String>> picks = select(capstone, selector, anyYear, folded.values(), selectors);
            if (anyYear || selector.getNeeds() != null || selector.getAtLeast() != null) {
                out.put(capstone.id(), capstone.withMetaGroups(groups(capstone, picks, folded, anyYear, calendar),
                        needs, selector.getAtLeast()));
            } else {
                out.put(capstone.id(), capstone.withMetaChildren(plainChildren(capstone, picks)));
            }
        }
    }

    /**
     * What {@code selector} on {@code capstone} picks from {@code candidates}, in groups ({@link #groupKey}):
     * keyed by the season of the calendar event a yearly copy comes back with when {@code anyYear} holds,
     * else by the pick's own id, so each pick is its own group. Keys and each group's ids are sorted, so a
     * fold is stable.
     */
    @Nonnull
    static Map<String, List<String>> select(@Nonnull AchievementDefinition capstone,
            @Nonnull AchievementAsset.MetaSelector selector, boolean anyYear,
            @Nonnull Collection<AchievementDefinition> candidates,
            @Nonnull Map<String, AchievementAsset.MetaSelector> selectors) {
        Map<String, List<String>> groups = new TreeMap<>();
        for (AchievementDefinition candidate : candidates) {
            if (!picks(capstone, selector, anyYear, candidate, selectors)) {
                continue;
            }
            groups.computeIfAbsent(groupKey(candidate, candidate.id(), anyYear), ignored -> new ArrayList<>())
                    .add(candidate.id());
        }
        for (List<String> ids : groups.values()) {
            Collections.sort(ids);
        }
        return groups;
    }

    /** Does {@code selector} on {@code capstone} pick {@code candidate}? */
    static boolean picks(@Nonnull AchievementDefinition capstone, @Nonnull AchievementAsset.MetaSelector selector,
            boolean anyYear, @Nonnull AchievementDefinition candidate,
            @Nonnull Map<String, AchievementAsset.MetaSelector> selectors) {
        if (candidate.id().equals(capstone.id())) {
            return false;
        }
        if (candidate.achievement().isMeta() || selectors.containsKey(candidate.id())) {
            return false;
        }
        // AnyYear: any year's copy of any event stands for its base. Otherwise, only the same occurrence.
        if (!anyYear && !sameOccurrence(capstone.achievement().occurrence(), candidate.achievement().occurrence())) {
            return false;
        }
        return selector.matches(candidate.achievement());
    }

    /** Both ordinary, or both copies of the same event's same year. */
    static boolean sameOccurrence(@Nullable Achievement.Occurrence a, @Nullable Achievement.Occurrence b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.year() == b.year() && a.eventId().equals(b.eventId());
    }

    /** Today's plain list: explicit children first, then every pick by id, none twice. */
    @Nonnull
    private static List<String> plainChildren(@Nonnull AchievementDefinition capstone,
            @Nonnull Map<String, List<String>> picks) {
        List<String> picked = new ArrayList<>();
        for (List<String> ids : picks.values()) {
            picked.addAll(ids);
        }
        Collections.sort(picked);
        List<String> children = new ArrayList<>(capstone.achievement().metaChildren());
        for (String id : picked) {
            if (!children.contains(id)) {
                children.add(id);
            }
        }
        return children;
    }

    /**
     * A grouped capstone's groups: the explicit children first, then the picks, none twice. An explicit
     * child is keyed as a pick would be ({@link #groupKey}), so under AnyYear one year's copy named outright
     * stands in its season's one group, and the season never counts twice; any other child is its own group.
     */
    @Nonnull
    private static List<Achievement.MetaGroup> groups(@Nonnull AchievementDefinition capstone,
            @Nonnull Map<String, List<String>> picks, @Nonnull Map<String, AchievementDefinition> folded,
            boolean anyYear, @Nonnull OccurrenceReader calendar) {
        Map<String, List<String>> keyed = new LinkedHashMap<>();
        Set<String> listed = new HashSet<>();
        for (String child : capstone.achievement().metaChildren()) {
            if (listed.add(child)) {
                keyed.computeIfAbsent(groupKey(folded.get(child), child, anyYear), ignored -> new ArrayList<>())
                        .add(child);
            }
        }
        for (Map.Entry<String, List<String>> pick : picks.entrySet()) {
            for (String id : pick.getValue()) {
                if (listed.add(id)) {
                    keyed.computeIfAbsent(pick.getKey(), ignored -> new ArrayList<>()).add(id);
                }
            }
        }
        List<Achievement.MetaGroup> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : keyed.entrySet()) {
            List<String> ids = group.getValue();
            out.add(new Achievement.MetaGroup(group.getKey(), ids,
                    countedFor(group.getKey(), folded.get(ids.get(0)), calendar)));
        }
        return out;
    }

    /**
     * The group {@code id} counts in: under AnyYear a yearly copy's season
     * ({@link Achievement.MetaGroup#seasonKey}), every other pick or child its own id, a key space no season's
     * key meets.
     */
    @Nonnull
    private static String groupKey(@Nullable AchievementDefinition definition, @Nonnull String id, boolean anyYear) {
        Achievement.Occurrence occurrence = definition == null ? null : definition.achievement().occurrence();
        return anyYear && occurrence != null ? Achievement.MetaGroup.seasonKey(occurrence.eventId()) : id;
    }

    /** A season's group is counted while its event is switched on, asked live; any other group always. */
    @Nonnull
    private static BooleanSupplier countedFor(@Nonnull String key, @Nullable AchievementDefinition first,
            @Nonnull OccurrenceReader calendar) {
        Achievement.Occurrence occurrence = first == null ? null : first.achievement().occurrence();
        if (occurrence == null || !Achievement.MetaGroup.seasonKey(occurrence.eventId()).equals(key)) {
            return ALWAYS;
        }
        String eventId = occurrence.eventId();
        return () -> calendar.isEnabled(eventId);
    }

    /** AnyYear as it applies: only an ordinary capstone stands on every year (reported once otherwise). */
    private static boolean anyYear(@Nonnull AchievementDefinition capstone,
            @Nonnull AchievementAsset.MetaSelector selector, @Nonnull Set<String> reported,
            @Nonnull List<Finding> issues) {
        if (!selector.isAnyYear()) {
            return false;
        }
        Achievement.Occurrence occurrence = capstone.achievement().occurrence();
        if (occurrence == null) {
            return true;
        }
        reportOnce(reported, "ANY_YEAR_ON_YEARLY_CAPSTONE", occurrence.baseId(),
                "MetaSelector.AnyYear is written on a yearly achievement, whose copies each stand on their own"
                        + " year; it is read as false. Put AnyYear on an ordinary capstone instead", issues);
        return false;
    }

    /** Needs as it applies: one or more, else every counted group (reported once). */
    @Nullable
    private static Integer needs(@Nonnull AchievementDefinition capstone,
            @Nonnull AchievementAsset.MetaSelector selector, @Nonnull Set<String> reported,
            @Nonnull List<Finding> issues) {
        Integer needs = selector.getNeeds();
        if (needs == null || needs >= 1) {
            return needs;
        }
        Achievement.Occurrence occurrence = capstone.achievement().occurrence();
        reportOnce(reported, "BAD_META_NEEDS", occurrence == null ? capstone.id() : occurrence.baseId(),
                "MetaSelector.Needs is " + needs + ", which no count can mean; it is read as every group in"
                        + " the count. Write 1 or more, or leave it out", issues);
        return null;
    }

    /** One finding per file and code, however many yearly copies the file minted. */
    private static void reportOnce(@Nonnull Set<String> reported, @Nonnull String code, @Nonnull String sourceId,
            @Nonnull String message, @Nonnull List<Finding> issues) {
        if (reported.add(code + "|" + sourceId)) {
            issues.add(Finding.warning(AchievementPoolValidator.DOMAIN, code, message, sourceId));
        }
    }
}
