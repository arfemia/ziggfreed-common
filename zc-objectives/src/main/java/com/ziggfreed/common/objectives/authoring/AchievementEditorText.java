package com.ziggfreed.common.objectives.authoring;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * Where the achievement editor's words resolve: {@code ziggfreedcommon.achievementeditor.lang}, so a key here drops
 * the {@code ziggfreedcommon.achievementeditor.} the file name carries. The criterion and reward pages read the same
 * key names the quest editor's do ({@link ObjectiveSlotPages}, {@link RewardSlotPages}), worded for an achievement.
 */
final class AchievementEditorText {

    /** The key family every line here resolves under (the shipped file name, minus {@code .lang}). */
    static final String PREFIX = "ziggfreedcommon.achievementeditor.";

    /** The editor's words and the rows labelled from them. */
    static final EditorWords WORDS = new EditorWords(PREFIX);

    private AchievementEditorText() {
    }

    /** One of the editor's lines, {@code {0}, {1}...} bound from {@code args}. */
    @Nonnull
    static Message line(@Nonnull String key, @Nonnull Object... args) {
        return WORDS.line(key, args);
    }

    /** The full key of one of the editor's lines. */
    @Nonnull
    static String key(@Nonnull String key) {
        return WORDS.key(key);
    }
}
