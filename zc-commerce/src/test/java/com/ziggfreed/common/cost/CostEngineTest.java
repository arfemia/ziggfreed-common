package com.ziggfreed.common.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.commerce.InMemoryCommerceStore;
import com.ziggfreed.common.currency.CurrencyCatalog;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.currency.ItemWallet;
import com.ziggfreed.common.subject.Subject;

/** Charging a price: what it takes, what it gives back, and what it must never half-do. */
class CostEngineTest {

    private static final Subject SUBJECT = Subject.of(UUID.randomUUID(), "Tester");

    private InMemoryCommerceStore store;
    private MapWallet wallet;
    private CurrencyEngine currencies;
    private CostEngine costs;

    /** An item wallet that can be told to refuse a particular take, to force a mid-drain failure. */
    private static final class MapWallet implements ItemWallet {
        final Map<String, Long> held = new LinkedHashMap<>();
        String refuseTakeOf = "";

        @Override
        public long count(@Nonnull Subject subject, @Nonnull String itemId) {
            return held.getOrDefault(itemId, 0L);
        }

        @Override
        public boolean take(@Nonnull Subject subject, @Nonnull String itemId, long amount) {
            if (itemId.equals(refuseTakeOf)) {
                return false;
            }
            long current = held.getOrDefault(itemId, 0L);
            if (current < amount) {
                return false;
            }
            held.put(itemId, current - amount);
            return true;
        }

        @Override
        public long give(@Nonnull Subject subject, @Nonnull String itemId, long amount) {
            held.merge(itemId, amount, Long::sum);
            return amount;
        }
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryCommerceStore();
        wallet = new MapWallet();
        currencies = CurrencyEngine.builder()
                .catalog(CurrencyCatalog.of(List.of(
                        CurrencyDef.builder("Bounty_Token").build(),
                        CurrencyDef.builder("Life_Essence").build())))
                .items(wallet)
                .store(store)
                .warn(msg -> { })
                .build();
        costs = CostEngine.builder(currencies).items(wallet).warn(msg -> { }).build();
    }

    private Cost twoCurrencies(long tokens, long essence) {
        Map<String, Long> owed = new LinkedHashMap<>();
        owed.put("Bounty_Token", tokens);
        owed.put("Life_Essence", essence);
        return Cost.of(Cost.Combine.ALL, owed, null, null);
    }

    @Test
    @DisplayName("a free price always passes and its receipt gives nothing back")
    void freeIsAlwaysPayable() {
        assertTrue(costs.check(SUBJECT, Cost.FREE).ok());
        CostEngine.Receipt receipt = costs.drain(SUBJECT, Cost.FREE);
        assertTrue(receipt.ok());
        assertTrue(receipt.paid().isFree());
    }

    @Test
    @DisplayName("a check names the FIRST thing the payer is short of")
    void checkNamesTheFirstShortfall() {
        currencies.credit(SUBJECT, "Bounty_Token", 10);
        CostEngine.Affordability afford = costs.check(SUBJECT, twoCurrencies(50, 5));
        assertFalse(afford.ok());
        assertEquals("Bounty_Token", afford.shortCurrencyId());
        assertNull(afford.shortItemId());
    }

    @Test
    @DisplayName("an All drain takes everything and the receipt is the whole price")
    void allTakesEverything() {
        currencies.credit(SUBJECT, "Bounty_Token", 100);
        currencies.credit(SUBJECT, "Life_Essence", 100);

        CostEngine.Receipt receipt = costs.drain(SUBJECT, twoCurrencies(60, 40));
        assertTrue(receipt.ok());
        assertEquals(60L, receipt.paid().amountOf("Bounty_Token"));
        assertEquals(40L, receipt.paid().amountOf("Life_Essence"));
        assertEquals(40L, currencies.balance(SUBJECT, "Bounty_Token"));
        assertEquals(60L, currencies.balance(SUBJECT, "Life_Essence"));
    }

    @Test
    @DisplayName("an All price short on its SECOND currency charges nothing at all")
    void allNeverHalfCharges() {
        currencies.credit(SUBJECT, "Bounty_Token", 100);
        currencies.credit(SUBJECT, "Life_Essence", 5);

        CostEngine.Receipt receipt = costs.drain(SUBJECT, twoCurrencies(60, 40));
        assertFalse(receipt.ok());
        assertEquals(100L, currencies.balance(SUBJECT, "Bounty_Token"), "the first take rolled back");
        assertEquals(5L, currencies.balance(SUBJECT, "Life_Essence"));
    }

    @Test
    @DisplayName("an item that vanishes after the currencies were taken gets them refunded")
    void vanishedItemsRefundTheCurrencies() {
        currencies.credit(SUBJECT, "Bounty_Token", 100);
        wallet.held.put("Ore_Iron", 10L);
        Cost price = Cost.of(Cost.Combine.ALL, Map.of("Bounty_Token", 25L),
                List.of(ItemCost.of("Ore_Iron", 4)), null);

        wallet.refuseTakeOf = "Ore_Iron";
        CostEngine.Receipt receipt = costs.drain(SUBJECT, price);

        assertFalse(receipt.ok());
        assertEquals(100L, currencies.balance(SUBJECT, "Bounty_Token"));
        assertEquals(10L, wallet.held.get("Ore_Iron"));
    }

    @Test
    @DisplayName("an Any drain charges exactly ONE component, and the receipt is only that one")
    void anyChargesOneComponentAndSaysWhich() {
        currencies.credit(SUBJECT, "Life_Essence", 100);
        Map<String, Long> owed = new LinkedHashMap<>();
        owed.put("Bounty_Token", 50L);
        owed.put("Life_Essence", 30L);
        Cost either = Cost.of(Cost.Combine.ANY, owed, null, null);

        CostEngine.Receipt receipt = costs.drain(SUBJECT, either);
        assertTrue(receipt.ok());
        assertEquals(1, receipt.paid().componentCount(), "only the component actually paid");
        assertEquals(30L, receipt.paid().amountOf("Life_Essence"));
        assertEquals(0L, receipt.paid().amountOf("Bounty_Token"));
        assertEquals(70L, currencies.balance(SUBJECT, "Life_Essence"));
    }

    @Test
    @DisplayName("refunding an Any RECEIPT gives back only what was paid, never the whole price")
    void refundingTheReceiptNotThePrice() {
        currencies.credit(SUBJECT, "Life_Essence", 100);
        Map<String, Long> owed = new LinkedHashMap<>();
        owed.put("Bounty_Token", 50L);
        owed.put("Life_Essence", 30L);
        Cost either = Cost.of(Cost.Combine.ANY, owed, null, null);

        CostEngine.Receipt receipt = costs.drain(SUBJECT, either);
        costs.refund(SUBJECT, receipt);

        assertEquals(100L, currencies.balance(SUBJECT, "Life_Essence"));
        assertEquals(0L, currencies.balance(SUBJECT, "Bounty_Token"), "never paid, so never refunded");
    }

    @Test
    @DisplayName("an Any price nobody can meet reports the first component and charges nothing")
    void anyUnpayableReportsTheFirst() {
        Map<String, Long> owed = new LinkedHashMap<>();
        owed.put("Bounty_Token", 50L);
        owed.put("Life_Essence", 30L);
        CostEngine.Affordability afford = costs.check(SUBJECT, Cost.of(Cost.Combine.ANY, owed, null, null));
        assertFalse(afford.ok());
        assertEquals("Bounty_Token", afford.shortCurrencyId());
    }

    @Test
    @DisplayName("a refund puts items back as well as currencies")
    void refundReturnsItemsToo() {
        currencies.credit(SUBJECT, "Bounty_Token", 100);
        wallet.held.put("Ore_Iron", 10L);
        Cost price = Cost.of(Cost.Combine.ALL, Map.of("Bounty_Token", 25L),
                List.of(ItemCost.of("Ore_Iron", 4)), null);

        CostEngine.Receipt receipt = costs.drain(SUBJECT, price);
        assertTrue(receipt.ok());
        assertEquals(6L, wallet.held.get("Ore_Iron"));

        costs.refund(SUBJECT, receipt);
        assertEquals(100L, currencies.balance(SUBJECT, "Bounty_Token"));
        assertEquals(10L, wallet.held.get("Ore_Iron"));
    }

    @Test
    @DisplayName("a shortfall listing names every missing component, not only the first")
    void shortfallListsEverythingMissing() {
        currencies.credit(SUBJECT, "Bounty_Token", 10);
        Map<String, Long> missing = costs.shortfall(SUBJECT, twoCurrencies(50, 30));
        assertEquals(40L, missing.get("Bounty_Token"));
        assertEquals(30L, missing.get("Life_Essence"));
    }

    @Test
    @DisplayName("scaling grows the price per prior purchase and leaves the authored one alone")
    void scalingIsAppliedToACopy() {
        CostScaling curve = CostScaling.of(CostScaling.Curve.EXPONENTIAL, 2.0, 0);
        Cost authored = Cost.of(Cost.Combine.ALL, Map.of("Bounty_Token", 100L), null, curve);

        assertEquals(100L, authored.scaled(0).amountOf("Bounty_Token"));
        assertEquals(200L, authored.scaled(1).amountOf("Bounty_Token"));
        assertEquals(400L, authored.scaled(2).amountOf("Bounty_Token"));
        assertEquals(100L, authored.amountOf("Bounty_Token"), "the authored price never changes");
    }

    @Test
    @DisplayName("a soft cap freezes the curve rather than letting it run away")
    void softCapFreezesTheCurve() {
        CostScaling curve = CostScaling.of(CostScaling.Curve.EXPONENTIAL, 2.0, 3);
        assertEquals(800L, CostScaling.scaled(100, 3, curve));
        assertEquals(800L, CostScaling.scaled(100, 50, curve));
    }

    @Test
    @DisplayName("the polynomial curve flattens where the exponential one does not")
    void polynomialFlattens() {
        CostScaling curve = CostScaling.of(CostScaling.Curve.POLYNOMIAL, 2.0, 0);
        assertEquals(100L, CostScaling.scaled(100, 0, curve));
        assertEquals(400L, CostScaling.scaled(100, 1, curve));
        assertEquals(900L, CostScaling.scaled(100, 2, curve));
    }

    @Test
    @DisplayName("blank and non-positive components are dropped rather than charged")
    void blankComponentsAreDropped() {
        Map<String, Long> owed = new LinkedHashMap<>();
        owed.put("Bounty_Token", 0L);
        owed.put("", 50L);
        Cost price = Cost.of(Cost.Combine.ALL, owed, List.of(ItemCost.of("", 3)), null);
        assertTrue(price.isFree());
    }
}
