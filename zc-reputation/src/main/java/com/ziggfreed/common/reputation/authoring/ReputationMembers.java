package com.ziggfreed.common.reputation.authoring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.reputation.ReputationNative;

/**
 * What may join a reputation's native group, checked before anything is written: the engine throws on every NPC
 * added to every world while a {@code ReputationGroup} names an NPC group it has not loaded, and an NPC takes the
 * first reputation group (in the engine's map order) whose NPC groups hold its role, so a character two reputations
 * claim follows whichever the engine meets first. Pure: the engine's answers come in as predicates
 * ({@code NPCPlugin.hasRoleName}, {@link ReputationNative#roleInGroup}, {@link ReputationNative#npcGroupExists}).
 */
public final class ReputationMembers {

    /** Why a role may not join. */
    public enum Kind {
        /** No NPC role of that name is loaded on this server. */
        UNKNOWN_ROLE,
        /** Another reputation's group already holds the role. */
        OTHER_REPUTATION
    }

    /** One role that may not join, and for {@link Kind#OTHER_REPUTATION}, the reputation that holds it. */
    public record Problem(@Nonnull String role, @Nonnull Kind kind, @Nullable String reputation) {
    }

    private ReputationMembers() {
    }

    /**
     * Every role of {@code roles} that may not join reputation {@code selfId}: one the engine does not know, or one
     * an NPC group named by another reputation's group already holds. Blank roles are skipped; each role is
     * reported once (any case).
     */
    @Nonnull
    public static List<Problem> problems(@Nonnull Collection<String> roles, @Nonnull String selfId,
            @Nonnull Predicate<String> isRole, @Nonnull Collection<ReputationNative.Group> groups,
            @Nonnull Predicate<String> npcGroupKnown, @Nonnull BiPredicate<String, String> roleInGroup) {
        List<Problem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : roles) {
            String role = raw == null ? "" : raw.trim();
            if (role.isEmpty() || !seen.add(role.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!isRole.test(role)) {
                out.add(new Problem(role, Kind.UNKNOWN_ROLE, null));
                continue;
            }
            String holder = holder(role, selfId, groups, npcGroupKnown, roleInGroup);
            if (holder != null) {
                out.add(new Problem(role, Kind.OTHER_REPUTATION, holder));
            }
        }
        return out;
    }

    /** The id of the first other reputation whose loaded NPC groups hold {@code role}, or null. */
    @Nullable
    private static String holder(@Nonnull String role, @Nonnull String selfId,
            @Nonnull Collection<ReputationNative.Group> groups, @Nonnull Predicate<String> npcGroupKnown,
            @Nonnull BiPredicate<String, String> roleInGroup) {
        for (ReputationNative.Group group : groups) {
            if (group == null || group.id().equalsIgnoreCase(selfId.trim()) || group.npcGroups() == null) {
                continue;
            }
            for (String npcGroup : group.npcGroups()) {
                if (npcGroup != null && npcGroupKnown.test(npcGroup) && roleInGroup.test(role, npcGroup)) {
                    return group.id();
                }
            }
        }
        return null;
    }

    /** The roles of {@code roles} the engine knows, trimmed, in order, each once (any case): what a member group may include. */
    @Nonnull
    public static List<String> knownRoles(@Nonnull Collection<String> roles, @Nonnull Predicate<String> isRole) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : roles) {
            String role = raw == null ? "" : raw.trim();
            if (!role.isEmpty() && isRole.test(role) && seen.add(role.toLowerCase(Locale.ROOT))) {
                out.add(role);
            }
        }
        return out;
    }

    /**
     * The entries of {@code npcGroups} the engine has loaded, trimmed, in order, each once (any case): what a native
     * group may name. An entry the engine has not loaded would make it throw on every NPC added, so it never reaches
     * a written group.
     */
    @Nonnull
    public static List<String> loadedGroups(@Nonnull Collection<String> npcGroups,
            @Nonnull Predicate<String> npcGroupKnown) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : npcGroups) {
            String group = raw == null ? "" : raw.trim();
            if (!group.isEmpty() && npcGroupKnown.test(group) && seen.add(group.toLowerCase(Locale.ROOT))) {
                out.add(group);
            }
        }
        return out;
    }
}
