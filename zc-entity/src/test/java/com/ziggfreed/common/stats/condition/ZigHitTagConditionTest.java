package com.ziggfreed.common.stats.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The hit-tag read ({@link HitReads#carriesTag}) and the condition that asks it ({@code ZigHitTag}): a hit whose
 * interaction carries an asked tag counts, without regard to case; one carrying only other tags does not; a hit with no
 * interaction behind it carries none. The condition decodes, keeps its tags trimmed, and never holds outside a hit, its
 * {@code Inverse} included.
 */
class ZigHitTagConditionTest {

    private static final Ref<EntityStore> REF = new Ref<>((Store<EntityStore>) null, 1);

    @BeforeAll
    static void registerTheCondition() {
        ZigHitTagCondition.ensureKnown();
    }

    @Test
    void aTaggedHitCounts_withoutRegardToCase() {
        assertTrue(HitReads.anyTag(List.of("MmoAbility_Volley", "Full_Draw"), List.of("full_draw")));
        assertTrue(HitReads.anyTag(List.of("Quick_Shot"), List.of("Full_Draw", " Quick_Shot ")),
                "any one of the asked tags counts");
    }

    @Test
    void aHitCarryingOnlyOtherTagsDoesNotCount() {
        assertFalse(HitReads.anyTag(List.of("Quick_Shot"), List.of("Full_Draw")));
    }

    @Test
    void aHitWithNoInteractionCarriesNoTag() {
        assertFalse(HitReads.anyTag(List.of(), List.of("Full_Draw")), "no damage node, no tags");
        assertFalse(HitReads.anyTag(List.of("Full_Draw"), List.of()), "nothing asked, nothing counts");
    }

    @Test
    void theConditionDecodes_keepsItsTagsTrimmed_andNeverHoldsOutsideAHit() throws IOException {
        Condition tagged = condition("{ \"Id\": \"ZigHitTag\", \"Tags\": [ \" Full_Draw \", \"\" ] }");
        Condition untagged = condition("{ \"Id\": \"ZigHitTag\", \"Tags\": [ \"Full_Draw\" ], \"Inverse\": true }");
        ZigHitTagCondition read = assertInstanceOf(ZigHitTagCondition.class, tagged);
        assertEquals(List.of("Full_Draw"), read.asked());
        assertFalse(tagged.eval(null, REF, Instant.EPOCH), "with no hit there is no tag to carry");
        assertFalse(untagged.eval(null, REF, Instant.EPOCH), "its Inverse turns only a hit's answer around");
    }

    @Test
    @Tag("engine-items")
    void aRealHitWithNoCalculatorCarriesNoTag_andItsInverseHoldsOnIt() throws IOException {
        Damage fall = new Damage(new Damage.EnvironmentSource("fall"), Integer.MAX_VALUE, 1.0f);
        assertFalse(HitReads.carriesTag(fall, Set.of("Full_Draw")), "no interaction dealt it, so no tag");
        ZigHitTagCondition inverse = (ZigHitTagCondition) condition(
                "{ \"Id\": \"ZigHitTag\", \"Tags\": [ \"Full_Draw\" ], \"Inverse\": true }");
        assertTrue(inverse.evalOnHit(null, REF, fall, Instant.EPOCH), "a hit carrying none of them, inverted");
        ZigHitTagCondition plain = (ZigHitTagCondition) condition("{ \"Id\": \"ZigHitTag\", \"Tags\": [ \"Full_Draw\" ] }");
        assertFalse(plain.evalOnHit(null, REF, fall, Instant.EPOCH));
    }

    @Nonnull
    private static Condition condition(@Nonnull String json) throws IOException {
        return Condition.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }
}
