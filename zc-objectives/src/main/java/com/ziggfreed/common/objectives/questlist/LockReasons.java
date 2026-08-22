package com.ziggfreed.common.objectives.questlist;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.quest.QuestGates;

/**
 * The ONE mapping from refusal tokens to the lines a player reads, shared by the objective book
 * and the NPC quest list so the two surfaces cannot disagree about why a quest is out of reach.
 *
 * <p>Tokens come in two families. The engine's own flat tokens ({@link QuestGates}) each map to a
 * fixed line. The requirement evaluator's tokens ({@link GateEvaluator}) are STRUCTURED - {@code
 * quest:<id>}, {@code factor:<id>}, {@code gate:<kind>} - and the quest form is rendered as the
 * actual ask ("Complete quest: X") rather than being discarded: the thing that shut a gate is
 * almost always another quest, and a locked row that names it tells the player exactly where to go
 * next. The factor and custom forms name authored internals a player cannot read, so they fold to
 * the generic requirements line; a raw token is never shown, and a token nothing here recognises
 * reads as the generic "not available" line.
 *
 * <p>The evaluator reports the flat {@code prerequisites} token BESIDE its specific one, so a
 * caller rendering the whole list would say "Requirements not met" and "Complete quest: X" about
 * the same gate. {@link #lines} therefore drops the flat token whenever a specific line made it
 * redundant, and {@link #bestLine} picks the most specific single line for a surface with room for
 * only one.
 */
public final class LockReasons {

    private LockReasons() {
    }

    /**
     * Every refusal as its own line: deduplicated (several tokens can fold to one sentence, and a
     * list repeating it reads as a bug rather than as emphasis), with the flat requirements line
     * dropped when a specific requirement line already covers it.
     */
    @Nonnull
    public static List<Message> lines(@Nonnull List<String> reasons) {
        boolean specific = false;
        for (String reason : reasons) {
            specific |= isSpecific(reason);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Message> lines = new ArrayList<>();
        for (String reason : reasons) {
            if (specific && QuestGates.REASON_PREREQUISITES.equals(reason)) {
                continue;
            }
            if (seen.add(dedupeKey(reason))) {
                lines.add(line(reason));
            }
        }
        return lines;
    }

    /** The single most specific line, for a surface with room for only one. */
    @Nonnull
    public static Message bestLine(@Nonnull List<String> reasons) {
        for (String reason : reasons) {
            if (isSpecific(reason)) {
                return line(reason);
            }
        }
        return reasons.isEmpty() ? line(null) : line(reasons.get(0));
    }

    /** The player-facing line for one refusal token; never null and never the raw token. */
    @Nonnull
    public static Message line(@Nullable String reason) {
        if (QuestGates.REASON_UNAVAILABLE.equals(reason)) {
            return text("book.quests.lock.unavailable");
        }
        if (QuestGates.REASON_ON_COOLDOWN.equals(reason)) {
            return text("book.quests.lock.on_cooldown");
        }
        if (QuestGates.REASON_PREREQUISITES.equals(reason)) {
            return text("book.quests.lock.prerequisites");
        }
        if (QuestGates.REASON_LOG_FULL.equals(reason)) {
            return text("book.quests.lock.log_full");
        }
        if (reason != null && reason.startsWith(GateEvaluator.REASON_QUEST)) {
            String questId = reason.substring(GateEvaluator.REASON_QUEST.length());
            Message title = ProgressionTexts.title(questId);
            return text("book.quests.lock.quest", title != null ? title : Msg.raw(questId));
        }
        if (reason != null && (reason.startsWith(GateEvaluator.REASON_FACTOR)
                || reason.startsWith(GateEvaluator.REASON_CUSTOM)
                || GateEvaluator.REASON_ANY_OF.equals(reason)
                || GateEvaluator.REASON_NOT.equals(reason))) {
            return text("book.quests.lock.prerequisites");
        }
        return text("book.quests.lock.other");
    }

    /** Does this token render as a line that names the concrete thing to go and do? */
    private static boolean isSpecific(@Nullable String reason) {
        return reason != null && reason.startsWith(GateEvaluator.REASON_QUEST);
    }

    /**
     * The identity a rendered line deduplicates under: the token itself where the line embeds it
     * (two quest requirements are two different sentences), the bucket for everything that folds
     * to a fixed sentence.
     */
    @Nonnull
    private static String dedupeKey(@Nullable String reason) {
        if (reason == null) {
            return "other";
        }
        if (reason.startsWith(GateEvaluator.REASON_QUEST)) {
            return reason;
        }
        if (QuestGates.REASON_UNAVAILABLE.equals(reason) || QuestGates.REASON_ON_COOLDOWN.equals(reason)
                || QuestGates.REASON_LOG_FULL.equals(reason)) {
            return reason;
        }
        if (QuestGates.REASON_PREREQUISITES.equals(reason)
                || reason.startsWith(GateEvaluator.REASON_FACTOR)
                || reason.startsWith(GateEvaluator.REASON_CUSTOM)
                || GateEvaluator.REASON_ANY_OF.equals(reason)
                || GateEvaluator.REASON_NOT.equals(reason)) {
            return "prerequisites";
        }
        return "other";
    }

    @Nonnull
    private static Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr("ziggfreedcommon.", "progression." + key, args);
    }
}
