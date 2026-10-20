package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.grant.Grant;
import com.ziggfreed.common.grant.StatGrant;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateRuntime;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.GateValidator;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackCategoryConfig;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.track.asset.TrackCurveAsset;
import com.ziggfreed.common.track.asset.TrackCurveConfig;
import com.ziggfreed.common.track.boost.BoostValidator;
import com.ziggfreed.common.track.curve.CurveTable;
import com.ziggfreed.common.track.gain.GainFactors;
import com.ziggfreed.common.track.pick.TrackPickAsset;
import com.ziggfreed.common.track.pick.TrackPickConfig;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.track.source.SourceRow;
import com.ziggfreed.common.track.source.TrackSourceAsset;
import com.ziggfreed.common.track.source.TrackSourceConfig;
import com.ziggfreed.common.track.world.TrackRules;
import com.ziggfreed.common.track.world.TrackWorldRuleAsset;
import com.ziggfreed.common.track.world.TrackWorldRuleConfig;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.BootAudit;
import com.ziggfreed.common.world.WhereValidator;
import com.ziggfreed.common.world.WorldSelector;
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
 *       findings over a track's {@code Requires};</li>
 *   <li>WARNING, for growth ({@link #auditGrowth}): {@link #UNKNOWN_GAIN_FACTOR}, {@link #SOURCE_WITHOUT_TRACK},
 *       {@link #UNKNOWN_SOURCE_TRACK}, {@link #SOURCE_WITHOUT_KIND}, {@link #UNKNOWN_SOURCE_KIND},
 *       {@link #SOURCE_PAYS_NOTHING}, {@link #RULE_WITHOUT_WHERE}, {@link #UNKNOWN_RULE_TRACK},
 *       {@link #DUPLICATE_RULE_WHERE} (two rule files naming the same worlds: one shadows the other),
 *       {@link #CONFLICTING_RULE_TRACK} (one id both allowed and turned off: it stays off), the gate audit over a
 *       source's {@code Requires} and the {@code Where} audit over a world rule file's ({@link #auditWorldRules}
 *       is the rules' part alone, for a consumer's own audit);</li>
 *   <li>for picks: the fold's own findings ({@code TrackPicks}: ERROR {@code UNRESOLVED_PICK_TOKEN} and
 *       {@code UNREADABLE_PICK_GRANTS}, an option left out; WARNING {@code PICK_WITHOUT_TRACK},
 *       {@code UNKNOWN_PICK_TRACK}, {@code UNKNOWN_PICK_OPTION}, {@code UNKNOWN_PICK_DECK},
 *       {@code GROUP_WITHOUT_LEVEL}, {@code GROUP_WITHOUT_OPTIONS}), then WARNING
 *       {@link #UNKNOWN_PICK_PREREQUISITE}, {@link #UNKNOWN_COST_TRACK}, {@link #COST_WITHOUT_POINTS},
 *       {@link #UNKNOWN_GRANT_STAT} ({@link #auditPicks}; also over a track's {@code Points.Grants}) and the gate
 *       audit over a pick's {@code Requires};</li>
 *   <li>WARNING, for trees ({@link #auditTrees}): {@link #UNDECLARED_BRANCH}, {@link #NODE_ACROSS_TRACKS},
 *       {@link #UNKNOWN_GATE_BRANCH}, {@link #UNKNOWN_STARTER_PICK}, {@link #RESERVED_PICK_ID}.</li>
 *   <li>The boosts' own audit ({@code boost.BoostValidator}, its {@code boost} domain), run inside this one so the
 *       boot audit and the once-at-boot error log carry it.</li>
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
    public static final String UNKNOWN_GAIN_FACTOR = "UNKNOWN_GAIN_FACTOR";
    public static final String SOURCE_WITHOUT_TRACK = "SOURCE_WITHOUT_TRACK";
    public static final String UNKNOWN_SOURCE_TRACK = "UNKNOWN_SOURCE_TRACK";
    public static final String SOURCE_WITHOUT_KIND = "SOURCE_WITHOUT_KIND";
    public static final String UNKNOWN_SOURCE_KIND = "UNKNOWN_SOURCE_KIND";
    public static final String SOURCE_PAYS_NOTHING = "SOURCE_PAYS_NOTHING";
    public static final String RULE_WITHOUT_WHERE = "RULE_WITHOUT_WHERE";
    public static final String UNKNOWN_RULE_TRACK = "UNKNOWN_RULE_TRACK";
    public static final String DUPLICATE_RULE_WHERE = "DUPLICATE_RULE_WHERE";
    public static final String CONFLICTING_RULE_TRACK = "CONFLICTING_RULE_TRACK";
    public static final String UNKNOWN_PICK_PREREQUISITE = "UNKNOWN_PICK_PREREQUISITE";
    public static final String UNKNOWN_COST_TRACK = "UNKNOWN_COST_TRACK";
    public static final String COST_WITHOUT_POINTS = "COST_WITHOUT_POINTS";
    public static final String UNKNOWN_GRANT_STAT = "UNKNOWN_GRANT_STAT";
    public static final String UNDECLARED_BRANCH = "UNDECLARED_BRANCH";
    public static final String NODE_ACROSS_TRACKS = "NODE_ACROSS_TRACKS";
    public static final String UNKNOWN_GATE_BRANCH = "UNKNOWN_GATE_BRANCH";
    public static final String UNKNOWN_STARTER_PICK = "UNKNOWN_STARTER_PICK";
    public static final String RESERVED_PICK_ID = "RESERVED_PICK_ID";

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
            Predicate<String> trackKnown = id -> TrackConfig.getInstance().live(id) != null;
            Predicate<String> categoryKnown = id -> TrackCategoryConfig.getInstance().category(id) != null;
            out.addAll(auditGrowth(TrackConfig.getInstance().all().values(),
                    TrackSourceConfig.getInstance().all().values(), TrackWorldRuleConfig.getInstance().all().values(),
                    trackKnown, categoryKnown, ProgressionRuntime.objectiveKinds()::isRegistered,
                    GainFactors::isRegistered));
            for (Map.Entry<String, TrackSourceAsset> entry : TrackSourceConfig.getInstance().all().entrySet()) {
                if (entry.getValue().isEnabled()) {
                    out.addAll(GateValidator.validate(entry.getValue().requires(), DOMAIN, entry.getKey(),
                            "track source", GateRuntime.kinds(), GateRuntime.vocabulary()::isRegistered, null));
                }
            }
            for (Map.Entry<String, TrackWorldRuleAsset> entry : TrackWorldRuleConfig.getInstance().all().entrySet()) {
                if (entry.getValue().isEnabled()) {
                    out.addAll(WhereValidator.validateSelector(entry.getValue().where(), entry.getKey() + ".Where"));
                }
            }
            out.addAll(TrackPicks.issues());
            out.addAll(auditPicks(TrackPicks.all().values(),
                    id -> {
                        TrackAsset track = TrackConfig.getInstance().live(id);
                        return track == null ? -1 : track.pointsPerLevel();
                    },
                    id -> StatIndexCache.resolve(id) != StatIndexCache.UNRESOLVED));
            out.addAll(auditTrees(TrackConfig.getInstance().liveTracks(), TrackPicks.all().values(), id -> {
                TrackPickAsset file = TrackPickConfig.getInstance().resolve(id);
                return file != null && !file.isEnabled();
            }));
            for (Map.Entry<String, TrackPickAsset> entry : TrackPickConfig.getInstance().all().entrySet()) {
                if (entry.getValue().isEnabled()) {
                    out.addAll(GateValidator.validate(entry.getValue().requires(), DOMAIN, entry.getKey(),
                            "track pick", GateRuntime.kinds(), GateRuntime.vocabulary()::isRegistered, null));
                }
            }
            out.addAll(BoostValidator.audit());
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
        for (Grant grant : track.pointsGrants()) {
            if (grant instanceof StatGrant granted && granted.stat() != null && !statKnown.test(granted.stat())) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_GRANT_STAT, "track '" + id + "' grants the stat '"
                        + granted.stat() + "' for each point spent, which is not registered here, so that grant "
                        + "raises nothing", id));
            }
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

    /**
     * The pure core of growth's audit: each track's own gain factors, each enabled source and each enabled world
     * rule file. Every finding is a WARNING: an unknown id may belong to a mod that registers later or does not
     * run here.
     *
     * @param trackKnown    whether an id names a switched-on track
     * @param categoryKnown whether an id names a loaded category
     * @param kindKnown     whether an objective kind id is registered
     * @param factorKnown   whether a gain factor id is registered
     */
    @Nonnull
    public static List<Finding> auditGrowth(@Nonnull Collection<TrackAsset> tracks,
            @Nonnull Collection<TrackSourceAsset> sources, @Nonnull Collection<TrackWorldRuleAsset> rules,
            @Nonnull Predicate<String> trackKnown, @Nonnull Predicate<String> categoryKnown,
            @Nonnull Predicate<String> kindKnown, @Nonnull Predicate<String> factorKnown) {
        List<Finding> out = new ArrayList<>();
        for (TrackAsset track : tracks) {
            if (track == null || !track.isEnabled() || !track.writesGainFactors()) {
                continue;
            }
            for (String factor : track.gainFactors()) {
                if (!factorKnown.test(factor)) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_GAIN_FACTOR, "track '" + track.getId() + "' runs the "
                            + "gain factor '" + factor + "', which nothing registered here, so it changes nothing",
                            track.getId()));
                }
            }
        }
        for (TrackSourceAsset source : sources) {
            if (source != null && source.getId() != null && source.isEnabled()) {
                auditSource(source, trackKnown, kindKnown, out);
            }
        }
        out.addAll(auditWorldRules(rules, trackKnown, categoryKnown));
        return out;
    }

    /**
     * The world rules' part of growth's audit, over the enabled rule files: one naming no world
     * ({@link #RULE_WITHOUT_WHERE}), a list or entry naming neither a track nor a category
     * ({@link #UNKNOWN_RULE_TRACK}), two naming the very same worlds, of which only the first by id ever applies to
     * them ({@link #DUPLICATE_RULE_WHERE}), and an id both allowed and turned off, which stays off
     * ({@link #CONFLICTING_RULE_TRACK}). Every finding is a WARNING. A consumer whose own climbs are no track
     * (a skill no track file names) passes a {@code trackKnown} that knows them too.
     */
    @Nonnull
    public static List<Finding> auditWorldRules(@Nonnull Collection<TrackWorldRuleAsset> rules,
            @Nonnull Predicate<String> trackKnown, @Nonnull Predicate<String> categoryKnown) {
        List<Finding> out = new ArrayList<>();
        List<TrackWorldRuleAsset> sorted = new ArrayList<>();
        for (TrackWorldRuleAsset rule : rules) {
            if (rule != null && rule.getId() != null && rule.isEnabled()) {
                sorted.add(rule);
            }
        }
        sorted.sort(Comparator.comparing(rule -> rule.getId().toLowerCase(Locale.ROOT)));
        Map<String, String> seenWhere = new HashMap<>();
        for (TrackWorldRuleAsset rule : sorted) {
            auditRule(rule, trackKnown, categoryKnown, out);
            WorldSelector where = rule.where();
            if (where == null || where.hasNoPositiveAxis()) {
                continue;
            }
            String prior = seenWhere.putIfAbsent(whereKey(where), rule.getId());
            if (prior != null) {
                out.add(Finding.warning(DOMAIN, DUPLICATE_RULE_WHERE, "track world rule '" + rule.getId()
                        + "' names the same worlds as '" + prior + "', so only '" + prior + "' applies there",
                        rule.getId()));
            }
        }
        return out;
    }

    /** One selector's axes as a comparable key: each axis lower-cased and sorted, since its entries are OR'd. */
    @Nonnull
    private static String whereKey(@Nonnull WorldSelector where) {
        return "match=" + axisKey(where.getMatch()) + "|config=" + axisKey(where.getGameplayConfig())
                + "|placement=" + axisKey(where.getPlacement()) + "|exclude=" + axisKey(where.getExcludeMatch());
    }

    @Nonnull
    private static String axisKey(@Nullable String[] values) {
        if (values == null) {
            return "";
        }
        TreeSet<String> out = new TreeSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return String.join(",", out);
    }

    private static void auditSource(@Nonnull TrackSourceAsset source, @Nonnull Predicate<String> trackKnown,
            @Nonnull Predicate<String> kindKnown, @Nonnull List<Finding> out) {
        String id = source.getId();
        String where = "track source '" + id + "'";
        String track = source.trackId();
        if (track == null) {
            out.add(Finding.warning(DOMAIN, SOURCE_WITHOUT_TRACK, where + " names no Track, so it pays nothing", id));
        } else if (!trackKnown.test(track)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_SOURCE_TRACK, where + " pays onto the track '" + track
                    + "', which is not switched on here, so it pays nothing", id));
        }
        String kind = source.when() == null ? null : source.when().kindId();
        if (kind == null) {
            out.add(Finding.warning(DOMAIN, SOURCE_WITHOUT_KIND, where + " names no When.Kind, so no moment pays",
                    id));
        } else if (!kindKnown.test(kind)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_SOURCE_KIND, where + " answers the moment '" + kind
                    + "', which no objective kind registered here is, so it pays nothing until one is", id));
        }
        boolean pays = source.baseRow() != null && source.baseRow().pays();
        for (SourceRow row : source.rows().values()) {
            pays |= row != null && row.pays();
        }
        if (!pays) {
            out.add(Finding.warning(DOMAIN, SOURCE_PAYS_NOTHING, where + " writes no Xp or PerAmount above 0, "
                    + "so it pays nothing (a row of 0 only stops a broader pattern)", id));
        }
    }

    private static void auditRule(@Nonnull TrackWorldRuleAsset rule, @Nonnull Predicate<String> trackKnown,
            @Nonnull Predicate<String> categoryKnown, @Nonnull List<Finding> out) {
        String id = rule.getId();
        if (rule.where() == null || rule.where().hasNoPositiveAxis()) {
            out.add(Finding.warning(DOMAIN, RULE_WITHOUT_WHERE, "track world rule '" + id + "' names no world in "
                    + "its Where, so it applies nowhere", id));
        }
        TrackRules rules = rule.rules();
        TrackRules.GainRules gain = rules == null ? null : rules.gain();
        if (gain == null) {
            return;
        }
        List<String> named = new ArrayList<>(List.of(gain.enabledTracks()));
        named.addAll(List.of(gain.disabledTracks()));
        named.addAll(gain.tracks().keySet());
        for (String name : named) {
            if (name != null && !name.isBlank() && !trackKnown.test(name.trim()) && !categoryKnown.test(name.trim())) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_RULE_TRACK, "track world rule '" + id + "' names '"
                        + name.trim() + "', which is neither a switched-on track nor a category here", id));
            }
        }
        Set<String> allowed = new HashSet<>();
        for (String name : gain.enabledTracks()) {
            if (name != null && !name.isBlank()) {
                allowed.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        Set<String> reported = new HashSet<>();
        for (String name : gain.disabledTracks()) {
            String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty() && allowed.contains(key) && reported.add(key)) {
                out.add(Finding.warning(DOMAIN, CONFLICTING_RULE_TRACK, "track world rule '" + id + "' lists '"
                        + name.trim() + "' in both EnabledTracks and DisabledTracks, so it stays off", id));
            }
        }
    }

    /**
     * The pure core of the picks' audit, over the folded picks (the fold reports its own findings): a prerequisite
     * naming no pick or option on the same track, a cost priced in a track not switched on or one that earns no
     * points, and a {@code Stat} grant naming a stat this server does not have. Every finding is a WARNING.
     *
     * @param pointsPerLevel a switched-on track's {@code Points.PerLevel}, or -1 when the id names none
     * @param statKnown      whether a native stat id is registered
     */
    @Nonnull
    public static List<Finding> auditPicks(@Nonnull Collection<TrackPicks.PickDef> picks,
            @Nonnull ToIntFunction<String> pointsPerLevel, @Nonnull Predicate<String> statKnown) {
        List<Finding> out = new ArrayList<>();
        for (TrackPicks.PickDef pick : picks) {
            TrackPickAsset asset = pick.asset();
            String id = asset.getId();
            String where = "track pick '" + id + "'";
            for (String needed : asset.prerequisites()) {
                if (!TrackPicks.knownOnTrack(pick.trackId(), needed)) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_PICK_PREREQUISITE, where + " needs '" + needed
                            + "' first, which is no pick or option on its track, so it never opens", id));
                }
            }
            if (asset.costPoints() > 0) {
                String costTrack = asset.costTrack();
                int perLevel = costTrack == null ? -1 : pointsPerLevel.applyAsInt(costTrack);
                if (perLevel < 0) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_COST_TRACK, where + " is priced in the points of '"
                            + costTrack + "', which is not a switched-on track here, so nobody can pay for it", id));
                } else if (perLevel == 0) {
                    out.add(Finding.warning(DOMAIN, COST_WITHOUT_POINTS, where + " is priced in the points of '"
                            + costTrack + "', which earns none (no Points.PerLevel), so nobody can pay for it", id));
                }
            }
            List<Grant> grants = new ArrayList<>(asset.grants());
            for (TrackPicks.GroupDef group : pick.groups()) {
                for (TrackPicks.OptionDef option : group.options().values()) {
                    grants.addAll(option.grants());
                }
            }
            for (Grant grant : grants) {
                if (grant instanceof StatGrant stat && stat.stat() != null && !statKnown.test(stat.stat())) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_GRANT_STAT, where + " grants the stat '" + stat.stat()
                            + "', which is not registered here, so that grant raises nothing", id));
                }
            }
        }
        return out;
    }

    /**
     * The pure core of the trees' audit, over the switched-on tracks and the folded picks: a pick's {@code Branch}
     * its track's {@code Branches} does not declare ({@link #UNDECLARED_BRANCH}), a {@code Node} written by picks on
     * different tracks, which never lock each other ({@link #NODE_ACROSS_TRACKS}), a branch gate
     * ({@code ziggfreedcommon:track_branch_spent}) naming a branch no pick on its track sits in, which never opens
     * ({@link #UNKNOWN_GATE_BRANCH}), a {@code Starter} entry that is no pick bought outright on its track
     * ({@link #UNKNOWN_STARTER_PICK}; one naming a pick switched off is passed over quietly), and a pick whose id
     * is {@value TrackDerived#POINTS} in any case, whose grants would be written under its track's
     * {@code Points.Grants} key {@code zigtrack:<track>:points} ({@link #RESERVED_PICK_ID}). Every finding is a
     * WARNING.
     *
     * @param switchedOff whether a pick id names a pick file that is switched off
     */
    @Nonnull
    public static List<Finding> auditTrees(@Nonnull Collection<TrackAsset> tracks,
            @Nonnull Collection<TrackPicks.PickDef> picks, @Nonnull Predicate<String> switchedOff) {
        List<Finding> out = new ArrayList<>();
        Map<String, TrackAsset> byId = new TreeMap<>();
        for (TrackAsset track : tracks) {
            if (track != null && track.getId() != null) {
                byId.put(TrackKey.normalize(track.getId()), track);
            }
        }
        Map<String, Set<String>> branchesInUse = new TreeMap<>();
        Map<String, Set<String>> nodeTracks = new TreeMap<>();
        Map<String, Set<String>> boughtOn = new TreeMap<>();
        for (TrackPicks.PickDef pick : picks) {
            if (!pick.isChoice()) {
                boughtOn.computeIfAbsent(pick.trackId(), k -> new TreeSet<>()).add(pick.id());
            }
            if (TrackDerived.POINTS.equals(pick.id())) {
                out.add(Finding.warning(DOMAIN, RESERVED_PICK_ID, "track pick '" + pick.asset().getId() + "' has "
                        + "the id its track's Points.Grants are written under (" + TrackDerived.grantKey(
                        pick.trackId(), TrackDerived.POINTS) + "), so the two would overwrite each other's grants; "
                        + "give the pick another id", pick.asset().getId()));
            }
            String branch = pick.asset().branch();
            if (branch != null) {
                branchesInUse.computeIfAbsent(pick.trackId(), k -> new TreeSet<>()).add(branch);
                TrackAsset track = byId.get(pick.trackId());
                if (track != null && track.branch(branch) == null) {
                    out.add(Finding.warning(DOMAIN, UNDECLARED_BRANCH, "track pick '" + pick.asset().getId()
                            + "' sits in the branch '" + branch + "', which its track '" + track.getId()
                            + "' does not declare in Branches, so the branch is named by its id", pick.asset().getId()));
                }
            }
            String node = pick.asset().node();
            if (node != null) {
                nodeTracks.computeIfAbsent(node, k -> new TreeSet<>()).add(pick.trackId());
            }
        }
        nodeTracks.forEach((node, onTracks) -> {
            if (onTracks.size() > 1) {
                out.add(Finding.warning(DOMAIN, NODE_ACROSS_TRACKS, "the choice node '" + node + "' has faces on "
                        + "the tracks " + String.join(", ", onTracks) + "; a node joins picks on one track only, so "
                        + "faces on different tracks never lock each other", node));
            }
        });
        for (TrackPicks.PickDef pick : picks) {
            GateSpec requires = pick.asset().requires();
            if (requires == null) {
                continue;
            }
            for (String param : branchParams(requires)) {
                String[] parts = TrackFactors.splitBranch(param);
                if (parts == null) {
                    continue;
                }
                String trackId = TrackKey.normalize(parts[0]);
                String branch = TrackKey.normalize(parts[1]);
                if (byId.containsKey(trackId) && !branchesInUse.getOrDefault(trackId, Set.of()).contains(branch)) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_GATE_BRANCH, "track pick '" + pick.asset().getId()
                            + "' opens on the points spent in the branch '" + parts[1] + "' of '" + parts[0]
                            + "', where no pick sits, so it never opens", pick.asset().getId()));
                }
            }
        }
        for (TrackAsset track : byId.values()) {
            String trackId = TrackKey.normalize(track.getId());
            Set<String> reported = new TreeSet<>();
            for (String entry : track.starter()) {
                if (!boughtOn.getOrDefault(trackId, Set.of()).contains(entry) && !switchedOff.test(entry)
                        && reported.add(entry)) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_STARTER_PICK, "track '" + track.getId() + "' starts its "
                            + "starter build with '" + entry + "', which is no pick bought outright on it, so that "
                            + "step is passed over", track.getId()));
                }
            }
        }
        return out;
    }

    /** Every Param a {@code ziggfreedcommon:track_branch_spent} condition anywhere in {@code requires} names. */
    @Nonnull
    private static List<String> branchParams(@Nonnull GateSpec requires) {
        List<String> out = new ArrayList<>();
        List<GateClause> clauses = new ArrayList<>();
        clauses.add(requires);
        clauses.addAll(List.of(requires.allOfOrEmpty()));
        clauses.addAll(List.of(requires.anyOfOrEmpty()));
        clauses.addAll(List.of(requires.notOrEmpty()));
        for (GateClause clause : clauses) {
            if (clause == null) {
                continue;
            }
            for (FactorCondition condition : clause.factorsOrEmpty()) {
                if (condition != null && TrackFactors.BRANCH_SPENT.equalsIgnoreCase(
                        String.valueOf(condition.getFactor()).trim()) && condition.getParam() != null) {
                    out.add(condition.getParam());
                }
            }
        }
        return out;
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
