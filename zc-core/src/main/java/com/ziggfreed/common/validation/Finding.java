package com.ziggfreed.common.validation;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * ONE audit finding, shared by every validator in this library.
 *
 * <p>The whole reason the validators exist is that content mistakes fail SILENTLY: a selector that
 * names nothing, a step listening for a moment nothing fires, a factor no registry provides. None
 * of them throws, none of them logs; the content simply never appears. A finding is how that
 * silence is made visible at load, while the author still has the file open.
 *
 * <p>The fields are deliberately neutral so a consumer can fold findings from several domains into
 * one report without learning a shape per validator:
 *
 * <ul>
 *   <li>{@code severity} - {@link Severity#ERROR} / {@link Severity#WARNING} / {@link Severity#INFO}</li>
 *   <li>{@code code} - a STABLE machine token ({@code "MISSING_NAMES"}, {@code "CYCLE"}). It is part
 *       of each validator's contract: a consumer may filter, suppress or re-tier on it, so a code is
 *       renamed only as a deliberate break.</li>
 *   <li>{@code message} - the human sentence, written for the pack author who has to fix it. Say
 *       what is wrong AND what it costs at runtime, because "Names is required" alone never tells
 *       anyone why their NPC is missing.</li>
 *   <li>{@code sourceId} - the asset id (or a caller-supplied context label such as
 *       {@code "mmo_hub.Where"}) the finding came from</li>
 *   <li>{@code domain} - which content family reported it ({@code "quest"}, {@code "placement"}).
 *       Blank means unstamped; {@link #withDomain} stamps one on when a consumer folds a validator's
 *       output into a wider audit.</li>
 * </ul>
 */
public record Finding(@Nonnull Severity severity, @Nonnull String code, @Nonnull String message,
                      @Nonnull String sourceId, @Nonnull String domain) {

    /** An unstamped finding's domain: the emitting validator did not name one. */
    public static final String NO_DOMAIN = "";

    public Finding {
        severity = severity == null ? Severity.WARNING : severity;
        code = code == null ? "" : code.trim();
        message = message == null ? "" : message.trim();
        sourceId = sourceId == null ? "" : sourceId.trim();
        domain = domain == null ? NO_DOMAIN : domain.trim().toLowerCase(Locale.ROOT);
    }

    // ==================== domain-less factories ====================
    // For a validator whose findings are stamped with a domain by whoever folds them in.

    @Nonnull
    public static Finding error(@Nonnull String code, @Nonnull String message, @Nonnull String sourceId) {
        return new Finding(Severity.ERROR, code, message, sourceId, NO_DOMAIN);
    }

    @Nonnull
    public static Finding warning(@Nonnull String code, @Nonnull String message, @Nonnull String sourceId) {
        return new Finding(Severity.WARNING, code, message, sourceId, NO_DOMAIN);
    }

    @Nonnull
    public static Finding info(@Nonnull String code, @Nonnull String message, @Nonnull String sourceId) {
        return new Finding(Severity.INFO, code, message, sourceId, NO_DOMAIN);
    }

    // ==================== domain-stamped factories ====================

    @Nonnull
    public static Finding error(@Nonnull String domain, @Nonnull String code, @Nonnull String message,
            @Nonnull String sourceId) {
        return new Finding(Severity.ERROR, code, message, sourceId, domain);
    }

    @Nonnull
    public static Finding warning(@Nonnull String domain, @Nonnull String code, @Nonnull String message,
            @Nonnull String sourceId) {
        return new Finding(Severity.WARNING, code, message, sourceId, domain);
    }

    @Nonnull
    public static Finding info(@Nonnull String domain, @Nonnull String code, @Nonnull String message,
            @Nonnull String sourceId) {
        return new Finding(Severity.INFO, code, message, sourceId, domain);
    }

    /** Is this something to fix (see {@link Severity#isProblem()})? */
    public boolean isProblem() {
        return severity.isProblem();
    }

    /** Was a domain stamped on? */
    public boolean hasDomain() {
        return !domain.isEmpty();
    }

    /**
     * This finding filed under {@code domain} - what a consumer folding one validator's output into
     * a wider audit calls. An already-stamped finding keeps its own domain, so folding a report that
     * already spans domains never relabels the parts.
     */
    @Nonnull
    public Finding withDomain(@Nullable String domain) {
        if (domain == null || domain.isBlank() || hasDomain()) {
            return this;
        }
        return new Finding(severity, code, message, sourceId, domain);
    }
}
