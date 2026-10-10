package com.ziggfreed.common.effect.buff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SlotPlan;

/**
 * What one refresh does to a player's buff tiles, decided without the engine: which effect each live
 * buff shows on and until when, which tiles come off, and the slot record saved for the next refresh.
 * {@link TimedBuffs} only carries it out.
 */
final class BuffPlan {

    /**
     * How far a tile's remaining time may stray from its buff's before it is re-applied. Re-applying
     * an active effect rewrites its time, so a tile within this of its buff is left alone and the
     * start of the effect never plays twice for one buff.
     */
    static final float DRIFT_SECONDS = 5f;

    /**
     * One refresh.
     *
     * @param wanted  each effect a live buff shows on, to the latest end among the buffs on it (two
     *                buffs share a tile only on a pool's last slot, or when both name one effect);
     *                ids are matched without regard to case
     * @param removes every effect this binding answers for that no live buff wants: each default pool
     *                id, and each id the last record named
     * @param record  each live buff's effect, per scope, by key, as the next refresh reads it
     */
    record Plan(@Nonnull Map<String, Long> wanted, @Nonnull Set<String> removes,
            @Nonnull Map<BuffScope, Map<String, String>> record) {
    }

    private BuffPlan() {
    }

    /**
     * Plan one refresh.
     *
     * @param buffs    what every source answered; a null, keyless, scopeless or ended buff is skipped,
     *                 and of two with one scope and key the later end is kept
     * @param previous the record the last refresh saved (per scope, key to effect id)
     * @param nowMs    the time to plan at, in epoch milliseconds
     */
    @Nonnull
    static Plan plan(@Nonnull Collection<TimedBuff> buffs, @Nonnull Map<BuffScope, Map<String, String>> previous,
            long nowMs) {
        Map<BuffScope, Map<String, TimedBuff>> live = new EnumMap<>(BuffScope.class);
        for (TimedBuff buff : buffs) {
            if (buff == null || buff.scope() == null || isBlank(buff.key()) || buff.endsAtMs() <= nowMs) {
                continue;
            }
            Map<String, TimedBuff> byKey = live.computeIfAbsent(buff.scope(), scope -> new TreeMap<>());
            TimedBuff seen = byKey.get(buff.key());
            if (seen == null || buff.endsAtMs() > seen.endsAtMs()) {
                byKey.put(buff.key(), buff);
            }
        }

        Map<String, Long> wanted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<BuffScope, Map<String, String>> record = new EnumMap<>(BuffScope.class);
        for (BuffScope scope : BuffScope.values()) {
            Map<String, TimedBuff> byKey = live.getOrDefault(scope, Map.of());
            List<String> pooled = new ArrayList<>();
            for (TimedBuff buff : byKey.values()) {
                if (isBlank(buff.effectId())) {
                    pooled.add(buff.key());
                }
            }
            Map<String, String> seats = SlotPlan.assign(pooled, previous.getOrDefault(scope, Map.of()), scope.pool());
            Map<String, String> scopeRecord = new LinkedHashMap<>();
            for (TimedBuff buff : byKey.values()) {
                String effect = isBlank(buff.effectId()) ? seats.get(buff.key()) : buff.effectId().trim();
                if (effect == null) {
                    continue;
                }
                scopeRecord.put(buff.key(), effect);
                wanted.merge(effect, buff.endsAtMs(), Math::max);
            }
            record.put(scope, Collections.unmodifiableMap(scopeRecord));
        }

        Set<String> removes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (BuffScope scope : BuffScope.values()) {
            removes.addAll(scope.pool());
        }
        for (Map<String, String> recorded : previous.values()) {
            if (recorded == null) {
                continue;
            }
            for (String effect : recorded.values()) {
                if (!isBlank(effect)) {
                    removes.add(effect.trim());
                }
            }
        }
        removes.removeIf(wanted::containsKey);
        return new Plan(Collections.unmodifiableMap(wanted), Collections.unmodifiableSet(removes),
                Collections.unmodifiableMap(record));
    }

    /** The seconds from {@code nowMs} to {@code endsAtMs}, never below zero. */
    static float secondsLeft(long endsAtMs, long nowMs) {
        return Math.max(0f, (endsAtMs - nowMs) / 1000f);
    }

    /**
     * Whether a tile must be (re-)applied: it is not on, or its time strays more than
     * {@link #DRIFT_SECONDS} from its buff's. An infinite tile never strays: re-applying could not
     * change it.
     *
     * @param tile   the tile's remaining seconds, null when it is not on or nobody can tell
     * @param target the buff's remaining seconds
     */
    static boolean drifted(@Nullable Float tile, float target) {
        if (tile == null) {
            return true;
        }
        if (tile.isInfinite()) {
            return false;
        }
        return Math.abs(tile - target) > DRIFT_SECONDS;
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.isBlank();
    }
}
