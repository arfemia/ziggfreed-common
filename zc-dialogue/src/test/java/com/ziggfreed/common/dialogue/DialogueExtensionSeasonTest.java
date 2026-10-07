package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.dialogue.asset.DialogueExtensionAsset;
import com.ziggfreed.common.dialogue.schema.DialogueExtension;
import com.ziggfreed.common.dialogue.schema.DialogueExtensionConfig;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.validation.Finding;

/**
 * An extension's {@code Season}: its lines stay spliced where they land, and the one option predicate
 * offers them only while that season runs, read live; a conversation's own lines never read a season;
 * the file carries the leaf onto every line; and the audit names an id no calendar event declares.
 */
class DialogueExtensionSeasonTest {

    private static final String SMITH = """
            { "Nodes": { "shop": { "Options": [ { "LabelKey": "s" } ] } } }
            """;

    private final long now = Instant.parse("2026-11-24T12:00:00Z").toEpochMilli();
    private AtomicBoolean harvestLive;

    @BeforeEach
    void setUp() {
        DialogueTestSupport.reset();
        harvestLive = new AtomicBoolean(false);
        FeatureFlags.register(SeasonGate.NAMESPACE, "Harvest_Feast_Live", "test", harvestLive::get);
    }

    @AfterEach
    void tearDown() {
        DialogueExtensionConfig.getInstance().mergePackLayer(Map.of());
        FeatureFlags.reset();
    }

    @Nonnull
    private DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).clock(() -> now).build();
    }

    @Nonnull
    private static DialogueExtension greeting(@Nonnull String season) {
        return DialogueExtension.of("harvest_feast_greeting", DialogueTestSupport.optionRows("""
                [ { "LabelKey": "harvest_feast.greeting" } ]
                """), null, null, true, season);
    }

    @Nonnull
    private static DialogueOption line(@Nonnull NpcDialogue dialogue, @Nonnull String extension) {
        for (DialogueOption option : dialogue.getNode("shop").getOptions()) {
            if (extension.equals(option.getInjectedBy())) {
                return option;
            }
        }
        throw new AssertionError("no line from '" + extension + "'");
    }

    @Test
    void aSeasonalLineStaysOnTheScreenAndIsOfferedOnlyWhileItsSeasonRuns() {
        DialogueEngine engine = engine();
        DialogueTestSupport.shareExtensions(greeting("Harvest_Feast"));
        NpcDialogue smith = engine.decode("smith", SMITH);
        assertNotNull(smith);
        DialogueOption seasonal = line(smith, "harvest_feast_greeting");
        DialogueOption own = smith.getNode("shop").getOptions().get(0);
        TestDialogueContext ctx = new TestDialogueContext(smith);

        assertEquals("Harvest_Feast", seasonal.getInjectedSeason());
        assertFalse(engine.optionAvailable(smith, "shop", seasonal, ctx), "out of season the line is hidden");
        assertTrue(engine.optionAvailable(smith, "shop", own, ctx), "the screen's own line reads no season");
        harvestLive.set(true);
        assertTrue(engine.optionAvailable(smith, "shop", seasonal, ctx), "in season, live, with no re-splice");
        assertNull(own.getInjectedSeason());
    }

    @Test
    void theFileCarriesItsSeasonOntoEveryLineItHandsOut() throws IOException {
        engine();
        DialogueExtensionAsset asset = DialogueExtensionAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString("""
                { "Season": "Harvest_Feast", "Options": [ { "LabelKey": "a" }, { "LabelKey": "b" } ] }
                """), new AssetExtraInfo<>(new AssetExtraInfo.Data(DialogueExtensionAsset.class,
                "Harvest_Feast_Greeting", null)));
        assertNotNull(asset);

        DialogueExtension extension = asset.toExtension(asset.getId());

        assertEquals("Harvest_Feast", asset.getSeason());
        assertEquals("Harvest_Feast", extension.getSeason());
        assertEquals(List.of("Harvest_Feast", "Harvest_Feast"),
                extension.getOptions().stream().map(DialogueOption::getInjectedSeason).toList());
    }

    @Test
    void theAuditNamesASeasonNoEventDeclares() {
        DialogueEngine engine = engine();
        DialogueTestSupport.shareExtensions(greeting("Harvest_Faest"));
        NpcDialogue smith = engine.decode("smith", SMITH);
        assertNotNull(smith);

        List<Finding> findings = DialogueStructureValidator.validateAll(List.of(smith));

        assertTrue(findings.stream().anyMatch(f -> SeasonGate.UNKNOWN_SEASON.equals(f.code())
                && "harvest_feast_greeting".equals(f.sourceId())), findings.toString());
    }
}
