package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The enforcement half of this module's agnosticism rule: a consumer's name, id prefixes, or domain
 * concepts may never appear in this engine's source - not in an identifier, not in a string, and not
 * in a comment. A comment is where it always creeps back in, one "for context" sentence at a time,
 * and a comment naming a specific consumer is how the next reader learns to write code for that
 * consumer.
 *
 * <p><b>Why these particular words.</b> The engine deliberately has no opinion about progression,
 * about what a reward or a currency is, or about how a consumer classifies its own content. A quest
 * carries free-form {@code tags} exactly so that classification rides THROUGH the engine without the
 * engine ever learning it - so naming any specific classification here is a design leak, not just a
 * wording slip. Forwarding a value without interpreting it is not a defence either: the value can be
 * generic while the name that carries it is not.
 *
 * <p><b>Deliberately NOT scanned.</b> {@code src/test} is out of scope - a fixture value is
 * author-owned, ships nothing, and a test naming a concrete id while proving a generic mechanism is
 * doing its job. The per-package router markdown is skipped for a structural reason rather than a
 * convenient one: it is the surface that STATES this rule, so it has to be able to quote the
 * vocabulary it forbids.
 *
 * <p><b>What to write instead</b> when a doc string genuinely needs an example: this engine's own
 * ids, or a plainly fictitious {@code yourmod:} namespace. A hit is a real finding, never a candidate
 * for an exception - there is no allowlist here on purpose.
 */
class QuestModuleAgnosticismTest {

    /** The one source root this module compiles and ships. */
    private static final Path ROOT = Path.of("src", "main", "java");

    /** File extensions worth reading under the root. */
    private static final List<String> SCANNED_EXTENSIONS = List.of(".java", ".json", ".lang", ".md");

    /** The per-package router filename: in-repo documentation, never shipped - see the class javadoc. */
    private static final String ROUTER_FILENAME = "claude.md";

    /**
     * The forbidden vocabulary. The namespace and the id prefix catch a direct reference; the
     * word-bounded rest catch the domain concepts that leak into prose - a progression currency, the
     * systems that award it, and a consumer's own content classification.
     */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "mmoskilltree|MMO_[A-Za-z]|\\bMMO\\b|\\bxp\\b|\\bskills?\\b|\\bexperience\\b"
                    + "|\\bleveling\\b|\\bbounty\\b|\\bbounties\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    void theEngineSourceNamesNoConsumerVocabulary() throws IOException {
        assertTrue(Files.isDirectory(ROOT), "missing scan root: " + ROOT.toAbsolutePath());

        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(QuestModuleAgnosticismTest::isScanned).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher match = FORBIDDEN.matcher(lines.get(i));
                    if (match.find()) {
                        hits.add(file + ":" + (i + 1) + ": [" + match.group() + "] " + lines.get(i).trim());
                    }
                }
            }
        }

        assertTrue(hits.isEmpty(), () -> "Consumer vocabulary reached this engine's source ("
                + hits.size() + " hit(s)). Rewrite each in the engine's own generic terms; use the"
                + " fictitious 'yourmod:' namespace for a third-party example.\n"
                + String.join("\n", hits));
    }

    private static boolean isScanned(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (ROUTER_FILENAME.equals(name)) {
            return false;
        }
        return SCANNED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}
