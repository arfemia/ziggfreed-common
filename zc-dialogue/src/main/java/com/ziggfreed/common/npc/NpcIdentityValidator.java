package com.ziggfreed.common.npc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.validation.Finding;

/**
 * What is wrong with an identity overlay, said out loud at load instead of discovered as an NPC that
 * quietly answers to the wrong name.
 *
 * <p>Every mistake here is invisible at runtime. A file that selects nothing, or names no id, simply
 * never applies; two files claiming the same role both look correct on their own and one of them
 * silently loses; and two roles spelled with different capitals are ONE role as far as the engine is
 * concerned, so an author can stare at two files that disagree without either looking wrong.
 *
 * <p>Findings carry the domain {@code identity} and are reported through the shared
 * {@link Finding} vocabulary, so a consumer folds them into its own audit without learning a shape.
 */
public final class NpcIdentityValidator {

    /** The domain every finding here is filed under. */
    public static final String DOMAIN = "identity";

    /** Neither {@code Role} nor {@code Group} authored: the file selects no NPC at all. */
    public static final String NO_SELECTOR = "IDENTITY_NO_SELECTOR";

    /** No {@code NpcId} authored: the file selects NPCs and then says nothing about them. */
    public static final String NO_NPC_ID = "IDENTITY_NO_NPC_ID";

    /** Both {@code Role} and {@code Group} authored on one file; the role match is what applies. */
    public static final String ROLE_AND_GROUP = "IDENTITY_ROLE_AND_GROUP";

    /** Two files claim the same {@code Role}. */
    public static final String DUPLICATE_ROLE = "IDENTITY_DUPLICATE_ROLE";

    /** Two files claim the same {@code Group}. */
    public static final String DUPLICATE_GROUP = "IDENTITY_DUPLICATE_GROUP";

    /** Two files name roles that differ only in capitalisation, so they are the same role. */
    public static final String CASE_ONLY_ROLE_COLLISION = "IDENTITY_CASE_ONLY_ROLE_COLLISION";

    /** An alias repeats the primary id, which it already answers to. */
    public static final String REDUNDANT_ALIAS = "IDENTITY_REDUNDANT_ALIAS";

    private NpcIdentityValidator() {
    }

    /** Audit one overlay in isolation (the per-file checks only). */
    @Nonnull
    public static List<Finding> audit(@Nullable NpcIdentityAsset overlay) {
        return overlay == null ? List.of() : auditOne(overlay);
    }

    /** Audit a whole folded pool: the per-file checks plus every collision between files. */
    @Nonnull
    public static List<Finding> audit(@Nullable Collection<NpcIdentityAsset> overlays) {
        if (overlays == null || overlays.isEmpty()) {
            return List.of();
        }
        List<Finding> out = new ArrayList<>();
        // Sorted by file id, the same order the index resolves collisions in, so the file this
        // reports as the winner is the file that actually wins.
        Map<String, NpcIdentityAsset> sorted = new TreeMap<>();
        for (NpcIdentityAsset overlay : overlays) {
            if (overlay != null && overlay.getId() != null) {
                sorted.put(overlay.getId(), overlay);
            }
        }
        Map<String, String> roleClaims = new LinkedHashMap<>();
        Map<String, String> roleSpellings = new LinkedHashMap<>();
        Map<String, String> groupClaims = new LinkedHashMap<>();

        for (Map.Entry<String, NpcIdentityAsset> entry : sorted.entrySet()) {
            String fileId = entry.getKey();
            NpcIdentityAsset overlay = entry.getValue();
            out.addAll(auditOne(overlay));

            String role = trimToNull(overlay.getRole());
            if (role != null) {
                String key = role.toLowerCase(Locale.ROOT);
                String winner = roleClaims.putIfAbsent(key, fileId);
                String firstSpelling = roleSpellings.putIfAbsent(key, role);
                if (winner != null) {
                    out.add(Finding.warning(DOMAIN, DUPLICATE_ROLE,
                            "Role '" + role + "' is already claimed by identity '" + winner
                                    + "', which is the one that applies. Give this file a different role, "
                                    + "or fold both ids into one file's NpcId plus Aliases.", fileId));
                }
                if (firstSpelling != null && !firstSpelling.equals(role)) {
                    out.add(Finding.warning(DOMAIN, CASE_ONLY_ROLE_COLLISION,
                            "Role '" + role + "' and '" + firstSpelling + "' (identity '"
                                    + roleClaims.get(key) + "') differ only in capitalisation, and the engine "
                                    + "treats a role name without regard to case - so these name the SAME role, "
                                    + "not two.", fileId));
                }
            }

            String group = trimToNull(overlay.getGroup());
            if (group != null) {
                String winner = groupClaims.putIfAbsent(group.toLowerCase(Locale.ROOT), fileId);
                if (winner != null) {
                    out.add(Finding.warning(DOMAIN, DUPLICATE_GROUP,
                            "Group '" + group + "' is already claimed by identity '" + winner
                                    + "', which is the one that applies. An NPC in both groups would otherwise "
                                    + "have two names with nothing to choose between them.", fileId));
                }
            }
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static List<Finding> auditOne(@Nonnull NpcIdentityAsset overlay) {
        String fileId = overlay.getId() == null ? "" : overlay.getId();
        List<Finding> out = new ArrayList<>();
        String role = trimToNull(overlay.getRole());
        String group = trimToNull(overlay.getGroup());
        String npcId = trimToNull(overlay.getNpcId());

        if (role == null && group == null) {
            out.add(Finding.error(DOMAIN, NO_SELECTOR,
                    "Authors neither Role nor Group, so it matches no NPC and does nothing. Name the NPC "
                            + "role this identity is about, or the NPCGroup covering a whole family of them.",
                    fileId));
        }
        if (npcId == null) {
            out.add(Finding.error(DOMAIN, NO_NPC_ID,
                    "Authors no NpcId, so it selects NPCs and then says nothing about them. NpcId is the "
                            + "character id content binds to.", fileId));
        }
        if (role != null && group != null) {
            out.add(Finding.warning(DOMAIN, ROLE_AND_GROUP,
                    "Authors both Role '" + role + "' and Group '" + group + "'. A role match is the more "
                            + "specific statement and is what applies, so the group here only covers roles the "
                            + "Role line does not. Split them into two files if that was not the intent.",
                    fileId));
        }
        if (npcId != null) {
            for (String alias : NpcIdentities.cleanIds(overlay.getAliases())) {
                if (alias.equalsIgnoreCase(npcId)) {
                    out.add(Finding.info(DOMAIN, REDUNDANT_ALIAS,
                            "Alias '" + alias + "' repeats the NpcId, which is already answered to.", fileId));
                }
            }
        }
        return out;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
