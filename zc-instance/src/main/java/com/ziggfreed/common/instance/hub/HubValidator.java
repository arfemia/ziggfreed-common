package com.ziggfreed.common.instance.hub;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.ziggfreed.common.instance.dungeon.layout.RoomSpec;
import com.ziggfreed.common.instance.dungeon.layout.asset.PrefabPoolIndex;
import com.ziggfreed.common.instance.hub.HubLots.Lot;
import com.ziggfreed.common.instance.hub.HubLots.Size;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The content audit over the folded hub districts ({@link HubDistrictAsset}), filed under the {@code hub} domain, run
 * once every pack has loaded (the boot audit's pass). Split the way every validator in this family is: {@link #audit()}
 * is the engine walk, {@link #audit(Collection, List, Predicate, Predicate, List)} the pure core.
 *
 * <p>Every code is a WARNING (a district's owner may be a mod this server does not run):
 * <ul>
 *   <li>the lot rule's own ({@link HubLayout}): {@code LOT_UNKNOWN}, {@code LOT_TOO_SMALL}, {@code LOT_CLASH} and
 *       {@code NO_LOT}, over every enabled district whatever its season, so a seasonal clash shows all year;</li>
 *   <li>{@link #SIZE_UNKNOWN}, {@link #CONCOURSE_EMPTY} (players arrive on the scaffolding there, or the bare
 *       landing when none fills it);</li>
 *   <li>{@link #PREFAB_UNKNOWN} and {@link #OVERLAY_POOL} for its prefabs, {@link #FLOOR_UNKNOWN} for its floor;</li>
 *   <li>{@link #ANCHOR_DUPLICATE}, {@link #ANCHOR_OUTSIDE} and {@link #MARKER_ANCHOR_UNKNOWN} for its spots;</li>
 *   <li>{@link #PLACEMENT_ANCHOR_UNRESOLVED}: an NPC placement's hub anchor naming a district or a spot no district
 *       declares (a typo), or one no enabled district on the map stands, unless the placement waits by the arrival
 *       then ({@code OrArrival});</li>
 *   <li>{@link #REQUIRES_PLAYER}: a {@code Requires} asking more than mod presence, which a district never reads;</li>
 *   <li>{@code UNKNOWN_SEASON}, through {@code SeasonGate}.</li>
 * </ul>
 * A door's way (the {@code Instance} destination a placement or a conversation opens) is checked by that destination's
 * own audit ({@code TravelDestinations}), wherever it is authored.
 */
public final class HubValidator {

    public static final String DOMAIN = "hub";

    /** The label the audit's lines carry when it is logged whole (zc's boot audit). */
    public static final String LOG_LABEL = "[hub] audit";

    public static final String SIZE_UNKNOWN = "SIZE_UNKNOWN";
    public static final String CONCOURSE_EMPTY = "CONCOURSE_EMPTY";
    public static final String PREFAB_UNKNOWN = "PREFAB_UNKNOWN";
    public static final String OVERLAY_POOL = "OVERLAY_POOL";
    public static final String FLOOR_UNKNOWN = "FLOOR_UNKNOWN";
    public static final String ANCHOR_DUPLICATE = "ANCHOR_DUPLICATE";
    public static final String ANCHOR_OUTSIDE = "ANCHOR_OUTSIDE";
    public static final String MARKER_ANCHOR_UNKNOWN = "MARKER_ANCHOR_UNKNOWN";
    public static final String PLACEMENT_ANCHOR_UNRESOLVED = "PLACEMENT_ANCHOR_UNRESOLVED";
    public static final String REQUIRES_PLAYER = "REQUIRES_PLAYER";

    /**
     * An NPC placement standing on a hub spot, as the placement store authors it.
     *
     * @param placementId the placement
     * @param district    its {@code Params.District}, or null when unwritten
     * @param anchor      its {@code Params.Anchor}, or null when unwritten
     * @param orArrival   its {@code Params.OrArrival}: it waits by the arrival when its spot stands nowhere
     */
    public record AnchorRef(@Nonnull String placementId, @Nullable String district, @Nullable String anchor,
                            boolean orArrival) {
    }

    @Nonnull
    private static volatile Supplier<Map<String, Map<String, String>>> placementAnchors = Map::of;
    private static volatile boolean anchorsFilled;
    private static final AtomicBoolean WARNED_UNFILLED = new AtomicBoolean();

    private HubValidator() {
    }

    /**
     * The placements standing on hub spots ({@link HubAnchors#PROVIDER}), each placement id to its {@code Custom}
     * anchor's {@code Params}, which only the wiring root can read (the placement store is another module's): filled
     * at setup. Unfilled, the audit leaves placements unchecked and says so once.
     */
    public static void placementAnchors(@Nonnull Supplier<Map<String, Map<String, String>>> source) {
        placementAnchors = source;
        anchorsFilled = true;
    }

    /** The engine walk over every folded district and the placements standing on them. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            HubDistrictConfig config = HubDistrictConfig.getInstance();
            PrefabPoolIndex pools = PrefabPoolIndex.live();
            return audit(config.all().values(), config.claims(false), key -> !pools.paths(key).isEmpty(),
                    HubValidator::blockKnown, refs());
        } catch (Throwable t) {
            SafeLog.warn("[hub] the hub audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /**
     * The pure core.
     *
     * @param districts    every folded district
     * @param claims       the enabled districts as the layout reads them
     * @param prefabExists whether a prefab name (one prefab or a pool) resolves to a prefab a loaded pack ships
     * @param blockExists  whether a block id names a loaded block
     * @param refs         the placements standing on hub spots
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<HubDistrictAsset> districts,
            @Nonnull List<HubLayout.Claim> claims, @Nonnull Predicate<String> prefabExists,
            @Nonnull Predicate<String> blockExists, @Nonnull List<AnchorRef> refs) {
        List<Finding> out = new ArrayList<>();
        HubLayout.Result layout = HubLayout.assign(HubLots.all(), claims);
        for (HubLayout.Problem problem : layout.problems()) {
            out.add(Finding.warning(DOMAIN, problem.code(), lotLine(problem), problem.districtId()));
        }
        HubLayout.Placed centre = null;
        for (HubLayout.Placed placed : layout.placed()) {
            if (placed.lot().id().equals(HubLots.CONCOURSE)) {
                centre = placed;
            }
        }
        if (centre == null || centre.placeholder()) {
            out.add(Finding.warning(DOMAIN, CONCOURSE_EMPTY, "no enabled district claims the Concourse lot, so players "
                    + "arrive in the hub on " + (centre == null ? "the bare landing" : "the scaffolding ('"
                    + centre.districtId() + "')") + "; a character whose placement waits by the arrival (OrArrival) "
                    + "stands there instead of on its own spot", HubLots.CONCOURSE));
        }
        for (HubDistrictAsset district : districts) {
            if (district == null || district.getId() == null) {
                continue;
            }
            auditOne(out, district, layout, prefabExists, blockExists);
        }
        for (AnchorRef ref : refs) {
            String why = unresolved(ref, districts, layout);
            if (why != null) {
                out.add(Finding.warning(DOMAIN, PLACEMENT_ANCHOR_UNRESOLVED, "placement '" + ref.placementId()
                        + "' stands on the hub spot " + ref.district() + "/" + ref.anchor() + ", but " + why
                        + (ref.orArrival() ? ", so it always waits by the arrival instead"
                                : ", so it never stands in a hub copy"), ref.placementId()));
            }
        }
        return out;
    }

    private static void auditOne(@Nonnull List<Finding> out, @Nonnull HubDistrictAsset district,
            @Nonnull HubLayout.Result layout, @Nonnull Predicate<String> prefabExists,
            @Nonnull Predicate<String> blockExists) {
        String id = district.getId();
        if (district.sizeWord() != null && district.size() == null) {
            out.add(Finding.warning(DOMAIN, SIZE_UNKNOWN, "district '" + id + "' is of size '" + district.sizeWord()
                    + "', which is neither Small nor Large, so it fits whatever lot it names", id));
        }
        if (district.basePrefab() != null && !exists(prefabExists, district.basePrefab())) {
            out.add(Finding.warning(DOMAIN, PREFAB_UNKNOWN, "district '" + id + "' is built from the prefab "
                    + district.basePrefab() + ", which no loaded pack ships under Server/Prefabs/", id));
        }
        for (String overlay : district.overlays()) {
            if (overlay.endsWith(RoomSpec.POOL_SUFFIX)) {
                out.add(Finding.warning(DOMAIN, OVERLAY_POOL, "district '" + id + "' names the pool " + overlay
                        + " as an overlay; an overlay is one prefab, so only the pool's first is written", id));
            } else if (!exists(prefabExists, overlay)) {
                out.add(Finding.warning(DOMAIN, PREFAB_UNKNOWN, "district '" + id + "' names the overlay " + overlay
                        + ", which no loaded pack ships under Server/Prefabs/", id));
            }
        }
        if (district.floor() != null && !exists(blockExists, district.floor())) {
            out.add(Finding.warning(DOMAIN, FLOOR_UNKNOWN, "district '" + id + "' lays its floor in " + district.floor()
                    + ", which is no loaded block, so its lot has no ground", id));
        }
        auditAnchors(out, district, layout);
        if (StoreGates.live(district.requires()) != null) {
            out.add(Finding.warning(DOMAIN, REQUIRES_PLAYER, "district '" + id + "' asks more of its Requires than "
                    + "mod presence; a district stands for everyone in a copy or for no one, so only the mods are read",
                    id));
        }
        SeasonGate.checkKnown(out, DOMAIN, district.getSeason(), id);
    }

    private static void auditAnchors(@Nonnull List<Finding> out, @Nonnull HubDistrictAsset district,
            @Nonnull HubLayout.Result layout) {
        String id = district.getId();
        Size size = district.size();
        Lot lot = layout.lotOf(id);
        int span = size != null ? size.span() : lot != null ? lot.size().span() : Size.LARGE.span();
        Set<String> seen = new HashSet<>();
        for (HubDistrictAsset.Anchor anchor : district.anchors()) {
            if (!seen.add(anchor.name().toLowerCase(Locale.ROOT))) {
                out.add(Finding.warning(DOMAIN, ANCHOR_DUPLICATE, "district '" + id + "' names two spots '"
                        + anchor.name() + "'; a placement on it stands on the first", id));
            }
            int half = span / 2;
            boolean inside = anchor.x() >= -half && anchor.x() < span - half && anchor.z() >= -half
                    && anchor.z() < span - half && anchor.y() >= -HubLots.DEPTH && anchor.y() < HubLots.HEIGHT;
            if (!inside) {
                out.add(Finding.warning(DOMAIN, ANCHOR_OUTSIDE, "district '" + id + "' puts its spot '" + anchor.name()
                        + "' outside its own ground (" + span + " blocks square around its anchor), on a street or a "
                        + "neighbour's lot", id));
            }
        }
        String marked = district.markerAnchor();
        if (district.hasMarker() && marked != null && district.anchor(marked) == null) {
            out.add(Finding.warning(DOMAIN, MARKER_ANCHOR_UNKNOWN, "district '" + id + "' puts its map marker on the "
                    + "spot '" + marked + "', which it does not name, so the marker stands at the lot's centre", id));
        }
    }

    /**
     * Why a placement's hub spot resolves nowhere, or null when it resolves, or when it stands nowhere only because its
     * district is off the map and the placement waits by the arrival then. A spot no district declares is a typo, and is
     * named either way.
     */
    @Nullable
    private static String unresolved(@Nonnull AnchorRef ref, @Nonnull Collection<HubDistrictAsset> districts,
            @Nonnull HubLayout.Result layout) {
        if (ref.district() == null || ref.anchor() == null) {
            return "its Params name no " + (ref.district() == null ? HubAnchors.DISTRICT : HubAnchors.ANCHOR);
        }
        HubDistrictAsset district = null;
        for (HubDistrictAsset d : districts) {
            if (d != null && d.getId() != null && d.getId().equalsIgnoreCase(ref.district())) {
                district = d;
            }
        }
        if (district == null) {
            return "no district of that id is loaded";
        }
        if (district.anchor(ref.anchor()) == null) {
            return "that district names no such spot";
        }
        if (ref.orArrival()) {
            return null;
        }
        if (!district.isEnabled()) {
            return "that district is switched off";
        }
        for (HubLayout.Placed placed : layout.placed()) {
            if (placed.districtId().equalsIgnoreCase(district.getId())) {
                return null;
            }
        }
        return "that district stands on no lot";
    }

    @Nonnull
    private static String lotLine(@Nonnull HubLayout.Problem problem) {
        String district = "district '" + problem.districtId() + "'";
        return switch (problem.code()) {
            case HubLayout.LOT_UNKNOWN -> district + " claims the lot '" + problem.lotId() + "', which the hub's map "
                    + "does not have, so it stands nowhere";
            case HubLayout.LOT_TOO_SMALL -> district + " is larger than the lot " + problem.lotId()
                    + ", so it stands nowhere";
            case HubLayout.LOT_CLASH -> district + " claims the lot " + problem.lotId() + ", which '" + problem.holder()
                    + "' holds, so it stands nowhere; move one of them in mods/ziggfreedcommon/hub-districts.json";
            default -> district + " names no Lot.Id and does not fill empty lots, so it stands nowhere";
        };
    }

    @Nonnull
    private static List<AnchorRef> refs() {
        if (!anchorsFilled && WARNED_UNFILLED.compareAndSet(false, true)) {
            SafeLog.fine("[hub] the placements standing on hub spots are not handed to the hub audit here, so "
                    + "they go unchecked");
        }
        List<AnchorRef> refs = new ArrayList<>();
        try {
            Map<String, Map<String, String>> placements = placementAnchors.get();
            if (placements != null) {
                for (Map.Entry<String, Map<String, String>> entry : placements.entrySet()) {
                    Map<String, String> params = entry.getValue() == null ? Map.of() : entry.getValue();
                    refs.add(new AnchorRef(entry.getKey(), HubAnchors.param(params, HubAnchors.DISTRICT),
                            HubAnchors.param(params, HubAnchors.ANCHOR), HubAnchors.orArrival(params)));
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[hub] the placements standing on hub spots could not be read: " + t.getMessage());
        }
        return refs;
    }

    /** {@code test}'s answer; one that throws reads as yes, since cannot tell is not a finding. */
    private static boolean exists(@Nonnull Predicate<String> test, @Nonnull String value) {
        try {
            return test.test(value);
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean blockKnown(@Nonnull String id) {
        return BlockType.getAssetMap().getIndex(id) != AssetMapWithIndexes.NOT_FOUND;
    }
}
