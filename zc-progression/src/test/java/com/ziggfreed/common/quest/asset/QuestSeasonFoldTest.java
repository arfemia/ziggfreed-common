package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decode;
import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * A quest's {@code Season}: hidden out of season and never locked by it; inherited from a season base
 * whatever the child writes in {@code Requires} (Review Focus 3, with the old base-Requires gate shown
 * losing itself beside it); and an id no event declares reported once at the fold, the quest hidden.
 */
class QuestSeasonFoldTest {

    private static final String RANK = "{ \"Factor\": \"yourmod:rank\", \"Min\": 5 }";
    private static final String STEP = "\"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Food_Pie_Pumpkin\" } }";

    private AtomicBoolean harvestLive;

    @BeforeEach
    void declareTheCalendarsSwitch() {
        harvestLive = new AtomicBoolean(false);
        FeatureFlags.register(SeasonGate.NAMESPACE, "Harvest_Feast_Live", "test", harvestLive::get);
        emptyStore();
    }

    @AfterEach
    void forget() {
        FeatureFlags.reset();
        emptyStore();
    }

    private static void emptyStore() {
        QuestAssetStore.getInstance().mergeQuests(Map.of());
        QuestAssetStore.getInstance().mergeGenerators(Map.of());
        QuestAssetStore.getInstance().mergeContributed(Map.of());
    }

    @Test
    void aChildOfASeasonBaseThatWritesItsOwnRequiresHidesOffSeasonAndLocksByRankInSeason() throws Exception {
        QuestAsset base = decodeRoot("{ \"Abstract\": true, \"Season\": \"Harvest_Feast\","
                + " \"Npc\": { \"ViewId\": \"harvest_feast_host\" }, " + STEP + " }", "harvest_feast_base");
        QuestAsset child = decode("{ \"Requires\": { \"Factors\": [ " + RANK + " ] } }",
                "harvest_feast_pies", "harvest_feast_base", base);

        assertEquals("Harvest_Feast", child.getSeason(), "the leaf survives the child's own Requires");
        QuestDefinition folded = child.toDefinition(null);
        assertFalse(folded.quest().available(), "off-season: absent, not shown locked");

        harvestLive.set(true);

        assertTrue(folded.quest().available(), "in season: present, read live with no republish");
        assertEquals(1, folded.requires().factorsOrEmpty().length, "and still locked by the rank it wrote");
        assertEquals("yourmod:rank", folded.requires().factorsOrEmpty()[0].getFactor());
        assertTrue(folded.lifted().isEmpty(), "the season is neither a lock reason nor a lifted condition");
    }

    @Test
    void aGateInABasesRequiresIsLostWhenTheChildWritesItsOwnWhichIsWhySeasonIsALeaf() throws Exception {
        QuestAsset base = decodeRoot("{ \"Abstract\": true, \"Requires\": { \"Factors\": [ { \"Factor\":"
                + " \"ziggfreedcommon:feature\", \"Param\": \"Harvest_Feast_Live\", \"Min\": 1 } ] }, " + STEP + " }",
                "old_base");
        QuestAsset child = decode("{ \"Requires\": { \"Factors\": [ " + RANK + " ] } }",
                "old_child", "old_base", base);

        assertTrue(child.toDefinition(null).quest().available(),
                "the child's Factors array replaced the base's whole, the season gate with it (review 2's B2)");
    }

    @Test
    void aSeasonHidesAQuestAndLocksNothing() throws Exception {
        QuestDefinition folded = decodeRoot("{ \"Season\": \"Harvest_Feast\", " + STEP + " }",
                "harvest_feast_turkey").toDefinition(null);

        assertFalse(folded.quest().available());
        assertTrue(folded.requires().isEmpty(), "nothing is left to lock: a season is a hide only");
        harvestLive.set(true);
        assertTrue(folded.quest().available());
        harvestLive.set(false);
        assertFalse(folded.quest().available(), "the run ended, and the same object reads it");
    }

    @Test
    void enabledFalseWinsWhateverTheSeasonSays() throws Exception {
        harvestLive.set(true);

        assertFalse(decodeRoot("{ \"Enabled\": false, \"Season\": \"Harvest_Feast\", " + STEP + " }",
                "retired").toDefinition(null).quest().available());
    }

    @Test
    void anIdNoEventDeclaresIsOneWarningAtTheFoldAndTheQuestStaysHidden() throws Exception {
        Map<String, QuestAsset> layer = new LinkedHashMap<>();
        layer.put("harvest_feast_typo", decodeRoot("{ \"Season\": \"Harvest_Faest\", " + STEP + " }",
                "harvest_feast_typo"));
        layer.put("harvest_feast_pies", decodeRoot("{ \"Season\": \"Harvest_Feast\", " + STEP + " }",
                "harvest_feast_pies"));
        QuestAssetStore.getInstance().mergeQuests(layer);
        harvestLive.set(true);

        QuestAssetStore.Resolution resolution = QuestAssetStore.getInstance().resolve(null);

        List<Finding> unknown = resolution.issues().stream()
                .filter(f -> SeasonGate.UNKNOWN_SEASON.equals(f.code())).toList();
        assertEquals(1, unknown.size(), resolution.issues().toString());
        assertEquals(Severity.WARNING, unknown.get(0).severity());
        assertEquals(QuestPoolValidator.DOMAIN, unknown.get(0).domain());
        assertEquals("harvest_feast_typo", unknown.get(0).sourceId());
        QuestDefinition typo = resolution.pool().definition("harvest_feast_typo");
        assertNotNull(typo);
        assertFalse(typo.quest().available(), "an id no event answers reads 0, so it stays hidden");
        assertTrue(resolution.pool().definition("harvest_feast_pies").quest().available());
    }
}
