package com.ziggfreed.common.ui.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bson.BsonDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.codec.Codec;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.owner.OwnerFileBinding;

/**
 * An owner form of the Entries shape writes one entry of an id-keyed file through the store's own entry writer (R23's
 * guards on that writer): a whole number written {@code 1800}, never {@code 1800.0}, a decimal as a decimal, under the
 * spelling the file already gives the id, every other key kept; an id the store's pattern refuses (a reserved
 * {@code $} key, a dotted or spaced id, a blank) or a leaf that is no plain path writes nothing, and neither does a file
 * that cannot be read. And its {@code Gate} hides an entry exactly where the library's hide axis hides content: a
 * feature switched off hides it, a permission alone never does.
 */
class OwnerFormEntriesTest {

    /** A namespace of this class alone: a feature contribution is process-wide. */
    private static final String NAMESPACE = "ownerformentries_test";

    private static final String PAID = "{ \"Factor\": \"" + NAMESPACE + ":feature\", \"Param\": \"Paid\", \"Min\": 1 }";

    private AtomicBoolean paid;

    @BeforeEach
    void declareTheFeature() {
        paid = new AtomicBoolean(true);
        FeatureFlags.register(NAMESPACE, "Paid", "test", paid::get);
    }

    @AfterEach
    void forgetDeclaredFeatures() {
        FeatureFlags.reset();
    }

    @Test
    void theEntryWriterKeepsNumberTypesAndTheFilesSpellingAndRefusesABadId(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("boosts.json");
        Files.writeString(file, "{ \"$Comment\": \"keep me\", \"mmo_Boost_A\": { \"Percent\": 25.0, \"Other\": \"kept\" } }",
                StandardCharsets.UTF_8);
        AbstractKeyedAssetConfig<String> store = new AbstractKeyedAssetConfig<>() {
        };
        OwnerFileBinding binding = OwnerFileBinding.builder("test/boosts.json", () -> file)
                .entries(OwnerFileBinding.entriesOf(store, Codec.STRING)).build();
        OwnerFormField percent = new OwnerFormField(0, OwnerFormKind.NUMBER, "Percent", 0.0, 10000.0, List.of());
        OwnerFormField cooldown = new OwnerFormField(1, OwnerFormKind.WHOLE, "Stack.Cooldown", 0.0, null, List.of());

        Map<Integer, String> typed = new LinkedHashMap<>();
        typed.put(0, "50");
        typed.put(1, "1800");
        OwnerFormWrite.Collected collected = OwnerFormWrite.collect(List.of(percent, cooldown), typed);
        assertTrue(collected.ok(), "every value fits its field: " + collected.refusal());
        assertEquals(Double.valueOf(50.0), collected.leaves().get("Percent"));
        assertEquals(Integer.valueOf(1800), collected.leaves().get("Stack.Cooldown"));
        assertTrue(OwnerFormWrite.writeEntry(binding, "MMO_BOOST_A", collected.leaves()));

        String written = Files.readString(file, StandardCharsets.UTF_8);
        String flat = written.replaceAll("\\s+", "");
        assertTrue(flat.contains("\"mmo_Boost_A\":{\"Percent\":50.0"), "a decimal under the file's own spelling: "
                + written);
        assertTrue(flat.contains("\"Cooldown\":1800") && !written.contains("1800.0"), "a whole number is whole: "
                + written);
        assertFalse(written.contains("MMO_BOOST_A"), "no second entry that differs only in case: " + written);
        assertTrue(written.contains("\"$Comment\": \"keep me\"") && written.contains("\"Other\": \"kept\""),
                "every key the form does not write stays: " + written);

        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("Percent", 75.0);
        for (String bad : List.of("$Comment", "Mmo.Boost", "two words", "", " ", "_Leading")) {
            assertFalse(binding.acceptsEntryId(bad), "the store's id pattern refuses '" + bad + "'");
            assertFalse(OwnerFormWrite.writeEntry(binding, bad, leaf), "nothing is written under '" + bad + "'");
        }
        Map<String, Object> reserved = new LinkedHashMap<>();
        reserved.put("$Comment", "overwritten");
        assertFalse(OwnerFormWrite.writeEntry(binding, "mmo_boost_a", reserved), "a leaf that is no plain path");
        assertEquals(written, Files.readString(file, StandardCharsets.UTF_8), "a refused write changes nothing");

        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("Reusable", new LinkedHashMap<String, Object>());
        assertTrue(OwnerFormWrite.writeEntry(binding, "New_One", seed), "an added entry holds its Having path");
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).replaceAll("\\s+", "")
                .contains("\"New_One\":{\"Reusable\":{}}"));

        assertTrue(OwnerFormWrite.removeEntry(binding, "MMO_boost_a"), "the whole entry comes out, any casing");
        String removed = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(removed.contains("mmo_Boost_A"), removed);
        assertTrue(removed.contains("\"$Comment\": \"keep me\"") && removed.contains("New_One"), removed);
        assertTrue(OwnerFormWrite.removeEntry(binding, "Never_Written"), "nothing to take out is nothing left");

        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
        assertFalse(OwnerFormWrite.writeEntry(binding, "New_One", leaf), "a file that cannot be read is never written");
        assertFalse(OwnerFormWrite.removeEntry(binding, "New_One"));
        assertEquals("{ not json", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void theGateHidesAnEntryWhereItsFeatureIsOffAndAPermissionAloneHidesNothing() {
        OwnerFormAsset.Entries spec = new OwnerFormAsset.Entries();
        spec.having = "Reusable";
        spec.gate = "Reusable.Requires";
        BsonDocument gated = BsonDocument.parse("{ \"Reusable\": { \"Requires\": { \"Permission\": \"some.node\", "
                + "\"Factors\": [ " + PAID + " ] } } }");
        BsonDocument permissionOnly = BsonDocument.parse(
                "{ \"Reusable\": { \"Requires\": { \"Permission\": \"some.node\" } } }");
        BsonDocument open = BsonDocument.parse("{ \"Reusable\": { } }");
        BsonDocument notReusable = BsonDocument.parse("{ \"Percent\": 50.0 }");

        assertTrue(OwnerFormEntries.shows(spec, gated), "the feature is on");
        assertTrue(OwnerFormEntries.shows(spec, permissionOnly));
        assertTrue(OwnerFormEntries.shows(spec, open));
        assertFalse(OwnerFormEntries.shows(spec, notReusable), "an entry without the Having path is not listed");

        paid.set(false);
        assertFalse(OwnerFormEntries.shows(spec, gated), "a switched-off feature hides the entry, read live");
        assertTrue(OwnerFormEntries.shows(spec, permissionOnly), "a permission is the player's, never asked here");
        assertTrue(OwnerFormEntries.shows(spec, open));

        spec.gate = "Reusable..Requires";
        assertFalse(OwnerFormEntries.shows(spec, open), "a Gate that is no path shows nothing, never everything");
    }
}
