package com.ziggfreed.common.objectives.book.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;

/**
 * How the journal tab plans one screen from the book's state and the reader's model, pure so the rules a player
 * notices are assertable: which row is selected, which sections show their rows, and how far Show more reaches.
 *
 * <p><b>Selection.</b> The state's selection while that quest is still listed, so Accept, Abandon and Collect land
 * back on the same quest in whatever section it moved to; otherwise the first row of the first open section.
 *
 * <p><b>The moved-row rule.</b> A verb that moves the selected quest into a section closed by default (Collect into
 * Completed, a daily into Waiting) opens that section, so the selected row is on screen where it went; the player's
 * own word on the section (closed by hand) still wins.
 */
final class QuestJournalPlan {

    private QuestJournalPlan() {
    }

    /** The row the page shows: {@code selectedId} while it is listed, else the first row of the first open section. */
    @Nullable
    static String selection(@Nonnull LedgerModel model, @Nonnull Set<String> openSections,
            @Nullable String selectedId) {
        if (model.contains(selectedId)) {
            return selectedId;
        }
        for (LedgerSection section : model.sections()) {
            if (!section.rows().isEmpty() && LedgerPainter.isOpen(section, openSections)) {
                return section.rows().get(0).id();
            }
        }
        return model.firstSelectable();
    }

    /**
     * The sections that show their rows, in the kit's form ({@link LedgerPainter#withSection}): the player's own
     * words, plus the selected row's section opened when it would otherwise be closed and the player never closed it
     * by hand.
     */
    @Nonnull
    static Set<String> openSections(@Nonnull LedgerModel model, @Nonnull Set<String> openSections,
            @Nullable String selectedId) {
        LedgerSection holding = sectionOf(model, selectedId);
        if (holding == null || LedgerPainter.isOpen(holding, openSections)
                || openSections.contains(LedgerPainter.CLOSED + holding.id())) {
            return openSections;
        }
        return LedgerPainter.withSection(openSections, holding.id(), true);
    }

    /** The section holding {@code rowId}, or null. */
    @Nullable
    static LedgerSection sectionOf(@Nonnull LedgerModel model, @Nullable String rowId) {
        if (rowId == null) {
            return null;
        }
        for (LedgerSection section : model.sections()) {
            for (LedgerRow row : section.rows()) {
                if (row.id().equals(rowId)) {
                    return section;
                }
            }
        }
        return null;
    }

    /** The section with {@code id}, or null. */
    @Nullable
    static LedgerSection section(@Nonnull LedgerModel model, @Nullable String id) {
        for (LedgerSection section : model.sections()) {
            if (section.id().equals(id)) {
                return section;
            }
        }
        return null;
    }

    /** {@code model} with each section in {@code caps} showing that many rows before "Show N more". */
    @Nonnull
    static LedgerModel withCaps(@Nonnull LedgerModel model, @Nonnull Map<String, Integer> caps) {
        if (caps.isEmpty()) {
            return model;
        }
        List<LedgerSection> sections = new ArrayList<>();
        for (LedgerSection section : model.sections()) {
            Integer cap = caps.get(section.id());
            sections.add(cap == null ? section
                    : new LedgerSection(section.id(), section.label(), section.rows(), section.openByDefault(), cap));
        }
        return new LedgerModel(sections, model.firstSelectable());
    }
}
