package com.ziggfreed.common.objectives.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.entity.overhead.OverheadIndicatorAsset;
import com.ziggfreed.common.quest.asset.QuestSituation;

/**
 * The looks this library ships: every state the shipped global word can show (each situation's state,
 * for a one-off and for a repeating quest) has exactly one look file, each look floats an item, and
 * each item is a marker shipped here (a Parent child of the pictured item carrying its own halo).
 * Which picture, how big and how high are the files' business, not asserted here.
 */
class ShippedQuestLooksTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources", "Server");
    private static final Path WORD = RESOURCES.resolve(Path.of("ZiggfreedCommon", "QuestIndicators", "Default.json"));
    private static final Path LOOKS = RESOURCES.resolve(Path.of("ZiggfreedCommon", "OverheadIndicators"));
    private static final Path ITEMS = RESOURCES.resolve(Path.of("Item", "Items"));

    @Test
    void theShippedLooksAreExactlyTheStatesTheShippedWordCanShowAndEachFloatsAnItem() throws IOException {
        QuestIndicatorAsset word = QuestIndicatorAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(Files.readString(WORD, StandardCharsets.UTF_8)), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(QuestIndicatorAsset.class, "Default", null)));
        Set<String> reachable = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (QuestSituation situation : QuestSituation.values()) {
            reachable.add(word.resolve(situation, false).state());
            reachable.add(word.resolve(situation, true).state());
        }

        Map<String, OverheadIndicatorAsset> looks = shippedLooks();
        Set<String> shipped = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        shipped.addAll(looks.keySet());
        assertEquals(reachable, shipped, "one look per state the shipped word can show: an extra file is a "
                + "state nothing shows by default, a missing one leaves a situation with no picture");
        for (Map.Entry<String, OverheadIndicatorAsset> look : looks.entrySet()) {
            assertTrue(look.getValue().hasLook(), look.getKey() + " names no picture");
            assertNotNull(look.getValue().getIcon().itemId(), look.getKey() + " floats an item");
        }
    }

    @Test
    void everyItemAShippedLookFloatsIsAMarkerItemShippedHere() throws IOException {
        Map<String, Path> items = shippedItems();
        for (Map.Entry<String, OverheadIndicatorAsset> look : shippedLooks().entrySet()) {
            String itemId = look.getValue().getIcon().itemId();
            Path file = items.get(itemId);
            assertNotNull(file, look.getKey() + " floats '" + itemId + "', which no file under " + ITEMS + " ships");
            JsonObject item = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(item.has("Parent") && !item.get("Parent").getAsString().isBlank(),
                    itemId + " must be a Parent child of the item whose picture it borrows");
            assertTrue(item.has("ItemEntity") && item.getAsJsonObject("ItemEntity").has("ParticleSystemId")
                            && !item.getAsJsonObject("ItemEntity").get("ParticleSystemId").getAsString().isBlank(),
                    itemId + " names no ItemEntity.ParticleSystemId, the one leaf a marker item exists to carry");
        }
    }

    @Nonnull
    private static Map<String, OverheadIndicatorAsset> shippedLooks() throws IOException {
        assertTrue(Files.isDirectory(LOOKS), "missing " + LOOKS.toAbsolutePath());
        Map<String, OverheadIndicatorAsset> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(LOOKS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - ".json".length());
                out.put(id, OverheadIndicatorAsset.CODEC.decodeAndInheritJsonAsset(
                        RawJsonReader.fromJsonString(Files.readString(file, StandardCharsets.UTF_8)), null,
                        new AssetExtraInfo<>(new AssetExtraInfo.Data(OverheadIndicatorAsset.class, id, null))));
            }
        }
        return out;
    }

    @Nonnull
    private static Map<String, Path> shippedItems() throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(ITEMS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String name = file.getFileName().toString();
                out.put(name.substring(0, name.length() - ".json".length()), file);
            }
        }
        return out;
    }
}
