package com.ziggfreed.common.objectives.book.achievement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;

/**
 * The Browse list's rules: which achievements a filter keeps, how ladders collapse, which section a row lands
 * in, how each section sorts. Built by {@link AchievementReader#browse}.
 *
 * <p>Sections, in order: Pinned (the live pins the filter keeps), then one per category (every category) or one
 * per subcategory (a category chosen, its rows with no subcategory first, then the file's subcategory order,
 * then the rest by id), then Feats of Strength (earned feats, closed unless the Feats status or a search asks).
 * A row appears once: a pinned row is listed under Pinned only. Each section caps at
 * {@link LedgerSection#DEFAULT_CAP} rows before the painter's "Show N more".
 *
 * <p>Public only for {@link #focus}, the open marks a focused open from outside the book hands the list.
 */
public final class AchievementBrowse {

    /** The section ids (a section's open or closed state rides the book's state under these). */
    static final String PINNED = "pinned";
    static final String FEATS = "feats";
    static final String OTHER = "other";
    static final String CATEGORY_PREFIX = "c.";
    static final String SUBCATEGORY_PREFIX = "s.";

    private AchievementBrowse() {
    }

    /** The list {@code reader} shows for {@code filter}. */
    @Nonnull
    static LedgerModel build(@Nonnull AchievementReader reader, @Nonnull BrowseFilter filter) {
        String needle = filter.search().toLowerCase(Locale.ROOT);
        List<Achievement> browse = new ArrayList<>();
        List<Achievement> feats = new ArrayList<>();
        for (Achievement a : reader.engine().achievements()) {
            AchievementShelves.Shelf shelf = reader.shelf(a);
            if (shelf == AchievementShelves.Shelf.NONE) {
                continue;
            }
            if (!filter.allCategories() && !filter.category().equals(AchievementGrouping.bucketOf(a))) {
                continue;
            }
            if (!needle.isEmpty() && !matches(reader, a, needle)) {
                continue;
            }
            if (!keeps(reader, a, shelf, filter.status())) {
                continue;
            }
            (shelf == AchievementShelves.Shelf.FEATS ? feats : browse).add(a);
        }
        if (!filter.searching()) {
            browse = reader.collapse(browse);
        }

        Comparator<Achievement> order = order(reader, filter.sort());
        boolean searching = filter.searching();
        List<LedgerSection> sections = new ArrayList<>();

        List<Achievement> pinned = new ArrayList<>();
        List<Achievement> rest = new ArrayList<>();
        for (Achievement a : browse) {
            (reader.pinned(a) ? pinned : rest).add(a);
        }
        for (Achievement a : feats) {
            if (reader.pinned(a)) {
                pinned.add(a);
            }
        }
        feats.removeAll(pinned);
        addSection(sections, reader, PINNED, reader.text("book.achievements.overview.pinned"), pinned, order, true);

        if (filter.allCategories()) {
            groupByCategory(reader, rest, order, sections);
        } else {
            groupBySubcategory(reader, filter.category(), rest, order, sections);
        }

        addSection(sections, reader, FEATS, reader.text("book.achievements.tile.feats"), feats, order,
                searching || BrowseFilter.STATUS_FEATS.equals(filter.status()));
        if (searching) {
            List<LedgerSection> opened = new ArrayList<>(sections.size());
            for (LedgerSection section : sections) {
                opened.add(new LedgerSection(section.id(), section.label(), section.rows(), true, section.cap()));
            }
            sections = opened;
        }
        return LedgerModel.of(sections);
    }

    /**
     * The open marks that land the Browse list, filtered to {@code category}, on one {@code subcategory}: its
     * section opened, every other section of that category closed (its rows with no subcategory included), in this
     * list's own section ids and the kit's mark ({@link LedgerPainter#CLOSED}). Pinned and Feats keep their defaults;
     * another category's achievements are not asked. The one place outside the list that spells its section ids.
     */
    @Nonnull
    public static Set<String> focus(@Nonnull Collection<Achievement> catalogue, @Nonnull String category,
            @Nonnull String subcategory) {
        String bucket = category.trim().toLowerCase(Locale.ROOT);
        String wanted = subcategory.trim().toLowerCase(Locale.ROOT);
        Set<String> out = new TreeSet<>();
        out.add(SUBCATEGORY_PREFIX + bucket + "." + wanted);
        for (Achievement a : catalogue) {
            if (a == null || !bucket.equals(AchievementGrouping.bucketOf(a))) {
                continue;
            }
            String sub = AchievementReader.blankToNull(a.subcategory());
            if (sub == null) {
                out.add(LedgerPainter.CLOSED + CATEGORY_PREFIX + bucket);
            } else if (!sub.equals(wanted)) {
                out.add(LedgerPainter.CLOSED + SUBCATEGORY_PREFIX + bucket + "." + sub);
            }
        }
        return Set.copyOf(out);
    }

    /** One section per category, in taxonomy order, content with no category last as "Other". */
    private static void groupByCategory(@Nonnull AchievementReader reader, @Nonnull List<Achievement> rows,
            @Nonnull Comparator<Achievement> order, @Nonnull List<LedgerSection> sections) {
        Map<String, List<Achievement>> byBucket = new LinkedHashMap<>();
        for (Achievement a : rows) {
            byBucket.computeIfAbsent(AchievementGrouping.bucketOf(a), k -> new ArrayList<>()).add(a);
        }
        List<String> buckets = new ArrayList<>(byBucket.keySet());
        buckets.sort(Comparator.comparingInt(reader::rank).thenComparing(b -> b));
        for (String bucket : buckets) {
            String id = bucket.isEmpty() ? OTHER : CATEGORY_PREFIX + bucket;
            addSection(sections, reader, id, reader.categoryName(bucket), byBucket.get(bucket), order, true);
        }
    }

    /**
     * One section per subcategory of {@code bucket}: rows with none first (under the category's own name), then
     * the subcategories in the file's order, then any the file does not name, by id.
     */
    private static void groupBySubcategory(@Nonnull AchievementReader reader, @Nonnull String bucket,
            @Nonnull List<Achievement> rows, @Nonnull Comparator<Achievement> order,
            @Nonnull List<LedgerSection> sections) {
        Map<String, List<Achievement>> bySub = new LinkedHashMap<>();
        for (Achievement a : rows) {
            String sub = AchievementReader.blankToNull(a.subcategory());
            bySub.computeIfAbsent(sub == null ? "" : sub, k -> new ArrayList<>()).add(a);
        }
        AchievementCategoryAsset asset = reader.category(bucket);
        List<String> authored = new ArrayList<>();
        if (asset != null) {
            for (String sub : asset.getSubcategories()) {
                String id = AchievementReader.blankToNull(sub);
                if (id != null && !authored.contains(id)) {
                    authored.add(id);
                }
            }
        }
        List<String> subs = new ArrayList<>(bySub.keySet());
        subs.sort(Comparator.comparingInt((String s) -> s.isEmpty() ? -1
                        : authored.contains(s) ? authored.indexOf(s) : authored.size())
                .thenComparing(s -> s));
        for (String sub : subs) {
            if (sub.isEmpty()) {
                String id = bucket.isEmpty() ? OTHER : CATEGORY_PREFIX + bucket;
                addSection(sections, reader, id, reader.categoryName(bucket), bySub.get(sub), order, true);
            } else {
                addSection(sections, reader, SUBCATEGORY_PREFIX + bucket + "." + sub,
                        reader.subcategoryName(bucket, sub), bySub.get(sub), order, true);
            }
        }
    }

    private static void addSection(@Nonnull List<LedgerSection> sections, @Nonnull AchievementReader reader,
            @Nonnull String id, @Nonnull Message label, @Nullable List<Achievement> achievements,
            @Nonnull Comparator<Achievement> order, boolean open) {
        if (achievements == null || achievements.isEmpty()) {
            return;
        }
        List<Achievement> sorted = new ArrayList<>(achievements);
        sorted.sort(order);
        List<LedgerRow> rows = new ArrayList<>(sorted.size());
        for (Achievement a : sorted) {
            rows.add(reader.row(a));
        }
        sections.add(new LedgerSection(id, label, rows, open));
    }

    /** Does {@code status} keep {@code a}? An unknown status was already read as all by the filter. */
    static boolean keeps(@Nonnull AchievementReader reader, @Nonnull Achievement a,
            @Nonnull AchievementShelves.Shelf shelf, @Nonnull String status) {
        return switch (status) {
            case BrowseFilter.STATUS_PROGRESS -> !reader.unlocked(a);
            case BrowseFilter.STATUS_EARNED -> reader.unlocked(a);
            case BrowseFilter.STATUS_WAITING -> reader.waiting(a);
            case BrowseFilter.STATUS_FEATS -> shelf == AchievementShelves.Shelf.FEATS;
            default -> true;
        };
    }

    /** The order inside a section. */
    @Nonnull
    static Comparator<Achievement> order(@Nonnull AchievementReader reader, @Nonnull String sort) {
        return switch (sort) {
            case BrowseFilter.SORT_AZ -> Comparator.comparing(reader::flatName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Achievement::id);
            case BrowseFilter.SORT_CLOSEST -> Comparator.comparingInt((Achievement a) -> reader.unlocked(a) ? 1 : 0)
                    .thenComparing(Comparator.comparingDouble(
                            (Achievement a) -> reader.aggregate(a).fraction()).reversed())
                    .thenComparing(reader::flatName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Achievement::id);
            default -> reader.defaultOrder();
        };
    }

    /** Title, description, criterion lines and a capstone's children's titles, as plain text. */
    static boolean matches(@Nonnull AchievementReader reader, @Nonnull Achievement a, @Nonnull String needle) {
        if (reader.flatName(a).toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        if (contains(ProgressionTexts.flavor(a.id()), needle)) {
            return true;
        }
        for (int i = 0; i < a.criteria().size(); i++) {
            if (contains(ProgressionTexts.objective(a.id(), Integer.toString(i)), needle)) {
                return true;
            }
        }
        for (String childId : a.metaChildren()) {
            Achievement child = reader.engine().achievement(childId);
            if (child != null && reader.flatName(child).toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(@Nullable Message text, @Nonnull String needle) {
        return text != null && UiText.flatten(text).toLowerCase(Locale.ROOT).contains(needle);
    }
}
