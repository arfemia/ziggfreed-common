package com.ziggfreed.common.objectives.title.page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.objectives.title.TitleConfig;
import com.ziggfreed.common.objectives.title.TitleText;
import com.ziggfreed.common.objectives.title.TitleUnlocks;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The title picker: every title the player has earned that is on offer, each with a switch, at most
 * one on, and a preview line saying how they appear. A press shows that title, or takes the shown
 * one off, through {@code TitleUnlocks}, and repaints only the switches it moved and the preview, so
 * the scroll position survives. What it lists is {@link TitlePickerRows}, appended from the shared
 * settings-row templates into {@code #Rows}. Every exit path sends an update.
 */
public final class TitlePickerPage extends ToastablePage<TitlePickerEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigTitlePickerPage.ui";

    private static final String ROWS = "#Rows";
    private static final String ROW_TOGGLE = "Pages/ZigFormToggleRow.ui";
    private static final String ROW_NOTE = "Pages/ZigFormNoteRow.ui";

    /** Title id to its row's selector, so a press repaints only what it moved. */
    private final Map<String, String> rowOf = new LinkedHashMap<>();

    private int rows;

    TitlePickerPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, TitlePickerEventData.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        appendTemplate(cmd);
        rowOf.clear();
        rows = 0;
        Ref<EntityStore> own = playerEntityRef(ref);
        Store<EntityStore> ownStore = own.getStore();

        cmd.set("#Title.TextSpans", TitleText.picker("title"));
        cmd.set("#Subtitle.TextSpans", TitleText.picker("subtitle"));
        cmd.set("#Preview.TextSpans", preview());
        render(cmd, events, TitlePickerRows.plan(TitleUnlocks.unlocked(ownStore, own),
                TitleUnlocks.active(ownStore, own), TitleConfig.getInstance()));

        ZigRichButton.text(cmd, "#BackButton", TitleText.picker("back"));
        SettingsUiUtil.bindNavigation(events);
        renderToastInto(cmd);
    }

    private void appendTemplate(@Nonnull UICommandBuilder cmd) {
        try {
            TitlePickerPages.resolvedDeps().theme().appendThemed(cmd, PAGE_TEMPLATE);
            return;
        } catch (Throwable t) {
            SafeLog.warn("[title] a page theme failed, so the picker renders plain: " + t.getMessage());
        }
        cmd.append(PAGE_TEMPLATE);
    }

    /** "You appear as: ..." through the very seam every menu names the player by. */
    @Nonnull
    private Message preview() {
        return TitleText.picker("preview",
                PlayerDisplayNames.displayName(playerRef.getUuid(), playerRef.getUsername()));
    }

    private void render(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull List<TitlePickerRows.Row> plan) {
        for (TitlePickerRows.Row row : plan) {
            String titleId = row.titleId();
            if (titleId == null) {
                cmd.set(nextRow(cmd, ROW_NOTE) + " #Note.TextSpans", TitleText.picker(row.noteKey()));
                continue;
            }
            String sel = nextRow(cmd, ROW_TOGGLE);
            rowOf.put(titleId, sel);
            cmd.set(sel + " #Title.TextSpans", TitleText.nameOf(titleId));
            Message flavor = TitleText.flavorOf(titleId);
            if (flavor != null) {
                cmd.set(sel + " #Hint.TextSpans", flavor);
                cmd.set(sel + " #Hint.Visible", true);
            }
            paintToggle(cmd, sel, row.on());
            SettingsUiUtil.bindButton(events, sel + " #Toggle", "press", "Title", titleId);
        }
    }

    @Nonnull
    private String nextRow(@Nonnull UICommandBuilder cmd, @Nonnull String template) {
        cmd.append(ROWS, template);
        return ROWS + "[" + rows++ + "]";
    }

    private static void paintToggle(@Nonnull UICommandBuilder cmd, @Nonnull String sel, boolean on) {
        SettingsUiUtil.setToggle(cmd, sel + " #Toggle", on, TitleText.picker("on"), TitleText.picker("off"));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull TitlePickerEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }
        String action = data.action == null ? "" : data.action;
        switch (action) {
            case "press" -> handlePress(store, ref, data.title);
            case "back" -> {
                if (!TitlePickerPages.resolvedDeps().backGuarded(store, ref, player)) {
                    player.getPageManager().setPage(ref, store, Page.None);
                }
            }
            case "close", "" -> player.getPageManager().setPage(ref, store, Page.None);
            default -> this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
        }
    }

    /** Show the pressed title, or take it off when it is the one shown; repaint what moved. */
    private void handlePress(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable String titleId) {
        UICommandBuilder cmd = new UICommandBuilder();
        String id = titleId == null ? null : titleId.trim().toLowerCase(Locale.ROOT);
        String sel = id == null ? null : rowOf.get(id);
        if (sel == null) {
            this.sendUpdate(cmd, new UIEventBuilder(), false);
            return;
        }
        String before = TitleUnlocks.active(store, ref);
        boolean takingOff = id.equals(before);
        TitleUnlocks.Outcome outcome = takingOff
                ? TitleUnlocks.deactivate(store, ref, playerRef)
                : TitleUnlocks.activate(store, ref, playerRef, id);
        if (outcome.changed()) {
            String beforeSel = before == null ? null : rowOf.get(before);
            if (beforeSel != null) {
                paintToggle(cmd, beforeSel, false);
            }
            paintToggle(cmd, sel, !takingOff);
            cmd.set("#Preview.TextSpans", preview());
            showToast(ToastKind.SUCCESS, takingOff ? TitleText.picker("cleared")
                    : TitleText.picker("shown", TitleText.nameOf(id)));
        } else if (outcome == TitleUnlocks.Outcome.NO_RECORD) {
            showToast(ToastKind.ERROR, TitleText.picker("not_kept"));
        }
        this.sendUpdate(cmd, new UIEventBuilder(), false);
    }
}
