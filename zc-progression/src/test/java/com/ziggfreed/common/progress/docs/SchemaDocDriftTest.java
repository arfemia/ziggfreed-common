package com.ziggfreed.common.progress.docs;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Fails the build when the committed schema reference ({@code SCHEMA.md} at this module's root) lags
 * the actual asset codecs.
 *
 * <p>{@link SchemaDocWriter#render()} is the single source of truth for both the committed file
 * (regenerated with {@code gradlew :zc-progression:generateSchemaDocs}) and this comparison, so the
 * two can never independently drift from the codecs - only from each other, which is exactly the
 * condition this test catches. Add a field to a quest or an achievement, forget the regeneration,
 * and the build says so before an author reads a reference that is missing it.
 */
class SchemaDocDriftTest {

    private static final Path SCHEMA_FILE = Path.of("SCHEMA.md");

    /**
     * Compares on LF alone. The reference is a committed text file, and a checkout on a machine
     * configured to hand out platform line endings would otherwise fail this test for a difference
     * nobody wrote and no reader can see.
     */
    private static String normalized(String text) {
        return text.replace("\r\n", "\n");
    }

    @Test
    void committedSchemaMdMatchesTheLiveCodecs() throws IOException {
        String expected = normalized(SchemaDocWriter.render());

        if (!Files.exists(SCHEMA_FILE)) {
            fail("Missing " + SCHEMA_FILE.toAbsolutePath()
                    + " - run `gradlew :zc-progression:generateSchemaDocs` and commit the result.");
            return;
        }

        String actual = normalized(Files.readString(SCHEMA_FILE, StandardCharsets.UTF_8));
        assertTrue(expected.equals(actual),
                () -> SCHEMA_FILE + " is stale (drifted from the live asset codecs). "
                        + "Run `gradlew :zc-progression:generateSchemaDocs` and commit the regenerated file.\n"
                        + "Expected length=" + expected.length() + ", committed length=" + actual.length());
    }

    @Test
    void everyRootTypeRendersAtLeastOneField() {
        Map<String, Object> model = SchemaDocWriter.renderModel();
        @SuppressWarnings("unchecked")
        Map<String, Object> types = (Map<String, Object>) model.get("types");

        assertTrue(types.size() == SchemaDocWriter.rootTypeNames().size(),
                "expected one rendered entry per registered root type");

        for (String name : SchemaDocWriter.rootTypeNames()) {
            Object typeDoc = types.get(name);
            assertTrue(typeDoc instanceof Map, name + " did not render");
            @SuppressWarnings("unchecked")
            List<Object> fields = (List<Object>) ((Map<String, Object>) typeDoc).get("fields");
            assertTrue(fields != null && !fields.isEmpty(), name + " rendered zero fields");
        }
    }

    /**
     * A shared group is documented ONCE and linked to from everywhere it is reused. If the writer
     * ever stopped recognising a registered type as a root, every consumer of it would silently gain
     * its own inlined copy - a reference that says the same thing four times and drifts in three of
     * them the next time somebody edits one.
     */
    @Test
    void aSharedGroupIsLinkedRatherThanInlined() {
        String rendered = SchemaDocWriter.render();

        assertTrue(rendered.contains("| `Text` | [ContentText](#type-contenttext) |"),
                "the Text group should link to its own section rather than being inlined");
        assertTrue(rendered.contains("| `Requires` | [Requires](#type-requires) |"),
                "the Requires block should link to its own section rather than being inlined");
        assertTrue(rendered.contains("array of [RewardEntry](#type-rewardentry)"),
                "a reward list should link to the shared reward entry section");
    }
}
