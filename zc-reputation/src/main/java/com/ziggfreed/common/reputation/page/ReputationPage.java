package com.ziggfreed.common.reputation.page;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.reputation.ReputationDef;
import com.ziggfreed.common.reputation.ReputationRuntime;
import com.ziggfreed.common.reputation.ReputationService;
import com.ziggfreed.common.reputation.ReputationText;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.ui.menu.MenuFrame;
import com.ziggfreed.common.ui.menu.MenuRail;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.util.SafeLog;

/**
 * Reputation: every reputation the player has met on the left, the one being read on the right. It paints
 * {@link ReputationView} and decides nothing. Picking a row reopens the page, so every build is a full one.
 * It sits in the shared menu frame with the Reputation tab selected on the rail; what its document must
 * declare is {@link ReputationPageLayout}'s. Every
 * {@code handleDataEvent} exit opens a page, closes this one, or sends an empty answer (a rail click nothing
 * opened for, an event with no player behind it), so the client is never left waiting.
 */
public final class ReputationPage extends InteractiveCustomUIPage<ReputationEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigReputationPage.ui";
    static final String ROW_TEMPLATE = "Pages/ZigSelectRow.ui";
    static final String LINE_TEMPLATE = "Pages/ZigDetailLine.ui";
    static final String LIST = "#ReputationList";
    static final String DETAIL_LIST = "#DetailList";
    static final String DETAIL_ICON = "#DetailIconSlot";
    static final String ROW_ICON = "#RowIconSlot";

    private static final String ROW_SELECTED_TINT = "#1a2d44";
    private static final String ROW_SELECTED_TEXT = "#ffffff";

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
        ZigMenu.appendThemed(cmd, PAGE_TEMPLATE, MenuFrame.RAIL, "#LeftPanel");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));
        cmd.set("#ReputationTitle.TextSpans", ReputationText.line("page.title"));
        // The shared menu's rail, the Reputation tab selected; painted before the empty-list early return.
        rail = ZigMenu.paint(cmd, events, store, ref, store.getComponent(ref, Player.getComponentType()),
                MenuSlot.REPUTATION.id(), false);

        List<ReputationView.Row> rows = rows(store, ref);
        ReputationView.Row row = ReputationView.pick(rows, selected);
        if (row == null) {
            cmd.set("#EmptyListLabel.TextSpans", ReputationText.line("page.empty"));
            cmd.set("#EmptyListLabel.Visible", true);
            cmd.set("#RightPanel.Visible", false);
            return;
        }
        selected = row.id();
        for (int i = 0; i < rows.size(); i++) {
            paintRow(cmd, events, i, rows.get(i));
        }
        paintDetail(cmd, row);
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
        player.getPageManager().setPage(ref, store, Page.None);
    }

    /** An empty update, for an event nothing opened or closed for: the client always hears back. */
    private void answer() {
        sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
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

    private void paintRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, int index,
            @Nonnull ReputationView.Row row) {
        cmd.append(LIST, ROW_TEMPLATE);
        String sel = LIST + "[" + index + "]";
        ZigRichButton.text(cmd, sel + " #RowBtn", ReputationView.rowLine(row));
        String icon = sel + " " + ROW_ICON;
        cmd.set(icon + ".Visible", IconRenderer.applyIcon(cmd, icon, row.reputation().icon(), null));
        cmd.set(sel + " #RowBadge.TextSpans", ReputationText.line("page.badge", row.standing().effective()));
        cmd.set(sel + " #RowBadge.Visible", true);
        if (row.id().equals(selected)) {
            UiRetint.retintButtonStates(cmd, sel + " #RowBtn", ROW_SELECTED_TINT, ROW_SELECTED_TINT, ROW_SELECTED_TINT);
            ZigRichButton.color(cmd, sel + " #RowBtn", ROW_SELECTED_TEXT);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #RowBtn",
                EventData.of("Action", "select").append("Reputation", row.id()), false);
    }

    private static void paintDetail(@Nonnull UICommandBuilder cmd, @Nonnull ReputationView.Row row) {
        ReputationDef def = row.reputation();
        cmd.set("#DetailTitle.TextSpans", ReputationText.name(def));
        cmd.set(DETAIL_ICON + ".Visible", IconRenderer.applyIcon(cmd, DETAIL_ICON, def.icon(), null));
        cmd.set("#DetailRank.TextSpans", ReputationText.rankName(def, row.standing().rank()));
        List<Message> lines = ReputationView.detailLines(row);
        for (int i = 0; i < lines.size(); i++) {
            cmd.append(DETAIL_LIST, LINE_TEMPLATE);
            cmd.set(DETAIL_LIST + "[" + i + "] #LineText.TextSpans", lines.get(i));
        }
    }
}
