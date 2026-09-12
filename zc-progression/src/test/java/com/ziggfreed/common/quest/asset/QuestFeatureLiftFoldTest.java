package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.factor.ModFactors;
import com.ziggfreed.common.progress.gate.FeatureLift;

/**
 * The hide axis folded by the shared quest fold itself: a plain top-level feature or mod-presence
 * condition leaves {@code Requires} at the fold, lands on the record's lifted list, and answers
 * {@link com.ziggfreed.common.quest.Quest#available()} LIVE beside {@code Enabled}.
 *
 * <p>The namespace here is test-unique on purpose: a feature contribution is process-wide and
 * cannot be withdrawn, so no two test classes may claim the same one.
 */
class QuestFeatureLiftFoldTest {

    private static final String NAMESPACE = "liftfold_quest";
    private static final String FEATURE_FACTOR = NAMESPACE + ":feature";

    private static final String GATED_JSON = """
            { "Requires": { "Factors": [ { "Factor": "liftfold_quest:feature", "Param": "Trading", "Min": 1 },
                                         { "Factor": "yourmod:rank", "Min": 5 } ] },
              "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore" } } }
            """;

    private AtomicBoolean trading;

    @BeforeEach
    void declareTheFeature() {
        trading = new AtomicBoolean(true);
        FeatureFlags.register(NAMESPACE, "trading", "yourmod", trading::get);
    }

    @AfterEach
    void forgetDeclaredFeatures() {
        FeatureFlags.reset();
    }

    @Test
    void aDeclaredNamespacesFeatureConditionLeavesTheGateAndLandsOnTheRecord() throws Exception {
        QuestDefinition definition = decodeRoot(GATED_JSON, "market_run").toDefinition(null);

        assertEquals(1, definition.requires().factorsOrEmpty().length, "only the rank stays a lock");
        assertEquals("yourmod:rank", definition.requires().factorsOrEmpty()[0].getFactor());
        assertEquals(1, definition.lifted().size());
        FeatureLift.Lifted lifted = definition.lifted().get(0);
        assertEquals(FEATURE_FACTOR, lifted.factorId());
        assertEquals("Trading", lifted.param(), "the param is kept as authored on the entry");
        assertFalse(lifted.isModPresence());
        assertEquals(List.of("trading"), definition.features(),
                "the flat hide-axis list spells a feature id lower-cased, as a feature table is read");
    }

    @Test
    void availableReadsEnabledAndEveryLiftedFeatureLiveSoAToggleMovesTheQuest() throws Exception {
        QuestDefinition definition = decodeRoot(GATED_JSON, "market_run").toDefinition(null);

        assertTrue(definition.quest().available(), "on, and enabled");
        trading.set(false);
        assertFalse(definition.quest().available(),
                "the same object reads off on the next look: no republish, no rebuild");
        trading.set(true);
        assertTrue(definition.quest().available());
    }

    @Test
    void enabledFalseStillWinsWhateverTheFeatureSays() throws Exception {
        QuestDefinition definition = decodeRoot(
                GATED_JSON.replace("{ \"Requires\"", "{ \"Enabled\": false, \"Requires\""), "market_run")
                .toDefinition(null);

        assertFalse(definition.quest().available());
    }

    @Test
    void aFeatureFactorOfANamespaceNothingDeclaredStaysALockRatherThanAHide() throws Exception {
        QuestDefinition definition = decodeRoot("""
                { "Requires": { "Factors": [ { "Factor": "nobody_declared:feature", "Param": "x", "Min": 1 } ] },
                  "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore" } } }
                """, "foreign").toDefinition(null);

        assertTrue(definition.lifted().isEmpty(), "an undeclared namespace may simply not be installed yet");
        assertEquals("nobody_declared:feature", definition.requires().factorsOrEmpty()[0].getFactor(),
                "so the condition stays in the gate, where fail-closed keeps the quest locked");
        assertTrue(definition.quest().available(), "and the quest exists, locked, rather than vanishing");
    }

    @Test
    void aModPresenceConditionIsLiftedAndFailsClosedWithNoEngineToAsk() throws Exception {
        QuestDefinition definition = decodeRoot("""
                { "Requires": { "Factors": [ { "Factor": "hytale:mod_installed", "Param": "Some:Mod", "Min": 1 } ] },
                  "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore" } } }
                """, "companion").toDefinition(null);

        assertEquals(1, definition.lifted().size());
        assertTrue(definition.lifted().get(0).isModPresence());
        assertEquals(ModFactors.MOD_INSTALLED, definition.lifted().get(0).factorId());
        assertEquals(List.of("Some:Mod"), definition.features(),
                "a mod's Group:Name keeps its case; the plugin table matches it case-sensitively");
        assertTrue(definition.requires().isEmpty(), "nothing else was authored, so the gate is spent");
        assertFalse(definition.quest().available(),
                "a unit JVM has no plugin table to ask, and 'cannot tell' hides rather than shows");
    }

    @Test
    void aQuestGatingOnNoFeatureIsUntouchedByTheLift() throws Exception {
        QuestDefinition definition = decodeRoot("""
                { "Requires": { "Factors": [ { "Factor": "yourmod:rank", "Min": 5 } ] },
                  "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore" } } }
                """, "plain").toDefinition(null);

        assertTrue(definition.lifted().isEmpty());
        assertTrue(definition.features().isEmpty());
        assertEquals(1, definition.requires().factorsOrEmpty().length);
        assertTrue(definition.quest().available());
    }
}
