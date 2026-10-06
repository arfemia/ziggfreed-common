package com.ziggfreed.common.objectives.hud;

import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.settings.PlayerSettings;
import com.ziggfreed.common.settings.page.SettingsRow;
import com.ziggfreed.common.settings.page.SettingsSection;
import com.ziggfreed.common.settings.page.SettingsText;
import com.ziggfreed.common.settings.page.SurfaceRows;
import com.ziggfreed.common.ui.hud.command.HudMessages;

/**
 * The Settings tab's Quest tracker card: Show, then where it sits among the spots offered to the tracker
 * (a spot whose {@code Panels} names {@code Quest_Tracker}). The whole card goes while the owner has the
 * tracker off ({@link TrackedQuestHudDeps#isEnabled}), and each lock in the player-settings record hides
 * its own row.
 */
public final class TrackerSettings {

    public static final String ID = "quest_tracker";
    static final String SHOW = "tracker.show";
    static final String WHERE = "tracker.where";

    private static final String HEADING_KEY = "ziggfreedcommon.progression.settings.tracker";

    private TrackerSettings() {
    }

    @Nonnull
    public static SettingsSection section() {
        String tracker = PlayerSettings.QUEST_TRACKER;
        return new SettingsSection(ID, Msg.key(HEADING_KEY), List.of(
                SettingsRow.toggle(SHOW, SettingsText.line("show"), null,
                        viewer -> ownerHasItOn() && !PlayerSettings.rules(tracker).showLocked(),
                        SurfaceRows.show(tracker)),
                SettingsRow.choice(WHERE, HudMessages.line("settings.spot"), null,
                        viewer -> ownerHasItOn() && !PlayerSettings.rules(tracker).spotLocked()
                                && SurfaceRows.whereOffered(tracker),
                        SurfaceRows.where(tracker))));
    }

    private static boolean ownerHasItOn() {
        return TrackedQuestHuds.resolvedDeps().isEnabled();
    }
}
