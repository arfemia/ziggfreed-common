package com.ziggfreed.common.damage.pvp;

import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Disarmed: an effect whose own file carries the native tag {@value #TAG} stops its wearer attacking for as long as it
 * is worn (R729's flag bearer, who cannot attack while he carries the flag; any mod's disarm). Per-asset data rides the
 * asset (R751), so a mod opts its effect in with one line in its own file:
 *
 * <pre>{@code
 * "Tags": { "ZigDisarm": [ "Primary", "Secondary", "Ability1", "Ability2", "Ability3" ] }
 * }</pre>
 *
 * <p>Two refusals, both read off the wearer's live effects: every hit the wearer is the source of (a blow, a shot, a
 * thrown bomb, a deployable's damage, a damage over time they put on) is cancelled in the Gather group, before any
 * step rolls a crit or fires a moment for it, and moves no one ({@link PvpHitSystem.Disarmed}), and an interaction chain of a type the tag's values name (the engine's own
 * {@code InteractionType} words, any casing) is refused as it starts ({@link DisarmChainSystem}), so no attack swings
 * and no effect a swing would put on lands. The tag with no value refuses the hits alone. A Use, a dodge or any type
 * the tag does not name runs as ever. The effect's own {@code AbilityEffects.Disabled} stays the client's half (the
 * engine sends it to the client and reads it nowhere on the server).
 */
public final class Disarm {

    /** The effect tag that disarms its wearer; its values name the interaction types refused. */
    public static final String TAG = "ZigDisarm";

    private Disarm() {
    }

    /** Does the entity at {@code ref} wear an effect tagged {@value #TAG}? World thread; never throws. */
    public static boolean isDisarmed(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        return wornTag(accessor, ref, null);
    }

    /**
     * Does the entity at {@code ref} wear an effect whose {@value #TAG} tag names {@code type}? False for a null type.
     * World thread; never throws.
     */
    public static boolean refuses(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nullable InteractionType type) {
        return type != null && wornTag(accessor, ref, type.name());
    }

    /**
     * Does an effect's raw tag map carry {@value #TAG} ({@code typeName} null), or carry it naming {@code typeName}
     * (any casing)? Pure.
     */
    static boolean carries(@Nullable Map<String, String[]> rawTags, @Nullable String typeName) {
        if (rawTags == null || rawTags.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String[]> entry : rawTags.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase(TAG)) {
                continue;
            }
            if (typeName == null) {
                return true;
            }
            String wanted = typeName.trim().toLowerCase(Locale.ROOT);
            String[] values = entry.getValue();
            if (values == null) {
                continue;
            }
            for (String value : values) {
                if (value != null && value.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@link #carries} over every effect the entity wears. Never throws: one that cannot be read wears none. */
    private static boolean wornTag(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nullable String typeName) {
        try {
            if (!ref.isValid()) {
                return false;
            }
            EffectControllerComponent ctrl = accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ctrl == null || ctrl.getActiveEffects().isEmpty()) {
                return false;
            }
            for (ActiveEntityEffect active : ctrl.getActiveEffects().values()) {
                EntityEffect effect = EntityEffect.getAssetMap().getAsset(active.getEntityEffectIndex());
                AssetExtraInfo.Data data = effect == null ? null : EntityEffect.CODEC.getData(effect);
                if (carries(data == null ? null : data.getRawTags(), typeName)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // An entity whose effects cannot be read wears no disarm.
        }
        return false;
    }
}
