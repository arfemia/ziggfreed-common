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
 * What the engine last wrote on each player: the tiers whose modifiers went on, the effects it
 * wanted on, and the tiers that were active, so the next recompute sweeps exactly what it wrote,
 * takes off exactly the effects it answers for, and announces only a tier that really flipped. The
 * row never decides whether an effect goes ON: that is asked of the entity itself at every
 * recompute ({@code GearSets.effectChanges}), so an effect something else cleared comes back.
 *
 * <p>TRANSIENT and never persisted: a modifier the previous boot left on an entity is not in here,
 * which is why a player with no row (the first recompute after login) is swept from what is
 * actually present on the stat map rather than from memory.
 *
 * <p><b>Keyed by player alone, never by world.</b> The stat map and the active effects travel with
 * the player: a world change moves the player's whole component holder into the next world's store
 * ({@code PlayerRef.removeFromStore} then {@code World.addPlayer}), and both the
 * {@code EntityStatMap} and the {@code EffectControllerComponent} are registered, codec-carried
 * components that nothing clears on that move. So the row describing what is on the player stays
 * true across the move, and the recompute the next world's ready event runs finds the same tiers
 * active and announces nothing. A row per world would instead diff a player returning from an
 * instance against what they wore when they left, and re-announce every tier that changed in
 * between. The engine does clear every effect on death and again on respawn; the row keeps listing
 * the look, the entity is asked, and the respawn recompute puts it back without a notice.
 *
 * <p><b>Evicted twice, never by a world unload:</b> on disconnect, and again when the player's
 * entity leaves its store for any reason but a world change ({@code GearSets.onEntityRemoved}),
 * because the disconnect event fires before the entity leaves and a recompute already queued on the
 * world thread could otherwise write the row back for a session that has ended.
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

    /** Drop the player's row: a disconnect, or the entity leaving its store for good. */
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
