package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@code _}-marked folder to id fold. The point of every case here is that an UNMARKED tree
 * keeps the ids it already had, so turning this on can never move content out from under an author
 * who did not ask for it.
 */
class NestedAssetIdTest {

    @Nested
    class WhichFoldersCount {

        @Test
        void anUnmarkedFolderContributesNothing() {
            assertEquals("trork_trouble",
                    NestedAssetId.effectiveId("Quests/MMOSkillTree/Zones/Trork_Trouble.json", "Trork_Trouble"));
        }

        @Test
        void aMarkedFolderContributesItsNameWithoutTheMarker() {
            assertEquals("wilds_trork_trouble",
                    NestedAssetId.effectiveId("Quests/MMOSkillTree/Zones/_Wilds/Trork_Trouble.json",
                            "Trork_Trouble"));
        }

        @Test
        void markedFoldersStackInPathOrder() {
            assertEquals("wilds_act1_trork_trouble",
                    NestedAssetId.effectiveId("Quests/_Wilds/_Act1/Trork_Trouble.json", "Trork_Trouble"));
            assertEquals(List.of("wilds", "act1"),
                    NestedAssetId.prefixesOf("Quests/_Wilds/_Act1/Trork_Trouble.json"));
        }

        @Test
        void unmarkedFoldersBetweenMarkedOnesAreSkippedNotCollapsed() {
            assertEquals("wilds_act1_trork_trouble",
                    NestedAssetId.effectiveId("Quests/_Wilds/Chapters/_Act1/Trork_Trouble.json",
                            "Trork_Trouble"));
        }

        @Test
        void aFileWithNoFolderAtAllKeepsItsPlainId() {
            assertEquals("trork_trouble", NestedAssetId.effectiveId("Trork_Trouble.json", "Trork_Trouble"));
            assertEquals("trork_trouble", NestedAssetId.effectiveId((String) null, "Trork_Trouble"));
        }

        @Test
        void theFilenameItselfNeverContributesEvenWhenMarked() {
            // The marker names a FOLDER. A file called _Hidden.json is just a file.
            assertEquals("_hidden", NestedAssetId.effectiveId("Quests/_Hidden.json", "_Hidden"));
        }

        @Test
        void abareUnderscoreIsNotAMarkedFolder() {
            assertFalse(NestedAssetId.isPrefixDirectory("_"));
            assertTrue(NestedAssetId.isPrefixDirectory("_Wilds"));
            assertFalse(NestedAssetId.isPrefixDirectory("Wilds"));
            assertFalse(NestedAssetId.isPrefixDirectory(null));
            assertEquals("trork_trouble", NestedAssetId.effectiveId("Quests/_/Trork_Trouble.json",
                    "Trork_Trouble"));
        }
    }

    @Nested
    class Shape {

        @Test
        void theWholeIdIsLowerCased() {
            assertEquals("wilds_trork_trouble",
                    NestedAssetId.effectiveId("_WILDS/TRORK_TROUBLE.json", "TRORK_TROUBLE"));
        }

        @Test
        void bothPathSeparatorsAreRead() {
            assertEquals("wilds_trork_trouble",
                    NestedAssetId.effectiveId("Quests\\_Wilds\\Trork_Trouble.json", "Trork_Trouble"));
        }

        @Test
        void theSameFileAlwaysYieldsTheSameId() {
            String once = NestedAssetId.effectiveId("Quests/_Wilds/Trork_Trouble.json", "Trork_Trouble");
            String twice = NestedAssetId.effectiveId("Quests/_Wilds/Trork_Trouble.json", "Trork_Trouble");
            assertEquals(once, twice);
        }

        @Test
        void twoFoldersUnderOneNameCollideOnPurposeSoTheStoreCanReportIt() {
            // Nothing here prevents a collision; the store's DUPLICATE_QUEST_ID finding is what
            // makes one visible, and it can only fire because this function is deterministic.
            assertEquals(NestedAssetId.effectiveId("_Wilds/Trork_Trouble.json", "Trork_Trouble"),
                    NestedAssetId.effectiveId("Other/_Wilds/Trork_Trouble.json", "Trork_Trouble"));
        }
    }

    @Nested
    class TypeRootBound {

        @Test
        void onlyFoldersBelowTheTypeRootAreRead() {
            // A checkout under D:\_work must not put "work" on the front of every id in it.
            Path path = Path.of("D:", "_work", "packs", "MyPack", "Server", "ZiggfreedCommon", "Quests",
                    "_Wilds", "Trork_Trouble.json");
            assertEquals("wilds_trork_trouble",
                    NestedAssetId.effectiveId(path, "ZiggfreedCommon/Quests", "Trork_Trouble"));
        }

        @Test
        void withoutATypeRootEveryAncestorIsRead() {
            Path path = Path.of("D:", "_work", "Quests", "Trork_Trouble.json");
            assertEquals("work_trork_trouble", NestedAssetId.effectiveId(path, null, "Trork_Trouble"));
        }

        @Test
        void theLastOccurrenceOfTheTypeRootWins() {
            Path path = Path.of("packs", "ZiggfreedCommon", "Quests", "vendor", "ZiggfreedCommon",
                    "Quests", "_Wilds", "Trork_Trouble.json");
            assertEquals("wilds_trork_trouble",
                    NestedAssetId.effectiveId(path, "ZiggfreedCommon/Quests", "Trork_Trouble"));
        }

        @Test
        void aTypeRootThePathDoesNotContainFallsBackToEveryAncestor() {
            Path path = Path.of("somewhere", "_Wilds", "Trork_Trouble.json");
            assertEquals("wilds_trork_trouble",
                    NestedAssetId.effectiveId(path, "ZiggfreedCommon/Quests", "Trork_Trouble"));
        }

        @Test
        void aNullPathIsTheBareFilenameId() {
            assertEquals("trork_trouble",
                    NestedAssetId.effectiveId((Path) null, "ZiggfreedCommon/Quests", "Trork_Trouble"));
        }
    }
}
