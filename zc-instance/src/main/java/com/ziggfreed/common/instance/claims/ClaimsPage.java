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
import com.ziggfreed.common.ui.kit.ContributedLedger;
import com.ziggfreed.common.ui.kit.DetailBindings;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailPainter;
import com.ziggfreed.common.ui.kit.DetailToggle;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.EmptyStatePainter;
import com.ziggfreed.common.ui.kit.LedgerBindings;
import com.ziggfreed.common.ui.kit.LedgerContext;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.menu.MenuFrame;
import com.ziggfreed.common.ui.menu.MenuRail;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastLine;
import com.ziggfreed.common.ui.toast.ToastSounds;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * Claims: every reward the player has earned and not taken, newest first, on the left (the kit's list), with what
 * other modules list on the tab below it (a track's choices not made yet, {@code LedgerContributions.CLAIMS}, each
 * row opening its source's own page), and the one being read on the right (the kit's reading page, its rewards as
 * the library's reward chips). It paints {@link ClaimsView} and decides nothing. Claim (gold) and Claim All are the
 * action bar's rich buttons, on a Claim's page only; a press
 * hands the rewards over through {@link ClaimGrants}, a full bag keeping the rest here, and reopens the page with
 * a toast saying how it went and naming what landed. Picking a row reopens the page, so every build is a full one.
 * It sits in the shared menu frame with the Claims tab selected on the rail. Every {@code handleDataEvent} exit
 * opens a page, closes this one, or sends an empty answer, so the client is never left waiting.
 *
 * <p><b>Spoils.</b> Built with a fight ({@link SpoilsView}), it is that fight's spoils chest: only the player's Claims
 * the fight paid, under the spoils' own words, and Take All taking just those ({@link ClaimGrants#claimFight}). With
 * none left it says so in one line.
 *
 * <p><b>The chest's first screen, which no chest opens now ({@link RewardChestPage} is every chest's one page).</b>
 * Built with a fight and {@code itemsInWindow}, it is what opening the chest showed while something that is not an item waits: those Claims with their items left out, Claim and Take All
 * handing over only what is not an item ({@link ClaimGrants#claimExceptItems},
 * {@link ClaimGrants#claimFightExceptItems}), each press told by the page's own toast. Once nothing but items is left,
 * the page says the rest is in the chest and its one button opens the chest's item window
 * ({@link SpoilsView#openItems}); with no item left either it says so in one line.
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

    /** The fight whose spoils this page shows, or null for every Claim waiting. */
    @Nullable private final String fight;

    /** True on a chest's first screen: only what is not an item is offered here, the items in the chest's window. */
    private final boolean itemsInWindow;

    @Nullable
    private String selected;

    /** The rail this build painted. */
    @Nonnull private MenuRail rail = MenuRail.EMPTY;

    public ClaimsPage(@Nonnull PlayerRef playerRef, @Nonnull ClaimsPageDeps deps, @Nullable String selectedId) {
        this(playerRef, deps, selectedId, null);
    }

    /** @param fight the fight whose spoils to show alone, or null for every Claim waiting */
    public ClaimsPage(@Nonnull PlayerRef playerRef, @Nonnull ClaimsPageDeps deps, @Nullable String selectedId,
            @Nullable String fight) {
        this(playerRef, deps, selectedId, fight, false);
    }

    /**
     * @param fight         the fight whose spoils to show alone, or null for every Claim waiting
     * @param itemsInWindow with a fight, the chest's first screen: only what is not an item here, the items after it
     *                      in the chest's window
     */
    public ClaimsPage(@Nonnull PlayerRef playerRef, @Nonnull ClaimsPageDeps deps, @Nullable String selectedId,
            @Nullable String fight, boolean itemsInWindow) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ClaimsEventData.CODEC);
        this.deps = deps;
        this.selected = selectedId;
        this.fight = fight == null || fight.isBlank() ? null : fight.trim();
        this.itemsInWindow = this.fight != null && itemsInWindow;
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
        cmd.set("#ClaimsTitle.TextSpans", text.line(fight == null ? "page.title" : "spoils.title"));
        // A chest's page reads its own file's line (Text.LeadKey), the boss chest's spoils.lead by default.
        cmd.set("#ClaimsLead.TextSpans", fight == null ? text.line("page.lead") : SpoilsChests.leadOf(fight));
        // The shared menu's rail, the Claims tab selected; painted before the empty-list early return.
        rail = ZigMenu.paint(cmd, events, store, ref, store.getComponent(ref, Player.getComponentType()),
                MenuSlot.CLAIMS.id(), false);

        List<Claim> claims = ClaimsView.newestFirst(waiting());
        // What other modules list on the tab (a track's choices not made yet): only on the plain tab, never a chest.
        ContributedLedger contributed = fight == null ? contributed(store, ref) : null;
        DetailView page = contributed == null || !contributed.hasRow(selected) ? null
                : contributed.page(selected, context(store, ref));
        Claim claim = page != null ? null : ClaimsView.pick(claims, selected);
        if (claim == null && page == null && contributed != null && !contributed.isEmpty()) {
            selected = contributed.firstRow();
            page = contributed.page(selected, context(store, ref));
        }
        if (claim == null && page == null) {
            cmd.set(DETAIL + ".Visible", false);
            cmd.set(EMPTY + ".Visible", true);
            if (itemsInWindow && itemsLeft()) {
                // Nothing but items is left: the button opens the chest's item window.
                EmptyStatePainter.paint(cmd, events, EMPTY, ClaimsView.spoilsItemsLeft(text),
                        EventData.of("Action", ClaimsView.ACTION_ITEMS));
                return;
            }
            EmptyStatePainter.paint(cmd, events, EMPTY,
                    fight == null ? ClaimsView.empty(text) : ClaimsView.spoilsEmpty(text), null);
            return;
        }
        if (claim != null) {
            selected = claim.id();
        }
        try {
            LedgerPainter.paint(cmd, events, LIST, ClaimsView.ledger(claims, text, contributed), Set.of(), selected,
                    ROWS, RowSize.STANDARD, playerRef);
            DetailPainter.paint(cmd, events, DETAIL, claim != null ? ClaimsView.detail(claim, text, fight != null)
                    : page, DETAIL_BINDINGS, playerRef);
        } catch (Throwable t) {
            // build() must not throw: a reading that fails leaves the page as far as it got.
            SafeLog.warn("[claims] the page could not paint the row " + selected, t);
        }
    }

    /** What other modules list on the tab for this player; empty when it cannot be read. */
    @Nonnull
    private ContributedLedger contributed(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return ClaimsView.contributed(context(store, ref));
    }

    /** Who the contributed sources read for: this page's player, now. */
    @Nonnull
    private LedgerContext context(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return new LedgerContext(store, ref, playerRef, System.currentTimeMillis());
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
        if (ClaimsView.ACTION_ITEMS.equals(data.action) && itemsInWindow) {
            UUID uuid = playerRef.getUuid();
            boolean opened = false;
            try {
                opened = uuid != null && SpoilsView.openItems(store, ref, player, playerRef, uuid, fight);
            } catch (Throwable t) {
                SafeLog.warn("[claims] the chest's items could not open; they wait under Claims", t);
            }
            if (!opened) {
                player.getPageManager().openCustomPage(ref, store, this);
            }
            return;
        }
        player.getPageManager().setPage(ref, store, Page.None);
    }

    /**
     * One Claim or Claim All press, told as one toast: everything handed over, a full bag keeping the rest here, or
     * part of it not payable on this server, over a row for each reward it handed over at what really landed
     * ({@link ClaimsView#landedLines}), since the action that paid names what it paid in its own toast: a reputation
     * moved under a page moves no World bar (R290). Unranked, as the page's own answer to the click, so it always
     * shows and a ranked notice the press raised first (a quest the collect completed, a Claim it arrived with) waits
     * to come back after it, where an equal rank would replace that notice for good. A press with nothing to take (a
     * stale page) says nothing; the reopen shows the truth.
     */
    private void press(boolean all, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UUID player = playerRef.getUuid();
        String claimId = selected;
        if (player == null || (!all && claimId == null)) {
            return;
        }
        try {
            ClaimGrants.Result result;
            if (itemsInWindow) {
                result = all ? ClaimGrants.claimFightExceptItems(deps.claims(), player, fight, ref, store,
                        deps.sink()).result()
                        : ClaimGrants.claimExceptItems(deps.claims(), player, claimId, ref, store, deps.sink());
            } else if (!all) {
                result = ClaimGrants.claim(deps.claims(), player, claimId, ref, store, deps.sink());
            } else if (fight == null) {
                result = ClaimGrants.claimAll(deps.claims(), player, ref, store, deps.sink());
            } else {
                result = ClaimGrants.claimFight(deps.claims(), player, fight, ref, store, deps.sink());
            }
            ClaimsText text = deps.text();
            if (result.nothing()) {
                return;
            }
            List<ToastLine> landed = ClaimsView.landedLines(result.collected());
            if (result.anyKept()) {
                showToast(ToastSpec.of(ToastKind.WARNING, text.line("toast.kept")).withLines(landed));
            } else if (result.failed() > 0) {
                showToast(ToastSpec.of(ToastKind.WARNING, text.line("toast.failed")).withLines(landed));
            } else {
                // On the chest's first screen the items stay for the window, so even Take All just says Claimed.
                showToast(ToastSpec.of(ToastKind.REWARD,
                        text.line(all && !itemsInWindow ? "toast.claimed_all" : "toast.claimed"))
                        .withLines(landed)
                        .withSound(ToastSounds.RECEIPT));
            }
        } catch (Throwable t) {
            SafeLog.warn("[claims] a Claim press failed", t);
        }
    }

    /**
     * The Claims waiting for this player, the fight's alone on its spoils; on the chest's first screen only those
     * holding something that is not an item, shown without their items. A failed read shows none.
     */
    @Nonnull
    private List<Claim> waiting() {
        try {
            UUID player = playerRef.getUuid();
            List<Claim> all = player == null ? List.of() : deps.claims().list(player);
            if (fight == null) {
                return all;
            }
            List<Claim> spoils = all.stream().filter(claim -> claim.fromFight(fight)).toList();
            if (!itemsInWindow) {
                return spoils;
            }
            return spoils.stream().filter(ClaimGrants::hasNonItem)
                    .map(claim -> claim.withRewards(claim.rewards().stream().filter(r -> !r.isItem()).toList()))
                    .toList();
        } catch (Throwable t) {
            SafeLog.warn("[claims] the page could not read the Claims waiting", t);
            return List.of();
        }
    }

    /** Whether an item from the fight still waits for this player, for the chest's window; false on a failed read. */
    private boolean itemsLeft() {
        try {
            UUID player = playerRef.getUuid();
            return player != null && fight != null && SpoilsView.hasItems(deps.claims(), player, fight);
        } catch (Throwable t) {
            SafeLog.warn("[claims] the page could not read the items waiting", t);
            return false;
        }
    }

    /** An empty update, for an event nothing opened or closed for: the client always hears back. */
    private void answer() {
        sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }
}
