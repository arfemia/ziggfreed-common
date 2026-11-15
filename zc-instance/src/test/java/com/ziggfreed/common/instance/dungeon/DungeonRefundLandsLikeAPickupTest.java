package com.ziggfreed.common.instance.dungeon;

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
 * A dungeon's item entry cost given back (a take that fell short mid-spend, a teleport the engine refused) lands the
 * way every item handed straight to a player lands: through zc-loot's one delivery, {@code LootRewardKinds.handToPlayer}
 * (the bag the player's pickup settings choose, with the game's own pickup notice, and the rest to the overflow policy
 * at their feet), never through a raw inventory give. zc-core's {@code InventoryUtil.give} answered the part that did
 * not fit and dropped it, so a refund into a full bag only left a warn line. A unit JVM has no live player whose bag
 * could answer, so the seam is held here and the landing is smoked in game.
 */
class DungeonRefundLandsLikeAPickupTest {

    private static final Path COST = Path.of("src", "main", "java", "com", "ziggfreed", "common", "instance",
            "dungeon", "DungeonEntryCost.java");

    private static final Pattern RAW_GIVE = Pattern.compile("InventoryUtil\\s*\\.\\s*give\\s*\\("
            + "|InventoryGrant\\s*\\.|Player\\s*\\.\\s*giveItem\\s*\\(|\\.\\s*addItemStacks?\\s*\\(");

    @Test
    void anEntryRefundIsHandedBackThroughTheOneDeliveryNeverARawGive() throws IOException {
        assertTrue(Files.isRegularFile(COST), "missing " + COST.toAbsolutePath());
        List<String> lines = Files.readAllLines(COST, StandardCharsets.UTF_8);

        List<String> hits = new ArrayList<>();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = codeOf(lines.get(i));
            code.append(line).append('\n');
            if (RAW_GIVE.matcher(line).find()) {
                hits.add("DungeonEntryCost.java:" + (i + 1) + "  " + lines.get(i).trim());
            }
        }
        assertEquals(List.of(), hits, "A dungeon's entry refund is handed back through LootRewardKinds.handToPlayer;"
                + " a raw give drops whatever the bag cannot take");
        assertTrue(code.indexOf("LootRewardKinds.handToPlayer(") >= 0,
                "the refund is the library's one delivery for items handed straight to a player");
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
