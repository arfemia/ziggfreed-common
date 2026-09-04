package com.ziggfreed.common.encounter.validate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.asset.EncounterParticipationConfig;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.validate.EncounterValidator.RoleReference;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * One pass over every piece of authored encounter content: the loaded scripts, the binding rows and
 * the participation rules, each read against the others, plus every role id the server names read
 * against the scripts (the engine keeps one builder per name across roles and scripts, so a script
 * named after a role silently replaces it).
 *
 * <p>Total and fail-soft: a store that throws costs its own findings and one line, never the whole
 * audit. The boot-time pass is gated to once per boot and hung off first player ready, because the
 * scripts and the loot tables it asks about are only all loaded by then; {@code /zigencounter
 * validate} is the on-demand form and is never gated.
 *
 * <p>The spawn markers are read here, since the engine owns them. Any other store that names a
 * role by id (an NPC placement's identity lives in a module this one may not import) registers
 * itself through {@link #addRoleNameSource}, which the wiring root does for the placement engine.
 */
public final class EncounterAudit {

    private static final AtomicBoolean LATE_AUDIT_RAN = new AtomicBoolean();

    /** Every registered "what names a role" source, by the kind word its findings are worded with. */
    private static final Map<String, Supplier<Map<String, ? extends Collection<String>>>> ROLE_NAME_SOURCES =
            new ConcurrentHashMap<>();

    private EncounterAudit() {
    }

    /**
     * Register a store that names NPC roles by id, so the audit can tell when one of them names an
     * encounter script instead. The supplier answers, per owner id, the role ids that owner names;
     * it is read fresh on every audit and a throwing source costs its own references only.
     *
     * @param kind         what the owners are, in the words the finding uses ({@code placement})
     * @param rolesByOwner the live read, owner id to the role ids it names
     */
    public static void addRoleNameSource(@Nonnull String kind,
            @Nonnull Supplier<Map<String, ? extends Collection<String>>> rolesByOwner) {
        ROLE_NAME_SOURCES.put(kind, rolesByOwner);
    }

    /** Audit every encounter domain and answer the findings together. */
    @Nonnull
    public static List<Finding> auditAll() {
        List<Finding> out = new ArrayList<>();
        try {
            out.addAll(EncounterValidator.validate(EncounterScripts.scanLoaded(),
                    EncounterBindingConfig.getInstance().all().values(),
                    EncounterParticipationConfig.getInstance().all().values(), lootables(),
                    roleReferences(), EncounterScripts.roleExists()));
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " the encounter content could not be audited", t);
        }
        return out;
    }

    /** Every role id named on this server: the spawn markers, then each registered source. */
    @Nonnull
    static List<RoleReference> roleReferences() {
        List<RoleReference> out = new ArrayList<>();
        collect(out, "spawn marker", EncounterScripts.spawnMarkerRoles());
        for (Map.Entry<String, Supplier<Map<String, ? extends Collection<String>>>> source
                : ROLE_NAME_SOURCES.entrySet()) {
            try {
                collect(out, source.getKey(), source.getValue().get());
            } catch (Throwable t) {
                SafeLog.warn(Encounters.LOG_PREFIX + " the " + source.getKey() + " role references could not be read",
                        t);
            }
        }
        return out;
    }

    private static void collect(@Nonnull List<RoleReference> out, @Nonnull String kind,
            @Nullable Map<String, ? extends Collection<String>> rolesByOwner) {
        if (rolesByOwner == null) {
            return;
        }
        for (Map.Entry<String, ? extends Collection<String>> owner : rolesByOwner.entrySet()) {
            if (owner.getKey() == null || owner.getValue() == null) {
                continue;
            }
            for (String role : owner.getValue()) {
                if (role != null && !role.isBlank()) {
                    out.add(new RoleReference(role, kind, owner.getKey()));
                }
            }
        }
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
