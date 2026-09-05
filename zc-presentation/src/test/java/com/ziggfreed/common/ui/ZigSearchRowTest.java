package com.ziggfreed.common.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.ui.builder.EventData;

/**
 * The search row's seam keeps the two facts a page can get wrong out of the page's hands: the
 * live value is named under an {@code @}-prefixed key (the client's resolve-as-path directive,
 * without which the path string ships literally and lands in the field as typed text), and the
 * row's parts are addressed as descendants of the instance the page named. The button words are
 * pinned to the shipped lang file, since a missing key renders as the key itself.
 */
class ZigSearchRowTest {

    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages",
            "en-US", "ziggfreedcommon.ui.lang");

    @Test
    void theValuePathIsScopedToTheInstance() {
        assertEquals("#QSearch #SearchField.Value", ZigSearchRow.valuePath("#QSearch"));
    }

    @Test
    void carryNamesTheLiveValueUnderTheDirectiveKey() {
        EventData data = EventData.of("Action", "search");
        EventData carried = ZigSearchRow.carry(data, "@SearchInput", "#ASearch");
        assertSame(data, carried, "carry appends onto the binding it was given");
        assertEquals(Map.of("Action", "search", "@SearchInput", "#ASearch #SearchField.Value"),
                carried.events());
    }

    @Test
    void carryRefusesABareKey() {
        EventData data = EventData.of("Action", "toggle");
        assertThrows(IllegalArgumentException.class,
                () -> ZigSearchRow.carry(data, "Search", "#RoleSearch"),
                "a bare key ships the path string literally; the seam must not let it through");
        assertThrows(IllegalArgumentException.class,
                () -> ZigSearchRow.carry(data, "@", "#RoleSearch"),
                "a lone '@' names nothing");
        assertEquals(Map.of("Action", "toggle"), data.events(),
                "a refused carry leaves the binding untouched");
    }

    @Test
    void theDirectiveCheckReturnsAGoodKeyUnchanged() {
        assertEquals("@Query", SettingsUiUtil.directive("@Query"));
    }

    @Test
    void theButtonWordsAreShipped() throws IOException {
        String english = Files.readString(ENGLISH, StandardCharsets.UTF_8);
        for (String key : new String[] {ZigSearchRow.SEARCH_LABEL, ZigSearchRow.CLEAR_LABEL}) {
            String entry = key.substring("ui.".length());
            assertTrue(english.lines().anyMatch(line -> line.startsWith(entry + " =")),
                    "ziggfreedcommon.ui.lang must author '" + entry
                            + "', or the button renders its key");
        }
    }
}
