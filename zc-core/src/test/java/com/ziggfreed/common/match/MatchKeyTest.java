package com.ziggfreed.common.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.match.MatchKey.Form;

/**
 * The key grammar a Match or Qualifier is written in: which spellings are set keys and which stay name patterns
 * exactly as before, how two spellings of one key fold together, and where a set sits on the shared ladder. Pure
 * parse and rank; what a set holds is the engine's answer and is not asked here.
 */
class MatchKeyTest {

    @Test
    void eachPrefixNamesItsFormWithoutRegardToCase() {
        assertEquals(Form.ITEM_TAG, MatchKey.parse("ItemTag:Type=Ore").form());
        assertEquals(Form.ITEM_TAG, MatchKey.parse("itemtag:type=ore").form());
        assertEquals(Form.BLOCK_FAMILY, MatchKey.parse("BlockFamily:Ore").form());
        assertEquals(Form.BLOCK_FAMILY, MatchKey.parse("BLOCKFAMILY:Ore").form());
        assertEquals(Form.NPC_GROUP, MatchKey.parse("NpcGroup:Goblin").form());
        assertEquals(Form.NPC_GROUP, MatchKey.parse("npcgroup:Goblin").form());
    }

    @Test
    void aSetIdIsTrimmedKeptAsWrittenAndExact() {
        MatchKey tag = MatchKey.parse("  ItemTag:  Family=Sword ");
        assertTrue(tag.isSet());
        assertEquals("Family=Sword", tag.set());
        assertNull(tag.name(), "a set key carries no name pattern");

        MatchKey starred = MatchKey.parse("BlockFamily:Ore*");
        assertEquals("Ore*", starred.set(), "a * in a set id is a literal character, never a wildcard");
        assertFalse(starred.isDefaultRule());
    }

    @Test
    void twoSpellingsOfOneKeyFoldTogetherAndDifferentFormsNever() {
        assertEquals(MatchKey.parse("ItemTag:Type=Ore").foldKey(), MatchKey.parse("itemtag: type=ore").foldKey());
        assertEquals(MatchKey.parse("NpcGroup:Goblin").foldKey(), MatchKey.parse("NPCGROUP:goblin").foldKey());
        assertNotEquals(MatchKey.parse("BlockFamily:Ore").foldKey(), MatchKey.parse("ItemTag:Ore").foldKey());
        assertNotEquals(MatchKey.parse("BlockFamily:Ore").foldKey(), MatchKey.parse("Ore").foldKey(),
                "a set key never collapses onto the name pattern spelling its id");
    }

    @Test
    void everyOtherSpellingIsTheNamePatternItAlwaysWas() {
        for (String authored : List.of("Ore_*", "*_Cracked", "*Geode*", "Rock_Stone", "*", "tag:Hit_Bomb",
                "cause:Fire", "Family_Tree", "ItemTagged", "Group_Leader", "hytale:thing")) {
            MatchKey key = MatchKey.parse(authored);
            assertEquals(Form.NAME, key.form(), authored + " must stay a name pattern");
            assertNotNull(key.name());
            assertEquals(authored.toLowerCase(Locale.ROOT), key.foldKey(),
                    authored + " must fold exactly as its pattern always did");
            assertEquals(NameMatchRank.ofPattern(NamePattern.parse(authored)), key.rank());
        }
    }

    @Test
    void aBlankKeyIsTheCatchAllFoldedAsWritten() {
        MatchKey blank = MatchKey.parse("  ");
        assertEquals(Form.NAME, blank.form());
        assertTrue(blank.isDefaultRule());
        assertEquals("", blank.foldKey(), "a blank Match folds apart from a written *, as it always has");
        assertTrue(MatchKey.parse(null).isDefaultRule());
    }

    @Test
    void aSetRanksUnderEveryPatternAndOverOnlyTheCatchAll() {
        NameMatchRank exact = MatchKey.parse("Ore_Iron").rank();
        NameMatchRank partial = MatchKey.parse("*Ore*").rank();
        NameMatchRank set = MatchKey.parse("BlockFamily:Ore").rank();
        NameMatchRank any = MatchKey.parse("*").rank();

        assertTrue(exact.isMoreSpecificThan(set), "a specific id beats a broad set");
        assertTrue(partial.isMoreSpecificThan(set), "any pattern pinning down characters beats a set");
        assertTrue(set.isMoreSpecificThan(any), "a set beats the catch-all");
        assertEquals(NameMatchRank.SET_BAND, set.band());

        List<NameMatchRank> shuffled = new ArrayList<>(List.of(any, set, partial, exact));
        Collections.sort(shuffled);
        assertEquals(List.of(exact, partial, set, any), shuffled);
    }

    @Test
    void twoSetsOfAnyFormAreEquallySpecific() {
        assertEquals(MatchKey.parse("ItemTag:Type=Ore").rank(), MatchKey.parse("BlockFamily:Ore").rank());
        assertEquals(MatchKey.parse("NpcGroup:Goblin").rank(), MatchKey.parse("ItemTag:Weapon").rank());
    }

    @Test
    void aSetNamingNothingCoversNothing() {
        MatchKey empty = MatchKey.parse("ItemTag:");
        assertTrue(empty.isSet());
        assertEquals("", empty.set());
        assertFalse(empty.matches("Ore_Iron", "ore_iron"));
    }

    @Test
    void aTagIsCarriedWhenTheEnginesExpandedMapHoldsItsKey() {
        // The engine's raw map as it keeps it: the authored key, then every value and key=value as keys of their own.
        Map<String, String[]> raw = Map.of("Type", new String[] {"Ore"}, "Ore", new String[0],
                "Type=Ore", new String[0]);
        assertTrue(MatchSets.carriesTag(raw, "Type=Ore"));
        assertTrue(MatchSets.carriesTag(raw, "type=ore"), "case is ignored");
        assertTrue(MatchSets.carriesTag(raw, "Ore"));
        assertFalse(MatchSets.carriesTag(raw, "Type=Rock"));
        assertFalse(MatchSets.carriesTag(null, "Ore"));
    }
}
