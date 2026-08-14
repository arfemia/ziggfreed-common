package com.ziggfreed.common.rotation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;

import com.ziggfreed.common.rotation.WeightedSlotDraw.DrawResult;
import com.ziggfreed.common.rotation.WeightedSlotDraw.SlotMatcher;

/**
 * Lays ONE player's re-rolled positions over the shared base draw.
 *
 * <p>The base draw stays what every player sees; a reroll is a per-player override at one position,
 * applied here at presentation time. That is what keeps the rotation itself stateless while still
 * letting somebody swap the one contract they cannot stand.
 *
 * <p><b>A stale override is dropped rather than shown.</b> An override whose candidate no longer
 * resolves, or no longer qualifies for the position it sits in, is discarded and the base pick
 * stands - so editing a pool or retiring a candidate can never leave a player looking at something
 * that is not there any more.
 */
public final class SlotRerollEngine {

    private SlotRerollEngine() {
    }

    /**
     * The base draw with {@code overrides} applied: position to the id shown there instead.
     *
     * @param resolve turns a stored id back into a live candidate, or null when it no longer exists
     * @param matcher the same eligibility test the draw used, so an override cannot sit in a slot
     *                that would never have produced it
     */
    @Nonnull
    public static <T> List<T> applyOverrides(@Nonnull DrawResult<T> base,
            @Nonnull Map<Integer, String> overrides, @Nonnull Function<String, T> resolve,
            @Nonnull SlotMatcher<T> matcher) {
        List<T> items = new ArrayList<>(base.items());
        for (Map.Entry<Integer, String> entry : overrides.entrySet()) {
            int position = entry.getKey();
            if (position < 0 || position >= items.size()) {
                continue;
            }
            T candidate = resolve.apply(entry.getValue());
            if (candidate == null) {
                continue;
            }
            PoolSlot slot = base.slotAt(position);
            if (slot != null && !matcher.matches(candidate, slot)) {
                continue;
            }
            items.set(position, candidate);
        }
        return items;
    }

    /**
     * The ids a reroll at {@code position} must NOT land on: every id currently on show, INCLUDING
     * the one being replaced.
     *
     * <p>Including it is the whole point. The replacement draw is deterministic per period, so a
     * draw allowed to re-pick the current candidate reads as "no alternative exists" whenever it
     * happens to land there, and stays stuck on that answer until the reroll count moves - a player
     * paying for a reroll that visibly does nothing.
     */
    @Nonnull
    public static <T> Set<String> excludeAll(@Nonnull List<T> current, @Nonnull Function<T, String> idFn) {
        Set<String> excluded = new HashSet<>();
        for (T candidate : current) {
            excluded.add(idFn.apply(candidate));
        }
        return excluded;
    }

    /**
     * {@link #excludeAll} plus everything that has ALREADY occupied {@code position} this period, so
     * a position cannot cycle back to something the player has already re-rolled away.
     */
    @Nonnull
    public static <T> Set<String> excludeAll(@Nonnull List<T> current, @Nonnull Function<T, String> idFn,
            @Nonnull Set<String> alreadySeenHere) {
        Set<String> excluded = excludeAll(current, idFn);
        excluded.addAll(alreadySeenHere);
        return excluded;
    }
}
