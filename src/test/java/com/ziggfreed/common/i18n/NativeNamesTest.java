package com.ziggfreed.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pure tests for {@link NativeNames#prettify} - the only unit-JVM-safe logic in this class
 * (the two-tier {@code server.items}/{@code items} probe needs a live {@code I18nModule}, so
 * {@link NativeNames#itemNameMsg} is exercised only against a running server, matching this
 * codebase's established discipline for engine-touching resolution: unit-test the pure core,
 * leave the live-catalog probe for in-game verification).
 */
class NativeNamesTest {

    @Test
    void prettify_underscoresBecomeSpacedTitleCase() {
        assertEquals("Ore Adamantite", NativeNames.prettify("Ore_Adamantite"));
    }

    @Test
    void prettify_preservesExistingIntraWordCasing() {
        assertEquals("Trork Chieftain", NativeNames.prettify("Trork_Chieftain"));
    }

    @Test
    void prettify_singleLowercaseWord_capitalizesFirstLetterOnly() {
        assertEquals("Zombie", NativeNames.prettify("zombie"));
    }

    @Test
    void prettify_nullOrEmpty_returnsEmpty() {
        assertEquals("", NativeNames.prettify(null));
        assertEquals("", NativeNames.prettify(""));
    }
}
