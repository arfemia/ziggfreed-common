package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decode;
import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.ContentText;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * {@code Text.Lore}: the plain fallback paragraph per lifecycle state, carried from the file onto
 * the runtime {@link ContentText} under the same three state words every surface asks by.
 */
class QuestLoreTest {

    private static final String LORE_JSON = """
            { "Text": { "TitleKey": "quest.errand.title",
                        "Lore": { "Incomplete": "Somebody should look into that.",
                                  "Active": "You said you would look into it.",
                                  "Complete": "Well, that is that." } },
              "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore" } } }
            """;

    @Test
    void eachStateParagraphReachesTheRuntimeTextUnderItsStateWord() throws Exception {
        QuestDefinition definition = decodeRoot(LORE_JSON, "errand").toDefinition(null);

        assertEquals(Map.of(
                ContentTextAsset.Lore.STATE_INCOMPLETE, "Somebody should look into that.",
                ContentTextAsset.Lore.STATE_ACTIVE, "You said you would look into it.",
                ContentTextAsset.Lore.STATE_COMPLETE, "Well, that is that."), definition.lore());

        ContentText text = definition.quest().text();
        assertEquals("You said you would look into it.",
                text.lore(ContentTextAsset.Lore.STATE_ACTIVE).getRawText(),
                "the paragraph is a raw fallback, so it reaches the client as typed");
        assertEquals("Well, that is that.", text.lore(ContentTextAsset.Lore.STATE_COMPLETE).getRawText());
        assertNull(text.lore("somewhere_else"), "a state nothing authored reads as nothing");
    }

    @Test
    void aQuestWithNoLoreCarriesNoneAndAStateLeftBlankIsDropped() throws Exception {
        QuestDefinition bare = decodeRoot("{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\" } } }",
                "bare").toDefinition(null);
        assertTrue(bare.lore().isEmpty());
        assertNull(bare.quest().text().lore(ContentTextAsset.Lore.STATE_ACTIVE));

        QuestDefinition partial = decodeRoot("""
                { "Text": { "Lore": { "Active": "   ", "Complete": "Done." } },
                  "Objectives": { "collect": { "Kind": "PICKUP_ITEM" } } }
                """, "partial").toDefinition(null);
        assertEquals(Map.of(ContentTextAsset.Lore.STATE_COMPLETE, "Done."), partial.lore(),
                "a blank paragraph is not a paragraph");
    }

    @Test
    void aChildRetuningOneStateKeepsTheParentsOthers() throws Exception {
        QuestAsset parent = decodeRoot(LORE_JSON, "errand_base");
        QuestDefinition child = decode("""
                { "Text": { "Lore": { "Active": "Still on it, then." } } }
                """, "errand_child", "errand_base", parent).toDefinition(null);

        assertEquals("Still on it, then.", child.lore().get(ContentTextAsset.Lore.STATE_ACTIVE));
        assertEquals("Somebody should look into that.", child.lore().get(ContentTextAsset.Lore.STATE_INCOMPLETE),
                "every leaf of the group inherits on its own");
        assertEquals("quest.errand.title", child.titleKey(), "and the sibling leaves of Text survive too");
    }
}
