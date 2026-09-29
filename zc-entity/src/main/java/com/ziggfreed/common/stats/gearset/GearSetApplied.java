package com.ziggfreed.common.stats.gearset;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.stats.gearset.GearSetKeys.TierRef;

/**
 * What the engine last wrote on each player while their entity sits in its current store: the
 * tiers whose modifiers went on, the effects it wanted on, and the tiers that were active, so the
 * next recompute sweeps exactly what it wrote, takes off exactly the effects it answers for, and
 * announces only a tier that really flipped. The row never decides whether an effect goes ON: that
 * is asked of the entity itself at every recompute ({@code GearSets.effectChanges}), so an effect
 * something else cleared comes back.
 *
 * <p>TRANSIENT and never persisted: a modifier the previous boot left on an entity is not in here,
 * which is why a player with no row is swept from what is actually present on the stat map rather
 * than from memory. The looks the engine asked for ARE saved, but on the player entity itself
 * ({@link GearSetLooksComponent}), so a login can take off the look of a set deleted meanwhile.
 *
 * <p><b>Keyed by player UUID, and forgotten whenever the player's entity leaves its store</b>
 * ({@code GearSets.onEntityRemoved}, for every {@code RemoveReason}). A disconnect removes the entity
 * with {@code UNLOAD} after the disconnect event has fired, and a world change removes it with the
 * same reason, so the eviction cannot tell the two apart and does not try: it runs on the world
 * thread after any recompute queued before it, so no row outlives the session, and the next world's
 * first recompute is a hydrate. That hydrate is right after a world change: the stat map and the
 * active effects travel with the player's component holder, so the sweep reads the live map, the
 * look is reconciled by asking the entity, and nothing is announced, since no tier flipped. The
 * engine does clear every effect on death and again on respawn; the row keeps listing the look, the
 * entity is asked, and the respawn recompute puts it back without a notice.
 */
final class GearSetApplied {

    /** One player's last write. */
    record Applied(@Nonnull Set<TierRef> writtenTiers, @Nonnull Set<String> effects, @Nonnull Set<TierRef> activeTiers) {

        Applied {
            writtenTiers = Collections.unmodifiableSet(new LinkedHashSet<>(writtenTiers));
            effects = Collections.unmodifiableSet(new LinkedHashSet<>(effects));
            activeTiers = Collections.unmodifiableSet(new LinkedHashSet<>(activeTiers));
        }
    }

    private static final Map<UUID, Applied> TABLE = new ConcurrentHashMap<>();

    private GearSetApplied() {
    }

    /** The player's last write, or null for the first recompute since login. */
    @Nullable
    static Applied get(@Nonnull UUID playerId) {
        return TABLE.get(playerId);
    }

    static void put(@Nonnull UUID playerId, @Nonnull Applied applied) {
        TABLE.put(playerId, applied);
    }

    /** Drop the player's row: their entity left its store, whatever the reason. */
    static void forget(@Nonnull UUID playerId) {
        TABLE.remove(playerId);
    }

    /** How many players are remembered; diagnostics and tests. */
    static int size() {
        return TABLE.size();
    }

    static void clearForTests() {
        TABLE.clear();
    }
}
