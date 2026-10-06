package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every accept decision the board page makes names the slot that posted the contract, so a slot's own
 * Requires locks it there. The page cannot be built in a unit JVM, so the guard reads its source.
 */
class ZigBoardPageSlotGateTest {

    private static final Path PAGE = Path.of("src", "main", "java", "com", "ziggfreed", "common", "commerce",
            "page", "ZigBoardPage.java");

    @Test
    void everyAcceptCheckOnThePageCarriesThePostedSlot() throws IOException {
        String source = Files.readString(PAGE, StandardCharsets.UTF_8);
        assertFalse(source.contains("engine.canAccept(subject, board, ref, now)"), "the slot-less check is gone");
        assertFalse(source.contains("engine.acceptGateRefusals(subject, board, ref)"));
        assertFalse(source.contains("engine.accept(s, board, bounty, System.currentTimeMillis())"));
        Matcher posted = Pattern.compile("\\bpostedIn\\(engine, ").matcher(source);
        int count = 0;
        while (posted.find()) {
            count++;
        }
        assertEquals(3, count, "sectionOf, renderLockedDetail and doAccept each find the posted slot");
    }
}
