package com.ziggfreed.common.ui.kit;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * A whole detail page, as a reader builds it: the header (picture, title, meta, a faint sub-meta line, pills, the
 * toggle), the progress block (with an optional label in place of "current / total"), the lead paragraph, the
 * blocks, the action bar's actions and its hint. {@link DetailPainter} paints it into an {@code @ZigDetailPage}.
 */
public record DetailView(@Nonnull Picture picture, @Nonnull Message title, @Nullable Message meta,
        @Nullable Message subMeta, @Nonnull List<Pill> badges, @Nullable DetailToggle toggle,
        @Nullable Progress progress, @Nullable Message progressLabel, @Nullable Message lead,
        @Nonnull List<DetailBlock> blocks, @Nonnull List<DetailAction> actions, @Nullable Message hint) {

    public DetailView {
        Objects.requireNonNull(title, "title");
        picture = picture == null ? Picture.NONE : picture;
        badges = badges == null ? List.of() : List.copyOf(badges);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** The action in {@code slot}, or null when the page shows none there. */
    @Nullable
    public DetailAction action(@Nonnull ActionSlot slot) {
        for (DetailAction action : actions) {
            if (action.slot() == slot) {
                return action;
            }
        }
        return null;
    }
}
