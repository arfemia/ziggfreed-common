package com.ziggfreed.common.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.EntityStatOp;
import com.hypixel.hytale.protocol.EntityStatResetBehavior;
import com.hypixel.hytale.protocol.EntityStatUpdate;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.ziggfreed.common.testing.EngineAssetStores;

/**
 * A heal after a max-health modifier keeps the modifier on its way to the entity's viewers. On a shared
 * stat the engine folds a Maximize into the update queued just before it and rewrites that update's op,
 * so a {@code PutModifier} followed by {@code maximizeStatValue} never reaches a viewer: the client's max
 * stays at the base and its bar empties while the entity still lives. {@link HealthUtil#fillToMax} heals
 * by the gap instead, an Add the engine never folds into a {@code PutModifier}. It has bitten twice.
 *
 * <p>Tagged {@code engine-items}: the stat-type store swap builds the engine's {@code AssetStore}, which
 * loads only under the engine's own log manager.
 */
@Tag("engine-items")
class HealthFillTest {

    private static final String KEY = "Test_Health_Fill_Scale";

    /** A shared stat like the game's Health: 100 at its base max, starting full. */
    @Nonnull
    private static EntityStatType sharedHealth() {
        return new EntityStatType("Test_Health_Fill_Health", 100, 0, 100, true, null, null, null,
                EntityStatResetBehavior.InitialValue);
    }

    /** A fresh map over the seeded type, its update queues emptied as at a tick's start. */
    @Nonnull
    private static EntityStatMap freshMap() {
        EntityStatMap stats = new EntityStatMap();
        stats.update();
        stats.clearUpdates();
        return stats;
    }

    @Nonnull
    private static StaticModifier doubledMax() {
        return new StaticModifier(Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.MULTIPLICATIVE, 2f);
    }

    private static boolean carries(@Nonnull List<EntityStatUpdate> updates, @Nonnull EntityStatOp op) {
        return updates.stream().anyMatch(update -> update.op == op);
    }

    @Test
    void fillingAfterAMaxModifierKeepsThePutModifierForViewers() {
        try (EngineAssetStores.Swap ignored = EngineAssetStores.statTypes(sharedHealth())) {
            EntityStatMap stats = freshMap();
            stats.putModifier(0, KEY, doubledMax());

            assertTrue(HealthUtil.fillToMax(stats, 0), "the raised pool was not full, so the fill heals");

            EntityStatValue health = stats.get(0);
            assertEquals(health.getMax(), health.get(), 1e-4f, "filled to the raised max");
            assertTrue(health.getMax() > 100f, "the modifier raised the max");
            List<EntityStatUpdate> shared = stats.otherUpdates.get(0);
            assertTrue(carries(shared, EntityStatOp.PutModifier),
                    "the viewers' queue still carries the PutModifier: " + shared.stream().map(u -> u.op).toList());
            assertFalse(carries(shared, EntityStatOp.Maximize), "no Maximize rewrote it");
        }
    }
}
