package com.ziggfreed.common.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * The content audits a headless boot asks for: run once, on the server's boot event, when the process
 * carries {@value #ENV}{@code =1}. A dev harness boots a server with no player, so the audits that
 * otherwise wait for the first player or for an admin's command never speak; with the switch on they
 * run as soon as every store has folded and every mod has registered its vocabulary.
 *
 * <p>An environment variable rather than an owner-config key: it lives only in the launched process,
 * so it never persists into a run directory or reaches an owner's real server, and it sits beside the
 * MMO's own {@code MMOSKILLTREE_AUDIT_ON_BOOT}, which the same harness exports.
 *
 * <p>Every pass logs through {@link ValidationReport#logAll}: an error at warning level, the rest at
 * info. A reader judging the boot matches records by pattern, not by level. One {@link #MARKER} line
 * counts what the passes found.
 */
public final class BootAudit {

    /** The switch, read from the process environment once, at the boot event. */
    public static final String ENV = "ZIGGFREEDCOMMON_AUDIT_ON_BOOT";

    /** The prefix of the one line that counts the boot audit's findings. */
    public static final String MARKER = "[zc] boot audit: ";

    /** One audit to run: the label its lines carry, and the audit itself. */
    public record Pass(@Nonnull String label, @Nonnull Supplier<List<Finding>> audit) {
    }

    private static final AtomicBoolean RAN = new AtomicBoolean();

    private BootAudit() {
    }

    /** Does this process ask for the boot audit? */
    public static boolean asked() {
        return asked(System.getenv(ENV));
    }

    /** {@code 1} or {@code true} (any case, spacing trimmed) asks; anything else does not. */
    static boolean asked(@Nullable String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return "1".equals(trimmed) || "true".equalsIgnoreCase(trimmed);
    }

    /** Run {@code passes} once per boot when the process asked for it; otherwise do nothing. */
    public static void runIfAsked(@Nonnull List<Pass> passes) {
        if (!asked() || !RAN.compareAndSet(false, true)) {
            return;
        }
        run(passes, SafeLog::warn, SafeLog::info);
    }

    /**
     * Run every pass, log each one's findings under its label, then the marker line. A pass that throws,
     * while it audits or while its answer is read and logged, is reported on the error sink and costs
     * only itself: its answer is copied before any of its lines print, so a broken one prints none of
     * them and counts nothing. The marker line always follows. Answers every finding, in pass order.
     */
    @Nonnull
    static List<Finding> run(@Nonnull List<Pass> passes, @Nonnull Consumer<String> errorSink,
            @Nonnull Consumer<String> noteSink) {
        List<Finding> all = new ArrayList<>();
        int ran = 0;
        for (Pass pass : passes) {
            try {
                List<Finding> found = pass.audit().get();
                List<Finding> safe = found == null ? List.of() : new ArrayList<>(found);
                ValidationReport.logAll(pass.label(), safe, errorSink, noteSink);
                all.addAll(safe);
                ran++;
            } catch (Throwable t) {
                say(errorSink, MARKER + "the pass '" + pass.label() + "' could not run: " + t.getMessage());
            }
        }
        say(noteSink, MARKER + all.size() + " finding(s) (" + ValidationReport.errorCount(all) + " error(s), "
                + ValidationReport.warningCount(all) + " warning(s)) from " + ran + " of " + passes.size()
                + " pass(es)");
        return all;
    }

    /** One line at a sink; a sink that throws costs that line, never the passes after it or the marker. */
    private static void say(@Nonnull Consumer<String> sink, @Nonnull String line) {
        try {
            sink.accept(line);
        } catch (Throwable ignored) {
            // A log-manager-less unit JVM: a flogger-backed sink can throw. The audit goes on.
        }
    }
}
