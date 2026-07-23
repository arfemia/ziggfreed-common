package com.ziggfreed.common.feedback;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * A thin, styled wrapper over the engine {@code NotificationUtil} toast API. Routes
 * a {@link Message} (the caller builds the localized message; this util is config-free
 * and never reads a locale) to one player with a {@link NotificationStyle}, plus
 * named helpers for the four common styles (Default / Danger / Warning / Success).
 *
 * <p>World-thread: writes a packet via the player's handler. Fully try-guarded so a
 * notification can never throw into the caller.
 */
public final class Notify {

    private Notify() {
    }

    /** Default-styled notification. */
    public static void def(@Nonnull PlayerRef playerRef, @Nonnull Message message) {
        send(playerRef, message, NotificationStyle.Default);
    }

    /** Danger-styled notification (red). */
    public static void danger(@Nonnull PlayerRef playerRef, @Nonnull Message message) {
        send(playerRef, message, NotificationStyle.Danger);
    }

    /** Warning-styled notification (amber). */
    public static void warning(@Nonnull PlayerRef playerRef, @Nonnull Message message) {
        send(playerRef, message, NotificationStyle.Warning);
    }

    /** Success-styled notification (green). */
    public static void success(@Nonnull PlayerRef playerRef, @Nonnull Message message) {
        send(playerRef, message, NotificationStyle.Success);
    }

    /** Send a notification with an explicit style. */
    public static void send(@Nonnull PlayerRef playerRef, @Nonnull Message message, @Nonnull NotificationStyle style) {
        try {
            PacketHandler handler = playerRef.getPacketHandler();
            NotificationUtil.sendNotification(handler, message, style);
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("Notify.send failed: " + t.getMessage());
        }
    }

    /**
     * A notification carrying an item IDENTITY the client can stack on: the {@code Item} slot
     * (built via {@link ItemStack#toPacket()}) is the only identity-bearing field on the
     * Notification packet, so consecutive notifications sharing the same {@code itemId} coalesce
     * client-side into one growing entry (native pickup behavior; no id/group/stack-key field
     * exists on the packet itself). The stacked entry FREEZES on {@code title}'s text; only the
     * item-slot {@code quantity} keeps updating - so a caller wanting live-looking stacked totals
     * must keep the amount OUT of {@code title} and IN {@code quantity} (the coalescing XP toast
     * and a native-pickup-style item toast both rely on this).
     *
     * <p>Default style (matches a native pickup notification); no SFX and no
     * {@code ShowItemPickupNotifications} gate here (that policy belongs to a caller like {@code
     * PickupMimic} that specifically mimics a real pickup - this helper is the bare stacking
     * mechanism). Try-guarded so it never throws into the caller.
     */
    public static void itemKeyed(@Nonnull PlayerRef playerRef, @Nonnull Message title,
            @Nullable Message secondary, @Nonnull String itemId, int quantity) {
        try {
            PacketHandler handler = playerRef.getPacketHandler();
            ItemStack itemStack = new ItemStack(itemId, Math.max(1, quantity));
            NotificationUtil.sendNotification(handler, title, secondary, itemStack.toPacket());
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("Notify.itemKeyed failed: " + t.getMessage());
        }
    }
}
