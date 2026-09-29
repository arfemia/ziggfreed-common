package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The context's leaves are ORTHOGONAL and each is independently absent: a call site supplies only
 * what its own moment has, and a provider reading a leaf nobody supplied gets null rather than a
 * substitute.
 *
 * <p>The {@code target} leaf is what this file leads with, because it is the one a provider is
 * most likely to be asked for at a site that does not have one: a kill has a victim, a block break
 * has none, and both evaluate the same authored formulas.
 *
 * <p>The tests that carry a real engine stack on the item leaf are tagged {@code engine-items}: an
 * {@code ItemStack} can only be built under the engine's own log manager, so they run in the
 * {@code engineItemTest} task and the rest in the log-manager-less {@code test} task (both set up in
 * {@code gradle/zc-module.gradle}).
 */
class FactorContextTest {

    /**
     * A ref standing in for a live entity handle. The context is a pure carrier - it stores the
     * reference and hands it back, never dereferencing it - so a store-less ref is all a leaf test
     * needs, and building a real ECS store would test the engine rather than this class.
     */
    private static Ref<EntityStore> refStub() {
        return new Ref<>((Store<EntityStore>) null);
    }

    @Test
    void everyLeafIsAbsentUntilSupplied() {
        FactorContext ctx = FactorContext.builder().build();

        assertNull(ctx.param());
        assertNull(ctx.world());
        assertNull(ctx.store());
        assertNull(ctx.subject());
        assertNull(ctx.target(), "a moment with no second entity has no target, never a stand-in");
        assertNull(ctx.payload());
        assertNull(ctx.item(), "a moment with no item of its own has none, never the held one");
        assertFalse(ctx.hasItem());
        assertFalse(ctx.hasLiveSubject());
        assertFalse(ctx.hasLiveTarget());
    }

    @Test
    void theTargetLeafIsHandedBackAsGiven() {
        Ref<EntityStore> victim = refStub();
        FactorContext ctx = FactorContext.builder().target(victim).build();

        assertSame(victim, ctx.target());
        assertNull(ctx.subject(), "supplying a target must not imply a subject");
    }

    @Test
    void subjectAndTargetAreIndependentLeaves() {
        Ref<EntityStore> killer = refStub();
        Ref<EntityStore> victim = refStub();
        FactorContext ctx = FactorContext.builder().subject(killer).target(victim).build();

        assertSame(killer, ctx.subject());
        assertSame(victim, ctx.target());
    }

    @Test
    void aLiveReadNeedsBothTheStoreAndTheRef() {
        // No store, so neither side can be read even with a ref present: hasLive* is the one
        // question a provider asks before touching an entity, and it must answer no here.
        FactorContext ctx = FactorContext.builder().subject(refStub()).target(refStub()).build();

        assertFalse(ctx.hasLiveSubject());
        assertFalse(ctx.hasLiveTarget());
    }

    @Test
    void reScopingToATermsParamCarriesEveryOtherLeaf() {
        // FactorConditions and FactorFormula rebuild the context per entry so each carries its own
        // Param. A leaf dropped here would silently blank a factor for every entry after the first.
        Ref<EntityStore> killer = refStub();
        Ref<EntityStore> victim = refStub();
        Object payload = new Object();
        FactorContext ctx = FactorContext.builder()
                .param("first")
                .subject(killer)
                .target(victim)
                .payload(payload)
                .build();

        FactorContext reScoped = ctx.withParam("second");

        assertEquals("second", reScoped.param());
        assertSame(killer, reScoped.subject());
        assertSame(victim, reScoped.target());
        assertSame(payload, reScoped.payload());
    }

    @Tag("engine-items")
    @Test
    void reScopingToATermsParamCarriesTheItemLeaf() {
        ItemStack piece = stackOf("Test_Piece");
        FactorContext ctx = FactorContext.builder()
                .param("first")
                .item(piece)
                .build();

        FactorContext reScoped = ctx.withParam("second");

        assertEquals("second", reScoped.param());
        assertSame(piece, reScoped.item(), "the item leaf rides every re-scope, or item terms after the first blank");
    }

    /**
     * A real engine stack without a running server: the engine's own protected no-arg constructor
     * and id field, the pair its codec fills. The context only carries it, never reads it.
     */
    private static ItemStack stackOf(String id) {
        return new ItemStack() {
            {
                this.itemId = id;
                this.quantity = 1;
            }
        };
    }

    @Tag("engine-items")
    @Test
    void theItemLeafIsHandedBackAsGivenAndImpliesNoEntity() {
        ItemStack piece = stackOf("Test_Piece");
        FactorContext ctx = FactorContext.builder().item(piece).build();

        assertSame(piece, ctx.item());
        assertTrue(ctx.hasItem());
        assertNull(ctx.subject(), "supplying an item must not imply a subject");
        assertNull(ctx.target());
    }

    @Tag("engine-items")
    @Test
    void anEmptyStackIsNoItem() {
        FactorContext ctx = FactorContext.builder().item(ItemStack.EMPTY).build();

        assertFalse(ctx.hasItem(), "the engine's empty stack stands for nothing, so no item reading runs on it");
    }

    @Test
    void theEntityFactoriesCarryNoItem() {
        assertNull(FactorContext.about(null, refStub()).item());
        assertNull(FactorContext.about(null, refStub(), refStub()).item());
    }

    @Test
    void aboutDropsARefItCouldNotRead() {
        // The one-call factory every "which entity is this about" site builds through. An invalid
        // ref is dropped rather than carried, so a provider only ever sees a subject it can read.
        FactorContext ctx = FactorContext.about(null, refStub(), refStub());

        assertNull(ctx.store());
        assertNull(ctx.subject(), "an invalid subject ref is dropped, never carried");
        assertNull(ctx.target(), "an invalid target ref is dropped, never carried");
    }

    @Test
    void aboutCarriesAValidRefAndTheTargetStaysOptional() {
        Ref<EntityStore> killer = new Ref<>((Store<EntityStore>) null, 0);
        FactorContext ctx = FactorContext.about(null, killer);

        assertSame(killer, ctx.subject());
        assertNull(ctx.target(), "a moment with no second entity has no target, never a stand-in");
    }

    @Test
    void aProviderReadsTheTargetThroughTheContextAndNullMeansAbsent() {
        Ref<EntityStore> victim = refStub();
        FactorProvider rarity = ctx -> ctx.target() == null ? null : 3.0;

        assertEquals(3.0, rarity.resolve(FactorContext.builder().target(victim).build()));
        assertNull(rarity.resolve(FactorContext.builder().build()),
                "a target-reading factor at a site with no target must answer null, not zero");
    }
}
