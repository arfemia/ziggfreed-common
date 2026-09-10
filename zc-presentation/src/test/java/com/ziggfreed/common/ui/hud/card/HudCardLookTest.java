package com.ziggfreed.common.ui.hud.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The one hex a card is coloured by: it reads in every spelling an author might type and in no
 * other, the identity pushes nothing whichever way it was spelled, the card's own leaf sits over
 * the shared record over the shipped look, a malformed value is ignored rather than crashing or
 * blackening a card, and the dressing rule keeps a shipped colour's hue while scaling its alpha by
 * the card's opacity. Every number below is the test's own.
 */
class HudCardLookTest {

    @AfterEach
    void forgetWarnings() {
        HudCardLook.resetWarnings();
    }

    // ==================== parsing ====================

    @Test
    void sixAndEightDigitsReadWithOrWithoutTheHashInEitherCase() {
        HudCardLook six = HudCardLook.parse("#6fbf73");
        assertNotNull(six);
        assertEquals("#6fbf73", six.hex());
        assertEquals(255, six.alpha(), "six digits is fully opaque");
        assertEquals(1.0, six.opacity());

        assertEquals("#6fbf73", HudCardLook.parse("6FBF73").hex(), "no hash, upper case: the same colour");
        assertEquals("#6fbf73", HudCardLook.parse("  #6fbf73  ").hex(), "surrounding space is ignored");

        HudCardLook eight = HudCardLook.parse("#ffffffb8");
        assertNotNull(eight);
        assertEquals("#ffffffb8", eight.hex());
        assertEquals(0xb8, eight.alpha(), "the last two digits are the alpha");
        assertEquals(0xb8 / 255.0, eight.opacity());
        assertEquals("#ffffffb8", HudCardLook.parse("ffffffB8").hex());
    }

    @Test
    void anythingElseReadsAsNothing() {
        assertNull(HudCardLook.parse(null));
        assertNull(HudCardLook.parse(""));
        assertNull(HudCardLook.parse("   "));
        assertNull(HudCardLook.parse("#fff"), "a short form is not one of the two spellings");
        assertNull(HudCardLook.parse("#12345"));
        assertNull(HudCardLook.parse("#1234567"));
        assertNull(HudCardLook.parse("#123456789"));
        assertNull(HudCardLook.parse("#gggggg"), "not hex digits");
        assertNull(HudCardLook.parse("rgb(1, 2, 3)"), "one hex, no other grammar");
    }

    // ==================== the identity ====================

    @Test
    void theIdentityPushesNothingWhicheverWayItWasSpelled() {
        for (String spelling : new String[] {"#ffffffff", "#ffffff", "FFFFFF", "ffffffff", " #FfFfFf "}) {
            HudCardLook look = HudCardLook.of(spelling);
            assertTrue(look.isIdentity(), spelling);
            assertNull(look.cardColor(), spelling + " is the shipped look and costs no command");
            assertEquals(1.0, look.opacity(), spelling);
        }
        assertSame(HudCardLook.SHIPPED, HudCardLook.of(null), "nothing authored is the shipped look");
        assertTrue(HudCardLook.SHIPPED.isIdentity());
        assertEquals(HudCardLook.IDENTITY_HEX, HudCardLook.SHIPPED.hex());
    }

    @Test
    void anythingOffTheIdentityIsPushedAsAuthored() {
        assertEquals("#fffffffe", HudCardLook.of("#fffffffe").cardColor(), "one step of alpha is not the identity");
        assertEquals("#fffffeff", HudCardLook.of("#fffffeff").cardColor(), "one step of blue is not the identity");
        assertEquals("#ffffffb8", HudCardLook.of("#ffffffb8").cardColor(), "the pure transparency case");
        assertEquals("#6fbf73", HudCardLook.of("#6fbf73").cardColor(), "a tint, fully opaque");
        assertFalse(HudCardLook.of("#6fbf73").isIdentity());
    }

    // ==================== the fold ====================

    @Test
    void theCardsOwnLeafSitsOverTheSharedRecordOverTheShippedLook() {
        assertEquals("#112233", HudCardLook.resolve("#112233", "#445566").cardColor(), "own over shared");
        assertEquals("#445566", HudCardLook.resolve(null, "#445566").cardColor(), "shared when the card states none");
        assertSame(HudCardLook.SHIPPED, HudCardLook.resolve(null, null), "an absent record is the shipped look");
        assertNull(HudCardLook.resolve(null, null).cardColor(), "and pushes nothing");
        assertSame(HudCardLook.SHIPPED, HudCardLook.of("#zz"), "a value that does not parse never blackens a card");
    }

    @Test
    void anAuthoredValueIsAcceptedNormalisedOrIgnored() {
        assertNull(HudCardLook.authored(null, "a file"), "nothing authored");
        assertNull(HudCardLook.authored("   ", "a file"), "a blank is nothing authored");
        assertEquals("#aabbcc", HudCardLook.authored(" #AABBCC ", "a file"), "accepted and normalised");
        assertEquals("#aabbcc80", HudCardLook.authored("aabbcc80", "a file"));
        assertNull(HudCardLook.authored("#not-a-colour", "a file"), "ignored, so the layer below decides");
        assertNull(HudCardLook.authored("#not-a-colour", "a file"), "and ignored again, the warning having been written once");
    }

    // ==================== the derivation ====================

    @Test
    void dimmingKeepsTheHueAndMultipliesTheShippedAlphaByTheOpacity() {
        assertEquals("#0a0b0c40", HudCardLook.dimmed("#0a0b0c", 0.5, 0.5), "0.5 x 0.5 x 255 is 64");
        assertEquals("#0a0b0c80", HudCardLook.dimmed("#0A0B0C", 0.5, 1.0), "full opacity keeps the shipped alpha");
        assertEquals("#0a0b0cff", HudCardLook.dimmed("#0a0b0c", 1.0, 1.0));
        assertEquals("#0a0b0c00", HudCardLook.dimmed("#0a0b0c", 0.5, 0.0), "a fully transparent card leaves nothing");
        assertEquals("#0a0b0c", HudCardLook.dimmed("#0a0b0c", 0.5, 0.5).substring(0, 7), "the hue is untouched");
    }

    @Test
    void aShippedDressingColourIsSixDigitsOrAProgrammingError() {
        assertThrows(IllegalArgumentException.class, () -> HudCardLook.dimmed("#0a0b0c80", 0.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> HudCardLook.dimmed("well", 0.5, 0.5));
    }
}
