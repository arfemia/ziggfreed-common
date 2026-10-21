package com.ziggfreed.common.encounter.wave;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An endless wave run's schedule and ramp: which wave is a boss wave, which band slot of the script each other wave
 * raises and at which size, and how much tougher each raised creature is. Pure: it reads no clock and no engine, so
 * a fight's readings ({@code ziggfreedcommon:encounter_wave_band} and the rest) and its add scaling are one
 * function of the wave number.
 *
 * <p><b>The schedule.</b> With {@code bossEvery} set, every {@code bossEvery}-th wave is a boss wave (5, 10, 15 for
 * 5), and its return is how many boss waves the run has reached with it (1 at wave 5, 2 at wave 10). Every other
 * wave is a mob wave: counted on their own (the boss waves left out), mob waves come in stints of
 * {@code bandWaves}, each stint raised by the next band slot of {@code bandSlots} in turn, and once every band has
 * had a stint the rotation starts again one size bigger, until {@code sizeMax}; with {@code sizeEvery} above 1 a
 * size holds for that many rotations. A boss wave carries the size the mob waves before it reached.
 *
 * <p>A wave number below 1 reads as wave 1.
 *
 * @param bossEvery the boss wave's spacing, 2 or more, or null for no boss waves
 * @param bandWaves how many mob waves one band raises in a row, 1 or more
 * @param bandSlots the band slots of the script, in the order they take turns, each 1 to {@link #MAX_SLOTS}; never
 *                  empty
 * @param sizeMax   the biggest size a wave reaches, 1 to {@link #MAX_SIZE}
 * @param sizeEvery how many rotations of the bands one size holds, 1 or more
 * @param ramp      how much tougher each raised creature is, wave by wave
 */
public record WaveRules(@Nullable Integer bossEvery, int bandWaves, @Nonnull List<Integer> bandSlots, int sizeMax,
        int sizeEvery, @Nonnull WaveRamp ramp) {

    /** How many band slots the endless template has. */
    public static final int MAX_SLOTS = 4;

    /** How many sizes the endless template raises a band at. */
    public static final int MAX_SIZE = 3;

    /** The least spacing a boss wave may have: every wave a boss wave is no wave run. */
    public static final int MIN_BOSS_EVERY = 2;

    public WaveRules {
        bossEvery = bossEvery == null || bossEvery < MIN_BOSS_EVERY ? null : bossEvery;
        bandWaves = Math.max(1, bandWaves);
        List<Integer> slots = new ArrayList<>();
        for (Integer slot : bandSlots) {
            if (slot != null && slot >= 1 && slot <= MAX_SLOTS) {
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("an endless run needs at least one band slot from 1 to " + MAX_SLOTS);
        }
        bandSlots = List.copyOf(slots);
        sizeMax = Math.max(1, Math.min(MAX_SIZE, sizeMax));
        sizeEvery = Math.max(1, sizeEvery);
    }

    /** Wave {@code number} of the run: whether it is a boss wave, its return, its band slot and its size. */
    @Nonnull
    public Wave at(int number) {
        int wave = Math.max(1, number);
        boolean boss = bossEvery != null && wave % bossEvery == 0;
        int returns = bossEvery == null ? 0 : wave / bossEvery;
        // The mob waves up to this one: this wave itself when it is one, the ones before it on a boss wave.
        int mobWaves = Math.max(1, wave - returns);
        int stint = (mobWaves - 1) / bandWaves;
        int rotation = stint / bandSlots.size();
        int size = Math.min(sizeMax, 1 + rotation / sizeEvery);
        int slot = boss ? 0 : bandSlots.get(stint % bandSlots.size());
        return new Wave(wave, boss, boss ? returns : 0, slot, size);
    }

    /**
     * One wave of an endless run.
     *
     * @param number  the wave's number, 1 or more
     * @param boss    whether it is a boss wave
     * @param returns on a boss wave, how many boss waves the run has reached with it (1 for the first); 0 otherwise
     * @param slot    on a mob wave, the band slot that raises it; 0 on a boss wave
     * @param size    the size it rises at, 1 to {@link #MAX_SIZE}
     */
    public record Wave(int number, boolean boss, int returns, int slot, int size) {
    }
}
