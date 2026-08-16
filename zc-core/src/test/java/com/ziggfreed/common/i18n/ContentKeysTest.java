package com.ziggfreed.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The authored-key seam, exercised entirely in a unit JVM: a fill is a set of keys and a namespace,
 * so what a client would be handed is a plain string nobody needs a server to assert.
 */
class ContentKeysTest {

    /** A consumer that ships exactly the keys it was built with. */
    private record Fill(@Nonnull String prefix, @Nonnull Set<String> keys) implements ContentI18n {

        @Override
        @Nonnull
        public String keyPrefix() {
            return prefix;
        }

        @Override
        public boolean hasKey(@Nonnull String unprefixedKey) {
            return keys.contains(unprefixedKey);
        }
    }

    @BeforeEach
    @AfterEach
    void unfilled() {
        ContentKeys.reset();
    }

    @Test
    void noFill_leavesTheKeyExactlyAsAuthored() {
        assertEquals("shop.general.title", ContentKeys.resolved("shop.general.title"));
        assertFalse(ContentKeys.known("shop.general.title"));
        assertTrue(ContentKeys.installed().isEmpty());
    }

    @Test
    void aFillThatShipsTheKey_lendsItsNamespace() {
        ContentKeys.install(new Fill("mmoskilltree.", Set.of("shop.general.title")));

        assertEquals("mmoskilltree.shop.general.title", ContentKeys.resolved("shop.general.title"));
        assertTrue(ContentKeys.known("shop.general.title"));
    }

    @Test
    void aFillThatDoesNotShipTheKey_leavesItAlone() {
        ContentKeys.install(new Fill("mmoskilltree.", Set.of("shop.general.title")));

        assertEquals("board.grade.hard", ContentKeys.resolved("board.grade.hard"));
        assertFalse(ContentKeys.known("board.grade.hard"));
    }

    @Test
    void aKeyAlreadyCarryingItsNamespace_passesThroughUntouched() {
        ContentKeys.install(new Fill("mmoskilltree.", Set.of("shop.general.title")));

        assertEquals("mmoskilltree.shop.general.title",
                ContentKeys.resolved("mmoskilltree.shop.general.title"));
        assertEquals("server.items.Ore_Adamantite.name",
                ContentKeys.resolved("server.items.Ore_Adamantite.name"));
    }

    @Test
    void twoConsumers_eachKeyGoesToWhicheverShipsIt() {
        ContentKeys.install(new Fill("mmoskilltree.", Set.of("shop.general.title")));
        ContentKeys.install(new Fill("kweebecnightmare.", Set.of("shop.spooky.title")));

        assertEquals("mmoskilltree.shop.general.title", ContentKeys.resolved("shop.general.title"));
        assertEquals("kweebecnightmare.shop.spooky.title", ContentKeys.resolved("shop.spooky.title"));
    }

    @Test
    void twoConsumersShippingTheSameKey_theOneRegisteredFirstAnswers() {
        ContentKeys.install(new Fill("mmoskilltree.", Set.of("shop.general.title")));
        ContentKeys.install(new Fill("kweebecnightmare.", Set.of("shop.general.title")));

        assertEquals("mmoskilltree.shop.general.title", ContentKeys.resolved("shop.general.title"));
    }

    @Test
    void installingTheSameNamespaceTwice_registersItOnce() {
        ContentKeys.install(new Fill("mmoskilltree.", Set.of("shop.general.title")));
        ContentKeys.install(new Fill("mmoskilltree.", Set.of("board.grade.hard")));

        assertEquals(1, ContentKeys.installed().size());
        assertEquals("board.grade.hard", ContentKeys.resolved("board.grade.hard"));
    }

    @Test
    void aFillThatThrows_costsItsClaimAndNothingElse() {
        ContentKeys.install(new ContentI18n() {
            @Override
            @Nonnull
            public String keyPrefix() {
                return "broken.";
            }

            @Override
            public boolean hasKey(@Nonnull String unprefixedKey) {
                throw new IllegalStateException("catalogue is not up yet");
            }
        });
        ContentKeys.install(new Fill("mmoskilltree.", Set.of("shop.general.title")));

        assertEquals("mmoskilltree.shop.general.title", ContentKeys.resolved("shop.general.title"));
    }

    @Test
    void blankKey_isReturnedUnchanged() {
        ContentKeys.install(new Fill("mmoskilltree.", Set.of("shop.general.title")));

        assertEquals("", ContentKeys.resolved(""));
        assertEquals("   ", ContentKeys.resolved("   "));
    }

    @Test
    void pick_prefersTheExplicitKeyWhenTheConsumerShipsIt() {
        Fill fill = new Fill("mmoskilltree.", Set.of("dialogue.hub.custom", "dialogue.hub.node.1"));

        assertEquals("dialogue.hub.custom",
                ContentKeys.pick(fill, "dialogue.hub.custom", "dialogue.hub.node.1"));
    }

    @Test
    void pick_fallsToTheConventionKeyWhenTheExplicitOneIsNotShipped() {
        Fill fill = new Fill("mmoskilltree.", Set.of("dialogue.hub.node.1"));

        assertEquals("dialogue.hub.node.1",
                ContentKeys.pick(fill, "dialogue.hub.custom", "dialogue.hub.node.1"));
        assertEquals("dialogue.hub.node.1", ContentKeys.pick(fill, null, "dialogue.hub.node.1"));
    }

    @Test
    void pick_answersNullWhenNeitherKeyIsShipped() {
        Fill fill = new Fill("mmoskilltree.", Set.of());

        assertNull(ContentKeys.pick(fill, "dialogue.hub.custom", "dialogue.hub.node.1"));
        assertNull(ContentKeys.pick(fill, null, null));
        assertNull(ContentKeys.pick(fill, "", ""));
    }

    @Test
    void pick_asksOnlyTheFillItWasGiven() {
        ContentKeys.install(new Fill("other.", Set.of("dialogue.hub.custom")));
        Fill mine = new Fill("mmoskilltree.", Set.of());

        assertNull(ContentKeys.pick(mine, "dialogue.hub.custom", null));
    }
}
