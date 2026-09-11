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
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.PropComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventItemMerging;
import com.hypixel.hytale.server.core.modules.entity.item.PreventPickup;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.CommonLog;

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
 * REAL block model (not a flat icon), keyed by the item's own {@link Item#getBlockId() block id}
 * rather than the item id: for a block item the two are the same string, and for a {@code Parent}
 * child that authors no block section of its own (a marker item that only re-tints the parent's
 * dropped-item halo) the block id is the parent's, so the child draws the parent's block instead
 * of an unknown key the client would skip. {@code scale} is written to {@link EntityScaleComponent}
 * VERBATIM, exactly as the exemplar branch and every other first-party {@code BlockEntity} spawn
 * writes it, so {@code 1.0} is one block wide and a caller's number means the same thing on both
 * routes (the builder-tools prefab anchor pins that reading: it scales its own {@code BlockEntity}
 * to {@code 1.05} purely to sit a hair proud of the real block it overlays).</li>
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
     * How a prop is built beyond its item, position and size: two INDEPENDENT switches, each off
     * by default so every existing caller keeps the static, touchable prop it always had.
     *
     * @param intangible           carry the engine's {@link Intangible} presence marker, which takes
     *                             the prop out of the melee and projectile candidate indexes so a
     *                             swing or an arrow passes straight through it (a floating cue over
     *                             somebody's head, never a thing to hit)
     * @param droppedItemAnimation let the client play its own dropped-item idle motion (the slow
     *                             turn and bob) instead of freezing the prop; the motion costs the
     *                             server nothing, since the client animates it alone
     */
    public record Options(boolean intangible, boolean droppedItemAnimation) {

        /** A static, touchable prop: what every caller before the switches existed was given. */
        public static final Options DEFAULT = new Options(false, false);

        /** This with the prop taken out of the hit indexes. */
        @Nonnull
        public Options withIntangible() {
            return new Options(true, droppedItemAnimation);
        }

        /** This with the client's dropped-item idle motion playing. */
        @Nonnull
        public Options withDroppedItemAnimation() {
            return new Options(intangible, true);
        }
    }

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
        return buildHolder(accessor, itemId, position, rotation, scale, Options.DEFAULT);
    }

    /**
     * {@link #buildHolder(ComponentAccessor, String, Vector3d, Rotation3f, float)} with the two
     * {@link Options} switches: intangible, and animated the way a dropped item is.
     */
    @Nullable
    public static Holder<EntityStore> buildHolder(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull String itemId, @Nonnull Vector3d position, @Nonnull Rotation3f rotation, float scale,
            @Nonnull Options options) {
        if (itemId.isBlank()) {
            return null;
        }
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            return (item != null && item.hasBlockType())
                    ? buildBlockEntityHolder(itemId, item.getBlockId(), position, rotation, scale, options)
                    : buildItemEntityHolder(accessor, itemId, position, rotation, scale, options);
        } catch (Throwable t) {
            warn("buildHolder failed for '" + itemId + "': " + t.getMessage(), t);
            return null;
        }
    }

    /**
     * The block route: the entity the client draws is the block behind {@code blockId}, the stack
     * it carries (tooltip, pickup rules, the dropped-item halo the item's own config names) is
     * {@code itemId}'s.
     */
    @Nonnull
    private static Holder<EntityStore> buildBlockEntityHolder(@Nonnull String itemId, @Nonnull String blockId,
            @Nonnull Vector3d position, @Nonnull Rotation3f rotation, float scale, @Nonnull Options options) {
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(blockId));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(position, rotation));
        holder.addComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(scale));
        holder.addComponent(ItemComponent.getComponentType(), new ItemComponent(tooltipStack(itemId, options)));
        holder.addComponent(PreventPickup.getComponentType(), PreventPickup.INSTANCE);
        holder.addComponent(PreventItemMerging.getComponentType(), PreventItemMerging.INSTANCE);
        holder.addComponent(PropComponent.getComponentType(), PropComponent.get());
        holder.ensureComponent(UUIDComponent.getComponentType());
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        applyPresence(holder, options);
        return holder;
    }

    @Nonnull
    private static Holder<EntityStore> buildItemEntityHolder(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull String itemId, @Nonnull Vector3d position, @Nonnull Rotation3f rotation, float scale,
            @Nonnull Options options) {
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(accessor.getExternalData().takeNextNetworkId()));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(position, rotation));
        holder.addComponent(ItemComponent.getComponentType(), new ItemComponent(tooltipStack(itemId, options)));
        holder.addComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(scale));
        holder.addComponent(PreventPickup.getComponentType(), PreventPickup.INSTANCE);
        holder.addComponent(PreventItemMerging.getComponentType(), PreventItemMerging.INSTANCE);
        holder.addComponent(HeadRotation.getComponentType(), new HeadRotation(rotation));
        holder.addComponent(PropComponent.getComponentType(), PropComponent.get());
        holder.ensureComponent(UUIDComponent.getComponentType());
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        applyPresence(holder, options);
        return holder;
    }

    /**
     * The one-item stack the prop renders from. Overriding the dropped-item animation is what
     * FREEZES the prop; leaving it alone lets the client's own idle motion play.
     */
    @Nonnull
    private static ItemStack tooltipStack(@Nonnull String itemId, @Nonnull Options options) {
        ItemStack tooltip = new ItemStack(itemId, 1);
        tooltip.setOverrideDroppedItemAnimation(!options.droppedItemAnimation());
        return tooltip;
    }

    /** The intangible marker, when asked for; both routes carry it the same way. */
    private static void applyPresence(@Nonnull Holder<EntityStore> holder, @Nonnull Options options) {
        if (options.intangible()) {
            holder.addComponent(Intangible.getComponentType(), Intangible.INSTANCE);
        }
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
                CommonLog.LOGGER.atWarning().withCause(cause)
                        .log("[ziggfreed-common][itemprop] " + message);
            } else {
                CommonLog.LOGGER.atWarning().log("[ziggfreed-common][itemprop] " + message);
            }
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }

    private static void fine(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atFine().log("[ziggfreed-common][itemprop] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
