package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * No library main source reads a block through a {@code World} or {@code WorldChunk} block accessor: a
 * call named {@code getBlock} or {@code getBlockType} that takes arguments, such as
 * {@code world.getBlock(x, y, z)} or {@code world.getBlockType(x, y, z)}. Nor does one read a cell's
 * environment through a column accessor: a call named {@code getEnvironment} that takes arguments, such
 * as {@code column.getEnvironment(x, y, z)}.
 *
 * <p><b>Why it is worth a build failure.</b> Update 7 deletes the interfaces {@code World} read blocks
 * through, and the column's environment accessor with them. A jar built on the live server that calls
 * one throws {@code NoSuchMethodError} on an Update 7 server, and every site the library had sat inside a
 * catch that hid it: look rays read clear, surface probes returned their fallback, block-state sounds
 * went silent and {@code BuildPermission} let a protected environment pass, with nothing logged at the
 * default level. Read a block off its chunk section instead: zc-core's {@code world/SectionBlockCursor},
 * or zc-world's {@code BlockOps}; and a cell's environment off the section's {@code EnvironmentSection},
 * as {@code BuildPermission} does.
 *
 * <p><b>What is scanned</b>: every {@code .java} file under the wiring root's and every module's
 * {@code src/main/java}, found by walking, so a new module is covered the day it appears. Comments and
 * string literals are blanked first, so prose naming the old accessors never trips it. A
 * {@code getBlockType()} or {@code getEnvironment()} with no arguments (a block event's or an entity's
 * own accessor) is not a cell read and passes. There is no escape marker: the accessors are gone on
 * Update 7, so no call can be kept. A {@code javap} linkage check against the server jar is the complete
 * check; this scan keeps the commonest forms from coming back between those runs.
 */
class BlockAccessorHygieneTest {

    /** A {@code getBlock(} or {@code getBlockType(} member call whose argument list is not empty. */
    private static final Pattern WORLD_BLOCK_READ = Pattern.compile("\\.getBlock(?:Type)?\\s*\\(\\s*[^)\\s]");

    /** A {@code getEnvironment(} member call whose argument list is not empty: a cell's environment through a column. */
    private static final Pattern COLUMN_ENVIRONMENT_READ = Pattern.compile("\\.getEnvironment\\s*\\(\\s*[^)\\s]");

    @Test
    void noLibraryMainSourceReadsABlockThroughAWorldAccessor() throws IOException {
        List<String> hits = mainSourceHits(WORLD_BLOCK_READ);
        assertTrue(hits.isEmpty(), () -> hits.size() + " block read(s) through a World or WorldChunk accessor, which"
                + " Update 7 deletes. Read the block off its chunk section: zc-core's world/SectionBlockCursor"
                + " (SectionBlockCursor.of(world).blockId(x, y, z)) or zc-world's BlockOps.\n" + String.join("\n", hits));
    }

    @Test
    void noLibraryMainSourceReadsAnEnvironmentThroughAColumnAccessor() throws IOException {
        List<String> hits = mainSourceHits(COLUMN_ENVIRONMENT_READ);
        assertTrue(hits.isEmpty(), () -> hits.size() + " environment read(s) through a column accessor, which"
                + " Update 7 deletes. Read the cell's EnvironmentSection off its chunk section, as zc-world's"
                + " BuildPermission does (EnvironmentSection.get(x, y, z)).\n" + String.join("\n", hits));
    }

    /** Every code line of the library's main sources that {@code rule} matches, as {@code path:line  code}. */
    @Nonnull
    static List<String> mainSourceHits(@Nonnull Pattern rule) throws IOException {
        List<Path> sources = mainSources();
        assertFalse(sources.isEmpty(), "no main sources found under " + Path.of(".").toAbsolutePath());
        List<String> hits = new ArrayList<>();
        for (Path source : sources) {
            List<String> code = blankCommentsAndStrings(Files.readAllLines(source, StandardCharsets.UTF_8));
            for (int i = 0; i < code.size(); i++) {
                if (rule.matcher(code.get(i)).find()) {
                    hits.add(source + ":" + (i + 1) + "  " + code.get(i).trim());
                }
            }
        }
        return hits;
    }

    /** The wiring root's and every module's main sources, in a stable order so a failure reads the same twice. */
    @Nonnull
    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> walk = Files.walk(Path.of("."))) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> {
                        String normalized = p.toString().replace('\\', '/');
                        return normalized.contains("/src/main/java/") && !normalized.contains("/build/");
                    })
                    .sorted()
                    .toList();
        }
    }

    /** Blank out comments and string literals, line by line, keeping block-comment state across lines. */
    @Nonnull
    static List<String> blankCommentsAndStrings(@Nonnull List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        boolean inBlock = false;
        for (String line : raw) {
            StringBuilder sb = new StringBuilder();
            boolean inString = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
                if (inBlock) {
                    if (c == '*' && next == '/') {
                        inBlock = false;
                        i++;
                    }
                    continue;
                }
                if (inString) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '/' && next == '*') {
                    inBlock = true;
                    i++;
                    continue;
                }
                if (c == '/' && next == '/') {
                    break;
                }
                if (c == '"') {
                    inString = true;
                    continue;
                }
                sb.append(c);
            }
            out.add(sb.toString());
        }
        return out;
    }

    // ==================== fixtures: prove the rules work ====================

    private static int hits(String... lines) {
        return hitsOf(WORLD_BLOCK_READ, lines);
    }

    private static int hitsOf(@Nonnull Pattern rule, String... lines) {
        int n = 0;
        for (String line : blankCommentsAndStrings(List.of(lines))) {
            if (rule.matcher(line).find()) {
                n++;
            }
        }
        return n;
    }

    @Test
    void flagsAWorldOrChunkBlockRead() {
        assertEquals(1, hits("        int id = world.getBlock(bx, by, bz);"));
        assertEquals(1, hits("        BlockType base = world.getBlockType(x, y, z);"));
        assertEquals(1, hits("        int id = chunk.getBlock( x, y, z );"));
    }

    @Test
    void passesABlockEventsOwnAccessorAndTheSectionRead() {
        assertEquals(0, hits("        String blockId = event.getBlockType().getId();"));
        assertEquals(0, hits("        int id = SectionBlockCursor.of(world).blockId(x, y, z);"));
        assertEquals(0, hits("        BlockType state = base.getBlockForState(stateName);"));
        assertEquals(0, hits("        int id = blocks.get(x, y, z);"));
    }

    @Test
    void passesMentionsInCommentsJavadocAndStrings() {
        assertEquals(0, hits(
                "    // never world.getBlock(x, y, z) here",
                "    /**",
                "     * {@code World.getBlock(int, int, int)} is gone on Update 7.",
                "     */",
                "    String s = \"world.getBlockType(x, y, z)\";"));
    }

    @Test
    void flagsAColumnEnvironmentRead() {
        assertEquals(1, hitsOf(COLUMN_ENVIRONMENT_READ, "            int id = column.getEnvironment(x, y, z);"));
        assertEquals(1, hitsOf(COLUMN_ENVIRONMENT_READ, "        int id = chunk.getEnvironment( bx, by, bz );"));
    }

    @Test
    void passesTheSectionReadAnArgumentFreeAccessorAndProse() {
        assertEquals(0, hitsOf(COLUMN_ENVIRONMENT_READ,
                "            return environments == null ? null : environments.get(x, y, z);"));
        assertEquals(0, hitsOf(COLUMN_ENVIRONMENT_READ, "        int environment = npc.getEnvironment();"));
        assertEquals(0, hitsOf(COLUMN_ENVIRONMENT_READ,
                "    // never column.getEnvironment(x, y, z) here",
                "    /** {@code BlockChunk.getEnvironment(int, int, int)} is gone on Update 7. */",
                "    String s = \"chunk.getEnvironment(x, y, z)\";"));
    }
}
