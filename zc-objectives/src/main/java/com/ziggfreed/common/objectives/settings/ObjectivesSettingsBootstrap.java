package com.ziggfreed.common.objectives.settings;

import com.ziggfreed.common.objectives.bonus.BonusFindSettings;
import com.ziggfreed.common.objectives.hud.TrackerSettings;
import com.ziggfreed.common.objectives.title.TitleSettings;
import com.ziggfreed.common.settings.page.SettingsSlot;
import com.ziggfreed.common.settings.page.ZigSettings;
import com.ziggfreed.common.util.SafeLog;

/**
 * This module's three sections of the Settings tab (the quest tracker, the lucky finds note and the title), filled
 * once from the wiring root's {@code setup()} the way its menu tabs are. Registration only
 * ({@code RootRegistrationOnlyTest}).
 */
public final class ObjectivesSettingsBootstrap {

    private ObjectivesSettingsBootstrap() {
    }

    public static void registerSections() {
        try {
            ZigSettings.fill(SettingsSlot.QUEST_TRACKER, TrackerSettings::section);
            ZigSettings.fill(SettingsSlot.BONUS_FINDS, BonusFindSettings::section);
            ZigSettings.fill(SettingsSlot.TITLE, TitleSettings::section);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the quest tracker, lucky finds and title settings could not be registered", t);
        }
    }
}
