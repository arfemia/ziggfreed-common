package com.ziggfreed.common.settings.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.settings.PlayerSettingsAsset;
import com.ziggfreed.common.settings.PlayerSettingsConfig;
import com.ziggfreed.common.ui.hud.panel.HudPanelAsset;
import com.ziggfreed.common.ui.hud.panel.HudPanelConfig;
import com.ziggfreed.common.ui.hud.panel.HudSpotAsset;
import com.ziggfreed.common.ui.hud.panel.HudSpotConfig;

/**
 * The library's Notifications card: the quest and achievement level first, then per bar display a
 * sub-heading over Show and Where it sits, in the order given; an owner's lock hides its own row, a
 * display switched off for everyone hides all of its rows, and a Where row needs a spot to offer.
 */
class NotificationSettingsTest {

    private static final List<String> PANELS = List.of("World_Bars", "Activity_Ledger");

    @Nonnull
    private static HudPanelAsset panel(@Nonnull String json, @Nonnull String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudPanelAsset.class, id, null);
        return HudPanelAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    @Nonnull
    private static HudSpotAsset spot(@Nonnull String json, @Nonnull String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudSpotAsset.class, id, null);
        return HudSpotAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    @Nonnull
    private static PlayerSettingsAsset record(@Nonnull String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(PlayerSettingsAsset.class, "Default", null);
        return PlayerSettingsAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    @BeforeEach
    void ship() throws Exception {
        HudPanelConfig.getInstance().mergePackLayer(Map.of(
                "World_Bars", panel("{ }", "World_Bars"),
                "Activity_Ledger", panel("{ }", "Activity_Ledger")));
        HudSpotConfig.getInstance().mergePackLayer(Map.of(
                "Top_Right", spot("{ \"Panels\": [\"World_Bars\"] }", "Top_Right"),
                "Top_Left", spot("{ \"Panels\": [\"Activity_Ledger\", \"World_Bars\"] }", "Top_Left"),
                "Quest_Tracker_Right", spot("{ \"Panels\": [\"Quest_Tracker\"] }", "Quest_Tracker_Right")));
    }

    @AfterEach
    void clear() {
        HudPanelConfig.getInstance().mergePackLayer(Map.of());
        HudPanelConfig.getInstance().mergeOwnerLayer(Map.of());
        HudSpotConfig.getInstance().mergePackLayer(Map.of());
        HudSpotConfig.getInstance().mergeOwnerLayer(Map.of());
        PlayerSettingsConfig.getInstance().mergePackLayer(Map.of());
        PlayerSettingsConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    @Nonnull
    private static List<String> ids(@Nonnull SettingsSection section) {
        return section.rows().stream().map(SettingsRow::id).toList();
    }

    @Nonnull
    private static List<String> shown(@Nonnull SettingsSection section) {
        List<String> out = new ArrayList<>();
        for (SettingsRow row : section.rows()) {
            if (SettingsPlan.visible(row, SettingsViewer.NOBODY)) {
                out.add(row.id());
            }
        }
        return out;
    }

    @Nonnull
    private static SettingsRow row(@Nonnull SettingsSection section, @Nonnull String id) {
        return section.rows().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theLevelComesFirstThenEachDisplayInTheOrderGiven() {
        SettingsSection section = NotificationSettings.section(PANELS);

        assertEquals(NotificationSettings.ID, section.id());
        assertEquals(List.of(NotificationSettings.LEVEL,
                "notifications.surface.World_Bars", "notifications.show.World_Bars", "notifications.where.World_Bars",
                "notifications.surface.Activity_Ledger", "notifications.show.Activity_Ledger",
                "notifications.where.Activity_Ledger"), ids(section));
        assertEquals(List.of(SettingsRow.Kind.CHOICE, SettingsRow.Kind.HEADING, SettingsRow.Kind.TOGGLE,
                SettingsRow.Kind.CHOICE, SettingsRow.Kind.HEADING, SettingsRow.Kind.TOGGLE, SettingsRow.Kind.CHOICE),
                section.rows().stream().map(SettingsRow::kind).toList());
    }

    @Test
    void everyRowShowsOnAServerThatFixedNothing() {
        SettingsSection section = NotificationSettings.section(PANELS);

        assertEquals(ids(section), shown(section));
    }

    @Test
    void aLockedLevelHidesItsRow() throws Exception {
        PlayerSettingsConfig.getInstance().mergePackLayer(Map.of("Default",
                record("{ \"Notifications\": { \"Level\": { \"Locked\": true } } }")));

        assertFalse(shown(NotificationSettings.section(PANELS)).contains(NotificationSettings.LEVEL));
    }

    @Test
    void aDisplaySwitchedOffForEveryoneHidesAllItsRows() throws Exception {
        HudPanelConfig.getInstance().mergeOwnerLayer(Map.of("World_Bars", panel("{ \"Enabled\": false }", "World_Bars")));

        List<String> shown = shown(NotificationSettings.section(PANELS));
        assertFalse(shown.stream().anyMatch(id -> id.endsWith("World_Bars")));
        assertTrue(shown.contains("notifications.show.Activity_Ledger"));
    }

    @Test
    void aLockedShowOrSpotHidesOnlyItsOwnRow() throws Exception {
        HudPanelConfig.getInstance().mergeOwnerLayer(Map.of("World_Bars",
                panel("{ \"Player\": { \"Show\": { \"Locked\": true } } }", "World_Bars")));
        List<String> showLocked = shown(NotificationSettings.section(PANELS));
        assertFalse(showLocked.contains("notifications.show.World_Bars"));
        assertTrue(showLocked.contains("notifications.where.World_Bars"));
        assertTrue(showLocked.contains("notifications.surface.World_Bars"));

        HudPanelConfig.getInstance().mergeOwnerLayer(Map.of("World_Bars",
                panel("{ \"Player\": { \"Show\": { \"Locked\": true }, \"Spot\": { \"Locked\": true } } }", "World_Bars")));
        List<String> bothLocked = shown(NotificationSettings.section(PANELS));
        assertFalse(bothLocked.contains("notifications.where.World_Bars"));
        assertFalse(bothLocked.contains("notifications.surface.World_Bars"), "a heading over nothing goes too");
    }

    @Test
    void aWhereRowNeedsASpotToOffer() {
        HudSpotConfig.getInstance().mergePackLayer(Map.of());

        List<String> shown = shown(NotificationSettings.section(PANELS));
        assertFalse(shown.contains("notifications.where.World_Bars"));
        assertTrue(shown.contains("notifications.show.World_Bars"));
        assertTrue(shown.contains("notifications.surface.World_Bars"));
    }

    @Test
    void theLevelOffersTheFourWordsAndReadsTheEffectiveOne() throws Exception {
        SettingsRow.Choice level = row(NotificationSettings.section(PANELS), NotificationSettings.LEVEL).choice();

        assertEquals(List.of("EveryUpdate", "Milestones", "Finishes", "None"),
                level.options(SettingsViewer.NOBODY).stream().map(SettingsOption::value).toList());
        assertEquals("EveryUpdate", level.value(SettingsViewer.NOBODY));
        PlayerSettingsConfig.getInstance().mergePackLayer(Map.of("Default",
                record("{ \"Notifications\": { \"Level\": { \"Default\": \"Finishes\" } } }")));
        assertEquals("Finishes", level.value(SettingsViewer.NOBODY));
        assertFalse(level.set(SettingsViewer.NOBODY, "Milestones"), "nobody to keep it for");
        assertFalse(level.set(SettingsViewer.NOBODY, "Loud"), "a word nobody knows is never kept");
    }

    @Test
    void whereOffersTheServersChoiceThenTheSpotsMeasuredForThatDisplay() {
        SettingsSection section = NotificationSettings.section(PANELS);
        SettingsRow.Choice where = row(section, "notifications.where.Activity_Ledger").choice();

        assertEquals(List.of(SpotOptions.SERVER_CHOICE, "top_left"),
                where.options(SettingsViewer.NOBODY).stream().map(SettingsOption::value).toList(),
                "never a spot measured for another display, nor the tracker's");
        assertEquals(SpotOptions.SERVER_CHOICE, where.value(SettingsViewer.NOBODY));
        assertFalse(where.set(SettingsViewer.NOBODY, "top_left"));

        SettingsRow.Toggle show = row(section, "notifications.show.World_Bars").toggle();
        assertTrue(show.on(SettingsViewer.NOBODY), "unset and unlocked: shown");
        assertFalse(show.set(SettingsViewer.NOBODY, false), "nobody to keep it for");
    }
}
