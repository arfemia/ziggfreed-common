package com.ziggfreed.common.objectives.questlist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.objectives.journal.QuestReader;
import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Entry;
import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Section;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;

/**
 * What {@link ZigNpcQuestPage} lists and opens on, decided with no page behind it so every rule is assertable: which
 * list a routed quest opens, the list as the kit's sectioned ledger (sections in the order a player can act on them,
 * the routed quest leading in its own open section), which quest the page opens on, which sections show their rows,
 * and the quest's page itself, which is the book's page with the character's buttons.
 *
 * <p>It also names every id the page addresses in {@code Pages/ZigNpcQuestPage.ui}, so the document test holds the
 * two together: the page sets nothing else, and never a kit template's child ids (the kit's painters own those).
 */
final class NpcQuestPagePlan {

    /** The list of what this character is holding out; also the default. */
    static final String TAB_HERE = "here";

    /** The list of what the player is carrying, wherever it came from. */
    static final String TAB_MINE = "mine";

    /** The page's document. */
    static final String DOCUMENT = "Pages/ZigNpcQuestPage.ui";

    /** The frame's close button. */
    static final String CLOSE = "#CloseButton";

    /** The bordered list panel a consumer's theme repaints. */
    static final String FRAME_PANEL = "#LeftPanel";

    /** The character's name. */
    static final String HEADER = "#NpcHeader";

    /** "Quests: N". */
    static final String COUNT = "#QuestCount";

    /** The Here segment. */
    static final String SEGMENT_HERE = "#TabHere";

    /** The Mine segment. */
    static final String SEGMENT_MINE = "#TabMine";

    /** The list the ledger painter fills. */
    static final String LIST = "#QuestList";

    /** The quest's page ({@code @ZigDetailPage}). */
    static final String PAGE = "#Page";

    /** The empty state shown in the page's place when a list is empty ({@code @ZigEmptyState}). */
    static final String PAGE_EMPTY = "#PageEmpty";

    /**
     * The sections a player acts on, open by default: a reward to take, a step to hand over, what is carried, what
     * can be taken, what waits to be collected elsewhere. What comes back on its own, what a gate refuses and what is
     * finished stay closed until asked, as the book's Not yet, Waiting and Completed do.
     */
    static final Set<Section> OPEN_BY_DEFAULT = Collections.unmodifiableSet(
            EnumSet.of(Section.READY, Section.TURN_IN, Section.ACTIVE, Section.AVAILABLE, Section.PARKED));

    private NpcQuestPagePlan() {
    }

    // ==================== the list ====================

    /**
     * Which list the page shows: the one asked for (an unknown name reads as Here), except that a routed quest only
     * the player's own list holds opens that list, so the quest a conversation routed here is reachable.
     */
    @Nonnull
    static String tab(@Nullable String requested, @Nullable String highlight, @Nonnull List<Quest> here,
            @Nonnull List<Quest> mine) {
        String tab = TAB_MINE.equals(requested) ? TAB_MINE : TAB_HERE;
        if (TAB_HERE.equals(tab) && highlight != null && !contains(here, highlight) && contains(mine, highlight)) {
            return TAB_MINE;
        }
        return tab;
    }

    /** The quests on {@code tab}: what the character lists (Here), or everything the player carries (Mine). */
    @Nonnull
    static List<Quest> quests(@Nonnull String tab, @Nonnull CharacterQuestListing listing, @Nonnull QuestEngine engine,
            @Nonnull Subject subject) {
        return TAB_MINE.equals(tab) ? engine.activeAndUnclaimed(subject) : listing.questsHere();
    }

    /** Each quest with the section it sits in at this character, the routed one marked, in the list's order. */
    @Nonnull
    static List<Entry> entries(@Nonnull List<Quest> quests, @Nonnull Function<Quest, Section> sectionOf,
            @Nullable String highlight) {
        List<Entry> entries = new ArrayList<>();
        for (Quest quest : quests) {
            entries.add(Entry.of(quest.id(), sectionOf.apply(quest), quest.id().equals(highlight)));
        }
        return sort(entries);
    }

    /** {@link NpcQuestSections#sort}: the routed quest first, then by section, then by order and id. */
    @Nonnull
    static List<Entry> sort(@Nonnull List<Entry> entries) {
        return NpcQuestSections.sort(entries);
    }

    /**
     * The list as the kit's ledger: one section per section the entries use, the routed quest's section first with
     * the routed quest its first row (there is no scroll-to on a page, so the first row IS "take me to it", and its
     * section's head still tells the truth about it), the rest in {@link Section} order. An entry {@code rows} cannot
     * read (a quest gone from the catalogue) is left out.
     *
     * @param entries in the list's order ({@link #entries})
     * @param caps    how many rows each section shows before "Show N more", by section id; absent is the kit's cap
     */
    @Nonnull
    static LedgerModel model(@Nonnull List<Entry> entries, @Nonnull Function<String, LedgerRow> rows,
            @Nonnull Function<Section, Message> labels, @Nonnull Map<String, Integer> caps) {
        Map<Section, List<LedgerRow>> grouped = new LinkedHashMap<>();
        Section routed = null;
        for (Entry entry : entries) {
            LedgerRow row = rows.apply(entry.questId());
            if (row == null) {
                continue;
            }
            grouped.computeIfAbsent(entry.section(), section -> new ArrayList<>()).add(row);
            if (entry.highlighted()) {
                routed = entry.section();
            }
        }
        List<LedgerSection> sections = new ArrayList<>();
        for (Map.Entry<Section, List<LedgerRow>> group : grouped.entrySet()) {
            Section section = group.getKey();
            String id = sectionId(section);
            Integer cap = caps.get(id);
            sections.add(new LedgerSection(id, labels.apply(section), group.getValue(),
                    OPEN_BY_DEFAULT.contains(section) || section == routed,
                    cap == null ? LedgerSection.DEFAULT_CAP : cap));
        }
        return LedgerModel.of(sections);
    }

    /** {@code caps} with one more page of rows shown in {@code sectionId}. */
    @Nonnull
    static Map<String, Integer> showMore(@Nonnull Map<String, Integer> caps, @Nonnull String sectionId) {
        Map<String, Integer> next = new HashMap<>(caps);
        next.merge(sectionId, 2 * LedgerSection.DEFAULT_CAP,
                (shown, ignored) -> shown + LedgerSection.DEFAULT_CAP);
        return next;
    }

    /**
     * Which quest the page opens on: the routed one when it is on this list, else the one already selected while it
     * is still on it, else the first row of the first open section, else nothing. A routed quest beats a stale
     * selection, or a hand-in routed from a conversation opens on whatever was last clicked; a surviving selection
     * beats the first row, or every refresh after an action jumps back to the top.
     */
    @Nullable
    static String select(@Nonnull LedgerModel model, @Nullable String highlight, @Nullable String current) {
        if (highlight != null && model.contains(highlight)) {
            return highlight;
        }
        if (current != null && model.contains(current)) {
            return current;
        }
        return model.firstSelectable();
    }

    /**
     * The open and closed sections to paint with: the player's own answers, plus the selected quest's section opened
     * when the player said nothing about it, so the page never reads a quest the list hides.
     */
    @Nonnull
    static Set<String> openSections(@Nonnull LedgerModel model, @Nonnull Set<String> viewer,
            @Nullable String selected) {
        String holding = sectionHolding(model, selected);
        if (holding == null || viewer.contains(holding) || viewer.contains(LedgerPainter.CLOSED + holding)) {
            return viewer;
        }
        for (LedgerSection section : model.sections()) {
            if (section.id().equals(holding) && !section.openByDefault()) {
                return LedgerPainter.withSection(viewer, holding, true);
            }
        }
        return viewer;
    }

    /** The id of the section holding {@code rowId}, or null. */
    @Nullable
    static String sectionHolding(@Nonnull LedgerModel model, @Nullable String rowId) {
        if (rowId == null) {
            return null;
        }
        for (LedgerSection section : model.sections()) {
            for (LedgerRow row : section.rows()) {
                if (row.id().equals(rowId)) {
                    return section.id();
                }
            }
        }
        return null;
    }

    /** The section a section id names, or null. */
    @Nullable
    static Section sectionNamed(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (Section section : Section.values()) {
            if (sectionId(section).equals(id)) {
                return section;
            }
        }
        return null;
    }

    /** A section's id in the ledger and in event data. */
    @Nonnull
    static String sectionId(@Nonnull Section section) {
        return section.name().toLowerCase(Locale.ROOT);
    }

    /** How many quests the list holds. */
    static int rowCount(@Nonnull LedgerModel model) {
        int count = 0;
        for (LedgerSection section : model.sections()) {
            count += section.rows().size();
        }
        return count;
    }

    // ==================== the page ====================

    /**
     * Where the page's buttons are read: at the character the page was opened on, or, with nobody in front of the
     * player (the page opened as their own list), by the book's own rules, since there is no place to read them at.
     */
    @Nullable
    static CharacterQuestListing place(@Nullable String npcId, @Nonnull CharacterQuestListing listing) {
        return npcId == null ? null : listing;
    }

    /** The quest's page: the book's page, with the buttons and hint {@code here} gives it. */
    @Nonnull
    static DetailView page(@Nonnull QuestReader reader, @Nonnull Quest quest, @Nullable CharacterQuestListing here) {
        return reader.page(quest, here);
    }

    private static boolean contains(@Nonnull List<Quest> quests, @Nonnull String questId) {
        for (Quest quest : quests) {
            if (quest.id().equals(questId)) {
                return true;
            }
        }
        return false;
    }
}
