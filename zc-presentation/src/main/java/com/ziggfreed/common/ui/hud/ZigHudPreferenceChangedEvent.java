package com.ziggfreed.common.ui.hud;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * A player's HUD preferences really changed: they picked a spot for a panel or cleared the pick,
 * hid a panel or showed it again, or flipped the switch hiding every panel.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; see
 * {@link HudPreferenceEvents} for the fire contract. It fires from every write path the library
 * owns (the HUD settings page and the {@code /zighud} verbs both go through {@link HudPreferences})
 * and NEVER for a write that changed nothing: picking the spot a panel already had is a successful
 * no-op with no event.
 *
 * <p>A listener that persists player data elsewhere (a database-backed consumer) marks the player
 * dirty off this event; the panels themselves repaint through the facade's own watchers and need
 * no listener. Both get the live {@link PlayerRef}, so neither has to look the player up again.
 */
public final class ZigHudPreferenceChangedEvent implements IEvent<Void> {

    private final UUID playerId;
    private final PlayerRef playerRef;
    @Nullable private final String panelId;

    public ZigHudPreferenceChangedEvent(@Nonnull UUID playerId, @Nonnull PlayerRef playerRef,
            @Nullable String panelId) {
        this.playerId = playerId;
        this.playerRef = playerRef;
        this.panelId = panelId;
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** The player's live reference; every library write path resolves one before it writes. */
    @Nonnull
    public PlayerRef playerRef() {
        return playerRef;
    }

    /** The panel the change was about, lower-cased, or null for the switch over every panel. */
    @Nullable
    public String panelId() {
        return panelId;
    }
}
