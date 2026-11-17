package com.ziggfreed.common.instance.hub;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedChunk;
import com.ziggfreed.common.instance.dungeon.gen.PaintJob;
import com.ziggfreed.common.instance.dungeon.gen.PaintPiece;
import com.ziggfreed.common.instance.dungeon.gen.PrefabPaint;
import com.ziggfreed.common.instance.dungeon.layout.Box;
import com.ziggfreed.common.instance.dungeon.layout.Cell;
import com.ziggfreed.common.instance.dungeon.layout.QuarterTurns;
import com.ziggfreed.common.instance.dungeon.layout.asset.PrefabPoolIndex;
import com.ziggfreed.common.instance.dungeon.room.RoomTurns;
import com.ziggfreed.common.instance.hub.HubLots.Lot;
import com.ziggfreed.common.util.SafeLog;

/**
 * One hub copy's layout, made once as the copy opens and kept for its life: which district stands on each lot
 * ({@link HubLayout}), the floors and prefabs its generator writes ({@link ZigHubWorldGenProvider}), where each
 * district's named spots stand ({@link HubAnchors}) and its map markers ({@link HubMarkers}). Immutable once made, but
 * for its spawner-child cache, which the painters fill from any thread.
 *
 * <p>Every copy is laid out from the same seed, so a hub looks the same each time it opens with the same districts.
 */
public final class HubPlan implements PaintJob {

    private static final String LOG = "[hub] ";

    /** The one seed every copy's spawner and child picks are made from. */
    static final long SEED = 0x7E3B1E_4B5DL;

    /** The block laid under the arrival when no district floors the Concourse, so nobody lands on the void. */
    static final String LANDING_BLOCK = "Rock_Stone";

    /** How far the landing reaches from the arrival's column, each way. */
    static final int LANDING_REACH = 3;

    /** The name of the spot by the arrival ({@link #byArrival}). */
    public static final String BY_ARRIVAL = "By_Arrival";

    /** How far ahead of the arrival (north, the way an arriving player looks) the spot by it stands. */
    static final int BY_ARRIVAL_AHEAD = 3;

    /** What has been warned about once already. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * One named spot of a district on a lot, in world blocks.
     *
     * @param district the district's id, as its file spells it
     * @param lotId    the lot it stands on
     * @param name     the spot's name, as its file spells it
     */
    public record Spot(@Nonnull String district, @Nonnull String lotId, @Nonnull String name, double x, double y,
                       double z, float yaw) {
    }

    /**
     * One district's map marker.
     *
     * @param id      the marker's id on the map, unique in the copy
     * @param icon    the marker's icon
     * @param nameKey the .lang key of its name, or null for none
     */
    public record MapSpot(@Nonnull String id, @Nonnull String icon, @Nullable String nameKey, double x, double y,
                          double z) {
    }

    /** A floor laid under a lot. */
    record Floor(@Nonnull Box box, int blockId) {
    }

    /** One district's prefabs on its lot, as the painter reads them. */
    record DistrictPiece(@Nonnull String label, @Nonnull Cell origin, @Nonnull PrefabRotation rotation,
                         @Nonnull Box box, @Nonnull IPrefabBuffer base, @Nonnull List<IPrefabBuffer> overlays,
                         int fillBlock, boolean keepContainers) implements PaintPiece {
    }

    @Nonnull private final HubLayout.Result layout;
    @Nonnull private final List<DistrictPiece> pieces;
    @Nonnull private final List<Floor> floors;
    @Nonnull private final Map<String, List<Spot>> spots;
    @Nonnull private final List<MapSpot> markers;
    @Nonnull private final Map<Path, IPrefabBuffer> children = new ConcurrentHashMap<>();

    private HubPlan(@Nonnull HubLayout.Result layout, @Nonnull List<DistrictPiece> pieces, @Nonnull List<Floor> floors,
            @Nonnull Map<String, List<Spot>> spots, @Nonnull List<MapSpot> markers) {
        this.layout = layout;
        this.pieces = List.copyOf(pieces);
        this.floors = List.copyOf(floors);
        this.spots = Map.copyOf(spots);
        this.markers = List.copyOf(markers);
    }

    /** A plan with nothing on it but the landing, for a copy whose districts could not be read. */
    @Nonnull
    static HubPlan bare() {
        List<Floor> floors = new ArrayList<>();
        landing(floors);
        return new HubPlan(new HubLayout.Result(List.of(), List.of()), List.of(), floors, Map.of(), List.of());
    }

    /**
     * Lay a copy out from the districts in force now: the live ones ({@code Enabled}, their season running) on the
     * lots {@link HubLayout} gives them, each prefab loaded. Off the world thread: it reads prefab files.
     */
    @Nonnull
    static HubPlan build(@Nonnull HubDistrictConfig config, @Nonnull PrefabPoolIndex pools) {
        HubLayout.Result layout = HubLayout.assign(HubLots.all(), config.claims(true));
        List<DistrictPiece> pieces = new ArrayList<>();
        List<Floor> floors = new ArrayList<>();
        Map<String, List<Spot>> spots = new LinkedHashMap<>();
        List<MapSpot> markers = new ArrayList<>();
        boolean centreFloored = false;
        for (HubLayout.Placed placed : layout.placed()) {
            HubDistrictAsset district = config.resolve(placed.districtId());
            if (district == null) {
                continue;
            }
            Lot lot = placed.lot();
            int floorId = blockOf(district.floor(), district.getId());
            if (floorId >= 0) {
                floors.add(new Floor(lot.floor(), floorId));
                centreFloored |= lot.id().equals(HubLots.CONCOURSE);
            }
            DistrictPiece piece = pieceOf(district, lot, pools);
            if (piece != null) {
                pieces.add(piece);
            }
            List<Spot> lotSpots = spots.computeIfAbsent(key(placed.districtId()), k -> new ArrayList<>());
            for (HubDistrictAsset.Anchor anchor : district.anchors()) {
                lotSpots.add(spotOf(placed.districtId(), lot, anchor));
            }
            if (district.hasMarker()) {
                markers.add(markerOf(district, placed.districtId(), lot));
            }
        }
        if (!centreFloored) {
            landing(floors);
        }
        return new HubPlan(layout, pieces, floors, spots, markers);
    }

    // ==================== the reads ====================

    /** Which district stands on which lot, and the districts left off. */
    @Nonnull
    public HubLayout.Result layout() {
        return layout;
    }

    /** Every spot of {@code district} (one per lot it stands on), in lot order; empty when it stands nowhere. */
    @Nonnull
    public List<Spot> spotsOf(@Nullable String district) {
        if (district == null || district.isBlank()) {
            return List.of();
        }
        List<Spot> found = spots.get(key(district));
        return found == null ? List.of() : found;
    }

    /** Every spot named {@code anchor} of {@code district}, one per lot it stands on. */
    @Nonnull
    public List<Spot> spots(@Nullable String district, @Nullable String anchor) {
        List<Spot> out = new ArrayList<>();
        if (anchor == null || anchor.isBlank()) {
            return out;
        }
        for (Spot spot : spotsOf(district)) {
            if (spot.name().equalsIgnoreCase(anchor.trim())) {
                out.add(spot);
            }
        }
        return out;
    }

    /** Every district's map marker. */
    @Nonnull
    public List<MapSpot> markers() {
        return markers;
    }

    /**
     * The spot by the arrival, the same in every copy: {@link #BY_ARRIVAL_AHEAD} blocks ahead of where players land (the
     * Concourse lot's centre), facing them, on ground every copy lays (a district's floor there, else the landing). A
     * character whose own spot stands nowhere waits here when its placement asks ({@link HubAnchors#OR_ARRIVAL}), so
     * the hub keeps its way out whatever an owner does with the Concourse.
     */
    @Nonnull
    public static Spot byArrival() {
        Lot concourse = HubLots.find(HubLots.CONCOURSE);
        Cell centre = concourse == null ? new Cell(0, HubLots.FLOOR_Y, 0) : concourse.anchor();
        return new Spot("", HubLots.CONCOURSE, BY_ARRIVAL, centre.x() + 0.5, centre.y() + 1.0,
                centre.z() + 0.5 - BY_ARRIVAL_AHEAD, 180f);
    }

    /** What the copy's generator writes into the chunk starting at {@code (baseX, baseZ)}: floors, then prefabs. */
    void paint(@Nonnull GeneratedChunk chunk, int baseX, int baseZ, @Nonnull PrefabPoolIndex pools) {
        for (Floor floor : floors) {
            PrefabPaint.fillBox(chunk, baseX, baseZ, floor.box(), floor.blockId());
        }
        PrefabPaint.paint(chunk, baseX, baseZ, this, pools);
    }

    /** The one line a copy logs as it opens. */
    @Nonnull
    String summary() {
        int placeholders = 0;
        for (HubLayout.Placed placed : layout.placed()) {
            if (placed.placeholder()) {
                placeholders++;
            }
        }
        return String.format(Locale.ROOT, "%d of %d lots built on (%d by a placeholder), %d prefab pieces, %d "
                        + "district(s) left off the map%s", layout.placed().size(), HubLots.all().size(), placeholders,
                pieces.size(), layout.problems().size(),
                layout.problems().isEmpty() ? "" : " (the hub audit names each and why)");
    }

    // ==================== PaintJob ====================

    @Override
    public long seed() {
        return SEED;
    }

    @Nonnull
    @Override
    public List<DistrictPiece> pieces() {
        return pieces;
    }

    @Nonnull
    @Override
    public IPrefabBuffer child(@Nonnull Path prefab) {
        return children.computeIfAbsent(prefab, PrefabBufferUtil::getCached);
    }

    @Nonnull
    @Override
    public String describe() {
        return "the temple hub";
    }

    // ==================== building ====================

    /** The district's prefabs on {@code lot}, loaded; null when it names none, or none a loaded pack ships. */
    @Nullable
    private static DistrictPiece pieceOf(@Nonnull HubDistrictAsset district, @Nonnull Lot lot,
            @Nonnull PrefabPoolIndex pools) {
        List<String> keys = new ArrayList<>();
        if (district.basePrefab() != null) {
            keys.add(district.basePrefab());
        }
        keys.addAll(district.overlays());
        List<IPrefabBuffer> loaded = new ArrayList<>();
        String baseKey = null;
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            List<Path> paths = pools.paths(key);
            if (paths.isEmpty()) {
                warnOnce("prefab:" + key, LOG + "the district " + district.getId() + " names the prefab " + key
                        + ", which no loaded pack ships under Server/Prefabs/; it is built without it");
                continue;
            }
            // A pool's pick is the lot's: the same district stands the same way on the same lot every time.
            int pick = i == 0 && paths.size() > 1 ? Math.floorMod(lot.id().hashCode(), paths.size()) : 0;
            loaded.add(PrefabBufferUtil.getCached(paths.get(pick)));
            if (baseKey == null) {
                baseKey = key;
            }
        }
        if (loaded.isEmpty()) {
            return null;
        }
        int turns = lot.turns();
        return new DistrictPiece("district " + district.getId() + " on the lot " + lot.id() + " (" + baseKey + ")",
                lot.anchor(), PrefabRotation.VALUES[QuarterTurns.normalize(turns)], lot.box(), loaded.get(0),
                List.copyOf(loaded.subList(1, loaded.size())), -1, district.keepContainers());
    }

    /** Where {@code anchor} of a district on {@code lot} stands: at its block's centre, turned with the lot. */
    @Nonnull
    static Spot spotOf(@Nonnull String district, @Nonnull Lot lot, @Nonnull HubDistrictAsset.Anchor anchor) {
        int turns = lot.turns();
        Cell origin = lot.anchor();
        double px = anchor.x() + 0.5;
        double pz = anchor.z() + 0.5;
        return new Spot(district, lot.id(), anchor.name() == null ? "" : anchor.name(),
                origin.x() + RoomTurns.placedX(px, pz, turns), origin.y() + anchor.y(),
                origin.z() + RoomTurns.placedZ(px, pz, turns), (float) RoomTurns.addDegrees(anchor.yaw(), turns));
    }

    /** A district's marker on {@code lot}: on its named spot when it names one this district has, else the centre. */
    @Nonnull
    private static MapSpot markerOf(@Nonnull HubDistrictAsset district, @Nonnull String districtId, @Nonnull Lot lot) {
        String id = "ziggfreedcommon:hub:" + key(districtId) + ":" + key(lot.id());
        HubDistrictAsset.Anchor anchor = district.anchor(district.markerAnchor());
        if (anchor != null) {
            Spot spot = spotOf(districtId, lot, anchor);
            return new MapSpot(id, district.markerIcon(), district.markerNameKey(), spot.x(), spot.y(), spot.z());
        }
        Cell centre = lot.anchor();
        return new MapSpot(id, district.markerIcon(), district.markerNameKey(), centre.x() + 0.5, centre.y() + 1.0,
                centre.z() + 0.5);
    }

    /** The landing under the arrival, the Concourse's centre. */
    private static void landing(@Nonnull List<Floor> floors) {
        int id = blockOf(LANDING_BLOCK, "the landing");
        Lot concourse = HubLots.find(HubLots.CONCOURSE);
        if (id < 0 || concourse == null) {
            return;
        }
        Cell centre = concourse.anchor();
        floors.add(new Floor(new Box(new Cell(centre.x() - LANDING_REACH, centre.y(), centre.z() - LANDING_REACH),
                new Cell(centre.x() + LANDING_REACH, centre.y(), centre.z() + LANDING_REACH)), id));
    }

    /** The block id {@code name} names, or -1 for none (or a name no loaded block answers to, said once). */
    private static int blockOf(@Nullable String name, @Nonnull String owner) {
        if (name == null) {
            return -1;
        }
        int id = BlockType.getAssetMap().getIndex(name);
        if (id == AssetMapWithIndexes.NOT_FOUND) {
            warnOnce("floor:" + name, LOG + owner + " lays its floor in " + name
                    + ", which no loaded block answers to; that ground is left empty");
            return -1;
        }
        return id;
    }

    private static void warnOnce(@Nonnull String key, @Nonnull String line) {
        if (WARNED.add(key.toLowerCase(Locale.ROOT))) {
            SafeLog.warn(line);
        }
    }

    @Nonnull
    private static String key(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
