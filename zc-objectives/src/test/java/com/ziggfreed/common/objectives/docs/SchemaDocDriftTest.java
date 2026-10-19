package com.ziggfreed.common.objectives.docs;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Fails the build when the committed schema reference ({@code SCHEMA.md} at this module's root) lags the native
 * objective Types' codecs: zc-progression's {@code SchemaDocDriftTest}, over {@link ObjectivesSchemaDocs}. Tagged
 * {@code engine-items}: the walk reaches the engine's own task base, whose codecs build engine items that log at
 * class init.
 *
 * <p>{@link ObjectivesSchemaDocs#DOCUMENT}'s {@code render()} is the single source of truth for both the committed
 * file (regenerated with {@code :zc-objectives:generateSchemaDocs}) and this comparison, so the two can never drift
 * from the codecs independently, only from each other, which is exactly what this catches.
 */
@Tag("engine-items")
class SchemaDocDriftTest {

    private static final Path SCHEMA_FILE = Path.of("SCHEMA.md");

    /** Compares on LF alone, so a checkout handing out platform line endings never fails on what no one wrote. */
    private static String normalized(String text) {
        return text.replace("\r\n", "\n");
    }

    @Test
    void committedSchemaMdMatchesTheLiveCodecs() throws IOException {
        String expected = normalized(ObjectivesSchemaDocs.DOCUMENT.render());

        if (!Files.exists(SCHEMA_FILE)) {
            fail("Missing " + SCHEMA_FILE.toAbsolutePath()
                    + " - run `:zc-objectives:generateSchemaDocs` through the lane and commit the result.");
            return;
        }

        String actual = normalized(Files.readString(SCHEMA_FILE, StandardCharsets.UTF_8));
        assertTrue(expected.equals(actual),
                () -> SCHEMA_FILE + " is stale (drifted from the live codecs). "
                        + "Run `:zc-objectives:generateSchemaDocs` through the lane and commit the regenerated file.\n"
                        + "Expected length=" + expected.length() + ", committed length=" + actual.length());
    }

    @Test
    void everyRootTypeRendersAtLeastOneField() {
        Map<String, Object> model = ObjectivesSchemaDocs.DOCUMENT.renderModel();
        @SuppressWarnings("unchecked")
        Map<String, Object> types = (Map<String, Object>) model.get("types");

        assertTrue(types.size() == ObjectivesSchemaDocs.DOCUMENT.rootTypeNames().size(),
                "expected one rendered entry per registered root type");

        for (String name : ObjectivesSchemaDocs.DOCUMENT.rootTypeNames()) {
            Object typeDoc = types.get(name);
            assertTrue(typeDoc instanceof Map, name + " did not render");
            @SuppressWarnings("unchecked")
            List<Object> fields = (List<Object>) ((Map<String, Object>) typeDoc).get("fields");
            assertTrue(fields != null && !fields.isEmpty(), name + " rendered zero fields");
        }
    }
}
