package com.ziggfreed.common.feedback;

import javax.annotation.Nonnull;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
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
}
