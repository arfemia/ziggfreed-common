package com.ziggfreed.common.encounter.asset;

import java.util.Collection;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.match.NameMatchRank;
import com.ziggfreed.common.match.NamePattern;
import com.ziggfreed.common.world.MatchRank;
import com.ziggfreed.common.world.WorldSelector;

/**
 * Which participation rule applies to a run: the most specific one matching the subject's mob id
 * AND the world, on the library's shared ladders and nothing else.
 *
 * <p>The subject axis is the name ladder ({@link NameMatchRank}: exact, then longest literal core,
 * then the bare {@code *}), and it decides first, because a rule is ABOUT a subject. Only rules tied
 * on the subject axis are then ordered on the world axis ({@link MatchRank}: a {@code GameplayConfig}
 * hit, an exact name, a pattern, then a rule with no {@code Where} at all, which applies everywhere
 * and so pins down nothing). Two rules equal on both keep the first by id, so an owner can read the
 * winner off the files. A rule whose {@code Where} rejects the world, or whose {@code Match} misses
 * the subject, is out regardless of how specific it is elsewhere.
 *
 * <p>Pure: it takes strings and rule objects and returns one, so a test drives it with fixtures and
 * no engine.
 */
public final class ParticipationRules {

    private ParticipationRules() {
    }

    /**
     * The winning rule, or null when no enabled rule matches.
     *
     * @param subjectMobId        the subject's mob id, or null for a run with no subject (only a
     *                            rule that covers every subject can match then)
     * @param worldName           the world's name, or null when unknown
     * @param worldGameplayConfig the world's authored gameplay config key, or null
     * @param rules               the folded rules, in id order
     */
    @Nullable
    public static EncounterParticipationAsset resolve(@Nullable String subjectMobId, @Nullable String worldName,
            @Nullable String worldGameplayConfig, @Nonnull Collection<EncounterParticipationAsset> rules) {
        String subjectLower = subjectMobId == null ? null : subjectMobId.trim().toLowerCase(Locale.ROOT);
        EncounterParticipationAsset best = null;
        NameMatchRank bestName = null;
        MatchRank bestWorld = null;
        boolean bestHasWhere = false;
        for (EncounterParticipationAsset rule : rules) {
            if (rule == null || !rule.isEnabled()) {
                continue;
            }
            NamePattern pattern = NamePattern.parse(rule.matchOrAll());
            if (subjectLower == null) {
                if (!pattern.isDefaultRule()) {
                    continue;
                }
            } else if (!pattern.matches(subjectLower)) {
                continue;
            }
            NameMatchRank nameRank = NameMatchRank.ofPattern(pattern);
            WorldSelector where = rule.getWhere();
            boolean hasWhere = where != null && !where.isBlank();
            MatchRank worldRank = null;
            if (hasWhere) {
                worldRank = where.match(worldName, worldGameplayConfig);
                if (worldRank == null) {
                    continue;
                }
            }
            if (best == null || beats(nameRank, hasWhere, worldRank, bestName, bestHasWhere, bestWorld)) {
                best = rule;
                bestName = nameRank;
                bestWorld = worldRank;
                bestHasWhere = hasWhere;
            }
        }
        return best;
    }

    /** Strictly more specific than the current best: subject axis first, then the world axis. */
    private static boolean beats(@Nonnull NameMatchRank name, boolean hasWhere, @Nullable MatchRank world,
            @Nonnull NameMatchRank bestName, boolean bestHasWhere, @Nullable MatchRank bestWorld) {
        int byName = name.compareTo(bestName);
        if (byName != 0) {
            return byName < 0;
        }
        if (hasWhere != bestHasWhere) {
            return hasWhere;
        }
        if (world == null || bestWorld == null) {
            return false;
        }
        return world.compareTo(bestWorld) < 0;
    }
}
