package com.ziggfreed.common.stats.damage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.EmptyItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The engine's own damage resistance, read the way its armor step builds it: worn armor's
 * {@code DamageResistance} (a broken piece's penalty included) and every active effect's, summed per cause
 * key, a hit taking its own cause's level and then each parent an armor listing links (R567). This
 * library's {@code Zig_DamageResist} effects, a potion and vanilla armor all land in the one sum, so a page
 * that shows "what a hit loses" reads it here rather than adding the stats up itself.
 *
 * <p><b>The key is the hit's exact cause.</b> The engine looks a hit's cause up exactly: a Crush hit meets
 * a {@code Physical} resistance only when something also keys {@code Crush} (R674). {@link #percentFor}
 * answers that same lookup, so it reads 0 for a cause nothing keys.
 *
 * <p><b>Armor penetration</b> rides the hit as {@link #setPenetration}: the fraction of the resistance on
 * the hit's own cause a hit ignores, which zc-effects' after-armor step applies once the engine's armor
 * step has run. Any dealer may set it on the hit it dispatches.
 */
public final class NativeResistance {

    private NativeResistance() {
    }

    /** One cause key's summed resistance: {@code percent} as a fraction (0.25 resists a quarter), {@code flat} in points. */
    public record Level(@Nonnull DamageCause key, double percent, double flat, @Nullable DamageCause parent) {
    }

    /**
     * What the engine takes off a hit of one cause: its own cause's level first, then each linked parent, in
     * the order the engine applies them. Empty when the cause bypasses resistance or nothing keys it.
     */
    public record Sum(@Nonnull List<Level> levels) {

        public static final Sum NONE = new Sum(List.of());

        public boolean isEmpty() {
            return levels.isEmpty();
        }

        /** The summed percent on the hit's own cause, a fraction; 0 when nothing keys it. */
        public double exactPercent() {
            return levels.isEmpty() ? 0.0 : levels.get(0).percent();
        }

        /** The share of a hit the percent terms take off across every level, a fraction in {@code [0, 1]}. */
        public double percent() {
            double kept = 1.0;
            for (Level level : levels) {
                kept *= Math.max(0.0, 1.0 - level.percent());
            }
            return Math.max(0.0, Math.min(1.0, 1.0 - kept));
        }
    }

    /** The hit's armor penetration, a fraction, put there by its dealer. */
    private static final class Meta {
        static final MetaKey<Double> PENETRATION = Damage.META_REGISTRY.registerMetaObject();
    }

    /**
     * Say that {@code damage} ignores {@code fraction} of the resistance on its own cause (0.25 ignores a
     * quarter of it), held to {@code [0, 1]}; 0 or less clears nothing and sets nothing. Set before the hit
     * reaches the Filter group's after-armor step.
     */
    public static void setPenetration(@Nonnull Damage damage, double fraction) {
        if (!(fraction > 0.0) || !Double.isFinite(fraction)) {
            return;
        }
        damage.putMetaObject(Meta.PENETRATION, Math.min(1.0, fraction));
    }

    /** The armor penetration {@code damage} carries, a fraction in {@code [0, 1]}; 0 when none was set. */
    public static double penetrationOf(@Nonnull Damage damage) {
        Double held = damage.getIfPresentMetaObject(Meta.PENETRATION);
        return held == null || !(held > 0.0) ? 0.0 : Math.min(1.0, held);
    }

    /** The engine's resistance against a hit of {@code cause} on {@code holder} now. Never throws. */
    @Nonnull
    public static Sum of(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> holder,
            @Nullable DamageCause cause) {
        if (cause == null || cause.doesBypassResistances()) {
            return Sum.NONE;
        }
        Map<String, Level> table = table(accessor, holder);
        Level level = table.get(key(cause));
        if (level == null) {
            return Sum.NONE;
        }
        List<Level> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (level != null && seen.add(key(level.key()))) {
            out.add(level);
            level = level.parent() == null ? null : table.get(key(level.parent()));
        }
        return new Sum(List.copyOf(out));
    }

    /** {@link #of(ComponentAccessor, Ref, DamageCause)}'s {@link Sum#percent}: the share of such a hit the engine takes off. */
    public static double percentFor(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> holder,
            @Nullable DamageCause cause) {
        return of(accessor, holder, cause).percent();
    }

    /** {@link #percentFor(ComponentAccessor, Ref, DamageCause)} for a cause id; 0 for one not loaded. */
    public static double percentFor(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> holder,
            @Nullable String causeId) {
        return causeId == null ? 0.0 : percentFor(accessor, holder, loadedCause(causeId));
    }

    /**
     * Every cause key the engine's table holds for {@code holder} now, lower-cased, with its summed percent (a
     * fraction): worn armor and every active effect, no parent walk. The per-key reading a ceiling's headroom
     * is taken from. Empty on any failure.
     */
    @Nonnull
    public static Map<String, Double> percentsByKey(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> holder) {
        Map<String, Level> table = table(accessor, holder);
        if (table.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> out = new HashMap<>(table.size() * 2);
        for (Map.Entry<String, Level> e : table.entrySet()) {
            out.put(e.getKey(), e.getValue().percent());
        }
        return out;
    }

    /** The engine's own merge of worn armor and active effects, by lower-cased cause id; empty on any failure. */
    @Nonnull
    private static Map<String, Level> table(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> holder) {
        try {
            if (holder == null || !holder.isValid()) {
                return Map.of();
            }
            World world = accessor.getExternalData().getWorld();
            InventoryComponent.Armor armor = accessor.getComponent(holder, InventoryComponent.Armor.getComponentType());
            ItemContainer worn = armor != null && armor.getInventory() != null ? armor.getInventory() : EmptyItemContainer.INSTANCE;
            EffectControllerComponent ctrl = accessor.getComponent(holder, EffectControllerComponent.getComponentType());
            boolean penalties = ItemUtils.canApplyItemStackPenalties(holder, accessor);
            // DEPRECATION-KEPT: the engine's armor step is the only resistance applier in both lines (its note names no successor); reading its own merge keeps every reader on the number a hit meets.
            var merged = DamageSystems.ArmorDamageReduction.getResistanceModifiers(world, worn, penalties, ctrl);
            if (merged == null || merged.isEmpty()) {
                return Map.of();
            }
            Map<String, Level> out = new HashMap<>(merged.size() * 2);
            // DEPRECATION-KEPT: each entry is the engine's own per-cause sum (the armor step's type, no successor); copied out into Level here so no reader names it.
            for (var e : merged.entrySet()) {
                DamageCause key = e.getKey();
                var mods = e.getValue();
                if (key == null || mods == null) {
                    continue;
                }
                out.put(key(key), new Level(key, mods.multiplierModifier, mods.flatModifier, mods.inheritedParentId));
            }
            return out;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    @Nonnull
    private static String key(@Nonnull DamageCause cause) {
        return cause.getId() == null ? "" : cause.getId().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static DamageCause loadedCause(@Nonnull String causeId) {
        try {
            return DamageCause.getAssetMap().getAsset(causeId);
        } catch (Throwable t) {
            return null;
        }
    }
}
