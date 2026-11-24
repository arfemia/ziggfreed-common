package com.ziggfreed.common.track.pick;

import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.track.asset.SlotRow;

/**
 * Which saved builds a player may keep on a track, pure: every build from 1 to the track's {@code Loadouts.Max}, and
 * each further build its {@code Loadouts.Slots} numbers past that while its row is open (the shared {@link SlotRow}
 * rule a stance's slots keep, the row's {@code Pick} read on the track, {@code {Track}} its id). A track writing no
 * rows keeps exactly 1 to {@code Max}, asking nobody anything. {@code PickService} asks it of a live player.
 */
public final class LoadoutSlots {

    private LoadoutSlots() {
    }

    /**
     * The builds open, ascending: 1 to {@code max}, then each row of {@code rows} whose number ({@link SlotRow#number})
     * is past {@code max} and that opens on {@code trackId} ({@code owns} asked with a key pick's lower-cased id,
     * {@code passes} with a row's {@code Requires}). A row reading as no number opens nothing.
     */
    @Nonnull
    public static List<Integer> open(int max, @Nonnull List<SlotRow> rows, @Nullable String trackId,
            @Nonnull Predicate<String> owns, @Nonnull Predicate<GateSpec> passes) {
        TreeSet<Integer> out = new TreeSet<>();
        for (int slot = 1; slot <= max; slot++) {
            out.add(slot);
        }
        for (SlotRow row : rows) {
            int number = row.number();
            if (number > max && !out.contains(number) && row.opens(trackId, owns, passes)) {
                out.add(number);
            }
        }
        return List.copyOf(out);
    }

    /** Is the build {@code slot} open: within {@code max}, or a row past it numbering it and open? Pure. */
    public static boolean isOpen(int slot, int max, @Nonnull List<SlotRow> rows, @Nullable String trackId,
            @Nonnull Predicate<String> owns, @Nonnull Predicate<GateSpec> passes) {
        if (slot < 1) {
            return false;
        }
        if (slot <= max) {
            return true;
        }
        for (SlotRow row : rows) {
            if (row.number() == slot && row.opens(trackId, owns, passes)) {
                return true;
            }
        }
        return false;
    }
}
