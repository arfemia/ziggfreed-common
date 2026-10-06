package com.ziggfreed.common.ui.hud.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.hud.command.HudMessages;
import com.ziggfreed.common.ui.hud.panel.HudPanelConfig;
import com.ziggfreed.common.ui.hud.panel.HudPanelOwnerWriter;
import com.ziggfreed.common.ui.hud.panel.HudPanels;
import com.ziggfreed.common.ui.hud.panel.HudSpotAsset;
import com.ziggfreed.common.ui.hud.panel.HudSpotConfig;
import com.ziggfreed.common.ui.hud.settings.HudSettingsRows.Row;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The server's HUD layout: for each shared bar panel, whether it is on for everyone, the spot it sits at,
 * and every inline leaf an owner may restate over that spot ({@link HudServerLeaf}), for the audience the
 * consumer registered. A player's own HUD choices are on the Settings tab ({@code settings/page}).
 *
 * <p>The on/off switch is kept at once; the rest is a draft written by Save, in exactly the shape an owner
 * would type into {@code mods/ziggfreedcommon/hud-panels.json}, through {@link HudPanelOwnerWriter}. A
 * blank field REMOVES that leaf, so the spot's own value applies again; a field that will not read is
 * named in a toast and NOTHING is written, for any panel, until it is fixed.
 *
 * <p>What it lists is {@link HudSettingsRows#server}, a plan worked out with no builder in hand, appended
 * row by row from the shared settings-row templates into {@code #Rows} by index. Every control speaks the
 * one event shape ({@link HudSettingsEventData}). Every handler re-checks the audience, since a page
 * outlives a permission change.
 */
public final class HudSettingsPage extends ToastablePage<HudSettingsEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigHudSettingsPage.ui";

    /** The key a row's live value rides under; the {@code @} is the client's resolve-as-path directive. */
    static final String VALUE_KEY = "@Value";

    private static final String ROWS = "#Rows";
    private static final String ROW_HEADER = "Pages/ZigFormHeaderRow.ui";
    private static final String ROW_TOGGLE = "Pages/ZigFormToggleRow.ui";
    private static final String ROW_DROPDOWN = "Pages/ZigFormDropdownRow.ui";
    private static final String ROW_FIELD = "Pages/ZigFormFieldRow.ui";
    private static final String ROW_NOTE = "Pages/ZigFormNoteRow.ui";

    /** Row id to its selector, so a partial update can address the control that changed. */
    private final Map<String, String> rowOf = new LinkedHashMap<>();

    /** The draft: row id to the live value the admin typed or chose, until Save. */
    private final Map<String, String> draft = new LinkedHashMap<>();

    private int rows;

    HudSettingsPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, HudSettingsEventData.CODEC);
    }

    /** A line of this page, from the family's own lang file. */
    @Nonnull
    private static Message msg(@Nonnull String key, @Nonnull Object... args) {
        return HudMessages.line("settings." + key, args);
    }

    // ==================== build ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        appendTemplate(cmd);
        rowOf.clear();
        draft.clear();
        rows = 0;

        cmd.set("#Title.TextSpans", msg("title"));
        cmd.set("#Subtitle.TextSpans", msg("subtitle"));
        render(cmd, events, HudSettingsRows.server(panelIds(), HudPanelConfig.getInstance()));

        ZigRichButton.text(cmd, "#BackButton", msg("back"));
        ZigRichButton.text(cmd, "#SaveButton", msg("save"));
        SettingsUiUtil.bindButton(events, "#SaveButton", "save");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton", EventData.of("Action", "back"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));
        renderToastInto(cmd);
    }

    private void appendTemplate(@Nonnull UICommandBuilder cmd) {
        try {
            HudSettingsPages.resolvedDeps().theme().appendThemed(cmd, PAGE_TEMPLATE);
            return;
        } catch (Throwable t) {
            SafeLog.warn("[hud-settings] a page theme failed, so the page renders plain: " + t.getMessage());
        }
        cmd.append(PAGE_TEMPLATE);
    }

    /** The panels the page lists, by each panel's authored {@code Order}. */
    @Nonnull
    private static List<String> panelIds() {
        return HudPanels.listing();
    }

    /** Append the plan row by row, each kind through its own template, worded from the family's lang file. */
    private void render(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull List<Row> plan) {
        for (Row row : plan) {
            Message hint = row.hintKey() == null ? null : msg(row.hintKey());
            switch (row.kind()) {
                case HEADER -> appendHeader(cmd, panelLabel(row.panelId()));
                case TOGGLE -> appendToggle(cmd, events, row.id(), msg(row.labelKey()), hint, row.on());
                case DROPDOWN -> appendDropdown(cmd, events, row.id(), msg(row.labelKey()), hint,
                        spotEntries(row.panelId(), row.noneKey()), row.value());
                case FIELD -> appendField(cmd, events, row.id(), msg(row.labelKey()), hint, row.value());
                case NOTE -> appendNote(cmd, msg(row.labelKey()));
            }
        }
    }

    /**
     * The spots offered for {@code panelId}, behind a first entry meaning "as the shipped file says",
     * worded by {@code noneKey}. A dropdown entry is resolved by the client from its message id.
     */
    @Nonnull
    private static List<DropdownEntryInfo> spotEntries(@Nonnull String panelId, @Nonnull String noneKey) {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        entries.add(new DropdownEntryInfo(
                LocalizableString.fromMessageId(HudMessages.key("settings." + noneKey)), HudSettingsRows.NONE));
        for (HudSpotAsset spot : HudSpotConfig.getInstance().offeredFor(panelId)) {
            String key = spot.labelKey();
            LocalizableString label = key != null
                    ? LocalizableString.fromMessageId(ContentKeys.resolved(key))
                    : LocalizableString.fromString(spot.getId());
            entries.add(new DropdownEntryInfo(label, spot.getId().toLowerCase(Locale.ROOT)));
        }
        return entries;
    }

    // ==================== rows ====================

    @Nonnull
    private String nextRow(@Nonnull UICommandBuilder cmd, @Nonnull String template, @Nullable String id) {
        cmd.append(ROWS, template);
        String sel = ROWS + "[" + rows++ + "]";
        if (id != null) {
            rowOf.put(id, sel);
        }
        return sel;
    }

    private void appendHeader(@Nonnull UICommandBuilder cmd, @Nonnull Message title) {
        cmd.set(nextRow(cmd, ROW_HEADER, null) + " #Title.TextSpans", title);
    }

    private void appendNote(@Nonnull UICommandBuilder cmd, @Nonnull Message note) {
        cmd.set(nextRow(cmd, ROW_NOTE, null) + " #Note.TextSpans", note);
    }

    private void appendToggle(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String id,
            @Nonnull Message title, @Nullable Message hint, boolean on) {
        String sel = nextRow(cmd, ROW_TOGGLE, id);
        cmd.set(sel + " #Title.TextSpans", title);
        paintToggle(cmd, sel, on);
        SettingsUiUtil.bindButton(events, sel + " #Toggle", "press", "Field", id);
        hint(cmd, sel, hint);
    }

    private void appendDropdown(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String id,
            @Nonnull Message title, @Nullable Message hint, @Nonnull List<DropdownEntryInfo> entries,
            @Nonnull String value) {
        String sel = nextRow(cmd, ROW_DROPDOWN, id);
        cmd.set(sel + " #Title.TextSpans", title);
        String control = sel + " #Dropdown";
        cmd.set(control + ".Entries", entries);
        cmd.set(control + ".Value", value);
        bindValue(events, control, id);
        draft.put(id, value);
        hint(cmd, sel, hint);
    }

    private void appendField(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String id,
            @Nonnull Message title, @Nullable Message hint, @Nonnull String text) {
        String sel = nextRow(cmd, ROW_FIELD, id);
        cmd.set(sel + " #Title.TextSpans", title);
        String control = sel + " #Field";
        cmd.set(control + ".Value", text);
        bindValue(events, control, id);
        draft.put(id, text);
        hint(cmd, sel, hint);
    }

    private static void hint(@Nonnull UICommandBuilder cmd, @Nonnull String sel, @Nullable Message hint) {
        if (hint != null) {
            cmd.set(sel + " #Hint.TextSpans", hint);
            cmd.set(sel + " #Hint.Visible", true);
        }
    }

    private static void paintToggle(@Nonnull UICommandBuilder cmd, @Nonnull String sel, boolean on) {
        SettingsUiUtil.setToggle(cmd, sel + " #Toggle", on, msg("on"), msg("off"));
    }

    private static void bindValue(@Nonnull UIEventBuilder events, @Nonnull String control, @Nonnull String id) {
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, control,
                EventData.of("Action", "field").append("Field", id).append(VALUE_KEY, control + ".Value"), false);
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull HudSettingsEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }
        String action = data.action == null ? "" : data.action;
        switch (action) {
            case "field" -> handleField(data.field, data.value);
            case "press" -> handlePress(store, ref, player, data.field);
            case "save" -> handleSave(store, ref, player);
            case "back" -> {
                if (!HudSettingsPages.resolvedDeps().backGuarded(store, ref, player)) {
                    player.getPageManager().setPage(ref, store, Page.None);
                }
            }
            case "close", "" -> player.getPageManager().setPage(ref, store, Page.None);
            default -> this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
        }
    }

    /** A dropdown or field changed: it waits in the draft for Save. */
    private void handleField(@Nullable String field, @Nullable String value) {
        if (field != null) {
            draft.put(field, value == null ? "" : value.trim());
        }
        this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }

    /** The on-for-everyone switch was clicked: flip it, keep it, and repaint that one control. */
    private void handlePress(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nullable String field) {
        UICommandBuilder cmd = new UICommandBuilder();
        String sel = field == null ? null : rowOf.get(field);
        if (sel != null && field.startsWith(HudSettingsRows.ENABLED)
                && HudSettingsPages.mayAdminister(store, ref, player)) {
            String panelId = field.substring(HudSettingsRows.ENABLED.length());
            boolean on = !HudPanelConfig.getInstance().panel(panelId).enabled();
            if (HudPanelOwnerWriter.setEnabled(panelId, on)) {
                paintToggle(cmd, sel, on);
                showToast(ToastKind.SUCCESS, msg(on ? "panel_on" : "panel_off", panelLabel(panelId)));
            } else {
                showToast(ToastKind.ERROR, msg("save_failed"));
            }
        }
        this.sendUpdate(cmd, new UIEventBuilder(), false);
    }

    /**
     * Save reads every panel's draft FIRST, so a field that will not read is named and nothing is written
     * for any panel; then writes each panel's leaves as an owner would type them and reopens the page so
     * the fold shows through.
     */
    private void handleSave(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        if (!HudSettingsPages.mayAdminister(store, ref, player)) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }
        Map<String, Map<String, Object>> perPanel = new LinkedHashMap<>();
        for (String id : panelIds()) {
            HudServerLeaf.Draft drafted = HudServerLeaf.draft(id, draft);
            HudServerLeaf refused = drafted.refused();
            if (refused != null) {
                showToast(ToastKind.ERROR, msg(refused.kind().refusalKey(), msg(refused.labelKey())));
                this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
                return;
            }
            Map<String, Object> leaves = new LinkedHashMap<>();
            String spot = draft.getOrDefault(HudSettingsRows.PLACEMENT + id, HudSettingsRows.NONE);
            leaves.put("Placement", spot.isEmpty() ? null : spot);
            leaves.putAll(drafted.leaves());
            perPanel.put(id, leaves);
        }
        for (Map.Entry<String, Map<String, Object>> panel : perPanel.entrySet()) {
            if (!HudPanelOwnerWriter.setLeaves(panel.getKey(), panel.getValue())) {
                showToast(ToastKind.ERROR, msg("save_failed"));
                this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
                return;
            }
        }
        showToast(ToastKind.SUCCESS, msg("saved"));
        if (!HudSettingsPages.open(store, ref, player)) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
        }
    }

    @Nonnull
    private static Message panelLabel(@Nonnull String panelId) {
        return HudPanelConfig.getInstance().panel(panelId).label();
    }
}
