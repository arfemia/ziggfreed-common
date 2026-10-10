package com.ziggfreed.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Seats keys in a pool of interchangeable slot ids, so each key keeps one slot from plan to plan.
 *
 * <p>The shape behind a row of native tiles: the engine shows one tile per effect id, so N things
 * that share one default look need N ids (a base id, then {@code base_2} to {@code base_N}, each
 * naming the base as its {@code Parent}), and a thing must stay on the same id while it lasts, or its
 * tile jumps and starts over. The rules, fixed once for every pool:
 *
 * <ul>
 *   <li>a key keeps the slot it held last time, unless an earlier key (in the order given) already
 *       kept that slot;</li>
 *   <li>every other key takes the smallest free slot, in the order given;</li>
 *   <li>when no slot is free, the key shares the LAST slot, so a pool of N shows N tiles however many
 *       keys there are.</li>
 * </ul>
 *
 * <p>A key that shared the last slot moves to a smaller slot as soon as one frees up: two keys on one
 * slot both claim it, the earlier keeps it, and the later takes the free one. Pure: no engine, no
 * state. A slot id is matched without regard to case, the way the engine's asset maps match an id.
 */
public final class SlotPlan {

    private SlotPlan() {
    }

    /**
     * The ids of a pool of {@code size} slots: {@code base} first, then {@code base_2} to
     * {@code base_<size>}. Empty when {@code size} is below 1.
     */
    @Nonnull
    public static List<String> slotIds(@Nonnull String base, int size) {
        List<String> out = new ArrayList<>(Math.max(0, size));
        for (int i = 0; i < size; i++) {
            out.add(i == 0 ? base : base + "_" + (i + 1));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Seat every key in the pool.
     *
     * @param keys     the keys to seat, in the order that settles a tie (an earlier key keeps a
     *                 contested slot and takes a free one first); a null, blank or repeated key is
     *                 skipped
     * @param previous each key's slot id from the last plan; an id outside {@code slots} is ignored
     * @param slots    the pool's ids, smallest first ({@link #slotIds})
     * @return each key's slot id, in the keys' order; empty when the pool has no slot
     */
    @Nonnull
    public static Map<String, String> assign(@Nonnull List<String> keys, @Nonnull Map<String, String> previous,
            @Nonnull List<String> slots) {
        Map<String, String> out = new LinkedHashMap<>();
        int size = slots.size();
        if (size == 0) {
            return out;
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                ordered.add(key);
            }
        }
        boolean[] taken = new boolean[size];
        Map<String, Integer> seat = new LinkedHashMap<>();
        for (String key : ordered) {
            int held = indexOf(slots, previous.get(key));
            if (held >= 0 && !taken[held]) {
                taken[held] = true;
                seat.put(key, held);
            }
        }
        for (String key : ordered) {
            if (seat.containsKey(key)) {
                continue;
            }
            int free = firstFree(taken);
            if (free < 0) {
                seat.put(key, size - 1);
                continue;
            }
            taken[free] = true;
            seat.put(key, free);
        }
        for (String key : ordered) {
            out.put(key, slots.get(seat.get(key)));
        }
        return out;
    }

    private static int indexOf(@Nonnull List<String> slots, @Nullable String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < slots.size(); i++) {
            if (id.equalsIgnoreCase(slots.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int firstFree(@Nonnull boolean[] taken) {
        for (int i = 0; i < taken.length; i++) {
            if (!taken[i]) {
                return i;
            }
        }
        return -1;
    }
}
