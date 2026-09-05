package com.ziggfreed.common.party.page;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.party.InviteResult;
import com.ziggfreed.common.party.Party;
import com.ziggfreed.common.party.PartyInvite;
import com.ziggfreed.common.party.PartyService;
import com.ziggfreed.common.party.PartySnapshot;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.ZigSearchRow;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;

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
 * Usernames resolve live from {@code Universe.getPlayer} at render time. Every
 * {@code handleDataEvent} exit path sends a response (re-open or {@code Page.None}) or the
 * client spins forever. The {@code .ui} ships once in ziggfreed-common and resolves
 * client-side across the merged asset tree.
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

    private final PartyPageDeps deps;
    private String activeTab = TAB_PARTY;
    private String searchText = "";
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
        PartyScreenMessages t = deps.text();
        cmd.append(PAGE_TEMPLATE);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));
        UiText.setText(cmd, "#Title.Text", t.title());

        UiText.setText(cmd, "#TabParty.Text", t.tabParty());
        UiText.setText(cmd, "#TabInvite.Text", t.tabInvite());
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabParty",
                EventData.of("Action", "tab").append("Target", TAB_PARTY), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabInvite",
                EventData.of("Action", "tab").append("Target", TAB_INVITE), false);
        paintActiveTab(cmd, TAB_INVITE.equals(activeTab) ? "#TabInvite" : "#TabParty");

        UUID viewer = playerRef.getUuid();
        if (TAB_INVITE.equals(activeTab)) {
            buildInviteTab(cmd, events, t, viewer);
        } else {
            buildPartyTab(cmd, events, t, viewer);
        }

        renderToastInto(cmd); // LAST: the toast overlay draws on top of the page content.
    }

    private void paintActiveTab(@Nonnull UICommandBuilder cmd, @Nonnull String sel) {
        UiRetint.retintButtonStates(cmd, sel, TAB_ACTIVE, TAB_ACTIVE, TAB_ACTIVE);
        cmd.set(sel + ".Style.Default.LabelStyle.TextColor", "#ffffff");
        cmd.set(sel + ".Style.Default.LabelStyle.RenderBold", true);
    }

    // ==================== Party tab ====================

    private void buildPartyTab(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                               @Nonnull PartyScreenMessages t, @Nonnull UUID viewer) {
        cmd.set("#SearchRow.Visible", false);
        PartyService svc = deps.service();
        int row = 0;

        // Incoming invites (actionable) first.
        for (PartyInvite inv : svc.pendingInvitesFor(viewer)) {
            if (row >= MAX_ROWS) {
                break;
            }
            String sel = appendRow(cmd, row, name(inv.inviter()), null);
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
            boolean viewerOwns = snap.isOwner(viewer);
            cmd.set("#MemberCount.Visible", true);
            UiText.setText(cmd, "#MemberCount.Text", t.memberCount(snap.size(), snap.maxSize()));
            for (UUID m : snap.members()) {
                if (row >= MAX_ROWS) {
                    break;
                }
                boolean ownerRow = snap.isOwner(m);
                String sel = appendRow(cmd, row, name(m), ownerRow ? t.ownerBadge() : null);
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
                UiText.setText(cmd, "#QueueBtn.Text", t.queueButton());
                events.addEventBinding(CustomUIEventBindingType.Activating, "#QueueBtn", EventData.of("Action", "queue"));
            }
            if (viewerOwns) {
                // Public/private pill: label shows the current state; clicking flips it.
                cmd.set("#PrivacyBtn.Visible", true);
                UiText.setText(cmd, "#PrivacyBtn.Text", snap.privateLobby() ? t.privacyPrivate() : t.privacyPublic());
                events.addEventBinding(CustomUIEventBindingType.Activating, "#PrivacyBtn", EventData.of("Action", "privacy"));
                cmd.set("#DisbandBtn.Visible", true);
                UiText.setText(cmd, "#DisbandBtn.Text", t.disbandButton());
                events.addEventBinding(CustomUIEventBindingType.Activating, "#DisbandBtn", EventData.of("Action", "disband"));
            } else {
                cmd.set("#LeaveBtn.Visible", true);
                UiText.setText(cmd, "#LeaveBtn.Text", t.leaveButton());
                events.addEventBinding(CustomUIEventBindingType.Activating, "#LeaveBtn", EventData.of("Action", "leave"));
            }
        }

        if (row == 0) {
            cmd.set("#EmptyState.Visible", true);
            UiText.setText(cmd, "#EmptyState.Text", t.emptyParty());
        }
    }

    // ==================== Invite tab ====================

    private void buildInviteTab(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                                @Nonnull PartyScreenMessages t, @Nonnull UUID viewer) {
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
                username = p.getUsername();
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
            String sel = appendRow(cmd, row, username, null);
            // The live search text rides the click, so the repaint keeps what was typed.
            bindRowButton(cmd, events, sel, "#RowBtnPrimary", t.inviteButton(),
                    ZigSearchRow.carry(EventData.of("Action", "invite").append("Target", uid.toString()),
                            SEARCH_KEY, SEARCH_ROW));
            row++;
        }
        if (row == 0) {
            cmd.set("#EmptyState.Visible", true);
            UiText.setText(cmd, "#EmptyState.Text", t.emptyInviteList());
        }
    }

    // ==================== row helpers ====================

    @Nonnull
    private String appendRow(@Nonnull UICommandBuilder cmd, int row, @Nonnull String name, @Nullable Message badge) {
        cmd.append("#PartyList", ROW_TEMPLATE);
        String sel = "#PartyList[" + row + "]";
        // Name is a plain String (a proper-noun username): .Text is a client String property that
        // cannot construct from a raw-Message object. The badge (a localized ownerBadge) stays a
        // translation Message, which .Text DOES resolve.
        UiText.setText(cmd, sel + " #RowName.Text", name);
        if (badge != null) {
            cmd.set(sel + " #RowBadge.Visible", true);
            UiText.setText(cmd, sel + " #RowBadge.Text", badge);
        }
        return sel;
    }

    /** Show a row's button with {@code label} and bind its click to {@code data}. */
    private void bindRowButton(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String sel,
                               @Nonnull String btnId, @Nonnull Message label, @Nonnull EventData data) {
        cmd.set(sel + " " + btnId + ".Visible", true);
        UiText.setText(cmd, sel + " " + btnId + ".Text", label);
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
                    // the open menu, and a failed invite is otherwise silent.
                    InviteResult result = svc.invite(viewer, target);
                    if (result == InviteResult.SENT) {
                        showToast(ToastKind.SUCCESS, deps.text().toastInviteSent(name(target)));
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
