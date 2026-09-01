package com.ziggfreed.common.world.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.math.util.ChunkUtil;

/**
 * The generic per-block record store, exercised through its codec and its per-section mutators
 * with a TOY payload. Registration against a live chunk-store registry and the engine's actual
 * save/load of the section land in in-game smoke, as with every other chunk-store component (the
 * {@code PlacedBlockSectionTest} precedent); what a unit test CAN pin is everything that decides
 * an answer: which record a position maps to, the round trip a chunk save and load puts the codec
 * through, that an empty section serializes to almost nothing, that records in one section stay
 * independent of each other, and that the degraded no-registration handle refuses quietly.
 */
class BlockRecordSectionTest {

    /** A two-field stand-in payload; any real payload is just a bigger version of this shape. */
    public static final class ToyRecord {
        String name;
        Integer count;

        static final BuilderCodec<ToyRecord> CODEC = BuilderCodec.builder(ToyRecord.class, ToyRecord::new)
                .append(new KeyedCodec<>("Name", Codec.STRING, false),
                        (r, v) -> r.name = v, r -> r.name)
                .add()
                .append(new KeyedCodec<>("Count", Codec.INTEGER, false),
                        (r, v) -> r.count = v, r -> r.count)
                .add()
                .build();
    }

    private static final BuilderCodec<BlockRecordSection<ToyRecord>> CODEC =
            BlockRecordSection.buildCodec(ToyRecord.CODEC);

    private static BlockRecordSection<ToyRecord> roundTrip(BlockRecordSection<ToyRecord> original) {
        ExtraInfo info = new ExtraInfo();
        return CODEC.decode(CODEC.encode(original, info), info);
    }

    private static ToyRecord toy(String name, int count) {
        ToyRecord r = new ToyRecord();
        r.name = name;
        r.count = count;
        return r;
    }

    @Test
    void aBlockNobodyWroteHasNoRecord() {
        BlockRecordSection<ToyRecord> section = CODEC.getDefaultValue();
        assertNull(section.get(ChunkUtil.indexBlock(3, 4, 5)));
        assertEquals(0, section.recordCount());
    }

    @Test
    void recordsSurviveBeingWrittenOutAndReadBack() {
        BlockRecordSection<ToyRecord> section = CODEC.getDefaultValue();
        section.put(ChunkUtil.indexBlock(1, 2, 3), toy("first", 7));
        section.put(ChunkUtil.indexBlock(31, 31, 31), toy("corner", 1));

        BlockRecordSection<ToyRecord> restored = roundTrip(section);

        assertEquals(2, restored.recordCount());
        ToyRecord first = restored.get(ChunkUtil.indexBlock(1, 2, 3));
        assertEquals("first", first.name);
        assertEquals(7, first.count);
        ToyRecord corner = restored.get(ChunkUtil.indexBlock(31, 31, 31));
        assertEquals("corner", corner.name);
        assertEquals(1, corner.count);
        assertNull(restored.get(ChunkUtil.indexBlock(1, 2, 4)), "a neighbouring block stays empty");
    }

    /** Two records in one section are independent: touching one never reaches the other. */
    @Test
    void recordsInOneSectionStayIndependent() {
        BlockRecordSection<ToyRecord> section = CODEC.getDefaultValue();
        int a = ChunkUtil.indexBlock(0, 0, 0);
        int b = ChunkUtil.indexBlock(0, 0, 1);
        section.put(a, toy("a", 1));
        section.put(b, toy("b", 2));

        BlockRecordSection<ToyRecord> restored = roundTrip(section);
        assertNotSame(restored.get(a), restored.get(b));

        restored.get(a).count = 99;
        assertEquals(2, restored.get(b).count, "mutating one record must not reach the other");

        assertTrue(restored.removeRecord(a));
        assertEquals("b", restored.get(b).name, "removing one record must leave the other standing");
    }

    /** An empty section must cost the bare version marker, not an empty map per section. */
    @Test
    void anEmptySectionSerializesToAlmostNothing() {
        ExtraInfo info = new ExtraInfo();
        BsonDocument empty = CODEC.encode(CODEC.getDefaultValue(), info).asDocument();
        assertFalse(empty.containsKey("Records"), "an empty section must not carry a Records key");
        assertTrue(empty.size() <= 1, "nothing beyond the version marker: " + empty.toJson());

        BlockRecordSection<ToyRecord> emptied = CODEC.getDefaultValue();
        int index = ChunkUtil.indexBlock(2, 2, 2);
        emptied.put(index, toy("gone", 1));
        assertTrue(emptied.removeRecord(index));
        BsonDocument afterwards = CODEC.encode(emptied, info).asDocument();
        assertFalse(afterwards.containsKey("Records"),
                "and it goes back to costing nothing once its last record is removed");
    }

    @Test
    void removeAnswersWhetherSomethingWasThere() {
        BlockRecordSection<ToyRecord> section = CODEC.getDefaultValue();
        int index = ChunkUtil.indexBlock(9, 9, 9);
        assertFalse(section.removeRecord(index), "nothing there yet");
        section.put(index, toy("here", 3));
        assertTrue(section.removeRecord(index));
        assertNull(section.get(index));
        assertFalse(section.removeRecord(index), "already gone");
        assertEquals(0, section.recordCount());
    }

    @Test
    void anEmptySectionSurvivesTheRoundTrip() {
        BlockRecordSection<ToyRecord> restored = roundTrip(CODEC.getDefaultValue());
        assertEquals(0, restored.recordCount());
        assertNull(restored.get(ChunkUtil.indexBlock(0, 0, 0)));
    }

    @Test
    void forEachVisitsEveryRecordWithItsLocalCoordinates() {
        BlockRecordSection<ToyRecord> section = CODEC.getDefaultValue();
        section.put(ChunkUtil.indexBlock(5, 6, 7), toy("a", 1));
        section.put(ChunkUtil.indexBlock(0, 31, 15), toy("b", 2));

        List<String> seen = new ArrayList<>();
        section.forEachRecord((x, y, z, record) -> seen.add(x + "," + y + "," + z + "=" + record.name));

        assertEquals(2, seen.size());
        assertTrue(seen.contains("5,6,7=a"), seen.toString());
        assertTrue(seen.contains("0,31,15=b"), seen.toString());
    }

    /** The save snapshot is a deep copy: a mutation after the snapshot never reaches it. */
    @Test
    void aCloneCarriesTheRecordsAndNotTheInstances() {
        BlockRecordSection<ToyRecord> section = CODEC.getDefaultValue();
        int index = ChunkUtil.indexBlock(4, 4, 4);
        section.put(index, toy("kept", 5));

        @SuppressWarnings("unchecked")
        BlockRecordSection<ToyRecord> copy = (BlockRecordSection<ToyRecord>) section.clone();
        assertNotSame(section, copy);
        assertNotSame(section.get(index), copy.get(index), "the snapshot must own its records");
        assertEquals("kept", copy.get(index).name);

        section.get(index).count = 99;
        assertEquals(5, copy.get(index).count, "a mutation after the snapshot must not reach it");
    }

    /**
     * A registration that could not take hands back a handle whose every read answers "nothing was
     * ever stored" and whose every write does nothing - the safe wrong answer, since the
     * alternative takes the whole server down over one component. The null registry stands in for
     * a registry the boot could not provide; the contract under test is exactly that the failure
     * never escapes.
     */
    @Test
    void aFailedRegistrationDegradesToNothingWasEverStored() {
        BlockRecordSection.Handle<ToyRecord> handle =
                BlockRecordSection.register(null, "ToyTest", ToyRecord.CODEC);
        assertFalse(handle.isRegistered());
        assertEquals("ToyTest", handle.registryId());
        assertNull(handle.get(null, null, 1, 2, 3));
        assertNull(handle.ensureAndGet(null, null, 1, 2, 3));
        assertFalse(handle.remove(null, null, 1, 2, 3));
        assertEquals(0, handle.count(null, null));
        handle.forEach(null, null, (x, y, z, record) -> {
            throw new AssertionError("a degraded handle must visit nothing");
        });
    }
}
