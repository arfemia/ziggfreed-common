package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.asset.PresenceRequiresCodec;
import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.loot.trigger.BonusRowAsset;

/**
 * A loot table and a bonus row read the quest block's {@code Requires} shape, a child keeps its base's
 * block, and the mod gate reads it; the editor lists the leaf with its sentence.
 */
class PresenceRequiresLeafTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";
    private static final String GATE = "\"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
            + " \"Param\": \"" + MMO + "\", \"Min\": 1 } ] }";

    @AfterEach
    void restore() {
        ModGates.useProbeForTests(null);
    }

    private static LootableAsset table(String id, String json, LootableAsset parent) throws IOException {
        return LootableAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(LootableAsset.class, id,
                        parent == null ? null : parent.getId())));
    }

    @Test
    void aTableReadsRequiresAndAChildKeepsItsBasesBlock() throws IOException {
        LootableAsset base = table("Mmo_Xp_Base", "{ " + GATE + ", \"ContributesTo\": \"Harvest_Feast_Table\" }", null);
        LootableAsset child = table("Mmo_Xp_Pies", "{ \"Rolls\": [] }", base);

        assertNotNull(child.getRequires());
        assertEquals(MMO, child.getRequires().factorsOrEmpty()[0].getParam());
        assertNull(table("Plain", "{}", null).getRequires(), "unauthored means it loads everywhere");

        ModGates.useProbeForTests(param -> 0.0);
        assertFalse(PresenceRequiresCodec.passesModGate(child.getRequires()));
        assertTrue(PresenceRequiresCodec.passesModGate(null));
    }

    @Test
    void aBonusRowReadsRequires() throws IOException {
        BonusRowAsset row = BonusRowAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(
                "{ " + GATE + ", \"When\": { \"Kind\": \"BreakBlock\", \"Match\": \"Rock_*\" } }"), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(BonusRowAsset.class, "Mmo_Rock_Bonus", null)));

        assertNotNull(row.getRequires());
        assertEquals(MMO, row.getRequires().factorsOrEmpty()[0].getParam());
    }

    @Test
    void theEditorListsTheLeafWithItsSentence() {
        assertNotNull(LootableAsset.CODEC.toSchema(new SchemaContext()).getProperties().get("Requires")
                .getMarkdownDescription());
        assertNotNull(BonusRowAsset.CODEC.toSchema(new SchemaContext()).getProperties().get("Requires")
                .getMarkdownDescription());
    }
}
