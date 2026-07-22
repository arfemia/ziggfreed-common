package com.ziggfreed.common.entity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.PropComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventItemMerging;
import com.hypixel.hytale.server.core.modules.entity.item.PreventPickup;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Builds / spawns / despawns a static, network-replicated, pickup-immune, physics-free PROP
 * entity that visually represents an item at a fixed position - the generic mechanism behind
 * "show an item as a real-world object" (a placed-input display, a shop pedestal, a puppet's
 * held prop, a trophy case, ...). Lifted config-free out of a consumer's own placed-input
 * display (RPG Stations' {@code station.StationCustodyDisplay}), which itself copied the
 * mechanism VERBATIM from the engine's own sanctioned admin "Entity Spawn Page" Items tab
 * ({@code hytale-shared-source/HytaleServer/NPC/.../pages/EntitySpawnPage.java}).
 *
 * <p>Two routes, picked by whether the representative item has a native {@code BlockType}:
 * <ul>
 * <li><b>Block-shaped item</b> ({@link Item#hasBlockType()}) - a {@link BlockEntity} renders the
 * REAL block model (not a flat icon), including the base {@code EntityScaleComponent}
 * scale-doubling ({@link #BLOCK_ENTITY_BASE_SCALE}) that exemplar branch applies (its own tuned
 * default for how a {@code BlockEntity} renders at "true" scale).</li>
 * <li><b>Everything else</b> (most weapons/tools - no dedicated entity-atlas {@code ModelAsset})
 * - a bare {@link ItemComponent} with {@code setOverrideDroppedItemAnimation(true)}, the generic
 * "dropped item minus physics" prop.</li>
 * </ul>
 * The THIRD exemplar route ({@code ModelAsset}-backed items) is deliberately NOT implemented
 * (rare in practice per that method's own comment).
 *
 * <p><b>Pickup-disable</b>: {@link PreventPickup#INSTANCE} (a pure marker) - the native
 * {@code PlayerItemEntityPickupSystem} query excludes it (plus {@link PropComponent}, which both
 * routes also carry). <b>Never-persisted, by construction</b>: both routes
 * {@code ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType())} - the same
 * native {@code NonSerialized} marker several first-party transient-entity systems use - so a
 * display entity CANNOT survive a server restart, and a consumer never needs an orphan-reconcile
 * pass on boot.
 *
 * <p><b>Two-phase API</b>: {@link #buildHolder} returns an un-added {@link Holder} so a caller
 * can add its OWN components (e.g. a press-F retrieve interaction) before committing via
 * {@link #spawn(ComponentAccessor, Holder)}; {@link #spawn(ComponentAccessor, String, Vector3d,
 * Rotation3f, float)} is the one-call convenience for a caller with no such customization.
 *
 * <p><b>Accessor shape</b>: every build/spawn call takes a {@link ComponentAccessor}
 * {@code <EntityStore>} (the interface both {@link Store} and {@link CommandBuffer} implement),
 * so a caller inside an interaction-handler / tick processing lock (which must defer entity
 * mutation to a {@code CommandBuffer} - a direct {@code store.addEntity} throws
 * {@code IllegalStateException("Store is currently processing!")} there) and a caller with a
 * live {@code Store} both work through the SAME method. {@link #despawn} needs the CONCRETE type
 * (the interface's {@code removeEntity} requires a {@code Holder} neither caller has at despawn
 * time), so it is overloaded on {@link Store}/{@link CommandBuffer} directly. WORLD-THREAD ONLY;
 * every engine-touching call is try-guarded to a no-op / {@code null}, never a throw.
 */
public final class ItemPropEntityService {

    /**
     * The engine's own tuned default for how a {@link BlockEntity} renders at "true" scale
     * ({@code EntitySpawnPage.BLOCK_ENTITY_BASE_SCALE}, hytale-shared-source NPC module).
     */
    private static final float BLOCK_ENTITY_BASE_SCALE = 2f;

    private ItemPropEntityService() {
    }

    /**
     * Builds (but does not add) a prop-entity {@link Holder} for {@code itemId} at
     * {@code position}/{@code rotation}/{@code scale}. Returns {@code null} (never throws) on a
     * blank {@code itemId} or any resolution failure - the caller treats a null return as
     * "no visual this time", never a hard error.
     */
    @Nullable
    public static Holder<EntityStore> buildHolder(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull String itemId, @Nonnull Vector3d position, @Nonnull Rotation3f rotation, float scale) {
        if (itemId.isBlank()) {
            return null;
        }
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            return (item != null && item.hasBlockType())
                    ? buildBlockEntityHolder(itemId, position, rotation, scale)
                    : buildItemEntityHolder(accessor, itemId, position, rotation, scale);
        } catch (Throwable t) {
            warn("buildHolder failed for '" + itemId + "': " + t.getMessage(), t);
            return null;
        }
    }

    @Nonnull
    private static Holder<EntityStore> buildBlockEntityHolder(@Nonnull String itemId, @Nonnull Vector3d position,
            @Nonnull Rotation3f rotation, float scale) {
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(itemId));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(position, rotation));
        holder.addComponent(EntityScaleComponent.getComponentType(),
                new EntityScaleComponent(scale * BLOCK_ENTITY_BASE_SCALE));
        ItemStack tooltip = new ItemStack(itemId, 1);
        tooltip.setOverrideDroppedItemAnimation(true);
        holder.addComponent(ItemComponent.getComponentType(), new ItemComponent(tooltip));
        holder.addComponent(PreventPickup.getComponentType(), PreventPickup.INSTANCE);
        holder.addComponent(PreventItemMerging.getComponentType(), PreventItemMerging.INSTANCE);
        holder.addComponent(PropComponent.getComponentType(), PropComponent.get());
        holder.ensureComponent(UUIDComponent.getComponentType());
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        return holder;
    }

    @Nonnull
    private static Holder<EntityStore> buildItemEntityHolder(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull String itemId, @Nonnull Vector3d position, @Nonnull Rotation3f rotation, float scale) {
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(accessor.getExternalData().takeNextNetworkId()));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(position, rotation));
        ItemStack tooltip = new ItemStack(itemId, 1);
        tooltip.setOverrideDroppedItemAnimation(true);
        holder.addComponent(ItemComponent.getComponentType(), new ItemComponent(tooltip));
        holder.addComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(scale));
        holder.addComponent(PreventPickup.getComponentType(), PreventPickup.INSTANCE);
        holder.addComponent(PreventItemMerging.getComponentType(), PreventItemMerging.INSTANCE);
        holder.addComponent(HeadRotation.getComponentType(), new HeadRotation(rotation));
        holder.addComponent(PropComponent.getComponentType(), PropComponent.get());
        holder.ensureComponent(UUIDComponent.getComponentType());
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        return holder;
    }

    /** Commits an already-built {@code holder} (see {@link #buildHolder}). Never throws; {@code null} on failure. */
    @Nullable
    public static Ref<EntityStore> spawn(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Holder<EntityStore> holder) {
        try {
            return accessor.addEntity(holder, AddReason.SPAWN);
        } catch (Throwable t) {
            warn("spawn failed: " + t.getMessage(), t);
            return null;
        }
    }

    /** One-call convenience: {@link #buildHolder} then {@link #spawn(ComponentAccessor, Holder)}. */
    @Nullable
    public static Ref<EntityStore> spawn(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull String itemId,
            @Nonnull Vector3d position, @Nonnull Rotation3f rotation, float scale) {
        Holder<EntityStore> holder = buildHolder(accessor, itemId, position, rotation, scale);
        return holder != null ? spawn(accessor, holder) : null;
    }

    /** Despawns {@code propRef} via a live {@link Store}. No-op (never throws) when already gone. */
    public static void despawn(@Nullable Ref<EntityStore> propRef, @Nonnull Store<EntityStore> store) {
        if (propRef == null || !propRef.isValid()) {
            return;
        }
        try {
            store.removeEntity(propRef, RemoveReason.REMOVE);
        } catch (Throwable t) {
            fine("despawn failed: " + t.getMessage());
        }
    }

    /**
     * Despawns {@code propRef} via a {@link CommandBuffer} (the tick-safe route for a caller
     * inside an interaction-handler / tick processing lock). No-op (never throws) when already
     * gone or {@code commandBuffer} is null.
     */
    public static void despawn(@Nullable Ref<EntityStore> propRef, @Nullable CommandBuffer<EntityStore> commandBuffer) {
        if (propRef == null || !propRef.isValid() || commandBuffer == null) {
            return;
        }
        try {
            commandBuffer.removeEntity(propRef, RemoveReason.REMOVE);
        } catch (Throwable t) {
            fine("despawn failed: " + t.getMessage());
        }
    }

    private static void warn(@Nonnull String message, @Nullable Throwable cause) {
        try {
            if (cause != null) {
                ZiggfreedCommonPlugin.LOGGER.atWarning().withCause(cause)
                        .log("[ziggfreed-common][itemprop] " + message);
            } else {
                ZiggfreedCommonPlugin.LOGGER.atWarning().log("[ziggfreed-common][itemprop] " + message);
            }
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }

    private static void fine(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("[ziggfreed-common][itemprop] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
