package com.ziggfreed.common.objectives.command;

import java.awt.Color;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.questlist.ProgressionTexts;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestStatus;

/**
 * Every line this command family says, and the two rules it says them by.
 *
 * <p><b>A sentence is a KEY; an id or a number is a raw argument.</b> The words belong to whoever is
 * reading them, and they are resolved on that person's own client, so nothing here builds a sentence
 * out of English fragments. A quest id, an achievement id, a player name and a count are DATA - they
 * read the same in every language, and a translated one would stop naming the thing an author has to
 * go and edit. What the runtime CALLS a piece of content is a nested {@link Message} the runtime's
 * own text sources answer, passed as an argument rather than resolved to a string here.
 *
 * <p><b>A status is a WORD, so it is a key too.</b> The runtime's {@link QuestStatus} and
 * {@link AchievementStatus} are the vocabulary every answer here is spoken in, and shipping the enum
 * constant at a reader would be shipping an untranslated token. {@link #questStatus} and
 * {@link #achievementStatus} name each one through a key of its own.
 *
 * <p><b>Keys live in {@code ziggfreedcommon.progression.admin.lang}</b>, so the in-file key drops the
 * {@code ziggfreedcommon.progression.admin.} segment the filename already carries. The DESCRIPTION
 * strings a command and its arguments are constructed with are keys too: the engine resolves a
 * command description through its own localization module, so a plain English one renders to the
 * reader as the raw text nobody translated.
 */
public final class ProgressAdminMessages {

    /** The key family every line here resolves under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.progression.admin.";

    /** What a command or one of its arguments is FOR, resolved by the engine's own help. */
    @Nonnull
    public static String desc(@Nonnull String what) {
        return PREFIX + "desc." + what;
    }

    private static final Color HEADING = new Color(0xFFCC66);
    private static final Color DETAIL = new Color(0xAAAAAA);
    private static final Color BAD = new Color(0xFF5555);
    private static final Color GOOD = new Color(0x77DD77);

    private ProgressAdminMessages() {
    }

    // ==================== saying it ====================

    /** A heading line: what the rows under it are. */
    public static void heading(@Nonnull CommandContext ctx, @Nonnull String key,
            @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(HEADING));
    }

    /** A row, or anything else that is detail rather than an answer. */
    public static void detail(@Nonnull CommandContext ctx, @Nonnull String key,
            @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(DETAIL));
    }

    /** Something was done. */
    public static void done(@Nonnull CommandContext ctx, @Nonnull String key,
            @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(GOOD));
    }

    /** Something was refused, and why. */
    public static void refused(@Nonnull CommandContext ctx, @Nonnull String key,
            @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(BAD));
    }

    // ==================== the runtime's own words ====================

    /**
     * A fragment of a line, for composing into an argument: it carries a key, so it survives being
     * nested where a bare join would render blank.
     */
    @Nonnull
    public static Message piece(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    /** What a quest's status is CALLED, as a nested message the reader's client resolves. */
    @Nonnull
    public static Message questStatus(@Nonnull QuestStatus status) {
        return Msg.key(PREFIX + "status.quest." + status.name().toLowerCase(Locale.ROOT));
    }

    /** What an achievement's status is CALLED, on the same terms. */
    @Nonnull
    public static Message achievementStatus(@Nonnull AchievementStatus status) {
        return Msg.key(PREFIX + "status.achievement." + status.name().toLowerCase(Locale.ROOT));
    }

    /**
     * What a quest is CALLED: every registered text source in order, then the words the fold put on
     * the runtime object, then its id written out - because a listing has to say SOMETHING, and an
     * id is traceable where a blank is not.
     */
    @Nonnull
    public static Message questName(@Nonnull Quest quest) {
        Message named = ProgressionTexts.title(quest.id());
        return named != null ? named : quest.text().titleOr(quest.id());
    }

    /** What an achievement is CALLED, on the same terms. */
    @Nonnull
    public static Message achievementName(@Nonnull Achievement achievement) {
        Message named = ProgressionTexts.title(achievement.id());
        return named != null ? named : achievement.text().titleOr(achievement.id());
    }

    /**
     * A row's trailing flags as ONE nested argument: each present flag is a keyed fragment, absent
     * ones contribute nothing, and none of them is a bare true or false at a reader. Empty when
     * nothing applies, which renders as exactly nothing.
     */
    @Nonnull
    public static Message flags(@Nonnull List<String> presentKeys) {
        Message[] pieces = new Message[presentKeys.size()];
        for (int i = 0; i < pieces.length; i++) {
            pieces[i] = piece(presentKeys.get(i));
        }
        return Msg.cat(pieces);
    }

    // ==================== small shared answers ====================

    /** The one spelling of "nothing in the shared catalogue is called that". */
    public static void unknownQuest(@Nonnull CommandContext ctx, @Nonnull String questId) {
        refused(ctx, "quest.unknown", questId);
    }

    /** The achievement twin of {@link #unknownQuest}. */
    public static void unknownAchievement(@Nonnull CommandContext ctx, @Nonnull String achievementId) {
        refused(ctx, "achievement.unknown", achievementId);
    }
}
