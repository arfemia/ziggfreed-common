package com.ziggfreed.common.track.boost;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.buff.TimedBuffs;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackStore;
import com.ziggfreed.common.track.TrackStores;
import com.ziggfreed.common.track.event.TrackEvents;
import com.ziggfreed.common.track.gain.GainPlans;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one starter and reader of boosts. Every start goes through {@link #start} (a boost item's {@code ZigBoost}
 * interaction, a {@code Boost} reward, {@code /zigboost start}, a consumer's own page), every read of what runs
 * through {@link #running}, and the gain pipeline reads them through {@code BoostFactor}.
 *
 * <p><b>Where they are kept.</b> A personal start is the starter's own, kept through their track store's seam
 * ({@code TrackStore.boosts}, the player's record by default), with the wait before their next start of the same
 * boost; a server start is the server's, in {@link ServerBoosts}. Both are on the clock, so they run on while a
 * player is away and across a restart.
 *
 * <p><b>A start</b> checks, in order: the boost is here (switched on, timed, in season), the starter meets its
 * {@code Requires}, their wait is over, the boost's own {@code Stack.Max} and the owner's limit
 * ({@code BoostSettings.Limits}: the player's for a personal boost, the server's for a server one). A start that
 * fits is kept first, then the gain plans it changes move ({@code GainPlans.invalidate} for the player's,
 * {@code invalidateAll} for a server one), the tiles re-sync, the starter (every player, for a server boost) is
 * told, the owner's start command runs ({@code BoostSettings.OnStart}, {@link BoostStartCommand}), and
 * {@code ZigBoostStartedEvent} fires. A boost that ends needs none of it: the compiled step sees its end
 * and builds the plan again, and its tile runs out with it. On the starter's own world thread.
 */
public final class Boosts {

    /** What a start came to: the outcome, the boost when it is known, the start when one was kept. */
    public record Start(@Nonnull BoostOutcome outcome, @Nullable BoostAsset boost, @Nullable BoostRun run,
                        long waitMs, int limit) {

        /** Did it start? */
        public boolean started() {
            return outcome == BoostOutcome.STARTED;
        }

        @Nonnull
        static Start refused(@Nonnull BoostOutcome outcome, @Nullable BoostAsset boost) {
            return new Start(outcome, boost, null, 0L, 0);
        }
    }

    /** One timed boost running for a player: the boost, the start, and whose it is. */
    public record Running(@Nonnull BoostAsset boost, @Nonnull BoostRun run, @Nonnull BoostScope scope) {
    }

    private Boosts() {
    }

    /**
     * Start {@code boostId} for the live player {@code playerId}, on their world thread, lifting all it lifts.
     *
     * @param percent what the start lifts by, in place of the boost's own; null for its own
     * @param seconds how long it runs, in place of the boost's own; null for its own
     * @param by      who or what started it: a player's name, a reward's source, {@code admin}
     */
    @Nonnull
    public static Start start(@Nonnull UUID playerId, @Nullable String boostId, @Nullable Double percent,
            @Nullable Long seconds, @Nonnull String by) {
        return start(playerId, boostId, percent, seconds, null, by);
    }

    /**
     * Start {@code boostId} for the live player {@code playerId}, on their world thread.
     *
     * @param percent what the start lifts by, in place of the boost's own; null for its own
     * @param seconds how long it runs, in place of the boost's own; null for its own
     * @param track   the one track the start lifts, inside what the boost lifts (a token pointed at one skill);
     *                null or blank for all it lifts
     * @param by      who or what started it: a player's name, a reward's source, {@code admin}
     */
    @Nonnull
    public static Start start(@Nonnull UUID playerId, @Nullable String boostId, @Nullable Double percent,
            @Nullable Long seconds, @Nullable String track, @Nonnull String by) {
        BoostAsset boost = BoostConfig.getInstance().live(boostId);
        Long length = timedLength(boost, seconds);
        if (length == null) {
            return Start.refused(refusedBeforeAnyone(boost, seconds), boost);
        }
        TrackKey.Subject subject = TrackKey.Subject.player(playerId);
        TrackStore store = TrackStores.of(TrackKey.Subject.PLAYER);
        HeldBoosts before = store == null ? null : store.boosts(subject);
        Ref<EntityStore> ref = PlayerAccess.liveRef(playerId);
        if (before == null || ref == null) {
            return Start.refused(BoostOutcome.NOT_LIVE, boost);
        }
        if (!passes(boost.liveRequires(), ref)) {
            return Start.refused(BoostOutcome.LOCKED, boost);
        }
        long now = System.currentTimeMillis();
        HeldBoosts held = before.live(now);
        BoostSettingsAsset settings = BoostSettingsConfig.getInstance().settings();
        BoostScope scope = boost.scope();
        int running;
        int ofThis;
        int limit;
        if (scope == BoostScope.SERVER) {
            List<BoostRun> server = ServerBoosts.shared().live(now);
            running = server.size();
            ofThis = count(server, boost.getId());
            limit = settings.serverLimit();
        } else {
            running = held.running(now);
            ofThis = held.running(boost.getId(), now);
            limit = settings.personalLimit();
        }
        long waitUntil = held.cooldownUntil(boost.getId());
        BoostOutcome fits = BoostStack.fits(now, waitUntil, ofThis, boost.stackMax(), running, limit, scope);
        if (fits != BoostOutcome.STARTED) {
            return new Start(fits, boost, null, Math.max(0L, waitUntil - now), limit);
        }
        BoostRun run = new BoostRun(boost.getId(), liftOf(boost, percent), now, now + length * 1000L, by, track);
        HeldBoosts after = scope == BoostScope.PERSONAL ? held.with(run) : held;
        if (boost.cooldownSeconds() > 0L) {
            after = after.withCooldown(boost.getId(), now + boost.cooldownSeconds() * 1000L);
        }
        if (!after.equals(before) && !store.putBoosts(subject, after) && scope == BoostScope.PERSONAL) {
            return Start.refused(BoostOutcome.NOT_LIVE, boost);
        }
        if (scope == BoostScope.SERVER) {
            ServerBoosts.shared().add(run);
            GainPlans.invalidateAll();
            TimedBuffs.refreshAllOnline();
        } else {
            GainPlans.invalidate(playerId);
            TimedBuffs.refresh(ref);
        }
        boolean replaced = BoostStartCommand.run(boost, run, scope, playerId);
        announce(boost, run, scope, playerId, replaced);
        TrackEvents.fireBoostStarted(boost.getId(), scope.word(), playerId, run);
        return new Start(BoostOutcome.STARTED, boost, run, 0L, limit);
    }

    /**
     * The live player {@code playerId} starts the reusable boost {@code boostId} themselves (a boosts page's button):
     * refused as {@link BoostOutcome#UNKNOWN} unless it is {@code Reusable}, and as {@link BoostOutcome#LOCKED} when
     * they do not meet {@code Reusable.Requires}; otherwise a start at the boost's own numbers, every other check
     * applying. On their world thread.
     */
    @Nonnull
    public static Start startOwn(@Nonnull UUID playerId, @Nullable String boostId, @Nonnull String by) {
        BoostAsset boost = BoostConfig.getInstance().live(boostId);
        if (boost == null || !boost.isReusable()) {
            return Start.refused(BoostOutcome.UNKNOWN, boost);
        }
        if (!mayStartOwn(playerId, boost)) {
            return Start.refused(BoostOutcome.LOCKED, boost);
        }
        return start(playerId, boost.getId(), null, null, null, by);
    }

    /**
     * May the live player {@code playerId} start {@code boost} themselves: it is {@code Reusable} and they meet its
     * {@code Reusable.Requires}? What a boosts page asks of each boost it might list. On their world thread.
     */
    public static boolean mayStartOwn(@Nonnull UUID playerId, @Nonnull BoostAsset boost) {
        if (!boost.isReusable()) {
            return false;
        }
        GateSpec who = boost.reusableRequires();
        return who == null || passes(StoreGates.live(who), PlayerAccess.liveRef(playerId));
    }

    /**
     * Start the server boost {@code boostId} with no player behind it (the console, a schedule): the boost is here,
     * timed and in season, and one more fits the boost's own {@code Stack.Max} and the server's limit. No
     * {@code Requires} or wait is asked, since nobody starts it; a personal boost is refused
     * ({@link BoostOutcome#NEEDS_PLAYER}). Every player online is told, and {@code ZigBoostStartedEvent} fires with
     * no starter. Any thread.
     */
    @Nonnull
    public static Start startServer(@Nullable String boostId, @Nullable Double percent, @Nullable Long seconds,
            @Nullable String track, @Nonnull String by) {
        BoostAsset boost = BoostConfig.getInstance().live(boostId);
        Long length = timedLength(boost, seconds);
        if (length == null) {
            return Start.refused(refusedBeforeAnyone(boost, seconds), boost);
        }
        if (boost.scope() != BoostScope.SERVER) {
            return Start.refused(BoostOutcome.NEEDS_PLAYER, boost);
        }
        long now = System.currentTimeMillis();
        List<BoostRun> server = ServerBoosts.shared().live(now);
        int limit = BoostSettingsConfig.getInstance().settings().serverLimit();
        BoostOutcome fits = BoostStack.fits(now, 0L, count(server, boost.getId()), boost.stackMax(), server.size(),
                limit, BoostScope.SERVER);
        if (fits != BoostOutcome.STARTED) {
            return new Start(fits, boost, null, 0L, limit);
        }
        BoostRun run = new BoostRun(boost.getId(), liftOf(boost, percent), now, now + length * 1000L, by, track);
        ServerBoosts.shared().add(run);
        GainPlans.invalidateAll();
        TimedBuffs.refreshAllOnline();
        // The owner's command first: only one handed over in the line's place keeps the library's line back.
        if (!BoostStartCommand.run(boost, run, BoostScope.SERVER, null)) {
            announceToAll(BoostText.started(boost, run));
        }
        TrackEvents.fireBoostStarted(boost.getId(), BoostScope.SERVER.word(), null, run);
        return new Start(BoostOutcome.STARTED, boost, run, 0L, limit);
    }

    /**
     * Keep {@code carried} on the live player {@code playerId}'s own record as they are, with {@code waits} (per boost
     * id, when the player's next start of it may come, in epoch milliseconds): starts and waits an older system of a
     * consumer's kept, moved across once. No limit, wait, season or {@code Requires} is asked and nothing is said or
     * fired, as a track's {@code seed} imports a climb; a start that has ended, one already held and a wait that is over
     * are dropped, and a wait already held runs to the later end. False when the player is not live here or the record
     * could not be
     * written (try again at their next ready). On their world thread.
     */
    public static boolean seed(@Nonnull UUID playerId, @Nonnull Collection<BoostRun> carried,
            @Nonnull Map<String, Long> waits) {
        TrackKey.Subject subject = TrackKey.Subject.player(playerId);
        TrackStore store = TrackStores.of(TrackKey.Subject.PLAYER);
        HeldBoosts held = store == null ? null : store.boosts(subject);
        if (held == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        HeldBoosts after = held.live(now);
        for (BoostRun run : carried) {
            // One already held (a move that ran before and is asked again) is never held twice.
            if (run != null && run.liveAt(now) && !after.runs().contains(run)) {
                after = after.with(run);
            }
        }
        for (Map.Entry<String, Long> wait : waits.entrySet()) {
            Long until = wait.getValue();
            if (wait.getKey() != null && !wait.getKey().isBlank() && until != null && until > now
                    && until > after.cooldownUntil(wait.getKey())) {
                after = after.withCooldown(wait.getKey(), until);
            }
        }
        if (after.equals(held)) {
            return true;
        }
        if (!store.putBoosts(subject, after)) {
            return false;
        }
        GainPlans.invalidate(playerId);
        TimedBuffs.refresh(PlayerAccess.liveRef(playerId));
        return true;
    }

    /**
     * Keep {@code carried} as the server's, as they are: server starts an older system of a consumer's ran, moved
     * across once, before or after the boot's read of the server's file (before it, they wait and join what the file
     * kept). No limit or season is asked and nothing is said; a start that has ended is dropped, and one the server
     * already keeps (equal in every leaf) is never kept twice. How many were kept or wait. Any thread.
     */
    public static int seedServer(@Nonnull Collection<BoostRun> carried) {
        return seedServer(carried, null);
    }

    /**
     * As {@link #seedServer(Collection)}, telling {@code written} once whether {@code server-boosts.json} now holds
     * them (true when nothing was left to write; false when it could not be written): at once after the boot's read,
     * at the read for a seed before it. A consumer retires its older file only on true, so a start is never in neither.
     */
    public static int seedServer(@Nonnull Collection<BoostRun> carried, @Nullable Consumer<Boolean> written) {
        int kept = ServerBoosts.shared().seed(carried, written);
        if (kept > 0) {
            GainPlans.invalidateAll();
            TimedBuffs.refreshAllOnline();
        }
        return kept;
    }

    /**
     * The multiplier every boost lifting {@code trackId} (in {@code categoryId}, null for none) applies now for the
     * player {@code playerId} (null for nobody's own starts) at {@code ref} (null asks no conditional boost's
     * {@code Requires}, so those count nowhere): the standing boosts, the server's starts and the player's own, summed
     * and capped as a gain's {@code Boost} step does, no prestige extra. For a gain a consumer pays past the gain
     * pipeline (a climb of its own with no track) and for a page showing what a gain would earn. 1 with none. On the
     * player's world thread.
     */
    public static double multiplier(@Nullable UUID playerId, @Nullable Ref<EntityStore> ref, @Nonnull String trackId,
            @Nullable String categoryId) {
        TrackStore store = TrackStores.of(TrackKey.Subject.PLAYER);
        HeldBoosts held = store == null || playerId == null ? null : store.boosts(TrackKey.Subject.player(playerId));
        return BoostFactor.multiplier(trackId, categoryId, held, ref);
    }

    /**
     * How long the live player {@code playerId} still waits before starting {@code boostId} again
     * ({@code Stack.Cooldown}), in milliseconds; 0 for no wait, or when they are not live here. On their world thread.
     */
    public static long waitMs(@Nonnull UUID playerId, @Nonnull String boostId) {
        TrackStore store = TrackStores.of(TrackKey.Subject.PLAYER);
        HeldBoosts held = store == null ? null : store.boosts(TrackKey.Subject.player(playerId));
        return held == null ? 0L : Math.max(0L, held.cooldownUntil(boostId) - System.currentTimeMillis());
    }

    /** How many personal boosts the live player {@code playerId} runs now; 0 when they are not live here. */
    public static int personalRunning(@Nonnull UUID playerId) {
        TrackStore store = TrackStores.of(TrackKey.Subject.PLAYER);
        HeldBoosts held = store == null ? null : store.boosts(TrackKey.Subject.player(playerId));
        return held == null ? 0 : held.running(System.currentTimeMillis());
    }

    /**
     * The length a start of {@code boost} runs, in seconds ({@code seconds} in place of the boost's own), or null when
     * nothing could start it here: no such boost, a standing one, no length, or out of season
     * ({@link #refusedBeforeAnyone} says which).
     */
    @Nullable
    private static Long timedLength(@Nullable BoostAsset boost, @Nullable Long seconds) {
        if (boost == null || boost.isStanding() || !boost.isPresent()) {
            return null;
        }
        long length = seconds != null ? Math.max(0L, seconds) : boost.seconds();
        return length <= 0L ? null : length;
    }

    /** Why {@link #timedLength} answered null for {@code boost}, in the order a start asks. */
    @Nonnull
    private static BoostOutcome refusedBeforeAnyone(@Nullable BoostAsset boost, @Nullable Long seconds) {
        if (boost == null) {
            return BoostOutcome.UNKNOWN;
        }
        if (boost.isStanding()) {
            return BoostOutcome.STANDING;
        }
        long length = seconds != null ? Math.max(0L, seconds) : boost.seconds();
        return length <= 0L ? BoostOutcome.UNTIMED : BoostOutcome.OUT_OF_SEASON;
    }

    /** The percent a start lifts by: {@code percent} when it is a number, else the boost's own. */
    private static double liftOf(@Nonnull BoostAsset boost, @Nullable Double percent) {
        return percent != null && Double.isFinite(percent) ? percent : boost.percent();
    }

    /**
     * Every timed boost running for the live player {@code playerId} now: each server one, then their own, in the
     * order they began, leaving out a start whose boost is gone, switched off or out of season. What the tiles
     * show (those whose {@code Requires} the player meets now), and what a consumer's page lists. On the player's
     * world thread.
     */
    @Nonnull
    public static List<Running> running(@Nonnull UUID playerId) {
        long now = System.currentTimeMillis();
        List<Running> out = new ArrayList<>();
        for (BoostRun run : ServerBoosts.shared().live(now)) {
            add(out, run, BoostScope.SERVER);
        }
        TrackStore store = TrackStores.of(TrackKey.Subject.PLAYER);
        HeldBoosts held = store == null ? null : store.boosts(TrackKey.Subject.player(playerId));
        if (held != null) {
            for (BoostRun run : held.runs()) {
                if (run.liveAt(now)) {
                    add(out, run, BoostScope.PERSONAL);
                }
            }
        }
        return out;
    }

    /**
     * Every standing boost lifting the live player {@code playerId} now: switched on, in season, and its
     * {@code Requires} met, in id order. What a consumer's page lists beside the timed boosts. On their world thread.
     */
    @Nonnull
    public static List<BoostAsset> standing(@Nonnull UUID playerId) {
        Ref<EntityStore> ref = PlayerAccess.liveRef(playerId);
        List<BoostAsset> out = new ArrayList<>();
        for (BoostAsset boost : BoostConfig.getInstance().standing()) {
            if (boost.isPresent() && passes(boost.liveRequires(), ref)) {
                out.add(boost);
            }
        }
        return out;
    }

    /** Every server boost running now, in the order they began (any thread). */
    @Nonnull
    public static List<BoostRun> serverRuns() {
        return ServerBoosts.shared().live(System.currentTimeMillis());
    }

    /**
     * The calendar events {@code eventIds} began, ended, rose or lapsed: every online player's tiles re-sync, each
     * on their own world thread, when a switched-on timed boost names one of them as its {@code Season} or asks a
     * {@code Requires} of the player ({@link BoostConfig#tilesFollow}), and nothing happens when none does. The
     * plans need nothing: such a boost is asked at every gain it would lift. Never on a gain. Any thread (the
     * calendar's tick thread, through zc-objectives' {@code CalendarBoostTiles}, since this module never imports the
     * calendar).
     */
    public static void onCalendarChange(@Nonnull Collection<String> eventIds) {
        if (BoostConfig.getInstance().tilesFollow(eventIds)) {
            TimedBuffs.refreshAllOnline();
        }
    }

    /** End every server boost now; how many were running. Any thread. */
    public static int clearServer() {
        int cleared = ServerBoosts.shared().clear();
        GainPlans.invalidateAll();
        TimedBuffs.refreshAllOnline();
        return cleared;
    }

    /**
     * End every personal boost the live player {@code playerId} runs, their waits kept; how many were running, or
     * -1 when they are not live here. On their world thread.
     */
    public static int clearPersonal(@Nonnull UUID playerId) {
        TrackKey.Subject subject = TrackKey.Subject.player(playerId);
        TrackStore store = TrackStores.of(TrackKey.Subject.PLAYER);
        HeldBoosts held = store == null ? null : store.boosts(subject);
        if (held == null) {
            return -1;
        }
        int running = held.running(System.currentTimeMillis());
        if (!held.runs().isEmpty() && !store.putBoosts(subject, held.withoutRuns())) {
            return -1;
        }
        GainPlans.invalidate(playerId);
        TimedBuffs.refresh(PlayerAccess.liveRef(playerId));
        return running;
    }

    /** Does the player at {@code ref} meet {@code live} (null: nothing to ask)? */
    static boolean passes(@Nullable GateSpec live, @Nullable Ref<EntityStore> ref) {
        if (live == null) {
            return true;
        }
        if (ref == null || !ref.isValid()) {
            return false;
        }
        try {
            return StoreGates.passes(live, GateQuery.of(FactorContext.about(ref.getStore(), ref)));
        } catch (Throwable t) {
            SafeLog.fine("[boost] a boost's Requires could not be read, so it does not pass: " + t.getMessage());
            return false;
        }
    }

    private static void add(@Nonnull List<Running> out, @Nonnull BoostRun run, @Nonnull BoostScope scope) {
        BoostAsset boost = BoostConfig.getInstance().live(run.boost());
        if (boost != null && boost.isPresent()) {
            out.add(new Running(boost, run, scope));
        }
    }

    private static int count(@Nonnull List<BoostRun> runs, @Nonnull String boostId) {
        int count = 0;
        for (BoostRun run : runs) {
            if (run.is(boostId)) {
                count++;
            }
        }
        return count;
    }

    /** Tell every player online {@code line}. */
    private static void announceToAll(@Nonnull Message line) {
        try {
            Universe universe = Universe.get();
            if (universe != null) {
                for (PlayerRef player : universe.getPlayers()) {
                    if (player != null) {
                        player.sendMessage(line);
                    }
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[boost] could not say that a boost started: " + t.getMessage());
        }
    }

    /**
     * Tell the starter, or every player online for a server boost, that it started; a server boost whose start
     * command was handed over in that line's place ({@code replaced}, {@code OnStart.ReplacesAnnouncement}) tells only
     * the starter.
     */
    private static void announce(@Nonnull BoostAsset boost, @Nonnull BoostRun run, @Nonnull BoostScope scope,
            @Nonnull UUID starter, boolean replaced) {
        Message line = BoostText.started(boost, run);
        if (scope == BoostScope.SERVER && !replaced) {
            announceToAll(line);
            return;
        }
        try {
            Ref<EntityStore> ref = PlayerAccess.liveRef(starter);
            PlayerRef player = ref == null ? null : ref.getStore().getComponent(ref, PlayerRef.getComponentType());
            if (player != null) {
                player.sendMessage(line);
            }
        } catch (Throwable t) {
            SafeLog.fine("[boost] could not say that a boost started: " + t.getMessage());
        }
    }
}
