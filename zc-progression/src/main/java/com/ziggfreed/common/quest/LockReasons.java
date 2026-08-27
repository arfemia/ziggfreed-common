package com.ziggfreed.common.quest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.factor.FactorNames;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateRefusal;
import com.ziggfreed.common.progress.runtime.ProgressionFactors;
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
 * quest:<id>}, {@code factor:<id>}, {@code gate:<kind>} - and several render as the actual ask
 * rather than being discarded: a quest requirement names the quest ("Complete quest: X"), and a
 * FACTOR requirement whose factor has a naming overlay ({@link FactorNames}, the
 * {@code Server/ZiggfreedCommon/Factors/} assets) names the factor - with its bound, when the
 * caller holds the {@link GateRefusal} record that carries one, and with the "(currently N)"
 * readout when that record also carries the value the evaluation resolved. The two membership
 * factor spellings ({@code ziggfreedcommon:quest_completed} / {@code achievement_earned}) name
 * their content's own title, and a permission refusal reads with its own fixed sentence - a
 * missing permission is a different kind of answer from a numeric bound - whether it was spelled
 * as the {@code Permission} leaf or as a {@code hytale:permission} factor condition, so one
 * requirement is one sentence however content spells it. A COMPOSITE record renders what its
 * group asks for: an {@code AnyOf}'s routes read as one either-or list ("Unlocked by: A or B",
 * joined by a translatable joiner; a single route reads as its own line, never as a list), and a
 * {@code Not} that shut the gate by passing reads as the negated sentence naming the met asks. A
 * factor no asset names, and every remaining structured form, folds to the generic requirements
 * line; a raw token is never shown, and a token nothing here recognises reads as the generic "not
 * available" line.
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
        if (refusal.kind() == GateRefusal.Kind.QUEST) {
            return contentLine("lock.quest", refusal.questId());
        }
        if (refusal.kind() == GateRefusal.Kind.PERMISSION) {
            return text("lock.permission");
        }
        if (refusal.kind() == GateRefusal.Kind.ANY_OF) {
            List<GateRefusal> routes = refusal.children();
            if (routes.isEmpty()) {
                return text("lock.prerequisites");
            }
            return routes.size() == 1 ? line(routes.get(0))
                    : text("lock.any_of", fold(routes, LockReasons::orJoiner));
        }
        if (refusal.kind() == GateRefusal.Kind.NOT) {
            List<GateRefusal> asks = refusal.children();
            return asks.isEmpty() ? text("lock.not")
                    : text("lock.not.met", fold(asks, LockReasons::commaJoiner));
        }
        if (refusal.kind() == GateRefusal.Kind.ALL_OF) {
            // Only ever a child inside an AnyOf's routes: a frameless comma list, exactly as the
            // bundle reads in that position.
            List<GateRefusal> asks = refusal.children();
            if (asks.isEmpty()) {
                return text("lock.prerequisites");
            }
            return asks.size() == 1 ? line(asks.get(0)) : fold(asks, LockReasons::commaJoiner);
        }
        if (refusal.kind() == GateRefusal.Kind.FACTOR) {
            if (GateEvaluator.PERMISSION_FACTOR.equalsIgnoreCase(refusal.factorId())) {
                // The long spelling of a Permission leaf is the same question, same sentence.
                return text("lock.permission");
            }
            if (ProgressionFactors.QUEST_COMPLETED.equalsIgnoreCase(refusal.factorId())
                    && refusal.param() != null) {
                return contentLine("lock.quest", refusal.param());
            }
            if (ProgressionFactors.ACHIEVEMENT_EARNED.equalsIgnoreCase(refusal.factorId())
                    && refusal.param() != null) {
                return contentLine("lock.achievement", refusal.param());
            }
            Message name = FactorNames.name(refusal.factorId(), refusal.param());
            if (name != null) {
                String bound = boundOf(refusal);
                if (bound == null) {
                    return text("lock.factor", name);
                }
                String current = numberOf(refusal.value());
                return current != null
                        ? text("lock.factor.bound.current", name, bound, current)
                        : text("lock.factor.bound", name, bound);
            }
        }
        return text("lock.prerequisites");
    }

    /**
     * A composite's children folded into ONE message that can sit in a {@code {0}} PARAM position.
     * Built with {@link Msg#cat}, NEVER {@link Msg#join}: a param renders only when it carries
     * {@code rawText} or {@code messageId}, which a bare join result does not - it would render
     * EMPTY nested inside the composite line. The separator folds the same way, because it is
     * itself part of the composite that lands in that param.
     */
    @Nonnull
    private static Message fold(@Nonnull List<GateRefusal> children,
            @Nonnull Supplier<Message> separator) {
        List<Message> parts = new ArrayList<>(children.size() * 2 - 1);
        for (GateRefusal child : children) {
            if (!parts.isEmpty()) {
                parts.add(separator.get());
            }
            parts.add(line(child));
        }
        return Msg.cat(parts.toArray(new Message[0]));
    }

    /** The translatable " or " between an either-or list's routes; a fresh instance per slot. */
    @Nonnull
    private static Message orJoiner() {
        return Msg.cat(Msg.raw(" "), text("lock.any_of.join"), Msg.raw(" "));
    }

    /** The locale-safe comma between the asks of one bundle. */
    @Nonnull
    private static Message commaJoiner() {
        return Msg.raw(", ");
    }

    /** A line naming one piece of catalogued content: its title where a source knows it, else its id. */
    @Nonnull
    private static Message contentLine(@Nonnull String key, @Nullable String contentId) {
        String id = contentId == null ? "" : contentId;
        Message title = ProgressionTexts.title(id);
        return text(key, title != null ? title : Msg.raw(id));
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

    /**
     * The record twin: a quest gate always, a permission always (its sentence names the concrete
     * kind of thing in the way), a composite when it carries children to describe, a factor gate
     * when a naming overlay answers for it or when it is one of the membership spellings that name
     * their content directly.
     */
    private static boolean isSpecific(@Nonnull GateRefusal refusal) {
        if (refusal.kind() == GateRefusal.Kind.QUEST
                || refusal.kind() == GateRefusal.Kind.PERMISSION) {
            return true;
        }
        if (isComposite(refusal)) {
            return !refusal.children().isEmpty();
        }
        if (refusal.kind() != GateRefusal.Kind.FACTOR) {
            return false;
        }
        if (GateEvaluator.PERMISSION_FACTOR.equalsIgnoreCase(refusal.factorId())) {
            return true;
        }
        if (refusal.param() != null
                && (ProgressionFactors.QUEST_COMPLETED.equalsIgnoreCase(refusal.factorId())
                        || ProgressionFactors.ACHIEVEMENT_EARNED.equalsIgnoreCase(refusal.factorId()))) {
            return true;
        }
        return FactorNames.name(refusal.factorId(), refusal.param()) != null;
    }

    /** Is this one of the three composite kinds that can carry children? */
    private static boolean isComposite(@Nonnull GateRefusal refusal) {
        return refusal.kind() == GateRefusal.Kind.ANY_OF || refusal.kind() == GateRefusal.Kind.NOT
                || refusal.kind() == GateRefusal.Kind.ALL_OF;
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
     * shared requirements bucket exactly as its token would. Both permission spellings share the
     * ONE permission bucket, and the {@code quest_completed} factor spelling deduplicates under
     * the SAME key as the {@code Quests} leaf naming that quest, because each pair renders the
     * identical sentence and a list saying it twice reads as a bug. A composite carrying children
     * deduplicates by everything its composed sentence says (its kind plus each child's own key,
     * recursively); a childless {@code not} keeps its own bucket - its fixed negated sentence is
     * not the requirements line.
     */
    @Nonnull
    private static String dedupeKey(@Nonnull GateRefusal refusal) {
        if (refusal.kind() == GateRefusal.Kind.QUEST) {
            return refusal.token();
        }
        if (refusal.kind() == GateRefusal.Kind.PERMISSION
                || (refusal.kind() == GateRefusal.Kind.FACTOR
                        && GateEvaluator.PERMISSION_FACTOR.equalsIgnoreCase(refusal.factorId()))) {
            return GateEvaluator.REASON_PERMISSION;
        }
        if (isComposite(refusal)) {
            if (!refusal.children().isEmpty()) {
                StringBuilder key = new StringBuilder(refusal.token()).append('(');
                for (GateRefusal child : refusal.children()) {
                    key.append(dedupeKey(child)).append(',');
                }
                return key.append(')').toString();
            }
            return refusal.kind() == GateRefusal.Kind.NOT ? GateEvaluator.REASON_NOT : "prerequisites";
        }
        if (refusal.kind() == GateRefusal.Kind.FACTOR && refusal.param() != null
                && ProgressionFactors.QUEST_COMPLETED.equalsIgnoreCase(refusal.factorId())) {
            return GateRefusal.quest(refusal.param()).token();
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
        return numberOf(refusal.min() != null ? refusal.min() : refusal.max());
    }

    /** A number as a line quotes it - grouped when whole - or null when there is none to quote. */
    @Nullable
    private static String numberOf(@Nullable Double number) {
        if (number == null || !Double.isFinite(number)) {
            return null;
        }
        return number == Math.floor(number)
                ? NumberFormatter.grouped((long) number.doubleValue())
                : number.toString();
    }

    @Nonnull
    private static Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr("ziggfreedcommon.", "progress." + key, args);
    }
}
