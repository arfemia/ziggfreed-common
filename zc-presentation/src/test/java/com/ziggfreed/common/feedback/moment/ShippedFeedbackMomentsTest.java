package com.ziggfreed.common.feedback.moment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The library's OWN default moment files: every one decodes through the real codec, every line
 * they author reads a key the library's own lang file ships, and the set covers exactly the moments
 * the library's engines announce.
 *
 * <p>These files are what a bare server runs on, so a typo in one is a silent blank toast on every
 * such server; and they are the files a consumer copies to override, so their shape had better be
 * the shape the codec reads.
 */
class ShippedFeedbackMomentsTest {

    private static final Path MOMENTS = Path.of("src", "main", "resources", "Server",
            "ZiggfreedCommon", "FeedbackMoments");

    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages",
            "en-US", "ziggfreedcommon.feedback.lang");

    /** The lang file's own namespace, which every shipped line writes out in full. */
    private static final String PREFIX = "ziggfreedcommon.feedback.";

    /** The moments the two progression engines and the claim table announce today. */
    private static final Set<String> ANNOUNCED = Set.of(
            "Quest_Completed", "Quest_Parked", "Quest_Claimed", "Quest_Objective_Progressed",
            "Achievement_Unlocked", "Achievement_Claimed", "Achievement_Server_First_Lost");

    @Test
    void everyAnnouncedMomentShipsADefaultThatDecodes() throws IOException {
        Map<String, FeedbackMomentAsset> shipped = shipped();

        assertEquals(new TreeSet<>(ANNOUNCED), new TreeSet<>(shipped.keySet()),
                "one default file per announced moment, no more and no fewer");
        for (Map.Entry<String, FeedbackMomentAsset> entry : shipped.entrySet()) {
            FeedbackMomentAsset asset = entry.getValue();
            boolean saysSomething = asset.getToast() != null || asset.getSound() != null
                    || asset.getBroadcast() != null || asset.getCommand() != null
                    || asset.getVariants().length > 0;
            assertTrue(saysSomething, entry.getKey() + " ships a file that does nothing");
        }
    }

    @Test
    void everyShippedLineReadsAKeyTheShippedLangFileCarries() throws IOException {
        Set<String> english = englishKeys();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, FeedbackMomentAsset> entry : shipped().entrySet()) {
            for (FeedbackMomentAsset.Line line : lines(entry.getValue())) {
                String key = line.getKey();
                if (key == null) {
                    problems.add(entry.getKey() + ": a line with no Key (a KeyArg-only line has no"
                            + " default wording, which a library default must have)");
                    continue;
                }
                if (!key.startsWith(PREFIX)) {
                    problems.add(entry.getKey() + ": '" + key + "' is not written out in full under "
                            + PREFIX + ", so it would be resolved through a consumer's catalogue");
                    continue;
                }
                if (!english.contains(key.substring(PREFIX.length()))) {
                    problems.add(entry.getKey() + ": '" + key + "' is not in " + ENGLISH);
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    /** The parked default branches on the reason the engine carries, so ONE file says both things. */
    @Test
    void theParkedDefaultSaysSomethingDifferentForAFullBag() throws IOException {
        FeedbackMomentAsset parked = shipped().get("Quest_Parked");
        assertNotNull(parked);

        FeedbackMomentAsset.Resolved full = parked.resolve(Map.of("reason", "no_space"));
        FeedbackMomentAsset.Resolved collect = parked.resolve(Map.of("reason", "collect"));

        assertFalse(full.toast().getTitle().getKey().equals(collect.toast().getTitle().getKey()),
                "a full bag reads a different line from a quest waiting to be collected");
    }

    // ==================== helpers ====================

    @Nonnull
    private static Map<String, FeedbackMomentAsset> shipped() throws IOException {
        assertTrue(Files.isDirectory(MOMENTS), "missing " + MOMENTS.toAbsolutePath());
        Map<String, FeedbackMomentAsset> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(MOMENTS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - ".json".length());
                AssetExtraInfo.Data data = new AssetExtraInfo.Data(FeedbackMomentAsset.class, id, null);
                FeedbackMomentAsset asset = FeedbackMomentAsset.CODEC.decodeAndInheritJsonAsset(
                        RawJsonReader.fromJsonString(Files.readString(file, StandardCharsets.UTF_8)),
                        null, new AssetExtraInfo<>(data));
                out.put(id, asset);
            }
        }
        return out;
    }

    @Nonnull
    private static List<FeedbackMomentAsset.Line> lines(@Nonnull FeedbackMomentAsset asset) {
        List<FeedbackMomentAsset.Line> out = new ArrayList<>();
        addLines(out, asset.getToast() == null ? null : asset.getToast().getTitle(),
                asset.getToast() == null ? null : asset.getToast().getSecondary());
        addLines(out, asset.getBroadcast() == null ? null : asset.getBroadcast().getTitle(),
                asset.getBroadcast() == null ? null : asset.getBroadcast().getSecondary());
        for (FeedbackMomentAsset.Variant variant : asset.getVariants()) {
            FeedbackMomentAsset.Resolved resolved = new FeedbackMomentAsset.Resolved(
                    variant.toast, variant.broadcast, variant.sound, variant.command);
            addLines(out, resolved.toast() == null ? null : resolved.toast().getTitle(),
                    resolved.toast() == null ? null : resolved.toast().getSecondary());
            addLines(out, resolved.broadcast() == null ? null : resolved.broadcast().getTitle(),
                    resolved.broadcast() == null ? null : resolved.broadcast().getSecondary());
        }
        return out;
    }

    private static void addLines(@Nonnull List<FeedbackMomentAsset.Line> out,
            @Nullable FeedbackMomentAsset.Line... lines) {
        for (FeedbackMomentAsset.Line line : lines) {
            if (line != null) {
                out.add(line);
            }
        }
    }

    @Nonnull
    private static Set<String> englishKeys() throws IOException {
        assertTrue(Files.isRegularFile(ENGLISH), "missing " + ENGLISH.toAbsolutePath());
        Set<String> keys = new TreeSet<>();
        for (String raw : Files.readAllLines(ENGLISH, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || eq < 0) {
                continue;
            }
            keys.add(line.substring(0, eq).trim());
        }
        return keys;
    }
}
