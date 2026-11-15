package com.ziggfreed.common.quest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateRefusal;
import com.ziggfreed.common.progress.gate.RefusalLines;
import com.ziggfreed.common.progress.runtime.ProgressionFactors;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;

/**
 * The quest engine's family of refusal lines over zc-core's one mapping ({@link RefusalLines}), shared by
 * every surface that has to explain why content is out of reach - the objective book, a character's offer
 * page, the commerce screens - so no two of them can disagree about the same gate. The requirement
 * evaluator's own tokens and records read through {@link RefusalLines} (its default family); this class adds
 * the engine's flat tokens ({@link QuestGates}) and the clock, and fills {@link RefusalLines}' two seams for
 * this module's content: a quest or achievement title ({@link ProgressionTexts}) and the two membership
 * factors ({@code quest_completed}, {@code achievement_earned}). {@link #install} registers all of it, once
 * and again after a reset; the class does so on first use.
 *
 * <p>Tokens come in two families. The engine's own flat tokens ({@link QuestGates}, listed once, in this
 * class's one table of them) each map to a fixed line - every one of them, so a daily finished today reads "Comes back
 * in 5h 12m" and a quest already carried reads "Already in your quest log" rather than both collapsing onto
 * the catch-all. The three clock tokens ({@code on_cooldown}, {@code period_spent}, {@code run_spent})
 * quote WHEN when the caller hands over the remaining wait beside the tokens
 * ({@link #lines(List, long)} and its siblings, or the {@link QuestEngine.AcceptCheck} forms that
 * carry it already), which {@link RefusalLines} hands this family ({@code Family.line(token, waitMs)}),
 * and fall back to their no-clock twins when a caller has no wait to hand over (a token lifted from
 * somewhere with no record behind it). The wait is composed by {@link #waitLine} as its own translatable
 * line, whole numbers bound as typed params so each client writes its own digits, and rides the lock line
 * as a NESTED param. This class holds no clock: the wait is always passed in, never read here. The
 * requirement evaluator's tokens ({@link GateEvaluator}) are STRUCTURED - {@code quest:<id>},
 * {@code factor:<id>}, {@code gate:<kind>} - and read through {@link RefusalLines}, whose javadoc says what
 * each renders as.
 *
 * <p>The evaluator reports the flat {@code prerequisites} token BESIDE its specific ones, so a
 * caller rendering the whole list would say "Requirements not met" and "Complete quest: X" about
 * the same gate. The flat token is this family's catch-all ({@code Family.generic}), so
 * {@link RefusalLines#lines} drops it whenever a specific line made it redundant, and
 * {@link #bestLine} picks the most specific single line for a surface with room for only one: the
 * walk, the dedupe and the drop rules are {@link RefusalLines}', never a second copy here. The record
 * entry points ({@link #linesOf}, {@link #line(GateRefusal)}) follow the same rules.
 */
public final class LockReasons {

    /** The owner this family is registered under. */
    private static final String OWNER = "ziggfreedcommon";

    /** One of the engine's flat tokens: its line's key, and whether that line quotes the wait (a clock token). */
    private record EngineLine(@Nonnull String token, @Nonnull String key, boolean clock) {
    }

    /** The engine's flat tokens, each its own sentence, by token: the one list of them. */
    private static final Map<String, EngineLine> ENGINE = engineLines(
            new EngineLine(QuestGates.REASON_UNAVAILABLE, "lock.unavailable", false),
            new EngineLine(QuestGates.REASON_ON_COOLDOWN, "lock.on_cooldown", true),
            new EngineLine(QuestGates.REASON_PERIOD_SPENT, "lock.period_spent", true),
            new EngineLine(QuestGates.REASON_RUN_SPENT, "lock.run_spent", true),
            new EngineLine(QuestGates.REASON_MAX_COMPLETIONS, "lock.max_completions", false),
            new EngineLine(QuestGates.REASON_ALREADY_STARTED, "lock.already_started", false),
            new EngineLine(QuestGates.REASON_SYSTEM_DISABLED, "lock.system_disabled", false),
            new EngineLine(QuestGates.REASON_PREREQUISITES, "lock.prerequisites", false),
            new EngineLine(QuestGates.REASON_LOG_FULL, "lock.log_full", false));

    /** A wait this short reads as "under a minute" rather than as a count of nothing. */
    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60L * MINUTE_MS;
    private static final long DAY_MS = 24L * HOUR_MS;

    static {
        install();
    }

    private LockReasons() {
    }

    @Nonnull
    private static Map<String, EngineLine> engineLines(@Nonnull EngineLine... lines) {
        Map<String, EngineLine> out = new LinkedHashMap<>();
        for (EngineLine line : lines) {
            out.put(line.token(), line);
        }
        return Map.copyOf(out);
    }

    /**
     * Register this module's part of {@link RefusalLines}: the engine's flat tokens as a family, the content
     * titles seam over {@link ProgressionTexts}, and the two membership factors. Idempotent; the library's
     * setup calls it, and so does this class's own first use.
     */
    public static void install() {
        RefusalLines.Family engine = new RefusalLines.Family() {
            @Override
            public Message line(@Nonnull String token) {
                return engineLine(token, RefusalLines.NO_WAIT);
            }

            @Override
            public Message line(@Nonnull String token, long waitMs) {
                return engineLine(token, waitMs);
            }

            @Override
            public boolean generic(@Nonnull String token) {
                return QuestGates.REASON_PREREQUISITES.equals(token);
            }

            @Nonnull
            @Override
            public String dedupeKey(@Nonnull String token) {
                return LockReasons.dedupeKey(token);
            }
        };
        for (String token : ENGINE.keySet()) {
            RefusalLines.register(token, OWNER, engine);
        }
        RefusalLines.contentTitles(ProgressionTexts::title);
        RefusalLines.memberFactor(ProgressionFactors.QUEST_COMPLETED, RefusalLines.QUEST_LINE_KEY);
        RefusalLines.memberFactor(ProgressionFactors.ACHIEVEMENT_EARNED, "ziggfreedcommon.progress.lock.achievement");
    }

    /**
     * Every refusal as its own line: deduplicated (several tokens can fold to one sentence, and a
     * list repeating it reads as a bug rather than as emphasis), with the flat requirements line
     * dropped when a specific requirement line already covers it. With no wait to hand over, the
     * clock tokens read their no-clock twins; see {@link #lines(List, long)}.
     */
    @Nonnull
    public static List<Message> lines(@Nonnull List<String> reasons) {
        return RefusalLines.lines(reasons);
    }

    /**
     * {@link #lines(List)} told WHEN the quest comes back: {@code waitMs} is the remaining wait a
     * repeat rule imposes, as {@link QuestEngine.AcceptCheck#waitMs()} carries it - {@code 0} for
     * none to quote, a positive wait for a running cooldown or a spent calendar window (quoted on
     * the clock token's line through {@link #waitLine}), {@link Long#MAX_VALUE} for a spent lifetime
     * cap, which reads as the finished-for-good line rather than as a number.
     */
    @Nonnull
    public static List<Message> lines(@Nonnull List<String> reasons, long waitMs) {
        return RefusalLines.lines(reasons, waitMs);
    }

    /** {@link #lines(List, long)} straight off the engine's answer, wait included. */
    @Nonnull
    public static List<Message> lines(@Nonnull QuestEngine.AcceptCheck check) {
        return lines(check.reasons(), check.waitMs());
    }

    /**
     * The record form of {@link #lines}, for a surface holding the evaluator's structured refusals:
     * same dedupe rule, and a factor line carries the BOUND the record does. There is no flat
     * requirements token to drop here - the records are all specific to what failed - but two
     * conditions folding to one sentence still render it once.
     */
    @Nonnull
    public static List<Message> linesOf(@Nonnull List<GateRefusal> refusals) {
        return RefusalLines.linesOf(refusals);
    }

    /**
     * The single most specific line, for a surface with room for only one. With no wait to hand
     * over, the clock tokens read their no-clock twins; see {@link #bestLine(List, long)}.
     */
    @Nonnull
    public static Message bestLine(@Nonnull List<String> reasons) {
        return RefusalLines.bestLine(reasons);
    }

    /** {@link #bestLine(List)} told when the quest comes back, on the terms of {@link #lines(List, long)}. */
    @Nonnull
    public static Message bestLine(@Nonnull List<String> reasons, long waitMs) {
        return RefusalLines.bestLine(reasons, waitMs);
    }

    /** {@link #bestLine(List, long)} straight off the engine's answer, wait included. */
    @Nonnull
    public static Message bestLine(@Nonnull QuestEngine.AcceptCheck check) {
        return bestLine(check.reasons(), check.waitMs());
    }

    /**
     * The player-facing line for one refusal token; never null and never the raw token. With no
     * wait to hand over, a clock token reads its no-clock twin; see {@link #line(String, long)}.
     */
    @Nonnull
    public static Message line(@Nullable String reason) {
        return RefusalLines.line(reason);
    }

    /**
     * {@link #line(String)} told when the quest comes back. Only the three clock tokens read the
     * wait: a positive one is quoted on their "in {0}" line through {@link #waitLine}, none
     * ({@code 0}) leaves the no-clock twin, and {@link Long#MAX_VALUE} - nothing brings it back -
     * reads as the finished-for-good line rather than as a number no player can use. Every other
     * token reads its fixed line whatever the wait says.
     */
    @Nonnull
    public static Message line(@Nullable String reason, long waitMs) {
        return RefusalLines.line(reason, waitMs);
    }

    /** The engine's own line for one of its flat tokens, or null for any other token. */
    @Nullable
    private static Message engineLine(@Nonnull String reason, long waitMs) {
        EngineLine line = ENGINE.get(reason);
        if (line == null) {
            return null;
        }
        return line.clock() ? clockLine(line.key(), waitMs) : text(line.key());
    }

    /**
     * A clock token's line: its {@code .in} form quoting the wait when there is one to quote, its
     * no-clock twin when there is none, the finished-for-good line when the wait is forever.
     */
    @Nonnull
    private static Message clockLine(@Nonnull String key, long waitMs) {
        if (waitMs == Long.MAX_VALUE) {
            return text("lock.max_completions");
        }
        return waitMs > RefusalLines.NO_WAIT ? text(key + ".in", waitLine(waitMs)) : text(key);
    }

    /**
     * How long a wait is, as a line a player reads and a lock line can carry as its {@code {0}}:
     * days and hours past a day, hours and minutes past an hour, minutes past a minute, and one
     * fixed "under a minute" line below that, so a repeat about to come back never reads as a wait
     * of nothing. Each number binds as a TYPED param on a {@code {N, number}} blank, so the
     * player's own client writes the digits; the line itself is a translation, so it renders
     * nested inside a lock line (a {@code Msg.tr} result carries a message id, which is what a
     * param needs to render at all). Whole units, rounded down: a player told "5m" with 5m 40s
     * left is not misled the way one told "0m" would be.
     *
     * <p>A wait that never ends ({@link Long#MAX_VALUE}, what a spent lifetime cap answers) reads
     * as the finished-for-good line rather than as the hundred billion days the arithmetic would
     * otherwise produce. It is a whole sentence rather than a span, so it is the answer for a
     * caller painting this on its own, never something to nest inside "comes back in {0}" - and
     * nothing does, because a lock line asks {@link #clockLine} instead, which decides the forever
     * case before it ever composes. The guard is here because this method is public and a caller
     * holding a raw engine value ({@code QuestEngine.offerableInMs}) should not have to know that
     * one value out of the range means something else.
     */
    @Nonnull
    public static Message waitLine(long waitMs) {
        if (waitMs == Long.MAX_VALUE) {
            return text("lock.max_completions");
        }
        if (waitMs < MINUTE_MS) {
            return text("wait.under_minute");
        }
        long days = waitMs / DAY_MS;
        long hours = (waitMs % DAY_MS) / HOUR_MS;
        long minutes = (waitMs % HOUR_MS) / MINUTE_MS;
        if (days > 0L) {
            return text("wait.days", days, hours);
        }
        if (hours > 0L) {
            return text("wait.hours", hours, minutes);
        }
        return text("wait.minutes", minutes);
    }

    /**
     * The player-facing line for one structured refusal: the quest's own title for a quest gate,
     * the factor's overlay-given name (with the bound when the record carries one, and the
     * "(currently N)" readout when the evaluation resolved a value beside a real bound) for a
     * factor gate, the permission sentence for a permission, the composed either-or / negated
     * sentence for a composite carrying children, the fixed requirements line for everything else.
     *
     * <p>Three factor spellings fold onto richer lines than their overlay name, because each is
     * the SAME question as a form this mapping already answers well: a
     * {@code ziggfreedcommon:quest_completed} bound reads as the quest line a {@code Quests}
     * prerequisite reads as, a {@code ziggfreedcommon:achievement_earned} bound names the
     * achievement the same way, and a {@code hytale:permission} bound reads as the permission
     * sentence the {@code Permission} leaf reads as - one requirement, one sentence, however
     * content spells it.
     *
     * <p>A composite's children render through this same method, so a route reads exactly as the
     * same requirement would at top level; a single-route {@code AnyOf} reads as that route's own
     * line rather than a one-entry list, and a childless composite (a record lifted from a token)
     * keeps its flat line - the generic requirements line for {@code any_of}, the fixed negated
     * sentence for {@code not}.
     */
    @Nonnull
    public static Message line(@Nonnull GateRefusal refusal) {
        return RefusalLines.line(refusal);
    }

    /**
     * The identity one of the engine's flat tokens deduplicates under: its own (every one has its own sentence, so
     * two of them are two lines), the shared requirements bucket for the flat requirements line, and the catch-all
     * bucket for a token this family does not word after all.
     */
    @Nonnull
    private static String dedupeKey(@Nonnull String token) {
        if (QuestGates.REASON_PREREQUISITES.equals(token)) {
            return "prerequisites";
        }
        return ENGINE.containsKey(token) ? token : "other";
    }

    @Nonnull
    private static Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr("ziggfreedcommon.", "progress." + key, args);
    }
}
