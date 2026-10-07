package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.util.RawJsonReader;

/** A gear set reads the quest block's {@code Requires} shape, a child keeps it, and the editor lists it. */
class GearSetRequiresTest {

    private static GearSetAsset set(String id, String json, GearSetAsset parent) throws IOException {
        return GearSetAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(GearSetAsset.class, id,
                        parent == null ? null : parent.getId())));
    }

    @Test
    void aSetReadsRequiresAndAChildKeepsItsBasesBlock() throws IOException {
        GearSetAsset base = set("Mmo_Harvest_Base", "{ \"Requires\": { \"Factors\": [ { \"Factor\":"
                + " \"hytale:mod_installed\", \"Param\": \"Ziggfreed:MMOSkillTree\", \"Min\": 1 } ] } }", null);
        GearSetAsset child = set("Mmo_Harvest_Set", "{ \"Members\": [ \"Harvest_Feast_Hat\" ] }", base);

        assertNotNull(child.getRequires());
        assertEquals("Ziggfreed:MMOSkillTree", child.getRequires().factorsOrEmpty()[0].getParam());
        assertNull(set("Plain_Set", "{}", null).getRequires());
    }

    @Test
    void theEditorListsTheLeafWithItsSentence() {
        assertNotNull(GearSetAsset.CODEC.toSchema(new SchemaContext()).getProperties().get("Requires")
                .getMarkdownDescription());
    }
}
