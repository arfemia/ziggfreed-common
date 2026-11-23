package com.ziggfreed.common.resource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.ValueType;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.util.SafeLog;

/**
 * Checks, drains and gives back a {@link ResourceCost} on a living entity. The one place a cost drawn from a stat is
 * judged, so every consumer reads a share, an empty pool and a fraction the same way.
 *
 * <p>{@link #check} answers {@link Verdict#OK}, {@link Verdict#SHORT} (with what the entity has and what the cost
 * needs, in points) or {@link Verdict#NO_POOL}: nothing could ever pay, because the entity carries no such stat, or
 * the stat holds nothing to spend (a {@code Max} of 0 or below; for a {@code Percent} cost, a span of 0, as the held
 * weapon's {@code SignatureEnergy} has with no bar weapon in hand). A free cost is always OK.
 *
 * <p>The math is the engine's own: {@code Absolute} compares the stat's value to the amount, as a
 * {@code StatsCondition} does; {@code Percent} takes the amount's share of the span ({@code Max} less {@code Min}) and
 * compares it to what sits above {@code Min}, as {@code StatsCondition} and {@code ChangeStat} do, so a cost of 100
 * Percent asks exactly what the game's own full-bar move asks. {@link #points} and {@link #judge} are that math, pure.
 *
 * <p>{@link #drain} takes the cost's points off the stat's current value and answers what it took;
 * {@link #restore} puts points back (a refund, a share of one), both clamped by the engine to the stat's bounds. Pass
 * a refund the points the drain took, not the cost again: a {@code Percent} cost's points move with the stat's size.
 * These move the stat's value, never a modifier (a value is what a cost spends), through
 * {@code EntityStatMap.addStatValue}, which syncs to the client. World thread, a store or a command buffer.
 *
 * <p>A cost naming a retired stat id draws on its one successor ({@code StatIndexCache.resolveCurrent}): a pool is
 * one stat, read and spent together, so an id retired onto several stats or at a scale names no pool.
 */
public final class ResourceCosts {

    /** What a {@link #check} answers. */
    public enum Verdict {
        /** The entity can pay (or the cost is free). */
        OK,
        /** The pool is there but holds too little now. */
        SHORT,
        /** Nothing could ever pay: no such stat on the entity, or nothing to spend in it. */
        NO_POOL
    }

    /**
     * A check's answer: the verdict, what the entity has toward the cost and what the cost needs, both in points of
     * the stat ({@code have} is the value for an {@code Absolute} cost, what sits above {@code Min} for a
     * {@code Percent} one; both 0 for a free cost or a missing stat).
     */
    public record Check(@Nonnull Verdict verdict, double have, double need) {

        /** The answer for a cost of nothing. */
        public static final Check FREE = new Check(Verdict.OK, 0.0, 0.0);

        /** Can the entity pay? */
        public boolean ok() {
            return verdict == Verdict.OK;
        }
    }

    /**
     * What a pool holds now, in points of the stat: its live value and its bounds as the entity carries them (its
     * modifiers on the maximum included), the reading a bar of the pool draws.
     */
    public record Level(double value, double min, double max) {

        /** How full the pool is, 0 to 1: the value's place between {@code min} and {@code max}; 0 with no span. */
        public double fraction() {
            double span = max - min;
            if (!(span > 0.0) || !Double.isFinite(span) || !Double.isFinite(value)) {
                return 0.0;
            }
            return Math.max(0.0, Math.min(1.0, (value - min) / span));
        }

        /** Is the pool at its maximum (or holds nothing to fill: no span)? */
        public boolean full() {
            return !(max - min > 0.0) || value >= max - 1e-4;
        }
    }

    private ResourceCosts() {
    }

    /**
     * What {@code ref}'s pool {@code stat} holds now ({@link Level}), read as a cost reads it (a retired id's one
     * successor); null for an unknown stat (named once in the log) or an entity carrying none. Never throws.
     */
    @Nullable
    public static Level level(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nullable String stat) {
        EntityStatValue value = valueOf(accessor, ref, stat);
        return value == null ? null : new Level(value.get(), value.getMin(), value.getMax());
    }

    /**
     * The points a cost of {@code amount} in {@code valueType} takes from a stat spanning {@code min} to {@code max}:
     * the amount itself when {@code Absolute}, its share of the span when {@code Percent}. 0 for an amount that is
     * not above 0 or not a number. Pure.
     */
    public static double points(double amount, @Nonnull ValueType valueType, double min, double max) {
        if (!(amount > 0.0) || !Double.isFinite(amount)) {
            return 0.0;
        }
        if (valueType != ValueType.Percent) {
            return amount;
        }
        double span = max - min;
        return span > 0.0 && Double.isFinite(span) ? amount * span / 100.0 : 0.0;
    }

    /**
     * The verdict on a cost of {@code amount} in {@code valueType} against a stat at {@code value} spanning {@code min}
     * to {@code max} ({@link #check}'s math). Pure.
     */
    @Nonnull
    public static Check judge(double amount, @Nonnull ValueType valueType, double value, double min, double max) {
        if (!(amount > 0.0) || !Double.isFinite(amount)) {
            return Check.FREE;
        }
        boolean percent = valueType == ValueType.Percent;
        double have = percent ? value - min : value;
        double need = points(amount, valueType, min, max);
        boolean empty = percent ? !(max - min > 0.0) : !(max > 0.0);
        if (empty) {
            return new Check(Verdict.NO_POOL, have, need);
        }
        return new Check(have >= need ? Verdict.OK : Verdict.SHORT, have, need);
    }

    /**
     * Can {@code ref} pay {@code cost} now ({@link #judge} on the stat's live value and bounds)? An unknown stat id
     * or an entity with no stat map is {@link Verdict#NO_POOL}, the id named once in the log. Never throws.
     */
    @Nonnull
    public static Check check(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull ResourceCost cost) {
        if (cost.isFree()) {
            return Check.FREE;
        }
        EntityStatValue value = valueOf(accessor, ref, cost.stat());
        if (value == null) {
            return new Check(Verdict.NO_POOL, 0.0, cost.amount());
        }
        return judge(cost.amount(), cost.valueType(), value.get(), value.getMin(), value.getMax());
    }

    /**
     * Take {@code cost}'s points off {@code ref}'s stat and answer what it took, in points (the engine clamps at
     * {@code Min}, so it may take less than asked; check first). Nothing is taken from a free cost or a pool that is
     * not there ({@link Verdict#NO_POOL}). Never throws.
     */
    public static double drain(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull ResourceCost cost) {
        if (cost.isFree()) {
            return 0.0;
        }
        String stat = cost.stat();
        try {
            EntityStatMap stats = statsOf(accessor, ref, stat);
            int index = StatIndexCache.resolveCurrent(stat);
            EntityStatValue value = stats == null ? null : stats.get(index);
            if (value == null) {
                return 0.0;
            }
            Check check = judge(cost.amount(), cost.valueType(), value.get(), value.getMin(), value.getMax());
            if (check.verdict() == Verdict.NO_POOL || !(check.need() > 0.0)) {
                return 0.0;
            }
            float before = value.get();
            stats.addStatValue(index, (float) -check.need());
            EntityStatValue after = stats.get(index);
            return after == null ? 0.0 : Math.max(0.0, before - after.get());
        } catch (Throwable t) {
            SafeLog.warn("[resource] drain of " + stat + " failed: " + t.getMessage());
            return 0.0;
        }
    }

    /**
     * Put {@code points} back on {@code ref}'s stat {@code stat} (a refund: what {@link #drain} took, or a share of
     * it) and answer what it gave, in points (the engine clamps at {@code Max}). Nothing for points not above 0, a
     * blank stat or a pool that is not there. Never throws.
     */
    public static double restore(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nullable String stat, double points) {
        if (!(points > 0.0) || !Double.isFinite(points) || stat == null || stat.isBlank()) {
            return 0.0;
        }
        String id = stat.trim();
        try {
            EntityStatMap stats = statsOf(accessor, ref, id);
            int index = StatIndexCache.resolveCurrent(id);
            EntityStatValue value = stats == null ? null : stats.get(index);
            if (value == null) {
                return 0.0;
            }
            float before = value.get();
            stats.addStatValue(index, (float) points);
            EntityStatValue after = stats.get(index);
            return after == null ? 0.0 : Math.max(0.0, after.get() - before);
        } catch (Throwable t) {
            SafeLog.warn("[resource] restore of " + id + " failed: " + t.getMessage());
            return 0.0;
        }
    }

    /** {@code ref}'s live value of {@code stat}, or null when the stat is unknown or the entity carries none. */
    @Nullable
    private static EntityStatValue valueOf(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref, @Nullable String stat) {
        try {
            EntityStatMap stats = statsOf(accessor, ref, stat);
            return stats == null ? null : stats.get(StatIndexCache.resolveCurrent(stat));
        } catch (Throwable t) {
            SafeLog.warn("[resource] reading " + stat + " failed: " + t.getMessage());
            return null;
        }
    }

    /** {@code ref}'s stat map when {@code stat} resolves and the entity is live, else null. */
    @Nullable
    private static EntityStatMap statsOf(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref, @Nullable String stat) {
        if (stat == null || !ref.isValid()) {
            return null;
        }
        if (StatIndexCache.resolveCurrent(stat) == StatIndexCache.UNRESOLVED) {
            StatIndexCache.warnUnknownOnce(stat);
            return null;
        }
        return accessor.getComponent(ref, EntityStatMap.getComponentType());
    }
}
