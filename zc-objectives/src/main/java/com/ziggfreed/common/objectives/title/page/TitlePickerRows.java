package com.ziggfreed.common.objectives.title.page;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.objectives.title.TitleConfig;

/**
 * What the title picker lists, worked out with nothing but the player's titles and the fold in hand:
 * one toggle row per unlocked title on offer, in picker order, the shown one on, or one note when
 * there is nothing to choose. A plan, because a page cannot be stood up in a unit JVM.
 */
final class TitlePickerRows {

    /** The note row shown when the player has no title on offer (the {@code picker.none} line). */
    static final String NONE_NOTE = "none";

    /** One row: a title's toggle ({@code titleId} set), or a note ({@code noteKey} set). */
    record Row(@Nullable String titleId, @Nullable String noteKey, boolean on) {

        @Nonnull
        static Row title(@Nonnull String titleId, boolean on) {
            return new Row(titleId, null, on);
        }

        @Nonnull
        static Row note(@Nonnull String noteKey) {
            return new Row(null, noteKey, false);
        }
    }

    private TitlePickerRows() {
    }

    @Nonnull
    static List<Row> plan(@Nonnull Collection<String> unlocked, @Nullable String active,
            @Nonnull TitleConfig titles) {
        List<String> offered = titles.listing(unlocked);
        if (offered.isEmpty()) {
            return List.of(Row.note(NONE_NOTE));
        }
        String shown = active == null ? null : active.trim().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>(offered.size());
        for (String id : offered) {
            rows.add(Row.title(id, id.equals(shown)));
        }
        return rows;
    }
}
