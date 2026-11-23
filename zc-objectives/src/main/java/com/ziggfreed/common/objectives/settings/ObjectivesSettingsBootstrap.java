package com.ziggfreed.common.objectives.settings;

import com.ziggfreed.common.objectives.bonus.BonusFindSettings;
import com.ziggfreed.common.objectives.hud.TrackerSettings;
import com.ziggfreed.common.objectives.title.TitleSettings;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.owner.OwnerFileBindings;
import com.ziggfreed.common.settings.page.SettingsSlot;
import com.ziggfreed.common.settings.page.ZigSettings;
import com.ziggfreed.common.util.SafeLog;

/**
 * This module's three sections of the Settings tab (the quest tracker, the lucky finds note and the title), filled
 * once from the wiring root's {@code setup()} the way its menu tabs are, and the owner file of the quest engine's own
 * settings ({@code progression.json}) as one the owner forms may write. Registration only
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

    /**
     * {@code mods/ziggfreedcommon/progression.json} as one an in-game editor may write
     * ({@link ProgressionSettingsOwnerLayers#BINDING}): its Default read off the record under the owner's layer, its
     * reload the record's own owner read. The quest settings form ({@code OwnerForms/Quest_Settings.json}) writes
     * through it.
     */
    public static void registerOwnerFiles() {
        try {
            OwnerFileBindings.register(OwnerFileBinding.keyedStore(ProgressionSettingsOwnerLayers.BINDING,
                    ProgressionSettingsOwnerLayers::file, ProgressionSettingsConfig.getInstance(),
                    ProgressionSettingsAsset.CODEC, ProgressionSettingsOwnerLayers::reload));
        } catch (Throwable t) {
            SafeLog.warn("[progression] progression.json could not be bound, so the quest settings form writes nothing",
                    t);
        }
    }
}
