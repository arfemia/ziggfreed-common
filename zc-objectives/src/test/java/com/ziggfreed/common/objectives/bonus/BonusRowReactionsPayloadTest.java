package com.ziggfreed.common.objectives.bonus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.UUID;

import org.joml.Vector3i;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.loot.trigger.BonusMoment;
import com.ziggfreed.common.loot.trigger.BonusRow;
import com.ziggfreed.common.loot.trigger.BonusRowAsset;
import com.ziggfreed.common.loot.trigger.BonusRowConfig;
import com.ziggfreed.common.objectives.producer.BlockBreakPayload;
import com.ziggfreed.common.objectives.producer.ZigBlockBreakProducer;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.subject.Subject;

/**
 * A produced break carrying the producer's own record: the row it pays, and the hole that pays none.
 * Tagged {@code engine-items}: the engine's break event is built here.
 */
@Tag("engine-items")
class BonusRowReactionsPayloadTest {

    @AfterEach
    void reset() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of());
    }

    private static Moment breaking(String target) {
        Subject player = Subject.of(UUID.randomUUID(), "tester");
        BlockBreakPayload payload = new BlockBreakPayload(
                new BreakBlockEvent((ItemStack) null, new Vector3i(0, 64, 0), (BlockType) null));
        return new Moment(ZigBlockBreakProducer.KIND, target, null, 1L, null, (Store<EntityStore>) null,
                new Ref<EntityStore>((Store<EntityStore>) null), null, player, player, payload);
    }

    private static LootRef saying() {
        return LootRef.of(null, new Roll[] {Roll.of(null, null, null, null,
                LootGrants.of(null, null, new String[] {"say found"}, null), null)});
    }

    @Test
    void aBreakWithItsRecordAndACoveringRowIsThatRow() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_rock", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Rock_*", null, saying(), null)));

        BonusRow row = BonusRowReactions.rowFor(breaking("Rock_Stone"));
        assertNotNull(row);
        assertEquals("fixture_rock", row.sourceId());
        assertNull(BonusRowReactions.rowFor(breaking("Ore_Iron")), "no row covers it");
    }

    @Test
    void aHoleIsNoRow() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_hole", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Rock_*", null, LootRef.of(null, null), null)));

        assertNull(BonusRowReactions.rowFor(breaking("Rock_Stone")));
    }
}
