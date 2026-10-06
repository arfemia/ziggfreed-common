package com.ziggfreed.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.ui.hud.HudPreferences;
import com.ziggfreed.common.ui.hud.panel.HudPanelAsset;
import com.ziggfreed.common.ui.hud.panel.HudPanelConfig;

/**
 * Every read answers the effective choice (a lock returns the owner's value, else the player's own, else
 * the owner's default) and a lock never deletes what the player chose; every REAL write is announced once
 * and a no-op not at all; a player with no record is refused; the panels' reads see the same answers.
 */
class PlayerSettingsTest {

    private final List<ZigPlayerSettingChangedEvent> fired = new ArrayList<>();

    @BeforeEach
    void listen() {
        PlayerSettings.publishTo(new NativeEventSeam.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                fired.add((ZigPlayerSettingChangedEvent) build.get());
            }
        });
    }

    @AfterEach
    void reset() {
        PlayerSettings.publishTo(null);
        PlayerSettings.clearWatchersForTests();
        PlayerSettingsConfig.getInstance().mergePackLayer(Map.of());
        PlayerSettingsConfig.getInstance().mergeOwnerLayer(Map.of());
        HudPanelConfig.getInstance().mergePackLayer(Map.of());
        HudPanelConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    @Nonnull
    private static HudPanelAsset panel(@Nonnull String json, @Nonnull String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudPanelAsset.class, id, null);
        return HudPanelAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    // ==================== effective values ====================

    @Test
    void anUnsetChoiceFollowsTheOwnersDefaultAndAPlayerMayTurnAHiddenDefaultOn() {
        PlayerSettingsComponent mine = new PlayerSettingsComponent();
        SurfaceRules hiddenByDefault = new SurfaceRules(false, null, null);

        assertTrue(PlayerSettings.effectiveShown(null, "World_Bars", SurfaceRules.NONE), "no record, no rule: shown");
        assertFalse(PlayerSettings.effectiveShown(mine, "World_Bars", hiddenByDefault), "unset follows the default");
        mine.setShown("World_Bars", true);
        assertTrue(PlayerSettings.effectiveShown(mine, "World_Bars", hiddenByDefault),
                "a player may turn on what the owner hides by default");
    }

    @Test
    void aLockReturnsTheOwnersValueAndKeepsThePlayersChoiceForWhenItLifts() {
        PlayerSettingsComponent mine = new PlayerSettingsComponent();
        mine.setShown("Quest_Tracker", false);
        mine.setLevel(NotificationLevel.NONE);

        assertTrue(PlayerSettings.effectiveShown(mine, "Quest_Tracker", new SurfaceRules(true, true, null)),
                "locked shown: the owner's value");
        assertEquals(Boolean.FALSE, mine.shown("Quest_Tracker"), "and the player's own choice is still kept");
        assertFalse(PlayerSettings.effectiveShown(mine, "Quest_Tracker", new SurfaceRules(true, false, null)),
                "unlocked again: the player's choice is back");

        assertEquals(NotificationLevel.MILESTONES, PlayerSettings.effectiveLevel(mine,
                new NotificationRules.LevelRule("Milestones", true)));
        assertEquals(NotificationLevel.NONE, PlayerSettings.effectiveLevel(mine,
                new NotificationRules.LevelRule("Milestones", false)));
    }

    @Test
    void aLockedSpotReadsAsTheServersChoice() {
        PlayerSettingsComponent mine = new PlayerSettingsComponent();
        mine.setSpot("World_Bars", "Bottom_Left");

        assertEquals("bottom_left", PlayerSettings.effectiveSpot(mine, "World_Bars", SurfaceRules.NONE));
        assertNull(PlayerSettings.effectiveSpot(mine, "World_Bars", new SurfaceRules(null, null, true)));
        assertEquals("bottom_left", mine.spot("World_Bars"), "the pick is kept under the lock");
        assertNull(PlayerSettings.effectiveSpot(null, "World_Bars", SurfaceRules.NONE));
    }

    @Test
    void theLevelFollowsTheChoiceThenTheDefault() {
        PlayerSettingsComponent mine = new PlayerSettingsComponent();
        NotificationRules.LevelRule finishes = new NotificationRules.LevelRule("Finishes", null);

        assertEquals(NotificationLevel.EVERY_UPDATE, PlayerSettings.effectiveLevel(null, NotificationRules.LevelRule.NONE));
        assertEquals(NotificationLevel.FINISHES, PlayerSettings.effectiveLevel(mine, finishes));
        mine.setLevel(NotificationLevel.MILESTONES);
        assertEquals(NotificationLevel.MILESTONES, PlayerSettings.effectiveLevel(mine, finishes));
    }

    @Test
    void theTrackersRulesComeFromTheRecordAndAPanelsFromItsOwnFile() throws Exception {
        PlayerSettingsConfig.getInstance().mergePackLayer(Map.of("Default", PlayerSettingsAssetTest.record(
                "{ \"QuestTracker\": { \"Spot\": { \"Locked\": true } } }", "Default", null)));
        HudPanelConfig.getInstance().mergePackLayer(Map.of("World_Bars",
                panel("{ \"Player\": { \"Show\": { \"Default\": false } } }", "World_Bars")));

        assertTrue(PlayerSettings.rules("quest_tracker").spotLocked(), "matched ignoring case");
        assertFalse(PlayerSettings.rules("World_Bars").showDefault());
        assertFalse(PlayerSettings.rules("World_Bars").spotLocked());
        assertTrue(PlayerSettings.rules("No_Such_Panel").showDefault(), "an unknown surface has no rules");
    }

    @Test
    void aReadWithNoPlayerAnswersTheDefaultsAndTheToastLevelIsUnknown() {
        assertTrue(PlayerSettings.shown((PlayerRef) null, "World_Bars"), "the cast picks the PlayerRef reader");
        assertNull(PlayerSettings.spot(null, "World_Bars"));
        assertFalse(PlayerSettings.hideAll(null));
        assertEquals(NotificationLevel.EVERY_UPDATE, PlayerSettings.level(null));
        assertNull(PlayerSettings.levelForToast(null), "no player: the toast keeps what was authored");
        assertFalse(PlayerSettings.writable(null));
    }

    // ==================== writes ====================

    @Test
    void aRealChangeIsAnnouncedOnceAndANoOpNotAtAll() {
        PlayerSettingsComponent mine = new PlayerSettingsComponent();
        UUID id = UUID.randomUUID();
        String setting = ZigPlayerSettingChangedEvent.shown("World_Bars");

        assertTrue(PlayerSettings.commit(mine, id, null, setting, s -> s.setShown("World_Bars", false)));
        assertFalse(PlayerSettings.commit(mine, id, null, setting, s -> s.setShown("World_Bars", false)));

        assertEquals(1, fired.size());
        assertEquals(id, fired.get(0).playerId());
        assertEquals("Hud.Shown.world_bars", fired.get(0).setting());
        assertEquals("world_bars", fired.get(0).surface());
        assertEquals("Hud.Spots.quest_tracker", ZigPlayerSettingChangedEvent.spot("Quest_Tracker"));
        assertNull(new ZigPlayerSettingChangedEvent(id, null, ZigPlayerSettingChangedEvent.LEVEL).surface());
        assertNull(new ZigPlayerSettingChangedEvent(id, null, ZigPlayerSettingChangedEvent.HIDE_ALL).surface());
    }

    @Test
    void aPlayerWithNoRecordIsRefusedAndNothingIsAnnounced() {
        assertFalse(PlayerSettings.commit(null, UUID.randomUUID(), null, ZigPlayerSettingChangedEvent.LEVEL,
                s -> s.setLevel(NotificationLevel.NONE)));
        assertTrue(fired.isEmpty());
    }

    // ==================== the panels' reads ====================

    @Test
    void aPanelHiddenByDefaultReadsHiddenForAPlayerWithNoChoice() throws Exception {
        HudPanelConfig.getInstance().mergePackLayer(Map.of(
                "World_Bars", panel("{ \"Player\": { \"Show\": { \"Default\": false } } }", "World_Bars"),
                "Activity_Ledger", panel("{ }", "Activity_Ledger")));

        assertTrue(HudPreferences.isHidden(null, "World_Bars"));
        assertFalse(HudPreferences.isHidden(null, "Activity_Ledger"));
        assertNull(HudPreferences.placementPick(null, "World_Bars"));
        assertFalse(HudPreferences.isHideAll(null));
    }
}
