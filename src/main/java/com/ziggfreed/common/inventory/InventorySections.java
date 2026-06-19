package com.ziggfreed.common.inventory;

import com.hypixel.hytale.server.core.inventory.InventoryComponent;

/**
 * The canonical list of the six managed inventory sections (Armor / Hotbar / Storage / Utility /
 * Tool / Backpack), in one place so {@link InventorySnapshot} (capture/restore order) and
 * {@link InventoryStripPolicy} (the default strip set) cannot drift if the engine ever adds or
 * renames a section. Package-private: the inventory primitives own this vocabulary.
 */
final class InventorySections {

    /** All six managed sections, in a fixed iteration order. */
    static final int[] ALL = {
        InventoryComponent.ARMOR_SECTION_ID,
        InventoryComponent.HOTBAR_SECTION_ID,
        InventoryComponent.STORAGE_SECTION_ID,
        InventoryComponent.UTILITY_SECTION_ID,
        InventoryComponent.TOOLS_SECTION_ID,
        InventoryComponent.BACKPACK_SECTION_ID,
    };

    private InventorySections() {
    }
}
