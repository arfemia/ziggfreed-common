package com.ziggfreed.common.ui.menu;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.util.SafeLog;

/**
 * The shared left-tab menu: the library's four slots ({@link MenuSlot}), filled by the modules that own
 * their screens, under one consumer section ({@link #consumer}). A page on {@code @ZigMenuFrame} paints
 * the rail with {@link #paint}, keeps the {@link MenuRail} it returns, and answers a click through
 * {@link MenuRail#handle}; each row is {@code Pages/ZigMenuTab.ui} appended by index into
 * {@link MenuFrame#LIST}, so no entry id ever becomes an element id.
 *
 * <p>World thread for every paint and open. The slots and the consumer are written at setup and read
 * on every paint.
 */
public final class ZigMenu {

    /** The event-data key a rail click carries (the row's index); every page on the rail declares it. */
    public static final String EVENT_KEY = "Menu";

    static final String TAB_TEMPLATE = "Pages/ZigMenuTab.ui";
    static final String ROW_LINE = "#TabLine";
    static final String BUTTON = "#TabBtn";
    static final String MARKER = "#Marker";
    static final String ICON_SLOT = "#TabIconSlot";
    static final String HEADER = "#Header";
    static final String SPACER = "#Spacer";

    private static final Map<MenuSlot, MenuEntry> SLOTS = new ConcurrentHashMap<>();
    private static final AtomicReference<Supplier<MenuDeps>> CONSUMER = new AtomicReference<>();

    private ZigMenu() {
    }

    // ==================== registration ====================

    /** Fill one of the library's slots. Called once at setup by the module that owns the screen. */
    public static void fill(@Nonnull MenuSlot slot, @Nonnull MenuEntry entry) {
        if (!slot.id().equals(entry.id())) {
            SafeLog.warn("[menu] the " + slot.id() + " slot was filled with an entry named '" + entry.id()
                    + "'; a page selecting it must name '" + entry.id() + "'");
        }
        SLOTS.put(slot, entry);
    }

    /** What fills {@code slot} right now, or null while nothing does. */
    @Nullable
    public static MenuEntry slot(@Nonnull MenuSlot slot) {
        return SLOTS.get(slot);
    }

    /**
     * Say what a consumer adds to the menu ({@link MenuDeps}). Call once from a consumer's setup;
     * last write wins, and null goes back to the library's four slots alone. Resolved on every paint.
     */
    public static void consumer(@Nullable Supplier<MenuDeps> supplier) {
        CONSUMER.set(supplier);
    }

    /** The deps in force: the consumer's, else {@link MenuDeps#EMPTY}. Guarded. */
    @Nonnull
    public static MenuDeps resolved() {
        Supplier<MenuDeps> supplier = CONSUMER.get();
        if (supplier == null) {
            return MenuDeps.EMPTY;
        }
        try {
            MenuDeps deps = supplier.get();
            return deps != null ? deps : MenuDeps.EMPTY;
        } catch (Throwable t) {
            SafeLog.warn("[menu] the consumer's menu deps failed to resolve: " + t.getMessage());
            return MenuDeps.EMPTY;
        }
    }

    // ==================== the frame ====================

    /**
     * Append a page's template and paint its frame, naming the panels the paint reaches (pass
     * {@link MenuFrame#RAIL} with the page's own bordered panels). A consumer that paints the frame by its
     * own policy ({@link MenuDeps#theme()}) does both; otherwise the library appends and paints the frame
     * from {@link MenuDeps#palette()}. Paint is decoration: a theme that throws falls back to the plain
     * append, the shape the book's own {@code appendTemplate} has always used.
     */
    public static void appendThemed(@Nonnull UICommandBuilder cmd, @Nonnull String template,
            @Nonnull String... frameSelectors) {
        MenuDeps deps = resolved();
        MenuDeps.PageTheme theme = deps.theme();
        if (theme != null) {
            try {
                theme.appendThemed(cmd, template, frameSelectors);
                return;
            } catch (Throwable t) {
                SafeLog.warn("[menu] a page theme failed, so " + template + " renders plain: " + t.getMessage());
            }
            cmd.append(template);
            return;
        }
        cmd.append(template);
        try {
            MenuFrameRetint.apply(cmd, deps.palette(), frameSelectors);
        } catch (Throwable t) {
            SafeLog.warn("[menu] the frame paint failed, so " + template + " keeps its authored look: " + t.getMessage());
        }
    }

    // ==================== painting the rail ====================

    /**
     * Paint the rail into the frame's {@link MenuFrame#LIST} and its branding hosts, for {@code viewer},
     * marking {@code selectedId} (a {@link MenuEntry#id()}; null marks none). {@code titleRow} is true
     * when the page declares the header-row branding hosts (see {@link MenuDeps.BrandingPainter}).
     * Returns the rail the page keeps for {@link MenuRail#handle}.
     */
    @Nonnull
    public static MenuRail paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull DestinationContext viewer, @Nullable String selectedId, boolean titleRow) {
        MenuDeps deps = resolved();
        MenuPalette.Resolved colours = MenuPalette.resolve(deps.palette());
        // The rail's branding labels take the palette's text colours whether or not a painter shows them.
        cmd.set("#BrandingServerName.Style.TextColor", colours.textPrimary());
        cmd.set("#BrandingDescription.Style.TextColor", colours.textMuted());
        try {
            deps.branding().paint(cmd, titleRow);
        } catch (Throwable t) {
            SafeLog.warn("[menu] the branding painter failed: " + t.getMessage());
        }
        List<MenuRow> rows = rows(deps, slots(), viewer);
        for (int i = 0; i < rows.size(); i++) {
            cmd.append(MenuFrame.LIST, TAB_TEMPLATE);
            paintRow(cmd, events, i, rows.get(i), selectedId, colours);
        }
        return new MenuRail(rows);
    }

    /** {@link #paint} for a page that holds the three handles; no player paints nothing. */
    @Nonnull
    public static MenuRail paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nullable Player player,
            @Nullable String selectedId, boolean titleRow) {
        return player == null ? MenuRail.EMPTY
                : paint(cmd, events, DestinationContext.of(store, ref, player), selectedId, titleRow);
    }

    private static void paintRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, int index,
            @Nonnull MenuRow row, @Nullable String selectedId, @Nonnull MenuPalette.Resolved colours) {
        String sel = MenuFrame.LIST + "[" + index + "]";
        switch (row.kind()) {
            case HEADER -> {
                cmd.set(sel + " " + ROW_LINE + ".Visible", false);
                cmd.set(sel + " " + HEADER + ".Visible", true);
                cmd.set(sel + " " + HEADER + ".TextSpans", row.header());
                cmd.set(sel + " " + HEADER + ".Style.TextColor", colours.textMuted());
            }
            case SPACER -> {
                cmd.set(sel + " " + ROW_LINE + ".Visible", false);
                cmd.set(sel + " " + SPACER + ".Visible", true);
            }
            case ENTRY -> paintEntry(cmd, events, sel, index, row.entry(), selectedId, colours);
        }
    }

    private static void paintEntry(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String sel, int index, @Nonnull MenuEntry entry, @Nullable String selectedId,
            @Nonnull MenuPalette.Resolved colours) {
        String button = sel + " " + BUTTON;
        boolean selected = entry.id().equals(selectedId);
        String fill = selected ? colours.header() : colours.background();
        ZigRichButton.text(cmd, button, entry.label());
        cmd.set(sel + " " + ROW_LINE + ".Background", colours.background());
        cmd.set(button + ".Style.Default.Background", fill);
        cmd.set(button + ".Style.Hovered.Background", colours.header());
        cmd.set(button + ".Style.Pressed.Background", fill);
        ZigRichButton.color(cmd, button, selected ? colours.textPrimary() : colours.textMuted());
        IconSpec icon = entry.icon();
        if (icon != null && !icon.isEmpty()) {
            String slot = sel + " " + ICON_SLOT;
            cmd.set(slot + ".Visible", IconRenderer.applyIcon(cmd, slot, icon));
        }
        if (selected) {
            // The selected tab: a bar beside it (a shape, not only a colour), a bold label on the selected
            // fill, and no binding, so pressing the page you are on does nothing.
            cmd.set(sel + " " + MARKER + ".Visible", true);
            cmd.set(sel + " " + MARKER + ".Background", colours.accent());
            cmd.set(button + " " + ZigRichButton.LABEL + ".Style.RenderBold", true);
            return;
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, button,
                EventData.of(EVENT_KEY, Integer.toString(index)), false);
    }

    // ==================== what the rail lists ====================

    /** The rows a paint draws: the consumer's shown entries under its heading, a gap, then the shown slots. */
    @Nonnull
    static List<MenuRow> rows(@Nonnull MenuDeps deps, @Nonnull Map<MenuSlot, MenuEntry> slots,
            @Nonnull DestinationContext viewer) {
        List<MenuRow> out = new ArrayList<>();
        MenuSection section = deps.section();
        if (section != null) {
            List<MenuEntry> shown = visible(section.entries(), viewer);
            if (!shown.isEmpty() && section.header() != null) {
                out.add(MenuRow.header(section.header()));
            }
            for (MenuEntry entry : shown) {
                out.add(MenuRow.entry(entry));
            }
        }
        List<MenuEntry> library = visible(ordered(slots), viewer);
        if (!out.isEmpty() && !library.isEmpty()) {
            out.add(MenuRow.SPACER);
        }
        for (MenuEntry entry : library) {
            out.add(MenuRow.entry(entry));
        }
        return List.copyOf(out);
    }

    /** A snapshot of the filled slots. */
    @Nonnull
    static Map<MenuSlot, MenuEntry> slots() {
        Map<MenuSlot, MenuEntry> out = new EnumMap<>(MenuSlot.class);
        out.putAll(SLOTS);
        return out;
    }

    @Nonnull
    private static List<MenuEntry> ordered(@Nonnull Map<MenuSlot, MenuEntry> slots) {
        List<MenuEntry> out = new ArrayList<>(MenuSlot.values().length);
        for (MenuSlot slot : MenuSlot.values()) {
            MenuEntry entry = slots.get(slot);
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    @Nonnull
    private static List<MenuEntry> visible(@Nonnull List<MenuEntry> entries, @Nonnull DestinationContext viewer) {
        List<MenuEntry> out = new ArrayList<>(entries.size());
        for (MenuEntry entry : entries) {
            if (visibleGuarded(entry, viewer)) {
                out.add(entry);
            }
        }
        return out;
    }

    /** Does {@code entry} show for {@code viewer}? A rule that throws hides it, once per throw, with a line. */
    static boolean visibleGuarded(@Nonnull MenuEntry entry, @Nonnull DestinationContext viewer) {
        try {
            return entry.visible().test(viewer);
        } catch (Throwable t) {
            SafeLog.warn("[menu] the '" + entry.id() + "' tab's rule failed, so it is hidden: " + t.getMessage());
            return false;
        }
    }

    // ==================== the landing ====================

    /**
     * Open where {@code /ziggui} lands for {@code viewer}: the consumer's landing, else the first slot that
     * shows. False when nothing opened, so the command says nothing is available.
     */
    public static boolean openLanding(@Nonnull DestinationContext viewer) {
        for (Destination candidate : landingCandidates(resolved(), visible(ordered(slots()), viewer))) {
            if (Destinations.open(candidate, viewer)) {
                return true;
            }
        }
        return false;
    }

    /** The landings to try, in order: the consumer's, then the first shown slot's screen. */
    @Nonnull
    static List<Destination> landingCandidates(@Nonnull MenuDeps deps, @Nonnull List<MenuEntry> shownSlots) {
        List<Destination> out = new ArrayList<>(2);
        if (deps.landing() != null) {
            out.add(deps.landing());
        }
        if (!shownSlots.isEmpty()) {
            out.add(shownSlots.get(0).opens());
        }
        return out;
    }

    /** Drop every slot and the consumer. Tests only. */
    public static void clearForTests() {
        SLOTS.clear();
        CONSUMER.set(null);
    }
}
