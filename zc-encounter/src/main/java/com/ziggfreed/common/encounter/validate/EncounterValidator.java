package com.ziggfreed.common.encounter.validate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterParticipationAsset;
import com.ziggfreed.common.encounter.asset.ParticipationSpec;
import com.ziggfreed.common.encounter.signal.EncounterSignal;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.LootableValidator;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.validation.Finding;

/**
 * Reads authored encounter content the way an author would want it read: the mistakes that produce
 * SILENCE. A mistyped {@code zc:} beat fires nothing and nobody is told; a binding naming a script
 * that does not exist binds nothing; a subject slot the script never fills means no credit, no scale
 * and no defeat; a defeat beat with no bar clear leaves a dead boss's bar on every screen; a
 * blocking action list under a sensor marked {@code Once} runs its first action and stops dead; a
 * prefab whose spawner block carries no per-block state pastes an egg sack with no marker under it
 * ({@link EncounterPrefabAudit}, read by the audit over every loaded pack).
 *
 * <p>Severity says what it means. An ERROR is content that cannot work as written (a defeat beat
 * that does not clear the bar is one, by rule). A WARNING is content that works but almost certainly
 * does not do what was intended, and an unknown id is always a warning rather than an error: the
 * pack that would answer it may simply not be installed here. A note is worth saying once.
 *
 * <p>The loot a fight pays (a binding's {@code Loot.OnDefeat} and each {@code Loot.OnPhase} state,
 * and the {@code Loot} a script's {@code ZigGrant} action carries) is read by the loot core's own
 * rules ({@link LootableValidator#auditRef(LootRef, String, RewardKindRegistry, Set)}) when the
 * caller hands over the reward vocabulary: none of those payouts carries a pass collector, so a
 * reward kind that only pays into one is reported there. A table this validator already reports as
 * missing is not reported a second time by the shared rule.
 */
public final class EncounterValidator {

    /** The domain every finding here is stamped with. */
    public static final String DOMAIN = "encounter";

    // Codes, stable so an owner can grep a boot log for one.
    public static final String UNKNOWN_MOMENT = "ENCOUNTER_UNKNOWN_MOMENT";
    public static final String PHASE_WITHOUT_STATE = "ENCOUNTER_PHASE_WITHOUT_STATE";
    public static final String DEFEAT_WITHOUT_BAR_CLEAR = "ENCOUNTER_DEFEAT_WITHOUT_BAR_CLEAR";
    public static final String BINDING_UNKNOWN_SCRIPT = "ENCOUNTER_BINDING_UNKNOWN_SCRIPT";
    public static final String BINDING_NOT_SPAWNABLE = "ENCOUNTER_BINDING_NOT_SPAWNABLE";
    public static final String DUPLICATE_BINDING = "ENCOUNTER_DUPLICATE_BINDING";
    public static final String SCRIPT_UNBOUND = "ENCOUNTER_SCRIPT_UNBOUND";
    public static final String SUBJECT_SLOT_UNKNOWN = "ENCOUNTER_SUBJECT_SLOT_UNKNOWN";
    public static final String UNKNOWN_LOOTABLE = "ENCOUNTER_UNKNOWN_LOOTABLE";
    public static final String PRESENCE_WITHOUT_COLLECTOR = "ENCOUNTER_PRESENCE_WITHOUT_COLLECTOR";
    public static final String RULE_MIN_SHARE_OUT_OF_RANGE = "ENCOUNTER_RULE_MIN_SHARE_OUT_OF_RANGE";
    public static final String RULE_BAD_MATCH = "ENCOUNTER_RULE_BAD_MATCH";
    public static final String ONCE_BLOCKS_LIST = "ENCOUNTER_ONCE_BLOCKS_LIST";
    public static final String SCRIPT_ID_IS_ROLE_ID = "ENCOUNTER_SCRIPT_ID_IS_ROLE_ID";
    /** A pack prefab's spawner block with no per-block state; the check itself is {@link EncounterPrefabAudit}. */
    public static final String PREFAB_SPAWNER_WITHOUT_STATE = "ENCOUNTER_PREFAB_SPAWNER_WITHOUT_STATE";

    /** The naming convention that keeps a script's id off every role's: a trailing suffix. */
    public static final String SCRIPT_ID_SUFFIX = "_Encounter";

    /** The script action that pays loot from inside the fight, and the key its loot sits under. */
    private static final String GRANT_TYPE = "ZigGrant";
    private static final String GRANT_LOOT = "Loot";

    /** How close a custom moment word has to be to a reserved one to read as a typo. */
    private static final int TYPO_DISTANCE = 2;

    /**
     * Something on this server that names an NPC role by id: a spawn marker's roster entry, an NPC
     * placement's {@code Identity.Role}. The validator reads these against the loaded scripts,
     * because the engine keeps ONE builder per name across roles and encounter scripts, so a
     * script named after a role replaces the role at load and every reference to that role is
     * left pointing at a fight.
     *
     * @param roleId  the role id as written
     * @param kind    what kind of thing names it ({@code spawn marker}, {@code placement}), for the message
     * @param namedBy the id of the thing naming it, the finding's source
     */
    public record RoleReference(@Nonnull String roleId, @Nonnull String kind, @Nonnull String namedBy) {
    }

    private EncounterValidator() {
    }

    /**
     * Audit everything: the scripts as scanned, the bindings, the rules.
     *
     * @param lootableExists answers whether a shared loot table id is loaded, or null to skip that check
     */
    @Nonnull
    public static List<Finding> validate(@Nonnull Map<String, EncounterScriptScan> scripts,
            @Nonnull Collection<EncounterBindingAsset> bindings,
            @Nonnull Collection<EncounterParticipationAsset> rules,
            @Nullable Predicate<String> lootableExists) {
        return validate(scripts, bindings, rules, lootableExists, List.of(), null);
    }

    /**
     * Audit everything, including every place a role id is named against the scripts.
     *
     * @param lootableExists answers whether a shared loot table id is loaded, or null to skip that check
     * @param roleReferences every role id something on this server names, with what names it
     * @param roleExists     answers whether an id resolves to a loaded NPC role, or null when nothing can say
     */
    @Nonnull
    public static List<Finding> validate(@Nonnull Map<String, EncounterScriptScan> scripts,
            @Nonnull Collection<EncounterBindingAsset> bindings,
            @Nonnull Collection<EncounterParticipationAsset> rules,
            @Nullable Predicate<String> lootableExists,
            @Nonnull Collection<RoleReference> roleReferences,
            @Nullable Predicate<String> roleExists) {
        return validate(scripts, bindings, rules, lootableExists, roleReferences, roleExists, Map.of(), null, false);
    }

    /**
     * Audit everything, and read every loot payout a fight makes by the loot core's rules: each
     * binding's {@code Loot.OnDefeat} and {@code Loot.OnPhase} states, and each script's
     * {@code ZigGrant} loot. None of those payouts carries a pass collector, so a collecting reward
     * kind written in their inline rolls is a warning.
     *
     * @param lootableExists answers whether a shared loot table id is loaded, or null to skip that check
     * @param roleReferences every role id something on this server names, with what names it
     * @param roleExists     answers whether an id resolves to a loaded NPC role, or null when nothing can say
     * @param grantLoots     each script's {@code ZigGrant} loot, by script id ({@link #zigGrantLoots})
     * @param kinds          the reward vocabulary the payouts pay through, or null to skip the kind checks
     */
    @Nonnull
    public static List<Finding> validate(@Nonnull Map<String, EncounterScriptScan> scripts,
            @Nonnull Collection<EncounterBindingAsset> bindings,
            @Nonnull Collection<EncounterParticipationAsset> rules,
            @Nullable Predicate<String> lootableExists,
            @Nonnull Collection<RoleReference> roleReferences,
            @Nullable Predicate<String> roleExists,
            @Nonnull Map<String, List<LootRef>> grantLoots,
            @Nullable RewardKindRegistry kinds) {
        return validate(scripts, bindings, rules, lootableExists, roleReferences, roleExists, grantLoots, kinds, true);
    }

    @Nonnull
    private static List<Finding> validate(@Nonnull Map<String, EncounterScriptScan> scripts,
            @Nonnull Collection<EncounterBindingAsset> bindings,
            @Nonnull Collection<EncounterParticipationAsset> rules,
            @Nullable Predicate<String> lootableExists,
            @Nonnull Collection<RoleReference> roleReferences,
            @Nullable Predicate<String> roleExists,
            @Nonnull Map<String, List<LootRef>> grantLoots,
            @Nullable RewardKindRegistry kinds,
            boolean auditLoot) {
        List<Finding> findings = new ArrayList<>();
        Map<String, EncounterScriptScan> byLowerId = new HashMap<>();
        for (EncounterScriptScan scan : scripts.values()) {
            byLowerId.put(scan.id().toLowerCase(Locale.ROOT), scan);
            findings.addAll(auditScript(scan));
        }
        Map<String, EncounterBindingAsset> boundScripts = new HashMap<>();
        LootAudit loot = auditLoot ? new LootAudit(kinds) : null;
        for (EncounterBindingAsset row : bindings) {
            findings.addAll(auditBinding(row, byLowerId, boundScripts, rules, lootableExists, roleExists, loot));
        }
        if (loot != null) {
            for (Map.Entry<String, List<LootRef>> script : grantLoots.entrySet()) {
                List<LootRef> refs = script.getValue();
                if (script.getKey() == null || refs == null) {
                    continue;
                }
                for (int i = 0; i < refs.size(); i++) {
                    String where = script.getKey() + " " + GRANT_TYPE + (refs.size() > 1 ? " " + (i + 1) : "")
                            + " " + GRANT_LOOT;
                    // Nothing else reports a ZigGrant's missing table, so the shared line stands.
                    findings.addAll(loot.audit(refs.get(i), where, false));
                }
            }
        }
        for (RoleReference reference : roleReferences) {
            EncounterScriptScan script = byLowerId.get(reference.roleId().toLowerCase(Locale.ROOT));
            if (script != null) {
                findings.add(Finding.warning(DOMAIN, SCRIPT_ID_IS_ROLE_ID, "This " + reference.kind()
                        + " names the role '" + reference.roleId() + "', but that id resolves to the encounter "
                        + "script '" + script.id() + "': the engine keeps one builder per name across roles and "
                        + "scripts, the script loaded last, and the role is gone, so nothing can spawn it. Rename "
                        + "the script (a trailing " + SCRIPT_ID_SUFFIX + " is the convention) and follow its "
                        + "references.", reference.namedBy()));
            }
        }
        for (EncounterScriptScan scan : scripts.values()) {
            if (scan.spawnable() && scan.firesFrameworkSignals()
                    && !boundScripts.containsKey(scan.id().toLowerCase(Locale.ROOT))) {
                findings.add(Finding.info(DOMAIN, SCRIPT_UNBOUND, "This script fires zc: beats but no binding row "
                        + "names it, so its fight is announced and credited under the library's defaults and pays "
                        + "only what a ZigGrant inside it says.", scan.id()));
            }
        }
        for (EncounterParticipationAsset rule : rules) {
            findings.addAll(auditRule(rule));
        }
        return findings;
    }

    /** One script's own findings. */
    @Nonnull
    public static List<Finding> auditScript(@Nonnull EncounterScriptScan scan) {
        List<Finding> findings = new ArrayList<>();
        for (String signalId : scan.signals()) {
            EncounterSignal signal = EncounterSignal.parse(signalId);
            if (signal == null) {
                continue;
            }
            if (signal.moment() == EncounterSignal.Moment.PHASE && signal.detail() == null) {
                findings.add(Finding.error(DOMAIN, PHASE_WITHOUT_STATE, "'" + signalId + "' names no state after "
                        + "zc:phase:, so no phase can be recorded; write zc:phase:<StateName>.", scan.id()));
            }
            if (signal.moment() == EncounterSignal.Moment.CUSTOM) {
                String word = firstWord(signal.detail());
                String near = nearestReserved(word);
                if (near != null) {
                    findings.add(Finding.warning(DOMAIN, UNKNOWN_MOMENT, "'" + signalId + "' reads like a misspelling "
                            + "of zc:" + near + "; as written it fires only the generic signal event.", scan.id()));
                }
            }
        }
        if (scan.defeatBeatsWithoutBarClear() > 0) {
            findings.add(Finding.error(DOMAIN, DEFEAT_WITHOUT_BAR_CLEAR, "A zc:defeated beat has no "
                    + "ClearEncounterBossBar beside it, so a dead boss's bar stays on every member's screen "
                    + "until the corpse is removed; author the clear in the same action list.", scan.id()));
        }
        if (scan.onceHeadedBlockingLists() > 0) {
            findings.add(Finding.warning(DOMAIN, ONCE_BLOCKS_LIST, "A blocking action list sits under a sensor "
                    + "marked Once, so only its first action ever runs: the sensor is spent after the first tick "
                    + "and the rest of the list is never reached. Leave Once off that sensor and end the list "
                    + "with the state change that stops it.", scan.id()));
        }
        return findings;
    }

    @Nonnull
    private static List<Finding> auditBinding(@Nonnull EncounterBindingAsset row,
            @Nonnull Map<String, EncounterScriptScan> scripts, @Nonnull Map<String, EncounterBindingAsset> boundScripts,
            @Nonnull Collection<EncounterParticipationAsset> rules, @Nullable Predicate<String> lootableExists,
            @Nullable Predicate<String> roleExists, @Nullable LootAudit loot) {
        List<Finding> findings = new ArrayList<>();
        String id = row.getId();
        String script = row.encounterAsset();
        String scriptLower = script.toLowerCase(Locale.ROOT);
        EncounterBindingAsset already = boundScripts.putIfAbsent(scriptLower, row);
        if (already != null) {
            findings.add(Finding.warning(DOMAIN, DUPLICATE_BINDING, "Both '" + already.getId() + "' and '" + id
                    + "' bind the script '" + script + "'; only the first by id is read.", id));
        }
        EncounterScriptScan scan = scripts.get(scriptLower);
        if (scan == null) {
            if (roleExists != null && roleExists.test(script)) {
                findings.add(Finding.warning(DOMAIN, SCRIPT_ID_IS_ROLE_ID, "'" + script + "' resolves to an NPC role, "
                        + "not an encounter script: the engine keeps one builder per name across roles and scripts, "
                        + "the role loaded last, and the script is gone, so this binding binds nothing. Rename the "
                        + "script (a trailing " + SCRIPT_ID_SUFFIX + " is the convention) and follow its references, "
                        + "this binding's EncounterAsset included.", id));
            } else if (!scripts.isEmpty()) {
                findings.add(Finding.warning(DOMAIN, BINDING_UNKNOWN_SCRIPT, "No encounter script called '" + script
                        + "' is loaded, so this binding binds nothing. That is expected when the pack shipping it is "
                        + "not installed; check the spelling otherwise.", id));
            }
        } else {
            if (!scan.spawnable()) {
                findings.add(Finding.warning(DOMAIN, BINDING_NOT_SPAWNABLE, "'" + script + "' is an Abstract base, "
                        + "which is never spawned; bind the Variant or Generic script built on it.", id));
            }
            EncounterBindingAsset.Subject subject = row.getSubject();
            String slot = subject == null ? EncounterBindingAsset.DEFAULT_TARGET_SLOT : subject.targetSlot();
            if (!scan.targetSlots().isEmpty() && !scan.targetSlots().contains(slot)
                    && (subject == null || !subject.anyOccupiedSlot())) {
                findings.add(Finding.warning(DOMAIN, SUBJECT_SLOT_UNKNOWN, "Subject.TargetSlot '" + slot
                        + "' is a slot the script never names (it names " + String.join(", ", scan.targetSlots())
                        + "), so no subject would bind; name the script's slot, or set AnyOccupiedSlot.", id));
            }
            if (!scan.memberCollector() && presenceCounts(row, rules)) {
                findings.add(Finding.warning(DOMAIN, PRESENCE_WITHOUT_COLLECTOR, "Presence is weighted for this "
                        + "fight, but '" + script + "' has no EncounterMembers collector on its Player sensor, so "
                        + "nobody ever counts as present.", id));
            }
        }
        if (row.getLoot() != null) {
            checkLoot(findings, id, "Loot.OnDefeat", row.getLoot().getOnDefeat(), lootableExists, loot);
            Map<String, LootRef> onPhase = row.getLoot().getOnPhase();
            if (onPhase != null) {
                for (Map.Entry<String, LootRef> entry : onPhase.entrySet()) {
                    checkLoot(findings, id, "Loot.OnPhase." + entry.getKey(), entry.getValue(), lootableExists, loot);
                }
            }
        }
        EncounterBindingAsset.Participation override = row.getParticipation();
        if (override != null && override.getMinShare() != null
                && (override.getMinShare() < 0.0 || override.getMinShare() > 1.0)) {
            findings.add(Finding.error(DOMAIN, RULE_MIN_SHARE_OUT_OF_RANGE, "Participation.MinShare must be between 0 "
                    + "and 1; it is " + override.getMinShare() + ".", id));
        }
        return findings;
    }

    @Nonnull
    private static List<Finding> auditRule(@Nonnull EncounterParticipationAsset rule) {
        List<Finding> findings = new ArrayList<>();
        if (rule.getMinShare() != null && (rule.getMinShare() < 0.0 || rule.getMinShare() > 1.0)) {
            findings.add(Finding.error(DOMAIN, RULE_MIN_SHARE_OUT_OF_RANGE, "MinShare must be between 0 and 1; it is "
                    + rule.getMinShare() + ".", rule.getId()));
        }
        String match = rule.getMatch();
        if (match != null && !match.isBlank()) {
            String core = match.trim();
            if (core.length() > 1 && core.substring(1, core.length() - 1).indexOf('*') >= 0) {
                findings.add(Finding.warning(DOMAIN, RULE_BAD_MATCH, "Match '" + match + "' carries a * in the middle; "
                        + "only a leading or trailing * is a wildcard, so this matches nothing.", rule.getId()));
            }
        }
        return findings;
    }

    /**
     * One binding loot leaf: its tables against what is loaded (when something can say), then, when
     * the loot audit runs, the loot core's rules over the whole ref, minus the shared unknown-table
     * line whenever the check above already judged the tables.
     */
    private static void checkLoot(@Nonnull List<Finding> findings, @Nonnull String id, @Nonnull String leaf,
            @Nullable LootRef ref, @Nullable Predicate<String> lootableExists, @Nullable LootAudit loot) {
        if (lootableExists != null) {
            checkTables(findings, id, leaf, ref, lootableExists);
        }
        if (loot != null) {
            findings.addAll(loot.audit(ref, id + " " + leaf, lootableExists != null));
        }
    }

    private static void checkTables(@Nonnull List<Finding> findings, @Nonnull String id, @Nonnull String leaf,
            @Nullable LootRef ref, @Nonnull Predicate<String> lootableExists) {
        if (ref == null || ref.getLootables() == null) {
            return;
        }
        for (String table : ref.getLootables()) {
            if (table == null || table.isBlank()) {
                continue;
            }
            if (!lootableExists.test(table)) {
                findings.add(Finding.warning(DOMAIN, UNKNOWN_LOOTABLE, leaf + " names the loot table '" + table
                        + "', which nothing on this server defines, so that part pays nothing.", id));
            }
        }
    }

    /**
     * The loot core's audit of one fight payout. No encounter payout layers a pass collector on the
     * subject it grants through, so every ref is audited against an empty carried set. Fail-soft: an
     * audit that cannot run says nothing rather than a false line.
     */
    private record LootAudit(@Nullable RewardKindRegistry kinds) {

        @Nonnull
        List<Finding> audit(@Nullable LootRef ref, @Nonnull String where, boolean tablesJudged) {
            List<Finding> out = new ArrayList<>();
            if (ref == null) {
                return out;
            }
            try {
                for (Finding finding : LootableValidator.auditRef(ref, where, kinds, Set.of())) {
                    if (!(tablesJudged && LootableValidator.UNKNOWN_TABLE.equals(finding.code()))) {
                        out.add(finding);
                    }
                }
            } catch (Throwable t) {
                return new ArrayList<>();
            }
            return out;
        }
    }

    /**
     * Every {@code ZigGrant} action's {@code Loot} in one script, in authored order, read off its
     * JSON the way the action reads it, following a {@code Reference} (a Variant's base, a macro's
     * Content) through {@code referenced}, each referenced builder once. A loot that does not decode
     * is left out (the action warns about it when it loads), and a loot behind a {@code Compute} is
     * not seen, so an unusual shape can hide a finding but never invent one.
     *
     * @param referenced resolves a referenced builder name to its file's root object, or null
     */
    @Nonnull
    public static List<LootRef> zigGrantLoots(@Nonnull JsonObject root, @Nonnull Function<String, JsonObject> referenced) {
        List<LootRef> out = new ArrayList<>();
        collectGrants(root, referenced, new HashSet<>(), out, 0);
        return out;
    }

    private static void collectGrants(@Nonnull JsonElement element, @Nonnull Function<String, JsonObject> referenced,
            @Nonnull Set<String> followed, @Nonnull List<LootRef> out, int depth) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectGrants(child, referenced, followed, out, depth);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement type = object.get("Type");
        if (type != null && type.isJsonPrimitive() && GRANT_TYPE.equals(type.getAsString())) {
            LootRef loot = decodeLoot(object.get(GRANT_LOOT));
            if (loot != null && !loot.isEmpty()) {
                out.add(loot);
            }
        }
        JsonElement reference = object.get("Reference");
        if (reference != null && reference.isJsonPrimitive() && depth < EncounterScriptScan.MAX_REFERENCE_DEPTH
                && followed.add(reference.getAsString())) {
            JsonObject target = null;
            try {
                target = referenced.apply(reference.getAsString());
            } catch (RuntimeException e) {
                // An unresolvable reference hides what it holds; it never invents a finding.
            }
            if (target != null) {
                collectGrants(target, referenced, followed, out, depth + 1);
            }
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            if (!"Reference".equals(key) && !"Modify".equals(key) && !"Parameters".equals(key)) {
                collectGrants(entry.getValue(), referenced, followed, out, depth);
            }
        }
    }

    /** A {@code ZigGrant}'s {@code Loot} as the action decodes it, or null when it is absent or unreadable. */
    @Nullable
    private static LootRef decodeLoot(@Nullable JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) {
            return null;
        }
        try {
            return LootRef.CODEC.decodeJson(RawJsonReader.fromJsonString(raw.toString()), new ExtraInfo());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether presence earns anything for this row: its own override, else the catch-all rule. */
    private static boolean presenceCounts(@Nonnull EncounterBindingAsset row,
            @Nonnull Collection<EncounterParticipationAsset> rules) {
        EncounterBindingAsset.Participation override = row.getParticipation();
        if (override != null && override.getPresence() != null) {
            return weighs(override.getPresence());
        }
        for (EncounterParticipationAsset rule : rules) {
            if (rule.isEnabled() && "*".equals(rule.matchOrAll())
                    && (rule.getWhere() == null || rule.getWhere().isBlank())) {
                return rule.getPresence() != null && weighs(rule.getPresence());
            }
        }
        return ParticipationSpec.STRUCTURAL.presence() != null && weighs(ParticipationSpec.STRUCTURAL.presence());
    }

    private static boolean weighs(@Nonnull FactorFormula formula) {
        return formula.baseOrZero() > 0.0 || !formula.hasNoTerms();
    }

    @Nonnull
    private static String firstWord(@Nullable String detail) {
        if (detail == null) {
            return "";
        }
        int colon = detail.indexOf(':');
        return (colon < 0 ? detail : detail.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
    }

    /** The reserved moment {@code word} looks like a misspelling of, or null when it looks like its own thing. */
    @Nullable
    static String nearestReserved(@Nonnull String word) {
        if (word.length() < 4) {
            return null;
        }
        for (EncounterSignal.Moment moment : EncounterSignal.Moment.values()) {
            if (moment == EncounterSignal.Moment.CUSTOM) {
                continue;
            }
            String reserved = moment.word();
            if (!word.equals(reserved) && (distance(word, reserved) <= TYPO_DISTANCE
                    || word.startsWith(reserved) || reserved.startsWith(word))) {
                return reserved;
            }
        }
        return null;
    }

    /** The edit distance between two short words. */
    static int distance(@Nonnull String a, @Nonnull String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
