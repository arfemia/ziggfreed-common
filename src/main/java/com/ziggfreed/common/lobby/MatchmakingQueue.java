package com.ziggfreed.common.lobby;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.feedback.EventTitles;
import com.ziggfreed.common.feedback.Notify;

/**
 * One fill-window + countdown matchmaking queue for a single {@link QueueKey}. Owns
 * ONLY queue membership + the {@link QueueState} machine + the timer; the actual game
 * launch is abstracted behind the injected {@link RoundLauncher}, membership-truth
 * behind {@code alreadyEngaged}, presence behind {@code online}, and all player-facing
 * text behind {@link QueueMessages}. It imports no world / store / mod types - the DRY
 * lift boundary.
 *
 * <p>State machine: {@code OPEN -> COUNTDOWN -> LAUNCHING -> CLOSED}. A join that hits
 * {@code maxParty} starts the countdown at once; a join that hits the fill-window
 * trigger ({@code minParty}, or 1 when {@code allowSolo}) starts the fill window, which
 * on expiry starts the countdown; a leave that drops below the floor cancels back to
 * {@code OPEN}; on countdown expiry the party is snapshotted, re-validated (online +
 * not already engaged), the initiator re-picked, and the launcher fired.
 *
 * <p><b>Threading:</b> all mutators are synchronized on {@code this}; timer callbacks
 * run on the {@link DelayScheduler}'s thread and re-acquire the lock. The tick is
 * <b>packet-only</b> - UUID iteration + {@link Universe#getPlayer} (a store-free
 * lookup) + {@code Notify}/{@code EventTitles} packet writes; <b>never</b> a Store/Ref
 * read. The foreign {@link RoundLauncher#launch} call is made OFF the lock, and the
 * launcher itself owns any world-thread hop.
 */
public final class MatchmakingQueue {

    private final QueueKey key;
    private final LobbyService owner;
    private final LobbyConfig config;
    private final RoundLauncher launcher;
    private final Predicate<UUID> alreadyEngaged;
    private final Predicate<UUID> online;
    private final QueueMessages messages;
    private final DelayScheduler scheduler;

    /** Join order is preserved (first = initiator). Guarded by {@code this}. */
    private final LinkedHashSet<UUID> members = new LinkedHashSet<>();

    private QueueState state = QueueState.OPEN;
    @Nullable private DelayScheduler.Cancellable fillTask;
    @Nullable private DelayScheduler.Cancellable countdownTask;
    private int countdownRemaining;

    MatchmakingQueue(@Nonnull QueueKey key, @Nonnull LobbyService owner, @Nonnull LobbyConfig config,
                     @Nonnull RoundLauncher launcher, @Nonnull Predicate<UUID> alreadyEngaged,
                     @Nonnull Predicate<UUID> online, @Nonnull QueueMessages messages,
                     @Nonnull DelayScheduler scheduler) {
        this.key = key;
        this.owner = owner;
        this.config = config;
        this.launcher = launcher;
        this.alreadyEngaged = alreadyEngaged;
        this.online = online;
        this.messages = messages;
        this.scheduler = scheduler;
    }

    @Nonnull public QueueKey key() {
        return key;
    }

    public synchronized QueueState state() {
        return state;
    }

    public synchronized int size() {
        return members.size();
    }

    public synchronized boolean contains(@Nonnull UUID uuid) {
        return members.contains(uuid);
    }

    // ==================== join / leave / force-start ====================

    /** Add {@code uuid} to the queue, advancing the state machine. */
    @Nonnull
    public synchronized JoinResult join(@Nonnull UUID uuid) {
        if (state == QueueState.LAUNCHING || state == QueueState.CLOSED) {
            return JoinResult.QUEUE_UNAVAILABLE;
        }
        if (alreadyEngaged.test(uuid)) {
            return JoinResult.ALREADY_ENGAGED;
        }
        if (members.contains(uuid)) {
            return JoinResult.ALREADY_QUEUED;
        }
        if (!owner.tryReserve(uuid, key)) {
            return JoinResult.ALREADY_QUEUED; // already sitting in another queue
        }
        members.add(uuid);
        toast(uuid, messages.joined(members.size(), config.minParty(), config.maxParty()), Tone.SUCCESS);
        evaluateAfterJoin();
        return JoinResult.JOINED;
    }

    /** Remove {@code uuid} from the queue; cancels a pending start if it drops below the floor. */
    public synchronized boolean leave(@Nonnull UUID uuid) {
        if (!members.remove(uuid)) {
            return false;
        }
        owner.release(uuid, key);
        toast(uuid, messages.left(members.size(), config.minParty(), config.maxParty()), Tone.DEFAULT);

        if (members.isEmpty()) {
            close();
            return true;
        }
        int trigger = triggerSize();
        if (members.size() < trigger) {
            // Dropped below the launch floor: cancel any pending start, reopen.
            if (state == QueueState.COUNTDOWN) {
                cancelCountdown();
                state = QueueState.OPEN;
                broadcast(messages.cancelled(), Tone.WARNING);
                hideBanners();
            }
            cancelFillWindow();
        }
        return true;
    }

    /** The initiator (first joiner) skips the fill window, if {@code leaderForceStart} is enabled. */
    public synchronized boolean forceStart(@Nonnull UUID leader) {
        if (!config.leaderForceStart() || state != QueueState.OPEN || members.isEmpty()) {
            return false;
        }
        UUID initiator = members.iterator().next();
        if (!initiator.equals(leader)) {
            return false;
        }
        cancelFillWindow();
        startCountdown();
        return true;
    }

    // ==================== state transitions (all under the caller's lock) ====================

    private void evaluateAfterJoin() {
        int size = members.size();
        if (size >= config.maxParty()) {
            if (state == QueueState.OPEN) {
                cancelFillWindow();
                startCountdown();
            }
            return; // already COUNTDOWN -> a late joiner just rides the current countdown
        }
        if (state == QueueState.OPEN && size >= triggerSize() && fillTask == null && countdownTask == null) {
            startFillWindow();
        }
    }

    private void startFillWindow() {
        cancelFillWindow();
        if (config.fillTimeoutSeconds() <= 0) {
            onFillElapsed();
            return;
        }
        fillTask = scheduler.schedule(this::onFillElapsed, config.fillTimeoutSeconds(), TimeUnit.SECONDS);
    }

    private void onFillElapsed() {
        synchronized (this) {
            fillTask = null;
            if (state != QueueState.OPEN) {
                return;
            }
            int size = members.size();
            if (size >= config.minParty() || (config.allowSolo() && size >= 1)) {
                startCountdown();
            }
            // else: under the floor with no solo allowance -> stay OPEN, wait for more.
        }
    }

    private void startCountdown() {
        state = QueueState.COUNTDOWN;
        cancelFillWindow();
        countdownRemaining = config.countdownSeconds();
        if (countdownRemaining <= 0) {
            // Launch on the scheduler thread so the foreign launcher call is OFF this lock.
            countdownTask = scheduler.schedule(this::fireLaunch, 0, TimeUnit.SECONDS);
            return;
        }
        showCountdownBanner(countdownRemaining);
        scheduleNextTick();
    }

    private void scheduleNextTick() {
        countdownTask = scheduler.schedule(this::onCountdownTick, 1, TimeUnit.SECONDS);
    }

    private void onCountdownTick() {
        boolean launch = false;
        synchronized (this) {
            countdownTask = null;
            if (state != QueueState.COUNTDOWN) {
                return;
            }
            countdownRemaining--;
            if (countdownRemaining > 0) {
                showCountdownBanner(countdownRemaining);
                scheduleNextTick();
            } else {
                launch = true;
            }
        }
        if (launch) {
            fireLaunch(); // already off any caller's lock (we are on the scheduler thread)
        }
    }

    /**
     * Snapshot + re-validate the party, pick a still-eligible initiator, then fire the
     * launcher OFF the lock. The lock is held only for the snapshot/clear; the foreign
     * launch + player notifications run after it is released.
     */
    private void fireLaunch() {
        UUID initiator;
        List<UUID> party;
        synchronized (this) {
            if (state == QueueState.LAUNCHING || state == QueueState.CLOSED) {
                return;
            }
            state = QueueState.LAUNCHING;
            cancelFillWindow();
            cancelCountdown();

            party = new ArrayList<>();
            for (UUID u : members) {
                if (online.test(u) && !alreadyEngaged.test(u)) {
                    party.add(u);
                }
            }
            initiator = party.isEmpty() ? null : party.get(0);

            for (UUID u : members) {
                owner.release(u, key);
            }
            members.clear();
        }

        if (initiator == null) {
            info("[Lobby] launch aborted for " + key + ": no eligible players");
            close();
            return;
        }

        // Hide the countdown banner + announce launch (packet-only; party already snapshotted).
        for (UUID u : party) {
            PlayerRef p = player(u);
            if (p == null) {
                continue;
            }
            EventTitles.hide(p, EventTitles.DEFAULT_FADE_OUT);
        }
        broadcastTo(party, messages.launching(), Tone.SUCCESS);

        final List<UUID> launched = party;
        CompletableFuture<?> future;
        try {
            future = launcher.launch(initiator, party);
        } catch (Throwable t) {
            warn("[Lobby] launcher threw for " + key + ": " + t.getMessage());
            broadcastTo(launched, messages.launchFailed(), Tone.DANGER);
            close();
            return;
        }
        // Hand-off complete: free the queue + the key immediately. The launcher owns the
        // party now (and a consumer's startChase binds them to its round registry
        // synchronously, so a re-join in the gap is guarded by alreadyEngaged). Waiting on
        // the future to close would leak the queue if the launch future ever hangs. The
        // future is attached only to drive the launch-failed toast.
        close();
        if (future != null) {
            future.whenComplete((r, e) -> {
                if (e != null) {
                    warn("[Lobby] launch failed for " + key + ": " + e.getMessage());
                    broadcastTo(launched, messages.launchFailed(), Tone.DANGER);
                }
            });
        }
    }

    private void close() {
        synchronized (this) {
            state = QueueState.CLOSED;
            cancelFillWindow();
            cancelCountdown();
            for (UUID u : members) {
                owner.release(u, key);
            }
            members.clear();
        }
        owner.removeQueue(key, this);
    }

    private int triggerSize() {
        return config.allowSolo() ? 1 : config.minParty();
    }

    private void cancelFillWindow() {
        if (fillTask != null) {
            fillTask.cancel();
            fillTask = null;
        }
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    // ==================== delivery (packet-only, fully guarded) ====================

    private enum Tone { DEFAULT, SUCCESS, WARNING, DANGER }

    private void toast(@Nonnull UUID uuid, @Nullable Message msg, @Nonnull Tone tone) {
        if (msg == null) {
            return;
        }
        PlayerRef p = player(uuid);
        if (p == null) {
            return;
        }
        switch (tone) {
            case SUCCESS -> Notify.success(p, msg);
            case WARNING -> Notify.warning(p, msg);
            case DANGER -> Notify.danger(p, msg);
            default -> Notify.def(p, msg);
        }
    }

    /** Toast to every current member (caller holds the lock). */
    private void broadcast(@Nullable Message msg, @Nonnull Tone tone) {
        if (msg == null) {
            return;
        }
        for (UUID u : members) {
            toast(u, msg, tone);
        }
    }

    /** Toast to a captured list (used after members has been cleared at launch). */
    private void broadcastTo(@Nonnull List<UUID> targets, @Nullable Message msg, @Nonnull Tone tone) {
        if (msg == null) {
            return;
        }
        for (UUID u : targets) {
            toast(u, msg, tone);
        }
    }

    private void showCountdownBanner(int remaining) {
        Message primary = messages.countdownPrimary(remaining);
        Message secondary = messages.countdownSecondary(remaining);
        if (primary == null || secondary == null) {
            return;
        }
        for (UUID u : members) {
            PlayerRef p = player(u);
            if (p != null) {
                EventTitles.show(p, primary, secondary, true);
            }
        }
    }

    private void hideBanners() {
        for (UUID u : members) {
            PlayerRef p = player(u);
            if (p != null) {
                EventTitles.hide(p, EventTitles.DEFAULT_FADE_OUT);
            }
        }
    }

    @Nullable
    private static PlayerRef player(@Nonnull UUID uuid) {
        try {
            return Universe.get().getPlayer(uuid);
        } catch (Throwable t) {
            return null; // no Universe (unit JVM) or unknown player -> skip delivery
        }
    }

    private static void info(@Nonnull String msg) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atInfo().log("%s", msg);
        } catch (Throwable ignored) {
        }
    }

    private static void warn(@Nonnull String msg) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("%s", msg);
        } catch (Throwable ignored) {
        }
    }
}
