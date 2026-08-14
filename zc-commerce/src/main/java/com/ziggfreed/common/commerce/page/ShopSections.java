package com.ziggfreed.common.commerce.page;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How a storefront's page READS: which runs of rows it is made of, and in what order.
 *
 * <p>Pure - ids, categories and numbers in, an ordered list out - which is what makes the ordering
 * assertable. It is the half a player notices immediately and the half a refactor breaks silently:
 * a shelf that stops reading first, a category that starts sorting alphabetically, an uncategorised
 * run that jumps to the top.
 *
 * <p>The shape is two kinds of run and one rule about each. A rotating SHELF reads before the
 * standing catalogue, because it is the part that will not be there tomorrow. The standing
 * catalogue reads in the order the STOREFRONT declared its categories, then every category nobody
 * declared, alphabetically, then whatever carries no category at all - so a storefront that says
 * nothing still reads in a stable order, and one that says something gets exactly what it asked
 * for.
 */
public final class ShopSections {

    /** What a run of rows IS, which is what decides how its header reads. */
    public enum Kind {

        /** A rotating shelf: turns over on a clock, so its header carries the countdown. */
        SHELF,

        /** A run of the standing catalogue, headed by its category. */
        CATEGORY
    }

    /** One run of rows: what it is, what names it, and the offers in it, already in order. */
    public record Section(@Nonnull Kind kind, @Nonnull String id, @Nonnull List<String> offerIds) {
    }

    /** One offer, as far as the ordering is concerned. */
    public record Entry(@Nonnull String offerId, @Nullable String category, int order) {

        /** An entry with no category and no ordering opinion. */
        @Nonnull
        public static Entry of(@Nonnull String offerId) {
            return new Entry(offerId, null, 0);
        }
    }

    /** The bucket everything with no category of its own falls into, which always reads last. */
    public static final String UNCATEGORISED = "";

    private ShopSections() {
    }

    /**
     * The standing catalogue as ordered runs.
     *
     * @param entries       every offer that always stands on the page, in any order
     * @param categoryOrder the storefront's own declared category order, lower-cased; anything not
     *                      named in it still reads, just after everything that is
     */
    @Nonnull
    public static List<Section> standing(@Nonnull List<Entry> entries,
            @Nullable List<String> categoryOrder) {
        Map<String, List<Entry>> byCategory = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry == null) {
                continue;
            }
            byCategory.computeIfAbsent(categoryKey(entry.category()), key -> new ArrayList<>())
                    .add(entry);
        }
        List<String> keys = new ArrayList<>(byCategory.keySet());
        keys.sort(categoryComparator(categoryOrder));

        List<Section> out = new ArrayList<>();
        for (String key : keys) {
            List<Entry> rows = byCategory.get(key);
            rows.sort(Comparator.comparingInt(Entry::order).thenComparing(Entry::offerId));
            List<String> ids = new ArrayList<>(rows.size());
            for (Entry row : rows) {
                ids.add(row.offerId());
            }
            out.add(new Section(Kind.CATEGORY, key, ids));
        }
        return out;
    }

    /**
     * Every offer id the page will draw, in the order the sections put them - which is the list a
     * selection is validated against and the order a default selection reads from.
     */
    @Nonnull
    public static List<String> orderedIds(@Nonnull List<Section> sections) {
        List<String> out = new ArrayList<>();
        for (Section section : sections) {
            out.addAll(section.offerIds());
        }
        return out;
    }

    /**
     * Which offer the detail panel opens on: whatever was already selected while it is still on the
     * page, else the first row, else nothing at all.
     *
     * <p>A surviving selection must beat the first row, or every refresh after a purchase would jump
     * the player back to the top of the list.
     */
    @Nullable
    public static String select(@Nonnull List<String> orderedIds, @Nullable String currentSelection) {
        if (currentSelection != null) {
            for (String id : orderedIds) {
                if (CommerceText.sameId(id, currentSelection)) {
                    return id;
                }
            }
        }
        return orderedIds.isEmpty() ? null : orderedIds.get(0);
    }

    /**
     * A declared category reads where the storefront says, an undeclared one after every declared
     * one and alphabetically among its peers, and the uncategorised bucket after all of them.
     */
    @Nonnull
    private static Comparator<String> categoryComparator(@Nullable List<String> categoryOrder) {
        List<String> declared = new ArrayList<>();
        if (categoryOrder != null) {
            for (String category : categoryOrder) {
                String key = categoryKey(category);
                if (!key.isEmpty() && !declared.contains(key)) {
                    declared.add(key);
                }
            }
        }
        return Comparator.comparingInt((String key) -> rankOf(key, declared)).thenComparing(key -> key);
    }

    private static int rankOf(@Nonnull String key, @Nonnull List<String> declared) {
        if (UNCATEGORISED.equals(key)) {
            return Integer.MAX_VALUE;
        }
        int declaredAt = declared.indexOf(key);
        return declaredAt >= 0 ? declaredAt : Integer.MAX_VALUE - 1;
    }

    @Nonnull
    private static String categoryKey(@Nullable String category) {
        return CommerceText.normalize(category);
    }
}
