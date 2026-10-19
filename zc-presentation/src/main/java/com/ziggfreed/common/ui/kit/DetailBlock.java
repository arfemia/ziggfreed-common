package com.ziggfreed.common.ui.kit;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One block of a detail page: its section label ("OBJECTIVES"), an optional caption at the end, and its lines.
 * {@code cards} lays the lines out as reward cards in a wrapping grid under the label ({@link TilePainter#rewardCards},
 * a reward chest's page) instead of one line each.
 */
public record DetailBlock(@Nonnull String id, @Nonnull Message label, @Nullable Message meta,
        @Nonnull List<DetailLine> lines, boolean cards) {

    public DetailBlock {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** A block of lines, one each: the common case. */
    public DetailBlock(@Nonnull String id, @Nonnull Message label, @Nullable Message meta,
            @Nonnull List<DetailLine> lines) {
        this(id, label, meta, lines, false);
    }
}
