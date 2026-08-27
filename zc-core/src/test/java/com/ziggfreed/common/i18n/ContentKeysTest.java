package com.ziggfreed.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The authored-key seam, exercised entirely in a unit JVM: the loaded catalogue is pinned to a plain
 * map of full registered ids ({@link LangCatalog#overrideForTests}), so what a client would be
 * handed is a string nobody needs a server to assert.
 */
class ContentKeysTest {

    /** A loaded catalogue shipping exactly these full ids (the value text is never read here). */
    @Nonnull
    private static Map<String, String> catalogue(@Nonnull String... fullIds) {
        Map<String, String> loaded = new LinkedHashMap<>();
        for (String id : fullIds) {
            loaded.put(id, "text of " + id);
        }
        return loaded;
    }

    @BeforeEach
    @AfterEach
    void realCatalogue() {
        LangCatalog.overrideForTests(null);
    }

    @Test
    void noCatalogue_leavesTheKeyExactlyAsAuthoredAndNeverThrows() {
        assertEquals("shop.general.title", ContentKeys.resolved("shop.general.title"));
        assertFalse(ContentKeys.known("shop.general.title"));

        LangCatalog.overrideForTests(Map.of());
        assertEquals("shop.general.title", ContentKeys.resolved("shop.general.title"));
        assertFalse(ContentKeys.known("shop.general.title"));
    }

    @Test
    void aLoadedNamespaceThatShipsTheKey_lendsItsNamespace() {
        LangCatalog.overrideForTests(catalogue("mmoskilltree.shop.general.title"));

        assertEquals("mmoskilltree.shop.general.title", ContentKeys.resolved("shop.general.title"));
        assertTrue(ContentKeys.known("shop.general.title"));
    }

    @Test
    void aKeyNoLoadedFileShips_isLeftAlone() {
        LangCatalog.overrideForTests(catalogue("mmoskilltree.shop.general.title"));

        assertEquals("board.grade.hard", ContentKeys.resolved("board.grade.hard"));
        assertFalse(ContentKeys.known("board.grade.hard"));
    }

    @Test
    void aKeyTheCatalogueCarriesExactly_passesThroughUntouchedAndOutranksTheNamespaceProbe() {
        LangCatalog.overrideForTests(catalogue(
                "mmoskilltree.shop.general.title",
                "server.items.Ore_Adamantite.name",
                // A longer id this suffix would also match; the exact hit must win before any scan.
                "somepack.server.items.Ore_Adamantite.name"));

        assertEquals("mmoskilltree.shop.general.title",
                ContentKeys.resolved("mmoskilltree.shop.general.title"));
        assertEquals("server.items.Ore_Adamantite.name",
                ContentKeys.resolved("server.items.Ore_Adamantite.name"));
        assertTrue(ContentKeys.known("server.items.Ore_Adamantite.name"));
    }

    @Test
    void twoNamespaces_eachKeyGoesToWhicheverShipsIt() {
        LangCatalog.overrideForTests(catalogue(
                "mmoskilltree.shop.general.title", "kweebecnightmare.shop.spooky.title"));

        assertEquals("mmoskilltree.shop.general.title", ContentKeys.resolved("shop.general.title"));
        assertEquals("kweebecnightmare.shop.spooky.title",
                ContentKeys.resolved("shop.spooky.title"));
    }

    @Test
    void twoConsumersShippingTheSameKey_theLexicographicallySmallestIdAnswers() {
        LangCatalog.overrideForTests(catalogue(
                "mmoskilltree.shop.general.title", "kweebecnightmare.shop.general.title"));

        assertEquals("kweebecnightmare.shop.general.title",
                ContentKeys.resolved("shop.general.title"));
    }

    /**
     * A consumer's word beats this library's own shipped default, which is what lets a mod reword a
     * heading the library also ships without touching the library.
     */
    @Test
    void aConsumerWord_outranksTheLibrarysOwnDefault() {
        LangCatalog.overrideForTests(catalogue(
                "ziggfreedcommon.shop.general.title", "mmoskilltree.shop.general.title"));

        assertEquals("mmoskilltree.shop.general.title",
                ContentKeys.resolved("shop.general.title"));
    }

    /**
     * And it beats it whatever the consumer is CALLED. Ranking by plain alphabetical order would
     * hand this one to the library, because "zonequests." sorts after "ziggfreedcommon." - the bug
     * this ordering exists to prevent.
     */
    @Test
    void aConsumerSortingAfterTheLibrary_stillOutranksIt() {
        LangCatalog.overrideForTests(catalogue(
                "ziggfreedcommon.shop.general.title", "zonequests.shop.general.title"));

        assertEquals("zonequests.shop.general.title",
                ContentKeys.resolved("shop.general.title"));
    }

    /** With nobody but the library shipping it, the library's own word is the answer. */
    @Test
    void onlyTheLibraryShipsIt_theLibraryAnswers() {
        LangCatalog.overrideForTests(catalogue("ziggfreedcommon.shop.general.title"));

        assertEquals("ziggfreedcommon.shop.general.title",
                ContentKeys.resolved("shop.general.title"));
    }

    /** The winner depends on the ids alone, never on the order the server loaded them in. */
    @Test
    void theSameCatalogueInAnyLoadOrder_resolvesTheSameWay() {
        LangCatalog.overrideForTests(catalogue(
                "aaa.board.grade.hard", "mmm.board.grade.hard", "zzz.board.grade.hard"));
        assertEquals("aaa.board.grade.hard", ContentKeys.resolved("board.grade.hard"));

        LangCatalog.overrideForTests(catalogue(
                "zzz.board.grade.hard", "mmm.board.grade.hard", "aaa.board.grade.hard"));
        assertEquals("aaa.board.grade.hard", ContentKeys.resolved("board.grade.hard"));
    }

    /**
     * The flattened catalogue keeps no record of where a lang file's namespace ends, so a key that
     * is a dotted TAIL of a longer loaded id still finds it - the id exists and the client resolves
     * it, which is why owner-prefixing stays the answer for a key that must not be shared.
     */
    @Test
    void aKeyThatIsATailOfALongerId_stillResolvesToThatId() {
        LangCatalog.overrideForTests(catalogue("mmoskilltree.ui.shop.general.title"));

        assertEquals("mmoskilltree.ui.shop.general.title",
                ContentKeys.resolved("shop.general.title"));
    }

    /** A reload that changes the loaded catalogue changes the answer; nothing pins the first one. */
    @Test
    void aNewCatalogue_isConsultedFresh() {
        LangCatalog.overrideForTests(catalogue("mmoskilltree.other.key"));
        assertFalse(ContentKeys.known("shop.general.title"));

        LangCatalog.overrideForTests(catalogue("mmoskilltree.shop.general.title"));
        assertTrue(ContentKeys.known("shop.general.title"));
        assertEquals("mmoskilltree.shop.general.title", ContentKeys.resolved("shop.general.title"));
    }

    @Test
    void blankKey_isReturnedUnchanged() {
        LangCatalog.overrideForTests(catalogue("mmoskilltree.shop.general.title"));

        assertEquals("", ContentKeys.resolved(""));
        assertEquals("   ", ContentKeys.resolved("   "));
        assertFalse(ContentKeys.known("   "));
    }

    @Test
    void tr_emitsTheResolvedIdAsTheMessageId() {
        LangCatalog.overrideForTests(catalogue("mmoskilltree.shop.general.title"));

        assertEquals("mmoskilltree.shop.general.title",
                ContentKeys.tr("shop.general.title").getMessageId());
        assertEquals("board.grade.hard", ContentKeys.tr("board.grade.hard").getMessageId(),
                "an unclaimed key still goes out traceably, as itself");
    }

    @Test
    void pick_prefersTheExplicitKeyWhenTheCatalogueShipsIt() {
        LangCatalog.overrideForTests(catalogue(
                "mmoskilltree.dialogue.hub.custom", "mmoskilltree.dialogue.hub.node.1"));

        assertEquals("dialogue.hub.custom",
                ContentKeys.pick("dialogue.hub.custom", "dialogue.hub.node.1"));
    }

    @Test
    void pick_fallsToTheConventionKeyWhenTheExplicitOneIsNotShipped() {
        LangCatalog.overrideForTests(catalogue("mmoskilltree.dialogue.hub.node.1"));

        assertEquals("dialogue.hub.node.1",
                ContentKeys.pick("dialogue.hub.custom", "dialogue.hub.node.1"));
        assertEquals("dialogue.hub.node.1", ContentKeys.pick(null, "dialogue.hub.node.1"));
    }

    @Test
    void pick_answersNullWhenNeitherKeyIsShipped() {
        LangCatalog.overrideForTests(Map.of());

        assertNull(ContentKeys.pick("dialogue.hub.custom", "dialogue.hub.node.1"));
        assertNull(ContentKeys.pick(null, null));
        assertNull(ContentKeys.pick("", ""));
    }
}
