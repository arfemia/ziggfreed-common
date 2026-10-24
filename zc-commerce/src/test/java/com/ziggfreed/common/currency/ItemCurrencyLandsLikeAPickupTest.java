package com.ziggfreed.common.currency;

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
 * An item currency's credit, refund or price handed back lands the way every item handed straight to a player
 * lands: through zc-loot's one delivery, {@code LootRewardKinds.handToPlayer} (the bag the player's pickup settings
 * choose, with the game's own pickup notice, and the rest to the overflow policy at their feet), never through a raw
 * inventory give. zc-core's {@code InventoryUtil.give} answered the part that did not fit and dropped it, so a
 * currency reward or refund paid into a full bag simply vanished. A unit JVM has no live player whose bag could
 * answer, so the seam is held here and the landing is smoked in game.
 */
class ItemCurrencyLandsLikeAPickupTest {

    private static final Path WALLET = Path.of("src", "main", "java", "com", "ziggfreed", "common", "currency",
            "NativeItemWallet.java");

    private static final Pattern RAW_GIVE = Pattern.compile("InventoryUtil\\s*\\.\\s*give\\s*\\("
            + "|InventoryGrant\\s*\\.|Player\\s*\\.\\s*giveItem\\s*\\(|\\.\\s*addItemStacks?\\s*\\(");

    @Test
    void anItemCurrencyIsHandedOverThroughTheOneDeliveryNeverARawGive() throws IOException {
        assertTrue(Files.isRegularFile(WALLET), "missing " + WALLET.toAbsolutePath());
        List<String> lines = Files.readAllLines(WALLET, StandardCharsets.UTF_8);

        List<String> hits = new ArrayList<>();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = codeOf(lines.get(i));
            code.append(line).append('\n');
            if (RAW_GIVE.matcher(line).find()) {
                hits.add("NativeItemWallet.java:" + (i + 1) + "  " + lines.get(i).trim());
            }
        }
        assertEquals(List.of(), hits, "An item currency is handed over through LootRewardKinds.handToPlayer; a raw"
                + " give drops whatever the bag cannot take");
        assertTrue(code.indexOf("LootRewardKinds.handToPlayer(") >= 0,
                "the hand-over is the library's one delivery for items handed straight to a player");
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
