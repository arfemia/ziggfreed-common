package com.ziggfreed.common.quest.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * How a QUEST category is presented, the same four leaves an achievement category draws with: where
 * it sorts, what illustrates it, what it is called and its accent colour. Authored at
 * {@code Server/ZiggfreedCommon/QuestCategories/<category>.json}, folded like every framework config.
 *
 * <p>Fixtures are authored HERE; nothing below reads content anybody ships.
 */
class QuestCategoryAssetTest {

    private static QuestCategoryAsset decode(String id, String json, @Nullable QuestCategoryAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestCategoryAsset.class, id,
                parent == null ? null : parent.getId());
        QuestCategoryAsset asset = QuestCategoryAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
        assertNotNull(asset, "quest category '" + id + "' did not decode");
        return asset;
    }

    @Test
    void theStoreLivesBesideTheOtherFrameworkTypes() {
        assertEquals("ZiggfreedCommon/QuestCategories", QuestCategoryAsset.TYPE_ROOT);
    }

    @Test
    void everyLeafDecodesAndTheIdIsLowerCased() throws IOException {
        QuestCategoryAsset asset = decode("Errands", """
                { "Order": 20, "Icon": "Fixture_Icon", "TitleKey": "fixture.quest.errands",
                  "Accent": "#4A9EFF" }
                """, null);

        assertEquals("errands", asset.getId(), "a PascalCase filename names the category content writes in lower case");
        assertEquals(Integer.valueOf(20), asset.getOrder());
        assertEquals("Fixture_Icon", asset.getIcon());
        assertEquals("fixture.quest.errands", asset.getTitleKey());
        assertEquals("#4a9eff", asset.getAccent());
    }

    @Test
    void everyLeafIsOptional() throws IOException {
        QuestCategoryAsset asset = decode("Chores", "{ \"Icon\": \"Fixture_Icon\" }", null);

        assertEquals("Fixture_Icon", asset.getIcon());
        assertNull(asset.getOrder());
        assertNull(asset.getTitleKey());
        assertNull(asset.getAccent());
        assertEquals(Integer.MAX_VALUE, asset.orderOrLast(),
                "a category naming no Order sorts after every category that named one");
    }

    @Test
    void aChildInheritsWhatItDoesNotRestate() throws IOException {
        QuestCategoryAsset parent = decode("Base", """
                { "Order": 5, "Icon": "Fixture_Icon", "TitleKey": "fixture.quest.base", "Accent": "#336699" }
                """, null);

        QuestCategoryAsset child = decode("Child", "{ \"Accent\": \"#aa0000\" }", parent);

        assertEquals("#aa0000", child.getAccent());
        assertEquals(Integer.valueOf(5), child.getOrder());
        assertEquals("Fixture_Icon", child.getIcon());
        assertEquals("fixture.quest.base", child.getTitleKey());
    }

    @Test
    void categoriesReadByOrderThenIdAndAnOwnerOutranksAPack() {
        QuestCategoryConfig config = QuestCategoryConfig.getInstance();
        try {
            config.mergePackLayer(Map.of(
                    "zulu", QuestCategoryAsset.of("zulu", 5, null, null, null),
                    "alpha", QuestCategoryAsset.of("alpha", 20, null, null, "#112233"),
                    "bravo", QuestCategoryAsset.of("bravo", 20, null, null, null),
                    "unranked", QuestCategoryAsset.of("unranked", null, null, null, null)));
            config.mergeOwnerLayer(Map.of(
                    "alpha", QuestCategoryAsset.of("alpha", 20, null, null, "#445566")));

            assertEquals(List.of("zulu", "alpha", "bravo", "unranked"),
                    config.ordered().stream().map(QuestCategoryAsset::getId).toList());
            assertEquals(List.of("zulu", "alpha", "bravo"), config.orderedIds(),
                    "only a category that named an Order takes a place in the declared order");
            QuestCategoryAsset alpha = config.category("ALPHA");
            assertNotNull(alpha, "a category is addressable in any casing");
            assertEquals("#445566", alpha.getAccent(), "the server owner's file is the one that stands");
            assertNull(config.category("nothing-describes-this"));
            assertNull(config.category(null));
        } finally {
            config.mergePackLayer(Map.of());
            config.mergeOwnerLayer(Map.of());
        }
    }

    @Test
    void theSchemaOffersTheItemPickerAndExplainsEveryLeaf() {
        Map<String, Schema> leaves = QuestCategoryAsset.CODEC.toSchema(new SchemaContext()).getProperties();
        List<String> authored = List.of("Order", "Icon", "TitleKey", "Accent");
        assertTrue(leaves.keySet().containsAll(authored), leaves.keySet().toString());

        assertEquals("Item", leaves.get("Icon").getHytaleAssetRef(), "Icon offers the editor's item picker");
        for (String leaf : authored) {
            assertNotNull(leaves.get(leaf).getMarkdownDescription(), leaf + " carries no sentence");
        }
    }
}
