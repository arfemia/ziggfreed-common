package com.ziggfreed.common.objectives.gate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType;
import com.hypixel.hytale.codec.CollectingExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * Each {@code ZigRequires} condition Type the library registers into an engine condition map decodes the shared
 * {@code Requires} block unchanged beside its map's own base keys (none left unread), reads an unauthored block as
 * none, and exports a schema the Asset Editor can take. Tagged {@code engine-items}: each codec chains from an
 * engine base whose class init builds engine codecs that log.
 *
 * <p>The NPC decision condition ({@link ZigRequiresNpcCondition}) is not here: its map's class init registers
 * every vanilla decision condition, and one of them reads the live entity module at its own class init, so no
 * unit JVM can build that codec. The boot proves it: registering a Type decodes its defaults through its codec.
 */
@Tag("engine-items")
class ZigRequiresTypesCodecTest {

    private static final String QUEST = "Zigtest_Intro";

    private static final String BLOCK = "\"Requires\": {\"Quests\": [\"" + QUEST + "\"]}";

    @Test
    void theTriggerVolumeConditionReadsItsBlockAndItsEvent() throws IOException {
        CollectingExtraInfo info = new CollectingExtraInfo();
        ZigRequiresTriggerCondition condition = decode(ZigRequiresTriggerCondition.codec(),
                "{\"Event\": \"ENTER\", \"Entry\": 2, " + BLOCK + "}", info);
        assertBlock(condition.getRequires(), info);
        assertEquals(TriggerEventType.ENTER, condition.getEventType(), "the native Event key reads beside it");
        assertNull(decode(ZigRequiresTriggerCondition.codec(), "{\"Event\": \"ENTER\"}", new CollectingExtraInfo())
                .getRequires(), "an unauthored block is none");
        assertSchema(ZigRequiresTriggerCondition.codec());
    }

    @Test
    void theEntityConditionReadsItsBlockBesideInverse() throws IOException {
        CollectingExtraInfo info = new CollectingExtraInfo();
        ZigRequiresEntityCondition condition = decode(ZigRequiresEntityCondition.codec(),
                "{\"Inverse\": true, " + BLOCK + "}", info);
        assertBlock(condition.getRequires(), info);
        assertNull(decode(ZigRequiresEntityCondition.codec(), "{}", new CollectingExtraInfo()).getRequires(),
                "an unauthored block is none");
        assertSchema(ZigRequiresEntityCondition.codec());
    }

    /**
     * The seam with the engine's flip: it computes {@code inverse != eval0(...)}, and the Type overrides that so a
     * refusal for want of a reading (here a ref with no store, as off the world thread or once the entity is gone)
     * never passes under {@code Inverse}.
     */
    @Test
    void theEntityConditionNeverPassesWithNobodyToAskEvenInverted() throws IOException {
        Ref<EntityStore> nobody = new Ref<>((Store<EntityStore>) null);
        Instant now = Instant.EPOCH;
        ZigRequiresEntityCondition asks = decode(ZigRequiresEntityCondition.codec(), "{" + BLOCK + "}",
                new CollectingExtraInfo());
        ZigRequiresEntityCondition inverted = decode(ZigRequiresEntityCondition.codec(),
                "{\"Inverse\": true, " + BLOCK + "}", new CollectingExtraInfo());
        ZigRequiresEntityCondition asksNothing = decode(ZigRequiresEntityCondition.codec(), "{}",
                new CollectingExtraInfo());
        ZigRequiresEntityCondition asksNothingInverted = decode(ZigRequiresEntityCondition.codec(),
                "{\"Inverse\": true}", new CollectingExtraInfo());
        assertFalse(asks.eval(null, nobody, now), "a block with nobody to ask refuses");
        assertFalse(inverted.eval(null, nobody, now), "and Inverse never turns that refusal into a pass");
        assertTrue(asksNothing.eval(null, nobody, now), "a block that asks for nothing holds");
        assertFalse(asksNothingInverted.eval(null, nobody, now), "and Inverse flips it as it does any condition");
    }

    @Test
    void theChoiceRequirementReadsItsBlock() throws IOException {
        CollectingExtraInfo info = new CollectingExtraInfo();
        ZigRequiresChoiceRequirement requirement = decode(ZigRequiresChoiceRequirement.codec(), "{" + BLOCK + "}",
                info);
        assertBlock(requirement.getRequires(), info);
        assertNull(decode(ZigRequiresChoiceRequirement.codec(), "{}", new CollectingExtraInfo()).getRequires(),
                "an unauthored block is none");
        assertSchema(ZigRequiresChoiceRequirement.codec());
    }

    @Test
    void theWorldEventConditionReadsItsBlockAndHandsItToTheLiveCondition() throws IOException {
        CollectingExtraInfo info = new CollectingExtraInfo();
        ZigRequiresEventCondition.Config config = decode(ZigRequiresEventCondition.Config.codec(), "{" + BLOCK + "}",
                info);
        assertBlock(config.getRequires(), info);
        ZigRequiresEventCondition live = (ZigRequiresEventCondition) config.create();
        assertSame(config.getRequires(), live.getRequires(), "the live condition asks the stage's own block");
        assertNull(decode(ZigRequiresEventCondition.Config.codec(), "{}", new CollectingExtraInfo()).getRequires(),
                "an unauthored block is none");
        assertNotNull(decode(ZigRequiresEventCondition.codec(), "{}", new CollectingExtraInfo()),
                "the live condition saves nothing and reads back from nothing");
        assertSchema(ZigRequiresEventCondition.Config.codec());
        assertNotNull(Schema.CODEC.encode(ZigRequiresEventCondition.codec().toSchema(new SchemaContext()),
                new ExtraInfo()));
    }

    // ==================== helpers ====================

    @Nonnull
    private static <T> T decode(@Nonnull BuilderCodec<T> codec, @Nonnull String json, @Nonnull ExtraInfo info)
            throws IOException {
        T decoded = codec.decodeJson(RawJsonReader.fromJsonString(json), info);
        assertNotNull(decoded, "the codec decodes " + json);
        return decoded;
    }

    /** The block read whole, and every key beside it read by the Type or its engine base. */
    private static void assertBlock(@Nullable GateSpec requires, @Nonnull CollectingExtraInfo info) {
        assertNotNull(requires, "the Requires block is read");
        assertArrayEquals(new String[] {QUEST}, requires.questsOrEmpty(), "in the shape every Requires has");
        assertTrue(info.getUnknownKeys().isEmpty(), () -> "keys left unread: " + info.getUnknownKeys());
    }

    /** The Type's schema documents its block and encodes, which the Asset Editor needs of every Type. */
    private static void assertSchema(@Nonnull BuilderCodec<?> codec) {
        ObjectSchema schema = codec.toSchema(new SchemaContext());
        assertNotNull(schema.getProperties().get("Requires"), "the schema lists Requires");
        assertNotNull(schema.getProperties().get("Requires").getMarkdownDescription(), "and documents it");
        assertNotNull(Schema.CODEC.encode(schema, new ExtraInfo()));
    }
}
