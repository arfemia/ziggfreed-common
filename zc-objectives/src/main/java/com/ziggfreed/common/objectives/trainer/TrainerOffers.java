package com.ziggfreed.common.objectives.trainer;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/** What one kind of offer holds at one trainer: its heading in the list, and its entries in order. */
public record TrainerOffers(@Nonnull Message label, @Nonnull List<TrainerEntry> entries) {

    public TrainerOffers {
        Objects.requireNonNull(label, "label");
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
