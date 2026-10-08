package com.ziggfreed.common.settings.page;

import java.util.ArrayList;
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
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.hud.command.HudMessages;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.ui.menu.MenuFrame;
import com.ziggfreed.common.ui.menu.MenuRail;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Settings tab: a player's own choices for this server, one headed card per section
 * ({@link ZigSettings#sections()}: a consumer's, then the quest tracker, the notifications and the title),
 * on the shared menu's rail with the Settings tab selected.
 *
 * <p>Switches and dropdowns act in place: a change is kept the moment it is made, through the row's own
 * {@code set}, and the page repaints every row's state and whether it shows with one partial update (so a
 * child switch disappears with its parent, and the scroll stays put). Only a tile opens another screen.
 * Every row is appended at build, each one's {@code .Visible} following its rule; a section with no row
 * showing at build is not drawn, and a tile hidden at build is not appended.
 *
 * <p>Every {@code handleDataEvent} exit opens a page, closes this one, or sends an update: a rail click
 * is answered by {@link MenuRail#handle} first, and a click the plan cannot resolve (a stale index, a row
 * hidden since the build) is answered empty. A reading or a write that throws costs its own row.
 */
public final class SettingsPage extends ToastablePage<SettingsEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigSettingsPage.ui";
    static final String SECTION_TEMPLATE = "Pages/ZigSettingsSection.ui";
    static final String TILE_TEMPLATE = "Pages/ZigSettingsTile.ui";
    static final String ROW_HEADING = "Pages/ZigFormHeaderRow.ui";
    static final String ROW_TOGGLE = "Pages/ZigFormToggleRow.ui";
    static final String ROW_CHOICE = "Pages/ZigFormDropdownRow.ui";

    /** The key a dropdown's live value rides under; the {@code @} is the client's resolve-as-path directive. */
    static final String VALUE_KEY = "@Value";

    private static final String SECTIONS = "#Sections";

    /** One drawn row: what it is, where it sits, and which drawn section it is in. */
    private record Drawn(@Nonnull SettingsRow row, @Nonnull String at, int section) {
    }

    @Nonnull private MenuRail rail = MenuRail.EMPTY;
    @Nonnull private final List<Drawn> drawn = new ArrayList<>();
    private int sectionCount;

    public SettingsPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, SettingsEventData.CODEC);
    }

    // ==================== build ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ZigMenu.appendThemed(cmd, PAGE_TEMPLATE, MenuFrame.RAIL);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));
        Player player = store.getComponent(ref, Player.getComponentType());
        // The page's own title first: the document declares the title row's branding hosts, so the paint below is
        // told so (titleRow true), and a consumer's right-mode server name written onto #PanelTitle wins over it.
        cmd.set("#PanelTitle.TextSpans", SettingsText.line("title"));
        // The shared menu's rail, the Settings tab selected; painted before anything that could return early.
        rail = ZigMenu.paint(cmd, events, store, ref, player, MenuSlot.SETTINGS.id(), true);
        cmd.set("#SettingsDescription.TextSpans", SettingsText.line("description"));

        drawn.clear();
        SettingsViewer viewer = SettingsViewer.of(store, ref, player);
        List<SettingsSection> sections = SettingsPlan.drawable(ZigSettings.sections(), viewer);
        sectionCount = sections.size();
        for (int i = 0; i < sections.size(); i++) {
            appendSection(cmd, events, i, sections.get(i), viewer);
        }
        paintState(cmd, viewer);
        renderToastInto(cmd);
    }

    private void appendSection(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, int index,
            @Nonnull SettingsSection section, @Nonnull SettingsViewer viewer) {
        cmd.append(SECTIONS, SECTION_TEMPLATE);
        String sel = SECTIONS + "[" + index + "]";
        cmd.set(sel + " #Heading.TextSpans", section.heading());
        int rows = 0;
        int tiles = 0;
        for (SettingsRow row : section.rows()) {
            if (row.kind() == SettingsRow.Kind.TILE) {
                if (!SettingsPlan.visible(row, viewer)) {
                    continue;
                }
                cmd.append(sel + " #Tiles", TILE_TEMPLATE);
                String at = sel + " #Tiles[" + tiles++ + "]";
                appendTile(cmd, events, drawn.size(), at, row);
                drawn.add(new Drawn(row, at, index));
                continue;
            }
            cmd.append(sel + " #Rows", templateOf(row.kind()));
            String at = sel + " #Rows[" + rows++ + "]";
            appendRow(cmd, events, drawn.size(), at, row, viewer);
            drawn.add(new Drawn(row, at, index));
        }
        if (tiles > 0) {
            cmd.set(sel + " #Tiles.Visible", true);
            Anchor grid = new Anchor();
            grid.setHeight(Value.of(SettingsLayout.tileGridHeight(tiles)));
            cmd.setObject(sel + " #Tiles.Anchor", grid);
        }
    }

    @Nonnull
    private static String templateOf(@Nonnull SettingsRow.Kind kind) {
        return switch (kind) {
            case HEADING -> ROW_HEADING;
            case TOGGLE -> ROW_TOGGLE;
            case CHOICE -> ROW_CHOICE;
            case TILE -> TILE_TEMPLATE;
        };
    }

    private void appendRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, int index,
            @Nonnull String at, @Nonnull SettingsRow row, @Nonnull SettingsViewer viewer) {
        cmd.set(at + " #Title.TextSpans", row.label());
        Message hint = row.hint();
        if (hint != null) {
            cmd.set(at + " #Hint.TextSpans", hint);
            cmd.set(at + " #Hint.Visible", true);
        }
        if (row.kind() == SettingsRow.Kind.TOGGLE) {
            SettingsUiUtil.bindButton(events, at + " #Toggle", "toggle", "Row", String.valueOf(index));
        } else if (row.kind() == SettingsRow.Kind.CHOICE) {
            String control = at + " #Dropdown";
            cmd.set(control + ".Entries", entries(row, viewer));
            cmd.set(control + ".Value", choiceValue(row, viewer));
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, control,
                    EventData.of("Action", "choice").append("Row", String.valueOf(index)).append(VALUE_KEY, control + ".Value"),
                    false);
        }
    }

    private void appendTile(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, int index,
            @Nonnull String at, @Nonnull SettingsRow row) {
        cmd.set(at + " #TileTitle.TextSpans", row.label());
        String icon = row.icon();
        if (icon == null || !IconRenderer.pushItem(cmd, at + " #TileIcon", icon)) {
            cmd.set(at + " #TileIcon.Visible", false);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, at,
                EventData.of("Action", "tile").append("Row", String.valueOf(index)), false);
    }

    /** Every drawn row's state and whether it shows, and whether each section still has a row showing. */
    private void paintState(@Nonnull UICommandBuilder cmd, @Nonnull SettingsViewer viewer) {
        boolean[] anyShown = new boolean[sectionCount];
        for (Drawn d : drawn) {
            boolean shows = SettingsPlan.visible(d.row(), viewer);
            anyShown[d.section()] |= shows;
            switch (d.row().kind()) {
                case TILE -> cmd.set(d.at() + " #TileSubtitle.TextSpans", tileLine(d.row(), viewer));
                case TOGGLE -> {
                    cmd.set(d.at() + ".Visible", shows);
                    SettingsUiUtil.setToggle(cmd, d.at() + " #Toggle", toggleOn(d.row(), viewer),
                            HudMessages.line("settings.on"), HudMessages.line("settings.off"));
                }
                case HEADING, CHOICE -> cmd.set(d.at() + ".Visible", shows);
            }
        }
        for (int i = 0; i < sectionCount; i++) {
            cmd.set(SECTIONS + "[" + i + "].Visible", anyShown[i]);
        }
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull SettingsEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            answer();
            return;
        }
        if (rail.handle(data.menu, store, ref, player, this::answer)) {
            return;
        }
        SettingsViewer viewer = SettingsViewer.of(store, ref, player);
        String action = data.action == null ? "" : data.action;
        switch (action) {
            case "toggle" -> onToggle(viewer, data.row);
            case "choice" -> onChoice(viewer, data.row, data.value);
            case "tile" -> onTile(viewer, data.row);
            case "close" -> player.getPageManager().setPage(ref, store, Page.None);
            default -> answer();
        }
    }

    private void onToggle(@Nonnull SettingsViewer viewer, @Nullable String token) {
        SettingsRow row = SettingsPlan.clicked(rows(), token, SettingsRow.Kind.TOGGLE, viewer);
        if (row == null) {
            answer();
            return;
        }
        boolean kept;
        try {
            kept = row.toggle().set(viewer, !row.toggle().on(viewer));
        } catch (Throwable t) {
            SafeLog.warn("[settings] the '" + row.id() + "' switch failed to keep a change: " + t.getMessage());
            kept = false;
        }
        repaint(viewer, kept);
    }

    private void onChoice(@Nonnull SettingsViewer viewer, @Nullable String token, @Nullable String value) {
        SettingsRow row = SettingsPlan.clicked(rows(), token, SettingsRow.Kind.CHOICE, viewer);
        if (row == null || value == null) {
            answer();
            return;
        }
        boolean kept;
        try {
            kept = row.choice().set(viewer, value.trim());
        } catch (Throwable t) {
            SafeLog.warn("[settings] the '" + row.id() + "' choice failed to keep a change: " + t.getMessage());
            kept = false;
        }
        repaint(viewer, kept);
    }

    private void onTile(@Nonnull SettingsViewer viewer, @Nullable String token) {
        SettingsRow row = SettingsPlan.clicked(rows(), token, SettingsRow.Kind.TILE, viewer);
        boolean opened = false;
        if (row != null) {
            try {
                opened = row.tile().open(viewer);
            } catch (Throwable t) {
                SafeLog.warn("[settings] the '" + row.id() + "' tile failed to open: " + t.getMessage());
            }
        }
        if (!opened) {
            answer();
        }
    }

    /** One partial update with every row's fresh state, and a toast when a change could not be kept. */
    private void repaint(@Nonnull SettingsViewer viewer, boolean kept) {
        if (!kept) {
            showToast(ToastKind.ERROR, HudMessages.line("settings.not_kept"));
        }
        UICommandBuilder cmd = new UICommandBuilder();
        paintState(cmd, viewer);
        sendUpdate(cmd, new UIEventBuilder(), false);
    }

    /** An empty update: the client always hears back. */
    private void answer() {
        sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }

    @Nonnull
    private List<SettingsRow> rows() {
        return drawn.stream().map(Drawn::row).toList();
    }

    // ==================== guarded reads ====================

    private static boolean toggleOn(@Nonnull SettingsRow row, @Nonnull SettingsViewer viewer) {
        try {
            return row.toggle().on(viewer);
        } catch (Throwable t) {
            SafeLog.warn("[settings] the '" + row.id() + "' switch failed to read: " + t.getMessage());
            return false;
        }
    }

    @Nonnull
    private static List<DropdownEntryInfo> entries(@Nonnull SettingsRow row, @Nonnull SettingsViewer viewer) {
        List<DropdownEntryInfo> out = new ArrayList<>();
        try {
            for (SettingsOption option : row.choice().options(viewer)) {
                out.add(new DropdownEntryInfo(option.label(), option.value()));
            }
        } catch (Throwable t) {
            SafeLog.warn("[settings] the '" + row.id() + "' choice failed to list its entries: " + t.getMessage());
        }
        return out;
    }

    @Nonnull
    private static String choiceValue(@Nonnull SettingsRow row, @Nonnull SettingsViewer viewer) {
        try {
            return row.choice().value(viewer);
        } catch (Throwable t) {
            SafeLog.warn("[settings] the '" + row.id() + "' choice failed to read: " + t.getMessage());
            return "";
        }
    }

    @Nonnull
    private static Message tileLine(@Nonnull SettingsRow row, @Nonnull SettingsViewer viewer) {
        try {
            return row.tile().line(viewer);
        } catch (Throwable t) {
            SafeLog.warn("[settings] the '" + row.id() + "' tile failed to read: " + t.getMessage());
            return Msg.raw("");
        }
    }
}
