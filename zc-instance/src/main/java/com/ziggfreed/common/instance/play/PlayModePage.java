package com.ziggfreed.common.instance.play;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.instance.preset.QueueModeEntry;
import com.ziggfreed.common.instance.preset.QueueModeId;
import com.ziggfreed.common.instance.preset.QueueModeSet;
import com.ziggfreed.common.lobby.LobbyConfig;
import com.ziggfreed.common.lobby.MatchmakingQueue;
import com.ziggfreed.common.lobby.QueueKey;
import com.ziggfreed.common.lobby.QueueListener;
import com.ziggfreed.common.lobby.QueueSnapshot;
import com.ziggfreed.common.lobby.QueueState;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;

/**
 * The generic, mod-agnostic "Play" screen: ONE page that morphs between two states off
 * the player's live-queue status.
 * <ul>
 *   <li><b>Chooser</b> (not queued): a chosen-difficulty header + the asset-driven mode
 *       cards (Public / Party / Solo, each an item-glyph icon + label + sublabel). Picking
 *       a card routes through the consumer's {@link PlayModeHandler} - Public queues + the
 *       page reopens to the roster, Party opens the party manager, Solo launches now.</li>
 *   <li><b>Roster / ready</b> (queued): the live roster, an {@code X/Y} count, the queue
 *       state (waiting / launching in N), and a Leave button. The launch countdown <b>ticks
 *       down live</b> - a registered {@link QueueListener} pushes the new status via {@code
 *       sendUpdate} on every {@code onChange} (fired each second during the countdown), with
 *       no page reopen and no polling. Close keeps the queue reservation; Leave de-queues.</li>
 * </ul>
 *
 * <p>Consumer policy (the {@link com.ziggfreed.common.lobby.LobbyService}, the resolved
 * {@link QueueModeSet}, the chrome {@link PlayScreenMessages}, the {@link PlayModeHandler})
 * is supplied through {@link PlayModePageDeps}; the page imports no mod types. Usernames
 * resolve live at render time. Every {@code handleDataEvent} exit path sends a response.
 * Supersedes the older single-purpose {@code QueuePage}.
 */
public class PlayModePage extends ToastablePage<PlayEventData> {

    private static final String PAGE_TEMPLATE = "Pages/ZigPlayPage.ui";
    private static final String CARD_TEMPLATE = "Pages/ZigPlayModeCard.ui";
    private static final String ROW_TEMPLATE = "Pages/ZigQueueRosterRow.ui";
    private static final int MAX_ROWS = 32;
    private static final String INITIATOR_COLOR = "#ffd97a";

    private final PlayModePageDeps deps;
    /** The difficulty chosen upstream (for the chooser state); null when opened straight into a live queue. */
    @Nullable
    private final String presetId;

    /** Live-countdown listener wiring (world-thread mutated only; the callback reads {@code isDismissed()}). */
    @Nullable
    private QueueListener listener;
    @Nullable
    private MatchmakingQueue listenedQueue;

    /** One-shot: the "you're queued" toast fires only on the first roster render, not on a live tick. */
    private boolean queuedToastPrimed = false;

    public PlayModePage(@Nonnull PlayerRef playerRef, @Nonnull PlayModePageDeps deps, @Nullable String presetId) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PlayEventData.CODEC);
        this.deps = deps;
        this.presetId = presetId;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        PlayScreenMessages t = deps.text();
        cmd.append(PAGE_TEMPLATE);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));
        cmd.set("#Title.Text", t.title());

        MatchmakingQueue queue = deps.lobby().currentQueueOf(playerRef.getUuid());
        if (queue == null) {
            detachListener(); // not queued -> no live updates
            buildChooser(cmd, events, t);
        } else {
            attachListener(queue); // queued -> live countdown
            buildRoster(cmd, events, t, queue);
        }
        renderToastInto(cmd); // LAST: the toast overlay draws on top of the page content.
    }

    // ==================== chooser state ====================

    private void buildChooser(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                              @Nonnull PlayScreenMessages t) {
        cmd.set("#ModeCards.Visible", true);
        cmd.set("#RosterArea.Visible", false);
        // TextSpans (not .Text): t.difficulty carries the preset NAME as a nested Message param,
        // which only resolves client-side on TextSpans (.Text leaves the {0} literal). See
        // hytale-rich-text-textspans.md / the DialoguePage #NodeText pattern.
        cmd.set("#Subtitle.TextSpans", t.difficulty(deps.presetName().apply(presetId)));

        QueueModeSet modes = deps.modes().apply(presetId);
        List<QueueModeEntry> ordered = modes.enabledOrdered();
        for (int i = 0; i < ordered.size(); i++) {
            QueueModeEntry e = ordered.get(i);
            cmd.append("#ModeCards", CARD_TEMPLATE);
            String sel = "#ModeCards[" + i + "]";
            // The glyph is authored in the consumer's /Server/ asset (never baked here); a blank
            // id (the neutral fallback) draws no glyph rather than a broken icon.
            String icon = e.iconItemId();
            if (icon != null && !icon.isBlank()) {
                cmd.set(sel + " #CardIcon.Visible", true);
                cmd.set(sel + " #CardIcon.Slots", List.of(new ItemGridSlot(new ItemStack(icon, 1))));
            } else {
                cmd.set(sel + " #CardIcon.Visible", false);
            }
            cmd.set(sel + " #CardBtn.Text", labelFor(e, t));
            cmd.set(sel + " #CardSub.Text", t.modeDesc(e.mode()));
            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #CardBtn",
                    EventData.of("Action", "pick").append("Mode", e.mode().wire()), false);
        }

        // Optional "Claim Rewards" button for spoils missed earlier (closed the results screen / full
        // inventory). Shown only when the consumer's hook reports pending rewards for this player.
        PlayRewardClaim claim = deps.claim();
        if (claim != null && claim.hasPending(playerRef.getUuid())) {
            cmd.set("#ClaimBtn.Visible", true);
            cmd.set("#ClaimBtn.Text", t.claimButton());
            events.addEventBinding(CustomUIEventBindingType.Activating, "#ClaimBtn", EventData.of("Action", "claim"));
        }
    }

    /** The card label: an authored {@code LabelKey} override (resolved via deps) wins over the default. */
    @Nonnull
    private Message labelFor(@Nonnull QueueModeEntry e, @Nonnull PlayScreenMessages t) {
        String key = e.labelKey();
        if (key != null && !key.isBlank()) {
            return deps.key().apply(key);
        }
        return t.modeLabel(e.mode());
    }

    // ==================== roster / ready state ====================

    private void buildRoster(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                             @Nonnull PlayScreenMessages t, @Nonnull MatchmakingQueue queue) {
        cmd.set("#ModeCards.Visible", false);
        cmd.set("#RosterArea.Visible", true);

        QueueSnapshot snap = queue.snapshot();
        LobbyConfig cfg = snap.config();
        // TextSpans (not .Text): the nested preset-name Message param resolves only on TextSpans
        // (see buildChooser).
        cmd.set("#Subtitle.TextSpans", t.difficulty(deps.presetName().apply(snap.key().presetId())));
        cmd.set("#PlayerCount.Text", t.playerCount(snap.size(), cfg.maxParty()));
        cmd.set("#Status.Text", statusFor(t, snap));
        cmd.set("#WaitEstimate.Text", t.waitEstimate(cfg.fillTimeoutSeconds() + cfg.countdownSeconds()));

        List<UUID> members = snap.members();
        for (int i = 0; i < members.size() && i < MAX_ROWS; i++) {
            cmd.append("#RosterList", ROW_TEMPLATE);
            String sel = "#RosterList[" + i + "]";
            // Plain String (a proper-noun username): .Text cannot construct from a raw-Message object.
            cmd.set(sel + " #RowName.Text", name(members.get(i)));
            if (i == 0) {
                cmd.set(sel + " #RowName.Style.TextColor", INITIATOR_COLOR); // initiator
            }
        }

        cmd.set("#LeaveBtn.Visible", true);
        cmd.set("#LeaveBtn.Text", t.leaveButton());
        events.addEventBinding(CustomUIEventBindingType.Activating, "#LeaveBtn", EventData.of("Action", "leave"));

        if (!queuedToastPrimed) {
            queuedToastPrimed = true;
            primeToast(ToastSpec.of(ToastKind.INFO, t.toastQueued(snap.size(), cfg.maxParty())));
        }
    }

    @Nonnull
    private static Message statusFor(@Nonnull PlayScreenMessages t, @Nonnull QueueSnapshot snap) {
        QueueState state = snap.state();
        if (state == QueueState.COUNTDOWN) {
            return t.statusCountdown(snap.countdownRemaining());
        }
        if (state == QueueState.LAUNCHING) {
            return t.statusLaunching();
        }
        return t.statusWaiting();
    }

    // ==================== live countdown (queue listener -> sendUpdate) ====================

    // attach/detach are synchronized because onLaunch detaches off the queue's daemon thread
    // while build/handleDataEvent/onDismiss attach/detach on the world thread.
    private synchronized void attachListener(@Nonnull MatchmakingQueue queue) {
        if (listener != null && listenedQueue == queue) {
            return; // already live on this queue
        }
        detachListener();
        QueueListener l = new QueueListener() {
            @Override
            public void onChange(@Nonnull QueueSnapshot snapshot) {
                pushLive(snapshot);
            }

            @Override
            public void onLaunch(@Nonnull QueueKey key, @Nonnull UUID initiator, @Nonnull List<UUID> party) {
                pushLaunching();
                // The queue is now CLOSED + evicted from the LobbyService; stop listening so the
                // page does not retain the dead queue (the round teleport then dismisses the page).
                detachListener();
            }
        };
        queue.addListener(l);
        this.listener = l;
        this.listenedQueue = queue;
    }

    private synchronized void detachListener() {
        if (listener != null && listenedQueue != null) {
            try {
                listenedQueue.removeListener(listener);
            } catch (Throwable ignored) {
            }
        }
        listener = null;
        listenedQueue = null;
    }

    /**
     * Push the live status + count to the open roster on a queue change (fires each second
     * during the countdown). Updates only the always-present template scalars (never the
     * dynamically-appended roster rows), so it cannot hit a not-yet-rendered selector; and
     * it is skipped once this page instance is dismissed (an unguarded stale push crashes
     * the client - the same hazard the toast surface guards).
     */
    private void pushLive(@Nonnull QueueSnapshot snap) {
        if (isDismissed()) {
            return;
        }
        try {
            PlayScreenMessages t = deps.text();
            LobbyConfig cfg = snap.config();
            UICommandBuilder cmd = new UICommandBuilder();
            cmd.set("#PlayerCount.Text", t.playerCount(snap.size(), cfg.maxParty()));
            cmd.set("#Status.Text", statusFor(t, snap));
            pushStatus(cmd);
        } catch (Throwable ignored) {
            // a malformed push must never corrupt the queue's listener fan-out
        }
    }

    private void pushLaunching() {
        if (isDismissed()) {
            return;
        }
        try {
            UICommandBuilder cmd = new UICommandBuilder();
            cmd.set("#Status.Text", deps.text().statusLaunching());
            pushStatus(cmd);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Push {@code cmd} to THIS open page, re-checking dismissal ON THE WORLD THREAD (where
     * {@code onDismiss} runs) immediately before the update. The base {@code sendUpdate}'s
     * dismissed guard is read off the queue's daemon thread - one hop too early: the engine's
     * own {@code sendUpdate} defers to {@code world.execute} and there re-checks only
     * {@code ref.isValid()}, so a dismiss landing in that gap would deliver a stale update to
     * whatever page replaced this one and crash the client (selector-not-found). Replicating
     * the engine push here lets the volatile {@code dismissed} re-check run inside the hop, so
     * a page that navigated away in the meantime is reliably skipped. Only ever updates the
     * always-present template scalars (never the dynamically-appended roster rows).
     */
    private void pushStatus(@Nonnull UICommandBuilder cmd) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        World world;
        try {
            world = store.getExternalData().getWorld();
        } catch (Throwable t) {
            return;
        }
        if (world == null) {
            return;
        }
        world.execute(() -> {
            if (isDismissed() || !ref.isValid()) {
                return;
            }
            try {
                Player p = store.getComponent(ref, Player.getComponentType());
                if (p == null) {
                    return;
                }
                p.getPageManager().updateCustomPage(new CustomPage(
                        getClass().getName(), false, false,
                        CustomPageLifetime.CanDismissOrCloseThroughInteraction,
                        cmd.getCommands(), UIEventBuilder.EMPTY_EVENT_BINDING_ARRAY));
            } catch (Throwable ignored) {
            }
        });
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull PlayEventData data) {
        Player player = (Player) store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        String action = data.action == null ? "" : data.action;
        if ("claim".equals(action)) {
            PlayRewardClaim claim = deps.claim();
            if (claim != null) {
                claim.claim(playerRef, ref, store);
                primeToast(ToastSpec.of(ToastKind.REWARD, deps.text().toastClaimed()));
            }
            // Re-render the chooser: the claim button hides itself once nothing remains pending.
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        if ("pick".equals(action)) {
            handlePick(ref, store, player, QueueModeId.fromString(data.mode));
            return;
        }
        if ("leave".equals(action)) {
            deps.lobby().leave(playerRef.getUuid());
        }
        // "leave" + "close" + any unknown action: close the screen (leave already de-queued;
        // close keeps the queue reservation). Detach the live listener so no stale push fires.
        detachListener();
        player.getPageManager().setPage(ref, store, Page.None);
    }

    private void handlePick(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                            @Nonnull Player player, @Nullable QueueModeId mode) {
        if (mode == null) {
            player.getPageManager().openCustomPage(ref, store, this); // unknown -> re-render
            return;
        }
        PlayModeHandler handler = deps.handler();
        switch (mode) {
            case PUBLIC -> {
                handler.queuePublic(playerRef, ref, store, presetId);
                // The page reopens itself so it morphs to the live roster (handler owns no page).
                player.getPageManager().openCustomPage(ref, store, this);
            }
            case PARTY -> {
                handler.openParty(playerRef, ref, store, presetId);
                // The handler owns the next page (the party manager).
            }
            case SOLO -> {
                detachListener();
                handler.launchSolo(playerRef, ref, store, presetId);
                // The round launches immediately; close the screen (the round teleport follows).
                player.getPageManager().setPage(ref, store, Page.None);
            }
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        super.onDismiss(ref, store); // sets the dismissed guard
        detachListener();
    }

    @Nonnull
    private static String name(@Nonnull UUID uuid) {
        try {
            PlayerRef p = Universe.get().getPlayer(uuid);
            if (p != null) {
                String live = p.getUsername();
                if (live != null && !live.isBlank()) {
                    return live;
                }
            }
        } catch (Throwable ignored) {
        }
        return uuid.toString().substring(0, 8);
    }
}
