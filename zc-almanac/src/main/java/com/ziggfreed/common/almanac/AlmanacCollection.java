package com.ziggfreed.common.almanac;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.almanac.asset.AlmanacCollectionAsset;
import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.asset.AlmanacSectionAsset;
import com.ziggfreed.common.counter.CounterMap;

/**
 * A player's "owned once" marks: the first time an item a season page's collection lists reaches any of their
 * inventory sections, its mark is set in their Almanac record, {@code $owned/<item>} = 1, and it stays for good. A
 * hidden slot shows its item from then on. Only listed items are ever marked, so an item no page lists costs one
 * lookup and writes nothing. The marks never reach the server's totals.
 *
 * <p>Two triggers set them: {@link AlmanacInventorySystem}, on every change to a player's inventory sections, and
 * the page's own look at the bag when it opens on a season ({@link #markHeld}), so an item held before the
 * collection existed shows the first time the page opens.
 */
public final class AlmanacCollection {

    private AlmanacCollection() {
    }

    /** Has the player obtained {@code itemId} once? Matched without regard to case; false for no id. */
    public static boolean owned(@Nonnull CounterMap tallies, @Nullable String itemId) {
        return storable(itemId) && tallies.get(AlmanacKeys.owned(itemId)) > 0L;
    }

    /** Mark {@code itemId} obtained, for good; true when the mark is new. A blank id, or one carrying the save's {@code |}, is refused. */
    public static boolean markOwned(@Nonnull CounterMap tallies, @Nullable String itemId) {
        return storable(itemId) && tallies.highWater(AlmanacKeys.owned(itemId), 1L);
    }

    /** Mark {@code itemId} when {@code index} tracks it; true when the mark is new. */
    public static boolean markIfTracked(@Nonnull CounterMap tallies, @Nonnull AlmanacIndex index,
            @Nullable String itemId) {
        return index.tracks(itemId) && markOwned(tallies, itemId);
    }

    /** Mark each of {@code itemIds} not yet owned that {@code held} says the player holds now; how many were new. */
    public static int markHeld(@Nonnull CounterMap tallies, @Nonnull Collection<String> itemIds,
            @Nonnull Predicate<String> held) {
        int marked = 0;
        for (String itemId : itemIds) {
            if (!storable(itemId) || owned(tallies, itemId)) {
                continue;
            }
            if (holds(held, itemId) && markOwned(tallies, itemId)) {
                marked++;
            }
        }
        return marked;
    }

    /** The item ids {@code page}'s collections list, in order, each once (matched without case); empty for none. */
    @Nonnull
    public static List<String> itemsOf(@Nullable AlmanacEntryAsset page) {
        List<AlmanacSectionAsset> sections = page == null ? null : page.sections();
        if (sections == null) {
            return List.of();
        }
        Map<String, String> firstSpelling = new LinkedHashMap<>();
        for (AlmanacSectionAsset section : sections) {
            AlmanacCollectionAsset collection = section.collection();
            if (collection == null) {
                continue;
            }
            for (AlmanacCollectionAsset.Slot slot : collection.slots()) {
                firstSpelling.putIfAbsent(AlmanacKeys.normalize(slot.item()), slot.item());
            }
        }
        return List.copyOf(firstSpelling.values());
    }

    /** A {@code held} that throws reads as not held: one item's lookup never costs the rest. */
    private static boolean holds(@Nonnull Predicate<String> held, @Nonnull String itemId) {
        try {
            return held.test(itemId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Can the record hold a mark for {@code itemId}? Not blank, and free of {@code |}, the save's pair join. */
    private static boolean storable(@Nullable String itemId) {
        return itemId != null && !itemId.isBlank() && itemId.indexOf('|') < 0;
    }
}
