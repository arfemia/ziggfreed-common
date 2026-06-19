package com.ziggfreed.common.inventory;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * Declarative policy for what {@link InventorySnapshot#strip} removes from an entity's inventory
 * on round entry. A snapshot is ALWAYS captured in full and ALWAYS restored to the exact entry
 * state on exit; this policy governs ONLY what the player carries INTO the round, never the restore.
 *
 * <p>Two independent dials, so a consumer can future-proof a "keep your armor" or "forbid these
 * items" variant without touching the snapshot/restore plumbing:
 * <ul>
 *   <li><b>Sections</b> - which inventory sections are subject to stripping at all (Armor / Hotbar /
 *       Storage / Utility / Tool / Backpack). A section NOT covered is left fully intact on entry.
 *       Default: all six.</li>
 *   <li><b>Item rule</b> - within a covered section, an optional id filter: {@link ItemRule#ALL}
 *       strips everything, {@link ItemRule#WHITELIST} strips ONLY the listed ids, {@link
 *       ItemRule#BLACKLIST} strips everything EXCEPT the listed ids (a keep list). Default
 *       {@link ItemRule#ALL}.</li>
 * </ul>
 *
 * Immutable; build via {@link #builder()} or use {@link #STRIP_ALL}.
 */
public final class InventoryStripPolicy {

    /** How a covered section's item ids are filtered. */
    public enum ItemRule {
        /** Strip every item in the section. */
        ALL,
        /** Strip ONLY items whose id is in the set; keep the rest. */
        WHITELIST,
        /** Strip every item EXCEPT those whose id is in the set (a keep list). */
        BLACKLIST
    }

    /** Strip the entire inventory (all six sections, every item). The minigame default. */
    public static final InventoryStripPolicy STRIP_ALL = builder().build();

    private final Set<Integer> sections;
    private final ItemRule itemRule;
    private final Set<String> itemIds;

    private InventoryStripPolicy(@Nonnull Set<Integer> sections, @Nonnull ItemRule itemRule,
                                 @Nonnull Set<String> itemIds) {
        this.sections = sections;
        this.itemRule = itemRule;
        this.itemIds = itemIds;
    }

    /** Whether {@code sectionId} is subject to stripping at all (an uncovered section is kept whole). */
    public boolean coversSection(int sectionId) {
        return sections.contains(sectionId);
    }

    /**
     * Whether the WHOLE section can be cleared in one transaction (it is covered AND has no item
     * filter). The fast path for the common strip-everything case.
     */
    public boolean clearsSection(int sectionId) {
        return itemRule == ItemRule.ALL && sections.contains(sectionId);
    }

    /** Whether an item with id {@code itemId} in a (covered) section should be removed on entry. */
    public boolean shouldStrip(@Nonnull String itemId) {
        return switch (itemRule) {
            case ALL -> true;
            case WHITELIST -> itemIds.contains(itemId);
            case BLACKLIST -> !itemIds.contains(itemId);
        };
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder; defaults to all six sections + {@link ItemRule#ALL} (= {@link #STRIP_ALL}). */
    public static final class Builder {
        private final Set<Integer> sections = new HashSet<>();
        private ItemRule itemRule = ItemRule.ALL;
        private final Set<String> itemIds = new HashSet<>();

        private Builder() {
            for (int id : InventorySections.ALL) {
                sections.add(id);
            }
        }

        /** Restrict stripping to EXACTLY these section ids (replaces the default all-six set). */
        @Nonnull
        public Builder sections(@Nonnull int... sectionIds) {
            sections.clear();
            for (int id : sectionIds) {
                sections.add(id);
            }
            return this;
        }

        /** Keep these sections fully intact on entry (remove them from the strip set). */
        @Nonnull
        public Builder keepSections(@Nonnull int... sectionIds) {
            for (int id : sectionIds) {
                sections.remove(id);
            }
            return this;
        }

        /** Strip ONLY these item ids (within covered sections); keep everything else. */
        @Nonnull
        public Builder whitelist(@Nonnull Collection<String> ids) {
            this.itemRule = ItemRule.WHITELIST;
            this.itemIds.clear();
            this.itemIds.addAll(ids);
            return this;
        }

        /** Strip everything EXCEPT these item ids (a keep list), within covered sections. */
        @Nonnull
        public Builder blacklist(@Nonnull Collection<String> ids) {
            this.itemRule = ItemRule.BLACKLIST;
            this.itemIds.clear();
            this.itemIds.addAll(ids);
            return this;
        }

        @Nonnull
        public InventoryStripPolicy build() {
            return new InventoryStripPolicy(new HashSet<>(sections), itemRule, new HashSet<>(itemIds));
        }
    }
}
