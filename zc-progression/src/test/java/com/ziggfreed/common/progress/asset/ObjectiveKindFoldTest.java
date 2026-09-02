package com.ziggfreed.common.progress.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;

/**
 * The fold that turns an authored kind file into a registered kind.
 *
 * <p>What matters here is the MERGE: a file states one fact about a kind and must leave every other
 * fact alone, because the alternative is a file that silently resets arithmetic nobody asked it to
 * touch. Each test states a leaf and asserts the untouched ones survived.
 */
class ObjectiveKindFoldTest {

    private ObjectiveKindRegistry kinds;

    @BeforeEach
    void reset() {
        kinds = new ObjectiveKindRegistry("test-objective");
        ObjectiveKindConfig.getInstance().loadDefaults(Map.of());
    }

    @Test
    void aFileStatingOnlyAPictureKeepsTheRegisteredArithmetic() throws IOException {
        kinds.register("mod", ObjectiveKind.valueBased("SOME_KIND"));

        fold("Some_Kind", """
                { "Presentation": { "Icon": { "ItemId": "Weapon_Sword_Crude" } } }
                """);

        ObjectiveKind folded = kinds.kind("SOME_KIND");
        assertNotNull(folded, "the kind is still registered after a fold");
        assertTrue(folded.valueBased(), "an unmentioned leaf is not an instruction to change it");
        assertTrue(folded.producible(), "an unmentioned leaf is not an instruction to change it");
        assertNotNull(folded.presentation().icon(), "the authored picture landed");
        assertEquals("Weapon_Sword_Crude", folded.presentation().icon().itemId(), "Icon.ItemId");
    }

    @Test
    void aFileMayTurnOneLeafOffWithoutRestatingTheRest() throws IOException {
        kinds.register("mod", ObjectiveKind.entityTargeted("SOME_KIND"));

        fold("Some_Kind", """
                { "Producible": false }
                """);

        ObjectiveKind folded = kinds.kind("SOME_KIND");
        assertNotNull(folded, "the kind survives");
        assertFalse(folded.producible(), "the authored leaf won");
        assertTrue(folded.targetsEntity(), "the flags it did not mention survived");
    }

    @Test
    void aFileNamingAnUnregisteredIdAddsTheKind() throws IOException {
        assertNull(kinds.kind("BRAND_NEW"), "nothing registered this id");

        fold("Brand_New", """
                { "TargetNames": { "Item": true } }
                """);

        ObjectiveKind added = kinds.kind("BRAND_NEW");
        assertNotNull(added, "a file may add a kind nothing registered");
        assertTrue(added.targetsItem(), "the authored flag landed");
        assertTrue(added.producible(), "an unstated Producible defaults to usable, as a bare registration does");
        assertFalse(added.valueBased(), "an unstated ValueBased defaults to accumulating");
    }

    @Test
    void targetPicturesMergePerTargetRatherThanReplacingTheMap() throws IOException {
        kinds.register("mod", ObjectiveKind.entityTargeted("SOME_KIND").withPresentation(
                new ObjectiveKind.Presentation(null, null,
                        Map.of("Boar", IconSpec.ofTexture("Icons/ModelsGenerated/Boar.png")))));

        fold("Some_Kind", """
                { "Presentation": { "TargetIcons": {
                    "Trork": { "TexturePath": "Icons/ModelsGenerated/Trork_Brawler.png" } } } }
                """);

        ObjectiveKind.Presentation p = kinds.kind("SOME_KIND").presentation();
        assertNotNull(p.iconForTarget("Trork"), "the authored target picture landed");
        assertNotNull(p.iconForTarget("Boar"), "a target the file did not mention kept its picture");
    }

    @Test
    void aTargetPictureIsFoundWhateverTheCaseTheStepSpellsItIn() throws IOException {
        fold("Some_Kind", """
                { "Presentation": { "TargetIcons": {
                    "Trork": { "TexturePath": "Icons/ModelsGenerated/Trork_Brawler.png" } } } }
                """);

        ObjectiveKind.Presentation p = kinds.kind("SOME_KIND").presentation();
        assertNotNull(p.iconForTarget("trork"), "target lookups are case-insensitive like every other id");
        assertNull(p.iconForTarget("Boar"), "an unauthored target has no picture");
    }

    @Test
    void aKindMayDeclareThatItsTargetNamesAWallet() throws IOException {
        // The engines here define no currency, so the flag is the whole of what they can say: it is
        // what lets the module that DOES own wallets answer the picture without this one importing it.
        fold("Earn_Currency", """
                { "TargetNames": { "Currency": true } }
                """);

        ObjectiveKind folded = kinds.kind("EARN_CURRENCY");
        assertNotNull(folded, "the kind is registered");
        assertTrue(folded.targetsCurrency(), "the authored flag landed");
        assertFalse(folded.targetsItem(), "a wallet is not an item id, whatever backs it");
        assertNull(folded.presentation().icon(), "nothing here pictures a wallet");
    }

    @Test
    void theTargetFacetsAreIndependentOfEachOther() throws IOException {
        // A kind may name more than one thing at once - a character is both somewhere to go and a
        // face - so setting one facet must never clear another. The file below sets two and the
        // registered kind keeps a third.
        kinds.register("mod", ObjectiveKind.placeTargeted("SOME_KIND"));

        fold("Some_Kind", """
                { "TargetNames": { "Entity": true, "Content": true } }
                """);

        ObjectiveKind folded = kinds.kind("SOME_KIND");
        assertTrue(folded.targetsPlace(), "the registered facet survived a file that did not mention it");
        assertTrue(folded.targetsEntity(), "an authored facet landed");
        assertTrue(folded.targetsContent(), "a second authored facet landed beside it");
        assertFalse(folded.targetsBoard(), "a facet nobody set stays off");
        assertFalse(folded.targetsCurrency(), "a facet nobody set stays off");
    }

    @Test
    void theShippedQuestKindDrawsFromTheQuestItNames() throws IOException {
        ObjectiveKindAsset asset =
                read("/Server/ZiggfreedCommon/ObjectiveKinds/Complete_Quest.json", "Complete_Quest");
        assertNotNull(asset.getTargetNames(), "the kind says what its target names");
        assertEquals(Boolean.TRUE, asset.getTargetNames().getContent(),
                "a quest step's target is another piece of content, drawn with its own icon");
    }

    @Test
    void everyShippedKindFileDecodes() throws IOException {
        // The files this module ships describe the built-in vocabulary. A file that stopped decoding
        // would leave its kind silently undescribed, so each one is read here as the server reads it.
        for (String id : new String[] {"Break_Block", "Craft_Item", "Kill_Entity", "Stat_Threshold",
                "Talk_To_Npc", "Turn_In"}) {
            ObjectiveKindAsset asset = read("/Server/ZiggfreedCommon/ObjectiveKinds/" + id + ".json", id);
            assertNotNull(asset, id + " decodes");
            assertNotNull(asset.getProducible(), id + " states whether content may use it");
        }
    }

    @Test
    void theShippedKillKindNamesACreatureAndCarriesAFamilyPicture() throws IOException {
        ObjectiveKindAsset asset =
                read("/Server/ZiggfreedCommon/ObjectiveKinds/Kill_Entity.json", "Kill_Entity");
        assertNotNull(asset.getTargetNames(), "the kill kind says what its target names");
        assertEquals(Boolean.TRUE, asset.getTargetNames().getEntity(), "a kill target is a creature");
        assertNotNull(asset.getPresentation(), "it carries a presentation");
        assertFalse(asset.getPresentation().getTargetIcons().isEmpty(),
                "a family whose members each have a portrait but which has none itself is pointed at one");
    }

    @Test
    void theShippedValueBasedKindIsTheOnlyOneOfTheSix() throws IOException {
        assertEquals(Boolean.TRUE,
                read("/Server/ZiggfreedCommon/ObjectiveKinds/Stat_Threshold.json", "Stat_Threshold")
                        .getValueBased(),
                "a standing value is a high-water mark, never an accumulating tally");
        assertEquals(Boolean.FALSE,
                read("/Server/ZiggfreedCommon/ObjectiveKinds/Break_Block.json", "Break_Block")
                        .getValueBased(),
                "breaking blocks accumulates");
    }

    /** Load one authored file into the table and fold it, as the asset listener does at boot. */
    private void fold(@Nonnull String fileId, @Nonnull String json) throws IOException {
        ObjectiveKindConfig.getInstance().loadDefaults(Map.of(fileId, decode(json, fileId)));
        ObjectiveKindFold.foldInto(kinds);
    }

    @Nonnull
    private static ObjectiveKindAsset decode(@Nonnull String json, @Nonnull String id) throws IOException {
        ObjectiveKindAsset asset = ObjectiveKindAsset.CODEC.decodeJsonAsset(
                RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ObjectiveKindAsset.class, id, null)));
        assertNotNull(asset, "the fixture decodes");
        return asset;
    }

    @Nonnull
    private static ObjectiveKindAsset read(@Nonnull String path, @Nonnull String id) throws IOException {
        String json;
        try (var in = ObjectiveKindFoldTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing shipped kind file: " + path);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return decode(json, id);
    }
}
