package com.ziggfreed.common.inventory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.inventory.InventoryComponent;

/**
 * Which inventory sections the snapshot and the strip manage, and which ones a restore clears.
 * Engine-free: the section ids are compile-time constants and no {@code Store} is touched, so a snapshot
 * is built from its captured sections directly.
 */
class InventorySectionsTest {

    private static final int ABILITIES = InventoryComponent.ABILITIES_SECTION_ID;
    private static final int RUNE_BAG = InventoryComponent.RUNE_BAG_SECTION_ID;
    private static final int[] ORIGINAL_SIX = {
        InventoryComponent.ARMOR_SECTION_ID,
        InventoryComponent.HOTBAR_SECTION_ID,
        InventoryComponent.STORAGE_SECTION_ID,
        InventoryComponent.UTILITY_SECTION_ID,
        InventoryComponent.TOOLS_SECTION_ID,
        InventoryComponent.BACKPACK_SECTION_ID,
    };

    private static boolean contains(int[] ids, int id) {
        return Arrays.stream(ids).anyMatch(each -> each == id);
    }

    /** A snapshot that captured exactly these sections, each holding nothing. */
    private static InventorySnapshot capturedSections(int... sectionIds) {
        List<InventorySnapshot.SectionData> sections = new ArrayList<>();
        for (int id : sectionIds) {
            sections.add(new InventorySnapshot.SectionData(id, (byte) -1, Map.of()));
        }
        return new InventorySnapshot(sections);
    }

    @Test
    void theRuneAbilitiesAndTheRuneBagAreManagedSections() {
        assertTrue(contains(InventorySections.ALL, ABILITIES), "slotted rune abilities are managed");
        assertTrue(contains(InventorySections.ALL, RUNE_BAG), "the rune bag is managed");
        assertTrue(contains(InventorySnapshot.SECTION_IDS, ABILITIES), "so a snapshot captures and strips them");
        assertTrue(contains(InventorySnapshot.SECTION_IDS, RUNE_BAG));
        assertTrue(InventoryStripPolicy.STRIP_ALL.coversSection(ABILITIES), "and the default strip takes them");
        assertTrue(InventoryStripPolicy.STRIP_ALL.clearsSection(RUNE_BAG));
    }

    @Test
    void theOriginalSixStayManaged() {
        for (int id : ORIGINAL_SIX) {
            assertTrue(contains(InventorySections.ALL, id), "section " + id);
        }
    }

    @Test
    void everyManagedSectionAppearsOnce() {
        assertEquals(InventorySections.ALL.length, Arrays.stream(InventorySections.ALL).distinct().count());
        assertEquals(ORIGINAL_SIX.length + 2, InventorySections.ALL.length,
                "the original six and the two rune sections");
    }

    @Test
    void aRestoreLeavesASectionItsSnapshotNeverCapturedAlone() {
        // A snapshot taken before the rune sections were managed: that round never stripped them, so the
        // player carried them through it, and the restore must not wipe them.
        InventorySnapshot older = capturedSections(ORIGINAL_SIX);

        int[] cleared = older.sectionsToClear();

        assertFalse(contains(cleared, ABILITIES));
        assertFalse(contains(cleared, RUNE_BAG));
        assertArrayEquals(ORIGINAL_SIX, cleared, "exactly what it captured, in capture order");
    }

    @Test
    void aRestoreClearsEverySectionItsSnapshotCaptured() {
        InventorySnapshot current = capturedSections(InventorySections.ALL);

        assertArrayEquals(InventorySections.ALL, current.sectionsToClear(),
                "a capture records every section the entity has, empty or not, so all of them clear");
    }
}
