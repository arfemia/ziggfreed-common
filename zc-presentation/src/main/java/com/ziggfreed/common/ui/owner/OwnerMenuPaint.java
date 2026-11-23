package com.ziggfreed.common.ui.owner;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.settings.page.SettingsLayout;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigFrame;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.ui.kit.Hero;
import com.ziggfreed.common.ui.kit.HeroPainter;

/**
 * Paints the owner menu ({@code Pages/ZigOwnerMenuPage.ui}) from its plan ({@link OwnerMenu#plan}): the frame's hero,
 * the status line, then one section per mod, each the Settings tab's section card with its tiles, sized to the tiles
 * it holds. The one place the menu's selectors are spelt; {@code KitSelectorsTest} walks every one.
 */
public final class OwnerMenuPaint {

    /** The page document. */
    public static final String PAGE_TEMPLATE = "Pages/ZigOwnerMenuPage.ui";

    /** One section: a heading over a card ({@code #Heading}, {@code #Tiles}). */
    public static final String SECTION_TEMPLATE = "Pages/ZigSettingsSection.ui";

    /** One tile, the shared {@code @ZigTile}. */
    public static final String TILE_TEMPLATE = "Pages/ZigSettingsTile.ui";

    /** What a press round-trips. */
    public static final String ACTION = "Action";
    public static final String TILE = "Tile";
    public static final String CLOSE = "close";
    public static final String PRESS = "tile";
    /** The Help button: into the menu's Help, or back out of it. */
    public static final String HELP = "help";

    /** What the Help button shows: nothing (no tile the owner sees carries a topic), Help, or the way back out. */
    public enum HelpButton {
        HIDDEN,
        OFFERED,
        SHOWING
    }

    /** The page's width, which the tile rows are worked out from. */
    public static final int PAGE_WIDTH = 1000;

    /** {@code #Content}'s padding, {@code #OwnerMenuSections}' right padding and the section card's padding. */
    private static final int CONTENT_PADDING = 18;
    private static final int SECTIONS_PADDING_RIGHT = 8;

    /** How many tiles sit across a card. */
    public static final int TILES_PER_ROW = Math.max(1, (PAGE_WIDTH - 2 * CONTENT_PADDING - SECTIONS_PADDING_RIGHT
            - 2 * SettingsLayout.CARD_PADDING) / (SettingsLayout.TILE_WIDTH + SettingsLayout.TILE_RIGHT));

    static final String SECTIONS = "#OwnerMenuSections";
    static final String STATUS = "#OwnerMenuStatus";
    static final String EMPTY = "#OwnerMenuEmpty";
    static final String BAR = "#OwnerMenuBar";
    static final String HELP_BUTTON = "#OwnerMenuHelp";

    private OwnerMenuPaint() {
    }

    /** The page's own bindings: the frame's close button and the Help button (bound even where it stays hidden). */
    public static void bind(@Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, ZigFrame.CLOSE, EventData.of(ACTION, CLOSE));
        events.addEventBinding(CustomUIEventBindingType.Activating, HELP_BUTTON, EventData.of(ACTION, HELP), false);
    }

    /**
     * Paint the menu, appended already: the hero, {@code status} (null for none), then {@code sections}. Each tile's
     * press carries its index in the answer, which the page reads back. The Help button stays hidden.
     *
     * @return every tile drawn, by index
     */
    @Nonnull
    public static List<OwnerTile> paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull List<OwnerMenu.Section> sections, @Nullable Message status, boolean error) {
        return paint(cmd, events, sections, status, error, HelpButton.HIDDEN);
    }

    /**
     * {@link #paint(UICommandBuilder, UIEventBuilder, List, Message, boolean)} with the Help button as {@code help}
     * says: hidden, offering Help, or (the menu showing its Help, its line saying so) leading back out.
     */
    @Nonnull
    public static List<OwnerTile> paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull List<OwnerMenu.Section> sections, @Nullable Message status, boolean error,
            @Nonnull HelpButton help) {
        boolean showing = help == HelpButton.SHOWING;
        HeroPainter.paint(cmd, new Hero(null, OwnerText.line("menu.title"),
                OwnerText.line(showing ? "menu.help.tagline" : "menu.tagline"), null));
        cmd.set(BAR + ".Visible", help != HelpButton.HIDDEN);
        if (help != HelpButton.HIDDEN) {
            ZigRichButton.text(cmd, HELP_BUTTON, OwnerText.line(showing ? "menu.help.done" : "menu.help"));
        }
        status(cmd, status, error);
        List<OwnerTile> drawn = new ArrayList<>();
        for (int s = 0; s < sections.size(); s++) {
            OwnerMenu.Section section = sections.get(s);
            cmd.append(SECTIONS, SECTION_TEMPLATE);
            String at = SECTIONS + "[" + s + "]";
            cmd.set(at + " #Heading.TextSpans", section.heading());
            List<OwnerTile> tiles = section.tiles();
            for (int t = 0; t < tiles.size(); t++) {
                OwnerTile tile = tiles.get(t);
                cmd.append(at + " #Tiles", TILE_TEMPLATE);
                String tileAt = at + " #Tiles[" + t + "]";
                paintTile(cmd, tileAt, tile);
                events.addEventBinding(CustomUIEventBindingType.Activating, tileAt,
                        EventData.of(ACTION, PRESS).append(TILE, String.valueOf(drawn.size())), false);
                drawn.add(tile);
            }
            cmd.set(at + " #Tiles.Visible", true);
            Anchor grid = new Anchor();
            grid.setHeight(Value.of(gridHeight(tiles.size())));
            cmd.setObject(at + " #Tiles.Anchor", grid);
        }
        if (sections.isEmpty()) {
            cmd.set(EMPTY + ".TextSpans", OwnerText.line(showing ? "menu.help.empty" : "menu.empty"));
            cmd.set(EMPTY + ".Visible", true);
        }
        return drawn;
    }

    /** The menu for a player who may not see it: the hero saying so, and nothing else. */
    public static void refused(@Nonnull UICommandBuilder cmd) {
        HeroPainter.paint(cmd, new Hero(null, OwnerText.line("menu.title"), OwnerText.line("menu.refused"), null));
        cmd.set(BAR + ".Visible", false);
    }

    /** Show {@code status} on the status line (red for an error), or hide it for null. */
    public static void status(@Nonnull UICommandBuilder cmd, @Nullable Message status, boolean error) {
        SettingsUiUtil.setStatus(cmd, STATUS, status, error);
    }

    /** The height a card's tile grid takes for {@code tiles} tiles: one row at least. */
    public static int gridHeight(int tiles) {
        int rows = Math.max(1, (tiles + TILES_PER_ROW - 1) / TILES_PER_ROW);
        return rows * SettingsLayout.TILE_ROW_HEIGHT;
    }

    private static void paintTile(@Nonnull UICommandBuilder cmd, @Nonnull String at, @Nonnull OwnerTile tile) {
        cmd.set(at + " #TileTitle.TextSpans", tile.title());
        Message line = tile.line();
        if (line != null) {
            cmd.set(at + " #TileSubtitle.TextSpans", line);
        } else {
            cmd.set(at + " #TileSubtitle.Visible", false);
        }
        String icon = tile.icon();
        if (icon == null || !IconRenderer.pushItem(cmd, at + " #TileIcon", icon)) {
            cmd.set(at + " #TileIcon.Visible", false);
        }
    }
}
