package com.ziggfreed.common.objectives.authoring;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;

/**
 * Where the quest editor's words resolve: {@code ziggfreedcommon.questeditor.lang}, so a key here drops the
 * {@code ziggfreedcommon.questeditor.} the file name carries.
 */
final class QuestEditorText {

    /** The key family every line here resolves under (the shipped file name, minus {@code .lang}). */
    static final String PREFIX = "ziggfreedcommon.questeditor.";

    private QuestEditorText() {
    }

    /** One of the editor's lines, {@code {0}, {1}...} bound from {@code args}. */
    @Nonnull
    static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, key, args);
    }

    /** The full key of one of the editor's lines, for a field label, a hint or a step name. */
    @Nonnull
    static String key(@Nonnull String key) {
        return PREFIX + key;
    }
}
