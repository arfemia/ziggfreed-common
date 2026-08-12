package com.ziggfreed.common.achievement.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;

/**
 * Every achievement a consumer should run, folded and ready, with inheritance already resolved.
 *
 * <p>The usual shape of a load is one line:
 * <pre>{@code
 * AchievementPool pool = AchievementAssetStore.getInstance().resolveAll("yourmod");
 * engine.setAchievements(pool.achievements());
 * }</pre>
 * and then the consumer's UI reads {@link #definition} for the text and gates while the engine runs
 * them. Rebuild the pool on every content reload; it is an immutable snapshot.
 */
public final class AchievementPool {

    /** An empty pool, for a consumer whose content has not loaded yet. */
    public static final AchievementPool EMPTY = new AchievementPool(Map.of());

    private final Map<String, AchievementDefinition> definitions;

    /** Wrap an already-folded {@code id -> definition} map (ids are lower-cased). */
    public AchievementPool(@Nonnull Map<String, AchievementDefinition> definitions) {
        Map<String, AchievementDefinition> copy = new LinkedHashMap<>();
        for (Map.Entry<String, AchievementDefinition> e : definitions.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                copy.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        this.definitions = Collections.unmodifiableMap(copy);
    }

    /** Every folded achievement, keyed by id, in resolution order. */
    @Nonnull
    public Map<String, AchievementDefinition> definitions() {
        return definitions;
    }

    /** The folded achievement under {@code achievementId} (case-insensitive), or null. */
    @Nullable
    public AchievementDefinition definition(@Nullable String achievementId) {
        return achievementId == null
                ? null
                : definitions.get(achievementId.trim().toLowerCase(Locale.ROOT));
    }

    /** The engine-side definitions, ready for {@link AchievementEngine#setAchievements}. */
    @Nonnull
    public Collection<Achievement> achievements() {
        List<Achievement> out = new ArrayList<>(definitions.size());
        for (AchievementDefinition definition : definitions.values()) {
            out.add(definition.achievement());
        }
        return out;
    }

    /** Every achievement id, in resolution order. */
    @Nonnull
    public List<String> ids() {
        return List.copyOf(definitions.keySet());
    }

    /** How many this pool carries. */
    public int size() {
        return definitions.size();
    }

    /** The total points every achievement in this pool that counts is worth. */
    public int totalPoints() {
        int total = 0;
        for (AchievementDefinition definition : definitions.values()) {
            if (definition.achievement().countsTowardTotal()) {
                total += definition.achievement().points();
            }
        }
        return total;
    }
}
