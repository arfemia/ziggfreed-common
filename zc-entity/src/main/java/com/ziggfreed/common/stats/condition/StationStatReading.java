package com.ziggfreed.common.stats.condition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.HytaleFactors;
import com.ziggfreed.common.stats.MirroredStats;

/**
 * A stat as it reads at one station, whether or not its holder is working there (R958), and the share of it bound to
 * working there. Two factors, each {@code Param} the stat and the station joined by {@code @}
 * ({@code StationYield_Crafting@Sawmill}):
 * <ul>
 *   <li>{@value #FACTOR}: {@code hytale:stat}'s reading, less each bound share on the stat that is on now for ANOTHER
 *       station (its list names a {@link ZigWorkingAtCondition} that does not name this one), plus each bound share
 *       that is off now and would count here (its list names a {@code ZigWorkingAt} that counts at this station, its
 *       other conditions holding as the holder pass reads them), through {@link BoundGrants#onPoints} and
 *       {@link BoundGrants#offPoints}. Unbound modifiers (gear, effects) stay in. So at the work it reads exactly the
 *       stat, and away from it (a station's unattended pile paid to whoever gathers it, an order rolled when it is
 *       collected) what the stat would read at that station.</li>
 *   <li>{@value #SHARE_FACTOR}: only the bound shares that count at this station, on or off, and nothing else: what a
 *       station's talents add to a stat that gear raises too, for a roll that pays for the talents alone.</li>
 * </ul>
 * A share's {@code ZigWorkingAt} counts at the station when it names it and is not inverted. Away from the work its
 * session and piece knobs are taken as holding (there is no session to ask, and a station's talents count whatever the
 * moment, R958); while its holder works at a station the condition names, the holder pass has read the knobs live, so
 * a share it left off stays off and a roll there reads exactly the stat, whichever of the condition's stations the
 * {@code Param} names.
 *
 * <p>A stat its owner mirrors from a record ({@link MirroredStats}: a level) reads exactly that record at any station:
 * {@code hytale:stat} already answers the record, so no bound share is added or taken out, and its station share
 * reads 0. A grant bound to working at a station never lifts a mirror, as gear never does.
 *
 * <p>Contributed process-wide at setup ({@link #contribute}), so a station's loot table, a quest's reward and a gate
 * all resolve them. Unanswerable where {@code hytale:stat} is (no live subject, an unknown stat) and for a {@code Param}
 * naming no stat and station. A reading of the subject's own state, so their volatility is the default {@code Moment}.
 */
public final class StationStatReading {

    /** The factor reading a stat as it reads at a station. */
    public static final String FACTOR = "ziggfreedcommon:station_stat";

    /** The factor reading only the shares of a stat bound to working at a station. */
    public static final String SHARE_FACTOR = "ziggfreedcommon:station_share";

    /** Who the contributions are attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** Between the stat and the station in a {@code Param}. */
    public static final char SEPARATOR = '@';

    /** One parsed {@code Param}: the stat read and the station it is read at. */
    public record Param(@Nonnull String statId, @Nonnull String stationId) {
    }

    private StationStatReading() {
    }

    /** Claim both ids process-wide. Once, from setup. */
    public static void contribute() {
        FactorContributions.register(FACTOR, OWNER, StationStatReading::resolve);
        FactorContributions.register(SHARE_FACTOR, OWNER, StationStatReading::resolveShare);
    }

    /**
     * {@code raw} read as {@code <Stat>@<Station>}, each side trimmed; null when either side is blank or the separator
     * is missing or repeated.
     */
    @Nullable
    public static Param parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        int at = raw.indexOf(SEPARATOR);
        if (at < 0 || raw.indexOf(SEPARATOR, at + 1) >= 0) {
            return null;
        }
        String stat = raw.substring(0, at).trim();
        String station = raw.substring(at + 1).trim();
        return stat.isEmpty() || station.isEmpty() ? null : new Param(stat, station);
    }

    /**
     * What the entity at {@code ref} reads on {@code statId} at {@code stationId}, given the stat's own reading
     * {@code stat}: less the on shares bound to another station, plus the off shares that would count there; a
     * mirrored stat's own reading unchanged ({@link MirroredStats}). World thread.
     */
    public static double read(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            double stat, @Nonnull String statId, @Nonnull String stationId) {
        if (MirroredStats.claimOf(statId) != null) {
            return stat;
        }
        String working = StationWork.stationAt(accessor, ref);
        double elsewhere = BoundGrants.onPoints(ref, statId, condition -> boundElsewhere(condition, stationId));
        double away = BoundGrants.offPoints(accessor, ref, statId,
                condition -> countsAway(condition, stationId, working));
        return stat - elsewhere + away;
    }

    /**
     * What the shares of {@code statId} bound to working at {@code stationId} add for the entity at {@code ref}, on or
     * off: none of the stat's unbound modifiers; 0 on a mirrored stat ({@link MirroredStats}). World thread.
     */
    public static double share(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId, @Nonnull String stationId) {
        if (MirroredStats.claimOf(statId) != null) {
            return 0.0;
        }
        String working = StationWork.stationAt(accessor, ref);
        return BoundGrants.onPoints(ref, statId, condition -> countsAt(condition, stationId))
                + BoundGrants.offPoints(accessor, ref, statId, condition -> countsAway(condition, stationId, working));
    }

    /** Is {@code condition} a station condition a share counts under at {@code stationId}? */
    static boolean countsAt(@Nullable Condition condition, @Nonnull String stationId) {
        return condition instanceof ZigWorkingAtCondition working && working.countsAt(stationId);
    }

    /**
     * Does an off share under {@code condition} count at {@code stationId} away from the work: it counts there, and
     * its holder is not working now at a station the condition names ({@code working}, null for none). At such a
     * station the holder pass has read every knob live, so a share it left off (a piece too low, a session too short)
     * stays off.
     */
    static boolean countsAway(@Nullable Condition condition, @Nonnull String stationId, @Nullable String working) {
        return countsAt(condition, stationId)
                && (working == null || !((ZigWorkingAtCondition) condition).names(working));
    }

    /** Is {@code condition} a station condition binding a share to some other station than {@code stationId}? */
    static boolean boundElsewhere(@Nullable Condition condition, @Nonnull String stationId) {
        return condition instanceof ZigWorkingAtCondition working && working.bindsElsewhereThan(stationId);
    }

    /** {@value #FACTOR}'s reading; see the class javadoc. */
    @Nullable
    static Double resolve(@Nonnull FactorContext ctx) {
        Param param = parse(ctx.param());
        if (param == null) {
            return null;
        }
        Double stat = HytaleFactors.resolveStat(ctx.withParam(param.statId()));
        Store<EntityStore> store = ctx.store();
        Ref<EntityStore> subject = ctx.subject();
        if (stat == null || store == null || subject == null) {
            return stat;
        }
        return read(store, subject, stat, param.statId(), param.stationId());
    }

    /** {@value #SHARE_FACTOR}'s reading; see the class javadoc. */
    @Nullable
    static Double resolveShare(@Nonnull FactorContext ctx) {
        Param param = parse(ctx.param());
        if (param == null) {
            return null;
        }
        // Answerable exactly where hytale:stat is: a live subject and a loaded stat.
        Double stat = HytaleFactors.resolveStat(ctx.withParam(param.statId()));
        Store<EntityStore> store = ctx.store();
        Ref<EntityStore> subject = ctx.subject();
        if (stat == null || store == null || subject == null) {
            return null;
        }
        return share(store, subject, param.statId(), param.stationId());
    }
}
