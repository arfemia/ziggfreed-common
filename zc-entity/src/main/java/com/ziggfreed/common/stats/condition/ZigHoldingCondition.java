package com.ziggfreed.common.stats.condition;

import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.HeldItemUtil;

/**
 * An entity condition about what the entity holds in its active hotbar slot: with {@code Block}, a block it could
 * place; with {@code Tool}, a gathering tool (an item with a tool spec for any gather type: a pickaxe, a hatchet, a
 * shovel). Each knob unauthored asks nothing; with neither, it holds while anything is held. Registered into the
 * engine's entity condition map as {@value #TYPE_NAME} (R700).
 *
 * <pre>{@code
 * { "Id": "ZigHolding", "Block": true }
 * { "Id": "ZigHolding", "Tool": true }
 * }</pre>
 */
public final class ZigHoldingCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigHolding";

    @Nullable
    private Boolean block;
    @Nullable
    private Boolean tool;

    private static final class Holder {
        static final BuilderCodec<ZigHoldingCondition> CODEC = BuilderCodec.builder(
                        ZigHoldingCondition.class, ZigHoldingCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while what the entity holds in its active hotbar slot is as written.")
                .append(new KeyedCodec<>("Block", Codec.BOOLEAN, false), (c, v) -> c.block = v, c -> c.block)
                .documentation("True: only while the held item is a block it could place. False: only while it is "
                        + "not. Either when unauthored.")
                .add()
                .append(new KeyedCodec<>("Tool", Codec.BOOLEAN, false), (c, v) -> c.tool = v, c -> c.tool)
                .documentation("True: only while the held item is a gathering tool (a pickaxe, a hatchet, a shovel). "
                        + "False: only while it is not. Either when unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigHoldingCondition> codec() {
        return Holder.CODEC;
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        try {
            InventoryComponent.Hotbar hotbar = componentAccessor.getComponent(ref,
                    InventoryComponent.Hotbar.getComponentType());
            ItemStack held = hotbar == null ? null : hotbar.getActiveItem();
            if (held == null || held.isEmpty()) {
                return false;
            }
            Item item = held.getItem();
            if (item == null) {
                return false;
            }
            if (block != null && block != item.hasBlockType()) {
                return false;
            }
            return tool == null || tool != HeldItemUtil.toolPowersOf(item).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
