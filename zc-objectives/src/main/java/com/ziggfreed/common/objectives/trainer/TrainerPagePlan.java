package com.ziggfreed.common.objectives.trainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.page.PagePart;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a trainer's page shows, read once per build: each part its trainer block places (or every registered part)
 * that has something to offer, in order, and the entry read on the right. The page paints it and answers every
 * click from it, never from a recomputed list. Also the one place the page document's ids are spelled.
 */
final class TrainerPagePlan {

    static final String DOCUMENT = "Pages/ZigTrainerPage.ui";
    static final String CLOSE = "#CloseButton";
    static final String NAME = "#TrainerName";
    static final String LEAD = "#TrainerLead";
    static final String SECTIONS = "#Sections";
    static final String PAGE = "#Page";
    static final String PAGE_EMPTY = "#PageEmpty";

    private TrainerPagePlan() {
    }

    /** The entry on the right, and the part it sits in (by name). */
    record Selected(@Nonnull String part, @Nonnull TrainerEntry entry) {

        /** What the entry's confirm is armed under: its part and its id. */
        @Nonnull
        String key() {
            return part + "/" + entry.id();
        }
    }

    /** The parts in order, and the selected entry (null when no part offers anything). */
    record Plan(@Nonnull List<TrainerSection> sections, @Nullable Selected selected) {

        Plan {
            sections = List.copyOf(sections);
        }

        /** The section at {@code index} as a click carries it, or null. */
        @Nullable
        TrainerSection section(@Nullable String index) {
            if (index == null) {
                return null;
            }
            try {
                int i = Integer.parseInt(index.trim());
                return i >= 0 && i < sections.size() ? sections.get(i) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** One part with what it offers here. */
    private record Built(@Nonnull PagePart<?, ?> part, @Nonnull TrainerOffers offers) {
    }

    /**
     * The page at {@code at}, with the entry {@code row} of the part {@code part} selected when it is still offered,
     * else the first entry of the first part.
     */
    @Nonnull
    static Plan of(@Nonnull TrainerAt at, @Nullable String part, @Nullable String row) {
        List<Built> built = new ArrayList<>();
        for (TrainerParts.Placed placed : TrainerParts.placed(at.trainer())) {
            TrainerOffers offers = ask(placed, at);
            if (offers != null && !offers.entries().isEmpty()) {
                built.add(new Built(placed.part(), offers));
            }
        }
        Selected selected = null;
        for (Built b : built) {
            if (b.part().name().equals(part)) {
                for (TrainerEntry entry : b.offers().entries()) {
                    if (entry.id().equals(row)) {
                        selected = new Selected(b.part().name(), entry);
                        break;
                    }
                }
            }
        }
        if (selected == null && !built.isEmpty()) {
            Built first = built.get(0);
            selected = new Selected(first.part().name(), first.offers().entries().get(0));
        }
        List<TrainerSection> sections = new ArrayList<>(built.size());
        for (Built b : built) {
            Map<String, TrainerEntry> entries = new LinkedHashMap<>();
            for (TrainerEntry entry : b.offers().entries()) {
                entries.putIfAbsent(entry.id(), entry);
            }
            String rowSelected = selected != null && selected.part().equals(b.part().name())
                    ? selected.entry().id() : null;
            sections.add(new TrainerSection(b.part(), TrainerParts.list(b.part().name(), b.offers()), entries,
                    rowSelected));
        }
        return new Plan(sections, selected);
    }

    /** What {@code placed}'s source offers at {@code at}; a source that throws costs only its own part. */
    @Nullable
    private static TrainerOffers ask(@Nonnull TrainerParts.Placed placed, @Nonnull TrainerAt at) {
        TrainerSource source = TrainerParts.source(placed.part().name());
        if (source == null) {
            return null;
        }
        try {
            return source.offers(at, placed.entry());
        } catch (Throwable t) {
            SafeLog.warn("[trainer] the trainer page's part '" + placed.part().name() + "' could not be read: "
                    + t.getMessage());
            return null;
        }
    }
}
