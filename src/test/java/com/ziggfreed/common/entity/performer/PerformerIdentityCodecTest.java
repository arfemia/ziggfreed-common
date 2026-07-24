package com.ziggfreed.common.entity.performer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * {@link PerformerIdentityComponent#CODEC} round-trip + the {@link PerformerIdentity} projection,
 * exercised via the {@code BuilderCodec} directly (never through the component registry / a live
 * store - engine state a unit JVM cannot stand up), mirroring {@code StackStatsTest}'s shape.
 */
class PerformerIdentityCodecTest {

    private static PerformerIdentityComponent decode(String json) throws IOException {
        return PerformerIdentityComponent.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void encodeThenDecodeRoundTripsAllFields() {
        PerformerIdentityComponent original = new PerformerIdentityComponent();
        original.ownerUuid = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
        original.stationKey = "world-1:10:64:-20";
        original.kind = PerformerKind.NPC_ROLE.code();
        original.spawnedAtMs = 1_700_000_000_123L;

        ExtraInfo info = new ExtraInfo();
        var bson = PerformerIdentityComponent.CODEC.encode(original, info);
        PerformerIdentityComponent decoded = PerformerIdentityComponent.CODEC.decode(bson, info);

        assertEquals(original.ownerUuid, decoded.ownerUuid);
        assertEquals(original.stationKey, decoded.stationKey);
        assertEquals(original.kind, decoded.kind);
        assertEquals(original.spawnedAtMs, decoded.spawnedAtMs);
    }

    @Test
    void decodesFieldsFromJson() throws IOException {
        PerformerIdentityComponent c = decode(
                "{\"OwnerUuid\":\"3f2504e0-4f89-41d3-9a0c-0305e82c3301\",\"StationKey\":\"w:1:2:3\","
                        + "\"Kind\":\"BareHolder\",\"SpawnedAtMs\":42}");
        assertEquals("3f2504e0-4f89-41d3-9a0c-0305e82c3301", c.ownerUuid);
        assertEquals("w:1:2:3", c.stationKey);
        assertEquals("BareHolder", c.kind);
        assertEquals(42L, c.spawnedAtMs);
    }

    @Test
    void missingFieldsDecodeToNullAndZero() throws IOException {
        PerformerIdentityComponent c = decode("{}");
        assertNull(c.ownerUuid);
        assertNull(c.stationKey);
        assertNull(c.kind);
        assertEquals(0L, c.spawnedAtMs);
    }

    @Test
    void toIdentityParsesUuidAndKind() throws IOException {
        UUID owner = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
        PerformerIdentityComponent c = decode(
                "{\"OwnerUuid\":\"3f2504e0-4f89-41d3-9a0c-0305e82c3301\",\"StationKey\":\"w:1:2:3\","
                        + "\"Kind\":\"NpcRole\",\"SpawnedAtMs\":42}");
        PerformerIdentity id = c.toIdentity();
        assertEquals(owner, id.ownerUuid());
        assertEquals("w:1:2:3", id.stationKey());
        assertEquals(PerformerKind.NPC_ROLE, id.kind());
        assertEquals(42L, id.spawnedAtMs());
    }

    @Test
    void toIdentityUnparseableUuidIsNull_stationKeyDefaultsBlank() throws IOException {
        PerformerIdentity id = decode("{\"OwnerUuid\":\"not-a-uuid\",\"Kind\":\"BareHolder\"}").toIdentity();
        assertNull(id.ownerUuid(), "an unparseable owner degrades to null, not a throw");
        assertEquals("", id.stationKey(), "a missing station key defaults to blank");
        assertEquals(PerformerKind.HOLDER, id.kind());
    }

    @Test
    void ofSetsAllFieldsFromIdentity() {
        UUID owner = UUID.randomUUID();
        PerformerIdentity id = new PerformerIdentity(owner, "w:9:9:9", PerformerKind.NPC_ROLE, 777L);
        PerformerIdentityComponent c = PerformerIdentityComponent.of(id);
        assertEquals(owner.toString(), c.ownerUuid);
        assertEquals("w:9:9:9", c.stationKey);
        assertEquals("NpcRole", c.kind);
        assertEquals(777L, c.spawnedAtMs);
        // round-trips back to an equal identity
        assertEquals(id, c.toIdentity());
    }

    @Test
    void cloneIsIndependent() {
        PerformerIdentityComponent c = PerformerIdentityComponent.of(
                new PerformerIdentity(UUID.randomUUID(), "w:1:1:1", PerformerKind.HOLDER, 5L));
        PerformerIdentityComponent copy = c.clone();
        copy.stationKey = "changed";
        assertTrue(!"changed".equals(c.stationKey), "clone is a deep-enough copy for its String fields");
    }
}
