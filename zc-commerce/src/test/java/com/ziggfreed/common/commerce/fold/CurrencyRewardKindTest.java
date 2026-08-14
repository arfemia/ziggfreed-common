package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.commerce.InMemoryCommerceStore;
import com.ziggfreed.common.currency.CurrencyCatalog;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * Paying a wallet as a reward, driven end to end with every seam faked: a fixed catalogue, an
 * in-memory store, no server.
 *
 * <p>Two of these are the load-bearing ones. A wallet nothing defines must FAIL rather than
 * silently pay nothing, because the shared issuance pass can only report what it is told failed; and
 * a wallet at its authored ceiling must NOT fail, because landing short of a cap is a decision the
 * content made rather than something that went wrong.
 */
class CurrencyRewardKindTest {

    private static final Subject SUBJECT = Subject.of(UUID.randomUUID(), "Tester");

    private InMemoryCommerceStore store;
    private CurrencyEngine engine;

    @BeforeEach
    void setUp() {
        store = new InMemoryCommerceStore();
        CurrencyCatalog catalog = CurrencyCatalog.of(List.of(
                CurrencyDef.builder("Bounty_Token").build(),
                CurrencyDef.builder("Capped_Token").cap(100L).build()));
        engine = CurrencyEngine.builder().catalog(catalog).store(store).build();
        CommerceDefaults.installCurrencyEngine(engine);
    }

    @AfterEach
    void tearDown() {
        CommerceDefaults.installCurrencyEngine(null);
    }

    @Test
    @DisplayName("a payout lands in the wallet the reward names")
    void aGrantCreditsTheNamedWallet() throws Exception {
        RewardSpec spec = RewardSpec.of(CurrencyRewardKind.KIND,
                Map.of("Currency", "Bounty_Token", "Amount", "300"));

        kinds().handler(CurrencyRewardKind.KIND).grant(spec, SUBJECT);

        assertEquals(300L, engine.balance(SUBJECT, "bounty_token"));
    }

    @Test
    @DisplayName("a wallet id is matched however the content spells it")
    void theWalletIdIsCaseInsensitive() throws Exception {
        RewardSpec spec = RewardSpec.of(CurrencyRewardKind.KIND,
                Map.of("currency", "bounty_token", "amount", "25"));

        kinds().handler(CurrencyRewardKind.KIND).grant(spec, SUBJECT);

        assertEquals(25L, engine.balance(SUBJECT, "Bounty_Token"));
    }

    @Test
    @DisplayName("a wallet nothing defines fails loudly rather than paying nothing quietly")
    void anUndefinedWalletFails() {
        RewardSpec spec = RewardSpec.of(CurrencyRewardKind.KIND,
                Map.of("Currency", "no_such_wallet", "Amount", "10"));

        assertThrows(IllegalStateException.class,
                () -> kinds().handler(CurrencyRewardKind.KIND).grant(spec, SUBJECT));
    }

    @Test
    @DisplayName("a reward naming no wallet, or no amount, is a failure rather than a no-op")
    void aMalformedPayoutFails() {
        assertThrows(IllegalStateException.class, () -> kinds().handler(CurrencyRewardKind.KIND)
                .grant(RewardSpec.of(CurrencyRewardKind.KIND, Map.of("Amount", "10")), SUBJECT));
        assertThrows(IllegalStateException.class, () -> kinds().handler(CurrencyRewardKind.KIND)
                .grant(RewardSpec.of(CurrencyRewardKind.KIND, Map.of("Currency", "Bounty_Token")),
                        SUBJECT));
    }

    @Test
    @DisplayName("a wallet already at its ceiling still counts as paid")
    void aCappedWalletIsNotAFailure() throws Exception {
        engine.set(SUBJECT, "Capped_Token", 100L);
        RewardSpec spec = RewardSpec.of(CurrencyRewardKind.KIND,
                Map.of("Currency", "Capped_Token", "Amount", "50"));

        kinds().handler(CurrencyRewardKind.KIND).grant(spec, SUBJECT);

        assertEquals(100L, engine.balance(SUBJECT, "capped_token"),
                "the cap held, and holding a cap is not a payout that went wrong");
    }

    @Test
    @DisplayName("the kind pays through the ONE issuance pass, like every other reward")
    void itPaysOutThroughTheSharedGrantPass() {
        RewardKindRegistry kinds = kinds();
        List<RewardSpec> rewards = List.of(
                RewardSpec.of(CurrencyRewardKind.KIND,
                        Map.of("Currency", "Bounty_Token", "Amount", "40")),
                RewardSpec.of(CurrencyRewardKind.KIND,
                        Map.of("Currency", "no_such_wallet", "Amount", "40")));

        RewardGrants.GrantOutcome outcome =
                RewardGrants.grantAll(rewards, SUBJECT, "test", kinds, null, message -> { });

        assertEquals(1, outcome.granted());
        assertEquals(1, outcome.failed(), "one bad wallet costs its own reward and nothing else");
        assertTrue(outcome.anyDelivered());
        assertEquals(40L, engine.balance(SUBJECT, "bounty_token"));
    }

    /** A private kind table, so this test never touches the process-wide one a server shares. */
    private static RewardKindRegistry kinds() {
        RewardKindRegistry kinds = new RewardKindRegistry();
        CurrencyRewardKind.registerInto(kinds);
        assertNotNull(kinds.handler(CurrencyRewardKind.KIND),
                "the kind has to be registered before anything can be paid through it");
        return kinds;
    }
}
