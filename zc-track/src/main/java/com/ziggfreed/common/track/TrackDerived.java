package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.grant.Grant;
import com.ziggfreed.common.grant.Grants;
import com.ziggfreed.common.grant.Placed;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.track.stance.StanceView;
import com.ziggfreed.common.track.stance.Stances;
import com.ziggfreed.common.track.world.TrackWorldRules;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a player's tracks put on them: every grant of every pick they made, and each track's {@code Points.Grants}
 * once per point spent, written by one idempotent rebuild ({@link #rebuild}). Each grant is keyed
 * {@code <owner>:<track>:<pick>} ({@value #OWNER}, {@link #grantKey}; a track's points grants
 * {@code <owner>:<track>:}{@value #POINTS}), so a pick's grants are one modifier per stat however often it was
 * taken, and the rebuild takes off anything under
 * {@value #OWNER} it no longer wants, including what a pick switched off or a world rule took away left behind, but
 * the conditional stats' {@code zigtrack:@cond:} modifiers, which zc-entity's {@code ConditionalStats} writes under
 * this owner only so the 1.7.x rollback sweep clears them (R755; no track id holds an {@code @}).
 * A pick held grants while it is held: the rebuild never asks its {@code Requires} again, so a pick a prestige kept
 * keeps granting even when a branch's points no longer meet its gate (R641, R736). A pick in a stance grants only
 * while its face is held in one of the stance's slots ({@code stance.StanceView}), and each held face adds what its
 * stance kind adds while held under {@code zigtrack:<track>:<face>:held} ({@link #heldKey}). A pick whose id is
 * {@value #POINTS} would share its track's points key (the validator's {@code RESERVED_PICK_ID}).
 *
 * <p><b>When it runs</b>: at every world entry (the ready settle, so a world's {@code Grants} rules apply where the
 * player now stands, by each grant's kind and families, and leaving an arena gives the grants back), after every
 * pick, undo and respec
 * ({@code PickService}) and every prestige ({@code TrackService.prestige}), and for every online player after a
 * pick, option, track, stat or world rule fold ({@link #rebuildAllOnline}). Running it twice changes nothing.
 *
 * <p>The grants themselves are the native kinds a mod registered ({@code grant.Grants}): the library's
 * {@code Stat} writes native stat modifiers; resistances, outgoing damage and abilities are kinds later modules
 * register the same way. World thread.
 */
public final class TrackDerived {

    /** The holder every track grant is written under. */
    public static final String OWNER = "zigtrack";

    /** What a track's {@code Points.Grants} are written under, after the track: {@code zigtrack:<track>:points}. */
    public static final String POINTS = "points";

    /** What a held face's stance kind adds is written under, after the face: {@code zigtrack:<track>:<face>:held}. */
    public static final String HELD = "held";

    /** Whether a grant stays on: the world rules' answer for its kind and its families on its track. */
    @FunctionalInterface
    public interface GrantFilter {
        boolean keeps(@Nonnull Grant grant, @Nonnull String trackId, @Nullable String categoryId);
    }

    private TrackDerived() {
    }

    /** The key a pick's grants are written under: {@code zigtrack:<track>:<pick>}. */
    @Nonnull
    public static String grantKey(@Nonnull String trackId, @Nonnull String pickId) {
        return OWNER + ':' + TrackKey.normalize(trackId) + ':' + TrackKey.normalize(pickId);
    }

    /** Rebuild {@code playerId}'s grants now, when they are live. Their world thread. */
    public static void rebuild(@Nullable UUID playerId) {
        Ref<EntityStore> ref = PlayerAccess.liveRef(playerId);
        if (ref != null) {
            rebuild(ref.getStore(), ref);
        }
    }

    /** Rebuild the grants of the player at {@code ref} now. Their world thread. Never throws. */
    public static void rebuild(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return;
            }
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null) {
                return;
            }
            UUID id = player.getUuid();
            World world = store.getExternalData().getWorld();
            List<Placed> placed = placementsOf(id, (grant, trackId, categoryId) -> TrackWorldRules.grantsOn(world,
                    grant.kind(), grant.families(), trackId, categoryId));
            Grants.apply(store, ref, OWNER, placed);
        } catch (Throwable t) {
            SafeLog.warn("[track] a player's grants could not be rebuilt: " + t.getMessage());
        }
    }

    /** Queue a rebuild of the player at {@code ref} on their world thread. Any thread. */
    public static void rebuildLater(@Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return;
        }
        try {
            WorldEvictors.worldOf(ref).execute(() -> rebuild(ref.getStore(), ref));
        } catch (Throwable t) {
            // FINE: a stopping world refuses every task, and its players' grants are rebuilt where they land.
            SafeLog.fine("[track] could not queue a grant rebuild: " + t.getMessage());
        }
    }

    /** Queue a rebuild for every online player, each on their own world thread. Any thread. */
    public static void rebuildAllOnline() {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            for (PlayerRef player : universe.getPlayers()) {
                rebuildLater(player == null ? null : player.getReference());
            }
        } catch (Throwable t) {
            SafeLog.fine("[track] could not queue every online player's grant rebuild: " + t.getMessage());
        }
    }

    /**
     * The key what holding the face {@code faceId} on {@code trackId} adds (its stance kind's {@code WhileHeld}) is
     * written under: {@code zigtrack:<track>:<face>:held}, beside the face's own grants and never one of them (a pick
     * id holds no colon), under the owner so the rollback sweep clears it (R755) and the stat writer takes it off the
     * moment the face is released.
     */
    @Nonnull
    public static String heldKey(@Nonnull String trackId, @Nonnull String faceId) {
        return grantKey(trackId, faceId) + ':' + HELD;
    }

    /**
     * What the live player {@code playerId}'s picks grant now ({@link #placements(Map, Collection, Function,
     * GrantFilter, StanceView)} over their own records and the faces they hold, {@code Stances.view}), each kept only
     * where {@code filter} keeps it: what the rebuild writes, and what a consumer reading a player's whole build reads,
     * so both count exactly the same grants. Their world thread.
     */
    @Nonnull
    public static List<Placed> placementsOf(@Nonnull UUID playerId, @Nonnull GrantFilter filter) {
        return placements(TrackPicks.all(), TrackConfig.getInstance().liveTracks(),
                trackId -> {
                    TrackReading reading = TrackService.read(TrackKey.player(playerId, trackId));
                    return reading == null ? null : reading.record();
                }, filter, Stances.view(playerId));
    }

    /**
     * {@link #placements(Map, Collection, Function, GrantFilter, StanceView)} holding no face: every pick in a stance
     * places nothing, every other pick exactly as it always did.
     */
    @Nonnull
    public static List<Placed> placements(@Nonnull Map<String, TrackPicks.PickDef> picks,
            @Nonnull Collection<TrackAsset> tracks, @Nonnull Function<String, TrackRecord> records,
            @Nonnull GrantFilter filter) {
        return placements(picks, tracks, records, filter, StanceView.NONE);
    }

    /**
     * What a player's picks grant, before the writers see it (pure): for each folded pick, every chosen option's
     * grants once per slot made, or a bought pick's grants once per take (no more than its {@code Repeat.Max}), each
     * under {@link #grantKey}; and for each track writing {@code Points.Grants}, those grants once per point of its
     * pool spent anywhere (the main line, R736), under {@code zigtrack:<track>:points} ({@link #POINTS}). A pick in a
     * stance ({@code Stance}) places its grants only while its face counts in {@code held} (owned for good, live only
     * while held), and each face that counts places what its stance kind adds while held ({@code WhileHeld}) under
     * {@link #heldKey}. Each is kept only where {@code filter} keeps it. An option no longer offered by its group
     * (switched off, gone) grants nothing.
     *
     * @param picks   every folded pick, by id
     * @param tracks  every switched-on track (what {@code Points.Grants} is read from)
     * @param records each track's record by lower-cased id, or null when the player holds none there
     * @param held    the faces the player holds that count ({@link StanceView#NONE} for none)
     */
    @Nonnull
    public static List<Placed> placements(@Nonnull Map<String, TrackPicks.PickDef> picks,
            @Nonnull Collection<TrackAsset> tracks, @Nonnull Function<String, TrackRecord> records,
            @Nonnull GrantFilter filter, @Nonnull StanceView held) {
        List<Placed> out = new ArrayList<>();
        Map<String, TrackRecord> seen = new HashMap<>();
        Function<String, TrackRecord> recordOf = id -> seen.computeIfAbsent(id, key -> {
            TrackRecord kept = records.apply(key);
            return kept == null ? TrackRecord.EMPTY : kept;
        });
        for (TrackAsset track : tracks) {
            List<Grant> grants = track == null ? List.of() : track.pointsGrants();
            if (grants.isEmpty()) {
                continue;
            }
            String trackId = TrackKey.normalize(track.getId());
            int spent = TrackPicks.spentOn(trackId, picks.values(), recordOf);
            if (spent <= 0) {
                continue;
            }
            String key = grantKey(trackId, POINTS);
            for (Grant grant : grants) {
                if (filter.keeps(grant, trackId, track.category())) {
                    out.add(new Placed(key, grant, spent));
                }
            }
        }
        for (TrackPicks.PickDef pick : picks.values()) {
            if (!held.counts(pick)) {
                continue;
            }
            TrackRecord record = recordOf.apply(pick.trackId());
            PickState state = record.picks();
            if (state.isEmpty()) {
                continue;
            }
            String key = grantKey(pick.trackId(), pick.id());
            if (pick.isChoice()) {
                for (TrackPicks.GroupDef group : pick.groups()) {
                    String prefix = PickState.slotKey(pick.id(), group.key(), 0);
                    prefix = prefix.substring(0, prefix.length() - 1);
                    for (Map.Entry<String, List<String>> slot : state.slots().entrySet()) {
                        if (!slot.getKey().startsWith(prefix)) {
                            continue;
                        }
                        for (String optionId : slot.getValue()) {
                            TrackPicks.OptionDef option = group.option(optionId);
                            if (option == null) {
                                continue;
                            }
                            for (Grant grant : option.grants()) {
                                if (filter.keeps(grant, pick.trackId(), pick.categoryId())) {
                                    out.add(new Placed(key, grant, 1));
                                }
                            }
                        }
                    }
                }
            } else {
                int takes = Math.min(state.takesOf(pick.id()), pick.asset().repeatMax());
                if (takes <= 0) {
                    continue;
                }
                for (Grant grant : pick.asset().grants()) {
                    if (filter.keeps(grant, pick.trackId(), pick.categoryId())) {
                        out.add(new Placed(key, grant, takes));
                    }
                }
            }
        }
        for (StanceView.HeldFace face : held.faces()) {
            String key = heldKey(face.trackId(), face.faceId());
            for (Grant grant : face.whileHeld()) {
                if (filter.keeps(grant, face.trackId(), face.categoryId())) {
                    out.add(new Placed(key, grant, 1));
                }
            }
        }
        return out;
    }
}
