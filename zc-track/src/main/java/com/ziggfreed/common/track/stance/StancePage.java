package com.ziggfreed.common.track.stance;

import java.util.ArrayList;
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
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.commerce.page.ConfirmArm;
import com.ziggfreed.common.track.tree.TreeActions;
import com.ziggfreed.common.track.tree.TreeEventData;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBindings;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailPainter;
import com.ziggfreed.common.ui.kit.DetailToggle;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.EmptyState;
import com.ziggfreed.common.ui.kit.EmptyStatePainter;
import com.ziggfreed.common.ui.kit.LedgerBindings;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.kit.StatPainter;
import com.ziggfreed.common.ui.kit.ViewTabPainter;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * A player's stances, the library's own surface for them (M513): the shown stance's header (its name, a line on what it
 * is for, the slots open and the faces held), a tab per stance shown, its list on the left (its slots, then the faces
 * the player owns by kind) and the row picked read on the right (what a face adds while held, what holding it costs
 * and what releasing it costs, Hold and Release; what a slot holds and what opens it).
 *
 * <p>It paints {@link StancePagePlan} and decides nothing: every press acts through {@link Stances}, which asks every
 * rule again, so a click is answered from the plan the last build painted. A press that charges arms on its first
 * press and acts on the second ({@link ConfirmArm}); any other click forgets the arm. Every event is answered once,
 * through the tree page's event door ({@link TreeActions#event}).
 */
final class StancePage extends ToastablePage<TreeEventData> {

    /** No line of the reading page opens anything, and it has no toggle. */
    private static final DetailBindings NO_BINDINGS = new DetailBindings() {
        @Override
        public EventData line(DetailBlock b, DetailLine l) {
            return null;
        }

        @Override
        public EventData toggle(DetailToggle t) {
            return null;
        }
    };

    /** A row's click reads it; no section folds or caps short. */
    private static final LedgerBindings ROWS = new LedgerBindings() {
        @Override
        public EventData row(LedgerSection s, LedgerRow r) {
            return EventData.of("Action", TreeActions.SELECT).append("Row", r.id());
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

    /** The stances shown, by id, in order; empty for every switched-on stance. */
    @Nonnull private final List<String> stanceIds;

    /** The trainer the page was opened at (the placement a conversation or a press F came from), or null. */
    @Nullable private final String placement;

    /** Two presses before a press that charges acts. Page-instance state, never persisted. */
    private final ConfirmArm arm = new ConfirmArm();

    /** The stance tab and the row the player picked; null reads the first. */
    @Nullable private String tab;
    @Nullable private String row;

    /** The plan the last build painted: every click is answered from it. */
    @Nullable private StancePagePlan.Plan painted;

    StancePage(@Nonnull PlayerRef playerRef, @Nonnull List<String> stanceIds, @Nullable String placement) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, TreeEventData.CODEC);
        this.stanceIds = List.copyOf(stanceIds);
        this.placement = placement;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        try {
            paint(cmd, events);
        } catch (Throwable t) {
            // A build never throws: the page keeps what was painted before the fault.
            SafeLog.warn("[stance] the stance page's build failed", t);
        }
        renderToastInto(cmd);
    }

    private void paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.append(StancePagePlan.DOCUMENT);
        events.addEventBinding(CustomUIEventBindingType.Activating, StancePagePlan.CLOSE,
                EventData.of("Action", TreeActions.CLOSE));
        // Bound once per build with no id: a press acts on whatever row the page shows.
        DetailPainter.bindActionsOnce(events, StancePagePlan.PAGE, StancePage::actionFor, null);

        UUID player = playerRef.getUuid();
        StancePagePlan.Plan plan = player == null ? null
                : StancePagePlan.of(player, StancePages.shown(stanceIds), placement, tab, row);
        painted = plan;
        if (plan == null) {
            showEmpty(cmd, events);
            return;
        }
        tab = plan.tab();
        row = plan.selected() == null ? null : plan.selected().id();
        header(cmd, plan.header());
        for (StancePagePlan.Tab each : plan.tabs()) {
            ViewTabPainter.append(cmd, events, StancePagePlan.TABS, each.label(), each.picture(),
                    each.id().equals(plan.tab()), EventData.of("Action", TreeActions.TAB).append("Tab", each.id()));
        }
        LedgerPainter.paint(cmd, events, StancePagePlan.LIST, plan.list(), Set.of(), row, ROWS, RowSize.STANDARD,
                playerRef);
        if (plan.selected() == null) {
            showEmpty(cmd, events);
            return;
        }
        cmd.set(StancePagePlan.PAGE_EMPTY + ".Visible", false);
        cmd.set(StancePagePlan.PAGE + ".Visible", true);
        DetailPainter.paint(cmd, events, StancePagePlan.PAGE, armed(plan.selected()), NO_BINDINGS, playerRef);
    }

    /** The header: the picture, the name, the line, and the two figures. */
    private static void header(@Nonnull UICommandBuilder cmd, @Nonnull StancePagePlan.Header header) {
        cmd.set(StancePagePlan.PIC + ".Visible", IconRenderer.applyPlainIcon(cmd, StancePagePlan.PIC,
                header.picture().itemId(), header.picture().texturePath()));
        cmd.set(StancePagePlan.NAME + ".TextSpans", header.name());
        cmd.set(StancePagePlan.LEAD + ".TextSpans", header.lead());
        StatPainter.paint(cmd, StancePagePlan.SLOTS, header.slots());
        StatPainter.paint(cmd, StancePagePlan.HELD, header.held());
    }

    /** The row's reading page, each armed button reading its confirm. */
    @Nonnull
    private DetailView armed(@Nonnull StancePagePlan.Entry entry) {
        DetailView view = entry.page();
        long now = System.currentTimeMillis();
        List<DetailAction> actions = new ArrayList<>(view.actions().size());
        boolean any = false;
        for (DetailAction action : view.actions()) {
            Message confirm = entry.confirm(action.slot());
            if (confirm != null && arm.isArmed(armKey(entry, action.slot()), now)) {
                actions.add(new DetailAction(action.slot(), confirm, action.look(), action.actionId(),
                        action.destination(), action.enabled(), action.tooltip()));
                any = true;
            } else {
                actions.add(action);
            }
        }
        return !any ? view : new DetailView(view.picture(), view.title(), view.meta(), view.subMeta(), view.badges(),
                view.toggle(), view.progress(), view.progressLabel(), view.lead(), view.blocks(), actions,
                view.hint());
    }

    @Nonnull
    private static String armKey(@Nonnull StancePagePlan.Entry entry, @Nonnull ActionSlot slot) {
        return entry.id() + "/" + slot.name();
    }

    /** The empty state in the reading page's place: nothing to show here right now. */
    private static void showEmpty(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.set(StancePagePlan.PAGE + ".Visible", false);
        cmd.set(StancePagePlan.PAGE_EMPTY + ".Visible", true);
        EmptyStatePainter.paint(cmd, events, StancePagePlan.PAGE_EMPTY,
                new EmptyState(Picture.NONE, StanceText.line("page.empty"), null, null), null);
    }

    /** The press each action-bar button carries. */
    @Nullable
    private static EventData actionFor(@Nonnull ActionSlot slot) {
        return switch (slot) {
            case PRIMARY -> EventData.of("Action", TreeActions.PRIMARY);
            case SECONDARY -> EventData.of("Action", TreeActions.SECONDARY);
            case DANGER -> EventData.of("Action", TreeActions.DANGER);
        };
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull TreeEventData data) {
        TreeActions.event(new Host(ref, store), data.action, data.tab, data.row);
    }

    /** What an event on this page does, through {@link TreeActions}. */
    private final class Host implements TreeActions.Host<Player> {

        @Nonnull private final Ref<EntityStore> ref;
        @Nonnull private final Store<EntityStore> store;

        Host(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
            this.ref = ref;
            this.store = store;
        }

        @Nullable
        @Override
        public Player player() {
            return store.getComponent(ref, Player.getComponentType());
        }

        @Override
        public void tab(@Nonnull Player player, @Nonnull String next) {
            arm.reset();
            tab = next;
            row = null;
        }

        @Override
        public void select(@Nonnull Player player, @Nonnull String next) {
            StancePagePlan.Plan plan = painted;
            if (plan != null && plan.entries().containsKey(next)) {
                arm.reset();
                row = next;
            }
        }

        @Override
        public void press(@Nonnull Player player, @Nonnull ActionSlot slot) {
            StancePagePlan.Plan plan = painted;
            StancePagePlan.Entry entry = plan == null ? null : plan.selected();
            DetailAction action = entry == null ? null : entry.page().action(slot);
            StancePagePlan.Press press = entry == null ? null : entry.press(slot);
            if (action == null || !action.enabled() || press == null) {
                return;
            }
            if (entry.confirm(slot) != null && !arm.confirm(armKey(entry, slot), System.currentTimeMillis())) {
                // Armed by this press: the repaint shows the button saying what a second press does.
                return;
            }
            arm.reset();
            ToastSpec toast;
            try {
                toast = press.press();
            } catch (Throwable t) {
                SafeLog.warn("[stance] a press on the stance page could not be carried out", t);
                toast = ToastSpec.of(ToastKind.ERROR, StanceText.line("action.failed"));
            }
            showToast(toast);
        }

        @Override
        public void reopen(@Nonnull Player player) {
            player.getPageManager().openCustomPage(ref, store, StancePage.this);
        }

        @Override
        public void close(@Nonnull Player player) {
            player.getPageManager().setPage(ref, store, Page.None);
        }

        @Override
        public void answer() {
            sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
        }
    }
}
