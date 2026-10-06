package com.ziggfreed.common.ui.hud.command;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.settings.PlayerSettings;

/**
 * The line a {@code /zighud} verb refuses with when the server fixed what it would change, or null when it
 * may go ahead: a verb never reports a change the owner's lock would hide from every read.
 */
final class HudCommandLocks {

    private HudCommandLocks() {
    }

    /** {@code place}: the owner fixed where {@code panelId} sits. */
    @Nullable
    static String placeRefusal(@Nonnull String panelId) {
        return PlayerSettings.rules(panelId).spotLocked() ? "place.locked" : null;
    }

    /** {@code hide} or {@code show} on one panel: the owner fixed whether {@code panelId} shows. */
    @Nullable
    static String showRefusal(@Nonnull String panelId) {
        return PlayerSettings.rules(panelId).showLocked() ? "hide.locked" : null;
    }
}
