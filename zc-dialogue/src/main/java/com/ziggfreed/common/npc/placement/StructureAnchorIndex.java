package com.ziggfreed.common.npc.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.cast.WorldEvictors;

/**
 * The live per-world index of worldgen spawn markers a structure anchor resolves against.
 *
 * <p>A structure anchor cannot be resolved on demand: a marker only becomes knowable when its
 * chunk loads and the engine adds the marker entity. So the marker sighting is recorded here as it
 * happens, and the sweep reads this index when it asks "where could this placement stand".
 *
 * <p><b>Transient on purpose.</b> The index is rebuilt from chunk loads every boot, and a marker
 * whose chunk has never loaded this session is simply not in it. That is the correct behaviour
 * rather than a gap: the sweep's place rule already requires the anchor's chunk to be LOADED, so
 * an unknown marker and an unloaded one lead to the same (correct) decision to do nothing. What
 * survives a restart is the ledger row, not the index.
 *
 * <p>Keyed by the marker's stable {@code prefabInstanceId}, which is what makes an anchor key
 * stable across restarts - a loop index would change with chunk-load order and mint a duplicate
 * NPC beside the standing one.
 *
 * <p>Evicted per world through {@link WorldEvictors}.
 */
public final class StructureAnchorIndex {

    /** One sighted marker: its id, the roles it can spawn, its stable instance id, and where it is. */
    public record Marker(@Nonnull String markerId, @Nonnull List<String> roles, int prefabInstanceId,
                         double x, double y, double z) {
    }

    private static final Map<World, Map<Integer, Marker>> INDEX = new ConcurrentHashMap<>();

    static {
        WorldEvictors.registerEvictor(INDEX::remove);
    }

    private StructureAnchorIndex() {
    }

    /**
     * Record a sighted marker. Idempotent per {@code prefabInstanceId}: a chunk re-loading the
     * same marker refreshes the entry rather than adding a second.
     *
     * @return true when this was a marker the index had not seen this session
     */
    public static boolean record(@Nonnull World world, @Nonnull String markerId, @Nonnull List<String> roles,
            int prefabInstanceId, double x, double y, double z) {
        Marker marker = new Marker(markerId, List.copyOf(roles), prefabInstanceId, x, y, z);
        return INDEX.computeIfAbsent(world, w -> new ConcurrentHashMap<>())
                .put(prefabInstanceId, marker) == null;
    }

    /** Every marker sighted in {@code world} this session. */
    @Nonnull
    public static Collection<Marker> markersIn(@Nullable World world) {
        if (world == null) {
            return List.of();
        }
        Map<Integer, Marker> byInstance = INDEX.get(world);
        return byInstance == null ? List.of() : new ArrayList<>(byInstance.values());
    }

    /** How many markers are indexed for a world (diagnostics, tests). */
    public static int size(@Nullable World world) {
        if (world == null) {
            return 0;
        }
        Map<Integer, Marker> byInstance = INDEX.get(world);
        return byInstance == null ? 0 : byInstance.size();
    }

    /** Drop one world's index (the evictor path, and a targeted reset). */
    public static void forget(@Nullable World world) {
        if (world != null) {
            INDEX.remove(world);
        }
    }

    /** Drop every world's index (tests). */
    static void clearForTests() {
        INDEX.clear();
    }

    /**
     * The PURE selection: which sighted markers a structure anchor accepts. Fail-closed on an
     * anchor with no allow-list authored, so a typo leaves the NPC absent rather than standing
     * beside every marker in the world.
     */
    @Nonnull
    public static List<Marker> matching(@Nonnull Collection<Marker> markers,
            @Nonnull NpcPlacementAsset.Anchor.Structure anchor) {
        List<Marker> out = new ArrayList<>();
        if (anchor.hasNoMatcher()) {
            return out;
        }
        for (Marker marker : markers) {
            if (marker == null) {
                continue;
            }
            if (anchor.matches(marker.markerId(), null)) {
                out.add(marker);
                continue;
            }
            for (String role : marker.roles()) {
                if (anchor.matches(null, role)) {
                    out.add(marker);
                    break;
                }
            }
        }
        return out;
    }
}
