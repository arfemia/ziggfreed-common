package com.ziggfreed.common.ui.kit;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/** One block of a detail page: its section label ("OBJECTIVES"), an optional caption at the end, and its lines. */
public record DetailBlock(@Nonnull String id, @Nonnull Message label, @Nullable Message meta,
        @Nonnull List<DetailLine> lines) {

    public DetailBlock {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
