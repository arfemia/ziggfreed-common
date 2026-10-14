package com.ziggfreed.common.party.page;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.party.InviteResult;
import com.ziggfreed.common.party.Party;
import com.ziggfreed.common.party.PartyInvite;
import com.ziggfreed.common.party.PartyService;
import com.ziggfreed.common.party.PartySnapshot;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.ZigSearchRow;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The generic party + invite screen (the {@code KweebecLeaderboardPage}/{@code DialoguePage}
 * twin). Two tabs over the one shared {@code $F.@ZigDecoratedFrame}:
 * <ul>
 *   <li><b>Party</b> - the viewer's incoming invites (Accept / Decline), then their
 *       party roster (owner badge; Kick on others when the viewer owns it), and a footer
 *       (Queue + Disband for the owner, Leave for a member).</li>
 *   <li><b>Invite</b> - a searchable list of currently-online players not already in a
 *       party, each with an Invite button (no friends list; the roster is live). The search
 *       is the shared {@link ZigSearchRow}: Search submits, Clear empties, and an Invite
 *       click carries the live text along so a name typed but not yet searched survives
 *       the repaint.</li>
 * </ul>
 *
 * <p>All consumer policy (the {@link PartyService}, the chrome {@link PartyScreenMessages},
 * the Queue handoff) is supplied through {@link PartyPageDeps}; the page is mod-agnostic.
 * Names come from the library's display-name seam at render time. Every
 * {@code handleDataEvent} exit path sends a response (re-open or {@code Page.None}) or the
 * client spins forever. The {@code .ui} ships once in ziggfreed-common and resolves
 * client-side across the merged asset tree.
 *
 * <p><b>Live repaint.</b> An open page repaints itself when its party changes, so a member
 * who joins, leaves or is kicked shows on everyone's open page, not only the actor's. The
 * first build wires one listener on the page's {@link PartyService} (one per service, by
 * identity, so a consumer wires nothing). A change queues a reopen of this SAME instance on
 * the viewer's own world thread, which keeps its tab, search text and chosen difficulty, for
 * every viewer on the Party tab who is in the party, holds an invite from it, or was last
 * shown it (the kicked, the departed, the disbanded and a stale invite row). The listener
 * runs under the service's lock, so it only reads and queues; the reopen happens after a
 * recheck that this page is still the one the viewer has open. A viewer on the Invite tab is
 * never repainted: a reopen would steal the search field's focus and drop what is typed in it.
 */
public class PartyInvitePage extends ToastablePage<PartyEventData> {

    private static final String PAGE_TEMPLATE = "Pages/ZigPartyPage.ui";
    private static final String ROW_TEMPLATE = "Pages/ZigPartyRow.ui";
    private static final int MAX_ROWS = 100;

    private static final String TAB_PARTY = "party";
    private static final String TAB_INVITE = "invite";
    /** Active-tab tint (matches the leaderboard page's painted active tab). */
    private static final String TAB_ACTIVE = "#5a7ba8";

    /** The invite tab's search row, an instance of the shared {@link ZigSearchRow}. */
    private static final String SEARCH_ROW = "#SearchRow";

    /**
     * The key the search row's live text rides under. The {@code @} is what makes the client
     * resolve the value as an element path; {@link PartyEventData} declares the same key.
     */
    private static final String SEARCH_KEY = "@Query";

    /**
     * The party page each viewer has open (viewer uuid to page): put on every build, removed on
     * dismiss only while it still maps to that instance. A party change walks it for the pages to
     * repaint; an entry a disconnect left behind is harmless, since a repaint first rechecks that the
     * page is still the one open.
     */
    private static final ConcurrentHashMap<UUID, PartyInvitePage> OPEN = new ConcurrentHashMap<>();

    /** The services already carrying this page's listener, by identity: one listener per service. */
    private static final Set<PartyService> WIRED =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    /** Logs the first repaint that throws, never one line per party change. */
    private static final AtomicBoolean REPAINT_FAILURE_LOGGED = new AtomicBoolean(false);

    private final PartyPageDeps deps;
    /** Volatile: the party listener reads it off the world thread to skip an Invite-tab viewer. */
    private volatile String activeTab = TAB_PARTY;
    private String searchText = "";
    /**
     * The party ids the last Party-tab build showed: the viewer's own party and every party an invite
     * row came from (empty on the Invite tab). Replaced whole, never mutated, since the listener reads
     * it off the world thread.
     */
    private volatile Set<String> shownPartyIds = Set.of();
    /**
     * True while a repaint waits on the viewer's world thread: set by the listener's queueing, cleared
     * by the queued task and at the top of every build, so a viewer whose own click reopened the page
     * is never painted twice for one change.
     */
    private final AtomicBoolean repaintQueued = new AtomicBoolean(false);
    /** The difficulty chosen upstream (Play screen -> Party mode); threaded to the Queue handler. */
    @Nullable
    private String presetId = null;

    public PartyInvitePage(@Nonnull PlayerRef playerRef, @Nonnull PartyPageDeps deps) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PartyEventData.CODEC);
        this.deps = deps;
    }

    public PartyInvitePage(@Nonnull PlayerRef playerRef, @Nonnull PartyPageDeps deps,
                           @Nonnull String activeTab, @Nullable String searchText) {
        this(playerRef, deps);
        this.activeTab = TAB_INVITE.equals(activeTab) ? TAB_INVITE : TAB_PARTY;
        this.searchText = searchText == null ? "" : searchText;
    }

    /** As the 4-arg form, plus the chosen difficulty the Queue button carries into the lobby. */
    public PartyInvitePage(@Nonnull PlayerRef playerRef, @Nonnull PartyPageDeps deps,
                           @Nonnull String activeTab, @Nullable String searchText, @Nullable String presetId) {
        this(playerRef, deps, activeTab, searchText);
        this.presetId = presetId;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        // Any build paints the state as it is now, so a repaint queued before it has nothing left to do.
        repaintQueued.set(false);
        wire(deps.service());
        UUID viewer = playerRef.getUuid();
        if (viewer != null) {
            OPEN.put(viewer, this);
        }

        PartyScreenMessages t = deps.text();
        cmd.append(PAGE_TEMPLATE);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));
        // Labels take TextSpans (the viewer's locale, params filled), and so does every button's #Label:
        // each button is a ZigRichButton.
        cmd.set("#Title.TextSpans", t.title());

        ZigRichButton.text(cmd, "#TabParty", t.tabParty());
        ZigRichButton.text(cmd, "#TabInvite", t.tabInvite());
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabParty",
                EventData.of("Action", "tab").append("Target", TAB_PARTY), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabInvite",
                EventData.of("Action", "tab").append("Target", TAB_INVITE), false);
        paintActiveTab(cmd, TAB_INVITE.equals(activeTab) ? "#TabInvite" : "#TabParty");

        if (TAB_INVITE.equals(activeTab)) {
            buildInviteTab(cmd, events, t, viewer);
        } else {
            buildPartyTab(cmd, events, t, viewer);
        }

        renderToastInto(cmd); // LAST: the toast overlay draws on top of the page content.
    }

    /** The chosen tab: its backgrounds in the active tint, and its #Label white and bold. */
    private void paintActiveTab(@Nonnull UICommandBuilder cmd, @Nonnull String sel) {
        UiRetint.retintButtonStates(cmd, sel, TAB_ACTIVE, TAB_ACTIVE, TAB_ACTIVE);
        ZigRichButton.color(cmd, sel, "#ffffff");
        cmd.set(sel + " " + ZigRichButton.LABEL + ".Style.RenderBold", true);
    }

    // ==================== Party tab ====================

    private void buildPartyTab(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                               @Nonnull PartyScreenMessages t, @Nonnull UUID viewer) {
        cmd.set("#SearchRow.Visible", false);
        PartyService svc = deps.service();
        int row = 0;
        Set<String> shown = new HashSet<>();

        // Incoming invites (actionable) first.
        for (PartyInvite inv : svc.pendingInvitesFor(viewer)) {
            if (row >= MAX_ROWS) {
                break;
            }
            shown.add(inv.partyId());
            String sel = appendRow(cmd, row, PlayerDisplayNames.displayName(inv.inviter(), null), null);
            bindRowButton(cmd, events, sel, "#RowBtnPrimary", t.acceptButton(),
                    EventData.of("Action", "accept").append("PartyId", inv.partyId()));
            bindRowButton(cmd, events, sel, "#RowBtnSecondary", t.declineButton(),
                    EventData.of("Action", "decline").append("PartyId", inv.partyId()));
            row++;
        }

        // The viewer's own party roster.
        Party party = svc.partyOf(viewer);
        PartySnapshot snap = party != null ? party.snapshot() : null;
        if (snap != null) {
            shown.add(snap.id());
            boolean viewerOwns = snap.isOwner(viewer);
            cmd.set("#MemberCount.Visible", true);
            cmd.set("#MemberCount.TextSpans", t.memberCount(snap.size(), snap.maxSize()));
            for (UUID m : snap.members()) {
                if (row >= MAX_ROWS) {
                    break;
                }
                boolean ownerRow = snap.isOwner(m);
                String sel = appendRow(cmd, row, PlayerDisplayNames.displayName(m, null), ownerRow ? t.ownerBadge() : null);
                if (viewerOwns && !ownerRow) {
                    bindRowButton(cmd, events, sel, "#RowBtnPrimary", t.kickButton(),
                            EventData.of("Action", "kick").append("Target", m.toString()));
                }
                row++;
            }

            // Footer: Queue + Privacy + Disband (owner) or Leave (member).
            cmd.set("#FooterRow.Visible", true);
            if (viewerOwns && deps.queueHandler() != null) {
                cmd.set("#QueueBtn.Visible", true);
                ZigRichButton.text(cmd, "#QueueBtn", t.queueButton());
                events.addEventBinding(CustomUIEventBindingType.Activating, "#QueueBtn", EventData.of("Action", "queue"));
            }
            if (viewerOwns) {
                // Public/private pill: label shows the current state; clicking flips it. Privacy only decides
                // how the party queues, so with no queue handler there is nothing for it to change.
                if (deps.queueHandler() != null) {
                    cmd.set("#PrivacyBtn.Visible", true);
                    ZigRichButton.text(cmd, "#PrivacyBtn",
                            snap.privateLobby() ? t.privacyPrivate() : t.privacyPublic());
                    events.addEventBinding(CustomUIEventBindingType.Activating, "#PrivacyBtn",
                            EventData.of("Action", "privacy"));
                }
                cmd.set("#DisbandBtn.Visible", true);
                ZigRichButton.text(cmd, "#DisbandBtn", t.disbandButton());
                events.addEventBinding(CustomUIEventBindingType.Activating, "#DisbandBtn", EventData.of("Action", "disband"));
            } else {
                cmd.set("#LeaveBtn.Visible", true);
                ZigRichButton.text(cmd, "#LeaveBtn", t.leaveButton());
                events.addEventBinding(CustomUIEventBindingType.Activating, "#LeaveBtn", EventData.of("Action", "leave"));
            }
        }

        if (row == 0) {
            cmd.set("#EmptyState.Visible", true);
            cmd.set("#EmptyState.TextSpans", t.emptyParty());
        }
        // Read by the party listener off the world thread: one immutable set, swapped whole.
        shownPartyIds = Set.copyOf(shown);
    }

    // ==================== Invite tab ====================

    private void buildInviteTab(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                                @Nonnull PartyScreenMessages t, @Nonnull UUID viewer) {
        shownPartyIds = Set.of(); // the Invite tab shows no party, and the listener skips it anyway
        cmd.set(SEARCH_ROW + ".Visible", true);
        ZigSearchRow.wire(cmd, events, SEARCH_ROW, searchText, SEARCH_KEY,
                EventData.of("Action", "search"), EventData.of("Action", "clear"));

        PartyService svc = deps.service();
        String q = searchText == null ? "" : searchText.trim().toLowerCase(Locale.ROOT);
        int row = 0;
        for (PlayerRef p : onlinePlayers()) {
            if (row >= MAX_ROWS) {
                break;
            }
            UUID uid;
            String username;
            try {
                uid = p.getUuid();
                username = p.getUsername(); // NAME-DATA-OK: the invite search matches the bare username, never a decorated one
            } catch (Throwable e) {
                continue;
            }
            if (uid == null || uid.equals(viewer) || svc.isInParty(uid)) {
                continue;
            }
            if (username == null || username.isBlank()) {
                continue;
            }
            if (!q.isEmpty() && !username.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            String sel = appendRow(cmd, row, PlayerDisplayNames.displayName(uid, username), null);
            // The live search text rides the click, so the repaint keeps what was typed.
            bindRowButton(cmd, events, sel, "#RowBtnPrimary", t.inviteButton(),
                    ZigSearchRow.carry(EventData.of("Action", "invite").append("Target", uid.toString()),
                            SEARCH_KEY, SEARCH_ROW));
            row++;
        }
        if (row == 0) {
            cmd.set("#EmptyState.Visible", true);
            cmd.set("#EmptyState.TextSpans", t.emptyInviteList());
        }
    }

    // ==================== row helpers ====================

    @Nonnull
    private String appendRow(@Nonnull UICommandBuilder cmd, int row, @Nonnull Message name, @Nullable Message badge) {
        cmd.append("#PartyList", ROW_TEMPLATE);
        String sel = "#PartyList[" + row + "]";
        // The name is the library's display name, a Message (a title rides with it), and the badge a
        // consumer's ownerBadge line; both are Labels, so both go on TextSpans, whatever the Message carries.
        cmd.set(sel + " #RowName.TextSpans", name);
        if (badge != null) {
            cmd.set(sel + " #RowBadge.Visible", true);
            cmd.set(sel + " #RowBadge.TextSpans", badge);
        }
        return sel;
    }

    /** Show a row's button with {@code label} and bind its click to {@code data}. */
    private void bindRowButton(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String sel,
                               @Nonnull String btnId, @Nonnull Message label, @Nonnull EventData data) {
        cmd.set(sel + " " + btnId + ".Visible", true);
        ZigRichButton.text(cmd, sel + " " + btnId, label);
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " " + btnId, data, false);
    }

    @Nonnull
    private static List<PlayerRef> onlinePlayers() {
        try {
            return new ArrayList<>(Universe.get().getPlayers());
        } catch (Throwable t) {
            return List.of();
        }
    }

    // ==================== live repaint (party listener -> reopen on the viewer's world thread) ====================

    /** Register the page's one listener on {@code svc}, the first time any page builds over it. */
    private static void wire(@Nonnull PartyService svc) {
        if (WIRED.add(svc)) {
            svc.addListener(snap -> repaintAffected(svc, snap));
        }
    }

    /**
     * The listener: queue a repaint on every open page over {@code svc} that shows {@code snap}'s
     * party. Runs synchronously under the service's lock on the mutating thread, so it only reads
     * volatile state and queues; it never blocks and never opens a page here.
     */
    private static void repaintAffected(@Nonnull PartyService svc, @Nonnull PartySnapshot snap) {
        for (Map.Entry<UUID, PartyInvitePage> e : OPEN.entrySet()) {
            PartyInvitePage page = e.getValue();
            if (page.deps.service() == svc && page.shows(e.getKey(), snap)) {
                page.queueRepaint();
            }
        }
    }

    /**
     * Whether this page, viewed by {@code viewer}, shows the party {@code snap} describes: the Party
     * tab only, for a member, an invitee, or a viewer last shown that party (which covers the kicked,
     * the departed, the disbanded and an invite row gone stale).
     */
    private boolean shows(@Nonnull UUID viewer, @Nonnull PartySnapshot snap) {
        if (!TAB_PARTY.equals(activeTab)) {
            return false;
        }
        if (snap.members().contains(viewer) || shownPartyIds.contains(snap.id())) {
            return true;
        }
        for (PartyInvite inv : snap.pendingInvites()) {
            if (viewer.equals(inv.invitee())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Queue one reopen of this page on the viewer's world thread, resolved the way the toast push
     * resolves it; a repaint already waiting covers this change too, since it reads the state as it
     * is when it runs. Called off the world thread (under the service's lock), so it only enqueues.
     */
    private void queueRepaint() {
        if (!repaintQueued.compareAndSet(false, true)) {
            return;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            repaintQueued.set(false);
            return;
        }
        Store<EntityStore> store;
        World world;
        try {
            store = ref.getStore();
            world = store.getExternalData().getWorld();
        } catch (Throwable t) {
            repaintQueued.set(false);
            return;
        }
        if (world == null) {
            repaintQueued.set(false);
            return;
        }
        try {
            world.execute(() -> repaintNow(ref, store));
        } catch (Throwable t) {
            repaintQueued.set(false); // the world has stopped taking tasks
        }
    }

    /**
     * The queued reopen, on the viewer's world thread: skipped when a build since the queueing already
     * painted the change, or when this page is no longer the one the player has open. Reopens THIS
     * instance with the player's own ref, so the tab, the search text and the difficulty carry over.
     */
    private void repaintNow(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (!repaintQueued.getAndSet(false)) {
            return;
        }
        try {
            if (isDismissed() || !ref.isValid()) {
                return;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || player.getPageManager().getCustomPage() != this) {
                return;
            }
            player.getPageManager().openCustomPage(ref, store, this);
        } catch (Throwable t) {
            if (REPAINT_FAILURE_LOGGED.compareAndSet(false, true)) {
                SafeLog.warn("[party] an open party page failed to repaint on a party change (logged once)", t);
            }
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        // Remove this instance's entry only, never a newer party page the same viewer has since built.
        UUID viewer = playerRef.getUuid();
        if (viewer != null) {
            OPEN.remove(viewer, this);
        }
        super.onDismiss(ref, store);
    }

    // ==================== event handling ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull PartyEventData data) {
        Player player = (Player) store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        PartyService svc = deps.service();
        UUID viewer = playerRef.getUuid();
        String action = data.action == null ? "" : data.action;

        // The live field text rides every binding that carries it, so it is the search truth
        // whenever it arrives; a click that carries none keeps the last searched text.
        if (data.query != null) {
            this.searchText = data.query;
        }

        switch (action) {
            case "search" -> {
                // The row's Search button: the carried text above is the whole change.
            }
            case "clear" -> this.searchText = "";
            case "tab" -> this.activeTab = TAB_INVITE.equals(data.target) ? TAB_INVITE : TAB_PARTY;
            case "invite" -> {
                UUID target = parseUuid(data.target);
                if (target != null) {
                    // Toast the outcome in-page: the PartyService Notify feed is hidden behind
                    // the open menu, and a failed invite is otherwise silent. A toast names the
                    // invitee plainly: a title shows in menus and leaderboards only.
                    InviteResult result = svc.invite(viewer, target);
                    if (result == InviteResult.SENT) {
                        showToast(ToastKind.SUCCESS,
                                deps.text().toastInviteSent(PlayerDisplayNames.plainName(target, null)));
                    } else {
                        showToast(ToastKind.WARNING, deps.text().toastInviteFailed());
                    }
                }
            }
            case "accept" -> {
                if (data.partyId != null) {
                    svc.accept(viewer, data.partyId);
                }
                this.activeTab = TAB_PARTY;
            }
            case "decline" -> {
                if (data.partyId != null) {
                    svc.decline(viewer, data.partyId);
                }
            }
            case "kick" -> {
                UUID target = parseUuid(data.target);
                if (target != null) {
                    svc.kick(viewer, target);
                }
            }
            case "leave" -> svc.leave(viewer);
            case "disband" -> svc.disband(viewer);
            case "privacy" -> {
                Party party = svc.partyOf(viewer);
                if (party != null && party.isOwner(viewer)) {
                    boolean next = !party.isPrivate();
                    svc.setPrivate(viewer, next);
                    showToast(ToastKind.SUCCESS, next ? deps.text().privacyPrivate() : deps.text().privacyPublic());
                }
            }
            case "queue" -> {
                PartyQueueHandler handler = deps.queueHandler();
                if (handler != null) {
                    try {
                        handler.queue(ref, store, presetId);
                    } catch (Throwable ignored) {
                    }
                    return; // the handler owns the next page (queue screen / close)
                }
            }
            default -> {
                player.getPageManager().setPage(ref, store, Page.None);
                return;
            }
        }
        player.getPageManager().openCustomPage(ref, store, this);
    }

    @Nullable
    private static UUID parseUuid(@Nullable String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
