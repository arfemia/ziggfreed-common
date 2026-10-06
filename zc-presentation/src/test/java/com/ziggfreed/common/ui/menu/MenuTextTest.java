package com.ziggfreed.common.ui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Every line {@code /ziggui} says ships in English, since a missing key renders as the key. */
class MenuTextTest {

    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.ui.lang");

    @Test
    void theCommandsDescriptionIsAKey() {
        assertEquals("ziggfreedcommon.ui.menu.desc", MenuText.desc());
    }

    @Test
    void everyLineTheCommandSaysIsShipped() throws IOException {
        String english = Files.readString(ENGLISH, StandardCharsets.UTF_8);
        for (String key : new String[] {"menu.desc", MenuText.NEEDS_PLAYER, MenuText.NOTHING}) {
            assertTrue(english.lines().anyMatch(line -> line.startsWith(key + " =")),
                    "ziggfreedcommon.ui.lang must author '" + key + "'");
        }
    }
}
