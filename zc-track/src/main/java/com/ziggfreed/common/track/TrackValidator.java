package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.gate.GateRuntime;
import com.ziggfreed.common.progress.gate.GateValidator;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackCategoryConfig;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.track.asset.TrackCurveAsset;
import com.ziggfreed.common.track.asset.TrackCurveConfig;
import com.ziggfreed.common.track.curve.CurveTable;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.BootAudit;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The content audit over tracks and their curves, filed under the {@value #DOMAIN} domain. Split the way every
 * validator in this family is: {@link #audit()} is the engine walk (it never throws), and
 * {@link #audit(Collection, Collection, Predicate, Predicate, Predicate)} the pure core. {@link #logErrorsOnce()}
 * logs the ERRORs once at boot, unless the process asked for zc's boot audit, whose {@value #LOG_LABEL} pass
 * prints every finding, so an error prints once either way. A switched-off track is skipped, except for its id.
 *
 * <ul>
 *   <li>ERROR {@link #RESERVED_CHARACTER} (a track id with {@code @}: such a track is never usable),
 *       {@link #NO_CURVE}, {@link #PIECE_SHAPE} (a piece with both or neither of Hybrid and Floors),
 *       {@link #PIECE_WITHOUT_TO}, {@link #FLOORS_NOT_ASCENDING}, {@link #TOO_MANY_LEVELS};</li>
 *   <li>WARNING {@link #UNKNOWN_CURVE}, {@link #UNKNOWN_CATEGORY}, {@link #UNKNOWN_MIRROR_STAT},
 *       {@link #UNKNOWN_REWARD_KIND} (an unknown id is a warning: its owner may register later or be a mod this
 *       server does not run), {@link #UNLOCK_WITHOUT_LEVEL}, {@link #UNLOCK_WITHOUT_REWARDS},
 *       {@link #DUPLICATE_UNLOCK_KEY} (two rows of one track known by one key: they share one record),
 *       {@link #PRESTIGE_OUT_OF_REACH}, {@link #CAP_PAST_CURVE}, {@link #TIER_PAST_TOP}, and the gate audit's
 *       findings over a track's {@code Requires}.</li>
 * </ul>
 */
public final class TrackValidator {

    public static final String DOMAIN = "track";

    /** The label this audit's lines carry, in zc's boot audit. */
    public static final String LOG_LABEL = "[track] audit";

    public static final String RESERVED_CHARACTER = "RESERVED_CHARACTER";
    public static final String NO_CURVE = "NO_CURVE";
    public static final String UNKNOWN_CURVE = "UNKNOWN_CURVE";
    public static final String UNKNOWN_CATEGORY = "UNKNOWN_CATEGORY";
    public static final String UNKNOWN_MIRROR_STAT = "UNKNOWN_MIRROR_STAT";
    public static final String UNKNOWN_REWARD_KIND = "UNKNOWN_REWARD_KIND";
    public static final String UNLOCK_WITHOUT_LEVEL = "UNLOCK_WITHOUT_LEVEL";
    public static final String UNLOCK_WITHOUT_REWARDS = "UNLOCK_WITHOUT_REWARDS";
    public static final String PRESTIGE_OUT_OF_REACH = "PRESTIGE_OUT_OF_REACH";
    public static final String CAP_PAST_CURVE = "CAP_PAST_CURVE";
    public static final String PIECE_SHAPE = "PIECE_SHAPE";
    public static final String PIECE_WITHOUT_TO = "PIECE_WITHOUT_TO";
    public static final String FLOORS_NOT_ASCENDING = "FLOORS_NOT_ASCENDING";
    public static final String TOO_MANY_LEVELS = "TOO_MANY_LEVELS";
    public static final String TIER_PAST_TOP = "TIER_PAST_TOP";
    public static final String DUPLICATE_UNLOCK_KEY = "DUPLICATE_UNLOCK_KEY";

    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private TrackValidator() {
    }

    /** The engine walk over every folded track and curve. Never throws. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            List<Finding> out = new ArrayList<>(audit(TrackConfig.getInstance().all().values(),
                    TrackCurveConfig.getInstance().all().values(),
                    id -> TrackCategoryConfig.getInstance().category(id) != null,
                    id -> StatIndexCache.resolve(id) != StatIndexCache.UNRESOLVED,
                    kind -> RewardKinds.shared().isRegistered(kind)));
            for (Map.Entry<String, TrackAsset> entry : TrackConfig.getInstance().all().entrySet()) {
                if (entry.getValue().isEnabled()) {
                    out.addAll(GateValidator.validate(entry.getValue().requires(), DOMAIN, entry.getKey(), "track",
                            GateRuntime.kinds(), GateRuntime.vocabulary()::isRegistered, null));
                }
            }
            return out;
        } catch (Throwable t) {
            SafeLog.warn("[track] the track audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /**
     * The pure core.
     *
     * @param categoryKnown whether a category id names a loaded category
     * @param statKnown     whether a stat channel id is registered
     * @param kindKnown     whether a reward kind id is registered
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<TrackAsset> tracks, @Nonnull Collection<TrackCurveAsset> curves,
            @Nonnull Predicate<String> categoryKnown, @Nonnull Predicate<String> statKnown,
            @Nonnull Predicate<String> kindKnown) {
        List<Finding> out = new ArrayList<>();
        Map<String, TrackCurveAsset> byId = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (TrackCurveAsset curve : curves) {
            if (curve != null && curve.getId() != null) {
                byId.put(curve.getId(), curve);
                auditCurve(curve, out);
            }
        }
        Function<String, CurveTable> tableOf = id -> {
            TrackCurveAsset curve = id == null ? null : byId.get(id);
            return curve == null ? null : curve.table();
        };
        for (TrackAsset track : tracks) {
            if (track != null && track.getId() != null) {
                auditTrack(track, tableOf, categoryKnown, statKnown, kindKnown, out);
            }
        }
        return out;
    }

    private static void auditTrack(@Nonnull TrackAsset track, @Nonnull Function<String, CurveTable> tableOf,
            @Nonnull Predicate<String> categoryKnown, @Nonnull Predicate<String> statKnown,
            @Nonnull Predicate<String> kindKnown, @Nonnull List<Finding> out) {
        String id = track.getId();
        if (!TrackAsset.usableId(id)) {
            out.add(Finding.error(DOMAIN, RESERVED_CHARACTER, "track '" + id + "' has '" + TrackAsset.RUN_SEPARATOR
                    + "' in its id, which a seasonal run's key reserves, so the track is never usable; rename the "
                    + "file", id));
            return;
        }
        if (!track.isEnabled()) {
            return;
        }
        String curveId = track.curveId();
        CurveTable table = null;
        if (curveId == null) {
            out.add(Finding.error(DOMAIN, NO_CURVE, "track '" + id + "' names no Curve, so it has no levels and "
                    + "answers nothing", id));
        } else {
            table = tableOf.apply(curveId);
            if (table == null) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_CURVE, "track '" + id + "' names the curve '" + curveId
                        + "', which no loaded file defines, so the track answers nothing until it loads", id));
            }
        }
        String category = track.category();
        if (category != null && !categoryKnown.test(category)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_CATEGORY, "track '" + id + "' names the category '" + category
                    + "', which no loaded TrackCategories file describes, so its prestige build defaults to Keep",
                    id));
        }
        String stat = track.mirrorStat();
        if (stat != null && !statKnown.test(stat)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_MIRROR_STAT, "track '" + id + "' mirrors its level onto the "
                    + "stat '" + stat + "', which is not registered here, so nothing is written", id));
        }
        if (table != null) {
            Integer cap = track.cap();
            if (cap != null && cap > table.top() && table.every() <= 0) {
                out.add(Finding.warning(DOMAIN, CAP_PAST_CURVE, "track '" + id + "' caps at level " + cap
                        + ", past its curve's top level " + table.top() + " with no Beyond, so the top is "
                        + table.top(), id));
            }
            int reachable = cap != null ? Math.min(cap, table.every() > 0 ? cap : table.top())
                    : (table.every() > 0 ? Integer.MAX_VALUE : table.top());
            if (track.prestiges() && track.prestigeLevel(1) > reachable) {
                out.add(Finding.warning(DOMAIN, PRESTIGE_OUT_OF_REACH, "track '" + id + "' prestiges first at level "
                        + track.prestigeLevel(1) + ", above the highest level it reaches (" + reachable
                        + "), so nobody can prestige; lower Prestige.Base", id));
            }
        }
        List<TrackAsset.Unlock> rows = track.unlocks();
        Map<String, Integer> firstByKey = new TreeMap<>();
        for (int row = 0; row < rows.size(); row++) {
            TrackAsset.Unlock unlock = rows.get(row);
            String key = unlock.key();
            String where = "track '" + id + "' unlock row " + row + " ('" + key + "')";
            Integer first = firstByKey.putIfAbsent(key, row);
            if (first != null) {
                out.add(Finding.warning(DOMAIN, DUPLICATE_UNLOCK_KEY, where + " is known by the same name as row "
                        + first + ", so the two share one record and this one pays no level the first has passed; "
                        + "give one of them its own Id", id));
            }
            if (!unlock.namesALevel()) {
                out.add(Finding.warning(DOMAIN, UNLOCK_WITHOUT_LEVEL, where + " names no At level and no Every, so "
                        + "it never pays", id));
            }
            RewardEntryAsset[] entries = unlock.presentEntries();
            if (entries.length == 0) {
                out.add(Finding.warning(DOMAIN, UNLOCK_WITHOUT_REWARDS, where + " pays no Rewards", id));
            }
            for (RewardEntryAsset entry : entries) {
                String kind = entry == null ? null : entry.getKind();
                if (kind != null && !kind.isBlank() && !kindKnown.test(kind.trim())) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_REWARD_KIND, where + " pays the reward kind '"
                            + kind.trim() + "', which nothing registered here, so that reward is left out of "
                            + "its Claims", id));
                }
            }
        }
    }

    private static void auditCurve(@Nonnull TrackCurveAsset curve, @Nonnull List<Finding> out) {
        String id = curve.getId();
        long levels = 0;
        long previous = Long.MIN_VALUE;
        List<TrackCurveAsset.Piece> pieces = curve.pieces();
        for (int i = 0; i < pieces.size(); i++) {
            TrackCurveAsset.Piece piece = pieces.get(i);
            String where = "curve '" + id + "' piece " + i;
            if (piece.hasHybrid() == piece.hasFloors()) {
                out.add(Finding.error(DOMAIN, PIECE_SHAPE, where + " writes " + (piece.hasHybrid() ? "both Hybrid "
                        + "and Floors (Floors is read)" : "neither Hybrid nor Floors, so it adds no levels"), id));
            }
            if (piece.hasFloors()) {
                for (long floor : piece.floorsOrEmpty()) {
                    if (floor < previous) {
                        out.add(Finding.error(DOMAIN, FLOORS_NOT_ASCENDING, where + " lists " + floor + " after "
                                + previous + "; floors climb, so it is raised to " + previous, id));
                    }
                    previous = Math.max(previous, floor);
                    levels++;
                }
            } else if (piece.hasHybrid()) {
                if (piece.to() == null) {
                    out.add(Finding.error(DOMAIN, PIECE_WITHOUT_TO, where + " is a Hybrid piece with no To level, "
                            + "so it adds no levels", id));
                } else {
                    levels = Math.max(levels, piece.to());
                    // A Floors piece after this one starts from the formula's top, which only the table knows.
                    previous = Long.MIN_VALUE;
                }
            }
        }
        if (levels > CurveTable.MAX_LEVELS) {
            out.add(Finding.error(DOMAIN, TOO_MANY_LEVELS, "curve '" + id + "' runs to " + levels + " levels; only "
                    + CurveTable.MAX_LEVELS + " are tabulated", id));
        }
        CurveTable table = curve.table();
        for (CurveTable.Tier tier : table.tiers()) {
            if (tier.from() > table.top() && table.every() <= 0) {
                out.add(Finding.warning(DOMAIN, TIER_PAST_TOP, "curve '" + id + "' starts the tier '" + tier.id()
                        + "' at level " + tier.from() + ", past its top level " + table.top()
                        + " with no Beyond, so nobody reaches it", id));
            }
        }
    }

    /**
     * Log every ERROR of the engine walk, once per boot, unless the process asked for zc's boot audit: its
     * {@value #LOG_LABEL} pass prints them then, with the warnings, so no error prints twice.
     */
    public static void logErrorsOnce() {
        if (BootAudit.asked() || !LOGGED.compareAndSet(false, true)) {
            return;
        }
        for (Finding finding : audit()) {
            if (finding.severity() == Severity.ERROR) {
                SafeLog.warn("[track] " + finding.code() + " (" + finding.sourceId() + "): " + finding.message());
            }
        }
    }
}
