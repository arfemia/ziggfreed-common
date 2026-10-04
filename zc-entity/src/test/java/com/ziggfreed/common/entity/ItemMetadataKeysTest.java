package com.ziggfreed.common.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.inventory.DisposableItemMetadata;
import com.ziggfreed.common.stats.StackStats;

/**
 * Which metadata keys a stack carries, read without the engine's deprecated accessor, and which of
 * them no mod has declared safe to destroy with it: a bare stack reads none, a stack carrying
 * metadata reads its keys in order, and a stack whose metadata the read cannot take apart reads
 * null, which a consumer about to destroy the stack refuses on.
 *
 * <p>Tagged {@code engine-items}: every case builds a real engine stack, which only the
 * {@code engineItemTest} task's log manager allows. The declared list is global and never retracted,
 * so each case declares keys of its own.
 */
@Tag("engine-items")
class ItemMetadataKeysTest {

    private static final Item ITEM = TestItems.item("Test_Metadata_Item", 1, 0);

    /** A one-item stack of {@link #ITEM} carrying {@code metadata}, made through the engine's own constructor. */
    @Nonnull
    private static ItemStack carrying(@Nullable BsonDocument metadata) {
        return new ItemStack(ITEM.getId(), 1, 1, 1, 0, metadata) {
            @Override
            public Item getItem() {
                return ITEM;
            }
        };
    }

    @Test
    void aBareStackCarriesNoKeys() {
        assertEquals(Set.of(), ItemReadings.metadataKeys(carrying(null)));
        assertEquals(Set.of(), ItemReadings.metadataKeys(carrying(new BsonDocument())));
        assertEquals(Set.of(), ItemReadings.undeclaredMetadataKeys(carrying(null)),
                "a bare stack has nothing anyone could have failed to declare");
    }

    @Test
    void aStackCarryingMetadataReadsItsKeysInOrder() {
        BsonDocument metadata = new BsonDocument("Test_Second_Writer", new BsonString("x"))
                .append("Test_First_Writer", new BsonInt32(3));

        Set<String> keys = ItemReadings.metadataKeys(carrying(metadata));

        assertEquals(List.of("Test_Second_Writer", "Test_First_Writer"), new ArrayList<>(keys));
        assertEquals(2, metadata.size(), "reading the keys leaves the stack's own document alone");
    }

    @Test
    void aStampedStackReadsTheRecordKey() {
        // The record encoded the way a stamp writes it; ItemStack#withMetadata itself would ask the
        // item store for the item, which a unit JVM does not have.
        BsonDocument metadata = new BsonDocument(StackStats.KEY,
                StackStats.CODEC.encode(new StackStats(Map.of("Health", 2.0), 1), new ExtraInfo()));
        ItemStack stamped = carrying(metadata);

        assertEquals(Map.of("Health", 2.0), StackStats.entriesOf(stamped), "a real stamped record");
        assertEquals(Set.of(StackStats.KEY), ItemReadings.metadataKeys(stamped));
    }

    @Test
    void theUndeclaredKeysAreTheOnesNoModDeclared() {
        DisposableItemMetadata.declare("Test_Declared_Record");
        BsonDocument metadata = new BsonDocument("Test_Declared_Record", new BsonInt32(1))
                .append("Test_Someone_Elses_Data", new BsonString("keep me"));

        assertEquals(Set.of("Test_Someone_Elses_Data"), ItemReadings.undeclaredMetadataKeys(carrying(metadata)));
    }

    @Test
    void aStackWhoseMetadataCannotBeReadReadsNullAndSoDoesNoStack() {
        // Update 7's encoder reads every field directly and hands the metadata document over as it is,
        // so no stack can make the encode itself throw; the read fails where a damaged document makes it
        // fail, taking the keys apart.
        BsonDocument damaged = new BsonDocument("Test_Key", new BsonInt32(1)) {
            @Override
            public Set<String> keySet() {
                throw new IllegalStateException("a metadata document whose keys cannot be read");
            }
        };

        assertNull(ItemReadings.metadataKeys(carrying(damaged)), "cannot tell, never 'no keys'");
        assertNull(ItemReadings.undeclaredMetadataKeys(carrying(damaged)),
                "so a consumer about to destroy it refuses");
        assertNull(ItemReadings.metadataKeys(null));
        assertNull(ItemReadings.undeclaredMetadataKeys(null));
    }
}
