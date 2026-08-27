package com.ziggfreed.common.inventory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The non-deprecated replacements for {@code Inventory}'s {@code getStorage}/{@code getHotbar}/
 * {@code getActiveHotbarItem}/{@code getCombinedBackpackStorageHotbar} and
 * {@code Player}'s {@code getPlayerRef}, all five marked {@code @Deprecated(forRemoval = true)}.
 * Each method here is the exact replacement named on the deprecated method's own javadoc:
 * fetch the equivalent {@link InventoryComponent} section (or the {@link PlayerRef} component)
 * directly, reading the raw hotbar slot as the engine itself stores it (an empty slot is real
 * Java {@code null}, never an {@code EMPTY} sentinel).
 *
 * <p>The ONE SHARED primitive both the MMO and RpgStations call for these reads, converged out of
 * two independently-drifted copies. It is not a claim that no inline copy of the same component
 * fetch exists anywhere: a caller that already holds the section component for its own reasons
 * still reads it directly (this library's own {@code entity/HeldItemUtil} does, and so do a
 * handful of consumer sites). What it does mean is that a caller wanting the accessor SHAPE has
 * one place to get it, so the replacement for a deprecated engine accessor is never re-derived.
 *
 * <p>Every two-argument overload takes a {@link ComponentAccessor} (a
 * {@link com.hypixel.hytale.component.Store} already satisfies it), so it works from a system tick
 * or a {@code CommandBuffer} alike. Every {@link Player} overload resolves its own {@link Ref}
 * through one shared guard that treats a {@code null} ref AND an invalid one
 * identically, so a caller reading straight off a {@link Player} never has to guard twice.
 *
 * <p><b>The two-argument forms are RAW reads.</b> They are world-thread only and they do NOT
 * pre-check the ref: an invalid one reaches {@code Store#getComponent}, which throws
 * {@code IllegalStateException("Invalid entity reference!")}, and an off-thread call trips the
 * store's own thread assertion. That is deliberate (a system tick already holds a live ref, and
 * swallowing an engine throw there would hide a real bug), but it means they do NOT degrade to
 * {@code null} the way the {@link Player} overloads do. Pass one a ref you know is live.
 */
public final class PlayerAccess {

    private PlayerAccess() {
    }

    /** The player's Storage section (replaces {@code Inventory#getStorage()}). */
    @Nullable
    public static InventoryComponent.Storage storage(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref) {
        return accessor.getComponent(ref, InventoryComponent.Storage.getComponentType());
    }

    /** Convenience overload deriving the store from the player's own reference. */
    @Nullable
    public static InventoryComponent.Storage storage(@Nonnull Player player) {
        Ref<EntityStore> ref = refOf(player);
        return ref == null ? null : storage(ref.getStore(), ref);
    }

    /** The player's Hotbar section (replaces {@code Inventory#getHotbar()}). */
    @Nullable
    public static InventoryComponent.Hotbar hotbar(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref) {
        return accessor.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
    }

    /** Convenience overload deriving the store from the player's own reference. */
    @Nullable
    public static InventoryComponent.Hotbar hotbar(@Nonnull Player player) {
        Ref<EntityStore> ref = refOf(player);
        return ref == null ? null : hotbar(ref.getStore(), ref);
    }

    /**
     * The item in the player's currently active hotbar slot (replaces
     * {@code Inventory#getActiveHotbarItem()}). Reads {@link InventoryComponent.Hotbar#getActiveItem()}
     * directly with no extra emptiness re-check: an empty slot is already {@code null} in the
     * native container, so a missing hotbar and an empty slot answer the same way.
     */
    @Nullable
    public static ItemStack activeHotbarItem(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Hotbar hotbar = hotbar(accessor, ref);
        return hotbar != null ? hotbar.getActiveItem() : null;
    }

    /** Convenience overload deriving the store from the player's own reference. */
    @Nullable
    public static ItemStack activeHotbarItem(@Nonnull Player player) {
        Ref<EntityStore> ref = refOf(player);
        return ref == null ? null : activeHotbarItem(ref.getStore(), ref);
    }

    /**
     * The player's combined backpack + storage + hotbar view (replaces
     * {@code Inventory#getCombinedBackpackStorageHotbar()}).
     */
    @Nonnull
    public static CombinedItemContainer combinedBackpackStorageHotbar(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref) {
        return InventoryComponent.getCombined(accessor, ref, InventoryComponent.BACKPACK_STORAGE_HOTBAR);
    }

    /**
     * Convenience overload deriving the store from the player's own reference.
     *
     * @return {@code null} when the player's ref cannot be resolved, rather than throwing
     */
    @Nullable
    public static CombinedItemContainer combinedBackpackStorageHotbar(@Nonnull Player player) {
        Ref<EntityStore> ref = refOf(player);
        return ref == null ? null : combinedBackpackStorageHotbar(ref.getStore(), ref);
    }

    /**
     * Replaces {@code Player#getPlayerRef()}: fetch the {@link PlayerRef} component directly.
     */
    @Nullable
    public static PlayerRef playerRef(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref) {
        return accessor.getComponent(ref, PlayerRef.getComponentType());
    }

    /** Convenience overload deriving the store from the player's own reference. */
    @Nullable
    public static PlayerRef playerRef(@Nonnull Player player) {
        Ref<EntityStore> ref = refOf(player);
        return ref == null ? null : playerRef(ref.getStore(), ref);
    }

    /**
     * The inverse of {@link #playerRef(Player)}: resolve the live {@link Player} entity behind
     * {@code playerRef}, or null when the reference is absent, stale, or the component is missing.
     * The ONE resolve-a-player primitive, so "a reference names a player, the entity is one" is
     * derived in a single place; world-thread only, like every resolved-entity read.
     */
    @Nullable
    public static Player player(@Nullable PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef == null ? null : playerRef.getReference();
        if (!usable(ref)) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        return store == null ? null : store.getComponent(ref, Player.getComponentType());
    }

    /**
     * Whether a {@link Ref} can be read through: present, AND still pointing at a live entity.
     * The ONE guard every {@link Player} overload above resolves its own ref with. Package-private
     * on purpose: no consumer asks this question today, and a shared library gains no public
     * surface it has no caller for. Widen it when a real caller arrives, not before.
     */
    static boolean usable(@Nullable Ref<EntityStore> ref) {
        return ref != null && ref.isValid();
    }

    @Nullable
    private static Ref<EntityStore> refOf(@Nonnull Player player) {
        Ref<EntityStore> ref = player.getReference();
        return usable(ref) ? ref : null;
    }
}
