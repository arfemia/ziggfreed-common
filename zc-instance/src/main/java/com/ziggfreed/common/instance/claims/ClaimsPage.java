package com.ziggfreed.common.instance.claims;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailBindings;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailPainter;
import com.ziggfreed.common.ui.kit.DetailToggle;
import com.ziggfreed.common.ui.kit.EmptyStatePainter;
import com.ziggfreed.common.ui.kit.LedgerBindings;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.menu.MenuFrame;
import com.ziggfreed.common.ui.menu.MenuRail;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSounds;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * Claims: every reward the player has earned and not taken, newest first, on the left (the kit's list), and the
 * one being read on the right (the kit's reading page, its rewards as the library's reward chips). It paints
 * {@link ClaimsView} and decides nothing. Claim (gold) and Claim All are the action bar's rich buttons; a press
 * hands the rewards over through {@link ClaimGrants}, a full bag keeping the rest here, and reopens the page with
 * a toast saying how it went. Picking a row reopens the page, so every build is a full one. It sits in the shared
 * menu frame with the Claims tab selected on the rail. Every {@code handleDataEvent} exit opens a page, closes
 * this one, or sends an empty answer, so the client is never left waiting.
 */
public final class ClaimsPage extends ToastablePage<ClaimsEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigClaimsPage.ui";
    static final String LIST = "#ClaimsList";
    static final String DETAIL = "#Page";
    static final String EMPTY = "#ClaimsEmpty";

    /** A row's click picks it; the one section never folds and never caps short. */
    private static final LedgerBindings ROWS = new LedgerBindings() {
        @Override
        public EventData row(LedgerSection s, LedgerRow r) {
            return EventData.of("Action", "select").append("Claim", r.id());
        }

        @Override
        public EventData section(LedgerSection s) {
            return null;
        }

        @Override
        public EventData showMore(LedgerSection s) {
            return null;
        }
    };

    /** The reading page opens nothing: no line is selectable and there is no toggle. */
    private static final DetailBindings DETAIL_BINDINGS = new DetailBindings() {
        @Override
        public EventData line(DetailBlock b, DetailLine l) {
            return null;
        }

        @Override
        public EventData toggle(DetailToggle t) {
            return null;
        }
    };

    @Nonnull private final ClaimsPageDeps deps;

    @Nullable
    private String selected;

    /** The rail this build painted. */
    @Nonnull private MenuRail rail = MenuRail.EMPTY;

    public ClaimsPage(@Nonnull PlayerRef playerRef, @Nonnull ClaimsPageDeps deps, @Nullable String selectedId) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ClaimsEventData.CODEC);
        this.deps = deps;
        this.selected = selectedId;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        try {
            paint(ref, cmd, events, store);
        } catch (Throwable t) {
            // build() must not throw: a reading that fails leaves the page as far as it got.
            SafeLog.warn("[claims] the page could not be built", t);
        }
        // Last, so the toast is the top sibling and draws over the page.
        renderToastInto(cmd);
    }

    private void paint(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ClaimsText text = deps.text();
        ZigMenu.appendThemed(cmd, PAGE_TEMPLATE, MenuFrame.RAIL, "#RightPanel");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));
        // The action bar's buttons are live elements, bound once per build.
        DetailPainter.bindActionsOnce(events, DETAIL, ClaimsPage::actionFor, null);
        cmd.set("#ClaimsTitle.TextSpans", text.line("page.title"));
        cmd.set("#ClaimsLead.TextSpans", text.line("page.lead"));
        // The shared menu's rail, the Claims tab selected; painted before the empty-list early return.
        rail = ZigMenu.paint(cmd, events, store, ref, store.getComponent(ref, Player.getComponentType()),
                MenuSlot.CLAIMS.id(), false);

        List<Claim> claims = ClaimsView.newestFirst(waiting());
        Claim claim = ClaimsView.pick(claims, selected);
        if (claim == null) {
            cmd.set(DETAIL + ".Visible", false);
            cmd.set(EMPTY + ".Visible", true);
            EmptyStatePainter.paint(cmd, events, EMPTY, ClaimsView.empty(text), null);
            return;
        }
        selected = claim.id();
        try {
            LedgerPainter.paint(cmd, events, LIST, ClaimsView.ledger(claims, text), Set.of(), selected, ROWS,
                    RowSize.STANDARD, playerRef);
            DetailPainter.paint(cmd, events, DETAIL, ClaimsView.detail(claim, text), DETAIL_BINDINGS, playerRef);
        } catch (Throwable t) {
            // build() must not throw: a reading that fails leaves the page as far as it got.
            SafeLog.warn("[claims] the page could not paint the Claim " + claim.id(), t);
        }
    }

    /** The press each action-bar button carries: Claim on the primary, Claim All on the secondary. */
    @Nullable
    private static EventData actionFor(@Nonnull ActionSlot slot) {
        return switch (slot) {
            case PRIMARY -> EventData.of("Action", ClaimsView.ACTION_CLAIM);
            case SECONDARY -> EventData.of("Action", ClaimsView.ACTION_CLAIM_ALL);
            case DANGER -> null;
        };
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull ClaimsEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            answer();
            return;
        }
        if (rail.handle(data.menu, store, ref, player, this::answer)) {
            return;
        }
        if ("select".equals(data.action) && data.claim != null && !data.claim.isBlank()) {
            selected = data.claim;
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        if (ClaimsView.ACTION_CLAIM.equals(data.action) || ClaimsView.ACTION_CLAIM_ALL.equals(data.action)) {
            press(ClaimsView.ACTION_CLAIM_ALL.equals(data.action), ref, store);
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        player.getPageManager().setPage(ref, store, Page.None);
    }

    /**
     * One Claim or Claim All press, told as a toast: everything handed over, a full bag keeping the rest here, or
     * part of it not payable on this server. A press with nothing to take (a stale page) says nothing; the reopen
     * shows the truth.
     */
    private void press(boolean all, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UUID player = playerRef.getUuid();
        String claimId = selected;
        if (player == null || (!all && claimId == null)) {
            return;
        }
        try {
            ClaimGrants.Result result = all
                    ? ClaimGrants.claimAll(deps.claims(), player, ref, store, deps.sink())
                    : ClaimGrants.claim(deps.claims(), player, claimId, ref, store, deps.sink());
            ClaimsText text = deps.text();
            if (result.nothing()) {
                return;
            }
            if (result.anyKept()) {
                showToast(ToastKind.WARNING, text.line("toast.kept"));
            } else if (result.failed() > 0) {
                showToast(ToastKind.WARNING, text.line("toast.failed"));
            } else {
                showToast(ToastSpec.of(ToastKind.REWARD, text.line(all ? "toast.claimed_all" : "toast.claimed"))
                        .withSound(ToastSounds.RECEIPT));
            }
        } catch (Throwable t) {
            SafeLog.warn("[claims] a Claim press failed", t);
        }
    }

    /** The Claims waiting for this player; a failed read shows none. */
    @Nonnull
    private List<Claim> waiting() {
        try {
            UUID player = playerRef.getUuid();
            return player == null ? List.of() : deps.claims().list(player);
        } catch (Throwable t) {
            SafeLog.warn("[claims] the page could not read the Claims waiting", t);
            return List.of();
        }
    }

    /** An empty update, for an event nothing opened or closed for: the client always hears back. */
    private void answer() {
        sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }
}
