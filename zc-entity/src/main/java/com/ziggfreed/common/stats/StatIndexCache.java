package com.ziggfreed.common.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.ziggfreed.common.util.SafeLog;

/**
 * Resolves a native {@link EntityStatType} id to its engine asset-map index and memoizes the
 * result. Shared by every class that needs to turn a stat id string into an index
 * ({@link EquipStatBridge}, {@link StatMirror}, {@link StatChannelAudit}, and the library's own
 * {@code hytale:stat} factor provider) so the resolution + caching rule exists exactly once.
 *
 * <p>Three doors: {@link #resolve} asks for the id exactly as written, for a writer's own ids and the channel audit,
 * where a miss is a load-order bug; {@link #resolveCurrent} also follows a retired id ({@link StatAliases}) to its one
 * successor, for a reader of an id authored elsewhere (a factor's {@code Param}, a cost's pool, a validator); and
 * {@link #landingsOf} follows it to every successor at the alias's scale, for a write (a grant, a counter's row, a
 * proc's stat change, a gear set tier).
 *
 * <p><b>Deliberately NOT routed through {@code util.AssetIndexCache}</b> even though the package
 * router lists it as the one allowed cross-domain primitive: that cache treats index {@code 0} as
 * "not resolved yet" (several engine asset maps reserve slot 0 as a none/clear sentinel), but a
 * custom {@link EntityStatType} can legitimately land at index 0 depending on pack load order -
 * caching only {@code idx > 0} would permanently mis-resolve such a channel as unregistered. The
 * rule here is to cache any {@code idx >= 0} (the engine's real "missing" sentinel is
 * {@code Integer.MIN_VALUE}) and never cache a miss, so a not-yet-loaded pack channel re-resolves
 * on the next call.
 */
public final class StatIndexCache {

    /** Returned (and never cached) when the id is not registered yet / the map is not ready. */
    public static final int UNRESOLVED = -1;

    @Nonnull
    private static final ConcurrentHashMap<String, Integer> CACHE = new ConcurrentHashMap<>();

    /** Channel ids already named as unregistered, by whichever reader met them first. */
    @Nonnull
    private static final Set<String> WARNED_UNKNOWN = ConcurrentHashMap.newKeySet();

    /** Retired ids (lower-cased) already named as read under their successor, by whichever reader met them first. */
    @Nonnull
    private static final Set<String> WARNED_RETIRED = ConcurrentHashMap.newKeySet();

    private StatIndexCache() {
    }

    /**
     * The asset-map index of the {@link EntityStatType} registered under {@code statId},
     * memoized once resolved.
     *
     * @return the resolved index ({@code >= 0}), or {@link #UNRESOLVED} if not registered yet /
     *         the asset store is not ready (e.g. a unit JVM with no live server). Never throws.
     */
    public static int resolve(@Nonnull String statId) {
        Integer cached = CACHE.get(statId);
        if (cached != null) {
            return cached;
        }
        int idx;
        try {
            idx = EntityStatType.getAssetMap().getIndex(statId);
        } catch (Throwable ignored) {
            // Asset store not ready (or not present, e.g. a unit JVM) - miss; do not cache.
            return UNRESOLVED;
        }
        if (idx >= 0) {
            CACHE.put(statId, idx);
            return idx;
        }
        return UNRESOLVED;
    }

    /**
     * The index a READER of {@code statId} reads: the id's own when it is loaded; else, for a retired id
     * ({@link StatAliases}) with exactly one successor at scale 1, that successor's, naming the retired id once
     * ({@link #warnRetiredOnce}); else {@link #UNRESOLVED}. A retired id with several successors or a scale has no one
     * stat a reading could stand for, so it stays unresolved here; a writer expands it instead (one write per
     * successor, at the alias's scale). An alias is followed once, never twice. Never throws.
     */
    public static int resolveCurrent(@Nonnull String statId) {
        int idx = resolve(statId);
        if (idx != UNRESOLVED || !StatAliases.isRetired(statId)) {
            return idx;
        }
        List<String> successors = StatAliases.resolve(statId);
        if (successors.size() != 1 || StatAliases.scale(statId) != 1.0) {
            return UNRESOLVED;
        }
        int successor = resolve(successors.get(0));
        if (successor != UNRESOLVED) {
            warnRetiredOnce(statId);
        }
        return successor;
    }

    /** One stat a write naming an id lands on: its id, its index and what one written point is worth on it. */
    public record Landing(@Nonnull String statId, int index, double scale) {

        /** {@code modifier} as it lands here: an additive amount times the scale, any other shape as it is. */
        @Nonnull
        public StaticModifier scaled(@Nonnull StaticModifier modifier) {
            if (scale == 1.0 || modifier.getCalculationType() != StaticModifier.CalculationType.ADDITIVE) {
                return modifier;
            }
            return new StaticModifier(modifier.getTarget(), modifier.getCalculationType(),
                    (float) (modifier.getAmount() * scale));
        }
    }

    /**
     * Where a WRITE naming {@code statId} lands: the stat itself when it is loaded; else, for a retired id
     * ({@link StatAliases}), each loaded successor at the alias's scale, since a write lands on every successor as a
     * saved roll does (the retired id named once, {@link #warnRetiredOnce}; a successor not loaded named once as
     * unknown); else none, the id named once as unknown. A scale applies to an additive amount alone: a multiplier is
     * the same on any stat. Never throws.
     */
    @Nonnull
    public static List<Landing> landingsOf(@Nonnull String statId) {
        int index = resolve(statId);
        if (index != UNRESOLVED) {
            return List.of(new Landing(statId, index, 1.0));
        }
        if (!StatAliases.isRetired(statId)) {
            warnUnknownOnce(statId);
            return List.of();
        }
        double scale = StatAliases.scale(statId);
        List<Landing> out = new ArrayList<>();
        for (String successor : StatAliases.resolve(statId)) {
            int at = resolve(successor);
            if (at == UNRESOLVED) {
                warnUnknownOnce(successor);
            } else {
                out.add(new Landing(successor, at, scale));
            }
        }
        if (!out.isEmpty()) {
            warnRetiredOnce(statId);
        }
        return out;
    }

    /**
     * Whether a write naming {@code statId} lands whole, the question an audit of a grant or another write asks: the
     * stat is loaded, or it is retired and every successor loads (the retired id named once, a pass with that warning,
     * never a miss). Never throws.
     */
    public static boolean resolvesForWrite(@Nonnull String statId) {
        if (resolve(statId) != UNRESOLVED) {
            return true;
        }
        if (!StatAliases.isRetired(statId)) {
            return false;
        }
        for (String successor : StatAliases.resolve(statId)) {
            if (resolve(successor) == UNRESOLVED) {
                return false;
            }
        }
        warnRetiredOnce(statId);
        return true;
    }

    /**
     * Names a retired stat id an author still writes, once per id (case-insensitive) across every reader and writer
     * that follows it to its successors, so the owner can rename it. Never throws.
     */
    public static void warnRetiredOnce(@Nonnull String statId) {
        if (WARNED_RETIRED.add(statId.toLowerCase(Locale.ROOT))) {
            SafeLog.warn("[stats] retired stat '" + statId + "' read as '"
                    + String.join("', '", StatAliases.resolve(statId)) + "'");
        }
    }

    /**
     * Names a stat channel nothing is registered under, once per id across every reader that
     * resolves through this cache ({@link EquipStatBridge}, a gear set's tiers, the
     * {@code hytale:stat} and {@code hytale:item_stat} factors), so one misspelled or late channel
     * logs one line rather than one per reader. A miss is never cached by {@link #resolve}, so a
     * channel registered later still resolves; only the log line is latched. Never throws.
     */
    public static void warnUnknownOnce(@Nonnull String statId) {
        if (WARNED_UNKNOWN.add(statId)) {
            SafeLog.warn("[stats] unknown stat channel '" + statId
                    + "' - skipping (not registered yet, or misspelled)");
        }
    }
}
