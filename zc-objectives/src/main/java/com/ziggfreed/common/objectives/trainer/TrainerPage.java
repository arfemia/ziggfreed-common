package com.ziggfreed.common.objectives.trainer;

import java.util.ArrayList;
import java.util.List;
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
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.npc.NpcNames;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
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
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.page.PagePainter;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * One trainer's page (R617, R623): its name and lead on the left over its parts, each a part of the page kit
 * ({@link TrainerParts}: Learn, Quests, Services, or a part a mod registers) painted through the kit's
 * {@link PagePainter} into {@code #Sections}, each a kit list section; the entry selected reads on the right on the
 * kit's reading page, its main button the entry's {@link TrainerAction}. An entry that charges arms on its first
 * press and acts on the second ({@link ConfirmArm}), and any other click forgets the arm.
 *
 * <p>It paints {@link TrainerPagePlan} and decides nothing: what each part lists is its source's, and every press
 * acts through the service that owns it, which asks every rule again. Every pick (a row) reopens the page, so every
 * build is a full one and a click is answered from the plan the last build painted. Every {@code handleDataEvent}
 * exit reopens the page, opens another, closes this one, or answers with an empty update.
 */
public final class TrainerPage extends ToastablePage<TrainerEventData> {

    private static final String CLOSE = "close";
    private static final String PRIMARY = "primary";

    /** The reading page shows no selectable line and no toggle. */
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

    /** The trainer's placement id, lower-cased. */
    @Nonnull private final String placementId;

    /** Two presses before a charging entry acts. Page-instance state, never persisted. */
    private final ConfirmArm arm = new ConfirmArm();

    /** The part and row the player picked; null reads the first entry. */
    @Nullable private String selectedPart;
    @Nullable private String selectedRow;

    /** The plan the last build painted: every click is answered from it. */
    @Nullable private TrainerPagePlan.Plan painted;

    /** The character's entity the page was first opened at (a press F), kept for the reach check; null else. */
    @Nullable private Ref<EntityStore> npcAnchor;

    /** Whether the first build has looked at what the page was opened on. */
    private boolean anchorRead;

    TrainerPage(@Nonnull PlayerRef playerRef, @Nonnull String placementId) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, TrainerEventData.CODEC);
        this.placementId = placementId;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        try {
            paint(ref, cmd, events, store);
        } catch (Throwable t) {
            // A build never throws: the page keeps what was painted before the fault.
            SafeLog.warn("[trainer] the trainer page's build failed", t);
        }
        renderToastInto(cmd);
    }

    private void paint(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        cmd.append(TrainerPagePlan.DOCUMENT);
        events.addEventBinding(CustomUIEventBindingType.Activating, TrainerPagePlan.CLOSE,
                EventData.of("Action", CLOSE));
        // Bound once per build with no id: a press acts on whatever entry the page shows.
        DetailPainter.bindActionsOnce(events, TrainerPagePlan.PAGE,
                slot -> slot == ActionSlot.PRIMARY ? EventData.of("Action", PRIMARY) : null, null);
        cmd.set(TrainerPagePlan.NAME + ".TextSpans", name());
        cmd.set(TrainerPagePlan.LEAD + ".TextSpans", TrainerText.line("page.lead"));

        // The build argument is the page's anchor, the character's own entity at a press-F; the first build keeps it
        // for the reach check, since a reopen anchors the page on the player.
        Ref<EntityStore> player = playerEntityRef(ref);
        if (!anchorRead) {
            anchorRead = true;
            npcAnchor = ref.equals(player) ? null : ref;
        }
        TrainerAt at = at(store, player);
        if (at == null) {
            painted = null;
            showEmpty(cmd, events, TrainerText.line("page.unavailable"));
            return;
        }
        TrainerPagePlan.Plan plan = TrainerPagePlan.of(at, selectedPart, selectedRow);
        painted = plan;
        if (plan.sections().isEmpty() || plan.selected() == null) {
            showEmpty(cmd, events, TrainerText.line("page.empty"));
            return;
        }
        PagePainter.paint(cmd, events, TrainerPagePlan.SECTIONS, plan.sections(), playerRef);
        TrainerPagePlan.Selected selected = plan.selected();
        selectedPart = selected.part();
        selectedRow = selected.entry().id();
        cmd.set(TrainerPagePlan.PAGE_EMPTY + ".Visible", false);
        cmd.set(TrainerPagePlan.PAGE + ".Visible", true);
        DetailPainter.paint(cmd, events, TrainerPagePlan.PAGE, view(selected), NO_BINDINGS, playerRef);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull TrainerEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            answer();
            return;
        }
        String action = data.action == null ? "" : data.action;
        switch (action) {
            case TrainerParts.SELECT -> select(data, ref, store, player);
            case PRIMARY -> press(ref, store, player);
            default -> player.getPageManager().setPage(ref, store, Page.None);
        }
    }

    /** A row's click: read that entry on the right. */
    private void select(@Nonnull TrainerEventData data, @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store, @Nonnull Player player) {
        TrainerPagePlan.Plan plan = painted;
        TrainerSection section = plan == null ? null : plan.section(data.section);
        if (section == null || section.entry(data.row) == null) {
            answer();
            return;
        }
        arm.reset();
        selectedPart = section.part().name();
        selectedRow = data.row;
        reopen(ref, store, player);
    }

    /** The main button: the shown entry's action, after a second press when it charges. */
    private void press(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player) {
        TrainerPagePlan.Plan plan = painted;
        TrainerPagePlan.Selected selected = plan == null ? null : plan.selected();
        TrainerEntry entry = selected == null ? null : selected.entry();
        DetailAction primary = entry == null ? null : entry.page().action(ActionSlot.PRIMARY);
        if (entry == null || entry.action() == null || primary == null || !primary.enabled()) {
            answer();
            return;
        }
        TrainerAt at = at(store, ref);
        TrainerResult result;
        if (at == null) {
            result = TrainerResult.toast(ToastKind.WARNING, TrainerText.line("page.unavailable"));
        } else if (!entry.fromAnywhere() && !TrainerReach.near(at)) {
            // The one reach check for every entry that acts (Learn, a respec, any service or part a mod adds),
            // asked before the arm so nothing waits on a confirm the player cannot give from here.
            arm.reset();
            result = TrainerResult.toast(ToastKind.WARNING, TrainerText.line("refused.far"));
        } else if (entry.confirm() != null && !arm.confirm(selected.key(), System.currentTimeMillis())) {
            // Armed by this press: the button now says what a second press does.
            reopen(ref, store, player);
            return;
        } else {
            try {
                result = entry.action().press(at);
            } catch (Throwable t) {
                SafeLog.warn("[trainer] a trainer's offer could not be carried out", t);
                result = TrainerResult.toast(ToastKind.ERROR, TrainerText.line("action.failed"));
            }
        }
        if (result.pageTaken()) {
            return;
        }
        if (result.toast() != null) {
            showToast(result.toast());
        }
        reopen(ref, store, player);
    }

    /** The selected entry's page, its main button reading its confirm while armed. */
    @Nonnull
    private DetailView view(@Nonnull TrainerPagePlan.Selected selected) {
        DetailView view = selected.entry().page();
        Message confirm = selected.entry().confirm();
        if (confirm == null || !arm.isArmed(selected.key(), System.currentTimeMillis())) {
            return view;
        }
        List<DetailAction> actions = new ArrayList<>(view.actions().size());
        for (DetailAction action : view.actions()) {
            actions.add(action.slot() != ActionSlot.PRIMARY ? action : new DetailAction(action.slot(), confirm,
                    action.look(), action.actionId(), action.destination(), action.enabled(), action.tooltip()));
        }
        return new DetailView(view.picture(), view.title(), view.meta(), view.subMeta(), view.badges(), view.toggle(),
                view.progress(), view.progressLabel(), view.lead(), view.blocks(), actions, view.hint());
    }

    /** The trainer and the player as they stand now, or null when the placement is no longer a trainer. */
    @Nullable
    private TrainerAt at(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        NpcPlacementAsset placement = Trainers.trainer(placementId);
        UUID id = playerRef.getUuid();
        Player player = store.getComponent(ref, Player.getComponentType());
        NpcPlacementAsset.Trainer block = placement == null ? null : placement.getTrainer();
        if (block == null || player == null || id == null) {
            return null;
        }
        return new TrainerAt(placementId, placement, Trainers.npcOf(placementId), Trainers.tracksTaught(block), store,
                ref, player, id, npcAnchor);
    }

    /** The trainer's name: its role's nameplate, else its character's, else a plain title. */
    @Nonnull
    private Message name() {
        try {
            String key = NpcNames.nameKeyOfPlacement(placementId);
            if (key != null) {
                return Msg.key(key);
            }
            Message name = NpcNames.nameFor(Trainers.npcOf(placementId));
            if (name != null) {
                return name;
            }
        } catch (Throwable t) {
            SafeLog.fine("[trainer] a trainer's name could not be read: " + t.getMessage());
        }
        return TrainerText.line("page.title.none");
    }

    /** The empty state in the reading page's place. */
    private static void showEmpty(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Message line) {
        cmd.set(TrainerPagePlan.PAGE + ".Visible", false);
        cmd.set(TrainerPagePlan.PAGE_EMPTY + ".Visible", true);
        EmptyStatePainter.paint(cmd, events, TrainerPagePlan.PAGE_EMPTY,
                new EmptyState(Picture.NONE, line, null, null), null);
    }

    private void reopen(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player) {
        try {
            player.getPageManager().openCustomPage(ref, store, this);
        } catch (Throwable t) {
            SafeLog.warn("[trainer] the trainer page could not reopen: " + t.getMessage());
            answer();
        }
    }

    /** An empty update, for an event nothing answered: the client always hears back. */
    private void answer() {
        sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }
}
