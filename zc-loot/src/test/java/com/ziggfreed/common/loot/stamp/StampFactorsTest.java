package com.ziggfreed.common.loot.stamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.factor.DerivedFactorAsset;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.FactorRegistry;

/**
 * {@code ziggfreedcommon:item_stamp_points} reads the context item through whichever stamper is
 * active, so these tests install a stub stamper that reports a fixed inspection: the reading must be
 * exactly what the stamper says, in total or per stat, and nothing at all where the moment carries no
 * item.
 *
 * <p>The tests that build a real engine stack are tagged {@code engine-items}: an {@code ItemStack}
 * can only be built under the engine's own log manager, so they run in the {@code engineItemTest}
 * task and the overlay test in the log-manager-less {@code test} task (both set up in
 * {@code gradle/zc-module.gradle}).
 */
class StampFactorsTest {

    /** A stamper whose every stack already carries four points on Mana and two on Health. */
    private static final Stamper STUB = new Stamper() {
        @Nonnull
        @Override
        public StampInspection inspect(@Nonnull ItemStack stack) {
            return new StampInspection(6, Map.of("Mana", 4, "Health", 2), 3);
        }

        @Nonnull
        @Override
        public ItemStack apply(@Nonnull ItemStack stack, @Nonnull List<StatRoll> entries) {
            return stack;
        }
    };

    @AfterEach
    void dropTheStamper() {
        StamperRegistry.clear();
    }

    private static ItemStack piece() {
        return new ItemStack() {
            {
                this.itemId = "Test_Piece";
                this.quantity = 1;
            }
        };
    }

    private static FactorRegistry vocabulary() {
        FactorRegistry registry = new FactorRegistry();
        StampFactors.registerInto(registry, "yourmod");
        return registry;
    }

    private static Double read(FactorRegistry registry, ItemStack item, String param) {
        return registry.resolve(StampFactors.ITEM_STAMP_POINTS,
                FactorContext.builder().item(item).param(param).build());
    }

    @Tag("engine-items")
    @Test
    void withNoParamItReadsTheTotal() {
        StamperRegistry.register(STUB);

        assertEquals(6.0, read(vocabulary(), piece(), null));
        assertEquals(6.0, read(vocabulary(), piece(), "  "));
    }

    @Tag("engine-items")
    @Test
    void aStatParamReadsThatStatAloneWithoutRegardToCase() {
        StamperRegistry.register(STUB);

        assertEquals(4.0, read(vocabulary(), piece(), "Mana"));
        assertEquals(2.0, read(vocabulary(), piece(), "health"));
        assertEquals(0.0, read(vocabulary(), piece(), "Stamina"), "a stat the stamp never touched is 0");
    }

    @Tag("engine-items")
    @Test
    void withNoItemInTheContextItAnswersNothing() {
        StamperRegistry.register(STUB);

        assertNull(read(vocabulary(), null, null), "a gate on an item the moment does not have stays shut");
        assertNull(read(vocabulary(), ItemStack.EMPTY, "Mana"));
    }

    @Tag("engine-items")
    @Test
    void withNoStamperEveryItemReadsBare() {
        assertEquals(0.0, read(vocabulary(), piece(), null),
                "no stamper means nothing was ever stamped, the inspection's own conservative answer");
    }

    /**
     * <b>This test leaves its claim behind.</b> {@code FactorContributions} is process-wide and its
     * reset hook is package-private to zc-core's {@code factor} package, deliberately unreachable
     * from here (no public test hook goes into production code), so the claim on
     * {@code item_stamp_points} outlives the test for the rest of this module's test JVM. That is
     * harmless to the rest of this class: the claim is the SAME provider {@code setup()} would
     * register (a repeat claim is idempotent), a local registration outranks it, and the provider
     * reads whichever stamper is registered at the moment, which {@code dropTheStamper} clears after
     * every test. A new test in this JVM that needs the id UNclaimed cannot have it.
     */
    @Tag("engine-items")
    @Test
    void theProcessWideClaimReachesAFreshVocabulary() {
        StamperRegistry.register(STUB);
        StampFactors.contribute();

        assertTrue(FactorContributions.isContributed(StampFactors.ITEM_STAMP_POINTS));
        assertEquals(4.0, new FactorRegistry().resolve(StampFactors.ITEM_STAMP_POINTS,
                FactorContext.builder().item(piece()).param("Mana").build()),
                "a vocabulary built anywhere resolves the contributed id with nothing registered locally");
    }

    @Test
    void theShippedOverlayNamesTheStampFactor() throws Exception {
        String path = "/Server/ZiggfreedCommon/Factors/Stamp_Item_Points.json";
        String json;
        try (var in = StampFactorsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing shipped overlay: " + path);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        DerivedFactorAsset asset = DerivedFactorAsset.CODEC.decodeJsonAsset(
                RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(DerivedFactorAsset.class, "Stamp_Item_Points", null)));
        assertTrue(asset.isOverlay(), path + " must target the factor through its Factor leaf");
        assertEquals(StampFactors.ITEM_STAMP_POINTS, asset.namedFactorId());
        assertTrue(asset.carriesNaming(), path + " must carry a name");
        assertNull(asset.getFormula(), path + " is a naming overlay, never a value definition");
    }
}
