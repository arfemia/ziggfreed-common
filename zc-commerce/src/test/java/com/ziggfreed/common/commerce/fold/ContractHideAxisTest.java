package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.quest.asset.QuestDefinition;

/**
 * A contract's plain feature condition leaves its gate at the fold, exactly as a quest's does, and
 * answers both the quest's availability and the draw live. The namespace is unique to this class.
 */
class ContractHideAxisTest {

    private static final String NAMESPACE = "hide_contract";
    private static final String GATED = """
            { "Boards": [ { "Board": "Daily", "Difficulty": "Normal" } ],
              "Requires": { "Factors": [ { "Factor": "hide_contract:feature", "Param": "Spooky", "Min": 1 },
                                         { "Factor": "yourmod:rank", "Min": 5 } ] },
              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Target": "Skeleton", "Amount": 3 } } }
            """;

    private AtomicBoolean spooky;

    @BeforeEach
    void declareTheFeature() {
        spooky = new AtomicBoolean(true);
        FeatureFlags.register(NAMESPACE, "spooky", "test", spooky::get);
    }

    @AfterEach
    void forget() {
        FeatureFlags.reset();
    }

    private static BountyAsset contract(String json) throws IOException {
        return BountyAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(BountyAsset.class, "haunt_hunt", null)));
    }

    @Test
    @DisplayName("the fold lifts the feature out of the gate and answers availability live")
    void theFoldLiftsTheFeature() throws IOException {
        QuestDefinition folded = contract(GATED).toDefinition(null);

        assertEquals(1, folded.requires().factorsOrEmpty().length, "only the rank stays a lock");
        assertEquals("yourmod:rank", folded.requires().factorsOrEmpty()[0].getFactor());
        assertEquals(1, folded.lifted().size());
        assertEquals("hide_contract:feature", folded.lifted().get(0).factorId());
        assertTrue(folded.quest().available());
        spooky.set(false);
        assertFalse(folded.quest().available(), "the same object reads off on the next look");
        spooky.set(true);
        assertTrue(folded.quest().available());
    }

    @Test
    @DisplayName("Enabled false still wins whatever the feature says")
    void enabledFalseWins() throws IOException {
        QuestDefinition folded = contract(GATED.replace("{ \"Boards\"", "{ \"Enabled\": false, \"Boards\""))
                .toDefinition(null);

        assertFalse(folded.quest().available());
    }

    @Test
    @DisplayName("a contract whose feature is off leaves the draw, read live off the same view")
    void theDrawReadsTheFeature() throws IOException {
        BountyAssetRef ref = BountyAssetRef.of(contract(GATED));

        assertTrue(ref.enabled());
        spooky.set(false);
        assertFalse(ref.enabled(), "so it never takes a slot on a board that turns over all year");
        assertTrue(ref.isOn("daily"), "while it still names its board, which keeps it on a carrier's list");
    }

    @Test
    @DisplayName("a contract gating on no feature folds exactly as before")
    void aContractWithNoFeatureIsUntouched() throws IOException {
        QuestDefinition folded = contract("""
                { "Requires": { "Factors": [ { "Factor": "yourmod:rank", "Min": 5 } ] } }
                """).toDefinition(null);

        assertTrue(folded.lifted().isEmpty());
        assertEquals(1, folded.requires().factorsOrEmpty().length);
        assertTrue(folded.quest().available());
    }
}
