package com.ziggfreed.common.effect.buff;

import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.util.SlotPlan;

/**
 * Who a timed buff belongs to, which picks the default tile it shows on when its source names no
 * effect of its own. Each scope's default is a pool of {@link #POOL_SIZE} effect ids shipped under
 * {@code Server/Entity/Effects/ZiggfreedCommon/}: the base id, then {@code _2} to {@code _4} naming
 * it as their {@code Parent}, so one buff is one tile (the engine shows one tile per effect id) and a
 * pack restyles the whole pool by shipping a same-id base file.
 */
public enum BuffScope {

    /** A buff one player has, such as a boost they used themselves. */
    PERSONAL("Zig_Buff_Personal"),

    /** A buff every player on the server has at once, such as a server-wide boost. */
    SERVER("Zig_Buff_Server");

    /** Slots in each default pool; a fifth buff of one scope shares the last ({@link SlotPlan}). */
    public static final int POOL_SIZE = 4;

    @Nonnull
    private final String defaultEffect;

    @Nonnull
    private final List<String> pool;

    BuffScope(@Nonnull String defaultEffect) {
        this.defaultEffect = defaultEffect;
        this.pool = SlotPlan.slotIds(defaultEffect, POOL_SIZE);
    }

    /** The base id of this scope's default pool, the one its other slots name as {@code Parent}. */
    @Nonnull
    public String defaultEffect() {
        return defaultEffect;
    }

    /** Every id in this scope's default pool, smallest slot first. */
    @Nonnull
    public List<String> pool() {
        return pool;
    }
}
