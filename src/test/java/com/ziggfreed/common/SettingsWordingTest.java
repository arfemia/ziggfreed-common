package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Nothing the Settings tab shows a player says "bar" or "panel": every en-US value its pages, cards,
 * rows, dropdowns and tiles show (the library's own and the two displays' names) is checked here, across
 * the modules that author them.
 */
class SettingsWordingTest {

    private record Key(String module, String file, String key) {
    }

    private static final String UI = "ziggfreedcommon.ui.lang";
    private static final String HUD = "ziggfreedcommon.hud.lang";

    private static final List<Key> SHOWN = List.of(
            new Key("zc-presentation", UI, "menu.settings"),
            new Key("zc-presentation", UI, "settings.title"),
            new Key("zc-presentation", UI, "settings.description"),
            new Key("zc-presentation", UI, "settings.notifications"),
            new Key("zc-presentation", UI, "settings.level"),
            new Key("zc-presentation", UI, "settings.level.hint"),
            new Key("zc-presentation", UI, "settings.level.everyupdate"),
            new Key("zc-presentation", UI, "settings.level.milestones"),
            new Key("zc-presentation", UI, "settings.level.finishes"),
            new Key("zc-presentation", UI, "settings.level.none"),
            new Key("zc-presentation", UI, "settings.show"),
            new Key("zc-presentation", HUD, "panel.world_bars"),
            new Key("zc-presentation", HUD, "panel.activity_ledger"),
            new Key("zc-presentation", HUD, "settings.spot"),
            new Key("zc-presentation", HUD, "settings.server_spot"),
            new Key("zc-presentation", HUD, "settings.on"),
            new Key("zc-presentation", HUD, "settings.off"),
            new Key("zc-presentation", HUD, "settings.not_kept"),
            new Key("zc-presentation", HUD, "settings.layout_tile"),
            new Key("zc-presentation", HUD, "settings.layout_tile_line"),
            new Key("zc-presentation", HUD, "spot.top_left"),
            new Key("zc-presentation", HUD, "spot.top_right"),
            new Key("zc-presentation", HUD, "spot.bottom_left"),
            new Key("zc-presentation", HUD, "spot.quest_tracker_right"),
            new Key("zc-presentation", HUD, "spot.quest_tracker_left"),
            new Key("zc-objectives", "ziggfreedcommon.progression.lang", "settings.tracker"),
            new Key("zc-objectives", "ziggfreedcommon.title.lang", "picker.settings_heading"),
            new Key("zc-objectives", "ziggfreedcommon.title.lang", "picker.settings_tile"),
            new Key("zc-objectives", "ziggfreedcommon.title.lang", "picker.settings_wearing"),
            new Key("zc-objectives", "ziggfreedcommon.title.lang", "picker.settings_none"));

    private static final Pattern BAR_OR_PANEL = Pattern.compile("(?i)\\b(bars?|panels?)\\b");

    @Test
    void noWordTheSettingsTabShowsSaysBarOrPanel() throws IOException {
        for (Key key : SHOWN) {
            Path file = Path.of(key.module(), "src", "main", "resources", "Server", "Languages", "en-US", key.file());
            String value = values(file).get(key.key());
            assertNotNull(value, "en-US " + key.file() + " must author " + key.key());
            assertFalse(BAR_OR_PANEL.matcher(value).find(),
                    key.key() + " = '" + value + "' says bar or panel to a player on the Settings tab");
        }
    }

    private static Map<String, String> values(Path file) throws IOException {
        Map<String, String> out = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && eq > 0) {
                out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
        return out;
    }
}
