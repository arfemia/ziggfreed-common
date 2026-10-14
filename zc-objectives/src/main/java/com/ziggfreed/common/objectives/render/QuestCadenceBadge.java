package com.ziggfreed.common.objectives.render;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestCadence;

/**
 * How often a quest comes round, said the same way on every screen that lists quests, so a daily
 * reads as a daily whether the player is standing at the character who hands it out or reading their
 * own log.
 *
 * <p><b>Derived, never authored.</b> The word comes from the quest's own repeat rule through
 * {@link QuestCadence}, the one classification the whole library buckets a repeat with, so a quest
 * cannot say it is weekly in one place and come back daily in another. There is nothing to write on
 * a quest to make this appear and nothing to keep in step: author the {@code Repeat} block and the
 * word follows it. A one-shot has none at all.
 */
public final class QuestCadenceBadge {

    private static final String PREFIX = "ziggfreedcommon.";
    private static final String DOMAIN = "progression.";

    private QuestCadenceBadge() {
        // static renderer
    }

    /** The cadence word for {@code repeat}, or null for a one-shot (a null rule). */
    @Nullable
    public static Message label(@Nullable Quest.Repeat repeat) {
        return switch (QuestCadence.of(repeat)) {
            case NONE -> null;
            case REPEATABLE -> text("quest.cadence.repeatable");
            case DAILY -> text("quest.cadence.daily");
            case WEEKLY -> text("quest.cadence.weekly");
            case SEASONAL -> text("quest.cadence.seasonal");
        };
    }

    @Nonnull
    private static Message text(@Nonnull String key) {
        return Msg.tr(PREFIX, DOMAIN + key);
    }
}
