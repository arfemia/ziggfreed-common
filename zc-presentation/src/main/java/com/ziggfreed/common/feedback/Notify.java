package com.ziggfreed.common.feedback;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.ItemWithAllMetadata;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import com.ziggfreed.common.CommonLog;

/**
 * A thin, styled wrapper over the engine {@code NotificationUtil} toast API. Routes
 * a {@link Message} (the caller builds the localized message; this util is config-free
 * and never reads a locale) to one player with a {@link NotificationStyle}, plus
 * named helpers for the four common styles (Default / Danger / Warning / Success).
 *
 * <p><b>The feed is seven lines that drain STRICTLY OLDEST FIRST, and a merge does not move the
 * entry it lands on.</b> Each entry gets five seconds, but the client stops draining at the first
 * entry that has not expired, and merging into an entry refreshes its five seconds where it already
 * sits. So an entry near the front that keeps being merged into holds up everything behind it for as
 * long as the merges keep coming, and the backlog only clears about five seconds after they stop.
 *
 * <p><b>That makes a tag the wrong tool for anything that repeats often.</b> A value that moves every
 * few seconds does not belong on this feed at all, tagged or not - draw it on a progress bar
 * ({@code ui.hud.panel}) and leave the feed for things that happen once. Tags are for a notice that
 * speaks occasionally about ONE standing thing, where the newest wording is the only one worth a
 * line. Two notices that must BOTH be read never share a tag: the second replaces the first outright.
 *
 * <p>An ITEM notice merges on the ITEM as well, whether or not it carries a tag, keeping the first
 * one's words and only growing its quantity badge. So a run of pickups of the same item refreshes
 * one entry over and over, which is the commonest way to pin the feed by accident.
 *
 * <p>Nothing takes a notice back off the feed. The packet carries no id and no lifetime, no packet
 * removes an entry, and hiding the whole component neither clears the list nor lets it drain - it
 * freezes the clock instead. Sending less is the only control there is.
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

    /** Send a notification with an explicit style, as its own entry in the feed. */
    public static void send(@Nonnull PlayerRef playerRef, @Nonnull Message message, @Nonnull NotificationStyle style) {
        send(playerRef, message, style, null);
    }

    /**
     * {@link #send(PlayerRef, Message, NotificationStyle)} under a {@code tag}: a later notice
     * carrying the same tag REPLACES this one where it stands instead of stacking beneath it. Null
     * tags nothing.
     */
    public static void send(@Nonnull PlayerRef playerRef, @Nonnull Message message,
            @Nonnull NotificationStyle style, @Nullable String tag) {
        try {
            PacketHandler handler = playerRef.getPacketHandler();
            NotificationUtil.sendNotification(handler, message, null, null, null, style, tag(tag));
        } catch (Throwable t) {
            CommonLog.LOGGER.atFine().log("Notify.send failed: " + t.getMessage());
        }
    }

    /**
     * A two-line notification illustrated by an item, with NO quantity badge and no merging: the
     * item is a picture here, not a pickup.
     *
     * <p>Quantity zero is what suppresses the badge - one reading "x1" beside an achievement's
     * trophy is noise. Use {@link #itemKeyed} instead when the item really is a thing the player
     * just gained and consecutive ones SHOULD merge into one growing entry.
     *
     * <p>A null or blank {@code iconItemId} simply sends the same notification without a picture.
     * Try-guarded so it never throws into the caller.
     */
    public static void withIcon(@Nonnull PlayerRef playerRef, @Nonnull Message title,
            @Nullable Message secondary, @Nullable String iconItemId) {
        withIcon(playerRef, title, secondary, iconItemId, NotificationStyle.Default);
    }

    /**
     * {@link #withIcon(PlayerRef, Message, Message, String)} with an explicit
     * {@link NotificationStyle}, for a caller whose notice carries its own tone (a feedback moment
     * authored gold or red keeps that tone on the feed too).
     */
    public static void withIcon(@Nonnull PlayerRef playerRef, @Nonnull Message title,
            @Nullable Message secondary, @Nullable String iconItemId,
            @Nonnull NotificationStyle style) {
        withIcon(playerRef, title, secondary, iconItemId, style, null);
    }

    /**
     * {@link #withIcon(PlayerRef, Message, Message, String, NotificationStyle)} under a {@code
     * tag}, which is what a notice speaking repeatedly about ONE thing wants: a step counting up
     * rewrites its own line rather than adding another, the newest send supplying the words. Null
     * tags nothing and stacks as before.
     *
     * <p>Two sends that merge and carry the SAME picture read as the same item to the client, which
     * grows a badge rather than rewriting words. So a notice whose WORDS change every time (a
     * counter) is illustrated by nothing, or by a picture that changes with them; the picture and
     * the live number are not both available on one line.
     */
    public static void withIcon(@Nonnull PlayerRef playerRef, @Nonnull Message title,
            @Nullable Message secondary, @Nullable String iconItemId,
            @Nonnull NotificationStyle style, @Nullable String tag) {
        try {
            ItemWithAllMetadata icon = null;
            if (iconItemId != null && !iconItemId.isBlank()) {
                icon = new ItemWithAllMetadata();
                icon.itemId = iconItemId.trim();
                icon.quantity = 0;
            }
            NotificationUtil.sendNotification(playerRef.getPacketHandler(), title, secondary,
                    null, icon, style, tag(tag));
        } catch (Throwable t) {
            CommonLog.LOGGER.atFine().log("Notify.withIcon failed: " + t.getMessage());
        }
    }

    /**
     * A notification carrying an item the player just gained, as its own entry in the feed. Pass a
     * tag through {@link #itemKeyed(PlayerRef, Message, Message, String, int, String)} to have a run
     * of them GROW one entry instead, which is what a repeating gain wants.
     */
    public static void itemKeyed(@Nonnull PlayerRef playerRef, @Nonnull Message title,
            @Nullable Message secondary, @Nonnull String itemId, int quantity) {
        itemKeyed(playerRef, title, secondary, itemId, quantity, null);
    }

    /**
     * A notification carrying an item the player just gained, merged under {@code tag}: a later
     * notice with the same tag naming the same item ADDS its quantity to the showing entry's badge
     * rather than stacking a second line, which is how a pile that keeps growing reads as one pile.
     *
     * <p>The merged entry keeps the FIRST send's words and only its badge climbs, so keep the amount
     * OUT of {@code title} and IN {@code quantity}, and give any line whose WORDS differ a tag of
     * its own (a lucky find and an ordinary one are two wordings, so two tags). A null tag makes
     * each send its own entry.
     *
     * <p>Default style (matches a native pickup notification); no SFX and no
     * {@code ShowItemPickupNotifications} gate here (that policy belongs to a caller like {@code
     * PickupMimic} that specifically mimics a real pickup - this helper is the bare mechanism).
     * Try-guarded so it never throws into the caller.
     */
    public static void itemKeyed(@Nonnull PlayerRef playerRef, @Nonnull Message title,
            @Nullable Message secondary, @Nonnull String itemId, int quantity, @Nullable String tag) {
        try {
            PacketHandler handler = playerRef.getPacketHandler();
            ItemStack itemStack = new ItemStack(itemId, Math.max(1, quantity));
            NotificationUtil.sendNotification(handler, title, secondary, null, itemStack.toPacket(),
                    NotificationStyle.Default, tag(tag));
        } catch (Throwable t) {
            CommonLog.LOGGER.atFine().log("Notify.itemKeyed failed: " + t.getMessage());
        }
    }

    /** A blank tag is no tag: an empty string would merge every untagged-looking notice together. */
    @Nullable
    private static String tag(@Nullable String tag) {
        return tag == null || tag.isBlank() ? null : tag.trim();
    }
}
