package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.placement.AnchorPosition.AnchorKind;
import com.ziggfreed.common.npc.placement.PlacementFactorRegistry.FactorContext;

/**
 * The three open registries, and specifically what happens when nobody registered anything: a gate
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
        NpcPlacementBindings.clearForTests();
    }

    // ==================== factors: fail closed ====================

    @Test
    void anUnregisteredFactorResolvesToZero() {
        assertEquals(0.0, PlacementFactorRegistry.resolve("yourmod:feature",
                new FactorContext("hub", "shop", null, null)));
        assertFalse(PlacementFactorRegistry.isRegistered("yourmod:feature"));
    }

    @Test
    void aGateOnAnUnregisteredFactorFailsClosed() {
        NpcPlacementAsset.Requires requires = NpcPlacementAsset.Requires.of(new PlacementCondition[]{
                PlacementCondition.of("yourmod:feature", "shop", 1.0, null)});

        assertEquals("yourmod:feature",
                PlacementFactorRegistry.firstFailure(requires, "hub", null, null),
                "a placement gated on a mod that is not installed must stay absent, not appear "
                        + "unconditionally");
    }

    @Test
    void aRegisteredFactorSatisfiesItsBound() {
        PlacementFactorRegistry.register("yourmod:feature", ctx -> "shop".equals(ctx.param()) ? 1.0 : 0.0);
        NpcPlacementAsset.Requires requires = NpcPlacementAsset.Requires.of(new PlacementCondition[]{
                PlacementCondition.of("yourmod:feature", "shop", 1.0, null)});

        assertNull(PlacementFactorRegistry.firstFailure(requires, "hub", null, null));
    }

    @Test
    void aThrowingFactorProviderResolvesToZeroRatherThanBreakingTheSweep() {
        PlacementFactorRegistry.register("yourmod:boom", ctx -> {
            throw new IllegalStateException("provider blew up");
        });

        assertEquals(0.0, PlacementFactorRegistry.resolve("yourmod:boom",
                new FactorContext("hub", null, null, null)));
    }

    @Test
    void aNonFiniteFactorValueIsTreatedAsZeroAndFailsABound() {
        PlacementFactorRegistry.register("yourmod:nan", ctx -> Double.NaN);

        assertEquals(0.0, PlacementFactorRegistry.resolve("yourmod:nan",
                new FactorContext("hub", null, null, null)));
        assertFalse(PlacementCondition.of("yourmod:nan", null, null, null).accepts(Double.NaN));
    }

    @Test
    void aBlankConditionIsSkippedRatherThanFailingTheWholeGate() {
        NpcPlacementAsset.Requires requires = NpcPlacementAsset.Requires.of(new PlacementCondition[]{
                PlacementCondition.of(null, null, 1.0, null)});

        assertNull(PlacementFactorRegistry.firstFailure(requires, "hub", null, null),
                "a half-authored entry must not hide an otherwise working placement");
    }

    @Test
    void conditionBoundsAreInclusiveAndIndependentlyOptional() {
        assertTrue(PlacementCondition.of("f", null, null, null).accepts(-5.0));
        assertTrue(PlacementCondition.of("f", null, 1.0, null).accepts(1.0));
        assertFalse(PlacementCondition.of("f", null, 1.0, null).accepts(0.999));
        assertTrue(PlacementCondition.of("f", null, null, 3.0).accepts(3.0));
        assertFalse(PlacementCondition.of("f", null, null, 3.0).accepts(3.001));
    }

    @Test
    void factorIdsMatchCaseInsensitively() {
        PlacementFactorRegistry.register("YourMod:Feature", ctx -> 7.0);

        assertEquals(7.0, PlacementFactorRegistry.resolve("yourmod:feature",
                new FactorContext("hub", null, null, null)));
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

    // ==================== bindings: the namespace split ====================

    @Test
    void bindingsGroupByNamespaceKeepingTheFullChannelId() {
        Map<String, Map<String, PlacementBinding>> grouped = NpcPlacementBindings.byNamespace(Map.of(
                "yourmod:ui_target", PlacementBinding.value("hub"),
                "yourmod:npc_id", PlacementBinding.value("guide"),
                "othermod:thing", PlacementBinding.value("x")));

        assertEquals(2, grouped.size());
        assertEquals(2, grouped.get("yourmod").size());
        assertNotNull(grouped.get("yourmod").get("yourmod:ui_target"),
                "the full channel id stays the key so a handler can switch on it directly");
        assertEquals(1, grouped.get("othermod").size());
    }

    @Test
    void aChannelWithNoNamespaceIsDroppedBecauseItHasNoOwner() {
        assertTrue(NpcPlacementBindings.byNamespace(Map.of(
                "ui_target", PlacementBinding.value("hub"))).isEmpty());
        assertTrue(NpcPlacementBindings.byNamespace(Map.of(
                ":ui_target", PlacementBinding.value("hub"))).isEmpty());
    }

    @Test
    void namespacesGroupCaseInsensitively() {
        Map<String, Map<String, PlacementBinding>> grouped = NpcPlacementBindings.byNamespace(Map.of(
                "YourMod:a", PlacementBinding.value("1"),
                "yourmod:b", PlacementBinding.value("2")));

        assertEquals(1, grouped.size());
        assertEquals(2, grouped.get("yourmod").size());
    }

    @Test
    void anUnclaimedNamespaceIsIgnoredRatherThanFailing() {
        assertFalse(NpcPlacementBindings.isRegistered("yourmod"));
        assertTrue(NpcPlacementBindings.registeredNamespaces().isEmpty());
        // dispatchInteract needs live refs, so the ignore path is asserted through the split plus
        // the registry lookup rather than through a fake engine store.
        assertNull(NpcPlacementBindings.bindingValue("nothing_here", "yourmod:npc_id"));
    }
}
