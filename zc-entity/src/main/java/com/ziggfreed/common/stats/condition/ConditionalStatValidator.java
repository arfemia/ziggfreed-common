package com.ziggfreed.common.stats.condition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The conditional stats' audit (zc's boot audit runs it): a file naming a stat this server does not have, folding
 * onto one it does not have, folding although it asks something of a hit or a target (an {@code Onto} the runtime
 * leaves out), following a damage line no source names, and two files for one stat. Every finding is a WARNING: an
 * id's owner may register later, or be a mod this server does not run.
 */
public final class ConditionalStatValidator {

    /** The audit's domain. */
    public static final String DOMAIN = "conditional_stat";

    /** The label the boot audit's pass prints its lines under. */
    public static final String LOG_LABEL = "[condition] audit";

    public static final String UNKNOWN_STAT = "UNKNOWN_STAT";
    public static final String UNKNOWN_ONTO = "UNKNOWN_ONTO";
    public static final String ONTO_ASKS_TARGET = "ONTO_ASKS_TARGET";
    public static final String LIKE_NO_LINE = "LIKE_NO_LINE";
    public static final String DUPLICATE_STAT = "DUPLICATE_STAT";

    private ConditionalStatValidator() {
    }

    /** The walk over every folded file, against the loaded stats and the registered damage lines. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            return audit(ConditionalStatConfig.getInstance().all(),
                    id -> EntityStatType.getAssetMap().getIndex(id) != AssetMapWithIndexes.NOT_FOUND,
                    TypedDamageBonuses::hasLine);
        } catch (Throwable t) {
            SafeLog.warn("[condition] the conditional stat audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /** The audit over {@code files}, with the stat and damage-line vocabularies passed in. Pure. */
    @Nonnull
    public static List<Finding> audit(@Nonnull Map<String, ConditionalStatAsset> files,
            @Nonnull Predicate<String> statKnown, @Nonnull Predicate<String> lineKnown) {
        List<Finding> out = new ArrayList<>();
        Map<String, String> firstFileByStat = new HashMap<>();
        for (Map.Entry<String, ConditionalStatAsset> entry : files.entrySet()) {
            String id = entry.getKey();
            ConditionalStatAsset file = entry.getValue();
            if (file == null || file.isAbstract() || !file.isEnabled() || file.statId() == null) {
                continue;
            }
            String stat = file.statId();
            String earlier = firstFileByStat.putIfAbsent(stat.toLowerCase(Locale.ROOT), id);
            if (earlier != null) {
                out.add(Finding.warning(DOMAIN, DUPLICATE_STAT, "conditional stat file '" + id + "' names the stat '"
                        + stat + "', which '" + earlier + "' already makes conditional: only the first counts", id));
            }
            if (!statKnown.test(stat)) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_STAT, "conditional stat file '" + id + "' names the stat '"
                        + stat + "', which this server does not have, so it pays nothing", id));
            }
            ConditionalStatAsset.Pays pays = file.pays();
            if (pays == null) {
                continue;
            }
            if (!pays.onto().isEmpty() && file.asksOfTarget()) {
                out.add(Finding.warning(DOMAIN, ONTO_ASKS_TARGET, "conditional stat file '" + id + "' folds onto "
                        + pays.onto() + " but asks something of a hit or a target, which a fold cannot read: the Onto"
                        + " is left out", id));
            }
            for (String onto : pays.onto()) {
                if (!statKnown.test(onto)) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_ONTO, "conditional stat file '" + id + "' folds onto '"
                            + onto + "', which this server does not have", id));
                }
            }
            ConditionalStatAsset.Damage damage = pays.damage();
            String like = damage == null ? null : damage.like();
            if (like != null && !lineKnown.test(like)) {
                out.add(Finding.warning(DOMAIN, LIKE_NO_LINE, "conditional stat file '" + id + "' follows the damage"
                        + " line of '" + like + "', which no damage source names, so it counts on no hit", id));
            }
        }
        return out;
    }
}
