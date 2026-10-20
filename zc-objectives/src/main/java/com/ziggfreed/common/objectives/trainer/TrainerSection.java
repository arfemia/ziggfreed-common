package com.ziggfreed.common.objectives.trainer;

import java.util.Map;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.page.PagePart;
import com.ziggfreed.common.ui.page.PartPlan;

/**
 * One part of a trainer's page as its plan holds it: the part that draws it, its one kit list section (its heading
 * and a row per entry), its entries by row id (what a press is answered from), and the row painted selected, if it
 * is this part's.
 */
public record TrainerSection(@Nonnull PagePart<?, ?> part, @Nonnull LedgerSection list,
        @Nonnull Map<String, TrainerEntry> entries, @Nullable String selected) implements PartPlan {

    public TrainerSection {
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(list, "list");
        entries = Map.copyOf(entries);
    }

    /** The entry whose row id is {@code rowId}, or null. */
    @Nullable
    public TrainerEntry entry(@Nullable String rowId) {
        return rowId == null ? null : entries.get(rowId);
    }
}
