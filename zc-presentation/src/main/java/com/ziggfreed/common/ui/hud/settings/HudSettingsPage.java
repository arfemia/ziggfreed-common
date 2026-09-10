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
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.hud.HudPreferenceComponent;
import com.ziggfreed.common.ui.hud.HudPreferences;
import com.ziggfreed.common.ui.hud.bar.HudBarLayout;
import com.ziggfreed.common.ui.hud.bar.HudBarPanelConfig;
import com.ziggfreed.common.ui.hud.bar.HudBarPanelOwnerWriter;
import com.ziggfreed.common.ui.hud.bar.HudBarPlacementAsset;
import com.ziggfreed.common.ui.hud.bar.HudBarPlacementConfig;
import com.ziggfreed.common.ui.hud.bar.HudBars;
import com.ziggfreed.common.ui.hud.command.HudMessages;
import com.ziggfreed.common.ui.hud.settings.HudSettingsRows.Row;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The HUD settings page: where the shared bar panels sit and which of them show, for the player
 * looking at it and, on the Server tab, for everyone.
 *
 * <p><b>Mine.</b> One switch hiding every bar, then for each panel: a picker over the spots
 * measured for it (the server's own choice first), and a switch showing or hiding that panel alone.
 * Every change is kept the moment it is made, through {@link HudPreferences}, and the player's
 * panels move on screen behind the page as it happens.
 *
 * <p><b>Server</b> (shown only to the audience the consumer registered): for each panel, whether it
 * is on for everyone, the spot it sits at, and every inline leaf an owner may restate over that
 * spot ({@link HudServerLeaf}: the offsets, the spread, the band, the cut, the least height and the
 * colour), each a field. The on/off switch is kept at once; the rest is a draft written by Save,
 * in exactly the shape an owner would type into {@code mods/ziggfreedcommon/hud-bar-panels.json},
 * through {@link HudBarPanelOwnerWriter}, so the file stays readable and editable by hand
 * afterwards. A blank field REMOVES that leaf, so the spot's own value applies again; a field that
 * will not read is named in a toast and NOTHING is written, for any panel, until it is fixed.
 *
 * <p>What each tab lists is {@link HudSettingsRows}, a plan worked out with no builder in hand;
 * this page appends it row by row from the shared settings-row templates into {@code #Rows},
 * addressed by index, so the page ships no per-panel markup and a third panel would cost it
 * nothing. Every control speaks the one event shape ({@link HudSettingsEventData}): {@code field}
 * with the row's id and live value, or {@code press} with the row's id. A tab switch reopens the
 * page; nothing else does, so the scroll position survives every edit.
 */
public final class HudSettingsPage extends ToastablePage<HudSettingsEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigHudSettingsPage.ui";

    /** The Mine tab: the player's own picks. */
    public static final String TAB_MINE = "mine";

    /** The Server tab: the owner defaults, written for everyone. */
    public static final String TAB_SERVER = "server";

    /** The key a row's live value rides under; the {@code @} is the client's resolve-as-path directive. */
    static final String VALUE_KEY = "@Value";

    private static final String ROWS = "#Rows";
    private static final String ROW_HEADER = "Pages/ZigFormHeaderRow.ui";
    private static final String ROW_TOGGLE = "Pages/ZigFormToggleRow.ui";
    private static final String ROW_DROPDOWN = "Pages/ZigFormDropdownRow.ui";
    private static final String ROW_FIELD = "Pages/ZigFormFieldRow.ui";
    private static final String ROW_NOTE = "Pages/ZigFormNoteRow.ui";

    @Nonnull private final String tab;
    private final boolean admin;

    /** Row id to its selector, so a partial update can address the control that changed. */
    private final Map<String, String> rowOf = new LinkedHashMap<>();

    /** The Server tab's draft: row id to the live value the admin typed or chose, until Save. */
    private final Map<String, String> draft = new LinkedHashMap<>();

    private int rows;

    HudSettingsPage(@Nonnull PlayerRef playerRef, @Nonnull String tab, boolean admin) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, HudSettingsEventData.CODEC);
        this.admin = admin;
        this.tab = admin && TAB_SERVER.equals(tab) ? TAB_SERVER : TAB_MINE;
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
        ZigRichButton.text(cmd, "#TabMine", msg("tab.mine"));
        ZigRichButton.text(cmd, "#TabServer", msg("tab.server"));
        SettingsUiUtil.setTabActive(cmd, "#TabMine", TAB_MINE.equals(tab));
        SettingsUiUtil.setTabActive(cmd, "#TabServer", TAB_SERVER.equals(tab));
        cmd.set("#Tabs.Visible", admin);
        SettingsUiUtil.bindButton(events, "#TabMine", "tab", "Tab", TAB_MINE);
        SettingsUiUtil.bindButton(events, "#TabServer", "tab", "Tab", TAB_SERVER);

        if (TAB_SERVER.equals(tab)) {
            render(cmd, events, HudSettingsRows.server(panelIds(), HudBarPanelConfig.getInstance()));
        } else {
            render(cmd, events, HudSettingsRows.mine(HudPreferences.component(playerRef), panelIds()));
        }

        ZigRichButton.text(cmd, "#BackButton", msg("back"));
        ZigRichButton.text(cmd, "#SaveButton", msg("save"));
        cmd.set("#SaveButton.Visible", TAB_SERVER.equals(tab));
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

    /** The panels the page lists, in the order the library attaches them. */
    @Nonnull
    private static List<String> panelIds() {
        List<String> ids = new ArrayList<>();
        for (HudBarLayout layout : HudBars.panels()) {
            ids.add(layout.panelId());
        }
        return ids;
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
     * The spots offered for {@code panelId}, behind a first entry meaning "no spot of my own",
     * worded by {@code noneKey}: the server's choice on Mine, the shipped file's on Server. A
     * dropdown entry is resolved by the client from its message id, so each entry carries the
     * placement's full label key rather than a server-rendered string.
     */
    @Nonnull
    private static List<DropdownEntryInfo> spotEntries(@Nonnull String panelId, @Nonnull String noneKey) {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        entries.add(new DropdownEntryInfo(
                LocalizableString.fromMessageId(HudMessages.key("settings." + noneKey)), HudSettingsRows.NONE));
        for (HudBarPlacementAsset spot : HudBarPlacementConfig.getInstance().offeredFor(panelId)) {
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
            return;
        }
        String action = data.action == null ? "" : data.action;
        switch (action) {
            case "tab" -> HudSettingsPages.open(store, ref, player, data.tab == null ? TAB_MINE : data.tab);
            case "field" -> handleField(data.field, data.value);
            case "press" -> handlePress(data.field);
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

    /** A dropdown or field changed: a Mine pick is kept at once, a Server value waits for Save. */
    private void handleField(@Nullable String field, @Nullable String value) {
        if (field == null) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }
        String text = value == null ? "" : value.trim();
        if (field.startsWith(HudSettingsRows.PICK)) {
            String panelId = field.substring(HudSettingsRows.PICK.length());
            boolean clear = text.isEmpty();
            if (HudPreferences.setPlacementPick(playerRef, panelId, clear ? null : text)) {
                showToast(ToastKind.SUCCESS, clear ? msg("pick_cleared", panelLabel(panelId))
                        : msg("pick_saved", panelLabel(panelId), spotLabel(text)));
            } else if (HudPreferences.component(playerRef) == null) {
                showToast(ToastKind.ERROR, msg("not_kept"));
            }
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }
        draft.put(field, text);
        this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }

    /** A toggle was clicked: flip it, keep it, and repaint that one control. */
    private void handlePress(@Nullable String field) {
        UICommandBuilder cmd = new UICommandBuilder();
        String sel = field == null ? null : rowOf.get(field);
        if (sel == null) {
            this.sendUpdate(cmd, new UIEventBuilder(), false);
            return;
        }
        if (HudSettingsRows.HIDE_ALL.equals(field)) {
            boolean hide = !HudPreferences.isHideAll(playerRef);
            if (HudPreferences.setHideAll(playerRef, hide)) {
                paintToggle(cmd, sel, hide);
                showToast(ToastKind.SUCCESS, msg(hide ? "hide_all_on" : "hide_all_off"));
            } else {
                showToast(ToastKind.ERROR, msg("not_kept"));
            }
        } else if (field.startsWith(HudSettingsRows.SHOW)) {
            String panelId = field.substring(HudSettingsRows.SHOW.length());
            HudPreferenceComponent prefs = HudPreferences.component(playerRef);
            boolean hide = prefs == null || !prefs.isHiddenAlone(panelId);
            if (HudPreferences.setHidden(playerRef, panelId, hide)) {
                paintToggle(cmd, sel, !hide);
                showToast(ToastKind.SUCCESS, msg(hide ? "hidden" : "shown", panelLabel(panelId)));
            } else {
                showToast(ToastKind.ERROR, msg("not_kept"));
            }
        } else if (field.startsWith(HudSettingsRows.ENABLED) && admin) {
            String panelId = field.substring(HudSettingsRows.ENABLED.length());
            boolean on = !HudBarPanelConfig.getInstance().panel(panelId).enabled();
            if (HudBarPanelOwnerWriter.setEnabled(panelId, on)) {
                paintToggle(cmd, sel, on);
                showToast(ToastKind.SUCCESS, msg(on ? "panel_on" : "panel_off", panelLabel(panelId)));
            } else {
                showToast(ToastKind.ERROR, msg("save_failed"));
            }
        }
        this.sendUpdate(cmd, new UIEventBuilder(), false);
    }

    /**
     * Save reads every panel's draft FIRST, so a field that will not read is named and nothing is
     * written for any panel; then writes each panel's leaves as an owner would type them and
     * reopens the tab so the fold shows through.
     */
    private void handleSave(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        if (!admin || !TAB_SERVER.equals(tab)) {
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
            if (!HudBarPanelOwnerWriter.setLeaves(panel.getKey(), panel.getValue())) {
                showToast(ToastKind.ERROR, msg("save_failed"));
                this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
                return;
            }
        }
        showToast(ToastKind.SUCCESS, msg("saved"));
        HudSettingsPages.open(store, ref, player, TAB_SERVER);
    }

    @Nonnull
    private static Message panelLabel(@Nonnull String panelId) {
        return HudBarPanelConfig.getInstance().panel(panelId).label();
    }

    @Nonnull
    private static Message spotLabel(@Nonnull String placementId) {
        HudBarPlacementAsset spot = HudBarPlacementConfig.getInstance().placement(placementId);
        return spot != null ? spot.label() : Msg.raw(placementId);
    }
}
