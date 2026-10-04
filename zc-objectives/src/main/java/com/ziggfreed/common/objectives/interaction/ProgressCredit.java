package com.ziggfreed.common.objectives.interaction;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.util.SafeLog;

/**
 * What one {@code ZigCreditProgress} fire credits: the objective kind, the target it names, how the
 * item was used, and how much the use counts for. Resolved from what the node authored and the item
 * its chain is about, and nothing else, so it is decided the same way on every server and is tested
 * without one.
 *
 * <p>An unauthored {@code Kind} is {@link #DEFAULT_KIND}; an unauthored {@code Target} is the item
 * used; an unauthored {@code Qualifier} leaves the use unqualified, which a step with no qualifier of
 * its own still counts; an unauthored {@code Amount} is 1. A blank leaf reads as unauthored. A use
 * that can name no target, or an amount below 1, credits nothing: an accumulating step would read a
 * negative amount as progress taken away.
 *
 * @param kind      the objective kind, upper-cased, the way every built-in is spelled
 * @param target    what the use names, trimmed
 * @param qualifier how the item was used, or null for an unqualified use
 * @param amount    how much the use counts for, 1 or more
 */
public record ProgressCredit(@Nonnull String kind, @Nonnull String target, @Nullable String qualifier,
                             long amount) {

    /** The kind a node that names none credits. */
    public static final String DEFAULT_KIND = "USE_ITEM";

    /** Kinds already reported as unable to take a credit, each once per process, upper-cased. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * The credit a node authoring these leaves earns for using {@code usedItemId}, or null when it
     * earns none.
     */
    @Nullable
    public static ProgressCredit resolve(@Nullable String authoredKind, @Nullable String authoredTarget,
            @Nullable String authoredQualifier, @Nullable Integer authoredAmount,
            @Nullable String usedItemId) {
        long amount = authoredAmount == null ? 1L : authoredAmount.longValue();
        if (amount < 1L) {
            return null;
        }
        String target = firstNonBlank(authoredTarget, usedItemId);
        if (target == null) {
            return null;
        }
        String kind = firstNonBlank(authoredKind, DEFAULT_KIND);
        return new ProgressCredit(kind.toUpperCase(Locale.ROOT), target,
                firstNonBlank(authoredQualifier, null), amount);
    }

    /**
     * Why a use cannot be credited to {@code kind} on a server whose vocabulary is {@code kinds}, or
     * null when it can. A kind nobody registered is no kind at all; a value-based kind (a standing
     * reading such as {@code STAT_THRESHOLD}) names a state the engines read themselves, so nothing
     * may fire it.
     */
    @Nullable
    static String refusal(@Nonnull ObjectiveKindRegistry kinds, @Nonnull String kind) {
        if (!kinds.isRegistered(kind)) {
            return "is no objective kind on this server, so no quest or achievement can count it";
        }
        if (kinds.isValueBased(kind)) {
            return "counts a standing value rather than a number of uses, so a use is never credited to it";
        }
        return null;
    }

    /** True the first time {@code kind} is reported into {@code reported}, in any casing. */
    static boolean firstReport(@Nonnull Set<String> reported, @Nonnull String kind) {
        return reported.add(kind.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * May a use be credited to {@code kind}? When it may not, says why once per kind per process, so
     * a node fired on every throw does not repeat the line.
     */
    static boolean creditable(@Nonnull ObjectiveKindRegistry kinds, @Nonnull String kind) {
        String refusal = refusal(kinds, kind);
        if (refusal == null) {
            return true;
        }
        if (firstReport(REPORTED, kind)) {
            SafeLog.warn("[interaction] ZigCreditProgress names the kind '" + kind + "', which " + refusal);
        }
        return false;
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }
}
