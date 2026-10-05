package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * No library main source decides whether an entity stays in the world from a chunk COLUMN: neither by
 * reading the column's ticking flag ({@code ChunkFlag.TICKING}) nor by asking a request to tick
 * ({@code GetChunkFlags.SET_TICKING}) anywhere but the one section helper.
 *
 * <p><b>Why it is worth a build failure.</b> On Update 7 a chunk section loads asleep whatever its column
 * does, and an entity added into a sleeping section is parked on the spot: the add answers null, the
 * caller's post-spawn never runs, and the entity comes back on its own once a player walks near, so a
 * caller that tries again stands a second one beside it (break list X29: the hub NPC on a headless boot).
 * zc-world's {@code world/TickingSections} is the one place that reads and wakes a section.
 *
 * <p><b>The kept column reads.</b> {@code NpcPlacementService.isChunkLoaded} and {@code requestChunk} keep
 * their 2.2.0 descriptors and their column reads for a consumer that links them; nothing in the library
 * calls them, and this test holds that file to exactly those two lines.
 *
 * <p>Comments and string literals are blanked first ({@code BlockAccessorHygieneTest}'s blanker), so prose
 * naming the flags passes.
 */
class SectionTickingHygieneTest {

    /** A column ticking read or a ticking request. */
    private static final Pattern COLUMN_TICKING = Pattern.compile("\\bChunkFlag\\s*\\.\\s*TICKING\\b|\\bSET_TICKING\\b");

    /** The one place the library reads and wakes a section (its section request carries SET_TICKING). */
    private static final String SECTIONS = "zc-world/src/main/java/com/ziggfreed/common/world/TickingSections.java";

    /** The 2.2.0 column methods kept for linkage. */
    private static final String KEPT = "zc-dialogue/src/main/java/com/ziggfreed/common/npc/placement/runtime/NpcPlacementService.java";

    /** {@code isChunkLoaded}'s flag read and {@code requestChunk}'s column request. */
    private static final int KEPT_LINES = 2;

    @Test
    void noLibraryMainSourceDecidesAnEntitysStayFromTheColumn() throws IOException {
        List<String> outside = BlockAccessorHygieneTest.mainSourceHits(COLUMN_TICKING).stream()
                .filter(hit -> !slashed(hit).contains(SECTIONS) && !slashed(hit).contains(KEPT))
                .toList();
        assertTrue(outside.isEmpty(), () -> outside.size() + " column ticking read(s) or request(s). On Update 7"
                + " an entity stays only in a TICKING chunk section, which a column's flag does not say and a"
                + " column request does not wake: read and wake the section through zc-world's"
                + " world/TickingSections (stateAt, ensureTicking, whenTicking, holdTicking).\n"
                + String.join("\n", outside));
    }

    @Test
    void theKeptColumnMethodsHoldExactlyTheirTwoLines() throws IOException {
        List<String> kept = BlockAccessorHygieneTest.mainSourceHits(COLUMN_TICKING).stream()
                .filter(hit -> slashed(hit).contains(KEPT))
                .toList();
        assertEquals(KEPT_LINES, kept.size(), () -> "NpcPlacementService keeps isChunkLoaded's column flag read and"
                + " requestChunk's column request for 2.2.0 linkage, and nothing else:\n" + String.join("\n", kept));
    }

    @Nonnull
    private static String slashed(@Nonnull String hit) {
        return hit.replace('\\', '/');
    }

    // ==================== fixtures: prove the rule works ====================

    private static int hits(String... lines) {
        int n = 0;
        for (String line : BlockAccessorHygieneTest.blankCommentsAndStrings(List.of(lines))) {
            if (COLUMN_TICKING.matcher(line).find()) {
                n++;
            }
        }
        return n;
    }

    @Test
    void flagsAColumnFlagReadAndATickingRequest() {
        assertEquals(1, hits("        return chunk != null && chunk.is(ChunkFlag.TICKING) ? chunk : null;"));
        assertEquals(1, hits("        if (!chunk.is( ChunkFlag . TICKING )) {"));
        assertEquals(1, hits(
                "                .getChunkReferenceAsync(index, GetChunkFlags.SET_TICKING | GetChunkFlags.HIGH_PRIORITY)"));
    }

    @Test
    void passesOtherFlagsTheSectionHelperAndProse() {
        assertEquals(0, hits("        boolean fresh = chunk.is(ChunkFlag.NEWLY_GENERATED);"));
        assertEquals(0, hits("        int flags = GetChunkFlags.NO_SET_TICKING_SYNC;"));
        assertEquals(0, hits("        if (!TickingSections.ensureTicking(world, x, y, z)) {"));
        assertEquals(0, hits(
                "    // never chunk.is(ChunkFlag.TICKING) here",
                "    /** {@code GetChunkFlags.SET_TICKING} on a column wakes no section on Update 7. */",
                "    String s = \"SET_TICKING\";"));
    }
}
