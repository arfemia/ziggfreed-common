package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.commerce.InMemoryCommerceStore;
import com.ziggfreed.common.currency.CurrencyCatalog;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.i18n.LangCatalog;

/**
 * How much of a wallet reads as one line. A wallet that ships its own amount line
 * ({@code currency.<id>.amount}) is read through it, so its name takes its plural in every language;
 * one that ships none falls back to the shared amount-beside-name reading. Either way the amount is a
 * typed number, never digits spelt on the server.
 */
class CurrencyTextAmountTest {

    private static final CurrencyDef SWEETS = CurrencyDef.builder("Hallows_Eve_Sweets")
            .nameKey("currency.hallows_eve_sweets.name").build();

    @BeforeEach
    @AfterEach
    void reset() {
        LangCatalog.overrideForTests(null);
    }

    @Test
    void aWalletsOwnAmountLineCarriesTheTypedAmountAlone() {
        LangCatalog.overrideForTests(Map.of(
                "hallowseve.progression.currency.hallows_eve_sweets.name", "Hallow Sweets",
                "hallowseve.progression.currency.hallows_eve_sweets.amount",
                "{0, number} {0, plural, one {Hallow Sweet} other {Hallow Sweets}}"));

        FormattedMessage fm = CurrencyText.amountOf(SWEETS, 6L, null).getFormattedMessage();

        assertEquals("hallowseve.progression.currency.hallows_eve_sweets.amount", fm.messageId,
                "the line is found under whichever namespace ships it, as the name key is");
        assertNotNull(fm.params);
        assertEquals(6L, ((LongParamValue) fm.params.get("0")).value, "the amount is typed");
        assertTrue(fm.messageParams == null || fm.messageParams.isEmpty(),
                "the line writes the name itself, plural and all");
    }

    @Test
    void withNoAmountLineTheAmountSitsBesideTheWalletsName() {
        LangCatalog.overrideForTests(Map.of(
                "hallowseve.progression.currency.hallows_eve_sweets.name", "Hallow Sweets"));

        FormattedMessage fm = CurrencyText.amountOf(SWEETS, 1234L, null).getFormattedMessage();

        assertEquals("ziggfreedcommon.commerce.price.amount_and_name", fm.messageId);
        assertEquals(1234L, ((LongParamValue) fm.params.get("0")).value);
        assertEquals("hallowseve.progression.currency.hallows_eve_sweets.name", fm.messageParams.get("1").messageId,
                "the name nests as the wallet's own translated name");
    }

    @Test
    void aConsumersNameIsWhatTheFallbackReads() {
        CurrencyText.Source mine = def -> Message.raw("Sweets of the Hollow");

        FormattedMessage fm = CurrencyText.amountOf(SWEETS, 3L, mine).getFormattedMessage();

        assertEquals("ziggfreedcommon.commerce.price.amount_and_name", fm.messageId);
        assertEquals("Sweets of the Hollow", fm.messageParams.get("1").rawText);
    }

    @Test
    void aConfirmLineReadsAPriceTheWayItsChipDoes() {
        LangCatalog.overrideForTests(Map.of(
                "hallowseve.progression.currency.hallows_eve_sweets.amount",
                "{0, number} {0, plural, one {Hallow Sweet} other {Hallow Sweets}}"));
        CurrencyEngine currencies = CurrencyEngine.builder()
                .catalog(CurrencyCatalog.of(List.of(SWEETS)))
                .store(new InMemoryCommerceStore())
                .build();

        assertEquals("hallowseve.progression.currency.hallows_eve_sweets.amount",
                CommerceChips.amountAndName(currencies, "hallows_eve_sweets", 6L, null).getFormattedMessage().messageId);
        assertEquals("ziggfreedcommon.commerce.price.amount_and_name",
                CommerceChips.amountAndName(currencies, "No_Such_Wallet", 6L, null).getFormattedMessage().messageId,
                "a wallet nothing defines still reads as its amount beside its id");
    }

    @Test
    void theAmountKeyUsesTheSameLowerCasedIdAsTheNameKey() {
        assertEquals("currency.hallows_eve_sweets.amount", CurrencyText.amountKey(" Hallows_Eve_Sweets "));
    }
}
