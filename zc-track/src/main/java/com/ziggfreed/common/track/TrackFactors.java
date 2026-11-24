package com.ziggfreed.common.track;

import java.util.UUID;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.FactorNames;
import com.ziggfreed.common.track.curve.CurveTable;
import com.ziggfreed.common.track.pick.PickService;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.track.stance.StanceText;
import com.ziggfreed.common.track.stance.StanceView;
import com.ziggfreed.common.track.stance.Stances;
import com.ziggfreed.common.track.stance.TrackStanceConfig;

/**
 * The track readings, contributed to every vocabulary on the server. {@code Param} names the track (its
 * id, any case), and for the tier reading the tier after a slash:
 * <ul>
 *   <li>{@value #LEVEL}: the best level ever reached, what a gate asks (a prestige never lowers it);</li>
 *   <li>{@value #CLIMB}: the current climb's level, which a prestige starts again: how a gate reads the
 *       current climb;</li>
 *   <li>{@value #TIER} (Param {@code <Track>/<Tier>}): 1 when the best level is at or above that tier's first
 *       level on the track's curve, else 0;</li>
 *   <li>{@value #PRESTIGE}: how many times the player prestiged the track;</li>
 *   <li>{@value #PICK} (Param {@code <Track>/<Pick or option>}): how many times the player took that pick or
 *       option on the track: every slot a choice made with it, every take of a pick bought outright, every slot of a
 *       choice pick made. What a line rank or a gate on a build reads (Edge rank III is 39 Edge picks). It counts what
 *       lives: a pick in a stance ({@code Stance}) only while its face is held, every other pick exactly as taken.</li>
 *   <li>{@value #PICK_OWNED} (the same Param): the same count owned for good, a pick in a stance counted whether its
 *       face is held or not.</li>
 *   <li>{@value #PICKS_TAKEN} (Param a pick id pattern, {@code *} matching any run of characters:
 *       {@code Your_*_Rank}): the owned count summed over every pick or option whose id matches, on every track the
 *       player climbs ({@code PickService.takenMatching}): ranks of a family bought anywhere, or any of a set of steps
 *       owned ({@code Min 1}).</li>
 *   <li>{@value #STANCE} (Param {@code <Stance>}, or {@code <Stance>/<Face>}): how many of the stance's faces the
 *       player holds that count now, or 1 while that face counts, else 0.</li>
 *   <li>{@value #STANCE_SLOTS} (Param {@code <Stance>}): how many of the stance's slots are open for the player, each
 *       row's {@code Requires} asked.</li>
 *   <li>{@value #BRANCH_SPENT} (Param {@code <Track>:<Branch>}): the points the player spent on that track's picks
 *       whose {@code Branch} names that branch, each pick's cost once per take (or slot made). What a tree's later
 *       row opens on (R736): {@code Min 5} for the second row.</li>
 * </ul>
 * Null (fail closed) for an unknown, switched-off or curve-less track, an unknown tier, or no live player; a
 * usable track the player never touched reads level 1 (and 0 picks). {@value #LEVEL} and {@value #CLIMB} alone also
 * read an alias a consumer registered ({@link TrackAliases}: a retired track's id, read as the best of its successors). Each reading changes only through the player's
 * own track writes, which move their gate epoch (a pick, its undo and a respec move it too), or a track, curve or
 * pick reload ({@code TrackPicks.invalidate}: a branch's points read the picks' {@code Branch} and {@code Cost}),
 * which moves the server epoch, so each declares {@code Volatility Player} in its Factors file and a verdict over it
 * is kept per player.
 */
public final class TrackFactors {

    public static final String OWNER = "ziggfreedcommon";

    public static final String LEVEL = "ziggfreedcommon:track_level";

    public static final String CLIMB = "ziggfreedcommon:track_climb";

    public static final String TIER = "ziggfreedcommon:track_tier";

    public static final String PRESTIGE = "ziggfreedcommon:track_prestige";

    public static final String PICK = "ziggfreedcommon:track_pick";

    public static final String PICK_OWNED = "ziggfreedcommon:track_pick_owned";

    public static final String PICKS_TAKEN = "ziggfreedcommon:track_picks_taken";

    public static final String STANCE = "ziggfreedcommon:track_stance";

    public static final String STANCE_SLOTS = "ziggfreedcommon:track_stance_slots";

    public static final String BRANCH_SPENT = "ziggfreedcommon:track_branch_spent";

    /** Between a branch reading's track and branch: {@code <Track>:<Branch>}. */
    public static final char BRANCH_SEPARATOR = ':';

    private TrackFactors() {
    }

    /** Claim the ten ids process-wide and word their lock lines. Once, at library setup. */
    public static void contribute() {
        FactorContributions.register(PICK, OWNER, TrackFactors::pick);
        FactorNames.registerParamNamer(PICK, TrackFactors::pickLockName);
        FactorContributions.register(PICK_OWNED, OWNER, TrackFactors::pickOwned);
        FactorNames.registerParamNamer(PICK_OWNED, TrackFactors::pickLockName);
        FactorContributions.register(PICKS_TAKEN, OWNER, TrackFactors::picksTaken);
        FactorNames.registerParamNamer(PICKS_TAKEN, TrackFactors::picksTakenLockName);
        FactorContributions.register(STANCE, OWNER, TrackFactors::stance);
        FactorNames.registerParamNamer(STANCE, TrackFactors::stanceLockName);
        FactorContributions.register(STANCE_SLOTS, OWNER, TrackFactors::stanceSlots);
        FactorNames.registerParamNamer(STANCE_SLOTS, TrackFactors::stanceSlotsLockName);
        FactorContributions.register(BRANCH_SPENT, OWNER, TrackFactors::branchSpent);
        FactorNames.registerParamNamer(BRANCH_SPENT, TrackFactors::branchLockName);
        FactorContributions.register(LEVEL, OWNER, ctx -> {
            TrackReading reading = gateReading(ctx, ctx.param(), TrackReading::best);
            return reading == null ? null : (double) reading.best();
        });
        FactorContributions.register(CLIMB, OWNER, ctx -> {
            TrackReading reading = gateReading(ctx, ctx.param(), TrackReading::level);
            return reading == null ? null : (double) reading.level();
        });
        FactorContributions.register(PRESTIGE, OWNER, ctx -> {
            TrackReading reading = reading(ctx, ctx.param());
            return reading == null ? null : (double) reading.prestige();
        });
        FactorContributions.register(TIER, OWNER, TrackFactors::tier);
        FactorNames.registerParamNamer(LEVEL, param -> trackLine("factor.level_of", param));
        FactorNames.registerParamNamer(CLIMB, param -> trackLine("factor.climb_of", param));
        FactorNames.registerParamNamer(PRESTIGE, param -> trackLine("factor.prestige_of", param));
        FactorNames.registerParamNamer(TIER, TrackFactors::tierLockName);
    }

    @Nullable
    static Double tier(@Nonnull FactorContext ctx) {
        String[] parts = split(ctx.param());
        if (parts == null) {
            return null;
        }
        TrackReading reading = reading(ctx, parts[0]);
        CurveTable.Tier tier = reading == null ? null : reading.def().table().tier(parts[1]);
        return tier == null ? null : reading.best() >= tier.from() ? 1.0 : 0.0;
    }

    /**
     * How many times the asking player took the pick or option {@code <Track>/<Id>} names, counting only what lives
     * ({@code TrackPicks.liveCount}: a pick in no stance exactly as taken, one in a stance only while its face is
     * held); null for no track.
     */
    @Nullable
    static Double pick(@Nonnull FactorContext ctx) {
        String[] parts = split(ctx.param());
        if (parts == null) {
            return null;
        }
        TrackReading reading = reading(ctx, parts[0]);
        return reading == null ? null : (double) TrackPicks.liveCount(TrackPicks.onTrack(reading.def().id()),
                reading.record().picks(), parts[1], Stances.counts(playerOf(ctx.store(), ctx.subject())));
    }

    /**
     * How many times the asking player took the pick or option {@code <Track>/<Id>} names, owned for good (a pick in
     * a stance counted whether its face is held or not); null for no track.
     */
    @Nullable
    static Double pickOwned(@Nonnull FactorContext ctx) {
        String[] parts = split(ctx.param());
        if (parts == null) {
            return null;
        }
        TrackReading reading = reading(ctx, parts[0]);
        return reading == null ? null : (double) reading.record().picks().count(parts[1]);
    }

    /**
     * How many takes the asking player owns, on every track, of every pick or option whose id matches the Param's
     * pattern ({@code PickService.takenMatching}); null for a blank Param or no player.
     */
    @Nullable
    static Double picksTaken(@Nonnull FactorContext ctx) {
        String param = ctx.param();
        UUID playerId = playerOf(ctx.store(), ctx.subject());
        if (playerId == null || param == null || param.isBlank()) {
            return null;
        }
        return (double) PickService.takenMatching(playerId, param);
    }

    /**
     * What a lock line on a picks-taken pattern calls the gate: the pick's own name for a pattern naming one pick
     * ({@code factor.pick_of}); null, so the generic line reads, for a pattern with a {@code *}.
     */
    @Nullable
    static Message picksTakenLockName(@Nonnull String param) {
        return param.isBlank() || param.indexOf('*') >= 0 ? null
                : TrackText.line("factor.pick_of", TrackText.pickName(param.trim()));
    }

    /**
     * The asking player's faces held in a stance: {@code <Stance>} reads how many of its faces count now,
     * {@code <Stance>/<Face>} 1 while that face counts, else 0; null for a stance unknown or off, or no player.
     */
    @Nullable
    static Double stance(@Nonnull FactorContext ctx) {
        String param = ctx.param();
        UUID playerId = playerOf(ctx.store(), ctx.subject());
        if (playerId == null || param == null || param.isBlank()) {
            return null;
        }
        String[] parts = split(param);
        String stanceId = parts == null ? param.trim() : parts[0];
        if (TrackStanceConfig.getInstance().live(stanceId) == null) {
            return null;
        }
        StanceView view = Stances.view(playerId);
        if (parts == null) {
            return (double) view.faces(stanceId).size();
        }
        for (StanceView.HeldFace face : view.faces(stanceId)) {
            if (face.faceId().equals(TrackKey.normalize(parts[1]))) {
                return 1.0;
            }
        }
        return 0.0;
    }

    /** How many of the stance {@code <Stance>}'s slots are open for the asking player; null when it cannot answer. */
    @Nullable
    static Double stanceSlots(@Nonnull FactorContext ctx) {
        UUID playerId = playerOf(ctx.store(), ctx.subject());
        Integer open = playerId == null ? null : Stances.slotsOpen(playerId, ctx.param());
        return open == null ? null : (double) open;
    }

    /**
     * What a lock line on a stance reading calls the gate: {@code <Stance>} the faces held in it
     * ({@code stance.factor.held_in}), {@code <Stance>/<Face>} that face held ({@code stance.factor.holding}). Null,
     * so the generic line reads, when the stance is unknown.
     */
    @Nullable
    static Message stanceLockName(@Nonnull String param) {
        String[] parts = split(param);
        Message stance = StanceText.nameOf(parts == null ? param : parts[0]);
        if (stance == null) {
            return null;
        }
        return parts == null ? StanceText.line("factor.held_in", stance)
                : StanceText.line("factor.holding", TrackText.pickName(parts[1]), stance);
    }

    /** What a lock line on {@code <Stance>}'s open slots calls the gate; null when the stance is unknown. */
    @Nullable
    static Message stanceSlotsLockName(@Nonnull String param) {
        Message stance = StanceText.nameOf(param);
        return stance == null ? null : StanceText.line("factor.slots_in", stance);
    }

    /**
     * What a lock line on {@code <Track>/<Id>} calls the gate: the pick's or option's own name ({@code factor.pick_of}).
     * Null, so the generic line reads, when the track is unknown.
     */
    @Nullable
    static Message pickLockName(@Nonnull String param) {
        String[] parts = split(param);
        TrackDef def = parts == null ? null : TrackService.def(parts[0]);
        return def == null ? null : TrackText.line("factor.pick_of", TrackText.pickName(parts[1]));
    }

    /**
     * The points the asking player spent in the branch {@code <Track>:<Branch>} names on that track's tree; null for
     * no track (a branch no pick names reads 0).
     */
    @Nullable
    static Double branchSpent(@Nonnull FactorContext ctx) {
        String[] parts = splitBranch(ctx.param());
        if (parts == null) {
            return null;
        }
        TrackReading reading = reading(ctx, parts[0]);
        return reading == null ? null : (double) TrackPicks.branchSpent(TrackPicks.onTrack(reading.def().id()),
                reading.record().picks(), parts[1]);
    }

    /**
     * What a lock line on {@code <Track>:<Branch>} calls the gate: the points in that branch, the branch named by
     * its track's {@code Branches} ({@code factor.branch_points}). Null, so the generic line reads, when the track is
     * unknown.
     */
    @Nullable
    static Message branchLockName(@Nonnull String param) {
        String[] parts = splitBranch(param);
        TrackDef def = parts == null ? null : TrackService.def(parts[0]);
        return def == null ? null : TrackText.line("factor.branch_points", TrackText.branchName(def.asset(), parts[1]));
    }

    /** {@code <Track>:<Branch>} as its two halves, or null when it is not that shape. */
    @Nullable
    static String[] splitBranch(@Nullable String param) {
        if (param == null) {
            return null;
        }
        int cut = param.lastIndexOf(BRANCH_SEPARATOR);
        if (cut <= 0 || cut == param.length() - 1) {
            return null;
        }
        return new String[] {param.substring(0, cut).trim(), param.substring(cut + 1).trim()};
    }

    /**
     * The climb a level or climb gate on {@code trackId} reads: the track's, else, for an alias
     * ({@link TrackAliases}), the best of its tracks by {@code rank}; null when neither reads.
     */
    @Nullable
    private static TrackReading gateReading(@Nonnull FactorContext ctx, @Nullable String trackId,
            @Nonnull ToIntFunction<TrackReading> rank) {
        if (TrackAliases.isAlias(trackId)) {
            UUID playerId = playerOf(ctx.store(), ctx.subject());
            return playerId == null ? null : TrackAliases.best(playerId, trackId, rank);
        }
        return reading(ctx, trackId);
    }

    /** The asking player's climb on {@code trackId}, read live, or null. */
    @Nullable
    private static TrackReading reading(@Nonnull FactorContext ctx, @Nullable String trackId) {
        UUID playerId = playerOf(ctx.store(), ctx.subject());
        if (playerId == null || trackId == null || trackId.isBlank()) {
            return null;
        }
        return TrackService.read(TrackKey.player(playerId, trackId.trim()));
    }

    /** The live player {@code ref} is, by id, or null for no player; also how a mirrored stat finds its climbs. */
    @Nullable
    static UUID playerOf(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (store == null || ref == null || !ref.isValid()) {
            return null;
        }
        try {
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            return player == null ? null : player.getUuid();
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code <Track>/<Tier>} as its two halves, or null when it is not that shape. */
    @Nullable
    private static String[] split(@Nullable String param) {
        if (param == null) {
            return null;
        }
        int slash = param.lastIndexOf('/');
        if (slash <= 0 || slash == param.length() - 1) {
            return null;
        }
        return new String[] {param.substring(0, slash).trim(), param.substring(slash + 1).trim()};
    }

    /**
     * A lock line's name for a reading on the track {@code param} names (an alias's for an alias, {@link TrackAliases}),
     * or null when no track has that id.
     */
    @Nullable
    private static Message trackLine(@Nonnull String key, @Nonnull String param) {
        Message name = TrackText.nameOf(param);
        if (name == null) {
            name = TrackAliases.name(param);
        }
        return name == null ? null : TrackText.line(key, name);
    }

    /**
     * What a lock line on {@code <Track>/<Tier>} calls the gate: the tier's name on that track
     * ({@code factor.tier_in}). Null, so the generic line reads, when the track or the tier is unknown.
     */
    @Nullable
    static Message tierLockName(@Nonnull String param) {
        String[] parts = split(param);
        TrackDef def = parts == null ? null : TrackService.def(parts[0]);
        CurveTable.Tier tier = def == null ? null : def.table().tier(parts[1]);
        return tier == null ? null
                : TrackText.line("factor.tier_in", TrackText.tierName(tier), TrackText.name(def.asset()));
    }
}
