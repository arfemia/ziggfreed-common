package com.ziggfreed.common.quest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.factor.FactorNames;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateRefusal;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.util.NumberFormatter;

/**
 * The ONE mapping from refusal tokens (and their structured records) to the lines a player reads,
 * shared by every surface that has to explain why content is out of reach - the objective book, a
 * character's offer page, the commerce screens - so no two of them can disagree about the same
 * gate.
 *
 * <p>Tokens come in two families. The engine's own flat tokens ({@link QuestGates}) each map to a
 * fixed line. The requirement evaluator's tokens ({@link GateEvaluator}) are STRUCTURED - {@code
 * quest:<id>}, {@code factor:<id>}, {@code gate:<kind>} - and two of them render as the actual ask
 * rather than being discarded: a quest requirement names the quest ("Complete quest: X"), and a
 * FACTOR requirement whose factor has a naming overlay ({@link FactorNames}, the
 * {@code Server/ZiggfreedCommon/Factors/} assets) names the factor - with its bound, when the
 * caller holds the {@link GateRefusal} record that carries one. A factor no asset names, and every
 * remaining structured form, folds to the generic requirements line; a raw token is never shown,
 * and a token nothing here recognises reads as the generic "not available" line.
 *
 * <p>The evaluator reports the flat {@code prerequisites} token BESIDE its specific ones, so a
 * caller rendering the whole list would say "Requirements not met" and "Complete quest: X" about
 * the same gate. {@link #lines} therefore drops the flat token whenever a specific line made it
 * redundant, and {@link #bestLine} picks the most specific single line for a surface with room for
 * only one. The record entry points ({@link #linesOf}, {@link #line(GateRefusal)}) follow the same
 * dedupe and drop rules.
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

    /**
     * The record form of {@link #lines}, for a surface holding the evaluator's structured refusals:
     * same dedupe rule, and a factor line carries the BOUND the record does. There is no flat
     * requirements token to drop here - the records are all specific to what failed - but two
     * conditions folding to one sentence still render it once.
     */
    @Nonnull
    public static List<Message> linesOf(@Nonnull List<GateRefusal> refusals) {
        Set<String> seen = new LinkedHashSet<>();
        List<Message> lines = new ArrayList<>();
        for (GateRefusal refusal : refusals) {
            if (refusal != null && seen.add(dedupeKey(refusal))) {
                lines.add(line(refusal));
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
        return reasons.isEmpty() ? line((String) null) : line(reasons.get(0));
    }

    /** The player-facing line for one refusal token; never null and never the raw token. */
    @Nonnull
    public static Message line(@Nullable String reason) {
        if (QuestGates.REASON_UNAVAILABLE.equals(reason)) {
            return text("lock.unavailable");
        }
        if (QuestGates.REASON_ON_COOLDOWN.equals(reason)) {
            return text("lock.on_cooldown");
        }
        if (QuestGates.REASON_PREREQUISITES.equals(reason)) {
            return text("lock.prerequisites");
        }
        if (QuestGates.REASON_LOG_FULL.equals(reason)) {
            return text("lock.log_full");
        }
        GateRefusal structured = GateRefusal.fromToken(reason);
        if (structured != null) {
            return line(structured);
        }
        return text("lock.other");
    }

    /**
     * The player-facing line for one structured refusal: the quest's own title for a quest gate,
     * the factor's overlay-given name (with the bound when the record carries one) for a factor
     * gate, the fixed requirements line for everything else.
     */
    @Nonnull
    public static Message line(@Nonnull GateRefusal refusal) {
        if (refusal.kind() == GateRefusal.Kind.QUEST) {
            String questId = refusal.questId() == null ? "" : refusal.questId();
            Message title = ProgressionTexts.title(questId);
            return text("lock.quest", title != null ? title : Msg.raw(questId));
        }
        if (refusal.kind() == GateRefusal.Kind.FACTOR) {
            Message name = FactorNames.name(refusal.factorId(), refusal.param());
            if (name != null) {
                String bound = boundOf(refusal);
                return bound != null
                        ? text("lock.factor.bound", name, bound)
                        : text("lock.factor", name);
            }
        }
        return text("lock.prerequisites");
    }

    /** Does this token render as a line that names the concrete thing to go and do? */
    private static boolean isSpecific(@Nullable String reason) {
        if (reason == null) {
            return false;
        }
        if (reason.startsWith(GateEvaluator.REASON_QUEST)) {
            return true;
        }
        GateRefusal structured = GateRefusal.fromToken(reason);
        return structured != null && isSpecific(structured);
    }

    /** The record twin: a quest gate always, a factor gate when a naming overlay answers for it. */
    private static boolean isSpecific(@Nonnull GateRefusal refusal) {
        if (refusal.kind() == GateRefusal.Kind.QUEST) {
            return true;
        }
        return refusal.kind() == GateRefusal.Kind.FACTOR
                && FactorNames.name(refusal.factorId(), refusal.param()) != null;
    }

    /**
     * The identity a rendered line deduplicates under: the token itself where the line embeds it
     * (two quest requirements are two different sentences, and so are two named factors), the
     * bucket for everything that folds to a fixed sentence.
     */
    @Nonnull
    private static String dedupeKey(@Nullable String reason) {
        if (reason == null) {
            return "other";
        }
        GateRefusal structured = GateRefusal.fromToken(reason);
        if (structured != null) {
            return dedupeKey(structured);
        }
        if (QuestGates.REASON_UNAVAILABLE.equals(reason) || QuestGates.REASON_ON_COOLDOWN.equals(reason)
                || QuestGates.REASON_LOG_FULL.equals(reason)) {
            return reason;
        }
        if (QuestGates.REASON_PREREQUISITES.equals(reason)) {
            return "prerequisites";
        }
        return "other";
    }

    /**
     * The record twin. A named factor deduplicates by everything its sentence says - id, param and
     * bounds - so two different bounds on one factor stay two lines; an unnamed one folds into the
     * shared requirements bucket exactly as its token would.
     */
    @Nonnull
    private static String dedupeKey(@Nonnull GateRefusal refusal) {
        if (refusal.kind() == GateRefusal.Kind.QUEST) {
            return refusal.token();
        }
        if (isSpecific(refusal)) {
            return refusal.token() + "#" + refusal.min() + "/" + refusal.max();
        }
        return "prerequisites";
    }

    /**
     * The one number a factor line quotes, grouped: the lower bound when the condition has one
     * (the thing to go and raise), else the upper bound, else null. A {@code Min} of exactly 1
     * with no {@code Max} is the PRESENCE idiom this vocabulary is full of (hold the item, own the
     * node, have done it once), and quoting its 1 turns "Requires X" into noise - so that one
     * shape reads as the unbounded line too.
     */
    @Nullable
    private static String boundOf(@Nonnull GateRefusal refusal) {
        if (refusal.min() != null && refusal.min() == 1.0 && refusal.max() == null) {
            return null;
        }
        Double bound = refusal.min() != null ? refusal.min() : refusal.max();
        if (bound == null || !Double.isFinite(bound)) {
            return null;
        }
        return bound == Math.floor(bound)
                ? NumberFormatter.grouped((long) bound.doubleValue())
                : bound.toString();
    }

    @Nonnull
    private static Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr("ziggfreedcommon.", "progress." + key, args);
    }
}
