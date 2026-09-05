package com.ziggfreed.common.objectives.render;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestCadence;
import com.ziggfreed.common.ui.StatusTones;

/**
 * How often a quest comes round, said the same way on every screen that lists quests: a word in a
 * row's badge slot, so a daily reads as a daily whether the player is standing at the character who
 * hands it out or reading their own log.
 *
 * <p><b>Derived, never authored.</b> The word comes from the quest's own repeat rule through
 * {@link QuestCadence}, the one classification the whole library buckets a repeat with, so a quest
 * cannot say it is weekly in one place and come back daily in another. There is nothing to write on
 * a quest to make this appear and nothing to keep in step: author the {@code Repeat} block and the
 * badge follows it. A one-shot has no badge at all.
 *
 * <p>{@link StatusTones#LIMITED} is the tone, the same purple every other "on a clock, comes back
 * on its own" reading uses.
 *
 * <p>The {@code .ui} contract is one {@code Label}, hidden by default, of a width that fits the
 * longest cadence word any locale ships: a listing row's {@code #RowBadge}, the quest log row's
 * {@code #QuestCadence}. {@link #paint} sets its text, its colour and its visibility on every path,
 * so it is safe to call on a row a partial update is reusing.
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
        };
    }

    /** The colour every cadence badge paints in. */
    @Nonnull
    public static String tone() {
        return StatusTones.LIMITED.hex();
    }

    /**
     * Paint {@code quest}'s cadence onto a row's badge label, or hide the label when the quest is a
     * one-shot.
     *
     * @param labelSelector the badge label's full selector, e.g. {@code "#QuestList[0] #RowBadge"}
     */
    public static void paint(@Nonnull UICommandBuilder cmd, @Nonnull String labelSelector,
            @Nonnull Quest quest) {
        Message label = label(quest.repeat());
        if (label == null) {
            cmd.set(labelSelector + ".Visible", false);
            return;
        }
        cmd.set(labelSelector + ".TextSpans", label);
        cmd.set(labelSelector + ".Style.TextColor", tone());
        cmd.set(labelSelector + ".Visible", true);
    }

    @Nonnull
    private static Message text(@Nonnull String key) {
        return Msg.tr(PREFIX, DOMAIN + key);
    }
}
