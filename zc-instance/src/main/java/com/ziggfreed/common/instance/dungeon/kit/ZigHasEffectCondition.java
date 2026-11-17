package com.ziggfreed.common.instance.dungeon.kit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerCondition;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A trigger-volume condition over the entity a volume event is about: it holds while that entity wears the effect
 * {@code Effect} (with {@code Wears} false, while it does not). A room reads a creature's state with it: a lured boss
 * that runs into a pillar only while it carries its charging mark, a plate that a creature only presses while it is
 * enraged.
 *
 * <pre>{@code
 * "Conditions": [ { "Type": "ZigHasEffect", "Event": "TICK", "Effect": "MyPack_Charging" } ]
 * }</pre>
 *
 * <p>An event with no entity (a signal, a volume's own creation) never passes, and an unknown effect id wears on no
 * one. Registered into the engine's trigger condition map at setup ({@code InstanceBootstrap.registerRoomKit}).
 */
public final class ZigHasEffectCondition extends TriggerCondition {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigHasEffect";

    /** The key naming the effect, which the trigger-volume inspector shows with the effect picker. */
    public static final String EFFECT_KEY = "Effect";

    @Nullable
    private String effect;
    @Nullable
    private Boolean wears;

    /** Lazy holder, as an interaction Type's: only registration builds the codec. */
    private static final class Holder {
        static final BuilderCodec<ZigHasEffectCondition> CODEC = BuilderCodec.builder(ZigHasEffectCondition.class,
                        ZigHasEffectCondition::new, TriggerCondition.BASE_CODEC)
                .documentation("Holds while the entity the event is about wears an effect: a creature carrying a "
                        + "mark, a player under a buff. An event with no entity never passes.")
                .append(new KeyedCodec<>("Effect", Codec.STRING, false), (c, v) -> c.effect = v, c -> c.effect)
                .documentation("The effect, by id. Unauthored, or an id no file defines, no one wears it.").add()
                .append(new KeyedCodec<>("Wears", Codec.BOOLEAN, false), (c, v) -> c.wears = v, c -> c.wears)
                .documentation("True (the default) holds while the entity wears the effect; false while it does "
                        + "not.").add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigHasEffectCondition> codec() {
        return Holder.CODEC;
    }

    /** The authored effect id, or null. */
    @Nullable
    public String getEffect() {
        return effect;
    }

    /** Whether the condition holds while the effect is worn (true, the default) or while it is not. */
    public boolean wears() {
        return wears == null || wears;
    }

    @Override
    public boolean test(@Nonnull TriggerContext context) {
        Ref<EntityStore> entity = context.getEntityRef();
        if (entity == null || !entity.isValid()) {
            return false;
        }
        return wears() == wearing(context.getStore(), entity);
    }

    /** Whether {@code entity} wears the effect this condition names. Never throws. */
    private boolean wearing(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> entity) {
        if (effect == null || effect.isBlank()) {
            return false;
        }
        try {
            EntityEffect asset = EntityEffect.getAssetMap().getAsset(effect.trim());
            if (asset == null) {
                return false;
            }
            EffectControllerComponent controller = store.getComponent(entity,
                    EffectControllerComponent.getComponentType());
            return controller != null && controller.hasEffect(asset);
        } catch (Throwable t) {
            return false;
        }
    }
}
