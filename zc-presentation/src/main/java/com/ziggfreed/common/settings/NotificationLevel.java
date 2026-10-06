package com.ziggfreed.common.settings;

import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.feedback.moment.FeedbackEngine;

/**
 * How chatty a player's quest and achievement notices are: one closed vocabulary of four words, read by
 * every toast whose moment marked itself {@code PlayerLevel}. The grading reads the moment's own values,
 * never its id: a progress tick carries {@code current} and {@code required}, a finish carries
 * {@code finished}, and a mark is the engine's own answer to the authored {@code EveryPercent}. Anything
 * that is not a tick (a completion, a claim, an unlock) shows at every level but {@link #NONE}.
 */
public enum NotificationLevel {

    EVERY_UPDATE("EveryUpdate"),
    MILESTONES("Milestones"),
    FINISHES("Finishes"),
    NONE("None");

    /** What a player who never chose gets on a server whose owner chose nothing. */
    public static final NotificationLevel DEFAULT = EVERY_UPDATE;

    private final String id;

    NotificationLevel(@Nonnull String id) {
        this.id = id;
    }

    /** The word an owner file and a save carry. */
    @Nonnull
    public String id() {
        return id;
    }

    /** The lang key suffix this level's name is filed under: its id lower-cased. */
    @Nonnull
    public String key() {
        return id.toLowerCase(Locale.ROOT);
    }

    /** Every word, in order: the editor's dropdown and the Settings row's entries. */
    @Nonnull
    public static String[] ids() {
        NotificationLevel[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = all[i].id;
        }
        return out;
    }

    /** The level {@code id} names, ignoring case and outer spaces; null for nothing or a word nobody knows. */
    @Nullable
    public static NotificationLevel parse(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String want = id.trim();
        for (NotificationLevel level : values()) {
            if (level.id.equalsIgnoreCase(want)) {
                return level;
            }
        }
        return null;
    }

    /**
     * Does a player at this level see a marked toast whose moment carries {@code args}?
     * {@code crossedMark} is the engine's answer to the authored {@code EveryPercent}: true at a mark,
     * false between marks, null when no mark was authored or the moment reports no progress.
     */
    public boolean allows(@Nonnull Map<String, Object> args, @Nullable Boolean crossedMark) {
        if (this == NONE) {
            return false;
        }
        if (this == EVERY_UPDATE || !isTick(args)) {
            return true;
        }
        if (Boolean.TRUE.equals(args.get(FeedbackEngine.FINISHED_ARG))) {
            return true;
        }
        return this == MILESTONES && Boolean.TRUE.equals(crossedMark);
    }

    private static boolean isTick(@Nonnull Map<String, Object> args) {
        return args.get(FeedbackEngine.CURRENT_ARG) != null && args.get(FeedbackEngine.REQUIRED_ARG) != null;
    }
}
