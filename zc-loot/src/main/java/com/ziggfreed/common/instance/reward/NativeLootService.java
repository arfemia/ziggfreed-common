package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Rotation3fc;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.CommonLog;

import org.joml.Vector3d;

/**
 * The XP-AGNOSTIC engine-touching half of the loot primitive: rolls a native Hytale {@code ItemDropList}
 * for its item stacks and spawns them on the ground, so a {@link LootTable} can delegate item SELECTION to
 * a native asset instead of the {@code item <id> <qty>} {@link LootEntry} DSL. Common ships this as the one
 * seam any consumer (a minigame results-claim, an MMO's bonus mob loot, a luck-loot ground drop) rolls a
 * native drop table through, so the native-roll + in-world-spawn idiom is written once, not re-derived per
 * mod. Mirrors the sibling {@code mmo-mob-scaling} {@code MobScalingLootDropSystem} pattern (native roll via
 * {@code ItemModule.getRandomItemDrops} + spawn via {@code ItemComponent.generateItemDrops}); knows nothing
 * about XP, skills, or any other MMO concept.
 *
 * <p><b>Non-throwing.</b> Both primitives are whole-body try-guarded: a disabled {@link ItemModule}, an
 * unregistered drop-list id (warned once per distinct id, mirroring {@code MobScalingLootDropSystem}'s
 * {@code WARNED_IDS} convention), or any engine throw degrades to an empty roll / a no-op spawn, never an
 * exception into the caller's tick.
 */
public final class NativeLootService {

    /** Warn-once-per-distinct-id set for a {@code dropListId} no {@link ItemDropList} asset claims. */
    private static final Set<String> WARNED_IDS = ConcurrentHashMap.newKeySet();

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
     * Spawn {@code items} on the ground at {@code position}/{@code rotation} (a no-op for an empty list),
     * via the native {@code ItemComponent.generateItemDrops} + {@code CommandBuffer.addEntities} idiom (the
     * exact shape {@code MobScalingLootDropSystem} uses for its bonus mob loot). World-thread only (touches
     * the {@link Store}); whole-body try-guarded so a loot throw never breaks the caller's tick.
     */
    public static void spawnInWorld(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull Vector3d position, @Nonnull Rotation3f rotation,
                                    @Nonnull List<ItemStack> items) {
        spawnAt(store, commandBuffer, position, rotation, items);
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
     * <p>The rotation is the read-only {@code Rotation3fc} the engine's own drop call takes, so a
     * caller with nothing to aim by can pass {@code Rotation3f.IDENTITY} without building a rotation
     * of its own.
     */
    public static void spawnInWorld(@Nonnull ComponentAccessor<EntityStore> accessor,
                                    @Nonnull Vector3d position, @Nonnull Rotation3fc rotation,
                                    @Nonnull List<ItemStack> items) {
        spawnAt(accessor, accessor, position, rotation, items);
    }

    /**
     * Build the drop entities through {@code generator} and add them through {@code adder}. The two
     * are the same handle outside a tick and a {@code Store}/{@code CommandBuffer} pair inside one.
     */
    private static void spawnAt(@Nonnull ComponentAccessor<EntityStore> generator,
                                @Nonnull ComponentAccessor<EntityStore> adder,
                                @Nonnull Vector3d position, @Nonnull Rotation3fc rotation,
                                @Nonnull List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        try {
            Holder<EntityStore>[] drops = ItemComponent.generateItemDrops(generator, items, position, rotation);
            adder.addEntities(drops, AddReason.SPAWN);
        } catch (Throwable t) {
            warn("spawnInWorld failed: " + t.getMessage());
        }
    }

    /**
     * The native-items-as-rewards step, swappable ONLY by same-package tests. Kept as its OWN seam
     * (distinct from {@link #engineRoll}) because a real {@link ItemStack} cannot be constructed at all in
     * a bare unit-test JVM here (even {@code ItemStack.CODEC.decode} forces {@code Item}'s codec to
     * initialize, which forces a validator class whose static field requires the Hytale log manager to be
     * installed before anything else touches {@code java.util.logging} - already lost to the test runner's
     * own bootstrap by the time any test code runs). Stubbing at this layer lets a test assert the MERGE
     * behavior ({@link #rollTable}'s union with the table's own entries) without ever touching {@link ItemStack}.
     */
    @Nonnull
    private static Function<String, List<InstanceReward>> nativeRewards = NativeLootService::liveNativeRewards;

    @Nonnull
    private static List<InstanceReward> liveNativeRewards(@Nonnull String dropListId) {
        List<InstanceReward> out = new ArrayList<>();
        for (ItemStack stack : rollNative(dropListId)) {
            out.add(InstanceReward.item(stack.getItemId(), stack.getQuantity(), null));
        }
        return out;
    }

    /**
     * Roll {@code table} the same way {@link LootTable#roll(int, boolean, Random)} always has (unchanged
     * behavior for the command/currency/gated {@code Guaranteed}/{@code Pool} entries), then, when the table
     * names a {@link LootTable#nativeDropList()}, roll it via {@link #rollNative} and append one
     * {@link InstanceReward#item(String, int, String)} entry per resolved {@link ItemStack} on top. A
     * {@code null}/blank {@code nativeDropList} is a pure pass-through to {@link LootTable#roll} (byte-for-
     * byte the pre-native behavior). This is the drop-in replacement for a consumer's
     * {@code table.roll(score, win, rng)} call at its reward choke-point.
     */
    @Nonnull
    public static List<InstanceReward> rollTable(@Nonnull LootTable table, int score, boolean win, @Nonnull Random rng) {
        List<InstanceReward> out = new ArrayList<>(table.roll(score, win, rng));
        String dropListId = table.nativeDropList();
        if (dropListId != null && !dropListId.isBlank()) {
            out.addAll(nativeRewards.apply(dropListId));
        }
        return out;
    }

    /**
     * Test-only seam: substitute the live {@link ItemModule} roll with a fake so a unit test can exercise
     * {@link #rollNative}'s own contract (never-throws, empty-on-disabled) without a booted server.
     * Package-private; production code must never call this.
     */
    static void setEngineRollForTesting(@Nonnull Function<String, List<ItemStack>> roll) {
        engineRoll = roll;
    }

    /**
     * Test-only seam: substitute {@link #rollTable}'s native-items-as-rewards step with a fake so a unit
     * test can exercise the merge/union without constructing a real {@link ItemStack} (see
     * {@link #nativeRewards}'s javadoc for why that is not possible here). Package-private; production
     * code must never call this.
     */
    static void setNativeRewardsForTesting(@Nonnull Function<String, List<InstanceReward>> fn) {
        nativeRewards = fn;
    }

    /** Restore the real engine roll + native-rewards step after a test that stubbed either. */
    static void resetEngineRollForTesting() {
        engineRoll = NativeLootService::liveRoll;
        nativeRewards = NativeLootService::liveNativeRewards;
    }

    private static void warn(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atWarning().log("[ZiggfreedCommon] NativeLootService: " + message);
        } catch (Throwable ignored) {
            // a log-manager-less unit JVM must not crash on the logging facade itself
        }
    }
}
