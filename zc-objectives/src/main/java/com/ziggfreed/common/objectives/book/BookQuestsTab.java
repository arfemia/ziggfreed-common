package com.ziggfreed.common.objectives.book;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.objectives.hud.TrackedQuestPanelRenderer;
import com.ziggfreed.common.objectives.questlist.LockReasons;
import com.ziggfreed.common.objectives.questlist.ProgressionTexts;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestLifecycle;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.StatusTones;
import com.ziggfreed.common.ui.TagColors;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.ZigRichButton;

import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.CAT_TAB_OUTER_WIDTH;
import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.CAT_TAB_TEMPLATE;
import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.MAX_ROWS;
import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.OBJECTIVE_ROW_TEMPLATE;
import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.QUEST_ROW_TEMPLATE;
import static com.ziggfreed.common.objectives.book.ObjectiveBookPage.TAG_CHIP_TEMPLATE;

/**
 * The quests tab of the Objective Book: the full quest log. Category chips (a dropdown once they
 * overflow), search with a native placeholder, a tag dropdown collected BEFORE the tag filter is
 * applied (so it never collapses to one entry), status chips, then one scrolling region carrying
 * the pre-expanded Active section above the browse list. Rows expand in place; every affordance -
 * accept, collect, hand in, abandon, track - answers with a scroll-preserving partial update
 * wherever a partial can tell the truth.
 *
 * <p>Everything here paints; the verbs live on {@link ObjectiveBookPage}. Board-managed quests
 * read through {@link ObjectiveBookDeps.BoardManagedQuests}: hidden unless carried, their plumbing
 * tags swapped for the board's own pills, Accept swapped for the at-the-board hint.
 */
final class BookQuestsTab {

    /** Objective line colours, keyed by the objective's own state within the row. */
    private static final String COLOR_COMPLETE = StatusTones.READY.hex();
    private static final String COLOR_IN_PROGRESS = "#b6c9de";
    private static final String COLOR_NOT_ACCEPTED = "#96a9be";
    private static final String COLOR_LOCKED = "#6a7a8e";

    /** The colour a custom category's strip hashes to, so one category is always one colour. */
    private static final String[] CUSTOM_CATEGORY_COLORS = {
        "#4acfff", "#ff7faa", "#7fff7f", "#ffff4a", "#cf7fff", "#ff7f4a"
    };

    private static final String[] STATUS_FILTERS = {"all", "active", "available", "completed"};

    private BookQuestsTab() {
    }

    // ==================== build ====================

    static void render(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
                       @Nonnull UIEventBuilder events, @Nonnull Subject subject,
                       @Nonnull QuestEngine engine) {
        // Rank each quest's state ONCE before filtering and sorting: a comparator key extractor
        // re-invokes on both operands of every comparison, so asking the engine there would
        // evaluate the whole repeat rule O(n log n) times for answers that do not change mid-sort.
        Map<String, QuestStatus> rankState = new HashMap<>();
        List<Quest> displayQuests = filteredQuests(page, subject, engine, page.filterTag(), rankState);
        List<Quest> preTagQuests = page.filterTag().isEmpty()
                ? displayQuests
                : filteredQuests(page, subject, engine, "", new HashMap<>());

        renderFilterBar(page, cmd, events, subject, engine, preTagQuests);

        List<Quest> activeQuests = new ArrayList<>();
        List<Quest> browseQuests = new ArrayList<>();
        for (Quest quest : displayQuests) {
            QuestStatus status = rankState.getOrDefault(quest.id(), QuestStatus.NOT_STARTED);
            if (status == QuestStatus.ACTIVE || status == QuestStatus.COMPLETED_UNCLAIMED) {
                activeQuests.add(quest);
            } else {
                browseQuests.add(quest);
            }
        }

        // Header stats: how many quests the browse list shows, and the active count over the cap.
        cmd.set("#Stat0Value.TextSpans", Msg.raw(String.valueOf(browseQuests.size())));
        cmd.set("#Stat0Label.TextSpans", page.text("book.quests.count", browseQuests.size()));
        updateHeaderCounts(page, cmd, subject, engine);

        if (!activeQuests.isEmpty()) {
            cmd.set("#ActiveSection.Visible", true);
            cmd.set("#ActiveHeader.TextSpans",
                    page.text("book.quests.active_header", activeQuests.size()));
            int shown = Math.min(activeQuests.size(), MAX_ROWS);
            for (int i = 0; i < shown; i++) {
                cmd.append("#ActiveQuestList", QUEST_ROW_TEMPLATE);
                paintQuestRow(page, cmd, events, "#ActiveQuestList[" + i + "]", activeQuests.get(i),
                        subject, engine, true);
            }
        }

        if (browseQuests.isEmpty() && activeQuests.isEmpty()) {
            cmd.set("#QEmptyMessage.Visible", true);
            cmd.set("#QEmptyMessage.TextSpans", page.text(anyQuestFilterActive(page)
                    ? "book.quests.filter.none" : "book.empty.quests"));
        } else {
            int shown = Math.min(browseQuests.size(), MAX_ROWS);
            for (int i = 0; i < shown; i++) {
                cmd.append("#QuestList", QUEST_ROW_TEMPLATE);
                paintQuestRow(page, cmd, events, "#QuestList[" + i + "]", browseQuests.get(i),
                        subject, engine, false);
            }
        }
    }

    private static boolean anyQuestFilterActive(@Nonnull ObjectiveBookPage page) {
        return !ObjectiveBookPage.FILTER_ALL.equalsIgnoreCase(page.filterCategory())
                || !ObjectiveBookPage.FILTER_ALL.equalsIgnoreCase(page.filterStatus())
                || !page.searchText().isEmpty()
                || !page.filterTag().isEmpty();
    }

    // ==================== filtering + ordering ====================

    /**
     * Every quest the current filters keep, sorted the way the log reads: ready-to-collect first,
     * then carried, then offerable, then waiting, then finished, and inside a state by the
     * authored order and the name. {@code rankState} receives each kept quest's evaluated state.
     */
    @Nonnull
    private static List<Quest> filteredQuests(@Nonnull ObjectiveBookPage page,
            @Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull String tagFilter,
            @Nonnull Map<String, QuestStatus> rankState) {
        List<Quest> result = new ArrayList<>();
        String needle = page.searchText().toLowerCase(Locale.ROOT);
        for (Quest quest : engine.quests()) {
            if (!quest.available()) {
                continue;
            }
            QuestStatus status = engine.status(subject, quest);
            if (status == QuestStatus.NOT_STARTED && !engine.isVisible(subject, quest)) {
                continue;
            }
            // Board-managed quests are listed only while carried or ready to collect; offering or
            // showing them finished is the board's job.
            if (page.deps().managedGuarded(quest)
                    && status != QuestStatus.ACTIVE && status != QuestStatus.COMPLETED_UNCLAIMED) {
                continue;
            }
            if (!ObjectiveBookPage.FILTER_ALL.equalsIgnoreCase(page.filterCategory())
                    && !page.filterCategory().equalsIgnoreCase(quest.category())) {
                continue;
            }
            if (!needle.isEmpty() && !searchHaystack(quest).contains(needle)) {
                continue;
            }
            if (!tagFilter.isEmpty() && !quest.hasTag(tagFilter)) {
                continue;
            }
            if (!matchesStatusFilter(page.filterStatus(), status, subject, engine, quest)) {
                continue;
            }
            rankState.put(quest.id(), status);
            result.add(quest);
        }
        // The flattened name is memoized per quest: a comparator key extractor re-invokes on both
        // operands of every comparison, and flattening a Message tree that often is real work.
        Map<String, String> flatNames = new HashMap<>();
        for (Quest quest : result) {
            flatNames.put(quest.id(), UiText.flatten(nameOf(quest)));
        }
        result.sort(Comparator
                .comparingInt((Quest q) -> stateSortRank(
                        rankState.getOrDefault(q.id(), QuestStatus.NOT_STARTED)))
                .thenComparingInt(Quest::listOrder)
                .thenComparing(q -> flatNames.getOrDefault(q.id(), ""),
                        String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static boolean matchesStatusFilter(@Nonnull String filter, @Nonnull QuestStatus status,
            @Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull Quest quest) {
        return switch (filter.toLowerCase(Locale.ROOT)) {
            case "active" -> status == QuestStatus.ACTIVE || status == QuestStatus.COMPLETED_UNCLAIMED;
            case "available" -> status == QuestStatus.NOT_STARTED
                    && engine.canAccept(subject, quest).allowed();
            case "completed" -> status == QuestStatus.COMPLETED || status == QuestStatus.ON_COOLDOWN;
            default -> true;
        };
    }

    private static int stateSortRank(@Nonnull QuestStatus status) {
        return switch (status) {
            case COMPLETED_UNCLAIMED -> 0;
            case ACTIVE -> 1;
            case NOT_STARTED -> 2;
            case ON_COOLDOWN -> 3;
            case COMPLETED -> 4;
        };
    }

    /**
     * The text a search matches against: the title and flavor FLATTENED to the server's default
     * language plus the raw tags. A String contains-check, not display - display stays
     * client-resolved everywhere.
     */
    @Nonnull
    private static String searchHaystack(@Nonnull Quest quest) {
        StringBuilder hay = new StringBuilder();
        hay.append(UiText.flatten(ProgressionTexts.title(quest.id()))).append('\n');
        hay.append(UiText.flatten(ProgressionTexts.flavor(quest.id()))).append('\n');
        for (String tag : quest.tags()) {
            hay.append(tag).append('\n');
        }
        return hay.toString().toLowerCase(Locale.ROOT);
    }

    // ==================== the filter bar ====================

    private static void renderFilterBar(@Nonnull ObjectiveBookPage page,
            @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull List<Quest> preTagQuests) {
        // Categories come from what this player can SEE, sorted so two mods' content interleaves
        // stably; tags come from the pre-tag-filter result set so the dropdown never collapses to
        // the one tag currently picked.
        List<String> categories = new ArrayList<>();
        for (Quest quest : engine.quests()) {
            if (!quest.available()) {
                continue;
            }
            QuestStatus status = engine.status(subject, quest);
            if (status == QuestStatus.NOT_STARTED && !engine.isVisible(subject, quest)) {
                continue;
            }
            String category = quest.category();
            if (category != null && !containsIgnoreCase(categories, category)) {
                categories.add(category);
            }
        }
        categories.sort(String.CASE_INSENSITIVE_ORDER);

        boolean allActive = ObjectiveBookPage.FILTER_ALL.equalsIgnoreCase(page.filterCategory());
        if (!ObjectiveBookPage.categoryChipsFit(categories.size(), CAT_TAB_OUTER_WIDTH,
                page.stripWidthBudget())) {
            // The rendered chips cannot fit the strip: the native dropdown carries them (its
            // panel scrolls by itself). A dropdown entry is a String-only sink, so labels
            // flatten here.
            cmd.set("#QCategoryDropdown.Visible", true);
            List<DropdownEntryInfo> entries = new ArrayList<>(categories.size() + 1);
            entries.add(SettingsUiUtil.entry(
                    UiText.flatten(page.text("book.quests.filter.all")), ObjectiveBookPage.FILTER_ALL));
            for (String category : categories) {
                entries.add(SettingsUiUtil.entry(
                        UiText.flatten(categoryLabel(page, category)), category));
            }
            SettingsUiUtil.populate(cmd, "#QCategoryDropdown", entries,
                    allActive ? ObjectiveBookPage.FILTER_ALL : page.filterCategory());
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#QCategoryDropdown",
                    page.fullState("category").append("@DropdownValue", "#QCategoryDropdown.Value"),
                    false);
        } else {
            int index = appendChip(page, cmd, events, "#QDynamicCategories", 0,
                    page.text("book.quests.filter.all"), allActive, "category",
                    ObjectiveBookPage.FILTER_ALL);
            for (String category : categories) {
                index = appendChip(page, cmd, events, "#QDynamicCategories", index,
                        categoryLabel(page, category),
                        category.equalsIgnoreCase(page.filterCategory()), "category", category);
            }
        }

        // Search: seed the field, bind the button (the full state already captures the live text).
        cmd.set("#QSearchField.Value", page.searchText());
        ZigRichButton.text(cmd, "#QSearchBtn", page.text("book.quests.filter.search"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#QSearchBtn",
                page.fullState("search"), false);
        if (!page.searchText().isEmpty()) {
            cmd.set("#QClearSearchBtn.Visible", true);
            ZigRichButton.text(cmd, "#QClearSearchBtn", page.text("book.quests.filter.clear"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#QClearSearchBtn",
                    page.fullState("clear_search"), false);
        }

        // The tag dropdown, collected pre-tag-filter; labels ride the consumer's tag reading.
        // With no tag anywhere in the visible set it stays hidden whole - an empty dropdown
        // filtering on nothing is noise, not an affordance. An ACTIVE tag filter always keeps
        // it (and its own tag listed), or there would be no way to clear the filter.
        Set<String> uniqueTags = new LinkedHashSet<>();
        for (Quest quest : preTagQuests) {
            uniqueTags.addAll(quest.tags());
        }
        if (!page.filterTag().isEmpty()) {
            uniqueTags.add(page.filterTag());
        }
        if (!uniqueTags.isEmpty()) {
            cmd.set("#QTagSpacer.Visible", true);
            cmd.set("#QTagFilter.Visible", true);
            List<DropdownEntryInfo> tagEntries = new ArrayList<>(uniqueTags.size() + 1);
            tagEntries.add(SettingsUiUtil.entry(
                    UiText.flatten(page.text("book.quests.filter.all_tags")), ""));
            for (String tag : uniqueTags) {
                tagEntries.add(SettingsUiUtil.entry(
                        UiText.flatten(page.deps().tagLabelGuarded(tag)), tag));
            }
            SettingsUiUtil.populate(cmd, "#QTagFilter", tagEntries,
                    page.filterTag().isEmpty() ? "" : page.filterTag());
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#QTagFilter",
                    page.fullState("tag").append("@DropdownValue", "#QTagFilter.Value"), false);
        }

        // Status chips, appended with the same template the categories use.
        int statusIndex = 0;
        for (String status : STATUS_FILTERS) {
            statusIndex = appendChip(page, cmd, events, "#QStatusBar", statusIndex,
                    statusLabel(page, status), status.equalsIgnoreCase(page.filterStatus()),
                    "status", status);
        }
    }

    /** One filter chip: append the shared template, label it, mark it active, bind the full state. */
    private static int appendChip(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull String container, int index,
            @Nonnull Message label, boolean active, @Nonnull String action, @Nonnull String value) {
        cmd.append(container, CAT_TAB_TEMPLATE);
        String sel = container + "[" + index + "]";
        ZigRichButton.text(cmd, sel + " #CatBtn", label);
        ObjectiveBookPage.styleChipActive(cmd, sel + " #CatBtn", active);
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #CatBtn",
                page.fullState(action).append("Id", value), false);
        return index + 1;
    }

    /**
     * A category's label: the three the library itself keys are localized, anything else renders
     * its authored id prettified - a pack's own category is its own vocabulary.
     */
    @Nonnull
    private static Message categoryLabel(@Nonnull ObjectiveBookPage page, @Nonnull String category) {
        String id = category.toLowerCase(Locale.ROOT);
        if ("main".equals(id) || "daily".equals(id) || "misc".equals(id)) {
            return page.text("book.quests.category." + id);
        }
        return Msg.raw(ObjectiveBookDeps.prettifyTag(category));
    }

    @Nonnull
    private static Message statusLabel(@Nonnull ObjectiveBookPage page, @Nonnull String status) {
        return ObjectiveBookPage.FILTER_ALL.equalsIgnoreCase(status)
                ? page.text("book.quests.filter.all")
                : page.text("book.quests.filter.status." + status.toLowerCase(Locale.ROOT));
    }

    private static boolean containsIgnoreCase(@Nonnull List<String> list, @Nonnull String value) {
        for (String entry : list) {
            if (entry.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    // ==================== one quest row ====================

    private static void paintQuestRow(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull String sel, @Nonnull Quest quest,
            @Nonnull Subject subject, @Nonnull QuestEngine engine, boolean expanded) {
        if (expanded) {
            cmd.set(sel + " #ExpandableContent.Visible", true);
            UiText.setText(cmd, sel + " #ExpandToggle.Text", "-");
            page.expandedQuestIds().add(quest.id());
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #ExpandToggle",
                page.fullState("toggle").append("Id", quest.id()).append("Selector", sel), false);

        QuestStatus status = engine.status(subject, quest);
        boolean managed = page.deps().managedGuarded(quest);

        // Track button - only for carried quests. The glyph is baked in the template; Java only
        // flips the variants and retints the pill.
        if (status == QuestStatus.ACTIVE) {
            boolean tracked = engine.tracked(subject).contains(quest.id());
            cmd.set(sel + " #TrackBtn.Visible", true);
            cmd.set(sel + " #TrackBtnSpacer.Visible", true);
            ObjectiveBookPage.paintPinIcon(cmd, sel + " #TrackBtn", tracked);
            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #TrackBtn",
                    page.fullState("toggletrack").append("Id", quest.id()).append("Selector", sel),
                    false);
        }

        cmd.set(sel + " #CategoryStrip.Background", categoryColor(quest.category()));
        cmd.set(sel + " #QuestName.TextSpans", nameOf(quest));
        Message flavor = ProgressionTexts.flavor(quest.id());
        cmd.set(sel + " #QuestDescription.TextSpans", flavor != null ? flavor : Msg.raw(""));

        // The compact per-row bar: a collapsed carried row still reads how far along it is.
        if (status == QuestStatus.ACTIVE || status == QuestStatus.COMPLETED_UNCLAIMED) {
            paintRowBar(cmd, sel, subject, engine, quest);
        }

        // Tags. A board-managed quest's plumbing tags read as noise: the board's own pills replace
        // them; everything else keeps its raw tag list through the consumer's label reading, and
        // the shared deterministic table colours it.
        List<ObjectiveBookDeps.Pill> pills = managed
                ? page.deps().pillsGuarded(quest) : List.of();
        List<String> displayTags = managed ? List.of() : quest.tags();
        int tagCount = managed ? pills.size() : displayTags.size();
        if (tagCount > 0) {
            cmd.set(sel + " #TagsRow.Visible", true);
            for (int t = 0; t < tagCount; t++) {
                cmd.append(sel + " #TagsRow", TAG_CHIP_TEMPLATE);
                String tagSel = sel + " #TagsRow[" + t + "]";
                if (managed) {
                    ObjectiveBookDeps.Pill pill = pills.get(t);
                    cmd.set(tagSel + " #TagText.TextSpans", pill.label());
                    cmd.set(tagSel + ".Background", pill.background());
                    cmd.set(tagSel + " #TagText.Style.TextColor", pill.textColor());
                } else {
                    String tag = displayTags.get(t);
                    cmd.set(tagSel + " #TagText.TextSpans", page.deps().tagLabelGuarded(tag));
                    TagColors.TagColor color = TagColors.colorOf(tag);
                    cmd.set(tagSel + ".Background", color.background());
                    cmd.set(tagSel + " #TagText.Style.TextColor", color.textColor());
                }
            }
        }

        boolean canAccept = status == QuestStatus.NOT_STARTED
                && engine.canAccept(subject, quest).allowed();

        // Requirements line for a quest not yet taken, coloured by whether it is takeable. The
        // consumer's reading first; a locked quest with no reading still names its best refusal.
        if (status == QuestStatus.NOT_STARTED) {
            Message requirements = page.deps().requirementLineGuarded(quest);
            if (requirements == null && !canAccept) {
                requirements = LockReasons.bestLine(engine.canAccept(subject, quest).reasons());
            }
            if (requirements != null) {
                cmd.set(sel + " #RequirementsLabel.Visible", true);
                cmd.set(sel + " #RequirementsLabel.TextSpans", requirements);
                cmd.set(sel + " #RequirementsLabel.Style.TextColor",
                        canAccept ? StatusTones.READY.hex() : StatusTones.SOFT_BLOCK.hex());
            }
        }

        // Status word + the shared tone.
        Message statusText;
        String statusColor;
        switch (status) {
            case ACTIVE -> {
                statusText = page.text("book.quests.status.active");
                statusColor = StatusTones.IN_PROGRESS.hex();
            }
            case COMPLETED_UNCLAIMED -> {
                statusText = page.text("book.quests.status.unclaimed");
                statusColor = StatusTones.READY.hex();
            }
            case NOT_STARTED -> {
                statusText = page.text(canAccept
                        ? "book.quests.status.available" : "book.quests.status.locked");
                statusColor = canAccept ? StatusTones.AVAILABLE.hex() : StatusTones.SOFT_BLOCK.hex();
            }
            case ON_COOLDOWN -> {
                // The formatted duration is data, passed as a flat param.
                statusText = page.text("book.quests.status.cooldown",
                        QuestLifecycle.formatCooldown(engine.cooldownRemainingMs(subject, quest)));
                statusColor = StatusTones.LIMITED.hex();
            }
            case COMPLETED -> {
                statusText = page.text("book.quests.status.completed");
                statusColor = StatusTones.READY.hex();
            }
            default -> {
                statusText = Msg.raw("");
                statusColor = StatusTones.SOFT_BLOCK.hex();
            }
        }
        cmd.set(sel + " #QuestStatus.TextSpans", statusText);
        cmd.set(sel + " #QuestStatus.Style.TextColor", statusColor);

        // Objectives, for anything the player could work on or read up on.
        if (status == QuestStatus.ACTIVE || status == QuestStatus.COMPLETED_UNCLAIMED
                || status == QuestStatus.NOT_STARTED) {
            paintObjectives(page, cmd, sel, quest, subject, engine, status);
        }

        // Rewards, through the one shared chip reading so a reward cannot read differently here
        // and on the NPC quest page.
        List<RewardChip> chips = RewardChips.chipsFor(quest.rewards(), page.deps().rewardChips());
        if (!chips.isEmpty()) {
            cmd.set(sel + " #RewardsRow.Visible", true);
            cmd.set(sel + " #RewardsHeader.TextSpans", page.text("npcquests.header.rewards"));
            for (int i = 0; i < chips.size(); i++) {
                BookAchievementsTab.paintRewardChip(cmd, sel + " #RewardsList", i, chips.get(i), null);
            }
        }

        // The action row.
        switch (status) {
            case NOT_STARTED -> {
                if (managed) {
                    // Board-accepted only: never offer Accept from the log; point at the board.
                    Message hint = page.deps().acceptHintGuarded(quest);
                    if (hint != null) {
                        cmd.set(sel + " #TurnInHint.Visible", true);
                        cmd.set(sel + " #TurnInHint.TextSpans", hint);
                    }
                } else if (canAccept) {
                    cmd.set(sel + " #ActionBtn.Visible", true);
                    ZigRichButton.text(cmd, sel + " #ActionBtn", page.text("book.quests.btn.accept"));
                    // #ActionBtn is bound to the state-dispatched "primary" action (accept here;
                    // claim when the row is ready to collect), so a completing hand-in can morph
                    // it to a gold Claim in place with no re-bind.
                    events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #ActionBtn",
                            page.fullState("primary").append("Id", quest.id()).append("Selector", sel));
                    // Pre-bind the hidden Abandon so a partial accept can show it with no re-bind.
                    ZigRichButton.text(cmd, sel + " #AbandonBtn", page.text("book.quests.btn.abandon"));
                    events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #AbandonBtn",
                            page.fullState("abandon").append("Id", quest.id()).append("Selector", sel));
                } else if (engine.maxActive() > 0
                        && engine.logSlotsUsed(subject) >= engine.maxActive()) {
                    cmd.set(sel + " #MaxQuestsNote.Visible", true);
                    cmd.set(sel + " #MaxQuestsNote.TextSpans",
                            page.text("book.quests.max_note", engine.maxActive()));
                }
            }
            case ACTIVE -> {
                if (managed) {
                    // A board-managed quest hands in and collects at the board, but CAN be dropped
                    // from the log.
                    Message hint = page.deps().acceptHintGuarded(quest);
                    if (hint != null) {
                        cmd.set(sel + " #TurnInHint.Visible", true);
                        cmd.set(sel + " #TurnInHint.TextSpans", hint);
                    }
                    cmd.set(sel + " #AbandonBtn.Visible", true);
                    ZigRichButton.text(cmd, sel + " #AbandonBtn", page.text("book.quests.btn.abandon"));
                    events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #AbandonBtn",
                            page.fullState("abandon").append("Id", quest.id()).append("Selector", sel));
                    break;
                }
                // Hand in, when a step can be delivered somewhere unlocked.
                ObjectiveDef turnIn = engine.firstActiveTurnIn(subject, quest, null);
                if (turnIn != null) {
                    cmd.set(sel + " #TurnInBtn.Visible", true);
                    cmd.set(sel + " #TurnInSpacer.Visible", true);
                    ZigRichButton.text(cmd, sel + " #TurnInBtn", page.text("book.quests.btn.turn_in"));
                    events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #TurnInBtn",
                            page.fullState("turn_in").append("Id", quest.id())
                                    .append("ObjectiveId", turnIn.id()).append("Selector", sel));
                }
                cmd.set(sel + " #AbandonBtn.Visible", true);
                ZigRichButton.text(cmd, sel + " #AbandonBtn", page.text("book.quests.btn.abandon"));
                events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #AbandonBtn",
                        page.fullState("abandon").append("Id", quest.id()).append("Selector", sel));
                // Pre-bind the hidden primary button so a partial update can show it as Accept
                // (after abandon) or a gold Claim (after a completing hand-in) with no re-bind.
                ZigRichButton.text(cmd, sel + " #ActionBtn", page.text("book.quests.btn.accept"));
                events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #ActionBtn",
                        page.fullState("primary").append("Id", quest.id()).append("Selector", sel));
            }
            case COMPLETED_UNCLAIMED -> {
                cmd.set(sel + " #ActionBtn.Visible", true);
                ZigRichButton.text(cmd, sel + " #ActionBtn", page.text("book.quests.btn.claim"));
                ObjectiveBookPage.applyGoldClaim(cmd, sel + " #ActionBtn");
                events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #ActionBtn",
                        page.fullState("primary").append("Id", quest.id()).append("Selector", sel));
            }
            default -> {
                // COMPLETED / ON_COOLDOWN: no action button.
            }
        }

        if (status == QuestStatus.ON_COOLDOWN) {
            cmd.set(sel + " #CooldownText.Visible", true);
            cmd.set(sel + " #CooldownText.TextSpans", page.text("book.quests.status.cooldown",
                    QuestLifecycle.formatCooldown(engine.cooldownRemainingMs(subject, quest))));
        }

        // A finished quest reads dim: still listed, no longer shouting.
        if (status == QuestStatus.COMPLETED) {
            cmd.set(sel + ".Background", "#141c26");
            cmd.set(sel + " #QuestName.Style.TextColor", "#6a7a8e");
            cmd.set(sel + " #QuestDescription.Style.TextColor", "#4a5568");
        }
    }

    private static void paintRowBar(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
            @Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull Quest quest) {
        QuestEngine.ObjectiveTally tally = engine.tally(subject, quest);
        cmd.set(sel + " #RowBarRow.Visible", true);
        cmd.set(sel + " #RowBar.Value", Math.max(0.0, Math.min(1.0, tally.fraction())));
        cmd.set(sel + " #RowBarText.TextSpans", Msg.raw(tally.completed() + "/" + tally.total()));
    }

    /**
     * The full objective list, order groups included: a step heading opens every order transition
     * past the first, and an objective in a later group renders locked until every earlier group
     * is complete.
     */
    private static void paintObjectives(@Nonnull ObjectiveBookPage page,
            @Nonnull UICommandBuilder cmd, @Nonnull String sel, @Nonnull Quest quest,
            @Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull QuestStatus status) {
        List<ObjectiveDef> objectives = quest.objectives();
        if (objectives.isEmpty()) {
            return;
        }
        cmd.set(sel + " #ObjectivesContainer.Visible", true);

        boolean hasOrderValues = quest.hasOrderedObjectives();
        if (objectives.size() > 1) {
            String hintKey;
            if (hasOrderValues) {
                hintKey = "book.quests.objectives.mixed";
            } else if (quest.sequential()) {
                hintKey = "book.quests.objectives.sequential";
            } else {
                hintKey = "book.quests.objectives.any_order";
            }
            cmd.set(sel + " #ObjectivesHint.Visible", true);
            cmd.set(sel + " #ObjectivesHint.TextSpans", page.text(hintKey));
        }

        Map<String, ObjectiveProgressState> progress =
                status == QuestStatus.ACTIVE || status == QuestStatus.COMPLETED_UNCLAIMED
                        ? engine.progressOf(subject, quest.id()) : null;

        int appendIndex = 0;
        int lastOrder = -1;
        for (ObjectiveDef objective : objectives) {
            if (hasOrderValues && objective.order() > 0 && objective.order() != lastOrder) {
                if (lastOrder > 0) {
                    // A visual step heading between order groups, reusing the one line template.
                    cmd.append(sel + " #ObjectivesContainer", OBJECTIVE_ROW_TEMPLATE);
                    String stepSel = sel + " #ObjectivesContainer[" + appendIndex + "]";
                    cmd.set(stepSel + " #ObjText.TextSpans",
                            page.text("book.quests.objectives.step", objective.order()));
                    cmd.set(stepSel + " #ObjText.Style.TextColor", "#4a6a8e");
                    cmd.set(stepSel + " #ObjText.Style.FontSize", 11);
                    cmd.set(stepSel + " #ObjText.Style.RenderBold", true);
                    cmd.set(stepSel + " #ObjText.Style.LetterSpacing", 1);
                    appendIndex++;
                }
                lastOrder = objective.order();
            }
            cmd.append(sel + " #ObjectivesContainer", OBJECTIVE_ROW_TEMPLATE);
            String objSel = sel + " #ObjectivesContainer[" + appendIndex + "]";
            paintObjectiveLine(page, cmd, objSel, quest, objective, progress,
                    hasOrderValues && objective.order() > 0
                            && !isOrderGroupComplete(objectives, progress, objective.order()));
            appendIndex++;
        }
    }

    /** One objective line: its text plus the running count, coloured by its state. */
    private static void paintObjectiveLine(@Nonnull ObjectiveBookPage page,
            @Nonnull UICommandBuilder cmd, @Nonnull String objSel, @Nonnull Quest quest,
            @Nonnull ObjectiveDef objective,
            @Nullable Map<String, ObjectiveProgressState> progress, boolean locked) {
        Message line = ProgressionTexts.objectiveOrUntitled(quest.id(), objective.id());
        Message text;
        String color;
        if (progress != null) {
            ObjectiveProgressState state = progress.get(objective.id());
            int current = state != null ? state.current() : 0;
            int required = state != null ? state.required() : objective.amountAsInt();
            text = Msg.join(line, Msg.raw("  "), page.text("book.progress", current, required));
            if (required > 0 && current >= required) {
                color = COLOR_COMPLETE;
            } else {
                color = locked ? COLOR_LOCKED : COLOR_IN_PROGRESS;
            }
        } else {
            text = line;
            color = COLOR_NOT_ACCEPTED;
        }
        cmd.set(objSel + " #ObjText.TextSpans", text);
        cmd.set(objSel + " #ObjText.Style.TextColor", color);
    }

    /** Are all objectives in groups BELOW {@code targetOrder} complete? */
    private static boolean isOrderGroupComplete(@Nonnull List<ObjectiveDef> objectives,
            @Nullable Map<String, ObjectiveProgressState> progress, int targetOrder) {
        if (progress == null) {
            return false;
        }
        for (ObjectiveDef objective : objectives) {
            if (objective.order() > 0 && objective.order() < targetOrder) {
                ObjectiveProgressState state = progress.get(objective.id());
                if (state == null || state.required() <= 0 || state.current() < state.required()) {
                    return false;
                }
            }
        }
        return true;
    }

    // ==================== shared readings ====================

    @Nonnull
    static Message nameOf(@Nonnull Quest quest) {
        return ProgressionTexts.titleOrUntitled(quest.id());
    }

    @Nonnull
    private static String categoryColor(@Nullable String category) {
        if (category == null) {
            return StatusTones.SOFT_BLOCK.hex();
        }
        return switch (category.toLowerCase(Locale.ROOT)) {
            case "main" -> "#4a9eff";
            case "daily" -> "#ffaa4a";
            case "misc" -> "#a07fff";
            default -> {
                int hash = Math.abs(category.toLowerCase(Locale.ROOT).hashCode());
                yield CUSTOM_CATEGORY_COLORS[hash % CUSTOM_CATEGORY_COLORS.length];
            }
        };
    }

    /**
     * The library's default requirements reading: the gate's prerequisite quests and permission
     * leaf, comma-joined, {@code AnyOf} alternatives bracketed. A {@code Not} group is
     * deliberately never shown - printing what must NOT be true reads as an instruction to go and
     * do the thing that keeps the quest shut. Null when nothing displayable is asked.
     */
    @Nullable
    static Message genericRequirementLine(@Nonnull Quest quest) {
        GateSpec requires = quest.requires();
        if (requires == null || requires.isEmpty()) {
            return null;
        }
        List<Message> items = new ArrayList<>(clauseItems(requires));
        for (GateClause clause : requires.allOfOrEmpty()) {
            if (clause != null) {
                items.addAll(clauseItems(clause));
            }
        }
        for (GateClause clause : requires.anyOfOrEmpty()) {
            if (clause == null) {
                continue;
            }
            List<Message> alternatives = clauseItems(clause);
            if (alternatives.isEmpty()) {
                continue;
            }
            Message joined = joinWithCommas(alternatives);
            items.add(alternatives.size() > 1
                    ? Msg.cat(Msg.raw("("), joined, Msg.raw(")"))
                    : joined);
        }
        if (items.isEmpty()) {
            return null;
        }
        // The joined list rides as a PARAM, so it is built with the param-safe composite.
        return Msg.key("ziggfreedcommon.progression.book.quests.requires", joinWithCommas(items));
    }

    /** Every displayable requirement ONE clause asks for. */
    @Nonnull
    private static List<Message> clauseItems(@Nonnull GateClause clause) {
        List<Message> items = new ArrayList<>();
        for (String questId : clause.questsOrEmpty()) {
            if (questId == null || questId.isBlank()) {
                continue;
            }
            Message name = ProgressionTexts.title(questId);
            items.add(Msg.key("ziggfreedcommon.progression.book.quests.req.quest",
                    name != null ? name : Msg.raw(questId)));
        }
        String permission = clause.getPermission();
        if (permission != null && !permission.isBlank()) {
            items.add(Msg.key("ziggfreedcommon.progression.book.quests.req.permission", permission));
        }
        return items;
    }

    @Nonnull
    private static Message joinWithCommas(@Nonnull List<Message> parts) {
        List<Message> out = new ArrayList<>(parts.size() * 2);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.add(Msg.raw(", "));
            }
            out.add(parts.get(i));
        }
        return Msg.cat(out.toArray(new Message[0]));
    }

    // ==================== partial updates ====================

    /** Recalculate the active-count header (the browse count only moves on a full repaint). */
    static void updateHeaderCounts(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull Subject subject, @Nonnull QuestEngine engine) {
        int active = engine.activeCount(subject);
        int maxActive = engine.maxActive();
        cmd.set("#Stat1Value.TextSpans",
                Msg.raw(maxActive > 0 ? active + "/" + maxActive : String.valueOf(active)));
        cmd.set("#Stat1Label.TextSpans", page.text("book.quests.active_count", active));
    }

    /**
     * Partial update after an accept: the row reads carried, Accept swaps for the pre-bound
     * Abandon, and the objective rows restart at zero. Scroll preserved.
     */
    static void sendQuestAcceptedUpdate(@Nonnull ObjectiveBookPage page, @Nullable String sel,
            @Nonnull Quest quest, @Nonnull Subject subject, @Nonnull QuestEngine engine) {
        UICommandBuilder cmd = new UICommandBuilder();
        if (sel != null) {
            cmd.set(sel + " #QuestStatus.TextSpans", page.text("book.quests.status.active"));
            cmd.set(sel + " #QuestStatus.Style.TextColor", StatusTones.IN_PROGRESS.hex());
            cmd.set(sel + " #ActionBtn.Visible", false);
            cmd.set(sel + " #AbandonBtn.Visible", true);
            cmd.set(sel + " #RequirementsLabel.Visible", false);
            paintRowBar(cmd, sel, subject, engine, quest);
            repaintRowObjectives(page, cmd, sel, quest, subject, engine);
        }
        updateHeaderCounts(page, cmd, subject, engine);
        TrackedQuestPanelRenderer.render(cmd, null, subject, false, null);
        page.pushUpdate(cmd, new UIEventBuilder());
    }

    /**
     * Partial update after an abandon: the row reads offerable again, Abandon swaps for the
     * pre-bound Accept, the progress decorations drop. Scroll preserved.
     */
    static void sendQuestAbandonedUpdate(@Nonnull ObjectiveBookPage page, @Nullable String sel,
            @Nonnull Subject subject, @Nonnull QuestEngine engine) {
        UICommandBuilder cmd = new UICommandBuilder();
        if (sel != null) {
            cmd.set(sel + " #QuestStatus.TextSpans", page.text("book.quests.status.available"));
            cmd.set(sel + " #QuestStatus.Style.TextColor", StatusTones.AVAILABLE.hex());
            cmd.set(sel + " #ActionBtn.Visible", true);
            cmd.set(sel + " #AbandonBtn.Visible", false);
            cmd.set(sel + " #ObjectivesContainer.Visible", false);
            cmd.set(sel + " #TurnInBtn.Visible", false);
            cmd.set(sel + " #TurnInSpacer.Visible", false);
            cmd.set(sel + " #TrackBtn.Visible", false);
            cmd.set(sel + " #TrackBtnSpacer.Visible", false);
            cmd.set(sel + " #RowBarRow.Visible", false);
        }
        updateHeaderCounts(page, cmd, subject, engine);
        TrackedQuestPanelRenderer.render(cmd, null, subject, false, null);
        page.pushUpdate(cmd, new UIEventBuilder());
    }

    /**
     * Scroll-preserving in-place refresh of a row after a hand-in, keyed on the resulting state:
     * morph to the gold Claim when the quest completed, hide the row when it left the
     * active bucket (it reappears correctly ranked on the next open), else repaint the progress.
     */
    static void refreshTurnedInRow(@Nonnull ObjectiveBookPage page, @Nonnull UICommandBuilder cmd,
            @Nonnull String sel, @Nonnull Quest quest, @Nonnull Subject subject,
            @Nonnull QuestEngine engine) {
        QuestStatus status = engine.status(subject, quest);
        switch (status) {
            case COMPLETED_UNCLAIMED -> {
                // Hand in morphs to Claim in place (#ActionBtn is already "primary"-bound). Match
                // a fresh ready-to-collect row: only the gold Claim shows.
                cmd.set(sel + " #TurnInBtn.Visible", false);
                cmd.set(sel + " #TurnInSpacer.Visible", false);
                cmd.set(sel + " #AbandonBtn.Visible", false);
                cmd.set(sel + " #TrackBtn.Visible", false);
                cmd.set(sel + " #TrackBtnSpacer.Visible", false);
                cmd.set(sel + " #ActionBtn.Visible", true);
                ZigRichButton.text(cmd, sel + " #ActionBtn", page.text("book.quests.btn.claim"));
                ObjectiveBookPage.applyGoldClaim(cmd, sel + " #ActionBtn");
                cmd.set(sel + " #QuestStatus.TextSpans", page.text("book.quests.status.unclaimed"));
                cmd.set(sel + " #QuestStatus.Style.TextColor", StatusTones.READY.hex());
                paintRowBar(cmd, sel, subject, engine, quest);
                repaintRowObjectives(page, cmd, sel, quest, subject, engine);
            }
            case ACTIVE -> {
                // A partial multi-item hand-in: repaint the progress; drop the button once the
                // hand-in step is satisfied.
                paintRowBar(cmd, sel, subject, engine, quest);
                repaintRowObjectives(page, cmd, sel, quest, subject, engine);
                if (engine.firstActiveTurnIn(subject, quest, null) == null) {
                    cmd.set(sel + " #TurnInBtn.Visible", false);
                    cmd.set(sel + " #TurnInSpacer.Visible", false);
                }
            }
            default -> {
                // The row left the active bucket. Hide it - hide, NOT remove, so sibling
                // #ActiveQuestList[i] selectors do not drift.
                cmd.set(sel + ".Visible", false);
            }
        }
    }

    /**
     * Repaint a row's objective lines in place using the naive 0..N index the partial-accept path
     * uses. Order-grouped quests inserted step headings at build, which shift this index, so those
     * repaint cosmetically off until the next open - accepted imprecision a full repaint corrects.
     */
    private static void repaintRowObjectives(@Nonnull ObjectiveBookPage page,
            @Nonnull UICommandBuilder cmd, @Nonnull String sel, @Nonnull Quest quest,
            @Nonnull Subject subject, @Nonnull QuestEngine engine) {
        Map<String, ObjectiveProgressState> progress = engine.progressOf(subject, quest.id());
        List<ObjectiveDef> objectives = quest.objectives();
        for (int i = 0; i < objectives.size(); i++) {
            paintObjectiveLine(page, cmd, sel + " #ObjectivesContainer[" + i + "]", quest,
                    objectives.get(i), progress, false);
        }
    }

}
