package com.ziggfreed.common.objectives.flair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.feedback.moment.FeedbackMomentAsset;

/**
 * Every line the flair family says has to have somewhere to resolve from: the {@code /zigflair}
 * answers and help lines in the admin file, and the toast the shipped {@code Flair_Unlocked} moment
 * draws in the player-facing file. A missing key is the failure a command surface cannot have -
 * nothing throws, and a reader is shown the raw key - so this asks the question that CAN be answered
 * without a server, by reading the sources and the shipped English files. The same guard the
 * progression family carries, on the same terms.
 */
class FlairAdminKeysTest {

    private static final Path SOURCE_DIR = Path.of("src", "main", "java", "com", "ziggfreed",
            "common", "objectives", "flair");

    private static final Path LANGUAGES = Path.of("src", "main", "resources", "Server", "Languages");

    private static final Path ADMIN_ENGLISH = LANGUAGES.resolve("en-US").resolve("ziggfreedcommon.flair.admin.lang");

    private static final Path PLAYER_ENGLISH = LANGUAGES.resolve("en-US").resolve("ziggfreedcommon.flair.lang");

    private static final Path UNLOCKED_MOMENT = Path.of("src", "main", "resources", "Server",
            "ZiggfreedCommon", "FeedbackMoments", FlairText.UNLOCKED_MOMENT + ".json");

    /** The files that hold the prefixes and the command names themselves, not keys. */
    private static final Set<String> NOT_SCANNED = Set.of("FlairAdminMessages.java", "FlairCommandLine.java");

    private static final Pattern DOTTED_LITERAL =
            Pattern.compile("\"([a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+)\"");

    private static final Pattern DESCRIPTION = Pattern.compile("desc\\(\"([^\"]+)\"\\)");

    private static final Pattern DESCRIPTION_OF_VERB = Pattern.compile("desc\\(verb\\)");

    @Test
    @DisplayName("every key the commands say is in the shipped English admin file")
    void everySpokenKeyExists() throws IOException {
        Set<String> shipped = shippedKeys(ADMIN_ENGLISH);
        Set<String> descriptionArgs = new LinkedHashSet<>();
        Set<String> spoken = new TreeSet<>();
        boolean verbsDescribedByName = false;

        for (Path source : sources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Matcher descriptions = DESCRIPTION.matcher(text);
            while (descriptions.find()) {
                descriptionArgs.add(descriptions.group(1));
                spoken.add("desc." + descriptions.group(1));
            }
            verbsDescribedByName |= DESCRIPTION_OF_VERB.matcher(text).find();
            Matcher literals = DOTTED_LITERAL.matcher(text);
            while (literals.find()) {
                spoken.add(literals.group(1));
            }
        }
        spoken.removeAll(descriptionArgs);
        assertTrue(verbsDescribedByName, "the verbs are described through desc(verb), checked below");

        assertTrue(spoken.size() > 8, "the scan found almost nothing, so it is not scanning");
        List<String> missing = new ArrayList<>();
        for (String key : spoken) {
            if (!shipped.contains(key)) {
                missing.add(key);
            }
        }
        assertEquals(List.of(), missing, "keys the commands say with nothing to resolve them from");
    }

    @Test
    @DisplayName("the family, every verb and every argument has a help line of its own")
    void everyVerbIsDescribed() throws IOException {
        Set<String> shipped = shippedKeys(ADMIN_ENGLISH);
        List<String> missing = new ArrayList<>();
        for (String key : List.of("desc.family", "desc." + FlairCommandLine.GRANT,
                "desc." + FlairCommandLine.REVOKE, "desc." + FlairCommandLine.LIST,
                "desc.arg." + FlairCommandLine.ARG_PLAYER, "desc.arg." + FlairCommandLine.ARG_FLAIR)) {
            if (!shipped.contains(key)) {
                missing.add(key);
            }
        }
        assertEquals(List.of(), missing, "the engine resolves a command description as a KEY, so a "
                + "verb without one shows the reader raw text nobody translated");
    }

    @Test
    @DisplayName("the shipped unlock moment draws a line the player-facing file carries")
    void theUnlockMomentReadsAShippedKey() throws IOException {
        assertTrue(Files.isRegularFile(UNLOCKED_MOMENT), "missing " + UNLOCKED_MOMENT.toAbsolutePath());
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(FeedbackMomentAsset.class,
                FlairText.UNLOCKED_MOMENT, null);
        FeedbackMomentAsset moment = FeedbackMomentAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(Files.readString(UNLOCKED_MOMENT, StandardCharsets.UTF_8)),
                null, new AssetExtraInfo<>(data));

        assertNotNull(moment.getToast(), "the default says something");
        assertNotNull(moment.getToast().getTitle());
        String key = moment.getToast().getTitle().getKey();
        assertNotNull(key);
        assertTrue(key.startsWith(FlairText.PREFIX), "the toast line is the flair family's own");
        assertTrue(shippedKeys(PLAYER_ENGLISH).contains(key.substring(FlairText.PREFIX.length())),
                "the moment names a line the player-facing file does not ship: " + key);
        assertEquals(List.of(FlairText.NAME_ARG), List.of(moment.getToast().getTitle().getArgs()),
                "the line's one blank is the flair's localized name, which the write passes under that name");
    }

    @Test
    @DisplayName("each key prefix is its shipped file's own name")
    void thePrefixesMatchTheFileNames() {
        assertEquals(prefixOf(ADMIN_ENGLISH), FlairAdminMessages.PREFIX);
        assertEquals(prefixOf(PLAYER_ENGLISH), FlairText.PREFIX);
    }

    private static String prefixOf(Path langFile) {
        String fileName = langFile.getFileName().toString();
        return fileName.substring(0, fileName.length() - ".lang".length()) + ".";
    }

    private static List<Path> sources() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_DIR), "missing " + SOURCE_DIR.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(SOURCE_DIR)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !NOT_SCANNED.contains(p.getFileName().toString()))
                    .sorted()
                    .toList();
        }
    }

    private static Set<String> shippedKeys(Path langFile) throws IOException {
        assertTrue(Files.isRegularFile(langFile), "missing " + langFile.toAbsolutePath());
        Set<String> keys = new TreeSet<>();
        for (String line : Files.readAllLines(langFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                continue;
            }
            keys.add(trimmed.substring(0, eq).trim());
        }
        return keys;
    }
}
