package com.ziggfreed.common.playerstats;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The player stat store's one way in: how a mod counts a stat for a player, and how it reads one back.
 * The library's own producers count through here, and a consumer counts what it adds over them through
 * here too, never into a record of its own.
 *
 * <p><b>Three ways to move a counted stat, and the stat's kind picks one.</b> {@link #add} accumulates
 * (a Sum: kills, blocks, seconds played); {@link #raise} keeps a best that grows (a Max); {@link #lower}
 * keeps a best that shrinks (a Min, such as a fastest time). Each writes the grand total
 * ({@code <stat>}) and, when a {@code thing} is named, that line of the breakdown
 * ({@code <stat>/<thing>}), both keyed by {@link StatKeys}. A {@link #gauge} is a number the consumer
 * owns (a skill level): it is ranked, never stored in the player's record.
 *
 * <p>Writes are called on the world thread that owns {@code store}, the way every producer runs. A
 * player with no record (the component never registered, or an entity that is not a player) counts
 * nothing; a stat id the key format cannot hold ({@link StatKeys#usableStatId}) counts nothing and is
 * reported once.
 */
public final class PlayerStats {

    private static final Set<String> WARNED_IDS = ConcurrentHashMap.newKeySet();

    private PlayerStats() {
    }

    /**
     * Add {@code amount} to {@code statId} for the player at {@code ref}: to its grand total and, when
     * {@code thing} is named, to that line of its breakdown. An amount of zero or less counts nothing,
     * since a tally never goes down.
     */
    public static void add(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId, @Nullable String thing, long amount) {
        if (amount <= 0L || !usable(statId)) {
            return;
        }
        PlayerStatsComponent record = PlayerStatsComponent.peek(store, ref);
        if (record == null) {
            return;
        }
        record.add(StatKeys.total(statId), amount);
        if (StatKeys.usableThing(thing)) {
            record.add(StatKeys.line(statId, thing), amount);
        }
    }

    /**
     * Raise {@code statId}'s best (a Max stat) to {@code value} when that beats it: the grand total holds
     * the best over every thing, and the named {@code thing}'s line its own best.
     */
    public static void raise(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId, @Nullable String thing, long value) {
        if (value <= 0L || !usable(statId)) {
            return;
        }
        PlayerStatsComponent record = PlayerStatsComponent.peek(store, ref);
        if (record == null) {
            return;
        }
        record.raise(StatKeys.total(statId), value);
        if (StatKeys.usableThing(thing)) {
            record.raise(StatKeys.line(statId, thing), value);
        }
    }

    /**
     * Lower {@code statId}'s best (a Min stat, such as a fastest time) to {@code value} when that beats
     * it, or set it when none is recorded: the grand total holds the best over every thing, and the
     * named {@code thing}'s line its own best. A value of zero or less is never a best.
     */
    public static void lower(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId, @Nullable String thing, long value) {
        if (value <= 0L || !usable(statId)) {
            return;
        }
        PlayerStatsComponent record = PlayerStatsComponent.peek(store, ref);
        if (record == null) {
            return;
        }
        record.lower(StatKeys.total(statId), value);
        if (StatKeys.usableThing(thing)) {
            record.lower(StatKeys.line(statId, thing), value);
        }
    }

    /**
     * Rank a number the consumer owns (a Gauge stat, such as a skill level) for the player
     * {@code uuid}, online or not: it sets {@code statId} (and the {@code thing} line, when named) on
     * the stat board's all-time ranking and is never stored in the player's record. {@code name} is
     * the name the board shows, or null to keep the one it holds. Callable from any thread.
     */
    public static void gauge(@Nonnull UUID uuid, @Nullable String name, @Nonnull String statId,
            @Nullable String thing, long value) {
        if (!usable(statId)) {
            return;
        }
        // The stat board lands with the rest of the store; until then a gauge has no ranking to set.
    }

    /**
     * Fold {@code lifetime} into the player's record once, claimed as {@code claimId} (for example
     * {@code mymod:statistics@1}): every tally is added to the lifetime bag under its own key, and the
     * claim is kept in the record, so a second call, a relog or a restart changes nothing. The merge is
     * built aside and swapped in whole, so a throw before the swap changes nothing and claims nothing.
     *
     * @return true when this call folded the import; false when it was already claimed, or the player
     *         has no record to fold it into
     * @throws IllegalArgumentException when {@code claimId} is blank or carries {@code ,} or {@code |}
     */
    public static boolean importOnce(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String claimId, @Nullable Map<String, Long> lifetime) {
        PlayerStatsComponent record = PlayerStatsComponent.peek(store, ref);
        return record != null && record.importOnce(claimId, lifetime);
    }

    /**
     * A frozen copy of the player's record, to read and never write: {@link StatsView#EMPTY} when they
     * have none. Read on the world thread that owns {@code store}.
     */
    @Nonnull
    public static StatsView view(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        PlayerStatsComponent record = PlayerStatsComponent.peek(store, ref);
        return record == null ? StatsView.EMPTY : record.view();
    }

    /** Is {@code statId} a key the store can hold? Reported once per id when it is not. */
    private static boolean usable(@Nullable String statId) {
        if (StatKeys.usableStatId(statId)) {
            return true;
        }
        if (WARNED_IDS.add(String.valueOf(statId))) {
            SafeLog.warn("[stats] the stat id '" + statId + "' cannot be counted: it is blank or carries"
                    + " / @ | or :");
        }
        return false;
    }
}
