package com.ziggfreed.common.entity;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemArmor;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemUtility;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemWeapon;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * Real engine item values built without a running server. A unit JVM has no item asset store, so an
 * {@link Item} is filled through its protected fields (the same fields the engine's codec fills)
 * with a level, a quality index and stat blocks. A stack goes through the engine's own public
 * {@code ItemStack} constructors, exactly as on a live server: on Update 7 they give a stack no quality
 * of its own unless one is passed (the 6-argument constructor, the one {@code ItemStack#withQuality}
 * uses), so an unstamped stack reads its item's quality; only {@code getItem()} is answered locally
 * instead of by an item-map lookup.
 *
 * <p><b>A test using this class is tagged {@code engine-items}.</b> The engine's item classes can
 * only be initialized under its own log manager, which only the {@code engineItemTest} task starts
 * its JVM with ({@code gradle/zc-module.gradle}); the default {@code test} task runs without one, as
 * a consumer mod's tests do, so an untagged test that builds an item here fails there on the
 * engine's class-init error.
 */
public final class TestItems {

    private TestItems() {
    }

    /** An item with an item level and a quality index and no stat blocks. */
    @Nonnull
    public static Item item(@Nonnull String id, int level, int qualityAt) {
        return item(id, level, qualityAt, null, null, null);
    }

    /** An item with an item level, a quality index and the given (nullable) stat blocks. */
    @Nonnull
    public static Item item(@Nonnull String id, int level, int qualityAt,
            @Nullable Int2ObjectMap<StaticModifier[]> armorStats,
            @Nullable Int2ObjectMap<StaticModifier[]> weaponStats,
            @Nullable Int2ObjectMap<StaticModifier[]> utilityStats) {
        return new Item(id) {
            {
                this.itemLevel = level;
                this.qualityIndex = qualityAt;
                this.armor = armorStats == null ? null : new ItemArmor() {
                    {
                        this.statModifiers = armorStats;
                    }
                };
                this.weapon = weaponStats == null ? null : new ItemWeapon() {
                    {
                        this.statModifiers = weaponStats;
                    }
                };
                this.utility = new ItemUtility() {
                    {
                        this.statModifiers = utilityStats;
                    }
                };
            }
        };
    }

    /**
     * A one-item stack of {@code asset} at the given wear, made the way the engine makes one: nothing
     * is stamped on it, so it reads its item's quality.
     */
    @Nonnull
    public static ItemStack stack(@Nonnull Item asset, double wear, double wearMax) {
        return madeFrom(asset, asset, wear, wearMax);
    }

    /**
     * A stack made while its item was {@code made}, whose item now answers as {@code current}: the
     * shape a live stack takes when its item's {@code Quality} is reloaded, or the quality index
     * order moves between boots, after the stack was made. Nothing was stamped on it, so on Update 7
     * it reads {@code current}'s quality; {@code getItem()} reports {@code current}.
     */
    @Nonnull
    public static ItemStack madeFrom(@Nonnull Item made, @Nonnull Item current, double wear, double wearMax) {
        AtomicReference<Item> answering = new AtomicReference<>(made);
        ItemStack stack = new ItemStack(made.getId(), 1, wear, wearMax, null) {
            @Override
            public Item getItem() {
                return answering.get();
            }
        };
        answering.set(current);
        return stack;
    }

    /**
     * A one-item stack of {@code asset} re-qualified to {@code stackQuality}, through the engine's
     * constructor that takes an explicit quality index (the one {@code ItemStack#withQuality} uses).
     * The stamp is the stack's own: it reads {@code stackQuality} whatever its item authors, and
     * Update 7 saves it with the stack under {@code QualityOverride}. Passing
     * {@code AssetMapWithIndexes.NOT_FOUND} stamps nothing, so that stack reads its item's quality.
     */
    @Nonnull
    public static ItemStack requalified(@Nonnull Item asset, int stackQuality) {
        return new ItemStack(asset.getId(), 1, 1, 1, stackQuality, null) {
            @Override
            public Item getItem() {
                return asset;
            }
        };
    }

    /** A quality asset with the given ordering value. */
    @Nonnull
    public static ItemQuality quality(int ordering) {
        return new ItemQuality("Test_Quality_" + ordering) {
            {
                this.qualityValue = ordering;
            }
        };
    }

    /** One stat-block entry: {@code modifiers} at {@code statIndex}. */
    @Nonnull
    public static Int2ObjectMap<StaticModifier[]> stats(int statIndex, @Nonnull StaticModifier... modifiers) {
        Int2ObjectMap<StaticModifier[]> map = new Int2ObjectOpenHashMap<>();
        map.put(statIndex, modifiers);
        return map;
    }

    /** An additive modifier on the channel's maximum. */
    @Nonnull
    public static StaticModifier additive(float amount) {
        return new StaticModifier(Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.ADDITIVE, amount);
    }

    /** A multiplicative modifier on the channel's maximum. */
    @Nonnull
    public static StaticModifier multiplicative(float amount) {
        return new StaticModifier(Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.MULTIPLICATIVE, amount);
    }
}
