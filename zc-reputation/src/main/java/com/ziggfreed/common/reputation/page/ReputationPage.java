package com.ziggfreed.common.reputation.page;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

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
import com.ziggfreed.common.progress.gate.GatedContent;
import com.ziggfreed.common.reputation.ReputationBeyond;
import com.ziggfreed.common.reputation.ReputationDef;
import com.ziggfreed.common.reputation.ReputationLadder;
import com.ziggfreed.common.reputation.ReputationRuntime;
import com.ziggfreed.common.reputation.ReputationService;
import com.ziggfreed.common.reputation.ReputationText;
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
import com.ziggfreed.common.ui.toast.RewardToastLines;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSounds;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * Reputation: every reputation the player has met on the left (the kit's list), the one being read on the
 * right (the kit's reading page). It paints {@link ReputationView} and decides nothing. Picking a row reopens
 * the page, so every build is a full one. It sits in the shared menu frame with the Reputation tab selected on
 * the rail; what its document must declare is {@link ReputationPageLayout}'s. The reading page's gold Collect
 * button, shown while Beyond payouts wait, hands the press to {@link ReputationBeyond#collect} and reopens the
 * page with a toast saying how it went. Every {@code handleDataEvent} exit opens a page, closes this one, or
 * sends an empty answer (a rail click nothing opened for, an event with no player behind it), so the client is
 * never left waiting.
 */
public final class ReputationPage extends ToastablePage<ReputationEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigReputationPage.ui";
    static final String LIST = "#ReputationList";
    static final String DETAIL = "#Page";
    static final String EMPTY = "#ReputationEmpty";

    /** A row's click picks it; the one section never folds and never caps short. */
    private static final LedgerBindings ROWS = new LedgerBindings() {
        @Override
        public EventData row(LedgerSection s, LedgerRow r) {
            return EventData.of("Action", "select").append("Reputation", r.id());
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

    @Nullable
    private String selected;

    /** The rail this build painted. */
    @Nonnull private MenuRail rail = MenuRail.EMPTY;

    public ReputationPage(@Nonnull PlayerRef playerRef, @Nullable String selectedId) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ReputationEventData.CODEC);
        this.selected = selectedId;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        try {
            paint(ref, cmd, events, store);
        } catch (Throwable t) {
            // build() must not throw: a reading that fails leaves the page as far as it got.
            SafeLog.warn("[reputation] the page could not be built", t);
        }
        // Last, so the toast is the top sibling and draws over the page.
        renderToastInto(cmd);
    }

    private void paint(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ZigMenu.appendThemed(cmd, PAGE_TEMPLATE, MenuFrame.RAIL, "#RightPanel");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));
        // The action bar's buttons are live elements, bound once per build; only Collect is ever shown.
        DetailPainter.bindActionsOnce(events, DETAIL,
                slot -> slot == ActionSlot.PRIMARY ? EventData.of("Action", ReputationView.ACTION_COLLECT) : null,
                null);
        cmd.set("#ReputationTitle.TextSpans", ReputationText.line("page.title"));
        cmd.set("#ReputationLead.TextSpans", ReputationText.line("page.lead"));
        // The shared menu's rail, the Reputation tab selected; painted before the empty-list early return.
        rail = ZigMenu.paint(cmd, events, store, ref, store.getComponent(ref, Player.getComponentType()),
                MenuSlot.REPUTATION.id(), false);

        Function<ReputationDef, ReputationLadder> ladders = ladders();
        List<ReputationView.Row> rows = rows(store, ref);
        ReputationView.Row row = ReputationView.pick(rows, selected);
        if (row == null) {
            cmd.set(DETAIL + ".Visible", false);
            cmd.set(EMPTY + ".Visible", true);
            EmptyStatePainter.paint(cmd, events, EMPTY, ReputationView.empty(), null);
            return;
        }
        selected = row.id();
        try {
            LedgerPainter.paint(cmd, events, LIST, ReputationView.ledger(rows, ladders), Set.of(), selected, ROWS,
                    RowSize.STANDARD, playerRef);
            DetailPainter.paint(cmd, events, DETAIL,
                    ReputationView.detail(row, ladders.apply(row.reputation()), gated(),
                            owed(store, ref, row.reputation()), wornGain(store, ref, row.reputation())),
                    DETAIL_BINDINGS, playerRef);
        } catch (Throwable t) {
            // build() must not throw: a reading that fails leaves the page as far as it got.
            SafeLog.warn("[reputation] the page could not paint " + row.id(), t);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull ReputationEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            answer();
            return;
        }
        if (rail.handle(data.menu, store, ref, player, this::answer)) {
            return;
        }
        if ("select".equals(data.action) && data.reputation != null && !data.reputation.isBlank()) {
            selected = data.reputation;
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        if (ReputationView.ACTION_COLLECT.equals(data.action)) {
            collect(store, ref);
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        player.getPageManager().setPage(ref, store, Page.None);
    }

    /**
     * One Collect press on the reputation being read, told as a toast: what it paid, that the bag needs room, or
     * that it could not pay. A press with nothing waiting (a stale page) says nothing; the reopen shows the truth.
     */
    private void collect(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            ReputationDef reputation = ReputationRuntime.service().known(selected);
            if (reputation == null) {
                return;
            }
            ReputationBeyond.Collected collected = ReputationBeyond.collect(store, ref, reputation);
            switch (collected.status()) {
                case PAID -> showToast(ToastSpec.of(ToastKind.REWARD,
                                ReputationText.line("beyond.toast.collected", collected.paid()))
                        .withLines(RewardToastLines.lines(collected.receipt(), null, null))
                        .withSound(ToastSounds.RECEIPT));
                case NO_ROOM -> showToast(ToastKind.ERROR, ReputationText.line("beyond.toast.no_room"));
                case FAILED -> showToast(ToastKind.WARNING, ReputationText.line("beyond.toast.failed"));
                case NOTHING -> {
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the Collect press on '" + selected + "' failed", t);
        }
    }

    /** How many of {@code reputation}'s Beyond payouts wait for this player; a failed read shows none. */
    private static long owed(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ReputationDef reputation) {
        try {
            return ReputationBeyond.owed(store, ref, reputation);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the page could not read the rewards waiting for " + reputation.id(), t);
            return 0L;
        }
    }

    /**
     * How much this player's worn gear moves each gain with {@code reputation}, read on every build, so a
     * change of gear shows on the next pick; a failed read costs the line, never the page.
     */
    private static long wornGain(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ReputationDef reputation) {
        try {
            return ReputationRuntime.service().wornGain(store, ref, reputation);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the page could not read the worn gain for " + reputation.id(), t);
            return 0L;
        }
    }

    /** An empty update, for an event nothing opened or closed for: the client always hears back. */
    private void answer() {
        sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }

    /**
     * Each reputation's own ladder (the shared ranks plus its own tiers above the top), the one {@link #rows}
     * reads, so the list, the ranks, the colour and Beyond all read the same top.
     */
    @Nonnull
    private static Function<ReputationDef, ReputationLadder> ladders() {
        try {
            return ReputationRuntime.service()::ladderFor;
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the page could not read the rank ladders", t);
            return def -> ReputationLadder.EMPTY;
        }
    }

    @Nonnull
    private static List<ReputationView.Row> rows(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            ReputationService service = ReputationRuntime.service();
            return ReputationView.rows(service.met(store, ref), service::ladderFor);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the page could not read the player's standing", t);
            return List.of();
        }
    }

    /** What every requirement on this server locks; a failure costs the "opens" lines, never the page. */
    @Nonnull
    private static List<GatedContent.Entry> gated() {
        try {
            return GatedContent.all();
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the page could not list what the ranks open", t);
            return List.of();
        }
    }
}
