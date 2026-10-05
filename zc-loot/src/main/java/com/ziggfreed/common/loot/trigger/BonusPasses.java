package com.ziggfreed.common.loot.trigger;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;
import org.joml.Vector3i;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.instance.reward.NativeLootService;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.GroundSpillSinks;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.reward.MomentItems;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.subject.Subject;

/**
 * A bonus row's pass, once its chance has fired: where each moment's loot lands and the one roll and
 * hand-over every table runs through.
 *
 * <p>The landings are fixed per moment. A broken block's bonus drops just above the block's centre,
 * and every stack it spills counts as found ({@link #atBlock}). A kill's drops at the corpse, as the
 * engine drops a dying entity's own loot, counting only what landed ({@link #atCorpse}). A harvest's
 * goes where the player's own pickup settings route it, partial stacks filled, and only the rest
 * lands as one pile at their feet ({@link #atHarvester}).
 *
 * <p>A harvest's pass runs LATER than the pickup that earned it: the engine fires its pickup event
 * before it gives the harvested stack, so the pass waits for one world task ({@link #afterHarvest})
 * and re-reads the event there ({@link #harvestOf}). Nothing here ever sets the event's stack or
 * fires the pickup again: the library's placed-item ledger keys the moment by the stack's identity,
 * and the engine's give would re-enter the producer.
 */
public final class BonusPasses {

    /** A broken block's spill counts as found whatever the ground answered: the released lucky-break rule. */
    static final boolean BLOCK_COUNTS_EVERY_SPILL = true;

    private BonusPasses() {
    }

    /** What one pass did: the engine's own tally, and how many items landed in all. */
    public record Outcome(@Nonnull LootEngine.Result result, int landed) {
    }

    // ==================== the landings ====================

    /** Just above a broken block's centre, so the bonus sits on top of where the block was. */
    @Nonnull
    public static Vector3d abovePosition(@Nonnull Vector3i block) {
        return new Vector3d(block.x + 0.5, block.y + 1.0, block.z + 0.5);
    }

    /** The broken-block landing over any ground: every spilled stack counts as found. */
    @Nonnull
    public static GroundSpillSinks.Builder blockLanding(@Nonnull GroundSpillSinks.Ground ground) {
        return GroundSpillSinks.at(ground).countFailedDrops(BLOCK_COUNTS_EVERY_SPILL);
    }

    /** A broken block's landing, dropping through the tick's buffer just above the block. */
    @Nonnull
    public static GroundSpillSinks.Builder atBlock(@Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> buffer, @Nonnull Vector3i block) {
        Vector3d position = abovePosition(block);
        return blockLanding(stacks -> NativeLootService.spawnInWorld(store, buffer, position, new Rotation3f(),
                new ArrayList<>(stacks)));
    }

    /** A kill's landing: the corpse ground, a pile counted only once it landed. */
    @Nonnull
    public static GroundSpillSinks.Builder atCorpse(@Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> buffer, @Nonnull Ref<EntityStore> victim) {
        return GroundSpillSinks.atEntity(store, buffer, victim);
    }

    /** A harvest's landing: the player's own pickup routing, the rest one pile at their feet. */
    @Nonnull
    public static GroundSpillSinks.Builder atHarvester(@Nonnull Ref<EntityStore> ref, @Nullable Player player) {
        return GroundSpillSinks.at(stacks -> NativeLootService.spawnAtFeet(ref, stacks)).pickupRoutedFirst(player);
    }

    // ==================== the pass ====================

    /**
     * Roll and hand over one row's loot through {@code preset}. Handed the harvested stack
     * ({@code moment}), the pass layers one {@link MomentItems} around it on the subject, so a
     * {@code Moment_Item} reward at any depth adds to its tally, and the copies it adds up to hand
     * over through the same preset after the rolls. Handed none, no collector rides the pass and a
     * {@code Moment_Item} reward counts lost.
     *
     * @param sample the pass's one {@code [0,1)} source: the rolls' draws and the copies' fraction
     * @return the engine's result (its cues still to present) and every item that landed
     */
    @Nonnull
    public static Outcome run(@Nonnull GroundSpillSinks.Builder preset, @Nonnull LootEngine.Resolved loot,
            @Nonnull FactorLookup lookup, @Nonnull DoubleSupplier sample, @Nullable ItemStack moment,
            @Nonnull Subject subject, @Nullable CommandRunner.Dispatcher commands,
            @Nullable Map<String, String> placeholders, @Nonnull String sourceId,
            @Nonnull Consumer<String> warn) {
        GroundSpillSinks handOver = preset.warn(warn).build();
        MomentItems copies = moment == null ? null : new MomentItems(moment);
        LootEngine.Sinks sinks = handOver.into(LootEngine.Sinks.builder())
                .commands(commands, placeholders)
                .rewards(RewardKinds.shared(), copies == null ? subject : subject.withFacets(copies))
                .sourceId(sourceId)
                .warn(warn)
                .build();
        LootEngine.Result result = LootEngine.rollAndGrant(loot.rolls(), loot.pools(), null, lookup, sample, sinks);
        int landed = landedCount(result.getItems());
        if (copies != null) {
            landed += landedCount(handOver.spill(copies.copies(sample)));
        }
        return new Outcome(result, landed);
    }

    // ==================== the harvest deferral ====================

    /**
     * The stack a harvest pass copies, read when the deferred pass runs: nothing for a cancelled
     * pickup or a missing or empty stack, otherwise the event's FINAL stack, the one the engine
     * re-reads before it gives.
     */
    @Nullable
    public static ItemStack harvestOf(@Nonnull InteractivelyPickupItemEvent event) {
        if (event.isCancelled()) {
            return null;
        }
        ItemStack stack = event.getItemStack();
        if (stack == null || stack.getItemId() == null || stack.isEmpty() || stack.getQuantity() <= 0) {
            return null;
        }
        return stack;
    }

    /**
     * Queue {@code task} onto {@code world}, which runs it after the store tick and so after the
     * engine has given the harvest. A world that refuses new tasks (it is shutting down) runs
     * nothing: no bonus, nothing else lost.
     *
     * @return true when the task was queued
     */
    public static boolean afterHarvest(@Nonnull Executor world, @Nonnull Runnable task) {
        try {
            world.execute(task);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The same, onto the world {@code store} belongs to. */
    public static boolean afterHarvest(@Nonnull Store<EntityStore> store, @Nonnull Runnable task) {
        try {
            return afterHarvest(store.getExternalData().getWorld(), task);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** How many items a hand-over's landed map ({@code itemId -> quantity}) adds up to. */
    public static int landedCount(@Nonnull Map<String, Integer> landed) {
        int total = 0;
        for (Integer quantity : landed.values()) {
            total += quantity == null ? 0 : quantity;
        }
        return total;
    }
}
