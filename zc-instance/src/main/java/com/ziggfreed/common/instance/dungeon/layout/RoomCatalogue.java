package com.ziggfreed.common.instance.dungeon.layout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Every room a layout can draw on, with each one's variant count asked of the {@link PoolIndex} once, here. Rooms
 * are kept in id order, so the generator walks them the same way whatever order the files loaded in; a repeated id
 * keeps the last one given.
 */
public final class RoomCatalogue {

    private final List<RoomSpec> rooms;
    private final Map<String, RoomSpec> byId;
    private final Map<String, Integer> variants;

    private RoomCatalogue(@Nonnull List<RoomSpec> rooms, @Nonnull Map<String, Integer> variants) {
        this.rooms = List.copyOf(rooms);
        Map<String, RoomSpec> index = new HashMap<>();
        for (RoomSpec room : this.rooms) {
            index.put(room.id(), room);
        }
        this.byId = index;
        this.variants = variants;
    }

    /** A catalogue of {@code rooms}, each pool counted through {@code pools}. */
    @Nonnull
    public static RoomCatalogue of(@Nonnull Collection<RoomSpec> rooms, @Nonnull PoolIndex pools) {
        Map<String, RoomSpec> unique = new LinkedHashMap<>();
        for (RoomSpec room : rooms) {
            if (room != null && !room.id().isEmpty()) {
                unique.put(room.id(), room);
            }
        }
        List<RoomSpec> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparing(RoomSpec::id));
        Map<String, Integer> counts = new HashMap<>();
        for (RoomSpec room : sorted) {
            counts.put(room.id(), countOf(pools, room.prefab()));
        }
        return new RoomCatalogue(sorted, counts);
    }

    /** Every room, in id order. */
    @Nonnull
    public List<RoomSpec> rooms() {
        return rooms;
    }

    /** The room {@code id} names, matched without regard to case, or null. */
    @Nullable
    public RoomSpec room(@Nullable String id) {
        String folded = Words.fold(id);
        return folded == null ? null : byId.get(folded);
    }

    /** How many variants room {@code roomId}'s prefab names; 0 for an unknown room or an empty pool. */
    public int variants(@Nonnull String roomId) {
        String folded = Words.fold(roomId);
        Integer count = folded == null ? null : variants.get(folded);
        return count == null ? 0 : count;
    }

    /** Whether the generator may pick the room at all: something to paste and a weight above 0. */
    public boolean usable(@Nonnull RoomSpec room) {
        return room.weight() > 0 && variants(room.id()) > 0;
    }

    private static int countOf(@Nonnull PoolIndex pools, @Nonnull String prefab) {
        if (prefab.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, pools.variants(prefab));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
