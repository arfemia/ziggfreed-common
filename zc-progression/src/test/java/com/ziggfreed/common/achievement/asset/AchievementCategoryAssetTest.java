package com.ziggfreed.common.achievement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The category leaves a listing draws with: an accent colour, the calendar event a category belongs
 * to, and the switch that files every subcategory under an event of its own. Each is optional, each
 * inherits through {@code Parent}, a server owner's file outranks a pack's, and the exported schema
 * stays one the Asset Editor can open.
 *
 * <p>Fixtures are authored HERE; nothing below reads content anybody ships.
 */
class AchievementCategoryAssetTest {

    private static AchievementCategoryAsset decode(String id, String json,
            @Nullable AchievementCategoryAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(AchievementCategoryAsset.class, id,
                parent == null ? null : parent.getId());
        AchievementCategoryAsset asset = AchievementCategoryAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
        assertNotNull(asset, "category '" + id + "' did not decode");
        return asset;
    }

    // ==================== the new leaves ====================

    @Test
    void theAccentTheEventAndTheSubcategorySwitchDecode() throws IOException {
        AchievementCategoryAsset asset = decode("Festivals", """
                { "Accent": "#E07B2A", "Event": "Fixture_Fair", "SubcategoryEvents": true }
                """, null);

        assertEquals("festivals", asset.getId());
        assertEquals("#e07b2a", asset.getAccent(), "an accent reads in one casing whatever the author typed");
        assertEquals("fixture_fair", asset.getEvent(), "an event id reads in the form every id compares in");
        assertTrue(asset.isSubcategoryEvents());
    }

    @Test
    void aFileNamingNoneOfThemDeclaresNoneOfThem() throws IOException {
        AchievementCategoryAsset asset = decode("Gathering", "{ \"Order\": 10 }", null);

        assertNull(asset.getAccent(), "an unauthored accent is left to the surface");
        assertNull(asset.getEvent(), "an unauthored event ties the category to nothing");
        assertFalse(asset.isSubcategoryEvents(), "unauthored, a subcategory is just a word");
    }

    @Test
    void anAccentWrittenInAnyOtherFormReadsAsUnauthored() throws IOException {
        for (String written : List.of("orange", "#e07b2", "#e07b2aff", "e07b2a", "#gg7b2a", "", "  ")) {
            AchievementCategoryAsset asset = decode("Combat", "{ \"Accent\": \"" + written + "\" }", null);
            assertNull(asset.getAccent(), "'" + written + "' is not a #rrggbb colour, so it must not be drawn");
        }
        assertEquals("#336699", decode("Combat", "{ \"Accent\": \" #336699 \" }", null).getAccent(),
                "surrounding spaces are an authoring slip, not a different colour");
    }

    // ==================== Parent ====================

    @Test
    void aChildInheritsEveryLeafItDoesNotRestate() throws IOException {
        AchievementCategoryAsset parent = decode("Base", """
                { "Order": 5, "Icon": "Fixture_Icon", "TitleKey": "fixture.category.base",
                  "Accent": "#336699", "Event": "Fixture_Fair", "SubcategoryEvents": true,
                  "Subcategories": ["first", "second"] }
                """, null);

        AchievementCategoryAsset child = decode("Child", "{ \"Order\": 7 }", parent);

        assertEquals(Integer.valueOf(7), child.getOrder());
        assertEquals("Fixture_Icon", child.getIcon());
        assertEquals("fixture.category.base", child.getTitleKey());
        assertEquals("#336699", child.getAccent());
        assertEquals("fixture_fair", child.getEvent());
        assertTrue(child.isSubcategoryEvents());
        assertEquals(List.of("first", "second"), child.getSubcategories());
    }

    @Test
    void aChildRestatingALeafChangesThatLeafAlone() throws IOException {
        AchievementCategoryAsset parent = decode("Base", """
                { "Accent": "#336699", "Event": "Fixture_Fair", "SubcategoryEvents": true }
                """, null);

        AchievementCategoryAsset child = decode("Child", """
                { "Accent": "#aa0000", "SubcategoryEvents": false }
                """, parent);

        assertEquals("#aa0000", child.getAccent());
        assertFalse(child.isSubcategoryEvents(), "an authored false turns an inherited true off");
        assertEquals("fixture_fair", child.getEvent(), "a leaf the child did not write stays the parent's");
    }

    // ==================== the fold ====================

    @Test
    void aServerOwnersFileOutranksAPacksForTheSameCategory() {
        AchievementCategoryConfig config = AchievementCategoryConfig.getInstance();
        try {
            config.mergePackLayer(Map.of("festivals", AchievementCategoryAsset.of("festivals", 10, null, null,
                    null, "#112233", "Pack_Event", true)));
            config.mergeOwnerLayer(Map.of("festivals", AchievementCategoryAsset.of("festivals", 10, null, null,
                    null, "#445566", null, false)));

            AchievementCategoryAsset folded = config.category("Festivals");
            assertNotNull(folded);
            assertEquals("#445566", folded.getAccent());
            assertNull(folded.getEvent());
            assertFalse(folded.isSubcategoryEvents());
        } finally {
            config.mergePackLayer(Map.of());
            config.mergeOwnerLayer(Map.of());
        }
    }

    // ==================== which event a group rides ====================

    @Test
    void aSubcategoryRidesItsOwnEventOnlyWhenItsCategorySaysSo() {
        AchievementCategoryAsset seasons = AchievementCategoryAsset.of("seasons", null, null, null, null,
                null, null, true);
        assertEquals("fixture_fair", seasons.eventFor("Fixture_Fair"),
                "with the switch on, a subcategory's id is its event's id");
        assertNull(seasons.eventFor(null), "the category itself names no event of its own");
        assertNull(seasons.eventFor("  "));

        AchievementCategoryAsset festival = AchievementCategoryAsset.of("festival", null, null, null, null,
                null, "Fixture_Fair", false);
        assertEquals("fixture_fair", festival.eventFor("stalls"),
                "with the switch off, a category's own event covers every group inside it");
        assertEquals("fixture_fair", festival.eventFor(null));

        AchievementCategoryAsset plain = AchievementCategoryAsset.of("combat", 0, null, null, null);
        assertNull(plain.eventFor("melee"), "a category tied to no event ties nothing inside it either");
    }

    // ==================== the Asset Editor ====================

    @Test
    void theSchemaOffersTheItemPickerDeclaresTheSwitchDefaultAndExplainsEveryLeaf() {
        Map<String, Schema> leaves = AchievementCategoryAsset.CODEC.toSchema(new SchemaContext()).getProperties();
        List<String> authored = List.of("Order", "Icon", "TitleKey", "Accent", "Subcategories", "Event",
                "SubcategoryEvents");
        assertTrue(leaves.keySet().containsAll(authored), leaves.keySet().toString());

        assertEquals("Item", leaves.get("Icon").getHytaleAssetRef(), "Icon offers the editor's item picker");
        assertEquals(Boolean.FALSE, ((BooleanSchema) leaves.get("SubcategoryEvents")).getDefault(),
                "unauthored SubcategoryEvents is false, and the schema must say so");
        assertNotNull(((ArraySchema) leaves.get("Subcategories")).getItems(), "the list says what it holds");
        for (String leaf : authored) {
            assertNotNull(leaves.get(leaf).getMarkdownDescription(), leaf + " carries no sentence");
        }
    }
}
