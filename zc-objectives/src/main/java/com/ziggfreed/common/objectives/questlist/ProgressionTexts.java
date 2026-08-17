package com.ziggfreed.common.objectives.questlist;

import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTextSource;
import com.ziggfreed.common.util.SafeLog;

/**
 * What the shared catalogue's content is CALLED, asked of every registered text source in order,
 * first non-null winning.
 *
 * <p>A merged catalogue is exactly where this matters. Whoever folded an entry kept its words, so a
 * surface reading one mod's own text would render the rest of the list blank - which is the failure
 * mode this exists to make impossible for any surface, not just the one that noticed it first.
 *
 * <p>Every source is individually guarded: one mod's broken naming costs its own rows and nobody
 * else's.
 */
public final class ProgressionTexts {

    /** This library's own lang namespace plus the domain file the placeholder lines live in. */
    private static final String PREFIX = "ziggfreedcommon.";
    private static final String DOMAIN = "progression.";

    private ProgressionTexts() {
    }

    /**
     * {@link #title}, or the book's own placeholder line when no source knows - for a surface that
     * has to show SOMETHING in the slot and would otherwise be a blank row a player cannot act on.
     */
    @Nonnull
    public static Message titleOrUntitled(@Nonnull String contentId) {
        Message answer = title(contentId);
        return answer != null ? answer : Msg.tr(PREFIX, DOMAIN + "book.quests.untitled");
    }

    /** {@link #objective}, or the book's own placeholder step line when no source knows. */
    @Nonnull
    public static Message objectiveOrUntitled(@Nonnull String contentId, @Nonnull String objectiveId) {
        Message answer = objective(contentId, objectiveId);
        return answer != null ? answer : Msg.tr(PREFIX, DOMAIN + "book.quests.step.untitled");
    }

    /** What this content is called, or null when no source knows. */
    @Nullable
    public static Message title(@Nonnull String contentId) {
        return ask(source -> source.title(contentId));
    }

    /** The line under the title, or null when there is none. */
    @Nullable
    public static Message flavor(@Nonnull String contentId) {
        return ask(source -> source.flavor(contentId));
    }

    /** What one step of this content reads as, or null when no source knows. */
    @Nullable
    public static Message objective(@Nonnull String contentId, @Nonnull String objectiveId) {
        return ask(source -> source.objective(contentId, objectiveId));
    }

    /**
     * The narrative this content reads with in {@code state}, or null when no source has one - which
     * is the common case, and the caller's cue to fall back to {@link #flavor}.
     */
    @Nullable
    public static Message lore(@Nonnull String contentId, @Nonnull String state) {
        return ask(source -> source.lore(contentId, state));
    }

    @Nullable
    private static Message ask(@Nonnull Function<ProgressionTextSource, Message> question) {
        for (ProgressionTextSource source : ProgressionRuntime.textSources()) {
            try {
                Message answer = question.apply(source);
                if (answer != null) {
                    return answer;
                }
            } catch (Throwable t) {
                SafeLog.warn("[progression] a text source failed while naming content: " + t.getMessage());
            }
        }
        return null;
    }
}
