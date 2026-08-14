package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.npc.placement.AnchorPosition.AnchorKind;

/**
 * The two open registries, and specifically what happens when nobody registered anything: a gate
 * must fail CLOSED, an anchor must yield NO position, and neither may throw.
 *
 * <p>Those are the cases a server actually hits (a pack installed without the mod it was written
 * for), and each of them is silent at runtime, so they are pinned here.
 */
class PlacementRegistryTest {

    @BeforeEach
    @AfterEach
    void reset() {
        PlacementFactorRegistry.clearForTests();
        AnchorResolverRegistry.clearForTests();
    }

    // ==================== factors: fail closed ====================

    /** The placement gate's own context shape: a placement id as payload, no subject entity. */
    private static FactorContext ctx(String placementId, String param) {
        return FactorContext.builder().payload(placementId).param(param).build();
    }

    @Test
    void anUnregisteredFactorCannotBeResolvedAtAll() {
        assertNull(PlacementFactorRegistry.resolve("yourmod:feature", ctx("hub", "shop")),
                "an unregistered factor must be unresolvable, never a zero that a bound could pass");
        assertFalse(PlacementFactorRegistry.isRegistered("yourmod:feature"));
    }

    @Test
    void aGateOnAnUnregisteredFactorFailsClosed() {
        NpcPlacementAsset.Requires requires = NpcPlacementAsset.Requires.of(new FactorCondition[]{
                FactorCondition.of("yourmod:feature", "shop", 1.0, null)});

        assertEquals("yourmod:feature",
                PlacementFactorRegistry.firstFailure(requires, "hub", null, null),
                "a placement gated on a mod that is not installed must stay absent, not appear "
                        + "unconditionally");
    }

    @Test
    void aBoundLessGateOnAnUnregisteredFactorAlsoFailsClosed() {
        NpcPlacementAsset.Requires requires = NpcPlacementAsset.Requires.of(new FactorCondition[]{
                FactorCondition.of("yourmod:feature", null, null, null)});

        assertEquals("yourmod:feature",
                PlacementFactorRegistry.firstFailure(requires, "hub", null, null),
                "a presence check is the shape most likely to be written, so it must be the shape "
                        + "that fails hardest when the factor's owner is absent");
    }

    @Test
    void aRegisteredFactorSatisfiesItsBound() {
        PlacementFactorRegistry.register("yourmod:feature", ctx -> "shop".equals(ctx.param()) ? 1.0 : 0.0);
        NpcPlacementAsset.Requires requires = NpcPlacementAsset.Requires.of(new FactorCondition[]{
                FactorCondition.of("yourmod:feature", "shop", 1.0, null)});

        assertNull(PlacementFactorRegistry.firstFailure(requires, "hub", null, null));
    }

    @Test
    void theGateContextCarriesThePlacementIdAsItsPayload() {
        PlacementFactorRegistry.register("yourmod:here",
                c -> "hub".equals(c.payload(String.class)) ? 1.0 : 0.0);
        NpcPlacementAsset.Requires requires = NpcPlacementAsset.Requires.of(new FactorCondition[]{
                FactorCondition.of("yourmod:here", null, 1.0, null)});

        assertNull(PlacementFactorRegistry.firstFailure(requires, "hub", null, null));
        assertEquals("yourmod:here",
                PlacementFactorRegistry.firstFailure(requires, "elsewhere", null, null));
    }

    @Test
    void aThrowingFactorProviderResolvesToNothingRatherThanBreakingTheSweep() {
        PlacementFactorRegistry.register("yourmod:boom", ctx -> {
            throw new IllegalStateException("provider blew up");
        });

        assertNull(PlacementFactorRegistry.resolve("yourmod:boom", ctx("hub", null)));
    }

    @Test
    void aNonFiniteFactorValueIsUnresolvableAndFailsABound() {
        PlacementFactorRegistry.register("yourmod:nan", ctx -> Double.NaN);

        assertNull(PlacementFactorRegistry.resolve("yourmod:nan", ctx("hub", null)));
        assertFalse(FactorCondition.of("yourmod:nan", null, null, null).accepts(Double.NaN));
    }

    @Test
    void aProviderThatCannotAnswerIsIndistinguishableFromAnUnregisteredOne() {
        PlacementFactorRegistry.register("yourmod:quiet", ctx -> null);

        assertNull(PlacementFactorRegistry.resolve("yourmod:quiet", ctx("hub", null)));
        assertFalse(FactorCondition.of("yourmod:quiet", null, null, null).accepts(null));
    }

    @Test
    void aBlankConditionIsSkippedRatherThanFailingTheWholeGate() {
        NpcPlacementAsset.Requires requires = NpcPlacementAsset.Requires.of(new FactorCondition[]{
                FactorCondition.of(null, null, 1.0, null)});

        assertNull(PlacementFactorRegistry.firstFailure(requires, "hub", null, null),
                "a half-authored entry must not hide an otherwise working placement");
    }

    @Test
    void conditionBoundsAreInclusiveAndIndependentlyOptional() {
        assertTrue(FactorCondition.of("f", null, null, null).accepts(-5.0));
        assertTrue(FactorCondition.of("f", null, 1.0, null).accepts(1.0));
        assertFalse(FactorCondition.of("f", null, 1.0, null).accepts(0.999));
        assertTrue(FactorCondition.of("f", null, null, 3.0).accepts(3.0));
        assertFalse(FactorCondition.of("f", null, null, 3.0).accepts(3.001));
    }

    @Test
    void factorIdsMatchCaseInsensitively() {
        PlacementFactorRegistry.register("YourMod:Feature", ctx -> 7.0);

        assertEquals(7.0, PlacementFactorRegistry.resolve("yourmod:feature", ctx("hub", null)));
        assertTrue(PlacementFactorRegistry.isRegistered("YOURMOD:FEATURE"));
    }

    // ==================== anchors: no position, never a crash ====================

    @Test
    void anUnregisteredAnchorProviderYieldsNoPosition() {
        List<AnchorPosition> resolved = AnchorResolverRegistry.resolve("yourmod:station_block",
                new AnchorResolverRegistry.AnchorRequest("hub", null, null, Map.of()));

        assertTrue(resolved.isEmpty());
        assertFalse(AnchorResolverRegistry.isRegistered("yourmod:station_block"));
    }

    @Test
    void aThrowingAnchorResolverYieldsNoPosition() {
        AnchorResolverRegistry.register("yourmod:boom", request -> {
            throw new IllegalStateException("resolver blew up");
        });

        assertTrue(AnchorResolverRegistry.resolve("yourmod:boom",
                new AnchorResolverRegistry.AnchorRequest("hub", null, null, Map.of())).isEmpty());
    }

    @Test
    void aRegisteredResolversPositionsAreReStampedIntoTheCustomKindAndNamespaced() {
        AnchorResolverRegistry.register("yourmod:station", request -> List.of(
                new AnchorPosition(AnchorKind.COORDS, "sawmill", 1, 2, 3, 90f)));

        List<AnchorPosition> resolved = AnchorResolverRegistry.resolve("yourmod:station",
                new AnchorResolverRegistry.AnchorRequest("hub", null, null, Map.of()));

        assertEquals(1, resolved.size());
        assertEquals(AnchorKind.CUSTOM, resolved.get(0).kind(),
                "a provider's positions always belong to the Custom group, whatever it stamped");
        assertEquals("custom:yourmod:station#sawmill", resolved.get(0).anchorKey(),
                "the provider id is folded into the instance id so two providers can never collide");
        assertEquals(1.0, resolved.get(0).x());
    }
}
