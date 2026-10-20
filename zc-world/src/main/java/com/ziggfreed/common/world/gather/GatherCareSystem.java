package com.ziggfreed.common.world.gather;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.BlockRuleSystems;

/**
 * The care step: with {@code GatherCare} in a hundred (held at the player's {@code GatherCareMax}), a tool hit on a
 * block costs no durability. The engine charges the wear in one write after the hit lands, with no hook between, so
 * the step notes the held tool's durability as each hit starts and, once the world has run the hits, gives back what
 * each cared hit lost and nothing more: two hits in one tick each read their own wear, from one start to the next. The
 * tool must be the same, still in the active hotbar slot, still whole. A hit that broke the tool is not undone, which is
 * why the stat is held under 100. A creative-mode hit, which wears nothing, is left alone.
 */
public final class GatherCareSystem extends EntityEventSystem<EntityStore, DamageBlockEvent> {

    /** The care stat. */
    public static final String CARE_STAT = "GatherCare";

    /** Its ceiling stat. */
    public static final String CEILING_STAT = "GatherCareMax";

    /** The ceiling a player reads when the ceiling stat cannot be read. */
    public static final double FALLBACK_CEILING = 75.0;

    private static final Set<Dependency<EntityStore>> DEPENDENCIES =
            Set.of(new SystemDependency<>(Order.AFTER, BlockRuleSystems.AttributedDamage.class));

    public GatherCareSystem() {
        super(DamageBlockEvent.class);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull DamageBlockEvent event) {
        try {
            if (event.isCancelled() || !(event.getDamage() > 0.0f)) {
                return;
            }
            ItemStack held = event.getItemInHand();
            if (held == null || held.isEmpty() || held.isUnbreakable() || held.isBroken()) {
                return;
            }
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            if (GatherReads.gameMode(store, ref) == GameMode.Creative) {
                return;
            }
            var stats = GatherReads.stats(store, ref);
            double care = Math.min(GatherReads.max(stats, CARE_STAT),
                    GatherReads.ceiling(stats, CEILING_STAT, FALLBACK_CEILING));
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            World world = store.getExternalData().getWorld();
            if (!(care > 0.0) || player == null || world == null) {
                return;
            }
            // Every hit is noted, cared or not: the next hit's start is where this one's wear ends.
            UUID playerId = player.getUuid();
            Hit hit = new Hit(held.getItemId(), held.getDurability(), GatherReads.roll(care));
            List<Hit> hits = PENDING.get(playerId);
            if (hits == null) {
                hits = new ArrayList<>(2);
                PENDING.put(playerId, hits);
                world.execute(() -> settle(store, ref, playerId));
            }
            hits.add(hit);
        } catch (Throwable t) {
            SafeLog.fine("[gather] a tool's care could not be read: " + t.getMessage());
        }
    }

    /** One hit as it started: the tool, its durability then, and whether care spares it. */
    private record Hit(@Nonnull String itemId, double before, boolean cared) {
    }

    /** Each player's hits since their refund last ran, oldest first. World-thread only per player. */
    private static final Map<UUID, List<Hit>> PENDING = new ConcurrentHashMap<>();

    /**
     * Give back what the cared hits took, each its own wear alone: a hit's wear is its start less the next hit's start,
     * the newest's its start less the tool now, read back over the run of hits with the tool still in the active
     * hotbar slot and whole. A hit with another tool ends the run: its wear can no longer be read.
     */
    static void settle(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull UUID playerId) {
        List<Hit> hits = PENDING.remove(playerId);
        try {
            if (hits == null || hits.isEmpty() || !ref.isValid()) {
                return;
            }
            InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            ItemStack now = hotbar == null ? null : hotbar.getActiveItem();
            if (now == null || now.isEmpty() || now.isBroken()) {
                return;
            }
            double refund = 0.0;
            double after = now.getDurability();
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (!hit.itemId().equals(now.getItemId())) {
                    break;
                }
                double lost = hit.before() - after;
                if (hit.cared() && lost > 0.0) {
                    refund += lost;
                }
                after = hit.before();
            }
            if (refund > 0.0) {
                ItemUtils.updateItemStackDurability(ref, now, hotbar.getInventory(), hotbar.getActiveSlot(), refund,
                        store);
            }
        } catch (Throwable t) {
            SafeLog.fine("[gather] a tool's wear could not be given back: " + t.getMessage());
        }
    }
}
