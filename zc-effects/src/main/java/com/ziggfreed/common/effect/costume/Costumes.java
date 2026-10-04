package com.ziggfreed.common.effect.costume;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Puts a costume on an entity and takes costumes off it, over the engine's own effect controller,
 * by the rules {@link CostumeRules} states. A costume is an ordinary native effect: it lasts as long
 * as its asset says, shows its own status icon and saves with the wearer like any other.
 *
 * <p>Taking off goes by the rule, never by a record of who put what on, so it reaches a costume
 * however it got there (this library's Type, a reward, a command, a potion) and still clears one a
 * reconnect restored without its model.
 *
 * <p>World thread only: pass the interaction's command buffer from inside a tick, and the store from
 * a task hopped onto the world.
 */
public final class Costumes {

    /** What one attempt to put a costume on came to. Nothing changed unless it is {@link #DRESSED}. */
    public enum DressOutcome {
        DRESSED, UNKNOWN_EFFECT, NOT_A_COSTUME, LOCKED, WEARING_ANOTHER, CANNOT_WEAR
    }

    /** Authoring mistakes already reported, each once per process. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private Costumes() {
    }

    /** Put the costume {@code effectId} on {@code wearer}, or say why not. */
    @Nonnull
    public static DressOutcome dress(@Nullable ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> wearer, @Nullable String effectId) {
        if (accessor == null || wearer == null || !wearer.isValid() || effectId == null || effectId.isBlank()) {
            return DressOutcome.CANNOT_WEAR;
        }
        String id = effectId.trim();
        EntityEffect wanted = effect(id);
        if (wanted == null) {
            reportOnce(id, "is not a loaded effect, so nobody can be dressed in it");
            return DressOutcome.UNKNOWN_EFFECT;
        }
        EffectControllerComponent controller =
                accessor.getComponent(wearer, EffectControllerComponent.getComponentType());
        if (controller == null) {
            return DressOutcome.CANNOT_WEAR;
        }
        return switch (CostumeRules.dress(lookOf(wanted), wearing(controller))) {
            case NOT_A_COSTUME -> {
                reportOnce(id, "changes no model or is a debuff, so it is never put on as a costume");
                yield DressOutcome.NOT_A_COSTUME;
            }
            case LOCKED -> DressOutcome.LOCKED;
            case WEARING_ANOTHER -> DressOutcome.WEARING_ANOTHER;
            case DRESS -> controller.addEffect(wearer, wanted, accessor)
                    ? DressOutcome.DRESSED : DressOutcome.CANNOT_WEAR;
        };
    }

    /**
     * Take every costume off {@code wearer}.
     *
     * @return how many came off; 0 when none was worn
     */
    public static int takeOff(@Nullable ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> wearer) {
        if (accessor == null || wearer == null || !wearer.isValid()) {
            return 0;
        }
        EffectControllerComponent controller =
                accessor.getComponent(wearer, EffectControllerComponent.getComponentType());
        if (controller == null) {
            return 0;
        }
        int removed = 0;
        for (String id : CostumeRules.costumesIn(wearing(controller))) {
            int index = EntityEffect.getAssetMap().getIndex(id);
            if (index != AssetMapWithIndexes.NOT_FOUND) {
                controller.removeEffect(wearer, index, RemovalBehavior.COMPLETE, accessor);
                removed++;
            }
        }
        return removed;
    }

    @Nullable
    private static EntityEffect effect(@Nonnull String id) {
        int index = EntityEffect.getAssetMap().getIndex(id);
        return index == AssetMapWithIndexes.NOT_FOUND ? null : EntityEffect.getAssetMap().getAsset(index);
    }

    @Nonnull
    private static List<CostumeRules.Look> wearing(@Nonnull EffectControllerComponent controller) {
        List<CostumeRules.Look> out = new ArrayList<>();
        for (int index : controller.getActiveEffectIndexes()) {
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(index);
            if (effect != null) {
                out.add(lookOf(effect));
            }
        }
        return out;
    }

    @Nonnull
    private static CostumeRules.Look lookOf(@Nonnull EntityEffect effect) {
        return new CostumeRules.Look(effect.getId(), effect.getModelChange(), effect.isDebuff());
    }

    private static void reportOnce(@Nonnull String id, @Nonnull String why) {
        if (REPORTED.add(id.toLowerCase(Locale.ROOT) + "|" + why)) {
            SafeLog.warn("[costume] the effect '" + id + "' " + why);
        }
    }
}
