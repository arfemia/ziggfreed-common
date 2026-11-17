package com.ziggfreed.common.effect.warcry;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.ziggfreed.common.effect.NativeEffectUtil;
import com.ziggfreed.common.stats.StatCarriers;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.util.ZigScheduler;

/**
 * Runs the war cries ({@link WarCries}): every creature wearing a war-cry effect, on its turn of the creature cadence
 * ({@link StatCarriers#due}). Once a player stands within {@value #FIGHT_RANGE} blocks and no other cry within its
 * radius is still in force, it shouts (its shout effect on it), and after the cry's delay every creature within the
 * radius that is hostile to players by default, itself too, takes the rally effect; it shouts again after the cry's
 * interval. The rally lands on the world's own thread, through the engine's effect add, never inside this tick.
 *
 * <p>Each wearer's next shout is kept by its UUID, and each world's cries in force by place, both forgotten soon after
 * they stop mattering, so nothing is saved with a creature.
 */
public final class WarCrySystem extends EntityTickingSystem<EntityStore> {

    /** How close a player must stand for a wearer to count as in a fight, in blocks. */
    static final double FIGHT_RANGE = 16.0;

    /** How long a wearer waits before asking again when no player is near, or another cry holds, in ms. */
    private static final long RETRY_MS = 1_000L;

    /** How long a wearer's next shout is remembered after it was last seen, in ms. */
    private static final long FORGET_MS = 60_000L;

    /** A cry's hold on its place when its rally effect names no length of its own, in ms. */
    private static final long DEFAULT_HOLD_MS = 8_000L;

    /** When each wearer may shout next, and when it was last seen. */
    private record Clock(long nextAt, long seenAt) {
    }

    /** A cry in force at a place, until a moment, in a world. */
    private record Held(@Nonnull UUID world, double x, double y, double z, double radius, long untilMs) {
    }

    private static final Map<UUID, Clock> CLOCKS = new ConcurrentHashMap<>();
    private static final List<Held> HELD = new ArrayList<>();
    private static volatile long lastSweepMs;

    @Nonnull
    private final Query<EntityStore> query = Query.and(NPCEntity.getComponentType(),
            EffectControllerComponent.getComponentType(), TransformComponent.getComponentType());

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            if (!WarCries.any() || !StatCarriers.due(store, chunk, index)) {
                return;
            }
            EffectControllerComponent effects = chunk.getComponent(index, EffectControllerComponent.getComponentType());
            WarCries.Cry cry = effects == null ? null : cryOn(effects);
            if (cry == null) {
                return;
            }
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            UUIDComponent id = chunk.getComponent(index, UUIDComponent.getComponentType());
            TransformComponent at = chunk.getComponent(index, TransformComponent.getComponentType());
            if (id == null || at == null || chunk.getComponent(index, DeathComponent.getComponentType()) != null) {
                return;
            }
            long now = System.currentTimeMillis();
            sweep(now);
            UUID uuid = id.getUuid();
            Clock clock = CLOCKS.get(uuid);
            if (clock != null && now < clock.nextAt()) {
                CLOCKS.put(uuid, new Clock(clock.nextAt(), now));
                return;
            }
            Vector3d position = new Vector3d(at.getPosition());
            World world = store.getExternalData().getWorld();
            UUID worldId = world == null ? null : world.getWorldConfig().getUuid();
            if (world == null || worldId == null || !playerNear(store, position)
                    || !claim(worldId, position, cry.radius(), now, holdMs(cry))) {
                CLOCKS.put(uuid, new Clock(now + RETRY_MS, now));
                return;
            }
            long every = Math.round(1000.0 * ThreadLocalRandom.current().nextDouble(cry.everyMin(),
                    Math.nextUp(cry.everyMax())));
            CLOCKS.put(uuid, new Clock(now + every, now));
            shout(world, ref, cry);
        } catch (Throwable t) {
            SafeLog.fine("[warcry] a war cry could not be read: " + t.getMessage());
        }
    }

    /** The war cry the first war-cry effect {@code effects} holds carries, or null. */
    @Nullable
    private static WarCries.Cry cryOn(@Nonnull EffectControllerComponent effects) {
        if (effects.getActiveEffects().isEmpty()) {
            return null;
        }
        for (ActiveEntityEffect active : effects.getActiveEffects().values()) {
            WarCries.Cry cry = WarCries.at(active.getEntityEffectIndex());
            if (cry != null) {
                return cry;
            }
        }
        return null;
    }

    /** Whether a player stands within {@value #FIGHT_RANGE} blocks of {@code position}. */
    private static boolean playerNear(@Nonnull Store<EntityStore> store, @Nonnull Vector3d position) {
        SpatialResource<Ref<EntityStore>, EntityStore> players =
                store.getResource(EntityModule.get().getPlayerSpatialResourceType());
        if (players == null) {
            return false;
        }
        List<Ref<EntityStore>> found = new ArrayList<>();
        players.getSpatialStructure().collect(position, FIGHT_RANGE, found);
        for (Ref<EntityStore> player : found) {
            if (player != null && player.isValid() && store.getComponent(player, DeathComponent.getComponentType()) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Take the place for a cry: false when another cry still in force reaches it, else the cry holds it until
     * {@code now + holdMs}. One lock over every world's held places, which are few and short-lived.
     */
    private static boolean claim(@Nonnull UUID world, @Nonnull Vector3d at, double radius, long now, long holdMs) {
        synchronized (HELD) {
            for (Held held : HELD) {
                if (held.untilMs() <= now || !held.world().equals(world)) {
                    continue;
                }
                double reach = Math.max(radius, held.radius());
                double dx = held.x() - at.x;
                double dy = held.y() - at.y;
                double dz = held.z() - at.z;
                if (dx * dx + dy * dy + dz * dz <= reach * reach) {
                    return false;
                }
            }
            HELD.add(new Held(world, at.x, at.y, at.z, radius, now + holdMs));
            return true;
        }
    }

    /** How long a cry holds its place: its delay and its rally effect's own length. */
    private static long holdMs(@Nonnull WarCries.Cry cry) {
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(cry.rally());
        float seconds = effect == null || effect.isInfinite() ? 0f : effect.getDuration();
        long rally = seconds > 0f ? Math.round(seconds * 1000.0) : DEFAULT_HOLD_MS;
        return Math.round(cry.delay() * 1000.0) + rally;
    }

    /** The shout now, on the world thread; the rally after the cry's delay. */
    private static void shout(@Nonnull World world, @Nonnull Ref<EntityStore> wearer, @Nonnull WarCries.Cry cry) {
        world.execute(() -> {
            if (!wearer.isValid()) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (cry.shout() != null) {
                NativeEffectUtil.apply(store, wearer, cry.shout());
            }
        });
        long delayMs = Math.round(cry.delay() * 1000.0);
        try {
            ZigScheduler.schedule(() -> rally(world, wearer, cry), delayMs, TimeUnit.MILLISECONDS, world);
        } catch (Throwable notRunning) {
            world.execute(() -> rally(world, wearer, cry));
        }
    }

    /** On the world thread: every creature hostile to players within the cry's radius of the wearer takes the rally. */
    private static void rally(@Nonnull World world, @Nonnull Ref<EntityStore> wearer, @Nonnull WarCries.Cry cry) {
        try {
            if (!wearer.isValid()) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (store.getComponent(wearer, DeathComponent.getComponentType()) != null) {
                return;
            }
            TransformComponent at = store.getComponent(wearer, TransformComponent.getComponentType());
            SpatialResource<Ref<EntityStore>, EntityStore> creatures =
                    store.getResource(NPCPlugin.get().getNpcSpatialResource());
            if (at == null || creatures == null) {
                return;
            }
            List<Ref<EntityStore>> found = new ArrayList<>();
            creatures.getSpatialStructure().collect(new Vector3d(at.getPosition()), cry.radius(), found);
            if (!found.contains(wearer)) {
                found.add(wearer);
            }
            for (Ref<EntityStore> creature : found) {
                if (creature != null && creature.isValid()
                        && store.getComponent(creature, DeathComponent.getComponentType()) == null
                        && hostile(store, creature)) {
                    NativeEffectUtil.apply(store, creature, cry.rally());
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[warcry] a war cry's rally could not land: " + t.getMessage());
        }
    }

    /** Whether {@code creature} is hostile to players by default, the foes a cry rallies. */
    private static boolean hostile(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> creature) {
        WorldSupport support = store.getComponent(creature, WorldSupport.getComponentType());
        return support != null && support.getDefaultPlayerAttitude() == Attitude.HOSTILE;
    }

    /** Forget a wearer's clock long unseen and a cry no longer in force, a few times a minute at most. */
    private static void sweep(long now) {
        if (now - lastSweepMs < 10_000L) {
            return;
        }
        lastSweepMs = now;
        CLOCKS.values().removeIf(clock -> now - clock.seenAt() > FORGET_MS);
        synchronized (HELD) {
            Iterator<Held> it = HELD.iterator();
            while (it.hasNext()) {
                if (it.next().untilMs() <= now) {
                    it.remove();
                }
            }
        }
    }
}
