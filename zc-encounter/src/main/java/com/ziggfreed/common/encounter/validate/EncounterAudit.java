package com.ziggfreed.common.encounter.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.asset.EncounterParticipationConfig;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * One pass over every piece of authored encounter content: the loaded scripts, the binding rows and
 * the participation rules, each read against the others.
 *
 * <p>Total and fail-soft: a store that throws costs its own findings and one line, never the whole
 * audit. The boot-time pass is gated to once per boot and hung off first player ready, because the
 * scripts and the loot tables it asks about are only all loaded by then; {@code /zigencounter
 * validate} is the on-demand form and is never gated.
 */
public final class EncounterAudit {

    private static final AtomicBoolean LATE_AUDIT_RAN = new AtomicBoolean();

    private EncounterAudit() {
    }

    /** Audit every encounter domain and answer the findings together. */
    @Nonnull
    public static List<Finding> auditAll() {
        List<Finding> out = new ArrayList<>();
        try {
            out.addAll(EncounterValidator.validate(EncounterScripts.scanLoaded(),
                    EncounterBindingConfig.getInstance().all().values(),
                    EncounterParticipationConfig.getInstance().all().values(), lootables()));
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " the encounter content could not be audited", t);
        }
        return out;
    }

    /** Push findings already in hand at the server log, split by how much each one matters. */
    public static void log(@Nonnull List<Finding> findings) {
        ValidationReport.logAll(Encounters.LOG_PREFIX + " content", findings, SafeLog::warn, SafeLog::info);
    }

    /** Audit and log in one call. */
    public static void runAndLog() {
        log(auditAll());
    }

    /** The boot-time pass, once per boot. */
    public static void runLateAudit() {
        if (LATE_AUDIT_RAN.compareAndSet(false, true)) {
            runAndLog();
        }
    }

    /** Which shared loot tables are loaded, or null when nothing can say. */
    @Nullable
    private static Predicate<String> lootables() {
        try {
            LootableConfig config = LootableConfig.getInstance();
            return config::has;
        } catch (Throwable t) {
            return null;
        }
    }
}
