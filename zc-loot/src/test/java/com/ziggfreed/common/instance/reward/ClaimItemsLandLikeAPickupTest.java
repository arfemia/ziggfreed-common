package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * A Claim's item lands the way the engine gives a picked-up item, through the pickup seam ({@code GroundSpillSinks}'
 * {@code pickupFits}, then {@code pickupRouted}: the hotbar, storage and backpack, in the player's own pickup order),
 * never through zc-core's combined view ({@code InventoryUtil.give} and {@code canFit}, over
 * {@code InventoryComponent.EVERYTHING}). The engine fills that view slot by slot in armor, hotbar, utility, storage,
 * backpack order, so with a full hotbar a shield taken from a boss's chest landed in a utility slot its player never
 * looked at (the Almanac then showed it found), and an armor piece would land worn. A unit JVM cannot build a
 * player's sections ({@code InventoryComponent.getCombined} reads the engine's entity module), so the seam is held
 * here and the landing is smoked in game.
 */
class ClaimItemsLandLikeAPickupTest {

    private static final Path GRANTER = Path.of("src", "main", "java", "com", "ziggfreed", "common", "instance",
            "reward", "InstanceRewardGranter.java");

    private static final Pattern COMBINED_VIEW =
            Pattern.compile("InventoryUtil\\s*\\.\\s*(give|canFit)\\s*\\(|InventoryComponent\\s*\\.\\s*EVERYTHING");

    @Test
    void aClaimsItemIsHandedOverThroughThePickupSeamNeverTheCombinedView() throws IOException {
        assertTrue(Files.isRegularFile(GRANTER), "missing " + GRANTER.toAbsolutePath());
        List<String> lines = Files.readAllLines(GRANTER, StandardCharsets.UTF_8);

        List<String> hits = new ArrayList<>();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = codeOf(lines.get(i));
            code.append(line).append('\n');
            if (COMBINED_VIEW.matcher(line).find()) {
                hits.add("InstanceRewardGranter.java:" + (i + 1) + "  " + lines.get(i).trim());
            }
        }
        assertEquals(List.of(), hits, "A Claim's item is handed over through GroundSpillSinks.pickupFits and"
                + " pickupRouted; the combined view fills armor and utility slots before the bag");
        assertTrue(code.indexOf("GroundSpillSinks.pickupFits(") >= 0, "the room probe is the pickup's own");
        assertTrue(code.indexOf("GroundSpillSinks.pickupRouted(") >= 0, "the give is the pickup's own");
    }

    /** A line's code: javadoc and block-comment lines read as nothing, a trailing // comment is cut. */
    private static String codeOf(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
            return "";
        }
        int at = line.indexOf("//");
        return at < 0 ? line : line.substring(0, at);
    }
}
