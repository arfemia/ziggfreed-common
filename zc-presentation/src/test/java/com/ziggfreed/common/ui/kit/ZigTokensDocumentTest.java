package com.ziggfreed.common.ui.kit;

import static com.ziggfreed.common.ui.kit.KitDocs.DOCUMENTS;
import static com.ziggfreed.common.ui.kit.KitDocs.document;
import static com.ziggfreed.common.ui.kit.KitDocs.namedValues;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * {@code Common/ZigTokens.ui} is the kit's one home for a colour or a named size: every other kit document reads
 * its colours from it ({@code $ZK.@ZigInkBody}), never spelling a hex of its own, so a contrast test can measure
 * every colour a kit page draws and a theme has one place to differ; and {@code ZigTokens} (the Java mirror the
 * painters and the contrast test read) names the same tokens with the same values. The named sizes are the seam
 * the templates and the page layouts share (plan section 2.4).
 */
class ZigTokensDocumentTest {

    private static final String TOKENS = "Common/ZigTokens.ui";

    /** A colour literal as a document spells it: {@code #rrggbb}, optionally with vanilla's alpha {@code (a)}. */
    private static final Pattern COLOUR = Pattern.compile("(?<![0-9A-Za-z])#[0-9a-fA-F]{6}(?:\\([0-9.]+\\))?(?![0-9A-Za-z])");

    @Test
    void theDocumentAndTheJavaMirrorNameTheSameColours() throws Exception {
        assertEquals(colours(), mirror("colours"),
                "Common/ZigTokens.ui and ZigTokens.colours() name the same colour tokens with the same spellings");
    }

    @Test
    void theDocumentAndTheJavaMirrorNameTheSameSizes() throws Exception {
        Map<String, Integer> documented = new LinkedHashMap<>();
        for (Map.Entry<String, String> value : namedValues(document(TOKENS)).entrySet()) {
            if (value.getValue().matches("\\d+")) {
                documented.put(value.getKey(), Integer.parseInt(value.getValue()));
            }
        }
        Map<String, Object> java = mirror("integers");
        Map<String, Integer> mirrored = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : java.entrySet()) {
            mirrored.put(entry.getKey(), ((Number) entry.getValue()).intValue());
        }
        assertEquals(documented, mirrored, "Common/ZigTokens.ui and ZigTokens.integers() name the same sizes");
    }

    @Test
    void theNamedSizesAreTheSeamTheTemplatesAndLayoutsShare() throws IOException {
        Map<String, String> values = namedValues(document(TOKENS));
        Map<String, Integer> expected = new LinkedHashMap<>();
        int[] space = {4, 8, 12, 16, 24, 32};
        for (int i = 0; i < space.length; i++) {
            expected.put("ZigSpace" + (i + 1), space[i]);
        }
        expected.put("ZigRowHeight", 56);
        expected.put("ZigCompactRowHeight", 44);
        expected.put("ZigSectionHeadHeight", 32);
        expected.put("ZigLineHeight", 32);
        expected.put("ZigControlHeight", 32);
        expected.put("ZigActionHeight", 40);
        expected.put("ZigHeaderHeight", 60);
        expected.put("ZigPillHeight", 24);
        expected.put("ZigPicInline", 20);
        expected.put("ZigPicLine", 28);
        expected.put("ZigPicRow", 32);
        expected.put("ZigPicTile", 40);
        expected.put("ZigPicHeader", 48);
        expected.put("ZigPicLarge", 64);
        for (Map.Entry<String, Integer> size : expected.entrySet()) {
            assertEquals(String.valueOf(size.getValue()), values.get(size.getKey()), "@" + size.getKey());
        }
        assertTrue(Integer.parseInt(values.get("ZigPicLarge")) <= 64,
                "item icons are 64px, so no picture rung draws one larger");
    }

    @Test
    void everyTokenIsAColourOrASize() throws IOException {
        for (Map.Entry<String, String> value : namedValues(document(TOKENS)).entrySet()) {
            String v = value.getValue();
            assertTrue(v.matches("\\d+") || COLOUR.matcher(v).matches(),
                    "@" + value.getKey() + " is a #rrggbb, a #rrggbb(a) or a whole number: " + v);
            assertTrue(value.getKey().startsWith("Zig"), "@" + value.getKey() + " carries the family's prefix");
        }
        assertTrue(colours().keySet().containsAll(List.of("ZigInkBright", "ZigInkStrong", "ZigInkBody", "ZigInkMuted",
                "ZigInkFaint", "ZigInkSection", "ZigInkHeading", "ZigSurfacePane", "ZigSurfaceRow", "ZigSurfaceRowHover",
                "ZigSurfaceRowPressed", "ZigSurfaceRowSelected", "ZigSurfaceScrim", "ZigDivider", "ZigAccent")),
                "the inks, surfaces, divider and accent of section 2.3");
        for (String tone : List.of("Active", "Collect", "Done", "Live", "Available", "Waiting", "Blocked", "Danger")) {
            assertTrue(colours().containsKey("ZigTone" + tone + "Fill"), "a fill for " + tone);
            assertTrue(colours().containsKey("ZigTone" + tone + "Text"), "a text colour for " + tone);
        }
    }

    @Test
    void noOtherKitDocumentSpellsAColour() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String path : DOCUMENTS) {
            if (path.equals(TOKENS)) {
                continue;
            }
            Matcher colour = COLOUR.matcher(document(path));
            while (colour.find()) {
                bad.add(path + ": " + colour.group());
            }
        }
        assertTrue(bad.isEmpty(), "a kit document reads every colour from Common/ZigTokens.ui ($ZK.@Name), so the "
                + "contrast test measures it and a theme can change it in one place: " + bad);
    }

    @Test
    void everyKitDocumentThatReadsATokenImportsTheTokens() throws IOException {
        for (String path : DOCUMENTS) {
            String ui = document(path);
            if (ui.contains("$ZK.@")) {
                String expected = path.startsWith("Common/") ? "\"ZigTokens.ui\"" : "\"../Common/ZigTokens.ui\"";
                assertTrue(ui.contains("$ZK = " + expected + ";"), path + " imports the tokens as $ZK = " + expected);
            }
        }
        assertFalse(document(TOKENS).contains("$"), "the tokens import nothing: they are where every value starts");
    }

    /** The document's colour tokens, name (without its {@code @}) to spelling. */
    @Nonnull
    private static Map<String, String> colours() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> value : namedValues(document(TOKENS)).entrySet()) {
            if (value.getValue().startsWith("#")) {
                out.put(value.getKey(), value.getValue().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * One of the Java mirror's maps, read by reflection so this document test compiles on its own: the mirror is
     * {@code ZigTokens}, whose {@code colours()} and {@code integers()} key each token
     * by its document name without the {@code @}.
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mirror(@Nonnull String method) throws Exception {
        Class<?> tokens;
        try {
            tokens = Class.forName("com.ziggfreed.common.ui.kit.ZigTokens");
        } catch (ClassNotFoundException missing) {
            fail("the Java mirror com.ziggfreed.common.ui.kit.ZigTokens is not on the test classpath");
            throw missing;
        }
        Method read = tokens.getMethod(method);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : ((Map<String, ?>) read.invoke(null)).entrySet()) {
            Object value = entry.getValue();
            out.put(entry.getKey(), value instanceof String s ? s.toLowerCase(Locale.ROOT) : value);
        }
        return out;
    }
}
