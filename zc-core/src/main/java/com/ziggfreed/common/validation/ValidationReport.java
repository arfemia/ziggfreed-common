package com.ziggfreed.common.validation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The counting and printing half of the audit: given a list of {@link Finding}s, how many of each
 * tier there are, what each one reads as on one line, and how to push the whole lot at a log.
 *
 * <p>It exists so the four-line "walk the findings, build a line, route ERROR one way and the rest
 * another" loop is written ONCE rather than once per config that folds an asset layer. Every sink
 * is a plain {@link Consumer} of a finished line, so this class needs no logger, no engine type and
 * no knowledge of who is reading - a config passes its guarded log methods, a command passes a chat
 * sink, a test passes a list.
 */
public final class ValidationReport {

    private ValidationReport() {
    }

    // ==================== counting ====================

    /** How many findings sit at {@code severity}. */
    public static int count(@Nullable Collection<Finding> findings, @Nonnull Severity severity) {
        if (findings == null) {
            return 0;
        }
        int n = 0;
        for (Finding finding : findings) {
            if (finding != null && finding.severity() == severity) {
                n++;
            }
        }
        return n;
    }

    public static int errorCount(@Nullable Collection<Finding> findings) {
        return count(findings, Severity.ERROR);
    }

    public static int warningCount(@Nullable Collection<Finding> findings) {
        return count(findings, Severity.WARNING);
    }

    public static int infoCount(@Nullable Collection<Finding> findings) {
        return count(findings, Severity.INFO);
    }

    /**
     * How many findings are something to FIX (errors plus warnings). Remarks are excluded on
     * purpose, so a "2 problems" headline never counts a note about a redundant knob.
     */
    public static int problemCount(@Nullable Collection<Finding> findings) {
        if (findings == null) {
            return 0;
        }
        int n = 0;
        for (Finding finding : findings) {
            if (finding != null && finding.isProblem()) {
                n++;
            }
        }
        return n;
    }

    /** Does anything here make the content unable to work? */
    public static boolean hasErrors(@Nullable Collection<Finding> findings) {
        return errorCount(findings) > 0;
    }

    /** Only the findings at {@code severity}, in the order given. */
    @Nonnull
    public static List<Finding> filter(@Nullable Collection<Finding> findings, @Nonnull Severity severity) {
        List<Finding> out = new ArrayList<>();
        if (findings != null) {
            for (Finding finding : findings) {
                if (finding != null && finding.severity() == severity) {
                    out.add(finding);
                }
            }
        }
        return out;
    }

    // ==================== formatting ====================

    /**
     * One finding as one line: {@code <label> '<sourceId>' [<CODE>]: <message>}. {@code label}
     * names what is being audited ({@code "WorldSelector"}, {@code "quest content"}); a blank label
     * or a blank source id simply drops out of the line rather than printing an empty quote.
     */
    @Nonnull
    public static String format(@Nullable String label, @Nonnull Finding finding) {
        StringBuilder sb = new StringBuilder();
        if (label != null && !label.isBlank()) {
            sb.append(label.trim()).append(' ');
        }
        if (!finding.sourceId().isEmpty()) {
            sb.append('\'').append(finding.sourceId()).append("' ");
        }
        sb.append('[').append(finding.code()).append("]: ").append(finding.message());
        return sb.toString();
    }

    /** Every finding as its own {@link #format} line, in the order given. */
    @Nonnull
    public static List<String> formatAll(@Nullable String label, @Nullable Collection<Finding> findings) {
        List<String> out = new ArrayList<>();
        if (findings != null) {
            for (Finding finding : findings) {
                if (finding != null) {
                    out.add(format(label, finding));
                }
            }
        }
        return out;
    }

    /**
     * The one-line headline: {@code "<label>: N errors, N warnings, N notes"}, or a clean bill when
     * there is nothing at all. For a command's summary line above the detail.
     */
    @Nonnull
    public static String summarize(@Nullable String label, @Nullable Collection<Finding> findings) {
        String prefix = label == null || label.isBlank() ? "Content" : label.trim();
        if (findings == null || findings.isEmpty()) {
            return prefix + ": nothing to report";
        }
        return prefix + ": " + plural(errorCount(findings), "error") + ", "
                + plural(warningCount(findings), "warning") + ", "
                + plural(infoCount(findings), "note");
    }

    // ==================== logging ====================

    /**
     * Push every finding at a sink, split by how much it matters: {@link Severity#ERROR} goes to
     * {@code errorSink}, everything else to {@code noteSink}. That split is the always-on baseline
     * a config wants at fold time - an error is worth a warning line in the server log, while a
     * warning about a mod that may not be installed is not.
     *
     * <p>Both sinks are optional. Each call is guarded, so a sink that throws (a flogger LOGGER in a
     * log-manager-less unit JVM does exactly that) costs that one line rather than the whole fold.
     */
    public static void logAll(@Nullable String label, @Nullable Collection<Finding> findings,
            @Nullable Consumer<String> errorSink, @Nullable Consumer<String> noteSink) {
        if (findings == null) {
            return;
        }
        for (Finding finding : findings) {
            if (finding == null) {
                continue;
            }
            Consumer<String> sink = finding.severity() == Severity.ERROR ? errorSink : noteSink;
            if (sink == null) {
                continue;
            }
            try {
                sink.accept(format(label, finding));
            } catch (Throwable ignored) {
                // A log-manager-less unit JVM: the flogger LOGGER can throw. Never take the fold down.
            }
        }
    }

    /** As {@link #logAll} with one sink for every tier. */
    public static void logAll(@Nullable String label, @Nullable Collection<Finding> findings,
            @Nullable Consumer<String> sink) {
        logAll(label, findings, sink, sink);
    }

    // ==================== internals ====================

    @Nonnull
    private static String plural(int n, @Nonnull String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
