package com.ziggfreed.common.objectives.bonus;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.feedback.Notify;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.trigger.BonusMoment;
import com.ziggfreed.common.settings.PlayerSettings;
import com.ziggfreed.common.util.SafeLog;

/**
 * The note every bonus find speaks with (R915): a block a player broke, a hand harvest or a kill that handed something
 * extra over, from any mod's bonus row, with or without a consumer installed. {@link BonusRowReactions} announces each
 * row that paid and whose find no roll's {@code Cue} announced already; a mod's own find calls {@link #announce} too,
 * so one note and one switch cover them all.
 *
 * <p>Each player's own switch is {@code Notifications.BonusFinds} on the Settings tab ({@link PlayerSettings#bonusFinds},
 * the {@link BonusFindSettings} card): on unless they turn it off. A burst batches per player and moment: the first
 * find speaks at once, finds within {@link #BATCH_MS} of it add up silently, and the next find after the window speaks
 * with the running total ({@code find.stacked}). Each moment speaks on its own feed line (a tag), so a burst rewrites
 * one line. The wording is {@code ziggfreedcommon.bonus.lang}'s, gold for a kill and yellow for a break or a harvest;
 * a caller may author its own headline, second line, colour and picture ({@link #announce(PlayerRef, BonusMoment, int,
 * Message, Message, String, String)}), and an authored headline binds no count.
 *
 * <p>World thread (the switch reads the player's own record). The batches are transient and single-process, forgotten
 * when the player leaves ({@link #onDisconnect}).
 */
public final class BonusFindNotes {

    /** How long a burst adds up before the next find speaks its total. */
    static final long BATCH_MS = 1000L;

    /** The lang file's prefix. */
    static final String PREFIX = "ziggfreedcommon.bonus.";

    /** The feed line each moment's notes share. */
    static final String TAG_PREFIX = "ziggfreedcommon.bonus.";

    /** A break's and a harvest's note colour. */
    static final String FIND_COLOR = "#ffff00";

    /** A kill's note colour. */
    static final String KILL_COLOR = "#ffaa00";

    /** The second line's colour. */
    static final String LINE_COLOR = "#ffffff";

    /** One player's burst for one moment. */
    private static final class Batch {
        long lastSpokeMs = Long.MIN_VALUE;
        long held;
    }

    private static final ConcurrentHashMap<UUID, Map<BonusMoment, Batch>> BATCHES = new ConcurrentHashMap<>();

    private BonusFindNotes() {
    }

    /** Announce {@code count} items a {@code moment} find handed {@code playerRef}, in the library's own words. */
    public static void announce(@Nullable PlayerRef playerRef, @Nonnull BonusMoment moment, int count) {
        announce(playerRef, moment, count, null, null, null, null);
    }

    /**
     * Announce a {@code moment} find of {@code count} items (at least one: a find that handed over no item, a
     * command alone, counts as one), unless the player turned the note off or a burst is adding up. {@code title},
     * {@code line}, {@code color} ({@code #rrggbb}) and {@code icon} (an item id) replace the library's own where
     * given. Never throws.
     */
    public static void announce(@Nullable PlayerRef playerRef, @Nonnull BonusMoment moment, int count,
            @Nullable Message title, @Nullable Message line, @Nullable String color, @Nullable String icon) {
        try {
            if (playerRef == null || !PlayerSettings.bonusFinds(playerRef)) {
                return;
            }
            UUID player = playerRef.getUuid();
            if (player == null) {
                return;
            }
            long total = gather(player, moment, Math.max(1, count), System.currentTimeMillis());
            if (total <= 0) {
                return;
            }
            Message primary = title != null ? title
                    : total > 1 ? Msg.key(PREFIX + "find.stacked", total) : Msg.key(PREFIX + "find");
            Message secondary = line != null ? line : Msg.key(PREFIX + lineKey(moment));
            String hex = color == null || color.isBlank() ? colorOf(moment) : color.trim();
            Notify.withIcon(playerRef, primary.color(hex), secondary.color(LINE_COLOR), icon,
                    NotificationStyle.Default, TAG_PREFIX + moment.token());
        } catch (Throwable t) {
            SafeLog.fine("[loot] a bonus find note could not be sent: " + t.getMessage());
        }
    }

    /**
     * Add {@code count} to {@code player}'s burst for {@code moment}: the total to speak now (the first find, or
     * the first after the window, with every find held since), or 0 while the burst is adding up.
     */
    static long gather(@Nonnull UUID player, @Nonnull BonusMoment moment, long count, long nowMs) {
        Batch batch = BATCHES.computeIfAbsent(player, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(moment, key -> new Batch());
        synchronized (batch) {
            batch.held += count;
            if (batch.lastSpokeMs != Long.MIN_VALUE && nowMs - batch.lastSpokeMs < BATCH_MS) {
                return 0L;
            }
            long total = batch.held;
            batch.held = 0L;
            batch.lastSpokeMs = nowMs;
            return total;
        }
    }

    /** The second line's key for {@code moment}. */
    @Nonnull
    static String lineKey(@Nonnull BonusMoment moment) {
        return switch (moment) {
            case BREAK_BLOCK -> "find.block";
            case KILL_MOB -> "find.kill";
            case PICKUP_ITEM -> "find.harvest";
        };
    }

    /** The headline colour for {@code moment}. */
    @Nonnull
    static String colorOf(@Nonnull BonusMoment moment) {
        return moment == BonusMoment.KILL_MOB ? KILL_COLOR : FIND_COLOR;
    }

    /** A player leaving: their bursts go with them. */
    public static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        UUID player = playerRef == null ? null : playerRef.getUuid();
        if (player != null) {
            BATCHES.remove(player);
        }
    }

    /** Forget every burst; for a test. */
    static void clearForTests() {
        BATCHES.clear();
    }
}
