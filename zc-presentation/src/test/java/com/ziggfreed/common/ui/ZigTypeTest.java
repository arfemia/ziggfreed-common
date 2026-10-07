package com.ziggfreed.common.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * {@code Common/ZigType.ui} and {@link ZigType} name the same steps with the same numbers, and no step is under
 * the floor but the one named HUD exception: a document reads its sizes from the one, Java from the other, so they
 * must never drift apart.
 */
class ZigTypeTest {

    private static final String DOCUMENT = "/Common/UI/Custom/Common/ZigType.ui";
    private static final Pattern STEP = Pattern.compile("^@ZigFont([A-Za-z]+)\\s*=\\s*(\\d+)\\s*;", Pattern.MULTILINE);

    @Test
    void theDocumentAndTheJavaNameTheSameStepsWithTheSameSizes() throws Exception {
        Map<String, Integer> steps = steps();
        assertTrue(steps.size() >= 4, "ZigType.ui names its steps: " + steps);
        for (Map.Entry<String, Integer> step : steps.entrySet()) {
            Field field = ZigType.class.getField(constantName(step.getKey()));
            assertEquals(step.getValue().intValue(), field.getInt(null), "@ZigFont" + step.getKey());
        }
        int constants = 0;
        for (Field field : ZigType.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !field.getName().equals("FLOOR")) {
                constants++;
            }
        }
        assertEquals(steps.size(), constants, "every ZigType constant but FLOOR is a step the document names");
    }

    @Test
    void noStepIsUnderTheFloorButMicro() throws IOException {
        assertEquals(ZigType.CAPTION, ZigType.FLOOR, "the caption is the floor");
        for (Map.Entry<String, Integer> step : steps().entrySet()) {
            if (!step.getKey().equals("Micro")) {
                assertTrue(step.getValue() >= ZigType.FLOOR, "@ZigFont" + step.getKey() + " is under the floor");
            }
        }
        assertTrue(ZigType.MICRO < ZigType.FLOOR, "Micro is the one named exception under the floor");
        assertEquals(13, ZigType.FLOOR, "the maintainer's ruling: no player text under 13");
        assertEquals(14, ZigType.BODY, "the maintainer's ruling: row labels and body text at 14");
    }

    @Test
    void theHeroStepIsTheLargest() throws IOException {
        Map<String, Integer> steps = steps();
        assertEquals(Integer.valueOf(28), steps.get("Hero"), "@ZigFontHero, the Almanac hero's season name");
        for (Map.Entry<String, Integer> step : steps.entrySet()) {
            assertTrue(step.getValue() <= steps.get("Hero"), "@ZigFont" + step.getKey() + " is no larger than the hero");
        }
    }

    /** {@code DisplayLarge} names the constant {@code DISPLAY_LARGE}. */
    private static String constantName(String step) {
        return step.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static Map<String, Integer> steps() throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        Matcher m = STEP.matcher(document());
        while (m.find()) {
            out.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        return out;
    }

    private static String document() throws IOException {
        try (InputStream in = ZigTypeTest.class.getResourceAsStream(DOCUMENT)) {
            assertNotNull(in, "the classpath ships " + DOCUMENT);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
