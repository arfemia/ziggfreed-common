package com.ziggfreed.common.settings.page;

import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Settings tab's place in the library, called once from the wiring root's {@code setup()}: its
 * destination (before any asset decodes), its tab on the shared rail, and the library's own Notifications
 * and Combat sections. Registration only ({@code RootRegistrationOnlyTest}).
 */
public final class SettingsBootstrap {

    private SettingsBootstrap() {
    }

    public static void registerPage() {
        try {
            SettingsDestinations.register();
            ZigMenu.fill(MenuSlot.SETTINGS, SettingsDestinations.entry());
            ZigSettings.fill(SettingsSlot.NOTIFICATIONS, NotificationSettings::section);
            ZigSettings.fill(SettingsSlot.COMBAT, CombatSettings::section);
        } catch (Throwable t) {
            SafeLog.warn("[settings] the Settings tab could not be registered", t);
        }
    }
}
