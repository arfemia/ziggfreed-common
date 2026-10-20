package com.ziggfreed.common.objectives.trainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementOverrides;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one read of which placements are trainers (R608): a trainer is an NPC placement carrying a {@code Trainer}
 * block, loaded and switched on (its own {@code Enabled} and the owner's switch in
 * {@code mods/ziggfreedcommon/npc-placements.json}). Its season and {@code Requires} are where it stands, not
 * whether it teaches, so a trainer standing only in winter keeps its picks at its page all year. What this answers
 * is also zc-track's {@code PickTrainers} seam: a pick whose named trainers none teach is learned on its track's page.
 */
public final class Trainers {

    private Trainers() {
    }

    /**
     * The placement {@code placementId} names (any case) when it is a trainer on this server, else null. For a
     * surface opening the page: a store that cannot be read is logged and opens nothing.
     */
    @Nullable
    public static NpcPlacementAsset trainer(@Nullable String placementId) {
        try {
            return lookup(placementId);
        } catch (Throwable t) {
            SafeLog.warn("[trainer] could not read the placement '" + placementId + "': " + t.getMessage());
            return null;
        }
    }

    /**
     * Whether {@code placementId} is a trainer on this server: the {@code PickTrainers} seam's answer. False only
     * for a placement that is no trainer; a store that cannot be read throws through to the seam, which keeps the
     * pick at its trainer rather than moving it to the track's page.
     */
    public static boolean teaches(@Nonnull String placementId) {
        return lookup(placementId) != null;
    }

    /** {@link #trainer} unguarded: null only for "not a trainer here"; a read that fails throws. */
    @Nullable
    private static NpcPlacementAsset lookup(@Nullable String placementId) {
        if (placementId == null || placementId.isBlank()) {
            return null;
        }
        NpcPlacementAsset placement = NpcPlacementConfig.getInstance().resolve(placementId.trim());
        if (placement == null || !placement.isTrainer() || !placement.isEnabled()) {
            return null;
        }
        return NpcPlacementOverrides.getInstance().isEnabled(placement.getId()) ? placement : null;
    }

    /**
     * Which trainer a moment means, in the one order every opener reads: the placement an author named (a
     * destination's or an interaction's {@code Placement}), else the placement the moment came from (the NPC pressed
     * or aimed at), else the first trainer placement standing the character a conversation is with. Null when none
     * of them is a trainer.
     */
    @Nullable
    public static String resolve(@Nullable String authored, @Nullable String fromPlacement, @Nullable String npcId) {
        if (authored != null && !authored.isBlank()) {
            return trainer(authored) != null ? lower(authored) : null;
        }
        if (trainer(fromPlacement) != null) {
            return lower(fromPlacement);
        }
        if (npcId == null || npcId.isBlank()) {
            return null;
        }
        for (String placement : NpcIdentities.placementsForNpcId(npcId)) {
            if (trainer(placement) != null) {
                return lower(placement);
            }
        }
        return null;
    }

    /**
     * The usable tracks {@code trainer} teaches, once each: every track {@code Tracks} names, in that order, then
     * every track in a category {@code Categories} names, by the tracks' own order. A name no usable track answers
     * to is passed over.
     */
    @Nonnull
    public static List<TrackDef> tracksTaught(@Nonnull NpcPlacementAsset.Trainer trainer) {
        Map<String, TrackDef> out = new LinkedHashMap<>();
        for (String id : trainer.tracks()) {
            TrackDef def = TrackService.def(id);
            if (def != null) {
                out.putIfAbsent(def.id(), def);
            }
        }
        List<String> categories = new ArrayList<>();
        for (String category : trainer.categories()) {
            categories.add(lower(category));
        }
        if (!categories.isEmpty()) {
            for (TrackDef def : TrackService.defs()) {
                String category = def.asset().category();
                if (category != null && categories.contains(lower(category))) {
                    out.putIfAbsent(def.id(), def);
                }
            }
        }
        return List.copyOf(out.values());
    }

    /** The character standing at {@code placementId}: its {@code Identity.NpcId}, else its role; null for neither. */
    @Nullable
    public static String npcOf(@Nonnull String placementId) {
        try {
            return NpcIdentities.npcIdOfPlacement(placementId);
        } catch (Throwable t) {
            SafeLog.fine("[trainer] could not read who stands at '" + placementId + "': " + t.getMessage());
            return null;
        }
    }

    @Nonnull
    private static String lower(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
