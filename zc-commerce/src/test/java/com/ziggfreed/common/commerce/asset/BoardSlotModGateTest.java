package com.ziggfreed.common.commerce.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.commerce.fold.BoardAssetSpec;
import com.ziggfreed.common.factor.ModGates;

/**
 * A board slot whose own {@code Requires} carries the plain mod gate the way it does not fit this server (a
 * {@code Min: 1} condition on a missing mod, a {@code Max: 0} one on an installed mod) is left off the board:
 * the draw never posts it and it never shows locked. A board whose every slot is left out posts nothing,
 * never the unslotted draw.
 */
class BoardSlotModGateTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";

    private static final String VILLAGER = "{ \"Slots\": ["
            + " { \"Difficulty\": \"Errand\" },"
            + " { \"Difficulty\": \"Skill\", \"Optional\": true, \"Requires\": { \"Factors\": ["
            + "   { \"Factor\": \"hytale:mod_installed\", \"Param\": \"" + MMO + "\", \"Min\": 1 } ] } },"
            + " { \"Difficulty\": \"Errand\", \"Optional\": true, \"Requires\": { \"Factors\": ["
            + "   { \"Factor\": \"hytale:mod_installed\", \"Param\": \"" + MMO + "\", \"Max\": 0 } ] } } ] }";

    @AfterEach
    void restore() {
        ModGates.useProbeForTests(null);
    }

    @Test
    void eachServerPostsOnlyTheSlotsThatFitIt() throws IOException {
        BoardAsset board = CommerceCodecTest.board(VILLAGER, "Christmas_Bram_Board", null, null);
        assertEquals(3, board.slotsOrEmpty().length, "the validator still reads every authored slot");

        ModGates.useProbeForTests(param -> 1.0);
        assertEquals(2, board.slotsHere().length, "with the MMO the stand-in errand is left out");
        assertEquals("skill", board.slotsHere()[1].label());
        assertEquals(2, BoardAssetSpec.of(board).slots().size());

        ModGates.useProbeForTests(param -> 0.0);
        assertEquals(2, board.slotsHere().length, "without it the skill slot is left out");
        assertEquals("errand", board.slotsHere()[1].label());
    }

    @Test
    void aBoardWhoseEverySlotIsLeftOutPostsNothing() throws IOException {
        BoardAsset board = CommerceCodecTest.board("{ \"Slots\": [ { \"Difficulty\": \"Skill\", \"Requires\": {"
                + " \"Factors\": [ { \"Factor\": \"hytale:mod_installed\", \"Param\": \"" + MMO + "\", \"Min\": 1 } ] } } ] }",
                "Mmo_Only_Board", null, null);
        ModGates.useProbeForTests(param -> 0.0);

        assertEquals(0, board.slotsHere().length);
        assertEquals(1, BoardAssetSpec.of(board).slots().size(), "one slot no contract fits, never the unslotted draw");
        assertEquals(true, BoardAssetSpec.of(board).slots().get(0).optional());
    }
}
