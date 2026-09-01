package com.ziggfreed.common.world.stash;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.codec.DeferredCodec;

/**
 * One pile inside a {@link BlockStash}: who put it there and what it holds. Pure storage - the
 * consumer decides who may add, take or see; the pile only records.
 *
 * <ul>
 *   <li>{@code Owner} - the uuid string of the player the pile belongs to.</li>
 *   <li>{@code Items} - counted contents by item id, in INSERTION ORDER: what went in first comes
 *       first, so a consumer draining oldest-first reads the map in order.</li>
 *   <li>{@code Unique} - one whole {@code ItemStack} kept AS THE STACK, through the engine's own
 *       item codec, so per-stack metadata (durability, quality, stamped stats, display overrides)
 *       rides along byte-identically. For the one item whose instance matters; counted goods
 *       belong in {@code Items}.</li>
 *   <li>{@code PendingCycles} - counts accrued against string keys the consumer defines, for work
 *       that settled while nobody was present and pays out later.</li>
 * </ul>
 *
 * <p>Every leaf is nullable; an unauthored leaf reads as null and costs nothing on disk. A pile
 * with NO leaf set at all is dropped by a save outright (an entry that says nothing is not
 * written), so a pile that must survive records at least its owner. Whoever mutates a pile flags
 * the owning section for a save (see the stash facade).
 */
public final class StashPile {

    @Nullable protected String owner;
    @Nullable protected Map<String, Integer> items;
    @Nullable protected ItemStack unique;
    @Nullable protected Map<String, Integer> pendingCycles;

    public static final BuilderCodec<StashPile> CODEC = BuilderCodec.builder(StashPile.class, StashPile::new)
            .append(new KeyedCodec<>("Owner", Codec.STRING, false),
                    (p, v) -> p.owner = v, p -> p.owner)
            .documentation("Uuid string of the player this pile belongs to.")
            .add()
            .append(new KeyedCodec<>("Items", new MapCodec<>(Codec.INTEGER, LinkedHashMap::new, false), false),
                    (p, v) -> p.items = v, p -> p.items)
            .documentation("Counted contents by item id, kept in insertion order (oldest first).")
            .add()
            // The engine's own item codec, resolved only when a value actually flows: referencing
            // it at class initialization would weld this type's loadability to the engine's whole
            // item static graph. Delegation is verbatim, so stack metadata round-trips
            // byte-identically.
            .append(new KeyedCodec<>("Unique", new DeferredCodec<>(() -> ItemStack.CODEC), false),
                    (p, v) -> p.unique = v, p -> p.unique)
            .documentation("One whole item stack kept as-is, metadata and all; counted goods belong in Items.")
            .add()
            .append(new KeyedCodec<>("PendingCycles", new MapCodec<>(Codec.INTEGER, LinkedHashMap::new, false), false),
                    (p, v) -> p.pendingCycles = v, p -> p.pendingCycles)
            .documentation("Counts accrued against consumer-defined keys, to be paid out later.")
            .add()
            .build();

    public StashPile() {
    }

    /** The uuid string of the player this pile belongs to, or null when unrecorded. */
    @Nullable
    public String getOwner() {
        return owner;
    }

    public void setOwner(@Nullable String owner) {
        this.owner = owner;
    }

    /** Counted contents by item id in insertion order, or null when the pile holds none. */
    @Nullable
    public Map<String, Integer> getItems() {
        return items;
    }

    /** {@link #getItems()}, creating the (insertion-ordered) map on first use. */
    @Nonnull
    public Map<String, Integer> itemsMutable() {
        Map<String, Integer> map = items;
        if (map == null) {
            map = new LinkedHashMap<>();
            items = map;
        }
        return map;
    }

    /** The one kept-as-is item stack, or null. */
    @Nullable
    public ItemStack getUnique() {
        return unique;
    }

    public void setUnique(@Nullable ItemStack unique) {
        this.unique = unique;
    }

    /** Accrued cycle counts by consumer-defined key, or null when none are owed. */
    @Nullable
    public Map<String, Integer> getPendingCycles() {
        return pendingCycles;
    }

    /** {@link #getPendingCycles()}, creating the map on first use. */
    @Nonnull
    public Map<String, Integer> pendingCyclesMutable() {
        Map<String, Integer> map = pendingCycles;
        if (map == null) {
            map = new LinkedHashMap<>();
            pendingCycles = map;
        }
        return map;
    }
}
