package com.ziggfreed.common.stats.gearset;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.stats.gearset.GearSetDecision.SlotCounts;

/**
 * Fires the gear-set family's native events on the shared engine event bus, under the library-wide
 * contract ({@link NativeEventSeam}): built only when somebody is listening, dispatched
 * synchronously on the calling (world) thread, and never able to take the recompute that caused
 * it down with it.
 *
 * <p>The two events are fired by {@link GearSets} alone, and only for a live change:
 * {@link ZigGearSetTierChangedEvent} for a REAL flip, {@link ZigGearSetPiecesChangedEvent} for a set
 * whose piece count rose with no flip. A third party listens on the bus with no compile-time
 * dependency beyond the event class; a host running without a bus (a harness, a unit JVM)
 * redirects the family through {@link #publishTo}.
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
     * Tier {@code tierIndex} of {@code set} really flipped for the player, who now has {@code counts}
     * of it on. The event carries the player's live {@link PlayerRef}; a player without one cannot
     * be announced on the bus and the seam logs the refusal instead of dispatching a half-built event.
     */
    static void fireTierChanged(@Nonnull UUID playerId, @Nullable PlayerRef playerRef, @Nonnull GearSetAsset set,
            int tierIndex, boolean active, @Nonnull SlotCounts counts) {
        SEAM.fire("ZigGearSetTierChanged", ZigGearSetTierChangedEvent.class, () -> {
            GearSetAsset.Tier tier = tierIndex >= 0 && tierIndex < set.tiers().size() ? set.tiers().get(tierIndex) : null;
            int full = GearSetDecision.fullCount(set);
            return new ZigGearSetTierChangedEvent(playerId, liveRef(playerRef, set.getId()),
                    GearSetKeys.setId(set.getId()), tierIndex, active,
                    GearSetDecision.shownPieces(counts.pieces(), full), set.memberIds().size(), full,
                    set.titleKey(), tier == null ? null : tier.titleKey(), nextTierTitleKey(set, counts));
        });
    }

    /**
     * The player put on another piece of {@code set}, now {@code counts} of it, and no tier of it
     * flipped. Same contract as {@link #fireTierChanged}.
     */
    static void firePiecesChanged(@Nonnull UUID playerId, @Nullable PlayerRef playerRef, @Nonnull GearSetAsset set,
            @Nonnull SlotCounts counts) {
        SEAM.fire("ZigGearSetPiecesChanged", ZigGearSetPiecesChangedEvent.class, () -> {
            int full = GearSetDecision.fullCount(set);
            return new ZigGearSetPiecesChangedEvent(playerId, liveRef(playerRef, set.getId()),
                    GearSetKeys.setId(set.getId()), GearSetDecision.shownPieces(counts.pieces(), full), full,
                    set.titleKey(), nextTierTitleKey(set, counts));
        });
    }

    /** The set's name for a notice: its title key resolved, else its id as plain text. */
    @Nonnull
    static Message setName(@Nullable String setTitleKey, @Nonnull String setId) {
        return setTitleKey == null ? Msg.raw(setId) : ContentKeys.tr(setTitleKey);
    }

    /** One authored line key resolved for a notice, or null when there is none. */
    @Nullable
    static Message line(@Nullable String key) {
        return key == null ? null : ContentKeys.tr(key);
    }

    @Nullable
    private static String nextTierTitleKey(@Nonnull GearSetAsset set, @Nonnull SlotCounts counts) {
        GearSetAsset.Tier next = GearSetDecision.nextTier(set, counts);
        return next == null ? null : next.titleKey();
    }

    @Nonnull
    private static PlayerRef liveRef(@Nullable PlayerRef playerRef, @Nonnull String setId) {
        if (playerRef == null) {
            throw new IllegalStateException("the player carries no live reference, so the '" + setId
                    + "' set change cannot be announced");
        }
        return playerRef;
    }
}
