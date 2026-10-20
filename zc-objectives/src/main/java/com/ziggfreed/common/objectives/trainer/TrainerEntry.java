package com.ziggfreed.common.objectives.trainer;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerRow;

/**
 * One thing a trainer offers, as its page shows it: its row in its part's list (the row's id is the entry's, unique
 * within the part), the reading page shown when it is selected (its Primary action is the button {@code action}
 * answers; a page with no Primary action, or one not enabled, presses nothing), and, for an action that charges,
 * {@code confirm}: the first press only arms it and the button reads this until a second press goes through
 * ({@code commerce/page/ConfirmArm}).
 *
 * <p>Every action is pressed only within the trainer's {@code Reach}, checked once by the page before it arms or
 * acts, unless {@code fromAnywhere}: an action that changes nothing (the Quests part's button, which opens the
 * character's quest list).
 */
public record TrainerEntry(@Nonnull LedgerRow row, @Nonnull DetailView page, @Nullable TrainerAction action,
        @Nullable Message confirm, boolean fromAnywhere) {

    public TrainerEntry {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(page, "page");
    }

    /** An entry whose button acts on the first press, near the trainer. */
    @Nonnull
    public static TrainerEntry of(@Nonnull LedgerRow row, @Nonnull DetailView page, @Nullable TrainerAction action) {
        return new TrainerEntry(row, page, action, null, false);
    }

    /** An entry whose button charges: armed by the first press (its label {@code confirm}), near the trainer. */
    @Nonnull
    public static TrainerEntry confirmed(@Nonnull LedgerRow row, @Nonnull DetailView page,
            @Nullable TrainerAction action, @Nonnull Message confirm) {
        return new TrainerEntry(row, page, action, confirm, false);
    }

    /** An entry whose button changes nothing and so works from anywhere (it opens another screen). */
    @Nonnull
    public static TrainerEntry fromAnywhere(@Nonnull LedgerRow row, @Nonnull DetailView page,
            @Nullable TrainerAction action) {
        return new TrainerEntry(row, page, action, null, true);
    }

    /** The entry's id: its row's. */
    @Nonnull
    public String id() {
        return row.id();
    }
}
