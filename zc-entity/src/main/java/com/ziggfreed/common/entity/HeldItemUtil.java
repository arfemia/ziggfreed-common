package com.ziggfreed.common.entity;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTool;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemToolSpec;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The guarded reads of "what is this entity holding, and what is that item worth" - the ACTIVE
 * hotbar stack plus the native attributes hanging off its item asset (tool gather powers, quality
 * tier, item level, durability, raw tags).
 *
 * <p>Every method is try-guarded and answers {@code null} (or an empty map) rather than throwing,
 * because every caller is a presentation/gating read where a failed lookup must degrade to "cannot
 * tell", never to a broken frame. <b>{@code null} means "cannot answer", never "zero"</b> - a
 * caller deciding a gate needs those apart, which is why nothing here substitutes a default for an
 * absent item.
 *
 * <p><b>World thread only</b>: the held-stack reads touch a live {@link Store}. The asset-side
 * reads ({@link #rawTagsOf}, {@link #toolPowersOf}, {@link #qualityValueOf}, {@link #itemLevelOf})
 * are pure asset-map lookups and need no ref at all.
 *
 * <p><b>Only GATHER POWER is keyed per gather type.</b> The native {@code ItemToolSpec} is one entry
 * PER {@code GatherType} on a tool, so a tool has as many powers as it has specs and a caller must
 * say which one it means ({@link #toolPowerFor}). Quality and item level are authored ONCE on the
 * item itself ({@code Quality} names an {@code ItemQuality} asset, {@code ItemLevel} is a plain
 * integer), and durability lives on the STACK, so those three are single values for the whole item
 * and there is nothing to address within them.
 */
public final class HeldItemUtil {

    private HeldItemUtil() {
    }

    /**
     * The entity's ACTIVE hotbar stack, or null when it has no hotbar, holds nothing, or the ref
     * is invalid. Deliberately the active-hotbar read rather than
     * {@code InventoryComponent#getItemInHand}, which also folds in the Tool component - a
     * different question than "what is in the player's hand right now".
     */
    @Nullable
    public static ItemStack heldStack(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (store == null || ref == null || !ref.isValid()) {
            return null;
        }
        try {
            InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            ItemStack active = hotbar == null ? null : hotbar.getActiveItem();
            return active == null || active.isEmpty() ? null : active;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The item id of the ACTIVE hotbar stack, or null when nothing usable is held. */
    @Nullable
    public static String heldItemId(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        ItemStack held = heldStack(store, ref);
        String id = held == null ? null : held.getItemId();
        return id == null || id.isBlank() ? null : id;
    }

    /** The item ASSET behind the ACTIVE hotbar stack, or null when nothing usable is held. */
    @Nullable
    public static Item heldItem(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        ItemStack held = heldStack(store, ref);
        try {
            return held == null ? null : held.getItem();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The item asset registered under {@code itemId}, or null when the id is blank/unknown. */
    @Nullable
    public static Item itemAsset(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        try {
            return Item.getAssetMap().getAsset(itemId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * An item's own native RAW tags ({@code {"<family>": ["<value>", ...]}}), for matching through
     * {@code codec.TagMatch}. Empty when the id is blank/unknown or the asset carries no tag data -
     * an empty map never matches, which keeps a tag gate closed by construction.
     */
    @Nonnull
    public static Map<String, String[]> rawTagsOf(@Nullable String itemId) {
        Item item = itemAsset(itemId);
        if (item == null) {
            return Map.of();
        }
        try {
            AssetExtraInfo.Data data = item.getData();
            Map<String, String[]> raw = data == null ? null : data.getRawTags();
            return raw != null ? raw : Map.of();
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    /**
     * Every native {@code GatherType} {@code item} carries a tool spec for, mapped to that spec's
     * power. Keys are LOWERCASED so a lookup matches whatever casing an author wrote; the highest
     * power wins when one gather type appears twice. Empty for a non-tool.
     *
     * <p>A real tool carries MANY specs at once - a hatchet is authored with its high {@code Woods}
     * power beside a token power for soils, rocks and every ore - so "the tool's power" is only ever
     * a question about ONE gather type or about the best of them. {@link #toolPowerFor} is the pick.
     */
    @Nonnull
    public static Map<String, Double> toolPowersOf(@Nullable Item item) {
        if (item == null) {
            return Map.of();
        }
        try {
            ItemTool tool = item.getTool();
            return toolPowersOf(tool == null ? null : tool.getSpecs());
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    /**
     * The same fold over a spec array the caller already holds (a session snapshotting the tool it
     * engaged with, a test fixture), so the map's shape is decided in exactly one place.
     */
    @Nonnull
    public static Map<String, Double> toolPowersOf(@Nullable ItemToolSpec[] specs) {
        return foldSpecs(specs, ItemToolSpec::getPower);
    }

    /**
     * Every native {@code GatherType} {@code item} carries a tool spec for, mapped to that spec's
     * HARVEST-TIER GATE value ({@code ItemToolSpec.Quality} - the integer {@code BlockHarvestUtils}
     * compares against a block's required {@code Gathering.Breaking.Quality} before the spec's
     * {@code Power} counts at all). <b>A different number from {@link #qualityValueOf}</b>, which
     * reads the ITEM's own rarity tier for the whole item, not a per-job gate the tool must clear.
     * Keys are LOWERCASED so a lookup matches whatever casing an author wrote; the highest tier wins
     * when one gather type appears twice. Empty for a non-tool.
     *
     * <p>Same spread as {@link #toolPowersOf}: a real tool is authored with a tier per gather type
     * (a pickaxe reaching high tiers on rock/ore families while gating out entirely on others), so
     * "the tool's tier" is only ever a question about ONE gather type or the best of them -
     * {@link #toolTierFor} is the pick.
     */
    @Nonnull
    public static Map<String, Double> toolTiersOf(@Nullable Item item) {
        if (item == null) {
            return Map.of();
        }
        try {
            ItemTool tool = item.getTool();
            return toolTiersOf(tool == null ? null : tool.getSpecs());
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    /**
     * The same fold over a spec array the caller already holds, mirroring
     * {@link #toolPowersOf(ItemToolSpec[])}.
     */
    @Nonnull
    public static Map<String, Double> toolTiersOf(@Nullable ItemToolSpec[] specs) {
        return foldSpecs(specs, spec -> (double) spec.getQuality());
    }

    /**
     * The shared per-{@code GatherType} fold underneath {@link #toolPowersOf(ItemToolSpec[])} and
     * {@link #toolTiersOf(ItemToolSpec[])}: keys LOWERCASED, the higher {@code reader} value wins on
     * a duplicated gather type, a blank/null gather type skipped. {@code reader} is the one thing
     * that differs between the two axes (power vs. the harvest-tier gate).
     */
    @Nonnull
    private static Map<String, Double> foldSpecs(@Nullable ItemToolSpec[] specs,
            @Nonnull ToDoubleFunction<ItemToolSpec> reader) {
        if (specs == null || specs.length == 0) {
            return Map.of();
        }
        try {
            Map<String, Double> out = new LinkedHashMap<>(specs.length);
            for (ItemToolSpec spec : specs) {
                String gatherType = spec == null ? null : spec.getGatherType();
                if (gatherType == null || gatherType.isBlank()) {
                    continue;
                }
                String key = gatherType.trim().toLowerCase(Locale.ROOT);
                double value = reader.applyAsDouble(spec);
                Double existing = out.get(key);
                if (existing == null || value > existing) {
                    out.put(key, value);
                }
            }
            return out;
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    /**
     * Pick ONE reading out of a {@link #toolPowersOf} map. Naming a {@code gatherType} asks for THAT
     * type's power (matched case-insensitively, since the map's own keys are lowercased); passing
     * null or blank asks the AGGREGATE question instead - the BEST power the tool has for any type,
     * which is the portable "how good a tool is this at all" read.
     *
     * <p>Null when the map is empty (nothing held, or the held item is no tool) and, crucially, also
     * when a NAMED gather type is absent from it: a tool that cannot do that job at all is a
     * different answer from one that does it badly, so the caller can keep a gate shut on it rather
     * than reading a fabricated {@code 0} as a real, very low power.
     */
    @Nullable
    public static Double toolPowerFor(@Nullable Map<String, Double> powers, @Nullable String gatherType) {
        return selectFrom(powers, gatherType);
    }

    /**
     * Pick ONE reading out of a {@link #toolTiersOf} map, same selection contract as
     * {@link #toolPowerFor}: a named {@code gatherType} (case-insensitive) picks that one type's
     * harvest-tier gate; null/blank asks for the AGGREGATE - the best tier the tool reaches for any
     * type. Null when the map is empty, or a NAMED type has no spec on it - a tool that cannot do
     * that job at all must not read as tier {@code 0}, which would look like a real (if unqualified)
     * gate value to a bounds-checking caller.
     */
    @Nullable
    public static Double toolTierFor(@Nullable Map<String, Double> tiers, @Nullable String gatherType) {
        return selectFrom(tiers, gatherType);
    }

    /**
     * The shared selection contract behind {@link #toolPowerFor} and {@link #toolTierFor}: a named
     * key picked case-insensitively, or - with null/blank - the AGGREGATE best value across every
     * entry. Null when the map is empty/null, or a named key is absent from it.
     */
    @Nullable
    private static Double selectFrom(@Nullable Map<String, Double> values, @Nullable String gatherType) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String wanted = gatherType == null ? null : gatherType.trim();
        if (wanted == null || wanted.isEmpty()) {
            double best = Double.NEGATIVE_INFINITY;
            for (Double value : values.values()) {
                if (value != null && value > best) {
                    best = value;
                }
            }
            return best == Double.NEGATIVE_INFINITY ? null : best;
        }
        return values.get(wanted.toLowerCase(Locale.ROOT));
    }

    /**
     * An item's RARITY as the native {@code ItemQuality.QualityValue} its referenced quality asset
     * authors - the number that ORDERS quality tiers (0 = lowest), so a pack shipping its own tier
     * participates with no code change. Null when {@code item} is null.
     *
     * <p>Two indirections, both deliberate: {@code Item#getQualityIndex()} returns an ASSET-MAP
     * INDEX rather than the ordering value, so it is resolved back through the quality asset map to
     * read the authored value. The engine's own default quality authors {@code -1}, floored to 0
     * here so an unqualified item can never drag a weighted formula below an authored lowest tier.
     */
    @Nullable
    public static Double qualityValueOf(@Nullable Item item) {
        if (item == null) {
            return null;
        }
        try {
            ItemQuality quality = ItemQuality.getAssetMap().getAsset(item.getQualityIndex());
            return quality == null ? 0.0 : Math.max(0.0, quality.getQualityValue());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** An item's native {@code ItemLevel}, floored at 0. Null when {@code item} is null. */
    @Nullable
    public static Double itemLevelOf(@Nullable Item item) {
        if (item == null) {
            return null;
        }
        try {
            return Math.max(0.0, item.getItemLevel());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * A stack's remaining durability as a percent in {@code [0, 100]}. A stack that tracks no
     * durability at all reads {@code 100} (an item that cannot wear is never worn), so a wear gate
     * never rejects one; null only when there is no stack to ask about.
     */
    @Nullable
    public static Double durabilityPercentOf(@Nullable ItemStack stack) {
        if (stack == null) {
            return null;
        }
        try {
            if (stack.isEmpty()) {
                return null;
            }
            if (stack.getMaxDurability() <= 0) {
                return 100.0;
            }
            double percent = (stack.getDurability() / stack.getMaxDurability()) * 100.0;
            return Math.max(0.0, Math.min(100.0, percent));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
