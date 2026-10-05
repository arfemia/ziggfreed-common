package com.ziggfreed.common.achievement.asset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * selecting), so no two capstones can ever wait on each other; and it picks only inside the
 * capstone's own occurrence, so a yearly copy stands on its own year's copies of the same event and
 * an ordinary capstone on ordinary achievements, never on a set that grows every year.
 */
final class MetaSelection {

    private MetaSelection() {
    }

    /** Rewrite every selector capstone in {@code out}; {@code selectors} is keyed by folded id. */
    static void apply(@Nonnull Map<String, AchievementDefinition> out,
            @Nonnull Map<String, AchievementAsset.MetaSelector> selectors, @Nonnull List<Finding> issues) {
        if (selectors.isEmpty()) {
            return;
        }
        Map<String, AchievementDefinition> folded = new LinkedHashMap<>(out);
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
            List<String> picked = new ArrayList<>();
            for (AchievementDefinition candidate : folded.values()) {
                if (picks(capstone, selector, candidate, selectors)) {
                    picked.add(candidate.id());
                }
            }
            Collections.sort(picked);
            List<String> children = new ArrayList<>(capstone.achievement().metaChildren());
            for (String id : picked) {
                if (!children.contains(id)) {
                    children.add(id);
                }
            }
            out.put(capstone.id(), capstone.withMetaChildren(children));
        }
    }

    /** Does {@code selector} on {@code capstone} pick {@code candidate}? */
    static boolean picks(@Nonnull AchievementDefinition capstone, @Nonnull AchievementAsset.MetaSelector selector,
            @Nonnull AchievementDefinition candidate,
            @Nonnull Map<String, AchievementAsset.MetaSelector> selectors) {
        if (candidate.id().equals(capstone.id())) {
            return false;
        }
        if (candidate.achievement().isMeta() || selectors.containsKey(candidate.id())) {
            return false;
        }
        if (!sameOccurrence(capstone.achievement().occurrence(), candidate.achievement().occurrence())) {
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
}
