package com.ziggfreed.common.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.commerce.InMemoryCommerceStore;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.cost.CostEngine;
import com.ziggfreed.common.cost.CostScaling;
import com.ziggfreed.common.currency.CurrencyCatalog;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.currency.ItemWallet;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.PeriodMath;

/** The purchase pipeline: what refuses it, the order it happens in, and what it undoes. */
class ShopEngineTest {

    private static final Subject SUBJECT = Subject.of(UUID.randomUUID(), "Tester");
    private static final String KIND = "Test_Payout";
    private static final long DAY_ONE = 100L * PeriodMath.DAY_MS + 3600_000L;
    private static final long DAY_TWO = DAY_ONE + PeriodMath.DAY_MS;

    /**
     * A requirement block read through the real shared codec, so this test is driven by the same
     * decode an authored file goes through rather than by a hand-built object.
     */
    private static GateSpec gate(String json) throws IOException {
        return GateSpec.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    /** An offer assembled in the test, standing in for whatever the authoring layer produces. */
    private record TestOffer(String id, Cost price, List<RewardSpec> payout, boolean on,
            @Nullable GateSpec gate, @Nullable PurchaseLimits caps) implements ShopOffer {

        @Override
        @Nonnull
        public String offerId() {
            return id;
        }

        @Override
        @Nonnull
        public Cost cost() {
            return price;
        }

        @Override
        @Nonnull
        public List<RewardSpec> rewards() {
            return payout;
        }

        @Override
        public boolean enabled() {
            return on;
        }

        @Override
        @Nullable
        public GateSpec requires() {
            return gate;
        }

        @Override
        @Nullable
        public PurchaseLimits limits() {
            return caps;
        }
    }

    private InMemoryCommerceStore store;
    private CurrencyEngine currencies;
    private CostEngine costs;
    private RewardKindRegistry kinds;
    private List<String> granted;
    private boolean payoutThrows;
    private ShopEngine shop;

    @BeforeEach
    void setUp() {
        store = new InMemoryCommerceStore();
        granted = new ArrayList<>();
        payoutThrows = false;

        currencies = CurrencyEngine.builder()
                .catalog(CurrencyCatalog.of(List.of(CurrencyDef.builder("Bounty_Token").build())))
                .items(ItemWallet.NONE)
                .store(store)
                .warn(msg -> { })
                .build();
        costs = CostEngine.builder(currencies).items(ItemWallet.NONE).warn(msg -> { }).build();

        kinds = new RewardKindRegistry();
        kinds.register(KIND, "test", new RewardHandler() {
            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
                if (payoutThrows) {
                    throw new IllegalStateException("payout refused");
                }
                granted.add(spec.paramOr("what", "?"));
            }
        });

        shop = ShopEngine.builder(costs, GateEvaluator.builder().warn(msg -> { }).build())
                .store(store)
                .kinds(kinds)
                .warn(msg -> { })
                .info(msg -> { })
                .build();
    }

    private static TestOffer offer(long price, PurchaseLimits caps) {
        return new TestOffer("boost_mining", Cost.single("Bounty_Token", price),
                List.of(RewardSpec.of(KIND, "what", "boost")), true, null, caps);
    }

    @Test
    @DisplayName("a purchase drains the price, pays out once, and records itself")
    void aPurchaseCharges() {
        currencies.credit(SUBJECT, "Bounty_Token", 500);

        ShopEngine.PurchaseOutcome outcome = shop.purchase(SUBJECT, offer(150, null), DAY_ONE);

        assertTrue(outcome.ok());
        assertEquals(List.of("boost"), granted);
        assertEquals(350L, currencies.balance(SUBJECT, "Bounty_Token"));
        assertEquals(1, store.purchasesTotal(SUBJECT, "boost_mining"));
        assertEquals(1, outcome.grants().granted());
    }

    @Test
    @DisplayName("a disabled offer refuses before anything else is even asked")
    void aDisabledOfferRefuses() {
        currencies.credit(SUBJECT, "Bounty_Token", 500);
        TestOffer off = new TestOffer("off", Cost.FREE, List.of(), false, null, null);

        ShopEngine.PurchaseOutcome outcome = shop.purchase(SUBJECT, off, DAY_ONE);
        assertFalse(outcome.ok());
        assertEquals(ShopEngine.REASON_DISABLED, outcome.reason());
    }

    @Test
    @DisplayName("a gate refusal is the shared evaluator's own word, passed straight through")
    void aGateRefusalIsTheSharedOne() throws IOException {
        currencies.credit(SUBJECT, "Bounty_Token", 500);
        TestOffer gated = new TestOffer("gated", Cost.FREE, List.of(), true,
                gate("{ \"Permission\": \"shop.vip\" }"), null);

        ShopEngine.PurchaseOutcome outcome = shop.purchase(SUBJECT, gated, DAY_ONE);
        assertFalse(outcome.ok());
        assertEquals(GateEvaluator.REASON_PERMISSION, outcome.reason());
        assertEquals(500L, currencies.balance(SUBJECT, "Bounty_Token"), "nothing was charged");
    }

    @Test
    @DisplayName("a price the buyer cannot meet names the currency and charges nothing")
    void anUnaffordablePriceNamesTheCurrency() {
        currencies.credit(SUBJECT, "Bounty_Token", 10);

        ShopEngine.PurchaseOutcome outcome = shop.purchase(SUBJECT, offer(150, null), DAY_ONE);
        assertFalse(outcome.ok());
        assertEquals(ShopEngine.REASON_SHORT_CURRENCY + "Bounty_Token", outcome.reason());
        assertEquals(10L, currencies.balance(SUBJECT, "Bounty_Token"));
        assertTrue(granted.isEmpty());
    }

    @Test
    @DisplayName("a daily limit holds within the day and lifts when the day number moves")
    void theDailyLimitResetsWithTheDay() {
        currencies.credit(SUBJECT, "Bounty_Token", 1000);
        TestOffer twiceADay = offer(10, PurchaseLimits.of(2, null));

        assertTrue(shop.purchase(SUBJECT, twiceADay, DAY_ONE).ok());
        assertTrue(shop.purchase(SUBJECT, twiceADay, DAY_ONE).ok());

        ShopEngine.PurchaseOutcome third = shop.purchase(SUBJECT, twiceADay, DAY_ONE);
        assertFalse(third.ok());
        assertEquals(ShopEngine.REASON_LIMIT_DAILY, third.reason());

        assertTrue(shop.purchase(SUBJECT, twiceADay, DAY_TWO).ok());
    }

    @Test
    @DisplayName("a lifetime limit is reported ahead of a daily one, being the worse news")
    void theLifetimeLimitIsReportedFirst() {
        currencies.credit(SUBJECT, "Bounty_Token", 1000);
        TestOffer onceEver = offer(10, PurchaseLimits.of(5, 1));

        assertTrue(shop.purchase(SUBJECT, onceEver, DAY_ONE).ok());
        ShopEngine.PurchaseOutcome second = shop.purchase(SUBJECT, onceEver, DAY_TWO);
        assertFalse(second.ok());
        assertEquals(ShopEngine.REASON_LIMIT_TOTAL, second.reason());
    }

    @Test
    @DisplayName("when every reward fails the price goes back and the purchase does not count")
    void nothingDeliveredRefundsAndDoesNotCount() {
        currencies.credit(SUBJECT, "Bounty_Token", 500);
        payoutThrows = true;

        ShopEngine.PurchaseOutcome outcome = shop.purchase(SUBJECT, offer(150, null), DAY_ONE);

        assertFalse(outcome.ok());
        assertEquals(ShopEngine.REASON_REFUNDED, outcome.reason());
        assertEquals(500L, currencies.balance(SUBJECT, "Bounty_Token"));
        assertEquals(0, store.purchasesTotal(SUBJECT, "boost_mining"));
        assertEquals(1, outcome.grants().failed());
    }

    @Test
    @DisplayName("a failed reward that could be queued still counts as delivered, so nothing is refunded")
    void aQueuedRewardCountsAsDelivered() {
        RewardKindRegistry replayable = new RewardKindRegistry();
        replayable.register(KIND, "test", new RewardHandler() {
            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
                throw new IllegalStateException("not right now");
            }

            @Override
            @Nullable
            public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                    @Nonnull String sourceId) {
                return "give " + subject.name() + " boost";
            }
        });
        List<String> queued = new ArrayList<>();
        ShopEngine queueing = ShopEngine.builder(costs, GateEvaluator.builder().warn(msg -> { }).build())
                .store(store)
                .kinds(replayable)
                .retryQueue((subject, command) -> queued.add(command))
                .warn(msg -> { })
                .info(msg -> { })
                .build();

        currencies.credit(SUBJECT, "Bounty_Token", 500);
        ShopEngine.PurchaseOutcome outcome = queueing.purchase(SUBJECT, offer(150, null), DAY_ONE);

        assertTrue(outcome.ok());
        assertTrue(outcome.anyQueued());
        assertEquals(1, queued.size());
        assertEquals(350L, currencies.balance(SUBJECT, "Bounty_Token"), "the price stands");
        assertEquals(1, store.purchasesTotal(SUBJECT, "boost_mining"));
    }

    @Test
    @DisplayName("an offer paying out nothing still completes, since nothing is not a failure")
    void anEmptyPayoutStillCompletes() {
        currencies.credit(SUBJECT, "Bounty_Token", 500);
        TestOffer nothing = new TestOffer("nothing", Cost.single("Bounty_Token", 25),
                List.of(), true, null, null);

        ShopEngine.PurchaseOutcome outcome = shop.purchase(SUBJECT, nothing, DAY_ONE);
        assertTrue(outcome.ok());
        assertEquals(475L, currencies.balance(SUBJECT, "Bounty_Token"));
    }

    @Test
    @DisplayName("a scaling price grows with what this buyer has already bought")
    void theQuotedPriceGrowsWithPriorPurchases() {
        currencies.credit(SUBJECT, "Bounty_Token", 10_000);
        CostScaling curve = CostScaling.of(CostScaling.Curve.EXPONENTIAL, 2.0, 0);
        TestOffer climbing = new TestOffer("climbing",
                Cost.of(Cost.Combine.ALL, Map.of("Bounty_Token", 100L), null, curve),
                List.of(RewardSpec.of(KIND, "what", "step")), true, null, null);

        assertEquals(100L, shop.priceFor(SUBJECT, climbing).amountOf("Bounty_Token"));
        assertTrue(shop.purchase(SUBJECT, climbing, DAY_ONE).ok());
        assertEquals(200L, shop.priceFor(SUBJECT, climbing).amountOf("Bounty_Token"));
        assertTrue(shop.purchase(SUBJECT, climbing, DAY_ONE).ok());
        assertEquals(400L, shop.priceFor(SUBJECT, climbing).amountOf("Bounty_Token"));
        assertEquals(10_000L - 100L - 200L, currencies.balance(SUBJECT, "Bounty_Token"));
    }

    @Test
    @DisplayName("an offer nothing answers to is refused rather than guessed at")
    void anUnknownOfferIdIsRefused() {
        ShopEngine.PurchaseOutcome outcome = shop.purchase(SUBJECT, "nobody_ships_this", DAY_ONE);
        assertFalse(outcome.ok());
        assertEquals(ShopEngine.REASON_UNKNOWN_OFFER, outcome.reason());
    }

    @Test
    @DisplayName("the check reports what the purchase would refuse, without charging anything")
    void checkAndPurchaseAgree() {
        currencies.credit(SUBJECT, "Bounty_Token", 10);
        TestOffer dear = offer(150, null);

        ShopEngine.PurchaseCheck check = shop.canPurchase(SUBJECT, dear, DAY_ONE);
        assertFalse(check.ok());
        assertEquals(ShopEngine.REASON_SHORT_CURRENCY + "Bounty_Token", check.reason());
        assertEquals(10L, currencies.balance(SUBJECT, "Bounty_Token"));
    }

    @Test
    @DisplayName("a catalog answers offers by id, case-insensitively")
    void theCatalogResolvesById() {
        ShopCatalog catalog = ShopCatalog.of(List.of(offer(10, null)), o -> "Featured");
        ShopEngine withCatalog = ShopEngine.builder(costs,
                        GateEvaluator.builder().warn(msg -> { }).build())
                .catalog(catalog).store(store).kinds(kinds).warn(msg -> { }).info(msg -> { }).build();

        currencies.credit(SUBJECT, "Bounty_Token", 100);
        assertTrue(withCatalog.purchase(SUBJECT, "BOOST_MINING", DAY_ONE).ok());
        assertEquals(1, catalog.poolCandidates("featured").size());
    }

    @Test
    @DisplayName("the day number a purchase is counted against comes from the clock it was handed")
    void theDayNumberComesFromTheInjectedClock() {
        assertEquals(100L, ShopEngine.epochDay(DAY_ONE));
        assertEquals(101L, ShopEngine.epochDay(DAY_TWO));
    }
}
