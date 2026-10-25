package com.ziggfreed.common.progress.gate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.factor.FactorNames;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.util.NumberFormatter;
import com.ziggfreed.common.util.SafeLog;

/**
 * The ONE mapping from refusal tokens (and their structured records) to the lines a player reads, for every
 * surface that has to say why something is out of reach, so no two of them can disagree about the same gate.
 * Every answer is a lang-keyed {@link Message}; nothing here, and nothing reading it, flattens a line to a
 * plain string.
 *
 * <p><b>The gate's own tokens are the default family</b> ({@link GateEvaluator}'s {@code factor:},
 * {@code quest:}, {@code gate:}, {@code permission}, {@code any_of}, {@code not}, {@code all_of}): a
 * prerequisite names its content ("Complete quest: X"), a FACTOR whose Factors file names it reads that name
 * with its bound, and the "(currently N)" readout when the record carries the value the evaluation resolved;
 * a permission reads its own sentence however it was spelled (the leaf or a {@code hytale:permission}
 * condition); an {@code AnyOf} reads its routes as one either-or list ("Unlocked by: A or B", a single route
 * as its own line), a {@code Not} that shut the gate by passing as the negated sentence naming what is met.
 * A factor no file names, and every other structured form, reads the generic requirements line; a raw token
 * is never shown, and a token nothing recognises reads as the generic "not available" line.
 *
 * <p><b>Consumers add their own families</b> ({@link #register}): a token equal to, or starting with, a
 * registered prefix reads through that family's renderer, whose keys live in the owner's own lang files. A
 * gate token is never handed to a family. A refusal outside {@code Requires} (a cooldown, a cost, a choice
 * already taken) is such a family. A caller holding the refusal's remaining wait passes it beside the tokens
 * ({@link #lines(List, long)}, {@link #bestLine(List, long)}, {@link #line(String, long)}), and a family whose lines
 * quote a wait reads it in {@link Family#line(String, long)}; the walk, the dedupe and the drop rules are the same
 * with or without one.
 *
 * <p><b>Two seams</b> the content's owner fills: {@link #contentTitles} names a piece of content by id (a
 * prerequisite's title; unfilled, the id itself), and {@link #memberFactor} declares a factor whose Param
 * names one piece of content (a "has finished X" reading), so a bound on it reads as that content's own
 * sentence, deduplicates with the leaf form naming the same content, and is a membership condition to the
 * validator ({@link #isMemberFactor}).
 */
public final class RefusalLines {

    /** One family of tokens a consumer words. */
    public interface Family {

        /** The line for {@code token}, or null when this family has none for it after all (the generic line then). */
        @Nullable
        Message line(@Nonnull String token);

        /**
         * The line for {@code token} told how long the refusal has left to run: {@code waitMs}, the remaining wait a
         * caller hands over beside its tokens (a cooldown's, a spent window's), {@link RefusalLines#NO_WAIT} for none
         * to quote,
         * {@link Long#MAX_VALUE} for one that never ends. For a family whose lines quote a wait; every other family
         * answers its plain {@link #line(String)}, the default.
         */
        @Nullable
        default Message line(@Nonnull String token, long waitMs) {
            return line(token);
        }

        /**
         * Does {@code token}'s line name the concrete thing to go and do, so a surface with room for one line
         * prefers it ({@link #bestLine})? Defaults to false.
         */
        default boolean specific(@Nonnull String token) {
            return false;
        }

        /**
         * Is {@code token}'s line the catch-all "not met" one, dropped from a list where a specific line
         * already covers the gate ({@link #lines})? Defaults to false.
         */
        default boolean generic(@Nonnull String token) {
            return false;
        }

        /**
         * The identity two lines deduplicate under; the token itself by default. A catch-all line that reads as
         * the gate's own requirements line answers {@code "prerequisites"}, the bucket that line shares.
         */
        @Nonnull
        default String dedupeKey(@Nonnull String token) {
            return token;
        }
    }

    private record Registered(@Nonnull String prefix, @Nonnull String owner, @Nonnull Family family) {
    }

    /** The lang namespace and file the gate family's own lines live in. */
    private static final String NS = "ziggfreedcommon.";
    private static final String DOMAIN = "progress.";

    /** The key a prerequisite on a piece of content reads with; a member factor registered with it shares it. */
    public static final String QUEST_LINE_KEY = NS + DOMAIN + "lock.quest";

    /** No wait to quote: what every entry point without a wait hands a family ({@link Family#line(String, long)}). */
    public static final long NO_WAIT = 0L;

    private static final Map<String, Registered> FAMILIES = new ConcurrentHashMap<>();

    /** Member factors by normalized id, to the full key their line reads with. */
    private static final Map<String, String> MEMBERS = new ConcurrentHashMap<>();

    @Nullable private static volatile Function<String, Message> titles;

    private RefusalLines() {
    }

    // ==================== registration ====================

    /**
     * Register (or replace) {@code family} for every token equal to or starting with {@code prefix}
     * ({@code "yourmod:cooldown"}, or {@code "yourmod:"} for a whole namespace). The longest matching prefix
     * answers. A gate token never reaches a family. At setup.
     */
    public static void register(@Nonnull String prefix, @Nonnull String owner, @Nonnull Family family) {
        if (prefix.isBlank()) {
            return;
        }
        String key = prefix.trim().toLowerCase(Locale.ROOT);
        FAMILIES.put(key, new Registered(key, owner, family));
    }

    /** Fill the seam that names a piece of content by id; null forgets it (lines then quote the id). */
    public static void contentTitles(@Nullable Function<String, Message> source) {
        titles = source;
    }

    /** Is the seam that names content filled? */
    public static boolean namesContent() {
        return titles != null;
    }

    /** Forget every family, member factor and the title seam. Tests only. */
    public static void resetForTests() {
        FAMILIES.clear();
        MEMBERS.clear();
        titles = null;
    }

    /**
     * Declare {@code factorId} a membership factor: its Param names one piece of content, and a bound on it
     * reads with {@code lineKey} (a full lang key whose {@code {0}} is that content's title).
     */
    public static void memberFactor(@Nonnull String factorId, @Nonnull String lineKey) {
        MEMBERS.put(RegistryLedger.normalize(factorId), lineKey);
    }

    /**
     * Is {@code factorId} a membership reading: a member factor, or {@code hytale:permission}? A bound on one
     * reads as "go and get it", which is why a Max-only condition on one is worth a load warning.
     */
    public static boolean isMemberFactor(@Nullable String factorId) {
        if (factorId == null || factorId.isBlank()) {
            return false;
        }
        return GateEvaluator.PERMISSION_FACTOR.equalsIgnoreCase(factorId.trim())
                || MEMBERS.containsKey(RegistryLedger.normalize(factorId));
    }

    // ==================== tokens ====================

    /**
     * Every refusal as its own line: deduplicated (several tokens fold to one sentence), and a family's
     * catch-all line ({@link Family#generic}) dropped where a specific one already covers the gate.
     */
    @Nonnull
    public static List<Message> lines(@Nonnull List<String> tokens) {
        return lines(tokens, NO_WAIT);
    }

    /**
     * {@link #lines(List)} told the remaining wait the refusal carries ({@code waitMs}, on the terms of
     * {@link Family#line(String, long)}), handed to every family's line; a gate token never reads it.
     */
    @Nonnull
    public static List<Message> lines(@Nonnull List<String> tokens, long waitMs) {
        boolean specific = false;
        for (String token : tokens) {
            specific |= isSpecific(token);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Message> lines = new ArrayList<>();
        for (String token : tokens) {
            if (specific && isGeneric(token)) {
                continue;
            }
            if (seen.add(dedupeKey(token))) {
                lines.add(line(token, waitMs));
            }
        }
        return lines;
    }

    /** The single most specific line, for a surface with room for only one. */
    @Nonnull
    public static Message bestLine(@Nonnull List<String> tokens) {
        return bestLine(tokens, NO_WAIT);
    }

    /** {@link #bestLine(List)} told the remaining wait the refusal carries, as {@link #lines(List, long)} is. */
    @Nonnull
    public static Message bestLine(@Nonnull List<String> tokens, long waitMs) {
        for (String token : tokens) {
            if (isSpecific(token)) {
                return line(token, waitMs);
            }
        }
        return tokens.isEmpty() ? line((String) null, waitMs) : line(tokens.get(0), waitMs);
    }

    /** The line for one token; never null and never the raw token. */
    @Nonnull
    public static Message line(@Nullable String token) {
        return line(token, NO_WAIT);
    }

    /** {@link #line(String)} told the remaining wait the refusal carries, as {@link #lines(List, long)} is. */
    @Nonnull
    public static Message line(@Nullable String token, long waitMs) {
        if (token == null || token.isBlank()) {
            return text("lock.other");
        }
        GateRefusal structured = GateRefusal.fromToken(token);
        if (structured != null) {
            return line(structured);
        }
        Registered family = familyOf(token);
        if (family != null) {
            Message line = ask(family, token, waitMs);
            if (line != null) {
                return line;
            }
        }
        return text("lock.other");
    }

    /** Does {@code token} read as a line naming the concrete thing to go and do? */
    public static boolean isSpecific(@Nullable String token) {
        if (token == null) {
            return false;
        }
        GateRefusal structured = GateRefusal.fromToken(token);
        if (structured != null) {
            return isSpecific(structured);
        }
        Registered family = familyOf(token);
        try {
            return family != null && family.family().specific(token);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Is {@code token} a family's catch-all line? A gate token never is. */
    public static boolean isGeneric(@Nullable String token) {
        if (token == null || GateRefusal.fromToken(token) != null) {
            return false;
        }
        Registered family = familyOf(token);
        try {
            return family != null && family.family().generic(token);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The identity a token's line deduplicates under. */
    @Nonnull
    public static String dedupeKey(@Nullable String token) {
        if (token == null) {
            return "other";
        }
        GateRefusal structured = GateRefusal.fromToken(token);
        if (structured != null) {
            return dedupeKey(structured);
        }
        Registered family = familyOf(token);
        if (family == null) {
            return "other";
        }
        try {
            return family.family().dedupeKey(token);
        } catch (Throwable t) {
            return "other";
        }
    }

    // ==================== records ====================

    /**
     * The record form of {@link #lines}, for a surface holding the evaluator's structured refusals: same
     * dedupe rule, and a factor line carries the BOUND the record does.
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

    /** The record form of {@link #bestLine}: the first specific record's line, else the first one's. */
    @Nonnull
    public static Message bestLineOf(@Nonnull List<GateRefusal> refusals) {
        for (GateRefusal refusal : refusals) {
            if (refusal != null && isSpecific(refusal)) {
                return line(refusal);
            }
        }
        return refusals.isEmpty() || refusals.get(0) == null ? text("lock.other") : line(refusals.get(0));
    }

    /**
     * The line for one structured refusal: the content's own title for a prerequisite or a member factor, the
     * factor's file-given name (with the bound when the record carries one, and the "(currently N)" readout
     * when the evaluation resolved a value beside a real bound), the permission sentence for a permission in
     * either spelling, the composed either-or or negated sentence for a composite carrying children, the fixed
     * requirements line for everything else. A composite's children render through this same method.
     */
    @Nonnull
    public static Message line(@Nonnull GateRefusal refusal) {
        switch (refusal.kind()) {
            case QUEST:
                return contentLine(QUEST_LINE_KEY, refusal.questId());
            case PERMISSION:
                return text("lock.permission");
            case ANY_OF: {
                List<GateRefusal> routes = refusal.children();
                if (routes.isEmpty()) {
                    return text("lock.prerequisites");
                }
                return routes.size() == 1 ? line(routes.get(0))
                        : text("lock.any_of", fold(routes, RefusalLines::orJoiner));
            }
            case NOT: {
                List<GateRefusal> asks = refusal.children();
                return asks.isEmpty() ? text("lock.not") : text("lock.not.met", fold(asks, RefusalLines::commaJoiner));
            }
            case ALL_OF: {
                // Only ever a child inside an AnyOf's routes: a frameless comma list.
                List<GateRefusal> asks = refusal.children();
                if (asks.isEmpty()) {
                    return text("lock.prerequisites");
                }
                return asks.size() == 1 ? line(asks.get(0)) : fold(asks, RefusalLines::commaJoiner);
            }
            case FACTOR:
                return factorLine(refusal);
            default:
                return text("lock.prerequisites");
        }
    }

    @Nonnull
    private static Message factorLine(@Nonnull GateRefusal refusal) {
        if (GateEvaluator.PERMISSION_FACTOR.equalsIgnoreCase(refusal.factorId())) {
            // The long spelling of a Permission leaf is the same question, same sentence.
            return text("lock.permission");
        }
        String memberKey = memberKeyOf(refusal);
        if (memberKey != null) {
            return contentLine(memberKey, refusal.param());
        }
        Message name = FactorNames.name(refusal.factorId(), refusal.param());
        if (name == null) {
            return text("lock.prerequisites");
        }
        String bound = boundOf(refusal);
        if (bound == null) {
            return text("lock.factor", name);
        }
        String current = numberOf(refusal.value());
        return current != null
                ? text("lock.factor.bound.current", name, bound, current)
                : text("lock.factor.bound", name, bound);
    }

    /**
     * The record twin of {@link #isSpecific(String)}: a prerequisite always, a permission always, a composite
     * when it carries children to describe, a factor when a file names it or it is a member factor naming its
     * content.
     */
    public static boolean isSpecific(@Nonnull GateRefusal refusal) {
        if (refusal.kind() == GateRefusal.Kind.QUEST || refusal.kind() == GateRefusal.Kind.PERMISSION) {
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
        if (memberKeyOf(refusal) != null) {
            return true;
        }
        return FactorNames.name(refusal.factorId(), refusal.param()) != null;
    }

    /**
     * The identity a rendered record deduplicates under: a prerequisite and a member factor by the line key
     * and the content they name (so the leaf and the factor spelling of one requirement render once); both
     * permission spellings share one bucket; a named factor by everything its sentence says (id, param,
     * bounds); a composite by its kind and each child's own key; everything that folds to a fixed sentence by
     * that sentence.
     */
    @Nonnull
    public static String dedupeKey(@Nonnull GateRefusal refusal) {
        if (refusal.kind() == GateRefusal.Kind.QUEST) {
            return "member:" + QUEST_LINE_KEY + "#" + refusal.questId();
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
        String memberKey = memberKeyOf(refusal);
        if (memberKey != null) {
            return "member:" + memberKey + "#" + refusal.param();
        }
        if (isSpecific(refusal)) {
            return refusal.token() + "#" + refusal.min() + "/" + refusal.max();
        }
        return "prerequisites";
    }

    // ==================== helpers ====================

    /** The member line key a factor refusal reads with, or null when it is no member factor or names nothing. */
    @Nullable
    private static String memberKeyOf(@Nonnull GateRefusal refusal) {
        if (refusal.kind() != GateRefusal.Kind.FACTOR || refusal.param() == null || refusal.factorId() == null
                || refusal.factorId().isBlank()) {
            return null;
        }
        return MEMBERS.get(RegistryLedger.normalize(refusal.factorId()));
    }

    private static boolean isComposite(@Nonnull GateRefusal refusal) {
        return refusal.kind() == GateRefusal.Kind.ANY_OF || refusal.kind() == GateRefusal.Kind.NOT
                || refusal.kind() == GateRefusal.Kind.ALL_OF;
    }

    @Nullable
    private static Registered familyOf(@Nonnull String token) {
        if (FAMILIES.isEmpty()) {
            return null;
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        Registered best = null;
        for (Registered registered : FAMILIES.values()) {
            if (t.startsWith(registered.prefix())
                    && (best == null || registered.prefix().length() > best.prefix().length())) {
                best = registered;
            }
        }
        return best;
    }

    @Nullable
    private static Message ask(@Nonnull Registered family, @Nonnull String token, long waitMs) {
        try {
            return family.family().line(token, waitMs);
        } catch (Throwable t) {
            SafeLog.warn("[gate] the refusal lines of '" + family.owner() + "' failed for '" + token + "': "
                    + t.getMessage());
            return null;
        }
    }

    /**
     * A composite's children folded into ONE message that can sit in a {@code {0}} PARAM position. Built with
     * {@link Msg#cat}, never {@link Msg#join}: a param renders only when it carries raw text or a message id,
     * which a bare join result does not.
     */
    @Nonnull
    private static Message fold(@Nonnull List<GateRefusal> children, @Nonnull Supplier<Message> separator) {
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

    /** A line naming one piece of content: its title where the seam knows it, else its id. */
    @Nonnull
    private static Message contentLine(@Nonnull String fullKey, @Nullable String contentId) {
        String id = contentId == null ? "" : contentId;
        Message title = titleOf(id);
        return Msg.key(fullKey, title != null ? title : Msg.raw(id));
    }

    @Nullable
    private static Message titleOf(@Nonnull String contentId) {
        Function<String, Message> source = titles;
        if (source == null || contentId.isBlank()) {
            return null;
        }
        try {
            return source.apply(contentId);
        } catch (Throwable t) {
            SafeLog.warn("[gate] naming '" + contentId + "' for a refusal line failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * The one number a factor line quotes, grouped: the lower bound when the condition has one (the thing to
     * go and raise), else the upper bound, else null. A {@code Min} of exactly 1 with no {@code Max} is the
     * PRESENCE idiom, and quoting its 1 turns "Requires X" into noise, so that one shape reads unbounded.
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
        return Msg.tr(NS, DOMAIN + key, args);
    }
}
