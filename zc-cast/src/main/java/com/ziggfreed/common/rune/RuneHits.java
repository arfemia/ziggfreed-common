package com.ziggfreed.common.rune;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.projectile.component.ImpactModifiers;
import com.ziggfreed.common.util.SafeLog;

/**
 * A rune line's fold ({@link RuneLineFold}) on the cast it shapes, through the engine's own doors, for a consumer that
 * builds its hits and fires its chains itself.
 *
 * <p><b>On a hit</b> ({@link #apply}): the engine's {@code DamageEntity} step converts the elemental damage of a cast
 * whose line holds a conversion ({@code ConvertElement}) and puts the cast's modifiers on each hit it deals, which the
 * game's own on-hit rune system reads to land each support's {@code OnHitEffects} (a rend, a wither, another mod's).
 * A consumer's own hit takes the same two: a cause under the engine's elemental root ({@link #ELEMENTAL_ROOT}) turns
 * into the conversion's cause (a physical or any other cause is untouched), and the modifiers ride the hit as {@code
 * ImpactModifiers.DAMAGE_MODIFIERS}. Called where the hit is built, before it is dispatched, so every system after it
 * reads the converted cause.
 *
 * <p><b>On a chain</b> ({@link #stamp}): the engine snapshots an ability item's cast onto its chain ({@code
 * ImpactModifiers.CAST_SNAPSHOT}), so the chain's native steps (an attribute-bound selector, effect or damage, the
 * charges a cast grants) and every fork of it read the cast's values. A consumer firing its own cast's chain stamps the
 * same snapshot; {@link #snapshotOf} reads it back.
 *
 * <p>Inert until a consumer calls it: with no modifiers every call changes nothing.
 */
public final class RuneHits {

    /** The engine's elemental damage root: a conversion turns every cause that is or inherits from it. */
    public static final String ELEMENTAL_ROOT = "Elemental";

    private RuneHits() {
    }

    /**
     * Put {@code mods} on {@code damage} as the engine does on an ability's hit: a cause under {@link #ELEMENTAL_ROOT}
     * converted to the conversion's cause when {@code mods} carries one, and {@code mods} attached as the hit's {@code
     * DAMAGE_MODIFIERS}. Answers {@code damage} itself. Null {@code mods} changes nothing; a conversion naming a cause
     * the server does not have converts nothing, as the engine's own does not. Never throws.
     */
    @Nonnull
    public static Damage apply(@Nonnull Damage damage, @Nullable ImpactModifiers mods) {
        if (mods == null) {
            return damage;
        }
        try {
            convert(damage, mods.getElementalCauseId());
            damage.putMetaObject(ImpactModifiers.DAMAGE_MODIFIERS, mods);
        } catch (Throwable t) {
            SafeLog.fine("[runes] a hit could not take its cast's runes: " + t.getMessage());
        }
        return damage;
    }

    /** The engine's {@code DamageEntityInteraction.convertElementalCauses} on one hit's cause. */
    private static void convert(@Nonnull Damage damage, @Nullable String causeId) {
        if (causeId == null) {
            return;
        }
        IndexedLookupTableAssetMap<String, DamageCause> causes = DamageCause.getAssetMap();
        DamageCause target = causes.getAsset(causeId);
        DamageCause root = causes.getAsset(ELEMENTAL_ROOT);
        if (target == null || root == null) {
            return;
        }
        DamageCause cause = causes.getAsset(damage.getDamageCauseIndex());
        if (cause == null || !cause.isOrInherits(root)) {
            return;
        }
        int index = causes.getIndex(target.getId());
        if (index != AssetMapWithIndexes.NOT_FOUND) {
            damage.setDamageCauseIndex(index);
        }
    }

    /**
     * Stamp {@code mods} on {@code ctx} as the cast's snapshot ({@code ImpactModifiers.CAST_SNAPSHOT}), the engine's own
     * door for an ability cast's chain, which every fork of it copies. Null {@code mods} or {@code ctx} stamps nothing.
     * Never throws. On the world thread, before the chain runs.
     */
    public static void stamp(@Nullable InteractionContext ctx, @Nullable ImpactModifiers mods) {
        if (ctx == null || mods == null) {
            return;
        }
        try {
            ctx.getMetaStore().putMetaObject(ImpactModifiers.CAST_SNAPSHOT, mods);
        } catch (Throwable t) {
            SafeLog.warn("[runes] a cast's runes could not be stamped on its chain", t);
        }
    }

    /** The cast snapshot {@code ctx} carries (stamped by {@link #stamp} or by the engine), or null. Never throws. */
    @Nullable
    public static ImpactModifiers snapshotOf(@Nullable InteractionContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.getMetaStore().getIfPresentMetaObject(ImpactModifiers.CAST_SNAPSHOT);
        } catch (Throwable t) {
            SafeLog.fine("[runes] a chain's cast snapshot could not be read: " + t.getMessage());
            return null;
        }
    }
}
