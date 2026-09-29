package com.ziggfreed.common.entity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonValue;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemArmor;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemUtility;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemWeapon;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.ziggfreed.common.inventory.DisposableItemMetadata;
import com.ziggfreed.common.stats.StatIndexCache;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

/**
 * The ONE reader of "what is this item worth": quality, item level, wear and the stat modifiers the
 * item asset itself authors, asked of a STACK (or, where no stack exists, of the item asset alone).
 *
 * <p>Every item-shaped factor reads through here, whichever item it is asked about: the portable
 * {@code hytale:tool_*} readings ask it about what is in the subject's hand, and the
 * {@code hytale:item_*} readings ask it about the stack a moment carries in its own item leaf.
 *
 * <p><b>{@code null} means "cannot answer", never zero</b>, exactly as in {@link HeldItemUtil}: no
 * stack, an empty stack, or an engine read that threw. Every engine read is try-guarded, because
 * every caller is a gate or a formula where a failed lookup must degrade to "cannot tell".
 *
 * <p><b>A stack's quality and its item's quality are two readings.</b> The engine copies the
 * item's quality index into every stack it builds, saves that index with the stack under its
 * {@code Quality} key, and rewrites it on {@code ItemStack#withQuality}; only a stack carrying no
 * index at all falls back to its item's. So {@link #quality(ItemStack)} reads the quality the
 * stack was MADE with (or re-qualified to), and {@link #quality(Item)} reads the quality the item
 * asset authors NOW. The two agree until the item's {@code Quality} is reloaded, or the quality
 * index order moves between boots, after the stack was made. Item level and the authored stat
 * modifiers exist only on the item asset, and durability only on the stack.
 *
 * <p>It also reads which METADATA keys a stack carries ({@link #metadataKeys}) and which of them no
 * mod has declared safe to destroy with it ({@link #undeclaredMetadataKeys}), the question anything
 * about to consume a stack asks first.
 */
public final class ItemReadings {

    /** The {@code ItemStack.CODEC} leaf that carries a stack's metadata document. */
    static final String METADATA_LEAF = "Metadata";

    /** A stack whose item tracks no durability reads as fully intact. */
    static final double UNWORN_PERCENT = 100.0;

    private ItemReadings() {
    }

    // ==================== quality ====================

    /**
     * The stack's RARITY as the native {@code ItemQuality.QualityValue} its OWN quality index
     * resolves to, floored at 0 - the number that ORDERS quality tiers, so a pack shipping its own
     * tier takes part with no code change. The index is the one the stack carries (copied from its
     * item when the stack was made, or set by a re-qualify), not its item's current one; see the
     * class javadoc. Null when there is no usable stack.
     */
    @Nullable
    public static Double quality(@Nullable ItemStack stack) {
        return quality(stack, ItemReadings::liveQuality);
    }

    /**
     * The item asset's CURRENT rarity value, the quality its {@code Quality} field names now. Same
     * floor as {@link #quality(ItemStack)}; it reads the same number for a stack only while that
     * stack's copied index still matches its item's (see the class javadoc). Null when
     * {@code item} is null.
     */
    @Nullable
    public static Double quality(@Nullable Item item) {
        return quality(item, ItemReadings::liveQuality);
    }

    /** {@link #quality(ItemStack)} over an injected quality lookup (the pure core). */
    @Nullable
    static Double quality(@Nullable ItemStack stack, @Nonnull IntFunction<ItemQuality> qualities) {
        if (isAbsent(stack)) {
            return null;
        }
        try {
            return qualityValue(qualities.apply(stack.getQualityIndex()));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** {@link #quality(Item)} over an injected quality lookup (the pure core). */
    @Nullable
    static Double quality(@Nullable Item item, @Nonnull IntFunction<ItemQuality> qualities) {
        if (item == null) {
            return null;
        }
        try {
            return qualityValue(qualities.apply(item.getQualityIndex()));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * A resolved quality asset's ordering value. An index that resolves to nothing reads 0, and the
     * engine's own default quality authors {@code -1}, floored to 0 here, so an unqualified item can
     * never drag a weighted formula below an authored lowest tier.
     */
    private static double qualityValue(@Nullable ItemQuality quality) {
        return quality == null ? 0.0 : Math.max(0.0, quality.getQualityValue());
    }

    @Nullable
    private static ItemQuality liveQuality(int index) {
        return ItemQuality.getAssetMap().getAsset(index);
    }

    // ==================== item level ====================

    /** The stack's item's native {@code ItemLevel}, floored at 0. Null when there is no usable stack. */
    @Nullable
    public static Double itemLevel(@Nullable ItemStack stack) {
        if (isAbsent(stack)) {
            return null;
        }
        try {
            return itemLevel(stack.getItem());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** An item asset's native {@code ItemLevel}, floored at 0. Null when {@code item} is null. */
    @Nullable
    public static Double itemLevel(@Nullable Item item) {
        if (item == null) {
            return null;
        }
        try {
            return Math.max(0.0, item.getItemLevel());
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================== durability ====================

    /**
     * The stack's remaining durability as a percent in {@code [0, 100]}. A stack that tracks no
     * durability at all reads {@code 100} (an item that cannot wear is never worn), so a wear gate
     * never rejects one; null only when there is no usable stack to ask about.
     */
    @Nullable
    public static Double durabilityPercent(@Nullable ItemStack stack) {
        if (isAbsent(stack)) {
            return null;
        }
        try {
            if (stack.getMaxDurability() <= 0) {
                return UNWORN_PERCENT;
            }
            double percent = (stack.getDurability() / stack.getMaxDurability()) * 100.0;
            return Math.max(0.0, Math.min(100.0, percent));
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================== authored stat modifiers ====================

    /**
     * What the stack's item asset itself authors toward the stat channel {@code statId}: the sum of
     * the ADDITIVE {@code StatModifiers} amounts across its {@code Armor}, {@code Weapon} and
     * {@code Utility} blocks, each block counted whatever slot the item happens to sit in. It reads
     * the item's own authored numbers, never a live entity, so it answers the same for a piece lying
     * on a bench as for the one being worn.
     *
     * <p><b>A MULTIPLICATIVE modifier contributes nothing.</b> A multiplier has no amount of its own
     * that adds into a sum (it scales whatever the channel already holds), so folding it in as a
     * number would misstate the item; this is the same rule the equip bridge's own summed reading
     * follows. Per-stack stamped stats are not authored on the item and are not counted here either.
     *
     * <p>{@code 0} when the item authors nothing toward that channel, which is a real answer about a
     * real item. Null when there is no usable stack, no {@code statId}, or no channel is registered
     * under that id.
     */
    @Nullable
    public static Double statTotal(@Nullable ItemStack stack, @Nullable String statId) {
        if (isAbsent(stack)) {
            return null;
        }
        try {
            return statTotal(stack.getItem(), statId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** {@link #statTotal(ItemStack, String)} for a caller holding the item asset alone. */
    @Nullable
    public static Double statTotal(@Nullable Item item, @Nullable String statId) {
        String id = statId == null ? null : statId.trim();
        if (item == null || id == null || id.isEmpty()) {
            return null;
        }
        int index = StatIndexCache.resolve(id);
        return index == StatIndexCache.UNRESOLVED ? null : statTotal(item, index);
    }

    /**
     * The pure core of {@link #statTotal(Item, String)} over an already-resolved stat index: the
     * ADDITIVE amounts the item's armor, weapon and utility blocks author at {@code statIndex}.
     */
    static double statTotal(@Nonnull Item item, int statIndex) {
        try {
            ItemArmor armor = item.getArmor();
            ItemWeapon weapon = item.getWeapon();
            ItemUtility utility = item.getUtility();
            return additiveAt(armor == null ? null : armor.getStatModifiers(), statIndex)
                    + additiveAt(weapon == null ? null : weapon.getStatModifiers(), statIndex)
                    + additiveAt(utility == null ? null : utility.getStatModifiers(), statIndex);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /** The summed ADDITIVE amounts one block authors at {@code statIndex}; 0 for a block with none. */
    private static double additiveAt(@Nullable Int2ObjectMap<StaticModifier[]> block, int statIndex) {
        StaticModifier[] modifiers = block == null ? null : block.get(statIndex);
        if (modifiers == null) {
            return 0.0;
        }
        double sum = 0.0;
        for (StaticModifier modifier : modifiers) {
            if (modifier != null && modifier.getCalculationType() == StaticModifier.CalculationType.ADDITIVE) {
                sum += modifier.getAmount();
            }
        }
        return sum;
    }

    // ==================== metadata keys ====================

    /**
     * The METADATA keys {@code stack} carries: the top-level keys of its metadata document, in the
     * document's order, as an immutable set. A bare stack (no metadata at all) reads an empty set.
     *
     * <p><b>Null means "cannot tell", and a caller about to destroy the stack refuses on it.</b> It
     * is answered for no stack, and for a stack the read cannot encode (the read never throws). The
     * engine's own metadata accessor is {@code @Deprecated} (not marked for removal, though the
     * engine's comment says it goes once stack metadata moves to components), so the keys are read
     * the one non-deprecated way the engine offers: the stack's own {@code ItemStack.CODEC} encodes
     * it, and the {@code Metadata} leaf of that document is the stack's metadata (the engine's own
     * document, not a copy, so only its key names are copied out and it never leaves this method).
     * No value is decoded and the stack is not changed.
     */
    @Nullable
    public static Set<String> metadataKeys(@Nullable ItemStack stack) {
        if (stack == null) {
            return null;
        }
        try {
            BsonValue metadata = ItemStack.CODEC.encode(stack, new ExtraInfo()).get(METADATA_LEAF);
            if (metadata == null || metadata.isNull()) {
                return Set.of();
            }
            if (!metadata.isDocument()) {
                return null;
            }
            return Collections.unmodifiableSet(new LinkedHashSet<>(metadata.asDocument().keySet()));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The metadata keys {@code stack} carries that NO mod has declared safe to destroy with it
     * ({@link DisposableItemMetadata}): empty when every key is declared or the stack is bare, and
     * null when {@link #metadataKeys} cannot tell, which a consumer about to destroy the stack treats
     * as a refusal. One read of the stack and one set lookup per key.
     */
    @Nullable
    public static Set<String> undeclaredMetadataKeys(@Nullable ItemStack stack) {
        Set<String> keys = metadataKeys(stack);
        return keys == null ? null : DisposableItemMetadata.undeclared(keys);
    }

    // ==================== helpers ====================

    private static boolean isAbsent(@Nullable ItemStack stack) {
        try {
            return stack == null || stack.isEmpty();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
