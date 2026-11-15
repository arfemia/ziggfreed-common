package com.ziggfreed.common.objectives.bonus;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.settings.PlayerSettings;
import com.ziggfreed.common.settings.page.SettingsRow;
import com.ziggfreed.common.settings.page.SettingsSection;
import com.ziggfreed.common.settings.page.SettingsViewer;

/**
 * The Settings tab's lucky finds card (R915, {@code SettingsSlot.BONUS_FINDS}): one switch, whether the player sees the
 * note a bonus find speaks with ({@link BonusFindNotes}), any mod's find included. Always offered: the note is each
 * player's own call.
 */
public final class BonusFindSettings {

    public static final String ID = "bonusfinds";
    public static final String NOTE = "bonusfinds.note";

    private BonusFindSettings() {
    }

    /** The section as this server has it now. */
    @Nonnull
    public static SettingsSection section() {
        return new SettingsSection(ID, Msg.key(BonusFindNotes.PREFIX + "settings.heading"), List.of(
                SettingsRow.toggle(NOTE, Msg.key(BonusFindNotes.PREFIX + "settings.note"),
                        Msg.key(BonusFindNotes.PREFIX + "settings.note.hint"), viewer -> true, NOTE_TOGGLE)));
    }

    /** The switch: the effective choice on, a press kept as the player's own. */
    private static final SettingsRow.Toggle NOTE_TOGGLE = new SettingsRow.Toggle() {
        @Override
        public boolean on(@Nonnull SettingsViewer viewer) {
            return PlayerSettings.bonusFinds(viewer.playerRef());
        }

        @Override
        public boolean set(@Nonnull SettingsViewer viewer, boolean on) {
            PlayerRef playerRef = viewer.playerRef();
            if (!PlayerSettings.writable(playerRef)) {
                return false;
            }
            PlayerSettings.setBonusFinds(playerRef, on);
            return true;
        }
    };
}
