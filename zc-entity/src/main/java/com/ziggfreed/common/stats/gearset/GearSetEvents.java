package com.ziggfreed.common.stats.gearset;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.event.NativeEventSeam;

/**
 * Fires the gear-set family's native event on the shared engine event bus, under the library-wide
 * contract ({@link NativeEventSeam}): built only when somebody is listening, dispatched
 * synchronously on the calling (world) thread, and never able to take the recompute that caused
 * it down with it.
 *
 * <p>The one event ({@link ZigGearSetTierChangedEvent}) is fired by {@link GearSets} alone, and only
 * for a REAL flip. A third party listens on the bus with no compile-time dependency beyond the
 * event class; a host running without a bus (a harness, a unit JVM) redirects the family through
 * {@link #publishTo}.
 */
public final class GearSetEvents {

    private static final NativeEventSeam SEAM = new NativeEventSeam("[gearset]");

    private GearSetEvents() {
    }

    /**
     * Route every fire through {@code publisher} instead of the engine bus; null restores the bus.
     * For a host outside a Hytale server and for a test observing what the engine fires. Not for a
     * mod: on a live server the bus is the one place a listener looks.
     */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        SEAM.publishTo(publisher);
    }

    /**
     * Tier {@code tierIndex} of {@code set} really flipped for the player. The event carries the
     * player's live {@link PlayerRef}; a player without one cannot be announced on the bus and the
     * seam logs the refusal instead of dispatching a half-built event.
     */
    static void fireTierChanged(@Nonnull UUID playerId, @Nullable PlayerRef playerRef, @Nonnull GearSetAsset set,
            int tierIndex, boolean active, int pieces) {
        SEAM.fire("ZigGearSetTierChanged", ZigGearSetTierChangedEvent.class, () -> {
            GearSetAsset.Tier tier = tierIndex >= 0 && tierIndex < set.tiers().size() ? set.tiers().get(tierIndex) : null;
            return new ZigGearSetTierChangedEvent(playerId, liveRef(playerRef, set.getId()),
                    GearSetKeys.setId(set.getId()), tierIndex, active, pieces,
                    set.memberIds().size(), set.titleKey(), tier == null ? null : tier.titleKey());
        });
    }

    @Nonnull
    private static PlayerRef liveRef(@Nullable PlayerRef playerRef, @Nonnull String setId) {
        if (playerRef == null) {
            throw new IllegalStateException("the player carries no live reference, so the '" + setId
                    + "' tier change cannot be announced");
        }
        return playerRef;
    }
}
