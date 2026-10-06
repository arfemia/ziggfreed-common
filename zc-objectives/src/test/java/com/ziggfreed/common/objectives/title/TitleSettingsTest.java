package com.ziggfreed.common.objectives.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.entity.title.ActiveTitles;
import com.ziggfreed.common.settings.page.SettingsRow;
import com.ziggfreed.common.settings.page.SettingsSection;
import com.ziggfreed.common.settings.page.SettingsViewer;
import com.ziggfreed.common.ui.hud.settings.HudSettingsDeps;
import com.ziggfreed.common.ui.hud.settings.HudSettingsPages;

/**
 * The Title card: a tile naming the title the player shows and opening the picker, and for the HUD page's
 * own audience alone a tile opening the server's HUD layout.
 */
class TitleSettingsTest {

    private static final UUID ZIG = UUID.randomUUID();

    @BeforeEach
    void titles() throws Exception {
        TitleConfig.getInstance().mergePackLayer(Map.of(
                "Hero", TitleAssetTest.title("{}", "Hero"),
                "Retired", TitleAssetTest.title("{ \"Enabled\": false }", "Retired")));
        ActiveTitles.persistTo(null, null);
    }

    @AfterEach
    void clear() {
        ActiveTitles.clear();
        TitleConfig.getInstance().mergePackLayer(Map.of());
        TitleConfig.getInstance().mergeOwnerLayer(Map.of());
        HudSettingsPages.deps(null);
    }

    @Nonnull
    private static SettingsRow row(@Nonnull String id) {
        return TitleSettings.section().rows().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theTitleCardIsTheTwoTiles() {
        SettingsSection section = TitleSettings.section();

        assertEquals(TitleSettings.ID, section.id());
        assertEquals(List.of(TitleSettings.PICKER, TitleSettings.LAYOUT),
                section.rows().stream().map(SettingsRow::id).toList());
        assertTrue(section.rows().stream().allMatch(r -> r.kind() == SettingsRow.Kind.TILE), "both open a screen");
        assertEquals(TitleSettings.PICKER_ICON, row(TitleSettings.PICKER).icon());
        assertEquals(TitleSettings.LAYOUT_ICON, row(TitleSettings.LAYOUT).icon());
    }

    @Test
    void theTileSaysWhichTitleThePlayerShows() {
        ActiveTitles.put(ZIG, "hero");

        assertEquals("ziggfreedcommon.title.picker.settings_wearing", TitleSettings.worn(ZIG).getMessageId());
    }

    @Test
    void noTitleOrOneSwitchedOffReadsAsNone() {
        String none = "ziggfreedcommon.title.picker.settings_none";
        assertEquals(none, TitleSettings.worn(ZIG).getMessageId());
        ActiveTitles.put(ZIG, "retired");
        assertEquals(none, TitleSettings.worn(ZIG).getMessageId(), "a title switched off shows nothing");
        assertEquals(none, TitleSettings.worn(null).getMessageId());
    }

    @Test
    void theServersLayoutTileIsTheHudPagesAudiencesAlone() {
        SettingsRow layout = row(TitleSettings.LAYOUT);
        assertFalse(layout.visible().test(SettingsViewer.NOBODY), "nobody looking administers nothing");
        HudSettingsPages.deps(() -> HudSettingsDeps.builder().audience((store, ref, player) -> true).build());
        assertFalse(layout.visible().test(SettingsViewer.NOBODY), "still no player to ask the audience about");

        assertTrue(row(TitleSettings.PICKER).visible().test(SettingsViewer.NOBODY), "the title tile is every player's");
        assertFalse(row(TitleSettings.PICKER).tile().open(SettingsViewer.NOBODY), "nobody to open the picker for");
    }
}
