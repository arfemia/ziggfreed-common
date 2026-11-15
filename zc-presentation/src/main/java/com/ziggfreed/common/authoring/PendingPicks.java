package com.ziggfreed.common.authoring;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.util.SafeLog;

/**
 * The targeting handoff's one piece of state: the wizard a player left to pick something in the world, and the
 * field the pick fills. The page's "Pick in the world" button parks the session here and closes; the player's next
 * click with any authoring tool takes it back ({@link AuthoringTools}), fills the field and opens the page again. A
 * park lasts {@link #WINDOW_MS}, then the next click opens the tool's own wizard as usual; opening any wizard drops
 * it ({@link AuthoringWizards#open}), and so does leaving.
 */
final class PendingPicks {

    /** How long a wizard waits for its pick, in milliseconds. */
    static final long WINDOW_MS = 120_000L;

    /** A parked wizard: the session, the field the pick fills, and when it stops waiting. */
    record Pending(@Nonnull WizardSession session, @Nonnull String fieldId, long until) {
    }

    private static final Map<UUID, Pending> BY_PLAYER = new ConcurrentHashMap<>();

    private PendingPicks() {
    }

    /** Park {@code session} for {@code player} until a click fills {@code fieldId}, replacing any earlier park. */
    static void await(@Nonnull UUID player, @Nonnull WizardSession session, @Nonnull String fieldId) {
        BY_PLAYER.put(player, new Pending(session, fieldId, System.currentTimeMillis() + WINDOW_MS));
    }

    /** Take back the player's parked wizard, or null when none waits (or its window has passed). */
    @Nullable
    static Pending take(@Nonnull UUID player) {
        Pending pending = BY_PLAYER.remove(player);
        return pending == null || pending.until() < System.currentTimeMillis() ? null : pending;
    }

    /** Drop the player's parked wizard, if any: they opened another, so the park must not take their next click. */
    static void forget(@Nonnull UUID player) {
        BY_PLAYER.remove(player);
    }

    /** A departing player's parked wizard is dropped. */
    static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef player = event.getPlayerRef();
            if (player != null) {
                BY_PLAYER.remove(player.getUuid());
            }
        } catch (Throwable t) {
            SafeLog.fine("[authoring] a departing player's parked wizard could not be dropped: " + t.getMessage());
        }
    }
}
