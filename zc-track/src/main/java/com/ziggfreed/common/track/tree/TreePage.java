package com.ziggfreed.common.track.tree;

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
import com.ziggfreed.common.track.pick.PickService;
import com.ziggfreed.common.track.pick.TrackTree;
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
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * A track's tree (R736), the interim page the Skills menu replaces: the track's header (its name, the player's level,
 * what every point spent gives, the points spent and left), a tab per branch (the picks in no branch first, the saved
 * builds and the respec last), the shown tab's list on the left (the kit's list: a section per row, a locked row
 * saying how many more points it needs in its branch) and the row picked read on the right (the kit's reading page:
 * what a pick gives and needs, its choice's other faces, Take and Undo; a saved build's price, Use and Save; the
 * respec's price and Respec).
 *
 * <p>It paints {@link TreePagePlan} and decides nothing: every press acts through {@link PickService}, which asks every
 * rule again, and every build is a full one, so a click is answered from the plan the last build painted. A press
 * that charges or replaces a saved build arms on its first press and acts on the second ({@link ConfirmArm}); any
 * other click forgets the arm. Every event is answered once ({@link TreeActions#event}). Undo last is the server's own
 * undo, the player's newest pick on any track (one made by a command or a re-spend included); the page closing, by
 * its close button, the client's dismiss, or another page opened over it, never by its own repaint
 * ({@link TreeActions.Window}), ends every one of them through {@link PickService#closeUndo}.
 */
final class TreePage extends ToastablePage<TreeEventData> {

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

    /** The track whose tree this is, lower-cased. */
    @Nonnull private final String trackId;

    /** Two presses before a press that charges acts. Page-instance state, never persisted. */
    private final ConfirmArm arm = new ConfirmArm();

    /** When the picks made here stop being undoable: whenever the page goes, but for its own repaint. */
    @Nonnull private final TreeActions.Window window;

    /** The tab and the row the player picked; null reads the first. */
    @Nullable private String tab;
    @Nullable private String row;

    /** The plan the last build painted: every click is answered from it. */
    @Nullable private TreePagePlan.Plan painted;

    TreePage(@Nonnull PlayerRef playerRef, @Nonnull String trackId) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, TreeEventData.CODEC);
        this.trackId = trackId;
        this.window = new TreeActions.Window(() -> PickService.closeUndo(playerRef.getUuid()));
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        try {
            paint(cmd, events);
        } catch (Throwable t) {
            // A build never throws: the page keeps what was painted before the fault.
            SafeLog.warn("[tree] the tree page's build failed", t);
        }
        renderToastInto(cmd);
    }

    private void paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.append(TreePagePlan.DOCUMENT);
        events.addEventBinding(CustomUIEventBindingType.Activating, TreePagePlan.CLOSE,
                EventData.of("Action", TreeActions.CLOSE));
        // Bound once per build with no id: a press acts on whatever row the page shows.
        DetailPainter.bindActionsOnce(events, TreePagePlan.PAGE, TreePage::actionFor, null);

        UUID player = playerRef.getUuid();
        TrackTree.View view = player == null ? null : TrackTree.read(player, trackId);
        if (view == null) {
            painted = null;
            showEmpty(cmd, events);
            return;
        }
        TreePagePlan.Plan plan = TreePagePlan.of(player, view, tab, row);
        painted = plan;
        tab = plan.tab();
        row = plan.selected() == null ? null : plan.selected().id();
        header(cmd, plan.header());
        for (TreePagePlan.Tab each : plan.tabs()) {
            ViewTabPainter.append(cmd, events, TreePagePlan.TABS, each.label(), each.picture(),
                    each.id().equals(plan.tab()), EventData.of("Action", TreeActions.TAB).append("Tab", each.id()));
        }
        LedgerPainter.paint(cmd, events, TreePagePlan.LIST, plan.list(), Set.of(), row, ROWS, RowSize.STANDARD,
                playerRef);
        if (plan.selected() == null) {
            showEmpty(cmd, events);
            return;
        }
        cmd.set(TreePagePlan.PAGE_EMPTY + ".Visible", false);
        cmd.set(TreePagePlan.PAGE + ".Visible", true);
        DetailPainter.paint(cmd, events, TreePagePlan.PAGE, armed(plan.selected()), NO_BINDINGS, playerRef);
    }

    /** The header: the picture, the name, the level, what every point gives, and the two figures. */
    private static void header(@Nonnull UICommandBuilder cmd, @Nonnull TreePagePlan.Header header) {
        cmd.set(TreePagePlan.PIC + ".Visible", IconRenderer.applyPlainIcon(cmd, TreePagePlan.PIC,
                header.picture().itemId(), header.picture().texturePath()));
        cmd.set(TreePagePlan.NAME + ".TextSpans", header.name());
        cmd.set(TreePagePlan.LEVEL + ".TextSpans", header.level());
        if (header.each() != null) {
            cmd.set(TreePagePlan.EACH + ".TextSpans", header.each());
        }
        cmd.set(TreePagePlan.EACH + ".Visible", header.each() != null);
        StatPainter.paint(cmd, TreePagePlan.SPENT, header.spent());
        StatPainter.paint(cmd, TreePagePlan.LEFT, header.left());
    }

    /** The row's reading page, each armed button reading its confirm. */
    @Nonnull
    private DetailView armed(@Nonnull TreePagePlan.Entry entry) {
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
    private static String armKey(@Nonnull TreePagePlan.Entry entry, @Nonnull ActionSlot slot) {
        return entry.id() + "/" + slot.name();
    }

    /** The empty state in the reading page's place: nothing to show here right now. */
    private static void showEmpty(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.set(TreePagePlan.PAGE + ".Visible", false);
        cmd.set(TreePagePlan.PAGE_EMPTY + ".Visible", true);
        EmptyStatePainter.paint(cmd, events, TreePagePlan.PAGE_EMPTY,
                new EmptyState(Picture.NONE, TreeText.line("page.empty"), null, null), null);
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

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            window.dismissed();
        } catch (Throwable t) {
            SafeLog.fine("[tree] could not close the tree page's undo window: " + t.getMessage());
        }
        super.onDismiss(ref, store);
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
            TreePagePlan.Plan plan = painted;
            if (plan != null && plan.entries().containsKey(next)) {
                arm.reset();
                row = next;
            }
        }

        @Override
        public void press(@Nonnull Player player, @Nonnull ActionSlot slot) {
            TreePagePlan.Plan plan = painted;
            TreePagePlan.Entry entry = plan == null ? null : plan.selected();
            DetailAction action = entry == null ? null : entry.page().action(slot);
            TreePagePlan.Press press = entry == null ? null : entry.press(slot);
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
                SafeLog.warn("[tree] a press on the tree page could not be carried out", t);
                toast = ToastSpec.of(ToastKind.ERROR, TreeText.line("action.failed"));
            }
            if (toast != null) {
                showToast(toast);
            }
        }

        @Override
        public void reopen(@Nonnull Player player) {
            window.reopen(() -> player.getPageManager().openCustomPage(ref, store, TreePage.this));
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
