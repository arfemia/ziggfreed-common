package com.ziggfreed.common.feedback;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.util.AssetIndexCache;

/**
 * The native-pickup-MIMIC notifier (round-5, 2026-07-22, lifted here per the maintainer's
 * common-lift amendment): reproduces the ENGINE's own item-pickup feedback mechanism -
 * {@code Player#notifyPickupItem}'s exact shape (source: {@code hytale-shared-source/.../entity/
 * entities/Player.java:498-529}) - for a PROGRAMMATIC item grant that never went through a real
 * ground-item pickup, so a mod handing a player an item can still give it the item-icon
 * Notification packet + the {@code SFX_Player_Pickup_Item} cue players already associate with
 * "I received something".
 *
 * <p>{@link #notify} is the parameterized primitive: the caller supplies its OWN message /
 * secondary message / {@link NotificationStyle} (so a "produced" or "lucky drop" toast can carry
 * its own localized text and color, not just the literal "you picked up" wording) while this
 * class owns the MECHANISM - the {@code Item} slot on the {@code Notification} packet (built via
 * {@link ItemStack#toPacket()}, the SAME real item-icon field a native pickup uses, scout finding
 * 2), and the {@code SFX_Player_Pickup_Item} cue (3D at {@code position} when given, matching
 * where the item visually sat, else 2D) - gated behind the world's {@code
 * ShowItemPickupNotifications} toggle for the NOTIFICATION only, exactly like the native method:
 * the SFX itself is NEVER gated by that toggle, matching {@code Player#notifyPickupItem}'s own
 * (perhaps surprising) behavior exactly.
 *
 * <p>{@link #notifyLikeNativePickup} is the byte-exact convenience: it delegates STRAIGHT to the
 * engine's own {@code Player#notifyPickupItem} rather than re-deriving the native "you picked up
 * X" message text by hand, so it can never drift from what a genuine walk-over/block-harvest
 * pickup looks like.
 *
 * <p>World-thread only (touches the {@link Store} and writes packets); every entry point is
 * try-guarded so a missing component / invalid ref / engine throw never escapes into the caller.
 */
public final class PickupMimic {

    /**
     * The native pickup cue's asset id ({@code hytale-shared-source}'s own {@code
     * TempAssetIdUtil.SOUND_EVENT_PLAYER_PICKUP_ITEM} constant value - referenced here as a plain
     * data literal, NOT via that engine class, which is {@code @Deprecated(forRemoval = true)} in
     * its entirety; resolved through the SAME non-deprecated {@link AssetIndexCache} seam {@code
     * sound.Sound3D} already uses, never the deprecated {@code TempAssetIdUtil.getSoundEventIndex}).
     */
    private static final String SOUND_EVENT_PLAYER_PICKUP_ITEM = "SFX_Player_Pickup_Item";

    private static final AssetIndexCache<SoundEvent> PICKUP_SFX_INDEX = AssetIndexCache.of(
            SOUND_EVENT_PLAYER_PICKUP_ITEM, id -> SoundEvent.getAssetMap().getIndex(id));

    private PickupMimic() {
    }

    /**
     * The parameterized mimic: an item-icon-bearing {@code Notification} with the CALLER's own
     * message/secondaryMessage/style, plus the native pickup SFX. Never throws.
     */
    public static void notify(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Message message, @Nullable Message secondaryMessage, @Nonnull NotificationStyle style,
            @Nonnull ItemStack itemStack, @Nullable Vector3d position) {
        try {
            if (store.getExternalData().getWorld().getGameplayConfig().getShowItemPickupNotifications()) {
                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef != null) {
                    NotificationUtil.sendNotification(playerRef.getPacketHandler(), message, secondaryMessage,
                            itemStack.toPacket(), style);
                }
            }
        } catch (Throwable t) {
            warn("notify", t);
        }
        playPickupSfx(ref, store, position);
    }

    /** {@link #notify} at {@link NotificationStyle#Default} (matches the native pickup's own style). */
    public static void notify(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Message message, @Nonnull ItemStack itemStack, @Nullable Vector3d position) {
        notify(ref, store, message, null, NotificationStyle.Default, itemStack, position);
    }

    /**
     * Byte-exact native-pickup mimicry: delegates straight to {@code Player#notifyPickupItem}
     * (message + item-icon Notification + SFX, all gated exactly like a genuine pickup) - zero
     * re-derivation of the native message text, so it can never drift. Never throws.
     */
    public static void notifyLikeNativePickup(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull ItemStack itemStack, @Nullable Vector3d position) {
        try {
            Player.notifyPickupItem(ref, itemStack, position, store);
        } catch (Throwable t) {
            warn("notifyLikeNativePickup", t);
        }
    }

    private static void playPickupSfx(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nullable Vector3d position) {
        try {
            int idx = PICKUP_SFX_INDEX.resolve();
            if (idx == AssetIndexCache.UNRESOLVED) {
                return;
            }
            if (position != null) {
                SoundUtil.playSoundEvent3dToPlayer(ref, idx, SoundCategory.UI, position, store);
            } else {
                SoundUtil.playSoundEvent2d(ref, idx, SoundCategory.UI, store);
            }
        } catch (Throwable t) {
            warn("sfx", t);
        }
    }

    private static void warn(@Nonnull String op, @Nonnull Throwable t) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("[ZiggfreedCommon] PickupMimic." + op + " failed: " + t.getMessage());
        } catch (Throwable ignored) {
            // a log-manager-less unit JVM must not crash on the logging facade itself
        }
    }
}
