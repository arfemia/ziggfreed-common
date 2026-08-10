package com.ziggfreed.common.npc.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.util.SafeLog;

/**
 * Where each named zone was discovered, so a zone anchor has a position to resolve to.
 *
 * <p><b>The engine owns the anchor; the consumer supplies the trigger.</b> "A zone was discovered"
 * is a domain moment this library cannot observe for itself (a consumer knows what a zone is, how
 * a player discovers one, and whether that counts). So a consumer calls
 * {@link #notifyZoneDiscovered} when it sees one, and this library owns everything after: the
 * position, the anchor key, the ledger, the sweep. That split is what keeps the anchor generic
 * without inventing a zone model here.
 *
 * <p>A discovery also kicks a reconcile for that world, so an NPC anchored to a zone appears when
 * the zone is found rather than at the next unrelated sweep.
 *
 * <p>Transient per session (rebuilt from discoveries) and evicted per world through
 * {@link WorldEvictors}. Zone names match case-insensitively, and a region name is recorded as a
 * second name for the same position so an anchor may target either.
 */
public final class ZoneAnchorIndex {

    /** One discovered zone: the name it was recorded under and where the discovery happened. */
    public record Discovery(@Nonnull String zoneName, double x, double y, double z) {
    }

    private static final Map<World, Map<String, Discovery>> INDEX = new ConcurrentHashMap<>();

    static {
        WorldEvictors.registerEvictor(INDEX::remove);
    }

    private ZoneAnchorIndex() {
    }

    /**
     * Tell this library a zone was discovered at {@code (x, y, z)}. Both {@code zoneName} and
     * {@code regionName} are recorded (either may be null), so content may anchor to whichever
     * name it knows.
     *
     * <p>World-thread only, and it kicks a reconcile for {@code world} so a zone-anchored NPC
     * appears immediately. Fully guarded.
     */
    public static void notifyZoneDiscovered(@Nullable World world, @Nullable Store<EntityStore> store,
            @Nullable String zoneName, @Nullable String regionName, double x, double y, double z) {
        if (world == null) {
            return;
        }
        boolean recorded = record(world, zoneName, x, y, z) | record(world, regionName, x, y, z);
        if (!recorded || store == null) {
            return;
        }
        try {
            NpcPlacementReconciler.requestSweep(world, store);
        } catch (Throwable t) {
            SafeLog.warn("[placement] zone-discovery sweep failed: " + t.getMessage());
        }
    }

    private static boolean record(@Nonnull World world, @Nullable String name, double x, double y, double z) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        return INDEX.computeIfAbsent(world, w -> new ConcurrentHashMap<>())
                .putIfAbsent(key, new Discovery(key, x, y, z)) == null;
    }

    /** The discovery recorded for {@code name} in {@code world}, or {@code null}. */
    @Nullable
    public static Discovery discoveryOf(@Nullable World world, @Nullable String name) {
        if (world == null || name == null || name.isBlank()) {
            return null;
        }
        Map<String, Discovery> byName = INDEX.get(world);
        return byName == null ? null : byName.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /** Every discovery recorded in {@code world} this session. */
    @Nonnull
    public static Collection<Discovery> discoveriesIn(@Nullable World world) {
        if (world == null) {
            return List.of();
        }
        Map<String, Discovery> byName = INDEX.get(world);
        return byName == null ? List.of() : new ArrayList<>(byName.values());
    }

    /** Drop one world's discoveries (the evictor path). */
    public static void forget(@Nullable World world) {
        if (world != null) {
            INDEX.remove(world);
        }
    }

    /** Drop every world's discoveries (tests). */
    static void clearForTests() {
        INDEX.clear();
    }
}
