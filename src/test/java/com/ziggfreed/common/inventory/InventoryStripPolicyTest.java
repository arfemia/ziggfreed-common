package com.ziggfreed.common.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.inventory.InventoryComponent;

/**
 * Engine-free unit tests for {@link InventoryStripPolicy}'s decision surface: the section-coverage
 * dials ({@code coversSection} / {@code clearsSection}) and the {@code ALL} / {@code WHITELIST} /
 * {@code BLACKLIST} item rule ({@code shouldStrip}). No {@link com.hypixel.hytale.component.Store}
 * is touched, so this is pure logic (the Hytale jar is on the test classpath only for the section-id
 * constants).
 */
class InventoryStripPolicyTest {

    private static final int ARMOR = InventoryComponent.ARMOR_SECTION_ID;
    private static final int HOTBAR = InventoryComponent.HOTBAR_SECTION_ID;
    private static final int STORAGE = InventoryComponent.STORAGE_SECTION_ID;

    @Test
    void stripAllCoversAndClearsEverySection() {
        InventoryStripPolicy p = InventoryStripPolicy.STRIP_ALL;
        for (int id : InventorySections.ALL) {
            assertTrue(p.coversSection(id), "STRIP_ALL covers section " + id);
            assertTrue(p.clearsSection(id), "STRIP_ALL clears section " + id);
        }
        assertTrue(p.shouldStrip("anything"));
        assertTrue(p.shouldStrip(""));
    }

    @Test
    void keepSectionsLeavesThatSectionIntact() {
        InventoryStripPolicy p = InventoryStripPolicy.builder().keepSections(ARMOR).build();
        assertFalse(p.coversSection(ARMOR), "kept section is not covered");
        assertFalse(p.clearsSection(ARMOR), "kept section is not cleared");
        assertTrue(p.coversSection(HOTBAR), "non-kept section stays covered");
        assertTrue(p.clearsSection(HOTBAR));
    }

    @Test
    void sectionsRestrictsToExactlyTheGivenSet() {
        InventoryStripPolicy p = InventoryStripPolicy.builder().sections(HOTBAR).build();
        assertTrue(p.coversSection(HOTBAR));
        assertFalse(p.coversSection(ARMOR));
        assertFalse(p.coversSection(STORAGE));
    }

    @Test
    void whitelistStripsOnlyListedIdsAndIsNotAFastClear() {
        InventoryStripPolicy p = InventoryStripPolicy.builder().whitelist(List.of("sword", "shield")).build();
        assertTrue(p.shouldStrip("sword"));
        assertTrue(p.shouldStrip("shield"));
        assertFalse(p.shouldStrip("bread"));
        // An item filter means the whole section can't be cleared in one transaction.
        assertTrue(p.coversSection(HOTBAR));
        assertFalse(p.clearsSection(HOTBAR), "a filtered section is not a one-transaction clear");
    }

    @Test
    void blacklistKeepsListedIdsAndStripsTheRest() {
        InventoryStripPolicy p = InventoryStripPolicy.builder().blacklist(List.of("bread", "torch")).build();
        assertFalse(p.shouldStrip("bread"), "blacklisted id is kept");
        assertFalse(p.shouldStrip("torch"));
        assertTrue(p.shouldStrip("sword"), "non-blacklisted id is stripped");
        assertFalse(p.clearsSection(HOTBAR), "a filtered section is not a one-transaction clear");
    }

    @Test
    void uncoveredSectionNeverClears() {
        InventoryStripPolicy p = InventoryStripPolicy.builder().sections(HOTBAR).build();
        // ALL rule but the section isn't covered -> not a fast clear.
        assertFalse(p.clearsSection(ARMOR));
    }
}
