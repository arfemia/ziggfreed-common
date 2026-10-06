package com.ziggfreed.common.reputation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.reputation.asset.ReputationAsset;

/**
 * Which reputations one kill moves, and by how much: every switched-on reputation's {@code Kills} rows whose
 * native NPC groups hold the dead NPC's role, summed per reputation. Pure: membership is asked through the
 * predicate the engine seam answers.
 */
public final class ReputationKills {

    /** The kill moment's kind: zc-objectives' {@code ZigMobKillProducer.KIND}, which this module cannot see. */
    public static final String KILL_KIND = "KILL_ENTITY";

    private ReputationKills() {
    }

    /** Reputation id (the engine's spelling) to the standing one kill of {@code roleName} moves; zero sums dropped. */
    @Nonnull
    public static Map<String, Integer> amounts(@Nonnull Collection<ReputationDef> reputations,
            @Nullable String roleName, @Nonnull BiPredicate<String, String> roleInGroup) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (roleName == null || roleName.isBlank()) {
            return out;
        }
        String role = roleName.trim();
        for (ReputationDef reputation : reputations) {
            long sum = 0;
            for (ReputationAsset.Kill kill : reputation.kills()) {
                if (kill.amount() != 0 && anyHolds(kill.npcGroups(), role, roleInGroup)) {
                    sum += kill.amount();
                }
            }
            int amount = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, sum));
            if (amount != 0) {
                out.put(reputation.id(), amount);
            }
        }
        return out;
    }

    private static boolean anyHolds(@Nonnull List<String> groups, @Nonnull String role,
            @Nonnull BiPredicate<String, String> roleInGroup) {
        for (String group : groups) {
            if (roleInGroup.test(role, group)) {
                return true;
            }
        }
        return false;
    }
}
