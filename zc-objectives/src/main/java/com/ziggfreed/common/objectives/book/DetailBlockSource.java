package com.ziggfreed.common.objectives.book;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.DetailBlock;

/**
 * Extra blocks a consumer adds to a quest's or an achievement's page, after the book's own (a mod's own
 * reading of a reward, a lore note). A block is data the book paints with the kit, so a consumer never ships a
 * document for the book. Filled through {@link ObjectiveBookDeps.Builder#detailBlocks}; read only through the
 * deps' guarded reads, so a source that throws or answers null adds nothing. Each method defaults to none, so a
 * source says only what it knows.
 */
public interface DetailBlockSource {

    /** No extra blocks anywhere. */
    DetailBlockSource NONE = new DetailBlockSource() {
    };

    /** The blocks after a quest's own, as {@code subject} reads them. */
    @Nonnull
    default List<DetailBlock> quest(@Nonnull Quest quest, @Nullable Subject subject) {
        return List.of();
    }

    /** The blocks after an achievement's own, as {@code subject} reads them. */
    @Nonnull
    default List<DetailBlock> achievement(@Nonnull Achievement achievement, @Nullable Subject subject) {
        return List.of();
    }
}
