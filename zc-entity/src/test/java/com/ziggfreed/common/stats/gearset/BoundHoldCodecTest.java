package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.grant.StatGrant;
import com.ziggfreed.common.stats.condition.BoundGrants;
import com.ziggfreed.common.stats.condition.ZigDirectHitCondition;
import com.ziggfreed.common.stats.condition.ZigWorkingAtCondition;

/**
 * The bound grant's hold reads as the files write it: a Stat grant's and a gear set tier's {@code HoldSeconds} decode
 * beside their {@code While}, read 0 unwritten or below zero, and a tier's reaches the bound modifiers the holder pass
 * reads; and the library's {@code ZigDirectHit} decodes and never holds outside a hit, its {@code Inverse} included.
 * Untagged: no engine item is built.
 */
class BoundHoldCodecTest {

    private static final Ref<EntityStore> REF = new Ref<>((Store<EntityStore>) null, 1);

    @BeforeAll
    static void registerTheConditions() {
        // The library registers its conditions at setup; a unit JVM registers the ones these files name.
        ZigWorkingAtCondition.ensureKnown();
        ZigDirectHitCondition.ensureKnown();
    }

    @Test
    void aStatGrantsHoldDecodesBesideItsWhile_andReadsZeroUnwrittenOrBelowZero() throws IOException {
        StatGrant held = grant("{ \"Stat\": \"DamageResist\", \"Add\": 3, \"While\": [ { \"Id\": \"ZigWorkingAt\" } ],"
                + " \"HoldSeconds\": 2 }");
        assertTrue(held.bound());
        assertEquals(2.0, held.holdSeconds(), 1e-9);
        assertEquals(0.0, grant("{ \"Stat\": \"DamageResist\", \"Add\": 3, \"While\": [ { \"Id\": \"ZigWorkingAt\" } ] }")
                .holdSeconds(), 0.0, "unwritten: it counts the moment its conditions hold");
        assertEquals(0.0, grant("{ \"Stat\": \"DamageResist\", \"Add\": 3, \"While\": [ { \"Id\": \"ZigWorkingAt\" } ],"
                + " \"HoldSeconds\": -1 }").holdSeconds(), 0.0, "below zero reads 0");
    }

    @Test
    void aGearSetTiersHoldDecodes_andReachesItsBoundModifiers() throws IOException {
        GearSetAsset set = GearSetAssetCodecTest.decode("Still_Kit", """
                { "Members": ["Still_Boots"],
                  "Bonuses": [ { "Pieces": 1, "While": [ { "Id": "ZigWorkingAt" } ], "HoldSeconds": 1.5,
                                 "StatModifiers": { "DamageResist": [ { "Amount": 4 } ] } } ] }
                """, null);
        GearSetAsset.Tier tier = set.tiers().get(0);
        assertTrue(tier.bound());
        assertEquals(1.5, tier.holdSeconds(), 1e-9);
        assertNotNull(GearSetAsset.Tier.CODEC.toSchema(new SchemaContext()).getProperties().get("HoldSeconds"),
                "the editor lists the leaf");

        List<BoundGrants.Bound> bound = GearSetPlan.bound(new GearSetKeys.TierRef("Still_Kit", 0), tier);
        assertEquals(1, bound.size());
        assertEquals(1.5, bound.get(0).holdSeconds(), 1e-9, "the hold reaches the modifier the holder pass reads");
        assertEquals(0.0, new BoundGrants.Bound("kit", "DamageResist", bound.get(0).modifier(),
                tier.whileConditions()).holdSeconds(), 0.0, "the four-argument form counts at once");
    }

    @Test
    void theDirectHitConditionDecodes_andNeverHoldsOutsideAHit() throws IOException {
        Condition direct = condition("{ \"Id\": \"ZigDirectHit\" }");
        Condition overTime = condition("{ \"Id\": \"ZigDirectHit\", \"Inverse\": true }");
        assertInstanceOf(ZigDirectHitCondition.class, direct);
        assertFalse(direct.eval(null, REF, Instant.EPOCH), "with no hit there is nothing to be direct");
        assertFalse(overTime.eval(null, REF, Instant.EPOCH), "its Inverse turns only a hit's answer around");
    }

    @Nonnull
    private static StatGrant grant(@Nonnull String json) throws IOException {
        return StatGrant.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Nonnull
    private static Condition condition(@Nonnull String json) throws IOException {
        return Condition.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }
}
