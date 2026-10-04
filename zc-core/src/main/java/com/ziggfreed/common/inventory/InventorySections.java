package com.ziggfreed.common.inventory;

import com.hypixel.hytale.server.core.inventory.InventoryComponent;

/**
 * The canonical list of the managed inventory sections, in one place so {@link InventorySnapshot}
 * (capture, strip and restore) and {@link InventoryStripPolicy} (the default strip set) cannot drift if
 * the engine ever adds or renames a section: Armor / Hotbar / Storage / Utility / Tool / Backpack, and
 * Update 7's rune Abilities (the slotted rune abilities) and Rune Bag. Package-private: the inventory
 * primitives own this vocabulary.
 */
final class InventorySections {

    /** Every managed section, in a fixed iteration order. */
    static final int[] ALL = {
        InventoryComponent.ARMOR_SECTION_ID,
        InventoryComponent.HOTBAR_SECTION_ID,
        InventoryComponent.STORAGE_SECTION_ID,
        InventoryComponent.UTILITY_SECTION_ID,
        InventoryComponent.TOOLS_SECTION_ID,
        InventoryComponent.BACKPACK_SECTION_ID,
        InventoryComponent.ABILITIES_SECTION_ID,
        InventoryComponent.RUNE_BAG_SECTION_ID,
    };

    private InventorySections() {
    }
}
