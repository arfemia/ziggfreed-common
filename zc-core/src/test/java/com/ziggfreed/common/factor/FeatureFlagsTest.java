package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The generic feature-flag factor: a mod declares its own features and their live state, and every
 * vocabulary on the server gates on {@code <namespace>:feature} with no wiring.
 *
 * <p>The namespaces here are test-unique on purpose: a contribution is process-wide and cannot be
 * withdrawn, so no two tests (and no other test class) may claim the same one.
 */
class FeatureFlagsTest {

    @AfterEach
    void forgetDeclaredFeatures() {
        FeatureFlags.reset();
    }

    private static FactorContext ctx(String param) {
        return FactorContext.builder().param(param).build();
    }

    @Test
    void aDeclaredFeatureGatesOnItsLiveState() {
        AtomicBoolean on = new AtomicBoolean(true);
        FeatureFlags.register("yourmod_flags_live", "trading", "yourmod", on::get);

        FactorProvider provider = FactorContributions.provider("yourmod_flags_live:feature");
        assertNotNull(provider, "declaring the first feature contributes the namespace's factor id");
        assertEquals(1.0, provider.resolve(ctx("trading")));

        on.set(false);
        assertEquals(0.0, provider.resolve(ctx("trading")),
                "the supplier is read per evaluation, so a runtime toggle lands on the next check");
    }

    @Test
    void anUndeclaredFeatureIsADefiniteOffAndAMissingParamIsUnanswerable() {
        FeatureFlags.register("yourmod_flags_off", "trading", "yourmod", () -> true);
        FactorProvider provider = FactorContributions.provider("yourmod_flags_off:feature");
        assertNotNull(provider);

        assertEquals(0.0, provider.resolve(ctx("nobody_declared_this")),
                "a feature nobody declared is genuinely off - a real 0 keeps the bounds-less "
                        + "presence form usable as 'the declaring mod is installed'");
        assertNull(provider.resolve(ctx(null)),
                "no param names no feature at all, so whatever asked stays shut");
        assertNull(provider.resolve(ctx("   ")));
    }

    @Test
    void aThrowingSupplierGatesAsOff() {
        FeatureFlags.register("yourmod_flags_throw", "broken", "yourmod", () -> {
            throw new IllegalStateException("config not loaded");
        });

        assertEquals(0.0, FactorContributions.provider("yourmod_flags_throw:feature")
                .resolve(ctx("broken")));
    }

    @Test
    void anAliasIsTheSameSupplierUnderASecondId() {
        AtomicBoolean on = new AtomicBoolean(true);
        FeatureFlags.register("yourmod_flags_alias", "currencies", "yourmod", on::get);
        FeatureFlags.register("yourmod_flags_alias", "currency", "yourmod", on::get);

        FactorProvider provider = FactorContributions.provider("yourmod_flags_alias:feature");
        assertEquals(1.0, provider.resolve(ctx("currency")));
        assertEquals(1.0, provider.resolve(ctx("CURRENCIES")), "feature ids match case-insensitively");
        assertEquals(List.of("currencies", "currency"), FeatureFlags.ids("yourmod_flags_alias"));
        assertTrue(FeatureFlags.isKnown("yourmod_flags_alias", "currency"));
        assertFalse(FeatureFlags.isKnown("yourmod_flags_alias", "shop"));
    }

    @Test
    void aNamespaceCarryingAColonIsRefused() {
        FeatureFlags.register("your:mod", "trading", "yourmod", () -> true);
        assertTrue(FeatureFlags.ids("your:mod").isEmpty(),
                "a colon in the namespace would mint a factor id no condition could address back");
    }
}
