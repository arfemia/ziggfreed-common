package com.ziggfreed.common.objectives.command;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;

/**
 * The two content arguments most verbs here take, resolved against the shared catalogue with one
 * spelling of each refusal.
 *
 * <p>Every verb that names a quest or an achievement asks the same two questions - was one named at
 * all, and does the catalogue know it - and a verb answering them itself would be the first to word
 * a refusal differently. The argument is still declared by the command that owns it, because that
 * is what puts it in the command's own help line; only the reading is shared.
 */
final class ContentArgs {

    private ContentArgs() {
    }

    /** The raw value of {@code arg}, trimmed, or null when it was left out or blank. */
    @Nullable
    static String value(@Nonnull CommandContext ctx, @Nonnull OptionalArg<String> arg) {
        String raw = arg.provided(ctx) ? arg.get(ctx) : null;
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /**
     * The quest {@code arg} names, or null after telling the sender that nothing was named or that
     * nothing in the catalogue is called that.
     */
    @Nullable
    static Quest quest(@Nonnull CommandContext ctx, @Nonnull OptionalArg<String> arg) {
        String questId = value(ctx, arg);
        if (questId == null) {
            ProgressAdminMessages.refused(ctx, "quest.needed");
            return null;
        }
        Quest quest = ProgressionRuntime.quests().quest(questId);
        if (quest == null) {
            ProgressAdminMessages.unknownQuest(ctx, questId);
        }
        return quest;
    }

    /** The achievement twin of {@link #quest}. */
    @Nullable
    static Achievement achievement(@Nonnull CommandContext ctx, @Nonnull OptionalArg<String> arg) {
        String achievementId = value(ctx, arg);
        if (achievementId == null) {
            ProgressAdminMessages.refused(ctx, "achievement.needed");
            return null;
        }
        Achievement achievement = ProgressionRuntime.achievements().achievement(achievementId);
        if (achievement == null) {
            ProgressAdminMessages.unknownAchievement(ctx, achievementId);
        }
        return achievement;
    }
}
