package com.ziggfreed.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;

/**
 * Tests for the parts of this class a unit JVM can reach: {@link NativeNames#prettify}, and the
 * key ladders through their probe-injectable overloads, driven here by a plain set of keys instead
 * of a live {@code I18nModule}.
 *
 * <p>The rung ABOVE those ladders, where the item asset names itself, is not reachable here: it
 * reads the engine's item asset store, which no unit JVM stands up, so it reads as "no item" and
 * every case below falls through to the key conventions. Matching this codebase's established
 * discipline for engine-touching resolution, that rung is verified against a running server.
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

    /** A probe that answers for exactly the keys named, so a ladder's order is observable. */
    private static Predicate<String> catalogOf(String... keys) {
        return Set.of(keys)::contains;
    }

    @Test
    void itemNameMsg_prefersTheNativeItemNamespace() {
        Message m = NativeNames.itemNameMsg("Ore_Adamantite",
                catalogOf("server.items.Ore_Adamantite.name", "items.Ore_Adamantite.name"));
        assertEquals("server.items.Ore_Adamantite.name", m.getMessageId());
    }

    @Test
    void itemNameMsg_fallsToTheModItemNamespaceWhenTheNativeOneIsAbsent() {
        Message m = NativeNames.itemNameMsg("Ore_Adamantite", catalogOf("items.Ore_Adamantite.name"));
        assertEquals("items.Ore_Adamantite.name", m.getMessageId());
    }

    @Test
    void itemNameMsg_prettifiesWhenNoKeyResolves() {
        Message m = NativeNames.itemNameMsg("Ore_Adamantite", catalogOf());
        assertNull(m.getMessageId());
        assertEquals("Ore Adamantite", m.getRawText());
    }

    @Test
    void itemNameMsg_blankIdReadsAsEmptyText() {
        Message m = NativeNames.itemNameMsg("  ", catalogOf("server.items.  .name"));
        assertEquals("", m.getRawText());
    }

    @Test
    void targetNameMsg_triesItemFamiliesBeforeCharacterOnes() {
        Message m = NativeNames.targetNameMsg("Trork_Chieftain",
                catalogOf("items.Trork_Chieftain.name", "server.npcRoles.Trork_Chieftain.name"));
        assertEquals("items.Trork_Chieftain.name", m.getMessageId());
    }

    @Test
    void targetNameMsg_reachesTheCharacterFamiliesWhenNoItemNamesTheId() {
        Message m = NativeNames.targetNameMsg("Trork_Chieftain",
                catalogOf("npcs.Trork_Chieftain.name"));
        assertEquals("npcs.Trork_Chieftain.name", m.getMessageId());
    }
}
