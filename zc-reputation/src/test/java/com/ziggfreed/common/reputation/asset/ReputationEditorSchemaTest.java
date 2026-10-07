package com.ziggfreed.common.reputation.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.IntegerSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;

/**
 * What the exported schema says to the in-game Asset Editor: every leaf carries a sentence and its
 * effective default, every array says what it holds, every engine id offers the editor's own picker, and
 * the rank-name map knows its $Comment key.
 */
class ReputationEditorSchemaTest {

    private static final List<String> LEAVES = List.of(
            "Enabled", "Text", "Icon", "Order", "Gear", "Cap", "Ranks", "Kills", "Beyond", "Earn");

    @Test
    void theTopLevelLeavesDeclareTheirDefaultsAndSentences() {
        Map<String, Schema> leaves = ReputationAsset.CODEC.toSchema(new SchemaContext()).getProperties();
        assertTrue(leaves.keySet().containsAll(LEAVES), leaves.keySet().toString());
        assertEquals(Boolean.TRUE, ((BooleanSchema) leaves.get("Enabled")).getDefault(),
                "unauthored Enabled is true, and the schema must say so or the editor shows an unchecked box");
        assertEquals(Integer.valueOf(0), ((IntegerSchema) leaves.get("Order")).getDefault());
        assertEquals("Item", leaves.get("Icon").getHytaleAssetRef(), "Icon offers the editor's item picker");
        for (String leaf : LEAVES) {
            assertNotNull(leaves.get(leaf).getMarkdownDescription(), leaf + " carries no sentence");
        }
    }

    @Test
    void theKillsListSaysWhatItHoldsAndEachGroupOffersThePicker() {
        ArraySchema kills = (ArraySchema) ReputationAsset.CODEC.toSchema(new SchemaContext()).getProperties()
                .get("Kills");
        assertNotNull(kills.getItems(), "Kills says it holds kill rows");
        ObjectSchema row = ReputationAsset.Kill.CODEC.toSchema(new SchemaContext());
        ArraySchema groups = (ArraySchema) row.getProperties().get("NPCGroups");
        assertEquals("NPCGroup", ((StringSchema) groups.getItems()).getHytaleAssetRef(),
                "each entry is one native NPC group, so each entry offers the picker");
    }

    @Test
    void theGearStatOffersTheStatPicker() {
        ObjectSchema gear = ReputationAsset.Gear.CODEC.toSchema(new SchemaContext());
        assertEquals("EntityStatType", gear.getProperties().get("Stat").getHytaleAssetRef());
    }

    @Test
    void theRanksMapDeclaresTheCommentKeyAndAnyRank() {
        SchemaContext context = new SchemaContext();
        ObjectSchema ranks = objectOf(ReputationAsset.CODEC.toSchema(context).getProperties().get("Ranks"), context);
        assertTrue(ranks.getProperties().containsKey("$Comment"),
                "a $Comment inside the map is a known key, so the editor's property pane mounts");
        assertNotNull(ranks.getAdditionalProperties(), "any other key is a rank id");
    }

    @Test
    void theKillAmountAndTheBeyondEveryDeclareTheirZeroDefaults() {
        ObjectSchema row = ReputationAsset.Kill.CODEC.toSchema(new SchemaContext());
        assertEquals(Integer.valueOf(0), ((IntegerSchema) row.getProperties().get("Amount")).getDefault(),
                "an unauthored Amount moves nothing, and the schema must say so");
        ObjectSchema beyond = ReputationAsset.Beyond.CODEC.toSchema(new SchemaContext());
        assertEquals(Integer.valueOf(0), ((IntegerSchema) beyond.getProperties().get("Every")).getDefault(),
                "an unauthored Every pays nothing, and the schema must say so");
    }

    @Test
    void theEarnLinesSayWhatTheyHold() {
        ObjectSchema earn = ReputationAsset.Earn.CODEC.toSchema(new SchemaContext());
        assertNotNull(((ArraySchema) earn.getProperties().get("Lines")).getItems(), "each entry is one line's key");
        assertNotNull(earn.getProperties().get("Lines").getMarkdownDescription());
    }

    @Test
    void theBeyondRewardsListSaysWhatItHolds() {
        ObjectSchema beyond = ReputationAsset.Beyond.CODEC.toSchema(new SchemaContext());
        assertNotNull(((ArraySchema) beyond.getProperties().get("Rewards")).getItems());
    }

    @Test
    void aRankEntryDeclaresItsFloorWithASentence() {
        ObjectSchema rank = ReputationAsset.RankName.CODEC.toSchema(new SchemaContext());
        assertNotNull(rank.getProperties().get("From"), "a rank entry declares From");
        assertNotNull(rank.getProperties().get("From").getMarkdownDescription(), "and says when it applies");
    }

    /** The object a leaf describes: itself, or the definition it references (unwrapping a nullable union). */
    @Nonnull
    private static ObjectSchema objectOf(@Nonnull Schema leaf, @Nonnull SchemaContext context) {
        Schema candidate = leaf.getAnyOf() == null ? leaf : leaf.getAnyOf()[0];
        if (candidate instanceof ObjectSchema object) {
            return object;
        }
        String ref = candidate.getRef();
        assertNotNull(ref, "the leaf is neither an object nor a reference");
        for (Map.Entry<String, Schema> entry : context.getDefinitions().entrySet()) {
            if (ref.endsWith(entry.getKey()) && entry.getValue() instanceof ObjectSchema object) {
                return object;
            }
        }
        throw new AssertionError("no definition behind " + ref + " in " + context.getDefinitions().keySet());
    }
}
