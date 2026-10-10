package com.ziggfreed.common.playerstats.encounter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BinaryOperator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.leaderboard.EncounterLeaderboardListener;
import com.ziggfreed.common.instance.leaderboard.Leaderboard;
import com.ziggfreed.common.instance.leaderboard.LeaderboardEntry;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoard;
import com.ziggfreed.common.instance.leaderboard.statboard.StatSectionConfig;
import com.ziggfreed.common.playerstats.PlayerStats;
import com.ziggfreed.common.playerstats.PlayerStatsComponent;
import com.ziggfreed.common.playerstats.StatKeys;
import com.ziggfreed.common.playerstats.ZcStats;
import com.ziggfreed.common.util.SafeLog;

/**
 * The old encounter board's rows, carried once into the stat store: each row ({@code <fight>} bucket, one
 * player) becomes that fight's line of the four encounter stats, its plays as {@code Encounters_Won}, its
 * best time as {@code Encounter_Best_Time}, its best score (the share in whole percent) as
 * {@code Encounter_Best_Share} and its {@code damage_dealt} tally as {@code Encounter_Damage_Dealt}.
 *
 * <p><b>Twice, each once.</b> Into the stat board's {@code all_time} at boot, claimed in the board's
 * {@code imports}, so every player's old rows rank at once, offline players included; and into each
 * player's own record when they are next ready, claimed in the record, so the record stays the truth the
 * board re-syncs from (a later win adds to the imported line rather than starting it over). Both write
 * absolute values a second run would leave as they were, the claim id is {@value #CLAIM} in each, and the
 * old file stays where it is, for an older build rolled back to.
 */
public final class EncounterBoardImport {

    /** The claim both imports take: in the stat board's {@code imports}, and in a player's record. */
    public static final String CLAIM = "ziggfreedcommon:encounter-board@1";

    /** One player's old rows: the name the board last saw, and their encounter stat lines. */
    record Rows(@Nullable String name, @Nonnull Map<String, Long> stats) {
    }

    /** The old board the cached rows were read from, and those rows: the board never changes once frozen. */
    private record Read(@Nonnull Leaderboard board, @Nonnull Map<UUID, Rows> rows) {
    }

    @Nullable private static volatile Read read;

    private EncounterBoardImport() {
    }

    /** The boot import into the stat board's {@code all_time}. Never throws. */
    public static void intoBoard() {
        try {
            if (intoBoard(EncounterLeaderboardListener.board())) {
                SafeLog.info("[stats] the encounter board's rows are ranked on the stat board (" + CLAIM + ")");
            }
        } catch (Throwable t) {
            SafeLog.warn("[stats] the encounter board's rows could not be ranked; the next boot tries again", t);
        }
    }

    /**
     * Rank {@code old}'s rows on the stat board's {@code all_time} once: true only for the call that did. It
     * waits, claiming nothing, while no section ranks the encounter stats yet (their files not loaded, or
     * the board off), so it never claims an import that wrote nothing.
     */
    static boolean intoBoard(@Nullable Leaderboard old) {
        if (old == null || StatBoard.imported(CLAIM)) {
            return false;
        }
        Map<UUID, Rows> rows = rowsOf(old);
        if (rows.isEmpty() || !ranksAny(rows)) {
            return false;
        }
        return StatBoard.importOnce(CLAIM, () -> {
            for (Map.Entry<UUID, Rows> row : rows.entrySet()) {
                StatBoard.setAllTime(row.getKey(), row.getValue().name(), row.getValue().stats());
            }
        });
    }

    /**
     * Fold the player's own old rows into their record once, at their ready pass, on their world thread:
     * true when this call folded them (and so re-ranked the record). Nothing for a player the old board
     * never saw, and nothing claimed for them either, since the frozen board will never hold them.
     */
    public static boolean intoRecord(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        Leaderboard old = EncounterLeaderboardListener.board();
        PlayerStatsComponent record = old == null ? null : PlayerStatsComponent.peek(store, ref);
        if (record == null || record.hasImported(CLAIM)) {
            return false;
        }
        PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
        Rows rows = player == null ? null : rowsOf(old).get(player.getUuid());
        return rows != null && PlayerStats.importOnce(store, ref, CLAIM, rows.stats());
    }

    /** Every player's old rows on {@code old}, read once per board. */
    @Nonnull
    static Map<UUID, Rows> rowsOf(@Nonnull Leaderboard old) {
        Read held = read;
        if (held != null && held.board() == old) {
            return held.rows();
        }
        Map<UUID, Rows> rows = map(old);
        read = new Read(old, rows);
        return rows;
    }

    /**
     * The mapping itself: a row of {@code old}'s per (fight, player), as that fight's four stat lines, and
     * each stat's total over the player's fights as a win counts it (wins and damage summed, the fastest
     * time, the best share). The board ranks the lines alone; the record keeps both.
     */
    @Nonnull
    private static Map<UUID, Rows> map(@Nonnull Leaderboard old) {
        Map<UUID, Map<String, Long>> stats = new LinkedHashMap<>();
        Map<UUID, String> names = new HashMap<>();
        for (String fight : old.bucketKeys()) {
            if (!StatKeys.usableThing(fight)) {
                continue;
            }
            for (Map.Entry<UUID, LeaderboardEntry> row : old.forBucket(fight).entrySet()) {
                LeaderboardEntry entry = row.getValue();
                long damage = entry.stat(EncounterLeaderboardListener.STAT_DAMAGE_DEALT);
                Map<String, Long> lines = stats.computeIfAbsent(row.getKey(), k -> new LinkedHashMap<>());
                put(lines, ZcStats.ENCOUNTERS_WON, fight, entry.plays);
                put(lines, ZcStats.ENCOUNTER_BEST_TIME, fight, entry.bestTimeSeconds);
                put(lines, ZcStats.ENCOUNTER_BEST_SHARE, fight, entry.bestScore);
                put(lines, ZcStats.ENCOUNTER_DAMAGE_DEALT, fight, damage);
                total(lines, ZcStats.ENCOUNTERS_WON, entry.plays, Long::sum);
                total(lines, ZcStats.ENCOUNTER_BEST_TIME, entry.bestTimeSeconds, Math::min);
                total(lines, ZcStats.ENCOUNTER_BEST_SHARE, entry.bestScore, Math::max);
                total(lines, ZcStats.ENCOUNTER_DAMAGE_DEALT, damage, Long::sum);
                if (entry.name != null && !entry.name.isBlank()) {
                    names.put(row.getKey(), entry.name);
                }
            }
        }
        Map<UUID, Rows> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<String, Long>> player : stats.entrySet()) {
            if (!player.getValue().isEmpty()) {
                out.put(player.getKey(), new Rows(names.get(player.getKey()), Map.copyOf(player.getValue())));
            }
        }
        return Map.copyOf(out);
    }

    /** One fight's line of {@code statId}, when the old row held a value for it. */
    private static void put(@Nonnull Map<String, Long> lines, @Nonnull String statId, @Nonnull String fight, long value) {
        if (value > 0L) {
            lines.put(StatKeys.line(statId, fight), value);
        }
    }

    /** {@code statId}'s total folded with one more fight's {@code value}, the stat's own way, when it holds one. */
    private static void total(@Nonnull Map<String, Long> lines, @Nonnull String statId, long value,
            @Nonnull BinaryOperator<Long> fold) {
        if (value > 0L) {
            lines.merge(StatKeys.total(statId), value, fold);
        }
    }

    /** Whether some section ranks a line of these rows now, so writing them would show. */
    private static boolean ranksAny(@Nonnull Map<UUID, Rows> rows) {
        for (Rows row : rows.values()) {
            if (!StatSectionConfig.getInstance().ranked(row.stats()).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
