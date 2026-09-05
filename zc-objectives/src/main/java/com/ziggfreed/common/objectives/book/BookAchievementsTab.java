package com.ziggfreed.common.objectives.book;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.achievement.asset.AchievementCategoryConfig;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.progress.runtime.ProgressionIcons;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.asset.ContentListingAsset.ChainMembership;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.StatusTones;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.ZigSearchRow;

import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.ACH_CATEGORY_CARD_TEMPLATE;
import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.ACH_CHIP_TEMPLATE;
import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.ACH_CRITERION_TEMPLATE;
import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.ACH_ROW_TEMPLATE;
import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.MAX_ROWS;
import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.MILESTONE_TEMPLATE;
import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.REWARD_ROW_TEMPLATE;
import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.WIDE_TAB_OUTER_WIDTH;
import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.WIDE_TAB_TEMPLATE;

/**
 * The achievements tab of the Objective Book: a two-panel browser. LEFT is the filter strip
 * (category tabs with unlocked / total counts, subcategory chips, search, sort and status chips)
 * over compact rows plus the earned-only feats section, ladders collapsed to their active rung.
 * RIGHT is the selected achievement's whole story - header, meta line, description, progress,
 * criteria, what it requires, what it is part of, its ladder, and its rewards with the one real
 * claim button - or, while nothing is selected, the OVERVIEW: recent unlocks, nearest to
 * complete, pinned, the category grid, and the consumer's points milestones.
 *
 * <p>Everything here paints; the verbs live on {@link ObjectiveBookPage}. Selecting a row
 * repaints the right panel alone, scroll preserved, through the clear + re-append + bind-fresh
 * partial-update pattern.
 */
final class BookAchievementsTab {

    private static final int MAX_RECENT = 5;
    private static final int MAX_NEAREST = 2;

    private static final String[] SORT_MODES = {"", "alpha", "progress"};
    private static final String[] STATUS_FILTERS = {"all", "in_progress", "unlocked"};

    private BookAchievementsTab() {
    }

    // ==================== build ====================

    static void render(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
                       @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store,
                       @Nonnull Ref<EntityStore> ref, @Nonnull Subject subject,
                       @Nonnull AchievementEngine engine) {
        List<ObjectiveBookDeps.MilestoneView> milestones =
                page.deps().milestonesGuarded(store, ref, subject);
        renderHeaderStats(page, cmd, subject, engine, milestones);
        renderFilterStrip(page, cmd, events, subject, engine);

        // The browse list plus the earned-only feats section.
        List<Achievement> display = filteredAchievements(page, subject, engine, false);
        if (display.isEmpty()) {
            cmd.set("#AEmptyMessage.Visible", true);
            cmd.set("#AEmptyMessage.TextSpans", page.text(page.searchText().isEmpty()
                    ? "book.achievements.empty" : "book.achievements.no_search_results"));
        } else {
            int shown = Math.min(display.size(), MAX_ROWS);
            for (int i = 0; i < shown; i++) {
                cmd.append("#AchList", ACH_ROW_TEMPLATE);
                paintListRow(page, cmd, events, "#AchList[" + i + "]", display.get(i), subject,
                        engine, true, false);
            }
        }
        List<Achievement> feats = unlockedFeats(page, subject, engine);
        if (!feats.isEmpty()) {
            cmd.set("#FeatsHeader.Visible", true);
            // The earned count is code-composed beside the header, never baked into a lang value.
            cmd.set("#FeatsHeader.TextSpans", Msg.join(page.text("book.achievements.feats_header"),
                    Msg.raw(" (" + feats.size() + ")")));
            int shown = Math.min(feats.size(), MAX_ROWS);
            for (int i = 0; i < shown; i++) {
                cmd.append("#FeatsList", ACH_ROW_TEMPLATE);
                paintListRow(page, cmd, events, "#FeatsList[" + i + "]", feats.get(i), subject,
                        engine, false, false);
            }
        }

        // The one real claim button is bound ONCE, id-less: it acts on the live selection, so a
        // partial detail repaint restyles it without ever re-binding it.
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DClaimBtn",
                page.fullState("claim"));
        ZigRichButton.text(cmd, "#DClaimBtn", page.text("book.achievements.claim_button"));
        ObjectiveBookPage.applyGoldClaim(cmd, "#DClaimBtn");

        // The detail header's pin toggle, bound the same way: once, id-less, acting on the live
        // selection. paintDetail repaints its glyph and hides it for a feat.
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DPinBtn",
                page.fullState("togglepin"));

        // The right panel: the selected achievement's detail, else the overview.
        Achievement selected = page.selectedId() == null ? null
                : engine.achievement(page.selectedId());
        if (selected != null) {
            cmd.set("#ADetail.Visible", true);
            paintDetail(page, cmd, events, subject, engine, selected);
        } else {
            cmd.set("#AOverview.Visible", true);
            paintOverview(page, cmd, events, store, ref, subject, engine, milestones);
        }
    }

    private static void renderHeaderStats(@Nonnull ObjectiveBookPage page,
            @Nonnull UICommandBuilder cmd, @Nonnull Subject subject,
            @Nonnull AchievementEngine engine,
            @Nonnull List<ObjectiveBookDeps.MilestoneView> milestones) {
        // Feats stay out of the unlocked count: they live in their own earned-only section and
        // count no points, so counting them would make the two header numbers disagree.
        int unlockedCount = 0;
        for (Achievement achievement : engine.achievements()) {
            if (!achievement.featOfStrength() && achievement.available()
                    && isUnlocked(engine.status(subject, achievement.id()))) {
                unlockedCount++;
            }
        }
        int points = engine.points(subject);
        cmd.set("#Stat0Value.TextSpans", Msg.raw(String.valueOf(unlockedCount)));
        cmd.set("#Stat0Value.Style.TextColor", StatusTones.READY.hex());
        cmd.set("#Stat0Label.TextSpans", page.text("book.achievements.unlocked_label"));
        cmd.set("#Stat1Value.TextSpans", Msg.raw(String.valueOf(points)));
        cmd.set("#Stat1Value.Style.TextColor", "#ffd24a");
        cmd.set("#Stat1Label.TextSpans", page.text("book.achievements.points_label"));
        paintNextMilestoneStat(page, cmd, points, milestones);
    }

    /** The third header stat: the next unclaimed milestone; hidden where no ladder exists. */
    static void paintNextMilestoneStat(@Nonnull ObjectiveBookPage page,
            @Nonnull UICommandBuilder cmd, int points,
            @Nonnull List<ObjectiveBookDeps.MilestoneView> milestones) {
        if (milestones.isEmpty()) {
            cmd.set("#Stat2Block.Visible", false);
            return;
        }
        cmd.set("#Stat2Block.Visible", true);
        cmd.set("#Stat2Label.TextSpans", page.text("book.achievements.next_milestone"));
        ObjectiveBookDeps.MilestoneView next = null;
        for (ObjectiveBookDeps.MilestoneView view : milestones) {
            if (!view.claimed()) {
                next = view;
                break;
            }
        }
        // A running "points / threshold" is locale-neutral data, never a translation key.
        cmd.set("#Stat2Value.TextSpans", next != null
                ? Msg.raw(points + " / " + next.threshold())
                : page.text("book.achievements.all_milestones_claimed"));
    }

    // ==================== the filter strip ====================

    private static void renderFilterStrip(@Nonnull ObjectiveBookPage page,
            @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Subject subject, @Nonnull AchievementEngine engine) {
        Map<String, int[]> counts = categoryCounts(subject, engine);
        int grandUnlocked = 0;
        int grandTotal = 0;
        for (int[] pair : counts.values()) {
            grandUnlocked += pair[0];
            grandTotal += pair[1];
        }
        List<String> categories = orderedCategories(counts);

        boolean allActive = ObjectiveBookPage.FILTER_ALL.equalsIgnoreCase(page.filterCategory());
        if (!ObjectiveBookPage.categoryChipsFit(categories.size(), WIDE_TAB_OUTER_WIDTH,
                page.stripWidthBudget())) {
            cmd.set("#ACategoryDropdown.Visible", true);
            List<DropdownEntryInfo> entries = new ArrayList<>(categories.size() + 1);
            entries.add(SettingsUiUtil.entry(
                    UiText.flatten(page.text("book.quests.filter.all"))
                            + "  " + grandUnlocked + "/" + grandTotal,
                    ObjectiveBookPage.FILTER_ALL));
            for (String category : categories) {
                int[] pair = counts.getOrDefault(category, new int[]{0, 0});
                entries.add(SettingsUiUtil.entry(
                        UiText.flatten(categoryDisplayName(page, category))
                                + "  " + pair[0] + "/" + pair[1], category));
            }
            SettingsUiUtil.populate(cmd, "#ACategoryDropdown", entries,
                    allActive ? ObjectiveBookPage.FILTER_ALL : page.filterCategory());
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ACategoryDropdown",
                    page.fullState("category").append("@DropdownValue", "#ACategoryDropdown.Value"),
                    false);
        } else {
            // The name + count composite rides a Label's .TextSpans (a top-level join, never a
            // param), so a plain join is safe here.
            int index = appendWideTab(page, cmd, events, 0,
                    Msg.join(page.text("book.quests.filter.all"),
                            Msg.raw("  " + grandUnlocked + "/" + grandTotal)),
                    allActive, ObjectiveBookPage.FILTER_ALL);
            for (String category : categories) {
                int[] pair = counts.getOrDefault(category, new int[]{0, 0});
                index = appendWideTab(page, cmd, events, index,
                        Msg.join(categoryDisplayName(page, category),
                                Msg.raw("  " + pair[0] + "/" + pair[1])),
                        category.equalsIgnoreCase(page.filterCategory()), category);
            }
        }

        // Subcategory chips - only when one category is chosen and it has a second level.
        if (!allActive) {
            List<String> subcategories = subcategoriesOf(engine, page.filterCategory());
            if (!subcategories.isEmpty()) {
                cmd.set("#ASubcatRow.Visible", true);
                int index = appendSubChip(page, cmd, events, 0,
                        page.text("book.achievements.subfilter_all"),
                        page.filterSubcategory().isEmpty(), "");
                for (String subcategory : subcategories) {
                    index = appendSubChip(page, cmd, events, index,
                            Msg.raw(ObjectiveBookDeps.prettifyTag(subcategory)),
                            subcategory.equalsIgnoreCase(page.filterSubcategory()), subcategory);
                }
            }
        }

        // Search (the shared row; the full state already carries the live text under the same
        // key), sort dropdown, status chips.
        ZigSearchRow.wire(cmd, events, page.activeSearchRow(), page.searchText(),
                ObjectiveBookPage.SEARCH_KEY, page.fullState("search"),
                page.fullState("clear_search"));
        // Sort is a single choice, so it rides the native dropdown and every label renders
        // whole. A dropdown entry is a String-only sink, so labels flatten here.
        List<DropdownEntryInfo> sortEntries = new ArrayList<>(SORT_MODES.length);
        for (String mode : SORT_MODES) {
            sortEntries.add(SettingsUiUtil.entry(UiText.flatten(page.text(sortKey(mode))), mode));
        }
        SettingsUiUtil.populate(cmd, "#ASortDropdown", sortEntries, page.sortMode());
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ASortDropdown",
                page.fullState("sort").append("@DropdownValue", "#ASortDropdown.Value"), false);
        int statusIndex = 0;
        for (String status : STATUS_FILTERS) {
            boolean active = "all".equals(status)
                    ? ObjectiveBookPage.FILTER_ALL.equalsIgnoreCase(page.filterStatus())
                    : status.equalsIgnoreCase(page.filterStatus());
            statusIndex = appendBarChip(page, cmd, events, "#AStatusBar", statusIndex,
                    page.text(statusKey(status)), active, "status", status);
        }
    }

    @Nonnull
    private static String sortKey(@Nonnull String mode) {
        return switch (mode) {
            case "alpha" -> "book.achievements.sort.alpha";
            case "progress" -> "book.achievements.sort.progress";
            default -> "book.achievements.sort.default";
        };
    }

    @Nonnull
    private static String statusKey(@Nonnull String status) {
        return switch (status) {
            case "in_progress" -> "book.achievements.status.in_progress";
            case "unlocked" -> "book.achievements.status.unlocked";
            default -> "book.achievements.status.all";
        };
    }

    private static int appendWideTab(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, int index, @Nonnull Message label, boolean active,
            @Nonnull String value) {
        cmd.append("#ACategoryTabs", WIDE_TAB_TEMPLATE);
        String sel = "#ACategoryTabs[" + index + "]";
        ZigRichButton.text(cmd, sel + " #CatBtn", label);
        ObjectiveBookPage.styleChipActive(cmd, sel + " #CatBtn", active);
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #CatBtn",
                page.fullState("category").append("Id", value), false);
        return index + 1;
    }

    private static int appendSubChip(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, int index, @Nonnull Message label, boolean active,
            @Nonnull String value) {
        cmd.append("#ASubcategoryTabs", ObjectiveBookPage.CAT_TAB_TEMPLATE);
        String sel = "#ASubcategoryTabs[" + index + "]";
        ZigRichButton.text(cmd, sel + " #CatBtn", label);
        ObjectiveBookPage.styleChipActive(cmd, sel + " #CatBtn", active);
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #CatBtn",
                page.fullState("subfilter").append("Id", value), false);
        return index + 1;
    }

    private static int appendBarChip(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull String container, int index,
            @Nonnull Message label, boolean active, @Nonnull String action, @Nonnull String value) {
        cmd.append(container, ObjectiveBookPage.CAT_TAB_TEMPLATE);
        String sel = container + "[" + index + "]";
        ZigRichButton.text(cmd, sel + " #CatBtn", label);
        ObjectiveBookPage.styleChipActive(cmd, sel + " #CatBtn", active);
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #CatBtn",
                page.fullState(action).append("Id", value), false);
        return index + 1;
    }

    // ==================== filtering + ordering ====================

    /** Unlocked / total per category bucket, feats excluded (they have their own section). */
    @Nonnull
    private static Map<String, int[]> categoryCounts(@Nonnull Subject subject,
            @Nonnull AchievementEngine engine) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (Achievement achievement : engine.achievements()) {
            if (!achievement.available() || achievement.featOfStrength()) {
                continue;
            }
            String bucket = bucketOf(achievement);
            int[] pair = out.computeIfAbsent(bucket, k -> new int[]{0, 0});
            pair[1]++;
            if (isUnlocked(engine.status(subject, achievement.id()))) {
                pair[0]++;
            }
        }
        return out;
    }

    /** The category buckets with anything to browse, in taxonomy order then name. */
    @Nonnull
    private static List<String> orderedCategories(@Nonnull Map<String, int[]> counts) {
        List<String> categories = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            // A category whose only content is feats has nothing to browse: no tab.
            if (!entry.getKey().isEmpty() && entry.getValue()[1] > 0) {
                categories.add(entry.getKey());
            }
        }
        categories.sort(Comparator
                .comparingInt((String c) -> ProgressionDefaults.categoryRank(c))
                .thenComparing(String.CASE_INSENSITIVE_ORDER));
        return categories;
    }

    @Nonnull
    private static List<String> subcategoriesOf(@Nonnull AchievementEngine engine,
            @Nonnull String category) {
        List<String> out = new ArrayList<>();
        for (Achievement achievement : engine.achievements()) {
            if (!achievement.available() || achievement.featOfStrength()) {
                continue;
            }
            if (!category.equalsIgnoreCase(bucketOf(achievement))) {
                continue;
            }
            String subcategory = achievement.subcategory();
            if (subcategory != null && !subcategory.isBlank()) {
                boolean known = false;
                for (String existing : out) {
                    if (existing.equalsIgnoreCase(subcategory)) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    out.add(subcategory);
                }
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * The browse list: enabled, visible for this player (a feat only once earned, a hidden one
     * only once earned), through the category / subcategory / search / status filters, ladders
     * collapsed to the rung being climbed, then sorted per the picked mode with pinned rows first.
     */
    @Nonnull
    private static List<Achievement> filteredAchievements(@Nonnull ObjectiveBookPage page,
            @Nonnull Subject subject, @Nonnull AchievementEngine engine, boolean feats) {
        List<Achievement> result = new ArrayList<>();
        String needle = page.searchText().toLowerCase(Locale.ROOT).trim();
        for (Achievement achievement : engine.achievements()) {
            if (!achievement.available()) {
                continue;
            }
            boolean unlocked = isUnlocked(engine.status(subject, achievement.id()));
            if (achievement.featOfStrength() != feats) {
                continue;
            }
            if (achievement.featOfStrength() && !unlocked) {
                continue;
            }
            if (!achievement.featOfStrength() && !unlocked
                    && !engine.isVisible(subject, achievement)) {
                continue;
            }
            if (!ObjectiveBookPage.FILTER_ALL.equalsIgnoreCase(page.filterCategory())
                    && !page.filterCategory().equalsIgnoreCase(bucketOf(achievement))) {
                continue;
            }
            if (!page.filterSubcategory().isEmpty()
                    && !page.filterSubcategory().equalsIgnoreCase(achievement.subcategory())) {
                continue;
            }
            if (!needle.isEmpty() && !matchesSearch(engine, achievement, needle)) {
                continue;
            }
            if ("in_progress".equalsIgnoreCase(page.filterStatus()) && unlocked) {
                continue;
            }
            if ("unlocked".equalsIgnoreCase(page.filterStatus()) && !unlocked) {
                continue;
            }
            result.add(achievement);
        }

        result = collapseChains(result, subject, engine, needle,
                engine.pinned(subject));

        List<String> pinned = engine.pinned(subject);
        Comparator<Achievement> pinnedFirst =
                Comparator.comparingInt(a -> pinned.contains(a.id()) ? 0 : 1);
        // Memoized flattened names: a comparator key extractor re-invokes per comparison.
        Map<String, String> flatNames = new HashMap<>();
        for (Achievement achievement : result) {
            flatNames.put(achievement.id(), UiText.flatten(nameOf(achievement)));
        }
        Comparator<Achievement> byName = Comparator.comparing(
                a -> flatNames.getOrDefault(a.id(), ""), String.CASE_INSENSITIVE_ORDER);
        Comparator<Achievement> order;
        if ("alpha".equals(page.sortMode())) {
            order = pinnedFirst.thenComparing(byName);
        } else if ("progress".equals(page.sortMode())) {
            Map<String, Double> ratios = new HashMap<>();
            for (Achievement achievement : result) {
                ratios.put(achievement.id(),
                        aggregateOf(subject, engine, achievement).fraction());
            }
            order = pinnedFirst
                    .thenComparingInt(a -> isUnlocked(engine.status(subject, a.id())) ? 1 : 0)
                    .thenComparing((a, b) -> Double.compare(
                            ratios.getOrDefault(b.id(), 0.0), ratios.getOrDefault(a.id(), 0.0)))
                    .thenComparing(byName);
        } else {
            order = pinnedFirst
                    .thenComparingInt(a -> isUnlocked(engine.status(subject, a.id())) ? 1 : 0)
                    .thenComparingInt(Achievement::sortOrder)
                    .thenComparing(byName);
        }
        result.sort(order);
        return result;
    }

    /**
     * Collapse each ladder so the list shows only the rung being climbed: the lowest un-earned
     * tier, or the top tier once the whole climb is done. Pinned rungs always stay (the player
     * asked to see them), and a search bypasses the collapse so any rung can be found.
     */
    @Nonnull
    private static List<Achievement> collapseChains(@Nonnull List<Achievement> input,
            @Nonnull Subject subject, @Nonnull AchievementEngine engine, @Nonnull String search,
            @Nonnull List<String> pinned) {
        if (!search.isEmpty()) {
            return input;
        }
        Map<String, List<Achievement>> byChain = new LinkedHashMap<>();
        List<Achievement> out = new ArrayList<>();
        for (Achievement achievement : input) {
            ChainMembership primary = achievement.primaryChain();
            if (primary == null || primary.getId() == null) {
                out.add(achievement);
                continue;
            }
            byChain.computeIfAbsent(primary.getId(), k -> new ArrayList<>()).add(achievement);
        }
        for (List<Achievement> tiers : byChain.values()) {
            tiers.sort(Comparator.comparingInt(BookAchievementsTab::tierOf));
            Achievement active = null;
            for (Achievement tier : tiers) {
                if (!isUnlocked(engine.status(subject, tier.id()))) {
                    active = tier;
                    break;
                }
            }
            if (active == null) {
                active = tiers.get(tiers.size() - 1);
            }
            for (Achievement tier : tiers) {
                if (tier == active || pinned.contains(tier.id())) {
                    out.add(tier);
                }
            }
        }
        return out;
    }

    @Nonnull
    private static List<Achievement> unlockedFeats(@Nonnull ObjectiveBookPage page,
            @Nonnull Subject subject, @Nonnull AchievementEngine engine) {
        List<Achievement> result = new ArrayList<>();
        for (Achievement achievement : engine.achievements()) {
            if (!achievement.available() || !achievement.featOfStrength()) {
                continue;
            }
            if (!isUnlocked(engine.status(subject, achievement.id()))) {
                continue;
            }
            if (!ObjectiveBookPage.FILTER_ALL.equalsIgnoreCase(page.filterCategory())
                    && !page.filterCategory().equalsIgnoreCase(bucketOf(achievement))) {
                continue;
            }
            result.add(achievement);
        }
        Map<String, String> flatNames = new HashMap<>();
        for (Achievement achievement : result) {
            flatNames.put(achievement.id(), UiText.flatten(nameOf(achievement)));
        }
        result.sort(Comparator.comparingInt(Achievement::sortOrder)
                .thenComparing(a -> flatNames.getOrDefault(a.id(), ""),
                        String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Title, description, criterion lines and required-achievement titles, flattened. */
    private static boolean matchesSearch(@Nonnull AchievementEngine engine,
            @Nonnull Achievement achievement, @Nonnull String needle) {
        if (UiText.flatten(nameOf(achievement)).toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        Message flavor = ProgressionTexts.flavor(achievement.id());
        if (flavor != null && UiText.flatten(flavor).toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (int i = 0; i < achievement.criteria().size(); i++) {
            Message line = ProgressionTexts.objective(achievement.id(), Integer.toString(i));
            if (line != null && UiText.flatten(line).toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        for (String childId : achievement.metaChildren()) {
            Achievement child = engine.achievement(childId);
            if (child != null
                    && UiText.flatten(nameOf(child)).toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // ==================== one list row ====================

    /**
     * One compact row. {@code allowPin} is false for feat rows (an earned trophy tracks nothing)
     * and {@code showDate} swaps the status column for the unlock date (the overview's recent
     * block reads better dated).
     */
    private static void paintListRow(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull String sel, @Nonnull Achievement achievement,
            @Nonnull Subject subject, @Nonnull AchievementEngine engine, boolean allowPin,
            boolean showDate) {
        AchievementStatus status = engine.status(subject, achievement.id());
        boolean unlocked = isUnlocked(status);

        cmd.set(sel + " #CategoryStrip.Background", categoryColor(bucketOf(achievement)));
        Message title = nameOf(achievement);
        ZigRichButton.text(cmd, sel + " #Open", title);
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Open",
                page.fullState("select").append("Id", achievement.id()).append("Selector", sel),
                false);
        if (achievement.id().equalsIgnoreCase(page.selectedId() == null ? "" : page.selectedId())) {
            tintRowSelected(cmd, sel, true);
            page.rememberSelectedRow(sel);
        }

        paintIcon(cmd, sel + " #IconSlot", sel + " #Icon", achievement, title);

        cmd.set(sel + " #Points.TextSpans", pointsTag(page, achievement));

        if (achievement.featOfStrength() || !allowPin) {
            cmd.set(sel + " #PinBtn.Visible", false);
        } else {
            boolean pinned = engine.pinned(subject).contains(achievement.id());
            ObjectiveBookPage.paintPinIcon(cmd, sel + " #PinBtn", pinned);
            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #PinBtn",
                    page.fullState("togglepin").append("Id", achievement.id())
                            .append("Selector", sel), false);
        }

        if (unlocked) {
            cmd.set(sel + " #Status.TextSpans", page.text("book.achievements.status.unlocked"));
            cmd.set(sel + " #Status.Style.TextColor", StatusTones.READY.hex());
            cmd.set(sel + " #Date.TextSpans", showDate
                    ? unlockDateMsg(page, engine.unlockedAt(subject, achievement.id()))
                    : Msg.raw(""));
        } else {
            cmd.set(sel + " #Status.TextSpans", page.text("book.achievements.status.in_progress"));
            cmd.set(sel + " #Status.Style.TextColor", StatusTones.IN_PROGRESS.hex());
        }

        Aggregate aggregate = aggregateOf(subject, engine, achievement);
        cmd.set(sel + " #Bar.Value", unlocked ? 1.0 : aggregate.fraction());
        cmd.set(sel + " #BarText.TextSpans", aggregate.text(page, unlocked));

        // The claim call-to-action routes to the detail column, where the reward breakdown is:
        // the row never claims inline.
        if (unlocked && status != AchievementStatus.CLAIMED && !achievement.claimRewards().isEmpty()) {
            cmd.set(sel + " #ClaimRow.Visible", true);
            ZigRichButton.text(cmd, sel + " #ClaimBtn", page.text("book.achievements.claim_button"));
            ObjectiveBookPage.applyGoldClaim(cmd, sel + " #ClaimBtn");
            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #ClaimBtn",
                    page.fullState("select").append("Id", achievement.id()).append("Selector", sel),
                    false);
        }
    }

    /** The selected row's tint swap, shared by the build pass and the partial selection swap. */
    static void tintRowSelected(@Nonnull UICommandBuilder cmd, @Nonnull String sel, boolean selected) {
        String tint = selected ? ObjectiveBookPage.ROW_SELECTED_TINT : ObjectiveBookPage.ROW_TINT;
        cmd.set(sel + ".Background", tint);
        cmd.set(sel + " #Open.Style.Default.Background", tint);
    }

    // ==================== the detail column ====================

    /**
     * Repaint the right panel for a fresh selection or a claim, scroll preserved: clear the
     * appended hosts, hide every optional header, re-append, and bind the fresh chips in the
     * partial update's own event builder.
     */
    static void repaintDetailPartial(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Subject subject,
            @Nonnull AchievementEngine engine, @Nonnull Achievement achievement) {
        cmd.set("#AOverview.Visible", false);
        cmd.set("#ADetail.Visible", true);
        cmd.clear("#DCriteriaList");
        cmd.clear("#DRequiresList");
        cmd.clear("#DPartOfList");
        cmd.clear("#DChainList");
        cmd.clear("#DRewardsList");
        cmd.set("#DCriteriaHeader.Visible", false);
        cmd.set("#DRequiresHeader.Visible", false);
        cmd.set("#DPartOfHeader.Visible", false);
        cmd.set("#DChainHeader.Visible", false);
        cmd.set("#DRewardsHeader.Visible", false);
        cmd.set("#DClaimRow.Visible", false);
        cmd.set("#DFirstClaimed.Visible", false);
        paintDetail(page, cmd, events, subject, engine, achievement);
    }

    private static void paintDetail(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Subject subject,
            @Nonnull AchievementEngine engine, @Nonnull Achievement achievement) {
        AchievementStatus status = engine.status(subject, achievement.id());
        boolean unlocked = isUnlocked(status);
        boolean claimed = status == AchievementStatus.CLAIMED;
        String bucket = bucketOf(achievement);

        // Header: strip, pin, icon, title, the server-first badge, the worth.
        cmd.set("#DStrip.Background", categoryColor(bucket));
        Message title = nameOf(achievement);
        cmd.set("#DTitle.TextSpans", title);
        paintIcon(cmd, "#DIconSlot", "#DIcon", achievement, title);
        cmd.set("#DPoints.TextSpans", pointsTag(page, achievement));

        // The header's pin toggle mirrors the list rows': hidden for a feat (an earned trophy
        // tracks nothing), else painted to the live pin state. The binding is build-time and
        // id-less, so this repaint never re-binds.
        boolean pinnable = !achievement.featOfStrength();
        cmd.set("#DPinBtn.Visible", pinnable);
        if (pinnable) {
            ObjectiveBookPage.paintPinIcon(cmd, "#DPinBtn",
                    engine.pinned(subject).contains(achievement.id()));
        }

        ObjectiveBookDeps.FirstClaim claim = achievement.serverFirst()
                ? page.deps().claimOfGuarded(achievement.id(), page.viewer().getUuid()) : null;
        if (achievement.serverFirst() && (claim == null || claim.self())) {
            cmd.set("#DFirstBadge.TextSpans", page.text("book.achievements.server_first"));
        } else {
            cmd.set("#DFirstBadge.TextSpans", Msg.raw(""));
        }
        if (claim != null) {
            cmd.set("#DFirstClaimed.Visible", true);
            if (claim.self()) {
                cmd.set("#DFirstClaimed.TextSpans", page.text("book.achievements.server_first_self"));
                cmd.set("#DFirstClaimed.Style.TextColor", StatusTones.READY.hex());
            } else {
                // The claimant's name is raw player data, not a localization key.
                cmd.set("#DFirstClaimed.TextSpans",
                        page.text("book.achievements.server_first_other", claim.claimantName()));
                cmd.set("#DFirstClaimed.Style.TextColor", StatusTones.SOFT_BLOCK.hex());
            }
        }

        // Meta line: where it lives, when it was earned, where it stands.
        Message categoryText = categoryDisplayName(page, bucket);
        String subcategory = achievement.subcategory();
        cmd.set("#DCategoryChip.TextSpans", subcategory == null || subcategory.isBlank()
                ? categoryText
                : Msg.cat(categoryText, Msg.raw(" / " + ObjectiveBookDeps.prettifyTag(subcategory))));
        if (unlocked) {
            cmd.set("#DDate.TextSpans", Msg.join(page.text("book.achievements.unlock_date_label"),
                    Msg.raw(": "), unlockDateMsg(page, engine.unlockedAt(subject, achievement.id()))));
            cmd.set("#DStatus.TextSpans", page.text("book.achievements.status.unlocked"));
            cmd.set("#DStatus.Style.TextColor", StatusTones.READY.hex());
        } else {
            cmd.set("#DDate.TextSpans", Msg.raw(""));
            cmd.set("#DStatus.TextSpans", page.text("book.achievements.status.in_progress"));
            cmd.set("#DStatus.Style.TextColor", StatusTones.IN_PROGRESS.hex());
        }

        Message flavor = ProgressionTexts.flavor(achievement.id());
        Message description = flavor;
        // A retired feat carries a "Feat of Strength since {0}" line.
        if (achievement.featOfStrength() && achievement.legacySince() != null) {
            Message featLine = page.text("book.achievements.feat_since", achievement.legacySince());
            description = description == null ? featLine : Msg.join(description, Msg.raw("\n"), featLine);
        }
        cmd.set("#DDescription.TextSpans", description != null ? description : Msg.raw(""));

        Aggregate aggregate = aggregateOf(subject, engine, achievement);
        cmd.set("#DBar.Value", unlocked ? 1.0 : aggregate.fraction());
        cmd.set("#DBarText.TextSpans", aggregate.text(page, unlocked));

        paintCriteria(page, cmd, subject, engine, achievement, unlocked);
        paintRelatives(page, cmd, events, subject, engine, achievement);
        paintRewards(page, cmd, subject, engine, achievement, unlocked, claimed);
    }

    private static void paintCriteria(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull Subject subject, @Nonnull AchievementEngine engine,
            @Nonnull Achievement achievement, boolean unlocked) {
        if (achievement.criteria().size() <= 1 || achievement.isMeta()) {
            return;
        }
        cmd.set("#DCriteriaHeader.Visible", true);
        cmd.set("#DCriteriaHeader.TextSpans", page.text("book.achievements.criteria_header"));
        for (int i = 0; i < achievement.criteria().size(); i++) {
            cmd.append("#DCriteriaList", ACH_CRITERION_TEMPLATE);
            String cSel = "#DCriteriaList[" + i + "]";
            ObjectiveProgressState state = engine.progressOf(subject, achievement, i);
            long required = Math.max(1, state.required());
            long current = unlocked ? required : Math.min(required, state.current());
            boolean met = current >= required;
            cmd.set(cSel + " #StatusTick.TextSpans", Msg.raw(met ? "+" : "-"));
            cmd.set(cSel + " #StatusTick.Style.TextColor",
                    met ? StatusTones.READY.hex() : "#6a7a8e");
            Message line = ProgressionTexts.objective(achievement.id(), Integer.toString(i));
            cmd.set(cSel + " #CritText.TextSpans",
                    line != null ? line : page.text("book.achievements.criterion.untitled"));
            cmd.set(cSel + " #LineIconSlot.Visible", IconRenderer.applyIcon(cmd, cSel,
                    ProgressionIcons.forObjective(achievement.id(), achievement.criteria().get(i))));
            cmd.set(cSel + " #CritText.Style.TextColor", met ? "#b6c9de" : "#96a9be");
            cmd.set(cSel + " #CritProgress.TextSpans", Msg.raw(current + "/" + required));
        }
    }

    /**
     * The related blocks, all folded into the detail column: what a capstone still needs, which
     * capstones this one feeds, and the whole ladder it is a rung of (each rung marked earned /
     * here / ahead, the current rung highlighted).
     */
    private static void paintRelatives(@Nonnull ObjectiveBookPage page,
            @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull Subject subject,
            @Nonnull AchievementEngine engine, @Nonnull Achievement achievement) {
        // The capstone's required list.
        if (achievement.isMeta()) {
            int chipIndex = 0;
            for (String childId : achievement.metaChildren()) {
                Achievement child = engine.achievement(childId);
                if (child == null || !child.available()) {
                    continue;
                }
                boolean childUnlocked = isUnlocked(engine.status(subject, childId));
                if (child.hidden() && !childUnlocked) {
                    continue;
                }
                if (chipIndex == 0) {
                    cmd.set("#DRequiresHeader.Visible", true);
                    cmd.set("#DRequiresHeader.TextSpans",
                            page.text("book.achievements.requires_header"));
                }
                appendChip(page, cmd, events, "#DRequiresList", chipIndex++,
                        Msg.cat(Msg.raw(childUnlocked ? "+ " : "- "), nameOf(child)),
                        childUnlocked ? StatusTones.READY.hex() : null, child.id());
            }
        }

        // The capstones this one is part of.
        int parentIndex = 0;
        for (Achievement parent : engine.achievements()) {
            if (!parent.isMeta() || !parent.available()) {
                continue;
            }
            boolean names = false;
            for (String childId : parent.metaChildren()) {
                if (achievement.id().equalsIgnoreCase(childId)) {
                    names = true;
                    break;
                }
            }
            if (!names) {
                continue;
            }
            if (parentIndex == 0) {
                cmd.set("#DPartOfHeader.Visible", true);
                cmd.set("#DPartOfHeader.TextSpans", page.text("book.achievements.part_of"));
            }
            boolean parentUnlocked = isUnlocked(engine.status(subject, parent.id()));
            appendChip(page, cmd, events, "#DPartOfList", parentIndex++,
                    Msg.cat(Msg.raw(parentUnlocked ? "+ " : "> "), nameOf(parent)),
                    parentUnlocked ? StatusTones.READY.hex() : null, parent.id());
        }

        // The whole ladder, the walked rung marked. (The old separate next-rung block was this
        // list's first un-earned entry, so the ladder alone tells the whole story.)
        ChainMembership primary = achievement.primaryChain();
        if (primary != null && primary.getId() != null) {
            List<Achievement> tiers = new ArrayList<>();
            for (Achievement other : engine.achievements()) {
                ChainMembership otherPrimary = other.primaryChain();
                if (otherPrimary != null && primary.getId().equalsIgnoreCase(otherPrimary.getId())) {
                    tiers.add(other);
                }
            }
            if (tiers.size() > 1) {
                tiers.sort(Comparator.comparingInt(BookAchievementsTab::tierOf));
                cmd.set("#DChainHeader.Visible", true);
                cmd.set("#DChainHeader.TextSpans", page.text("book.achievements.chain_header"));
                for (int i = 0; i < tiers.size(); i++) {
                    Achievement tier = tiers.get(i);
                    boolean tierUnlocked = isUnlocked(engine.status(subject, tier.id()));
                    boolean self = tier.id().equalsIgnoreCase(achievement.id());
                    String marker = tierUnlocked ? "+ " : (self ? "> " : "- ");
                    String color = tierUnlocked ? StatusTones.READY.hex()
                            : (self ? "#ffd24a" : null);
                    appendChip(page, cmd, events, "#DChainList", i,
                            Msg.cat(Msg.raw(marker + "T" + tierOf(tier) + " "), nameOf(tier)),
                            color, tier.id());
                }
            }
        }
    }

    private static void appendChip(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull String container, int index,
            @Nonnull Message label, @Nullable String color, @Nonnull String targetId) {
        cmd.append(container, ACH_CHIP_TEMPLATE);
        String sel = container + "[" + index + "]";
        ZigRichButton.text(cmd, sel + " #ChildBtn", label);
        if (color != null) {
            ZigRichButton.color(cmd, sel + " #ChildBtn", color);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #ChildBtn",
                page.fullState("select").append("Id", targetId), false);
    }

    private static void paintRewards(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull Subject subject, @Nonnull AchievementEngine engine,
            @Nonnull Achievement achievement, boolean unlocked, boolean claimed) {
        List<RewardChip> autoChips =
                RewardChips.chipsFor(achievement.autoRewards(), page.deps().rewardChips());
        List<RewardChip> claimChips =
                RewardChips.chipsFor(achievement.claimRewards(), page.deps().rewardChips());
        if (autoChips.isEmpty() && claimChips.isEmpty()) {
            return;
        }
        cmd.set("#DRewardsHeader.Visible", true);
        cmd.set("#DRewardsHeader.TextSpans", page.text("book.achievements.rewards_header"));

        int index = 0;
        for (RewardChip chip : autoChips) {
            Message tag = page.text(unlocked
                    ? "book.achievements.reward_auto" : "book.achievements.reward_locked");
            String sel = paintRewardChip(cmd, "#DRewardsList", index++, chip, tag);
            cmd.set(sel + " #RewardName.Style.TextColor", unlocked ? "#96a9be" : "#5a6a7c");
        }
        for (RewardChip chip : claimChips) {
            Message tag;
            String color;
            if (claimed) {
                tag = page.text("book.achievements.reward_claimed");
                color = StatusTones.READY.hex();
            } else if (unlocked) {
                tag = page.text("book.achievements.reward_pending");
                color = "#ffd24a";
            } else {
                tag = page.text("book.achievements.reward_locked");
                color = "#5a6a7c";
            }
            String sel = paintRewardChip(cmd, "#DRewardsList", index++, chip, tag);
            cmd.set(sel + " #RewardName.Style.TextColor", color);
        }

        boolean showClaim = unlocked && !claimed && !achievement.claimRewards().isEmpty();
        cmd.set("#DClaimRow.Visible", showClaim);
    }

    /**
     * One reward line: the chip's icon (with the reward's own line as the hover name) and its
     * text, plus an optional state tag in brackets. Returns the row selector so the caller can
     * colour the text. Shared with the quests tab's reward rows.
     */
    @Nonnull
    static String paintRewardChip(@Nonnull UICommandBuilder cmd, @Nonnull String container,
            int index, @Nonnull RewardChip chip, @Nullable Message tag) {
        cmd.append(container, REWARD_ROW_TEMPLATE);
        String sel = container + "[" + index + "]";
        Message label = tag == null ? chip.label()
                : Msg.join(chip.label(), Msg.raw("  ("), tag, Msg.raw(")"));
        cmd.set(sel + " #RewardName.TextSpans", label);
        if (chip.hasIcon()) {
            // The icon's hover name is a String-only engine sink: flatten the chip's own line.
            ItemGridSlot slot = new ItemGridSlot(new ItemStack(chip.iconItemId(), 1));
            String hover = UiText.flatten(chip.label());
            if (!hover.isEmpty()) {
                slot.setName(hover);
            }
            cmd.set(sel + " #RewardIcon.Slots", List.of(slot));
            cmd.set(sel + " #RewardIconSlot.Visible", true);
        }
        return sel;
    }

    // ==================== the overview (nothing selected) ====================

    private static void paintOverview(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Subject subject,
            @Nonnull AchievementEngine engine,
            @Nonnull List<ObjectiveBookDeps.MilestoneView> milestones) {
        // Recent unlocks: the only time-ordered view of what was earned.
        cmd.set("#OvRecentHeader.TextSpans", page.text("book.achievements.overview.recent"));
        List<Achievement> recent = new ArrayList<>();
        Map<String, Long> stamps = new HashMap<>();
        for (Achievement achievement : engine.achievements()) {
            if (!achievement.available()) {
                continue;
            }
            long at = engine.unlockedAt(subject, achievement.id());
            if (at > 0L) {
                recent.add(achievement);
                stamps.put(achievement.id(), at);
            }
        }
        recent.sort((a, b) -> Long.compare(stamps.getOrDefault(b.id(), 0L),
                stamps.getOrDefault(a.id(), 0L)));
        int recentShown = Math.min(recent.size(), MAX_RECENT);
        for (int i = 0; i < recentShown; i++) {
            cmd.append("#RecentList", ACH_ROW_TEMPLATE);
            paintListRow(page, cmd, events, "#RecentList[" + i + "]", recent.get(i), subject,
                    engine, false, true);
        }
        if (recentShown == 0) {
            cmd.set("#RecentEmpty.Visible", true);
            cmd.set("#RecentEmpty.TextSpans", page.text("book.achievements.overview.no_unlocks"));
        }

        // Nearest to complete: cross-category, zero-progress hidden, lost server-firsts excluded.
        cmd.set("#OvNearestHeader.TextSpans", page.text("book.achievements.overview.nearest"));
        List<Achievement> candidates = new ArrayList<>();
        Map<String, Double> ratios = new HashMap<>();
        for (Achievement achievement : engine.achievements()) {
            if (!achievement.available() || achievement.featOfStrength() || achievement.hidden()) {
                continue;
            }
            if (isUnlocked(engine.status(subject, achievement.id()))) {
                continue;
            }
            if (achievement.serverFirst()) {
                ObjectiveBookDeps.FirstClaim claim =
                        page.deps().claimOfGuarded(achievement.id(), page.viewer().getUuid());
                if (claim != null && !claim.self()) {
                    continue;
                }
            }
            double ratio = aggregateOf(subject, engine, achievement).fraction();
            if (ratio <= 0.0) {
                continue;
            }
            candidates.add(achievement);
            ratios.put(achievement.id(), ratio);
        }
        candidates.sort((a, b) -> Double.compare(ratios.getOrDefault(b.id(), 0.0),
                ratios.getOrDefault(a.id(), 0.0)));
        int nearestShown = Math.min(candidates.size(), MAX_NEAREST);
        for (int i = 0; i < nearestShown; i++) {
            cmd.append("#NearestList", ACH_ROW_TEMPLATE);
            paintListRow(page, cmd, events, "#NearestList[" + i + "]", candidates.get(i), subject,
                    engine, false, false);
        }
        if (nearestShown == 0) {
            cmd.set("#NearestEmpty.Visible", true);
            cmd.set("#NearestEmpty.TextSpans", page.text("book.achievements.overview.no_progress"));
        }

        // Pinned: the dedicated block, pins live so one can be dropped from here.
        List<String> pinned = engine.pinned(subject);
        if (!pinned.isEmpty()) {
            cmd.set("#OvPinnedHeader.Visible", true);
            cmd.set("#OvPinnedHeader.TextSpans", page.text("book.achievements.overview.pinned"));
            int index = 0;
            for (String id : pinned) {
                Achievement achievement = engine.achievement(id);
                if (achievement == null || !achievement.available()) {
                    continue;
                }
                cmd.append("#PinnedList", ACH_ROW_TEMPLATE);
                paintListRow(page, cmd, events, "#PinnedList[" + index + "]", achievement, subject,
                        engine, true, isUnlocked(engine.status(subject, id)));
                index++;
            }
        }

        // The category completion grid.
        cmd.set("#OvCategoriesHeader.TextSpans", page.text("book.achievements.overview.categories"));
        Map<String, int[]> counts = categoryCounts(subject, engine);
        List<String> categories = orderedCategories(counts);
        for (int i = 0; i < categories.size(); i++) {
            String category = categories.get(i);
            int[] pair = counts.getOrDefault(category, new int[]{0, 0});
            cmd.append("#CategoriesGrid", ACH_CATEGORY_CARD_TEMPLATE);
            String sel = "#CategoriesGrid[" + i + "]";
            cmd.set(sel + " #CategoryName.TextSpans", categoryDisplayName(page, category));
            cmd.set(sel + " #CategoryStrip.Background", categoryColor(category));
            cmd.set(sel + " #Counts.TextSpans", Msg.raw(pair[0] + "/" + pair[1]));
            cmd.set(sel + " #CategoryProgress.Value",
                    pair[1] == 0 ? 0.0 : (double) pair[0] / (double) pair[1]);
            ZigRichButton.text(cmd, sel + " #BrowseBtn",
                    page.text("book.achievements.overview.browse"));
            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #BrowseBtn",
                    page.fullState("category").append("Id", category), false);
        }

        paintMilestones(page, cmd, events, subject, engine, milestones);
    }

    /** The points-milestone block; hidden whole where the consumer ships no ladder. */
    private static void paintMilestones(@Nonnull ObjectiveBookPage page,
            @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull Subject subject,
            @Nonnull AchievementEngine engine,
            @Nonnull List<ObjectiveBookDeps.MilestoneView> milestones) {
        if (milestones.isEmpty()) {
            return;
        }
        int points = engine.points(subject);
        cmd.set("#OvMilestoneHeader.Visible", true);
        cmd.set("#OvMilestoneHeader.TextSpans", page.text("book.achievements.milestones_header"));
        for (int i = 0; i < milestones.size(); i++) {
            ObjectiveBookDeps.MilestoneView view = milestones.get(i);
            cmd.append("#MilestoneList", MILESTONE_TEMPLATE);
            String sel = "#MilestoneList[" + i + "]";
            cmd.set(sel + " #MilestoneName.TextSpans", view.title());
            cmd.set(sel + " #MilestoneDescription.TextSpans",
                    view.description() != null ? view.description() : Msg.raw(""));

            List<RewardChip> chips = RewardChips.chipsFor(view.rewards(), page.deps().rewardChips());
            if (!chips.isEmpty()) {
                cmd.set(sel + " #MilestoneRewardsHeader.Visible", true);
                cmd.set(sel + " #MilestoneRewardsHeader.TextSpans",
                        page.text("book.achievements.rewards_header"));
                for (int c = 0; c < chips.size(); c++) {
                    paintRewardChip(cmd, sel + " #MilestoneRewardsList", c, chips.get(c), null);
                }
            }

            long current = Math.min(points, view.threshold());
            cmd.set(sel + " #MilestoneProgress.Value",
                    Math.min(1.0, (double) current / Math.max(1, view.threshold())));

            Message statusText;
            if (view.claimed()) {
                statusText = page.text("book.achievements.milestone_claimed");
                cmd.set(sel + " #MilestoneStatus.Style.TextColor", StatusTones.READY.hex());
            } else if (view.unlocked()) {
                statusText = page.text("book.achievements.milestone_available");
                cmd.set(sel + " #MilestoneStatus.Style.TextColor", "#ffd24a");
            } else {
                statusText = Msg.raw(points + " / " + view.threshold());
            }
            cmd.set(sel + " #MilestoneStatus.TextSpans", statusText);

            if (view.claimable()) {
                cmd.set(sel + " #ClaimRow.Visible", true);
                ZigRichButton.text(cmd, sel + " #MilestoneClaimBtn",
                        page.text("book.achievements.milestone_claim"));
                ObjectiveBookPage.applyGoldClaim(cmd, sel + " #MilestoneClaimBtn");
                events.addEventBinding(CustomUIEventBindingType.Activating,
                        sel + " #MilestoneClaimBtn",
                        page.fullState("claim_milestone")
                                .append("Threshold", String.valueOf(view.threshold())), false);
            }
        }
    }

    /**
     * Repaint the overview's milestone block and the header stat after a claim, scroll preserved:
     * clear the card host, re-append, bind the fresh claim buttons in the partial update's own
     * event builder.
     */
    static void repaintMilestonesPartial(@Nonnull ObjectiveBookPage page,
            @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Subject subject, @Nonnull AchievementEngine engine) {
        List<ObjectiveBookDeps.MilestoneView> milestones =
                page.deps().milestonesGuarded(store, ref, subject);
        cmd.clear("#MilestoneList");
        cmd.set("#OvMilestoneHeader.Visible", false);
        paintMilestones(page, cmd, events, subject, engine, milestones);
        paintNextMilestoneStat(page, cmd, engine.points(subject), milestones);
    }

    // ==================== shared readings ====================

    static boolean isUnlocked(@Nonnull AchievementStatus status) {
        return status == AchievementStatus.UNLOCKED || status == AchievementStatus.CLAIMED;
    }

    @Nonnull
    static Message nameOf(@Nonnull Achievement achievement) {
        return ProgressionTexts.titleOrUntitled(achievement.id());
    }

    /** The worth column: the FEAT tag for a feat, "{points} pts" for everything else. */
    @Nonnull
    private static Message pointsTag(@Nonnull ObjectiveBookPage page,
            @Nonnull Achievement achievement) {
        return achievement.featOfStrength()
                ? page.text("book.achievements.feat_tag")
                : page.text("book.achievements.points_tag", achievement.points());
    }

    /**
     * The bucket an achievement files under: the runtime object's own listing first, the folded
     * definition's as the fallback for content whose fold predates the model carrying it.
     */
    @Nonnull
    static String bucketOf(@Nonnull Achievement achievement) {
        String category = achievement.category();
        if (category == null || category.isBlank()) {
            category = ProgressionDefaults.achievementCategory(achievement.id());
        }
        return category == null || category.isBlank() ? "" : category.toLowerCase(Locale.ROOT);
    }

    private static int tierOf(@Nonnull Achievement achievement) {
        ChainMembership primary = achievement.primaryChain();
        return primary == null ? 0 : primary.tierOrZero();
    }

    @Nonnull
    private static Message categoryDisplayName(@Nonnull ObjectiveBookPage page,
            @Nonnull String bucket) {
        if (bucket.isEmpty()) {
            return page.text("book.achievements.category.uncategorised");
        }
        return AchievementGrouping.label(bucket,
                AchievementCategoryConfig.getInstance().category(bucket));
    }

    /** One accent per category, so its strip reads the same on every row and card. */
    @Nonnull
    private static String categoryColor(@Nonnull String bucket) {
        return switch (bucket) {
            case "combat" -> "#ff5a5a";
            case "gathering" -> "#4aff7f";
            case "crafting" -> "#a07fff";
            case "leveling" -> "#ffd24a";
            case "quests" -> "#ff9f4a";
            case "mastery" -> "#4ad8d0";
            default -> StatusTones.SOFT_BLOCK.hex();
        };
    }

    /** The unlock stamp as an ISO date, or the localized unknown mark for a legacy unlock. */
    @Nonnull
    private static Message unlockDateMsg(@Nonnull ObjectiveBookPage page, long epochMillis) {
        if (epochMillis <= 0) {
            return page.text("book.achievements.unlock_date_unknown");
        }
        // An ISO date is locale-neutral data; Locale.ROOT pins ASCII digits and the Gregorian
        // calendar whatever the JVM's default locale is.
        return Msg.raw(new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(epochMillis)));
    }

    /** One progress reading per achievement, shared by the row bar, the detail bar and the sorts. */
    private record Aggregate(double fraction, long current, long required, boolean criteriaStyle) {

        @Nonnull
        Message text(@Nonnull ObjectiveBookPage page, boolean unlocked) {
            long shownCurrent = unlocked ? required : current;
            if (criteriaStyle) {
                return Msg.join(Msg.raw(shownCurrent + "/" + required + " "),
                        page.text("book.achievements.criteria_suffix"));
            }
            return Msg.raw(shownCurrent + "/" + required);
        }
    }

    @Nonnull
    private static Aggregate aggregateOf(@Nonnull Subject subject,
            @Nonnull AchievementEngine engine, @Nonnull Achievement achievement) {
        boolean unlocked = isUnlocked(engine.status(subject, achievement.id()));
        if (achievement.isMeta()) {
            int total = 0;
            int met = 0;
            for (String childId : achievement.metaChildren()) {
                Achievement child = engine.achievement(childId);
                if (child == null || !child.available()) {
                    continue;
                }
                boolean childUnlocked = isUnlocked(engine.status(subject, childId));
                if (child.hidden() && !childUnlocked) {
                    continue;
                }
                total++;
                if (childUnlocked) {
                    met++;
                }
            }
            int shownTotal = Math.max(1, total);
            return new Aggregate((double) met / shownTotal, met, shownTotal, true);
        }
        if (achievement.criteria().size() == 1) {
            ObjectiveProgressState state = engine.progressOf(subject, achievement, 0);
            long required = Math.max(1, state.required());
            long current = unlocked ? required : Math.min(required, state.current());
            return new Aggregate(Math.min(1.0, (double) current / required), current, required, false);
        }
        int total = Math.max(1, achievement.criteria().size());
        int met = 0;
        for (int i = 0; i < achievement.criteria().size(); i++) {
            ObjectiveProgressState state = engine.progressOf(subject, achievement, i);
            if (unlocked || (state.required() > 0 && state.current() >= state.required())) {
                met++;
            }
        }
        return new Aggregate((double) met / total, met, total, true);
    }

    /** The achievement's picture: the model's own, else the folded definition's, else nothing. */
    private static void paintIcon(@Nonnull UICommandBuilder cmd, @Nonnull String slotSel,
            @Nonnull String gridSel, @Nonnull Achievement achievement, @Nonnull Message title) {
        String icon = achievement.icon();
        if (icon == null || icon.isBlank()) {
            icon = ProgressionDefaults.achievementIcon(achievement.id());
        }
        if (icon == null || icon.isBlank()) {
            return;
        }
        // The icon's hover name is a String-only engine sink: flatten the title for it; the
        // VISIBLE name beside it stays a client-resolved Message.
        ItemGridSlot slot = new ItemGridSlot(new ItemStack(icon, 1));
        String hover = UiText.flatten(title);
        if (!hover.isEmpty()) {
            slot.setName(hover);
        }
        cmd.set(gridSel + ".Slots", List.of(slot));
        cmd.set(slotSel + ".Visible", true);
    }
}
