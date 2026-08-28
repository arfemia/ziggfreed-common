package com.ziggfreed.common.instance.reward;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Rotation3fc;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.cast.WorldEvictors;

import org.joml.Vector3d;

/**
 * The XP-AGNOSTIC engine-touching half of the loot primitive: rolls a native Hytale {@code ItemDropList}
 * for its item stacks and spawns them on the ground, so authored loot can delegate item SELECTION to a
 * native asset instead of naming every item itself. Common ships this as the one seam any consumer (a
 * minigame results-claim, an MMO's bonus mob loot, a luck-loot ground drop, an overflow sink for a full
 * inventory) rolls a native drop table or ground-spawns item stacks through, so the native-roll +
 * in-world-spawn idiom is written once, not re-derived per mod. Mirrors the sibling
 * {@code mmo-mob-scaling} {@code MobScalingLootDropSystem} pattern (native roll via
 * {@code ItemModule.getRandomItemDrops} + spawn via {@code ItemComponent.generateItemDrops}); knows nothing
 * about XP, skills, or any other MMO concept.
 *
 * <p><b>Non-throwing, and every spawn answers.</b> Every primitive is whole-body try-guarded: a disabled
 * {@link ItemModule}, an unregistered drop-list id (warned once per distinct id, mirroring
 * {@code MobScalingLootDropSystem}'s {@code WARNED_IDS} convention), or any engine throw degrades to an
 * empty roll / a refused spawn, never an exception into the caller's tick. Each spawn form answers
 * whether the drop LANDED - true when the entities were added, or when the add was handed to the owning
 * world's thread to land right after the current tick; false when nothing spawned and nothing was
 * queued, in which case the warn names the exact stacks that were lost.
 */
public final class NativeLootService {

    /** Warn-once-per-distinct-id set for a {@code dropListId} no {@link ItemDropList} asset claims. */
    private static final Set<String> WARNED_IDS = ConcurrentHashMap.newKeySet();

    /**
     * The lift {@link #spawnAtFeet} applies above the entity's own position: the same small lift the
     * engine gives a mob's death drops, so items bounce on the floor instead of inside it.
     */
    private static final double FEET_DROP_LIFT = 1.0;

    /** How many stacks a lost-items warn names outright before it just counts the rest. */
    private static final int WARN_STACKS_NAMED = 8;

    /**
     * The live-engine roll, swappable ONLY by same-package tests (see {@code NativeLootServiceTest}) that
     * cannot boot a real {@link ItemModule} / asset store in a bare unit-test JVM. Production code never
     * touches this field; {@link #rollNative} is the only public entry point.
     */
    @Nonnull
    private static Function<String, List<ItemStack>> engineRoll = NativeLootService::liveRoll;

    private NativeLootService() {
    }

    /**
     * Roll the native {@code ItemDropList} named {@code dropListId} for its item stacks: zero side effects,
     * just the resolved stacks (mirrors {@code ItemModule.getRandomItemDrops}). Returns an empty list (never
     * {@code null}, never throws) when the id is blank, {@link ItemModule} is disabled, or no
     * {@link ItemDropList} asset claims that id (warned once per distinct unknown id).
     */
    @Nonnull
    public static List<ItemStack> rollNative(@Nonnull String dropListId) {
        if (dropListId.isBlank()) {
            return List.of();
        }
        try {
            List<ItemStack> rolled = engineRoll.apply(dropListId);
            return rolled != null ? rolled : List.of();
        } catch (Throwable t) {
            warn("rollNative('" + dropListId + "') failed: " + t.getMessage());
            return List.of();
        }
    }

    @Nonnull
    private static List<ItemStack> liveRoll(@Nonnull String dropListId) {
        ItemModule itemModule = ItemModule.get();
        if (itemModule == null || !itemModule.isEnabled()) {
            return List.of();
        }
        if (ItemDropList.getAssetMap().getAsset(dropListId) == null) {
            if (WARNED_IDS.add(dropListId)) {
                warn("native drop list '" + dropListId + "' has no ItemDropList asset; no items will roll");
            }
            return List.of();
        }
        return itemModule.getRandomItemDrops(dropListId);
    }

    /**
     * Spawn {@code items} on the ground at {@code position}/{@code rotation} (a no-op answering true for
     * an empty list), via the native {@code ItemComponent.generateItemDrops} +
     * {@code CommandBuffer.addEntities} idiom (the exact shape {@code MobScalingLootDropSystem} uses for
     * its bonus mob loot). World-thread only (touches the {@link Store}); whole-body try-guarded so a
     * loot throw never breaks the caller's tick.
     *
     * <p>This pair form is the PREFERRED route from inside a system or interaction tick: the tick's own
     * {@code CommandBuffer} queues the add for after the tick, which a direct {@code Store} add cannot do
     * while the store is processing.
     *
     * @return true when the drop entities were added; false when the spawn failed (warned, naming the
     *         stacks that were lost).
     */
    public static boolean spawnInWorld(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                       @Nonnull Vector3d position, @Nonnull Rotation3f rotation,
                                       @Nonnull List<ItemStack> items) {
        return spawnGuarded(store, commandBuffer, position, rotation, items);
    }

    /**
     * The ONE-accessor form, for a caller that is not inside a system tick: a reward paying out from
     * a page click, a command, or an event handler holding nothing but a live {@link Store}.
     *
     * <p>{@link Store} and {@code CommandBuffer} are both {@link ComponentAccessor}s, so the same
     * handle can both build the drop entities and add them. Use THIS form when you hold one accessor
     * and the tick-safe pair above when you are inside a tick, where a direct
     * {@code store.addEntities} is rejected while the store is processing and the mutation has to be
     * queued on the buffer instead.
     *
     * <p><b>The safety net:</b> when a {@link Store}-routed add is rejected because the caller WAS
     * inside a tick after all (the engine's write-processing assert - typical for a reward grant fired
     * off a moment producer, where no buffer exists to thread), the same spawn is re-queued onto the
     * owning world's thread and lands right after the tick, answering true. It is a net under the
     * payout paths that cannot carry a buffer, not a licence to skip passing one where you have it: a
     * caller inside a tick that holds the tick's {@code CommandBuffer} should still use the pair form,
     * which lands in the same tick's flush instead of a follow-up task.
     *
     * <p>The rotation is the read-only {@code Rotation3fc} the engine's own drop call takes, so a
     * caller with nothing to aim by can pass {@code Rotation3f.IDENTITY} without building a rotation
     * of its own.
     *
     * @return true when the drop entities were added or re-queued onto the owning world's thread;
     *         false when nothing spawned and nothing was queued (warned, naming the stacks that were
     *         lost).
     */
    public static boolean spawnInWorld(@Nonnull ComponentAccessor<EntityStore> accessor,
                                       @Nonnull Vector3d position, @Nonnull Rotation3fc rotation,
                                       @Nonnull List<ItemStack> items) {
        if (items.isEmpty()) {
            return true;
        }
        try {
            spawnNow(accessor, accessor, position, rotation, items);
            return true;
        } catch (IllegalStateException processing) {
            // The store refused a direct write; the world thread can retry it right after the tick.
            return requeueOnWorldThread(accessor, position, rotation, items, processing);
        } catch (Throwable t) {
            warnLost(items, t);
            return false;
        }
    }

    /**
     * Spawn {@code items} on the ground at {@code ref}'s own feet (its position lifted
     * {@value #FEET_DROP_LIFT} block, the engine's own mob-death-drop lift), in whatever world that
     * entity is in. The drop-at-feet primitive behind an overflow sink or any "give it back on the
     * ground" path; routes through the one-accessor {@link #spawnInWorld(ComponentAccessor, Vector3d,
     * Rotation3fc, List)} and inherits its safety net, so it is safe to call from inside a tick.
     *
     * @return true when the drop entities were added or re-queued onto the owning world's thread;
     *         false when the ref is dead, its position is unreadable, or the spawn failed (warned,
     *         naming the stacks that were lost).
     */
    public static boolean spawnAtFeet(@Nonnull Ref<EntityStore> ref, @Nonnull List<ItemStack> items) {
        if (items.isEmpty()) {
            return true;
        }
        try {
            if (!ref.isValid()) {
                warnLost(items, new IllegalStateException("the receiving entity's ref is no longer valid"));
                return false;
            }
            Store<EntityStore> store = ref.getStore();
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                warnLost(items, new IllegalStateException("the receiving entity has no readable position"));
                return false;
            }
            // A defensive copy: the engine hands back its live vector, and adding to it in place
            // would teleport whoever is standing on it.
            Vector3d at = new Vector3d(transform.getPosition()).add(0.0, FEET_DROP_LIFT, 0.0);
            return spawnInWorld(store, at, Rotation3f.IDENTITY, items);
        } catch (Throwable t) {
            warnLost(items, t);
            return false;
        }
    }

    /** The guarded spawn: true when the entities were added, false (warned) when anything threw. */
    private static boolean spawnGuarded(@Nonnull ComponentAccessor<EntityStore> generator,
                                        @Nonnull ComponentAccessor<EntityStore> adder,
                                        @Nonnull Vector3d position, @Nonnull Rotation3fc rotation,
                                        @Nonnull List<ItemStack> items) {
        if (items.isEmpty()) {
            return true;
        }
        try {
            spawnNow(generator, adder, position, rotation, items);
            return true;
        } catch (Throwable t) {
            warnLost(items, t);
            return false;
        }
    }

    /**
     * Build the drop entities through {@code generator} and add them through {@code adder}. The two
     * are the same handle outside a tick and a {@code Store}/{@code CommandBuffer} pair inside one.
     */
    private static void spawnNow(@Nonnull ComponentAccessor<EntityStore> generator,
                                 @Nonnull ComponentAccessor<EntityStore> adder,
                                 @Nonnull Vector3d position, @Nonnull Rotation3fc rotation,
                                 @Nonnull List<ItemStack> items) {
        Holder<EntityStore>[] drops = ItemComponent.generateItemDrops(generator, items, position, rotation);
        adder.addEntities(drops, AddReason.SPAWN);
    }

    /**
     * The safety net under the one-accessor form: a {@link Store}-routed spawn the store rejected
     * mid-tick is re-run on the owning world's thread, where it executes after the tick, outside the
     * processing lock. Only a {@code Store} can name its world; any other accessor, or a world that
     * cannot be resolved, keeps the refusal and warns that the items were lost.
     */
    private static boolean requeueOnWorldThread(@Nonnull ComponentAccessor<EntityStore> accessor,
                                                @Nonnull Vector3d position, @Nonnull Rotation3fc rotation,
                                                @Nonnull List<ItemStack> items,
                                                @Nonnull IllegalStateException cause) {
        Store<EntityStore> store = accessor instanceof Store<EntityStore> s ? s : null;
        World world = store == null ? null : worldOf(store);
        if (world == null) {
            warnLost(items, cause);
            return false;
        }
        // Defensive copies: the retry runs after the caller's tick, and the caller owns (and may
        // reuse) the vector, the rotation and the list it passed in.
        Vector3d at = new Vector3d(position);
        Rotation3f facing = new Rotation3f(rotation);
        List<ItemStack> stacks = List.copyOf(items);
        world.execute(() -> spawnGuarded(store, store, at, facing, stacks));
        return true;
    }

    /** The store's world via the shared {@link WorldEvictors} chain, or null when it cannot answer. */
    @Nullable
    private static World worldOf(@Nonnull Store<EntityStore> store) {
        try {
            return WorldEvictors.worldOf(store);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Test-only seam: substitute the live {@link ItemModule} roll with a fake so a unit test can exercise
     * {@link #rollNative}'s own contract (never-throws, empty-on-disabled) without a booted server.
     * Package-private; production code must never call this.
     */
    static void setEngineRollForTesting(@Nonnull Function<String, List<ItemStack>> roll) {
        engineRoll = roll;
    }

    /** Restore the real engine roll after a test that stubbed it. */
    static void resetEngineRollForTesting() {
        engineRoll = NativeLootService::liveRoll;
    }

    /** A refused spawn is real item loss, so the warn says so and names what was lost. */
    private static void warnLost(@Nonnull List<ItemStack> items, @Nonnull Throwable cause) {
        String named;
        try {
            named = summarize(items);
        } catch (Throwable unreadable) {
            named = items.size() + " item stacks";
        }
        warn("could not spawn " + named + " - these items are LOST: " + cause);
    }

    /** {@code "3x Coin_Gold, 1x Iron_Sword"}, capped at {@value #WARN_STACKS_NAMED} named stacks. */
    @Nonnull
    private static String summarize(@Nonnull List<ItemStack> items) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i == WARN_STACKS_NAMED) {
                out.append(" and ").append(items.size() - i).append(" more");
                break;
            }
            if (i > 0) {
                out.append(", ");
            }
            ItemStack stack = items.get(i);
            out.append(stack == null ? "?" : stack.getQuantity() + "x " + stack.getItemId());
        }
        return out.toString();
    }

    private static void warn(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atWarning().log("[ZiggfreedCommon] NativeLootService: " + message);
        } catch (Throwable ignored) {
            // a log-manager-less unit JVM must not crash on the logging facade itself
        }
    }
}
