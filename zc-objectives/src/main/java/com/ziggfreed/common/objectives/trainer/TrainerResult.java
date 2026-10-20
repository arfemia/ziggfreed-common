package com.ziggfreed.common.objectives.trainer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * What a press did, for the page to show: a toast over the page (or none), and whether another screen took the
 * page's place (the quest list a quest's button opens), in which case the trainer page says nothing more.
 */
public record TrainerResult(@Nullable ToastSpec toast, boolean pageTaken) {

    /** Nothing to say: the page repaints as it now reads. */
    public static final TrainerResult NONE = new TrainerResult(null, false);

    /** Another screen opened in the page's place. */
    public static final TrainerResult TAKEN = new TrainerResult(null, true);

    /** A toast of {@code kind} saying {@code line}, over the repainted page. */
    @Nonnull
    public static TrainerResult toast(@Nonnull ToastKind kind, @Nonnull Message line) {
        return new TrainerResult(ToastSpec.of(kind, line), false);
    }
}
