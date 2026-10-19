package com.ziggfreed.common.track;

import java.util.UUID;

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

/**
 * The four track readings, contributed to every vocabulary on the server. {@code Param} names the track (its
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
 *       choice pick made. What a line rank or a gate on a build reads (Edge rank III is 39 Edge picks).</li>
 * </ul>
 * Null (fail closed) for an unknown, switched-off or curve-less track, an unknown tier, or no live player; a
 * usable track the player never touched reads level 1 (and 0 picks). Each reading changes only through the player's
 * own track writes, which move their gate epoch (a pick, its undo and a respec move it too), or a track or curve
 * reload, which moves the server epoch, so each declares {@code Volatility Player} in its Factors file and a verdict
 * over it is kept per player.
 */
public final class TrackFactors {

    public static final String OWNER = "ziggfreedcommon";

    public static final String LEVEL = "ziggfreedcommon:track_level";

    public static final String CLIMB = "ziggfreedcommon:track_climb";

    public static final String TIER = "ziggfreedcommon:track_tier";

    public static final String PRESTIGE = "ziggfreedcommon:track_prestige";

    public static final String PICK = "ziggfreedcommon:track_pick";

    private TrackFactors() {
    }

    /** Claim the five ids process-wide and word their lock lines. Once, at library setup. */
    public static void contribute() {
        FactorContributions.register(PICK, OWNER, TrackFactors::pick);
        FactorNames.registerParamNamer(PICK, TrackFactors::pickLockName);
        FactorContributions.register(LEVEL, OWNER, ctx -> {
            TrackReading reading = reading(ctx, ctx.param());
            return reading == null ? null : (double) reading.best();
        });
        FactorContributions.register(CLIMB, OWNER, ctx -> {
            TrackReading reading = reading(ctx, ctx.param());
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

    /** How many times the asking player took the pick or option {@code <Track>/<Id>} names; null for no track. */
    @Nullable
    static Double pick(@Nonnull FactorContext ctx) {
        String[] parts = split(ctx.param());
        if (parts == null) {
            return null;
        }
        TrackReading reading = reading(ctx, parts[0]);
        return reading == null ? null : (double) reading.record().picks().count(parts[1]);
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

    /** The asking player's climb on {@code trackId}, read live, or null. */
    @Nullable
    private static TrackReading reading(@Nonnull FactorContext ctx, @Nullable String trackId) {
        UUID playerId = playerOf(ctx.store(), ctx.subject());
        if (playerId == null || trackId == null || trackId.isBlank()) {
            return null;
        }
        return TrackService.read(TrackKey.player(playerId, trackId.trim()));
    }

    @Nullable
    private static UUID playerOf(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
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

    /** A lock line's name for a reading on the track {@code param} names, or null when no track has that id. */
    @Nullable
    private static Message trackLine(@Nonnull String key, @Nonnull String param) {
        Message name = TrackText.nameOf(param);
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
