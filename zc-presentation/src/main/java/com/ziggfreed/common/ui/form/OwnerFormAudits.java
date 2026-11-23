package com.ziggfreed.common.ui.form;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * The content audits an owner form may count ({@link OwnerFormAsset#audit()}): the mod that audits a file registers its
 * audit once at setup under an id, and a form over that file names the id, so the form shows how many problems the
 * audit finds in what it edits (errors and warnings, {@link ValidationReport#problemCount}), asked each time the form is
 * drawn. Ids match without regard to case; registering an id again replaces the audit. An audit is the mod's own, the
 * same one its validator or boot audit runs, never a second copy.
 */
public final class OwnerFormAudits {

    private static final Map<String, Supplier<List<Finding>>> AUDITS = new ConcurrentHashMap<>();

    private OwnerFormAudits() {
    }

    /** Register (or replace) {@code audit} under {@code id}. */
    public static void register(@Nonnull String id, @Nonnull Supplier<List<Finding>> audit) {
        AUDITS.put(key(id), audit);
    }

    /** Has an installed mod registered an audit under {@code id}? */
    public static boolean has(@Nullable String id) {
        return id != null && !id.isBlank() && AUDITS.containsKey(key(id));
    }

    /**
     * How many problems the audit {@code id} names finds right now, or null when no installed mod registered it or it
     * threw (one warning), so the form shows no count rather than a wrong one.
     */
    @Nullable
    public static Integer problems(@Nullable String id) {
        Supplier<List<Finding>> audit = id == null || id.isBlank() ? null : AUDITS.get(key(id));
        if (audit == null) {
            return null;
        }
        try {
            return ValidationReport.problemCount(audit.get());
        } catch (Throwable t) {
            SafeLog.warn("[owner] the audit '" + id + "' an owner form counts could not run: " + t.getMessage());
            return null;
        }
    }

    /** Forget every audit. Tests only. */
    public static void clearForTests() {
        AUDITS.clear();
    }

    @Nonnull
    private static String key(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
