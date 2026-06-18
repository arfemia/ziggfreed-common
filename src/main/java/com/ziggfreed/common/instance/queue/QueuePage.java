package com.ziggfreed.common.instance.queue;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.lobby.LobbyConfig;
import com.ziggfreed.common.lobby.MatchmakingQueue;
import com.ziggfreed.common.lobby.QueueSnapshot;
import com.ziggfreed.common.lobby.QueueState;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;

/**
 * The generic queue / "ready" screen the player stays on after queueing. Shows the live
 * roster, an {@code X/Y players} count, the queue state (waiting / launching in N), and a
 * derived wait estimate, plus three buttons:
 * <ul>
 *   <li><b>Refresh</b> - re-render the current snapshot (manual; the live countdown is
 *       the queue's existing {@code EventTitles} banner).</li>
 *   <li><b>Leave</b> - de-queue ({@code LobbyService.leave}) and close.</li>
 *   <li><b>Close</b> (the frame's close button) - close the screen but STAY QUEUED in
 *       the background (the {@code queuedTo} reservation persists); re-open later.</li>
 * </ul>
 *
 * <p>Consumer policy (the {@code LobbyService}, the chrome {@link QueueScreenMessages}) is
 * supplied through {@link QueuePageDeps}; the page is mod-agnostic. Usernames resolve live
 * at render time. Every {@code handleDataEvent} exit path sends a response.
 */
public class QueuePage extends ToastablePage<QueueEventData> {

    private static final String PAGE_TEMPLATE = "Pages/ZigQueuePage.ui";
    private static final String ROW_TEMPLATE = "Pages/ZigQueueRosterRow.ui";
    private static final int MAX_ROWS = 32;

    private final QueuePageDeps deps;
    /** One-shot: the "you're queued" toast fires only on the first open, not on a Refresh reopen. */
    private boolean queuedToastPrimed = false;

    public QueuePage(@Nonnull PlayerRef playerRef, @Nonnull QueuePageDeps deps) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, QueueEventData.CODEC);
        this.deps = deps;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        QueueScreenMessages t = deps.text();
        cmd.append(PAGE_TEMPLATE);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));
        cmd.set("#Title.Text", t.title());

        MatchmakingQueue queue = deps.lobby().currentQueueOf(playerRef.getUuid());
        if (queue == null) {
            cmd.set("#Status.Text", t.notInQueue());
            renderToastInto(cmd);
            return;
        }

        QueueSnapshot snap = queue.snapshot();
        LobbyConfig cfg = snap.config();
        cmd.set("#PlayerCount.Text", t.playerCount(snap.size(), cfg.maxParty()));
        cmd.set("#Status.Text", statusFor(t, snap));
        cmd.set("#WaitEstimate.Text", t.waitEstimate(cfg.fillTimeoutSeconds() + cfg.countdownSeconds()));

        List<UUID> members = snap.members();
        for (int i = 0; i < members.size() && i < MAX_ROWS; i++) {
            cmd.append("#RosterList", ROW_TEMPLATE);
            String sel = "#RosterList[" + i + "]";
            // Plain String (a proper-noun username), not a raw Message: .Text is a client String
            // property that cannot construct from a raw-Message object (aborts the CustomUI update).
            cmd.set(sel + " #RowName.Text", name(members.get(i)));
            if (i == 0) {
                cmd.set(sel + " #RowName.Style.TextColor", "#ffd97a"); // initiator
            }
        }

        cmd.set("#RefreshBtn.Visible", true);
        cmd.set("#RefreshBtn.Text", t.refreshButton());
        cmd.set("#LeaveBtn.Visible", true);
        cmd.set("#LeaveBtn.Text", t.leaveButton());
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RefreshBtn", EventData.of("Action", "refresh"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#LeaveBtn", EventData.of("Action", "leave"));

        // One-shot in-page "you're queued" toast on first open (the feed is hidden behind the
        // menu). Primed (no push) so this same build's renderToastInto paints it; a Refresh
        // reopen does not re-fire it.
        if (!queuedToastPrimed) {
            queuedToastPrimed = true;
            primeToast(ToastSpec.of(ToastKind.INFO, t.toastQueued(snap.size(), cfg.maxParty())));
        }

        renderToastInto(cmd); // LAST: the toast overlay draws on top of the page content.
    }

    @Nonnull
    private static Message statusFor(@Nonnull QueueScreenMessages t, @Nonnull QueueSnapshot snap) {
        QueueState state = snap.state();
        if (state == QueueState.COUNTDOWN) {
            return t.statusCountdown(snap.countdownRemaining());
        }
        if (state == QueueState.LAUNCHING) {
            return t.statusLaunching();
        }
        return t.statusWaiting();
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull QueueEventData data) {
        Player player = (Player) store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        String action = data.action == null ? "" : data.action;
        if ("refresh".equals(action)) {
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        if ("leave".equals(action)) {
            deps.lobby().leave(playerRef.getUuid());
        }
        // "leave" + "close" + any unknown action: close the screen (leave already de-queued;
        // close keeps the queue reservation).
        player.getPageManager().setPage(ref, store, Page.None);
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
