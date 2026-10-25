package com.ziggfreed.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.server.core.modules.i18n.parser.LangFileParser;

/**
 * Pure coverage for {@link GeneratedLangPack}'s file-writing core + the filename-prefix key
 * convention, plus the unregister decision core through the {@code PackRegistry} seam (an
 * in-memory registry standing in for the engine, which cannot be stood up in a unit JVM).
 * Registration ({@link GeneratedLangPack#registerZipPack}) needs a live {@code AssetModule} and
 * is engine-plumbing, untested here.
 */
class GeneratedLangPackTest {

    // --- storedKeyPrefix / storedKey: the M4 filename-minus-.lang prefix rule ---

    @Test
    void storedKeyPrefix_isFilenameMinusLangExtension() {
        assertEquals("server", GeneratedLangPack.storedKeyPrefix("server.lang"));
        assertEquals("client", GeneratedLangPack.storedKeyPrefix("client.lang"));
        // A filename WITHOUT the .lang extension is returned verbatim (defensive).
        assertEquals("server", GeneratedLangPack.storedKeyPrefix("server"));
    }

    @Test
    void storedKey_prependsThePrefixSoAServerLangEntryLandsUnderServer() {
        // items.X.description.zplain written into server.lang -> server.items.X.description.zplain,
        // the exact stored key the display-side lookup probes (the M4 fix's whole point).
        assertEquals("server.items.Weapon_Sword_Crude.description.zplain",
                GeneratedLangPack.storedKey("server.lang", "items.Weapon_Sword_Crude.description.zplain"));
    }

    // --- writeLocaleFiles: per-locale file shapes ---

    @Test
    void writeLocaleFiles_writesOneFilePerLocaleAtTheConventionPath(@TempDir Path root) throws IOException {
        GeneratedLangPack.writeLocaleFiles(root, "server.lang", perLocale(
                "en-US", entry("items.A.description.zplain", "A clean blade."),
                "de-DE", entry("items.A.description.zplain", "Eine saubere Klinge.")), null);

        Path en = root.resolve("Server").resolve("Languages").resolve("en-US").resolve("server.lang");
        Path de = root.resolve("Server").resolve("Languages").resolve("de-DE").resolve("server.lang");
        assertTrue(Files.isRegularFile(en));
        assertTrue(Files.isRegularFile(de));
        assertTrue(Files.readString(en, StandardCharsets.UTF_8)
                .contains("items.A.description.zplain = A clean blade."));
        assertTrue(Files.readString(de, StandardCharsets.UTF_8)
                .contains("items.A.description.zplain = Eine saubere Klinge."));
    }

    @Test
    void writeLocaleFiles_skipsEmptyValuedEntries(@TempDir Path root) throws IOException {
        GeneratedLangPack.writeLocaleFiles(root, "server.lang", perLocale(
                "en-US", entry("items.A.description.zplain", "", "items.B.description.zplain", "Kept.")), null);
        String content = Files.readString(
                root.resolve("Server").resolve("Languages").resolve("en-US").resolve("server.lang"),
                StandardCharsets.UTF_8);
        assertFalse(content.contains("items.A.description.zplain"), "empty value must be skipped");
        assertTrue(content.contains("items.B.description.zplain = Kept."));
    }

    @Test
    void writeLocaleFiles_isIdempotent_secondWriteReplacesWholesale(@TempDir Path root) throws IOException {
        Path file = root.resolve("Server").resolve("Languages").resolve("en-US").resolve("server.lang");

        GeneratedLangPack.writeLocaleFiles(root, "server.lang",
                perLocale("en-US", entry("items.A.description.zplain", "First.")), null);
        String first = Files.readString(file, StandardCharsets.UTF_8);

        // Same input a second time -> byte-identical file (idempotent boot regeneration).
        GeneratedLangPack.writeLocaleFiles(root, "server.lang",
                perLocale("en-US", entry("items.A.description.zplain", "First.")), null);
        assertEquals(first, Files.readString(file, StandardCharsets.UTF_8));

        // A CHANGED input replaces the file wholesale (no stale leftovers).
        GeneratedLangPack.writeLocaleFiles(root, "server.lang",
                perLocale("en-US", entry("items.B.description.zplain", "Second.")), null);
        String second = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(second.contains("items.A.description.zplain"), "stale entry must be gone");
        assertTrue(second.contains("items.B.description.zplain = Second."));
    }

    // --- escapeValue: the engine's LangFileParser grammar ---

    @Test
    void escapeValue_keepsBareMarkupButEscapesNewlinesAndTrimWrapsWhitespace() {
        // Inner double-quotes in a non-trim-wrapped value stay bare (a generated <color> row works).
        assertEquals("<color is=\"#FF0000\">Red</color>", GeneratedLangPack.escapeValue("<color is=\"#FF0000\">Red</color>"));
        assertEquals("a\\nb", GeneratedLangPack.escapeValue("a\nb"));
        // Leading whitespace forces quote-wrapping (one outer pair, which the parser strips).
        assertEquals("\" padded\"", GeneratedLangPack.escapeValue(" padded"));
    }

    /**
     * The file is read by the engine's own parser: every value must come back as written, and no
     * value may swallow the next entry (a trailing backslash is a line continuation there, and an
     * outer pair of quotes is stripped). The engine's parser logs at class init, hence the tag.
     */
    @Test
    @Tag("engine-items")
    void theEnginesParserReadsEveryValueBackAsWrittenAndNoEntryJoinsTheNext(@TempDir Path root) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("a.quoted", "\"The Hunt\"");
        values.put("b.padded", "  padded ");
        values.put("c.trailing", "ends in a backslash\\");
        values.put("d.lines", "one\ntwo\tthree");
        values.put("e.after", "still its own entry");
        GeneratedLangPack.writeLocaleFiles(root, "server.lang", Map.of("en-US", values), "a header");

        Path file = root.resolve("Server").resolve("Languages").resolve("en-US").resolve("server.lang");
        Map<String, String> engine;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            engine = LangFileParser.parse(file.toString(), reader);
        }
        assertEquals(values, engine);
        assertEquals(values, GeneratedLangPack.readLocaleFile(root, "server.lang", "en-US"));
    }

    // --- unregisterZipPack: the decision core through the PackRegistry seam ---

    /** In-memory stand-in for the engine's pack registry, counting engine-unregister calls. */
    private static final class FakeRegistry implements GeneratedLangPack.PackRegistry {
        final Map<String, Object> packs = new LinkedHashMap<>();
        int unregisterCalls;

        @Override
        public Object getAssetPack(String packId) {
            return packs.get(packId);
        }

        @Override
        public void unregisterPack(String packId) {
            unregisterCalls++;
            packs.remove(packId);
        }
    }

    @Test
    void unregister_afterARegistration_unregistersAndReturnsTrue() {
        FakeRegistry registry = new FakeRegistry();
        registry.packs.put("com.example:Overlay", new Object());

        assertTrue(GeneratedLangPack.unregisterZipPack(registry, "com.example", "Overlay"));
        assertTrue(registry.packs.isEmpty(), "the registered pack must be gone");
        assertEquals(1, registry.unregisterCalls);
    }

    @Test
    void doubleUnregister_isIdempotent_returnsFalseWithoutTouchingTheEngineAgain() {
        FakeRegistry registry = new FakeRegistry();
        registry.packs.put("com.example:Overlay", new Object());

        assertTrue(GeneratedLangPack.unregisterZipPack(registry, "com.example", "Overlay"));
        // Second call: nothing registered any more - false, and the engine's own unregister (which
        // WARNs on an unknown id) must NOT be called again.
        assertFalse(GeneratedLangPack.unregisterZipPack(registry, "com.example", "Overlay"));
        assertEquals(1, registry.unregisterCalls, "the engine unregister must run exactly once");
    }

    @Test
    void unregister_withNoAssetModule_returnsFalseWithoutThrowing() {
        // A null registry is the AssetModule-unavailable path (the live seam returns null then);
        // in this unit JVM the public overload takes exactly that path too.
        assertFalse(GeneratedLangPack.unregisterZipPack(null, "com.example", "Overlay"));
        assertFalse(GeneratedLangPack.unregisterZipPack("com.example", "Overlay"));
    }

    // --- fixtures ---

    private static Map<String, String> entry(String... keyThenValue) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keyThenValue.length; i += 2) {
            m.put(keyThenValue[i], keyThenValue[i + 1]);
        }
        return m;
    }

    private static Map<String, Map<String, String>> perLocale(Object... localeThenEntries) {
        Map<String, Map<String, String>> m = new LinkedHashMap<>();
        for (int i = 0; i < localeThenEntries.length; i += 2) {
            @SuppressWarnings("unchecked")
            Map<String, String> entries = (Map<String, String>) localeThenEntries[i + 1];
            m.put((String) localeThenEntries[i], entries);
        }
        return m;
    }
}
