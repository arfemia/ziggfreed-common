package com.ziggfreed.common.loot.stamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.inventory.DisposableItemMetadata;

/**
 * A stamper declares every metadata key it writes, and registering it declares them safe to destroy
 * with their item. The library's own stamper declares its stat record and the engine tooltip it
 * writes beside it; a third-party stamper's keys arrive the same way, with no second call.
 *
 * <p>No item is built here: naming keys needs none. The metadata keys ARE the literal strings, since
 * a changed key would orphan every stamped item already in the world.
 *
 * <p>The declared list is process-wide and never retracted, so every registration case writes a key
 * of its own that no other test can have declared, and checks it was NOT declared before
 * registering; the library stamper's keys are checked through {@code metadataKeys()} alone, since
 * any other test may already have registered it. No case depends on the order the methods run in.
 */
class StamperMetadataKeysTest {

    /** A stamper from another mod, writing one key of its own. */
    private static final class ForeignStamper implements Stamper {

        private final String key;

        ForeignStamper(@Nonnull String key) {
            this.key = key;
        }

        @Override
        @Nonnull
        public StampInspection inspect(@Nonnull ItemStack stack) {
            return StampInspection.empty();
        }

        @Override
        @Nonnull
        public ItemStack apply(@Nonnull ItemStack stack, @Nonnull List<StatRoll> entries) {
            return stack;
        }

        @Override
        @Nonnull
        public Set<String> metadataKeys() {
            return Set.of(key);
        }
    }

    /** A key no other test, and no earlier case of this one, can have declared. */
    @Nonnull
    private static String uniqueKey() {
        String key = "Consumer_Stamp_Record_" + UUID.randomUUID();
        assertFalse(DisposableItemMetadata.isDeclared(key), "nothing declared this key yet");
        return key;
    }

    @AfterEach
    void unregister() {
        StamperRegistry.clear();
    }

    @Test
    void theLibraryStamperNamesItsRecordAndTheEngineTooltip() {
        assertEquals(Set.of("ZigStackStats", "ItemDisplay"), new StackStatsStamper().metadataKeys());
    }

    @Test
    void registeringAStamperDeclaresEveryKeyItNames() {
        String key = uniqueKey();

        StamperRegistry.register(new ForeignStamper(key));

        assertTrue(DisposableItemMetadata.isDeclared(key));
    }

    @Test
    void aReplacedStamperKeepsItsDeclarationBecauseItsItemsStillCarryTheKeys() {
        String first = uniqueKey();
        String second = uniqueKey();

        StamperRegistry.register(new ForeignStamper(first));
        StamperRegistry.register(new ForeignStamper(second));

        assertTrue(DisposableItemMetadata.isDeclared(first), "the replaced stamper's key stays declared");
        assertTrue(DisposableItemMetadata.isDeclared(second), "and the replacement's is declared beside it");
    }

    @Test
    void aStamperThatWritesNoMetadataDeclaresNothingAndRegisteringNullIsHarmless() {
        Stamper silent = new Stamper() {
            @Override
            @Nonnull
            public StampInspection inspect(@Nonnull ItemStack stack) {
                return StampInspection.empty();
            }

            @Override
            @Nonnull
            public ItemStack apply(@Nonnull ItemStack stack, @Nonnull List<StatRoll> entries) {
                return stack;
            }
        };

        assertTrue(silent.metadataKeys().isEmpty());
        StamperRegistry.register(null);
        assertNull(StamperRegistry.get());
    }
}
