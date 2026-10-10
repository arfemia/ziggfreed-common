package com.ziggfreed.common.encounter.validate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.encounter.asset.EncounterRuleAsset;
import com.ziggfreed.common.validation.Finding;

/**
 * Reads the encounter rules the way an author would want them read: a rule that names no role
 * counts nothing (an error), a role no loaded NPC carries counts nothing on this server (a warning,
 * since the pack that ships the role may simply not be installed), and two rules naming one role
 * leave all but the first by id unread (a warning). A rule switched off is not read.
 */
public final class EncounterRuleValidator {

    public static final String RULE_NO_ROLE = "ENCOUNTER_RULE_NO_ROLE";
    public static final String RULE_UNKNOWN_ROLE = "ENCOUNTER_RULE_UNKNOWN_ROLE";
    public static final String RULE_DUPLICATE_ROLE = "ENCOUNTER_RULE_DUPLICATE_ROLE";

    private EncounterRuleValidator() {
    }

    /**
     * @param rowsById   the folded rules by id (a fold's {@code all()})
     * @param roleExists answers whether an id resolves to a loaded NPC role, or null when nothing can say
     */
    @Nonnull
    public static List<Finding> validate(@Nonnull Map<String, EncounterRuleAsset> rowsById,
            @Nullable Predicate<String> roleExists) {
        List<Finding> findings = new ArrayList<>();
        Map<String, String> claimedBy = new HashMap<>();
        for (Map.Entry<String, EncounterRuleAsset> entry : new TreeMap<>(rowsById).entrySet()) {
            EncounterRuleAsset row = entry.getValue();
            if (row == null || !row.isEnabled()) {
                continue;
            }
            String id = row.getId() == null ? entry.getKey() : row.getId();
            String role = row.getRole();
            if (role == null) {
                findings.add(Finding.error(EncounterValidator.DOMAIN, RULE_NO_ROLE, "This rule names no "
                        + "Match.Role, so no kill ever counts under it; name the NPC role id it counts.", id));
                continue;
            }
            String first = claimedBy.putIfAbsent(EncounterRuleAsset.roleKey(role), id);
            if (first != null) {
                findings.add(Finding.warning(EncounterValidator.DOMAIN, RULE_DUPLICATE_ROLE, "Both '" + first
                        + "' and '" + id + "' name the role '" + role + "'; only the first by id is read.", id));
            }
            if (roleExists != null && !roleExists.test(role)) {
                findings.add(Finding.warning(EncounterValidator.DOMAIN, RULE_UNKNOWN_ROLE, "No NPC role called '"
                        + role + "' is loaded, so no kill counts under this rule. That is expected when the pack "
                        + "shipping the role is not installed; check the spelling otherwise.", id));
            }
        }
        return findings;
    }
}
