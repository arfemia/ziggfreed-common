package com.ziggfreed.common.loot.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.validation.Finding;

/**
 * A row's {@code Season} takes it out of the table while its event is not running, exactly as
 * {@code Enabled: false} would: a broader pattern covers its names again, and a same-pattern row it
 * shadowed answers again. The table re-folds as the season turns over, with no merge. The audit still
 * reads an off-season row, and names a season no event declares.
 */
class BonusRowSeasonTest {

    private static final String PAYING = "\"Loot\": { \"Rolls\": [ { \"Grants\": { \"Items\":"
            + " [ { \"Item\": \"Fixture_Gem\", \"Count\": 1 } ] } } ] }";

    @TempDir
    Path ownerDir;

    private AtomicBoolean harvestLive;

    @BeforeEach
    void setUp() {
        BonusRowOwnerLayers.setDirectory(ownerDir);
        harvestLive = new AtomicBoolean(false);
        FeatureFlags.register(SeasonGate.NAMESPACE, "Harvest_Feast_Live", "test", harvestLive::get);
    }

    @AfterEach
    void tearDown() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of());
        BonusRowConfig.getInstance().mergeOwnerLayer(Map.of());
        BonusRowOwnerLayers.setDirectory(BonusRowOwnerLayers.DEFAULT_DIRECTORY);
        FeatureFlags.reset();
    }

    private static BonusRowAsset row(String id, String json) throws IOException {
        return BonusRowAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(BonusRowAsset.class, id, null)));
    }

    private static String idFor(String name) {
        BonusRow best = BonusRowConfig.getInstance().bestFor(BonusMoment.BREAK_BLOCK, name);
        return best == null ? null : best.sourceId();
    }

    @Test
    void aSeasonalRowCoversItsNameOnlyWhileItsSeasonRunsAndABroaderRowCoversItOtherwise() throws IOException {
        Map<String, BonusRowAsset> layer = new LinkedHashMap<>();
        layer.put("fixture_any_rock", row("fixture_any_rock",
                "{ \"When\": { \"Kind\": \"BreakBlock\", \"Match\": \"Rock_*\" }, " + PAYING + " }"));
        layer.put("harvest_feast_stone", row("harvest_feast_stone", "{ \"Season\": \"Harvest_Feast\","
                + " \"When\": { \"Kind\": \"BreakBlock\", \"Match\": \"Rock_Stone\" }, " + PAYING + " }"));
        BonusRowConfig.getInstance().mergePackLayer(layer);

        assertEquals("fixture_any_rock", idFor("Rock_Stone"), "out of season the row is out of the table");
        harvestLive.set(true);
        assertEquals("harvest_feast_stone", idFor("Rock_Stone"), "the run starts and the next roll sees it, no merge");
        harvestLive.set(false);
        assertEquals("fixture_any_rock", idFor("Rock_Stone"), "and the run's end gives the name back");
    }

    @Test
    void aSeasonalRowOnTheSamePatternGivesItBackWhenItsSeasonEnds() throws IOException {
        Map<String, BonusRowAsset> layer = new LinkedHashMap<>();
        layer.put("fixture_a_rock", row("fixture_a_rock",
                "{ \"When\": { \"Kind\": \"BreakBlock\", \"Match\": \"Rock_*\" }, " + PAYING + " }"));
        layer.put("harvest_feast_rock", row("harvest_feast_rock", "{ \"Season\": \"Harvest_Feast\","
                + " \"When\": { \"Kind\": \"BreakBlock\", \"Match\": \"Rock_*\" }, " + PAYING + " }"));
        BonusRowConfig.getInstance().mergePackLayer(layer);

        assertEquals("fixture_a_rock", idFor("Rock_Granite"));
        harvestLive.set(true);
        assertEquals("harvest_feast_rock", idFor("Rock_Granite"), "the id that sorts last wins one pattern");
        harvestLive.set(false);
        assertEquals("fixture_a_rock", idFor("Rock_Granite"), "as Enabled false would, the shadowed row answers");
    }

    @Test
    void aSeasonStartingWhileTheTableFoldsIsReadOnceSoItsRowsJoinOnTheNextRoll() throws IOException {
        // The switch reads off for its first look and on ever after: a run that starts while the fold
        // is reading. The fold must judge the rows and record the live set from that ONE reading, or it
        // records the season as seen with its rows left out, and no roll in the whole run re-folds.
        AtomicInteger reads = new AtomicInteger();
        FeatureFlags.register(SeasonGate.NAMESPACE, "Harvest_Feast_Live", "test", () -> reads.incrementAndGet() > 1);
        Map<String, BonusRowAsset> layer = new LinkedHashMap<>();
        layer.put("fixture_any_rock", row("fixture_any_rock",
                "{ \"When\": { \"Kind\": \"BreakBlock\", \"Match\": \"Rock_*\" }, " + PAYING + " }"));
        layer.put("harvest_feast_stone", row("harvest_feast_stone", "{ \"Season\": \"Harvest_Feast\","
                + " \"When\": { \"Kind\": \"BreakBlock\", \"Match\": \"Rock_Stone\" }, " + PAYING + " }"));
        BonusRowConfig.getInstance().mergePackLayer(layer);

        assertEquals("harvest_feast_stone", idFor("Rock_Stone"), "the run is on, so its row answers");
    }

    @Test
    void theAuditReadsAnOffSeasonRowAndNamesASeasonNoEventDeclares() throws IOException {
        BonusRowConfig.getInstance().mergePackLayer(Map.of("harvest_feast_stone", row("harvest_feast_stone",
                "{ \"Season\": \"Harvest_Faest\", \"When\": { \"Kind\": \"BreakBlock\", \"Match\": \"Rock_Stone\" }, "
                        + PAYING + " }")));

        assertTrue(BonusRowConfig.getInstance().table().entries().isEmpty(), "never live, so never rolled");
        assertEquals(1, BonusRowConfig.getInstance().authoredTable().entries().size(), "but still audited");
        assertEquals(Map.of("harvest_feast_stone", "Harvest_Faest"), BonusRowConfig.getInstance().seasonsByRow());
        List<Finding> findings = BonusRowAudit.auditAll(null, null);
        assertTrue(findings.stream().anyMatch(f -> SeasonGate.UNKNOWN_SEASON.equals(f.code())
                && "harvest_feast_stone".equals(f.sourceId())), findings.toString());
    }
}
