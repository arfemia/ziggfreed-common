package com.ziggfreed.common.npc.placement.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Where each placed NPC actually ended up, so a consumer can point at one whose chunk is asleep.
 *
 * <p>It exists for exactly one shape of feature: a quest waypoint, a map marker, a "your guide is
 * over there" arrow. Those need a POSITION for an NPC that may not be resident, and reading it off
 * the entity is impossible precisely when it matters most.
 *
 * <p><b>Keyed by {@code (worldName, placementId, anchorKey)}, never by placement id alone.</b> Two
 * concurrent instances of one dungeon share a placement id, so a single-key cache would point a
 * player standing in instance A at instance B's coordinates - a bug that only appears once two
 * people are in the same dungeon separately, which is to say in production.
 *
 * <p><b>Not an authority.</b> {@link NpcPlacementLedger} decides what has been placed; this only
 * remembers where. A miss means "ask again later", never "nothing is placed there".
 */
public final class NpcPlacementPositionCache {

    /** One remembered position. */
    public record Entry(@Nonnull String worldName, @Nonnull String placementId, @Nonnull String anchorKey,
                        double x, double y, double z) {
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private NpcPlacementPositionCache() {
    }

    @Nonnull
    private static String key(@Nonnull String worldName, @Nonnull String placementId, @Nonnull String anchorKey) {
        return worldName.toLowerCase(Locale.ROOT) + '\0'
                + placementId.toLowerCase(Locale.ROOT) + '\0'
                + anchorKey.toLowerCase(Locale.ROOT);
    }

    /** Remember where an instance was placed. */
    public static void record(@Nonnull String worldName, @Nonnull String placementId, @Nonnull String anchorKey,
            double x, double y, double z) {
        CACHE.put(key(worldName, placementId, anchorKey),
                new Entry(worldName, placementId, anchorKey, x, y, z));
    }

    /** The remembered position for one instance, or {@code null}. */
    @Nullable
    public static Entry get(@Nonnull String worldName, @Nonnull String placementId, @Nonnull String anchorKey) {
        return CACHE.get(key(worldName, placementId, anchorKey));
    }

    /**
     * Every remembered position for {@code placementId} in {@code worldName}. The read a waypoint
     * feature makes: it knows which placement it wants and which world the player is in, and gets
     * back only that world's instances.
     */
    @Nonnull
    public static List<Entry> forPlacement(@Nonnull String worldName, @Nonnull String placementId) {
        String world = worldName.toLowerCase(Locale.ROOT);
        String placement = placementId.toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry e : CACHE.values()) {
            if (e.worldName().equalsIgnoreCase(world) && e.placementId().equalsIgnoreCase(placement)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Forget one instance (it was despawned, or its row was dropped). */
    public static void forget(@Nonnull String worldName, @Nonnull String placementId, @Nonnull String anchorKey) {
        CACHE.remove(key(worldName, placementId, anchorKey));
    }

    /** Forget a whole world (it was removed). */
    public static void forgetWorld(@Nonnull String worldName) {
        String prefix = worldName.toLowerCase(Locale.ROOT) + '\0';
        CACHE.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Forget everything (an asset reload changed what the positions mean). */
    public static void invalidateAll() {
        CACHE.clear();
    }

    /** How many positions are remembered (diagnostics, tests). */
    public static int size() {
        return CACHE.size();
    }
}
