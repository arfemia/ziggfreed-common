package com.ziggfreed.common.reputation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackRecord;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.track.asset.TrackCurveAsset;
import com.ziggfreed.common.track.asset.TrackCurveConfig;
import com.ziggfreed.common.track.asset.TrackOwnerLayers;
import com.ziggfreed.common.track.curve.CurveTable;
import com.ziggfreed.common.track.event.ZigTrackChangedEvent;
import com.ziggfreed.common.track.gain.GainFactors;
import com.ziggfreed.common.util.SafeLog;

/**
 * Each reputation as a track (SK4, R602): a player's standing with a switched-on reputation is their climb on
 * the track of the same id, and its curve is the reputation's own ladder, derived here from the native ranks,
 * the native group and the companion file, never authored twice.
 * <ul>
 *   <li>The ladder's floors are the curve's one {@code Floors} piece, so level n is the nth rank from the bottom
 *       (Hated is level 1, Exalted level 7 on the shipped ladder, a reputation's own ranks above it after), and
 *       each rank is a tier of the same id, named as the reputation names it.</li>
 *   <li>Its {@code Beyond.Every} is the curve's Beyond, so each payout step past the top rank is one level past
 *       the top. A reputation with no Beyond still climbs past its top rank, with no level past it
 *       ({@link #OPEN_TOP}).</li>
 *   <li>The track sits in the {@value #CATEGORY} category and prestiges only where the reputation's own file writes
 *       a {@code Prestige} group (R852, R643; {@link #prestigeOf}), its {@code Base} one below the top rank's level
 *       unless written, so the first prestige asks the top rank and each one after it one level more; a file with
 *       no group gives a track whose Prestige group allows no rank, which an owner may still turn on in
 *       {@code tracks.json}. A prestiged standing earns nothing on top of a gain (the track's default
 *       {@code GainPercent} 0, so a quest's reputation lands as written) unless the group writes one.</li>
 * </ul>
 * The tracks and curves are provided to the track stores under {@value #OWNER} ({@link #refresh}), at boot and
 * after every companion fold, so a pack's file and the owner's word still outrank them.
 *
 * <p>The rest is the pure arithmetic the ledger and the import share: what a climb reads as
 * ({@link #reading}), the standing the engine's copy holds ({@link #held}, {@link #copyOf}) and today's save as a
 * climb ({@link #imported}).
 */
public final class ReputationTracks {

    /** Who provides the reputations' tracks and curves to the track stores. */
    public static final String OWNER = "ziggfreedcommon:reputation";

    /** The category every reputation's track sits in ({@code TrackCategories/Reputation.json}). */
    public static final String CATEGORY = "Reputation";

    /** A reputation's curve is filed under this, then the reputation's id. */
    public static final String CURVE_PREFIX = "Reputation_";

    /**
     * The mark on a reputation's climb saying through which level its Beyond payouts are parked or paid: the top
     * rank's level plus every Beyond step already parked, what the import carries over from the player's
     * reputation record (R692) and every parked payout raises. No unlock row's key holds a dot, so it never meets
     * one.
     */
    public static final String BEYOND_ROW = "reputation.beyond";

    /**
     * The Beyond step of a reputation that pays nothing past its top: more standing than the engine ever keeps,
     * so the climb goes on past the top rank (as standing always has) with no level past it.
     */
    public static final long OPEN_TOP = Integer.MAX_VALUE;

    /** The lower-cased ids of the tracks provided now. */
    private static volatile Set<String> provided = Set.of();

    private ReputationTracks() {
    }

    // ==================== the tracks ====================

    /**
     * Derive every switched-on reputation's track and curve from what {@code service} reads now and hand them to
     * the track stores, replacing the last set; then read the owner's {@code tracks.json} again, so an entry there
     * (an owner turning prestige on) retunes a reputation's track leaf by leaf. A reputation whose ladder has
     * fewer than two ranks gets none, and keeps its standing in the engine's own value. Each track's prestige comes
     * from its reputation's own file ({@link #prestigeOf}).
     */
    public static void refresh(@Nonnull ReputationService service) {
        Map<String, TrackAsset> tracks = new LinkedHashMap<>();
        Map<String, TrackCurveAsset> curves = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        List<String> known = new ArrayList<>();
        for (ReputationDef def : service.all()) {
            known.add(def.id());
            ReputationLadder ladder = service.ladderFor(def);
            if (!ladder.usable() || !TrackAsset.usableId(def.id())) {
                continue;
            }
            String curveId = curveId(def);
            curves.put(curveId, curveOf(curveId, def, ladder));
            tracks.put(def.id(), trackOf(def, curveId, ladder));
            ids.add(def.id().trim().toLowerCase(Locale.ROOT));
        }
        ReputationGain.registerRetiredAliases(known);
        TrackCurveConfig.getInstance().provide(OWNER, curves);
        TrackConfig.getInstance().provide(OWNER, tracks);
        Set<String> before = provided;
        provided = Set.copyOf(ids);
        TrackOwnerLayers.reload();
        if (!provided.equals(before)) {
            SafeLog.info("[reputation] " + provided.size() + " reputation(s) keep their standing on a track: "
                    + provided.stream().sorted().toList());
        }
    }

    /** {@link #refresh} against the production service. */
    public static void refresh() {
        try {
            refresh(ReputationRuntime.service());
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the reputations' tracks could not be built: " + t.getMessage(), t);
        }
    }

    /** At boot, once every native rank and group has loaded: the tracks again, and the per-world warning. */
    public static void onBoot(@Nonnull BootEvent event) {
        refresh();
        TrackReputationLedger.warnPerWorldOnce(ReputationRuntime.service().engine());
    }

    /** A climb moved: when it is a reputation's, tell the service (a move it made itself it already told). */
    public static void onTrackChanged(@Nonnull ZigTrackChangedEvent event) {
        try {
            ReputationRuntime.service().trackChanged(event);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] a reputation's track move could not be followed: " + t.getMessage());
        }
    }

    /** Is {@code trackId} (any case) a reputation's track this module provides now? */
    public static boolean provides(@Nullable String trackId) {
        return trackId != null && provided.contains(trackId.trim().toLowerCase(Locale.ROOT));
    }

    /** {@code def}'s track as the track stores answer it now, or null when none is provided or usable. */
    @Nullable
    public static TrackDef track(@Nonnull ReputationDef def) {
        return provides(def.id()) ? TrackService.def(def.id()) : null;
    }

    /** {@code player}'s climb on {@code def}'s track. */
    @Nonnull
    public static TrackKey key(@Nonnull UUID player, @Nonnull ReputationDef def) {
        return TrackKey.player(player, def.id());
    }

    /** The id {@code def}'s curve is filed under. */
    @Nonnull
    public static String curveId(@Nonnull ReputationDef def) {
        return CURVE_PREFIX + def.id();
    }

    /**
     * The gain factors a reputation's track runs: the world's rules, the boosts, the prestige extra and the rested
     * pool, never the library's {@code Bonus}, since {@code ReputationService.change} already raises a gain by the
     * reputation's own gain stats ({@code CategoryGain_Reputation} and {@code TrackGain_<Id>}, {@link ReputationGain})
     * before the Cap, so a Bonus XP meal (the all-track {@code TrackGain}) speeds skills and not standing. An owner who
     * adds Bonus back in {@code tracks.json} adds {@code TrackGain} and counts the reputation's own stats a second time.
     */
    public static final List<String> GAIN_FACTORS = List.of(GainFactors.WORLD, GainFactors.BOOST, GainFactors.PRESTIGE,
            GainFactors.RESTED);

    /**
     * {@code def}'s track: its name, picture and place, its curve, the category, {@link #GAIN_FACTORS}, and its
     * prestige ({@link #prestigeOf}).
     */
    @Nonnull
    static TrackAsset trackOf(@Nonnull ReputationDef def, @Nonnull String curveId, @Nonnull ReputationLadder ladder) {
        String title = def.titleKey();
        String flavor = def.flavorKey();
        ContentTextAsset text = title == null && flavor == null ? null
                : ContentTextAsset.of(title == null ? null : ContentKeys.resolved(title),
                        flavor == null ? null : ContentKeys.resolved(flavor), null);
        return TrackAsset.of(def.id(), text, def.icon(), def.order(), CATEGORY, curveId, prestigeOf(def, ladder))
                .withGainFactors(GAIN_FACTORS);
    }

    /**
     * {@code def}'s Prestige group as its track carries it (R852, R643): the reputation file's own {@code Prestige}
     * group, its {@code Base} defaulting to one below the top rank's level so the first prestige asks the top rank;
     * with no group written, prestige off (no rank allowed), with the same Base, so an owner who turns it on in
     * {@code tracks.json} asks the top rank too. Every other leaf reads as written, else the track's default (no
     * extra after a prestige, the Resets defaults).
     */
    @Nonnull
    static TrackAsset.Prestige prestigeOf(@Nonnull ReputationDef def, @Nonnull ReputationLadder ladder) {
        int base = Math.max(0, ladder.ranks().size() - 1);
        TrackAsset.Prestige own = def.prestige();
        return own == null ? TrackAsset.Prestige.of(0, null, base, null) : own.withDefaults(null, base);
    }

    /** {@code def}'s ladder as a curve: its floors, its Beyond step, a tier per rank. */
    @Nonnull
    public static TrackCurveAsset curveOf(@Nonnull String curveId, @Nonnull ReputationDef def,
            @Nonnull ReputationLadder ladder) {
        List<ReputationLadder.Rank> ranks = ladder.ranks();
        long[] floors = new long[ranks.size()];
        Map<String, TrackCurveAsset.Tier> tiers = new LinkedHashMap<>();
        for (int i = 0; i < ranks.size(); i++) {
            ReputationLadder.Rank rank = ranks.get(i);
            floors[i] = rank.min();
            tiers.put(rank.id(), TrackCurveAsset.Tier.of(i + 1, tierNameKey(def, rank)));
        }
        long every = def.beyondEvery() > 0 ? def.beyondEvery() : OPEN_TOP;
        return TrackCurveAsset.of(curveId, new TrackCurveAsset.Piece[] {TrackCurveAsset.Piece.floors(floors)},
                every, tiers);
    }

    /** What {@code def} calls {@code rank}, as a key: its own name, else the library's word, else none. */
    @Nullable
    static String tierNameKey(@Nonnull ReputationDef def, @Nonnull ReputationLadder.Rank rank) {
        String authored = def.rankNameKey(rank.id());
        if (authored != null) {
            return ContentKeys.resolved(authored);
        }
        String own = rank.id().trim().toLowerCase(Locale.ROOT);
        return ReputationText.LADDER_IDS.contains(own) ? ReputationText.PREFIX + "rank." + own : null;
    }

    /**
     * Raise {@code player}'s Beyond mark on {@code def}'s track to the level {@code highest} earned standing
     * stands at, when that is past the top: what a parked payout calls, so the climb knows every step parked.
     * Nothing for a reputation with no track or no Beyond. The mark is a level read with the Every of this moment,
     * so a later Every reads it as another standing (the module router's limit for the page).
     */
    static void markBeyond(@Nonnull UUID player, @Nonnull ReputationDef def, long highest) {
        TrackDef track = track(def);
        if (track == null || def.beyondEvery() <= 0) {
            return;
        }
        int level = track.table().levelOf(highest);
        if (level > track.table().top()) {
            TrackService.markPaidThrough(key(player, def), List.of(BEYOND_ROW), level);
        }
    }

    // ==================== the arithmetic ====================

    /**
     * The standing every rank reads on a climb whose current standing is {@code value}: the value itself, or,
     * once the climb was prestiged, at least the floor of the best level it ever reached, so a prestige keeps
     * every unlock and NPC attitude the best rank opened (R641).
     */
    public static long held(@Nonnull TrackRecord record, long value, @Nonnull CurveTable table) {
        if (record.prestige() <= 0 || record.best() <= 0) {
            return value;
        }
        return Math.max(value, table.floorOf(record.best()));
    }

    /**
     * What the engine's copy holds for a climb standing at {@code held}: held inside the engine's own bounds, the
     * ladder's bottom floor to one below its top's ceiling, as the engine clamps.
     */
    public static long copyOf(long held, @Nonnull ReputationLadder ladder) {
        ReputationLadder.Rank bottom = ladder.bottom();
        ReputationLadder.Rank top = ladder.top();
        if (bottom == null || top == null) {
            return held;
        }
        return Math.max(bottom.min(), Math.min((long) top.max() - 1L, held));
    }

    /**
     * What {@code record} reads as: its value (the reputation's starting value while nothing is on record), the
     * standing its ranks read ({@link #held}), and, when {@code copy} is the engine's value now (null where no
     * copy is kept), whatever moved the copy since it was last written, which is an outside write (the admin
     * {@code /reputation}, a native objective's reward) not yet folded into the track: readings see it at once,
     * and the ledger folds it in at the next change or world entry.
     */
    @Nonnull
    public static ReputationLedger.Reading reading(@Nonnull TrackRecord record, @Nonnull CurveTable table,
            int initial, @Nullable Integer copy, boolean entry, @Nonnull ReputationLadder ladder) {
        long value = record.isEmpty() ? initial : record.value();
        long held = held(record, value, table);
        long drift = copy == null ? 0L : copy - copyOf(held, ladder);
        return new ReputationLedger.Reading(toInt(value + drift), toInt(held + drift), entry || !record.isEmpty());
    }

    /**
     * Today's save as a climb on {@code table} (the reputation's curve), for the one-time import: the engine's
     * value {@code standing}; the best level, where the higher of {@code standing} and the {@code highest}
     * standing on the player's reputation record (null for none) stands; and, when that is past the top rank (a
     * reputation paying past its top, whose Beyond steps are the levels past it), the Beyond mark at that level,
     * so every payout already parked or collected stays paid (TrackRecord's {@code withAllPaidThrough}). Both are
     * levels on {@code table} as it is now: a later Every or ladder reads them as other standings.
     */
    @Nonnull
    public static TrackRecord imported(int standing, @Nullable Long highest, @Nonnull CurveTable table) {
        long reached = highest == null ? standing : Math.max(highest, standing);
        int best = table.levelOf(reached);
        TrackRecord record = new TrackRecord(standing, 0.0, best, 0, Map.of());
        return best > table.top() ? record.withAllPaidThrough(List.of(BEYOND_ROW), best) : record;
    }

    /** {@code value} held to the int range a standing is read in. */
    static int toInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }
}
