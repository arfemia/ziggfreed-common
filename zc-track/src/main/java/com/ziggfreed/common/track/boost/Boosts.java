package com.ziggfreed.common.track.boost;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

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
 * told, and {@code ZigBoostStartedEvent} fires. A boost that ends needs none of it: the compiled step sees its end
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
     * Start {@code boostId} for the live player {@code playerId}, on their world thread.
     *
     * @param percent what the start lifts by, in place of the boost's own; null for its own
     * @param seconds how long it runs, in place of the boost's own; null for its own
     * @param by      who or what started it: a player's name, a reward's source, {@code admin}
     */
    @Nonnull
    public static Start start(@Nonnull UUID playerId, @Nullable String boostId, @Nullable Double percent,
            @Nullable Long seconds, @Nonnull String by) {
        BoostAsset boost = BoostConfig.getInstance().live(boostId);
        if (boost == null) {
            return Start.refused(BoostOutcome.UNKNOWN, null);
        }
        if (boost.isStanding()) {
            return Start.refused(BoostOutcome.STANDING, boost);
        }
        long length = seconds != null ? Math.max(0L, seconds) : boost.seconds();
        if (length <= 0L) {
            return Start.refused(BoostOutcome.UNTIMED, boost);
        }
        if (!boost.isPresent()) {
            return Start.refused(BoostOutcome.OUT_OF_SEASON, boost);
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
        double lift = percent != null && Double.isFinite(percent) ? percent : boost.percent();
        BoostRun run = new BoostRun(boost.getId(), lift, now, now + length * 1000L, by);
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
        announce(boost, run, scope, playerId);
        TrackEvents.fireBoostStarted(boost.getId(), scope.word(), playerId, run);
        return new Start(BoostOutcome.STARTED, boost, run, 0L, limit);
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

    /** Tell the starter, or every player online for a server boost, that it started. */
    private static void announce(@Nonnull BoostAsset boost, @Nonnull BoostRun run, @Nonnull BoostScope scope,
            @Nonnull UUID starter) {
        Message line = BoostText.started(boost, run);
        try {
            if (scope == BoostScope.SERVER) {
                Universe universe = Universe.get();
                if (universe != null) {
                    for (PlayerRef player : universe.getPlayers()) {
                        if (player != null) {
                            player.sendMessage(line);
                        }
                    }
                }
                return;
            }
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
