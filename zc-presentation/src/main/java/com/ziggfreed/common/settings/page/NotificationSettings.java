package com.ziggfreed.common.settings.page;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.settings.NotificationLevel;
import com.ziggfreed.common.settings.PlayerSettings;
import com.ziggfreed.common.ui.hud.command.HudMessages;
import com.ziggfreed.common.ui.hud.panel.HudPanelConfig;
import com.ziggfreed.common.ui.hud.panel.HudPanels;

/**
 * The library's Notifications card on the Settings tab: the quest and achievement level, then for each
 * bar display (in the panels' own {@code Order}) a sub-heading with its name over Show and Where it sits.
 * A display the owner switched off for everyone shows no rows; a lock hides its own row; a sub-heading
 * over no row goes too. The words never say "bar" or "panel": a display is named by its own file.
 */
public final class NotificationSettings {

    public static final String ID = "notifications";
    public static final String LEVEL = "notifications.level";
    static final String HEADING = "notifications.surface.";
    static final String SHOW = "notifications.show.";
    static final String WHERE = "notifications.where.";

    private NotificationSettings() {
    }

    /** The section as this server has it now. */
    @Nonnull
    public static SettingsSection section() {
        return section(HudPanels.listing());
    }

    /** The section over {@code panelIds}, in that order. */
    @Nonnull
    public static SettingsSection section(@Nonnull List<String> panelIds) {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.choice(LEVEL, SettingsText.line("level"), SettingsText.line("level.hint"),
                viewer -> !PlayerSettings.levelRules().locked(), LEVEL_CHOICE));
        for (String id : panelIds) {
            rows.add(SettingsRow.heading(HEADING + id, HudPanelConfig.getInstance().panel(id).label(),
                    viewer -> panelOn(id) && (showOffered(id) || whereOffered(id))));
            rows.add(SettingsRow.toggle(SHOW + id, SettingsText.line("show"), null,
                    viewer -> panelOn(id) && showOffered(id), SurfaceRows.show(id)));
            rows.add(SettingsRow.choice(WHERE + id, HudMessages.line("settings.spot"), null,
                    viewer -> panelOn(id) && whereOffered(id), SurfaceRows.where(id)));
        }
        return new SettingsSection(ID, SettingsText.line("notifications"), rows);
    }

    private static boolean panelOn(@Nonnull String id) {
        return HudPanelConfig.getInstance().panel(id).enabled();
    }

    private static boolean showOffered(@Nonnull String id) {
        return !PlayerSettings.rules(id).showLocked();
    }

    private static boolean whereOffered(@Nonnull String id) {
        return !PlayerSettings.rules(id).spotLocked() && SurfaceRows.whereOffered(id);
    }

    /** The level dropdown: the four words, the effective one chosen, a pick kept as the player's own. */
    private static final SettingsRow.Choice LEVEL_CHOICE = new SettingsRow.Choice() {
        @Nonnull
        @Override
        public List<SettingsOption> options(@Nonnull SettingsViewer viewer) {
            List<SettingsOption> out = new ArrayList<>();
            for (NotificationLevel level : NotificationLevel.values()) {
                out.add(new SettingsOption(level.id(),
                        LocalizableString.fromMessageId(SettingsText.key("level." + level.key()))));
            }
            return out;
        }

        @Nonnull
        @Override
        public String value(@Nonnull SettingsViewer viewer) {
            return PlayerSettings.level(viewer.playerRef()).id();
        }

        @Override
        public boolean set(@Nonnull SettingsViewer viewer, @Nonnull String value) {
            NotificationLevel wanted = NotificationLevel.parse(value);
            PlayerRef playerRef = viewer.playerRef();
            if (wanted == null || !PlayerSettings.writable(playerRef)) {
                return false;
            }
            PlayerSettings.setLevel(playerRef, wanted);
            return true;
        }
    };
}
