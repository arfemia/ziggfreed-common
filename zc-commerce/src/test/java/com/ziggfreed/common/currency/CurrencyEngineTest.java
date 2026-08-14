package com.ziggfreed.common.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.commerce.InMemoryCommerceStore;
import com.ziggfreed.common.subject.Subject;

/** The item-or-counter dispatch, the caps, and what an earn is and is not. */
class CurrencyEngineTest {

    private static final Subject SUBJECT = Subject.of(UUID.randomUUID(), "Tester");

    private InMemoryCommerceStore store;
    private FakeItemWallet wallet;
    private List<String> earns;
    private List<String> spends;
    private CurrencyEngine engine;

    @BeforeEach
    void setUp() {
        store = new InMemoryCommerceStore();
        wallet = new FakeItemWallet();
        earns = new ArrayList<>();
        spends = new ArrayList<>();

        CurrencyCatalog catalog = CurrencyCatalog.of(List.of(
                CurrencyDef.builder("Bounty_Token").build(),
                CurrencyDef.builder("Capped_Token").cap(100).build(),
                CurrencyDef.builder("Life_Essence").backingItem("Ingredient_Life_Essence").build(),
                CurrencyDef.builder("Fading").decayPerDayPercent(0.5).build(),
                CurrencyDef.builder("Risky").lossOnDeathPercent(0.25).build()));

        engine = CurrencyEngine.builder()
                .catalog(catalog)
                .items(wallet)
                .store(store)
                .observer(new CurrencyObserver() {
                    @Override
                    public void earned(@Nonnull Subject subject, @Nonnull CurrencyDef currency,
                            long amount, long balance) {
                        earns.add(currency.id() + "+" + amount);
                    }

                    @Override
                    public void spent(@Nonnull Subject subject, @Nonnull CurrencyDef currency, long amount) {
                        spends.add(currency.id() + "-" + amount);
                    }
                })
                .warn(msg -> { })
                .build();
    }

    @Test
    @DisplayName("a counter currency reads and writes the commerce store")
    void counterBackedUsesTheStore() {
        assertEquals(0L, engine.balance(SUBJECT, "Bounty_Token"));
        assertEquals(40L, engine.credit(SUBJECT, "Bounty_Token", 40));
        assertEquals(40L, store.balance(SUBJECT, "Bounty_Token"));
        assertTrue(engine.debit(SUBJECT, "Bounty_Token", 15));
        assertEquals(25L, engine.balance(SUBJECT, "Bounty_Token"));
    }

    @Test
    @DisplayName("an item currency reads and writes the inventory, and the store never sees it")
    void itemBackedUsesTheWallet() {
        wallet.put("Ingredient_Life_Essence", 12);
        assertEquals(12L, engine.balance(SUBJECT, "Life_Essence"));
        assertTrue(engine.debit(SUBJECT, "Life_Essence", 5));
        assertEquals(7L, engine.balance(SUBJECT, "Life_Essence"));
        assertEquals(0L, store.balance(SUBJECT, "Life_Essence"));
    }

    @Test
    @DisplayName("ids match case-insensitively, because a filename and a reference need not agree")
    void idsMatchCaseInsensitively() {
        engine.credit(SUBJECT, "bounty_token", 10);
        assertEquals(10L, engine.balance(SUBJECT, "BOUNTY_TOKEN"));
    }

    @Test
    @DisplayName("a credit never crosses the cap, and answers how far it got")
    void creditHonoursTheCap() {
        assertEquals(100L, engine.credit(SUBJECT, "Capped_Token", 250));
        assertEquals(100L, engine.credit(SUBJECT, "Capped_Token", 50));
    }

    @Test
    @DisplayName("a debit takes everything asked for or nothing at all")
    void debitIsAllOrNothing() {
        engine.credit(SUBJECT, "Bounty_Token", 10);
        assertFalse(engine.debit(SUBJECT, "Bounty_Token", 25));
        assertEquals(10L, engine.balance(SUBJECT, "Bounty_Token"));
    }

    @Test
    @DisplayName("a spend is recorded and announced; a refund undoes both silently")
    void refundIsNotAnEarn() {
        engine.credit(SUBJECT, "Bounty_Token", 100);
        earns.clear();

        assertTrue(engine.debit(SUBJECT, "Bounty_Token", 30));
        assertEquals(List.of("Bounty_Token-30"), spends);
        assertEquals(30L, engine.lifetimeSpent(SUBJECT, "Bounty_Token"));

        engine.refund(SUBJECT, "Bounty_Token", 30);
        assertEquals(100L, engine.balance(SUBJECT, "Bounty_Token"));
        assertEquals(0L, engine.lifetimeSpent(SUBJECT, "Bounty_Token"));
        assertTrue(earns.isEmpty(), "a refund is money coming back, never income");
    }

    @Test
    @DisplayName("an earn announces the delta that actually landed, not what was asked for")
    void earnAnnouncesTheLandedDelta() {
        engine.credit(SUBJECT, "Capped_Token", 250);
        assertEquals(List.of("Capped_Token+100"), earns);
    }

    @Test
    @DisplayName("an unknown currency is inert rather than an error")
    void unknownCurrencyIsInert() {
        assertEquals(0L, engine.balance(SUBJECT, "Nothing_Defines_This"));
        assertEquals(0L, engine.credit(SUBJECT, "Nothing_Defines_This", 50));
        assertFalse(engine.debit(SUBJECT, "Nothing_Defines_This", 1));
    }

    @Test
    @DisplayName("set clamps to the cap and announces nothing")
    void setClampsAndIsSilent() {
        assertEquals(100L, engine.set(SUBJECT, "Capped_Token", 999));
        assertEquals(0L, engine.set(SUBJECT, "Capped_Token", -5));
        assertTrue(earns.isEmpty());
    }

    @Test
    @DisplayName("death loss rounds up so a small balance with a real percentage still loses something")
    void deathLossRoundsUp() {
        engine.credit(SUBJECT, "Risky", 3);
        assertEquals(1, engine.applyDeathLoss(SUBJECT));
        assertEquals(2L, engine.balance(SUBJECT, "Risky"), "ceil(3 * 0.25) is 1");
    }

    @Test
    @DisplayName("decay compounds over the days the caller supplies, and no days is no decay")
    void decayCompoundsOverSuppliedDays() {
        engine.credit(SUBJECT, "Fading", 100);
        assertEquals(0, engine.applyDecay(SUBJECT, 0));
        assertEquals(100L, engine.balance(SUBJECT, "Fading"));

        assertEquals(1, engine.applyDecay(SUBJECT, 2));
        assertEquals(25L, engine.balance(SUBJECT, "Fading"), "half a day, twice");
    }

    @Test
    @DisplayName("a throwing observer never reaches the transaction that was running")
    void aThrowingObserverIsContained() {
        CurrencyEngine fragile = CurrencyEngine.builder()
                .catalog(CurrencyCatalog.of(List.of(CurrencyDef.builder("Bounty_Token").build())))
                .items(wallet)
                .store(store)
                .observer(new CurrencyObserver() {
                    @Override
                    public void earned(@Nonnull Subject subject, @Nonnull CurrencyDef currency,
                            long amount, long balance) {
                        throw new IllegalStateException("listener trouble");
                    }
                })
                .warn(msg -> { })
                .build();

        assertEquals(25L, fragile.credit(SUBJECT, "Bounty_Token", 25));
    }

    @Test
    @DisplayName("consumer knobs ride on the definition and the engine reads none of them")
    void metaCarriesConsumerKnobs() {
        CurrencyDef def = CurrencyDef.builder("Bounty_Token")
                .meta("mmoskilltree", "ShowOnSidebar", "true")
                .meta("mmoskilltree", "XpConversionPercent", "0.05")
                .build();
        assertTrue(def.metaFlag("mmoskilltree", "ShowOnSidebar", false));
        assertEquals(0.05, def.metaNumber("mmoskilltree", "XpConversionPercent", 0.0), 1e-9);
        assertEquals(7.0, def.metaNumber("othermod", "Whatever", 7.0), 1e-9);
    }

    @Test
    @DisplayName("an item currency's icon falls back to whatever backs it")
    void iconFallsBackToTheBackingItem() {
        CurrencyDef backed = CurrencyDef.builder("Life_Essence")
                .backingItem("Ingredient_Life_Essence").build();
        assertEquals("Ingredient_Life_Essence", backed.iconItemId());

        CurrencyDef counter = CurrencyDef.builder("Bounty_Token").iconItem("Ingredient_Bar_Gold").build();
        assertEquals("Ingredient_Bar_Gold", counter.iconItemId());
    }
}
