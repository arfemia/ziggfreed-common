package com.ziggfreed.common.ui.spike;

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
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.inventory.ItemIds;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.util.SafeLog;

/**
 * The {@code /zigspike} bench for the 1.7.0 redesign (W1-58, plan section 5; spike branch {@code rd-spike} only,
 * never merged): one panel per mechanism the kit relies on, SP1a to SP16, each captioned with what a pass looks
 * like, so the maintainer judges every one in about ten minutes.
 *
 * <p>{@link #build} paints only pushes the family or vanilla already proves: appends, {@code .TextSpans},
 * {@code .Visible}, an item's own icon texture through {@link IconRenderer#applyPlainIcon}, grid slots, and one
 * {@code appendInline} (vanilla {@code CommandListPage}). Every unproven push sits behind its own button, in the
 * plan's order, and each press echoes on {@code #Status} in the SAME update, so an echo with no visible change
 * is a fail and a dropped connection is a fail too. Every exit of {@link #handleDataEvent} answers.
 *
 * <p>Vanilla sources for what the bench pushes: {@code Value.ref} onto a row's {@code .Style}
 * ({@code WorldEventPanelPage.java:88-92}, {@code :790}), onto a style in a document the appended row imports
 * ({@code TriggerVolumeInspectorPage.java:101-103}, {@code :496}) and onto a {@code Label}'s {@code .Style}
 * ({@code TriggerVolumeInspectorPage.java:110-115}, {@code :1647}); a whole {@code Anchor} by {@code setObject}
 * ({@code MemoriesPage.java:237-242}); {@code .TooltipText} with a translation ({@code MemoriesPage.java:189});
 * {@code .Disabled} ({@code OverrideNearbyRespawnPointPage.java:104}, {@code BarterPage.java:168}); rows appended
 * and bound in a partial ({@code ChangeModelPage}).
 */
public final class SpikePage extends InteractiveCustomUIPage<SpikeEventData> {

    static final String DOCUMENT = "Pages/ZigSpikePage.ui";
    static final String ROW = "Pages/ZigSpikeRow.ui";
    static final String KIT = "Common/ZigSpikeKit.ui";
    static final String THEME = "Common/Themes/ZigSpikeTheme.ui";

    /** An Epic vanilla item, so its grid slot draws the rarity square SP2b removes. */
    static final String AXE = "Weapon_Axe_Mithril";

    private static final Value<String> ROW_NORMAL = Value.ref(ROW, "SpikeRowStyle");
    private static final Value<String> ROW_SELECTED = Value.ref(ROW, "SpikeRowSelectedStyle");
    private static final Value<String> KIT_SELECTED = Value.ref(KIT, "SpikeSharedSelectedStyle");
    private static final Value<String> THEME_RED = Value.ref(THEME, "SpikeThemeRowStyle");
    private static final Value<String> WORD_DONE = Value.ref(DOCUMENT, "SpikeWordDoneStyle");

    private static final String[] ROW_NAMES = {"Row 1", "Row 2", "Row 3", "Row 4"};
    private static final String[] SECTION_NAMES = {"Section A", "Section B"};
    private static final String[] NESTED_NAMES = {"Nested row 1", "Nested row 2", "Nested row 3"};

    /** SP2's picture slots, by size: 20, 28, 32, 40, 48, 64, 96. */
    private static final String[] PICTURE_SLOTS = {"#PicA", "#PicB", "#PicC", "#PicD", "#PicE", "#PicF", "#PicG"};

    /** SP10's sample strings, raw on purpose (spike only): a German and a Russian row title and segment word. */
    private static final String TITLE_DE = "Schattenbrecher der Kürbisnacht 2026 und die Prüfung der Unterwelt";
    private static final String TITLE_RU = "Сокрушитель"
            + " призраков"
            + " Тыквенной ночи 2026"
            + " и испытание"
            + " подземного мира";
    private static final String SEG_DE = "Herausforderungen";
    private static final String SEG_RU = "Статистика"
            + " сезона";

    /** SP5: the marker track is 600 wide; the marker steps along it and wraps. */
    private static final int MARKER_STEP = 118;
    private static final int MARKER_STOPS = 6;

    /**
     * SP16: one picture per item, each {left, top, size} on the 962 x 240 plate, all clear of its edges; the two
     * lanterns sit inside the glow's box.
     */
    private static final String[] PLATE_ITEMS = {
            "Weapon_Battleaxe_Mithril", "Armor_Mithril_Chest", "Deco_Lever",
            "Weapon_Axe_Mithril", "Deco_Lantern", "Furniture_Kweebec_Lantern"};
    private static final int[][] PLATE_SLOTS = {
            {36, 56, 128}, {200, 24, 96}, {330, 160, 48}, {430, 40, 64}, {560, 120, 96}, {790, 36, 128}};
    private static final String PLATE_PICTURE = "Group { AssetImage #IcoTex { Anchor: (Full: 0); Visible: false; "
            + "FallbackTexturePath: \"UI/Custom/Pages/Memories/MissingIcon.png\"; } }";

    /**
     * SP16's layers under the pictures: the plate's flat bottom colour, the white sky gradient tinted to the top
     * colour by {@code .Background.Color}, and the white glow tinted the same way and placed by a whole Anchor as
     * a 400 x 400 box at (514, -78), so it overflows the plate above and below (a clip test).
     */
    private static final String PLATE_BOTTOM = "#2a1a2c";
    private static final String PLATE_TOP = "#0a0f1e";
    private static final String PLATE_GLOW = "#a0501a";

    private int presses;
    private int markerStop;
    private boolean sectionOpen;
    private boolean plateComposed;
    private boolean collectDisabled;

    public SpikePage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, SpikeEventData.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        cmd.append(DOCUMENT);
        try {
            paint(cmd);
        } catch (Throwable t) {
            SafeLog.warn("[spike] the bench could not paint", t);
        }
        try {
            bind(events);
        } catch (Throwable t) {
            SafeLog.warn("[spike] the bench could not bind", t);
        }
    }

    private static void paint(@Nonnull UICommandBuilder cmd) {
        for (int i = 0; i < ROW_NAMES.length; i++) {
            cmd.append("#SelRows", ROW);
            cmd.set(row("#SelRows", i) + " #Select #Label.TextSpans", Msg.raw(ROW_NAMES[i]));
        }
        for (int i = 0; i < SECTION_NAMES.length; i++) {
            cmd.append("#SecList", ROW);
            cmd.set(row("#SecList", i) + " #Select #Label.TextSpans", Msg.raw(SECTION_NAMES[i]));
        }
        for (String slot : PICTURE_SLOTS) {
            IconRenderer.applyPlainIcon(cmd, slot, AXE, null);
        }
        paintGrids(cmd);
        cmd.appendInline("#MarkerTrack", "Group { Anchor: (Left: 0, Top: 0, Width: 6, Height: 20); "
                + "Background: (Color: #e8a93b); }");
        IconRenderer.applyPlainIcon(cmd, "#BadPic", AXE, null);
        cmd.set("#TitleDe.TextSpans", Msg.raw(TITLE_DE));
        cmd.set("#TitleRu.TextSpans", Msg.raw(TITLE_RU));
        cmd.set("#SegDe #Label.TextSpans", Msg.raw(SEG_DE));
        cmd.set("#SegRu #Label.TextSpans", Msg.raw(SEG_RU));
        IconRenderer.applyPlainIcon(cmd, "#HeroPic", AXE, null);
    }

    /** SP2b: the same Epic item in two reward grids, the right one without its rarity square. */
    private static void paintGrids(@Nonnull UICommandBuilder cmd) {
        if (!ItemIds.exists(AXE)) {
            return;
        }
        cmd.set("#GridPlain.Slots", List.of(new ItemGridSlot(new ItemStack(AXE, 1))));
        ItemGridSlot skipped = new ItemGridSlot(new ItemStack(AXE, 1));
        skipped.setSkipItemQualityBackground(true);
        cmd.set("#GridSkip.Slots", List.of(skipped));
    }

    private static void bind(@Nonnull UIEventBuilder events) {
        bind(events, "#CloseButton", "close");
        bind(events, "#BtnRowOne", "rowOne");
        bind(events, "#BtnRowTwo", "rowTwo");
        bind(events, "#BtnRowThree", "rowThree");
        bind(events, "#BtnRowFour", "rowFour");
        bind(events, "#BtnTone", "tone");
        bind(events, "#BtnMarker", "marker");
        bind(events, "#BtnOpen", "open");
        bind(events, "#BtnBadPic", "badPicture");
        bind(events, "#BtnRing", "ring");
        bind(events, "#BtnTips", "tips");
        bind(events, "#BtnCompose", "compose");
        bind(events, "#CollectBtn", "collect");
        bind(events, "#BtnDisable", "disable");
    }

    private static void bind(@Nonnull UIEventBuilder events, @Nonnull String selector, @Nonnull String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("Action", action), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull SpikeEventData data) {
        String action = data.action == null ? "" : data.action;
        if ("close".equals(action)) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                player.getPageManager().setPage(ref, store, Page.None);
                return;
            }
        }
        presses++;
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        try {
            answer(action, data.index, cmd, events);
        } catch (Throwable t) {
            SafeLog.warn("[spike] the bench failed on " + action, t);
            cmd = new UICommandBuilder();
            events = new UIEventBuilder();
            status(cmd, "The server failed on this press; the server log has why.");
        }
        sendUpdate(cmd, events, false);
    }

    private void answer(@Nonnull String action, @Nullable String index, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events) {
        switch (action) {
            case "rowOne" -> {
                cmd.set(row("#SelRows", 0) + " #Select.Style", ROW_SELECTED);
                cmd.set(row("#SelRows", 1) + " #Select.Style", ROW_NORMAL);
                status(cmd, "SP1a sent: row 1 selected, row 2 normal, both by reference to the row document.");
            }
            case "rowTwo" -> {
                cmd.set(row("#SelRows", 1) + " #Select.Style", ROW_SELECTED);
                cmd.set(row("#SelRows", 0) + " #Select.Style", ROW_NORMAL);
                status(cmd, "SP1a sent: row 2 selected, row 1 normal, both by reference to the row document.");
            }
            case "rowThree" -> {
                cmd.set(row("#SelRows", 2) + " #Select.Style", KIT_SELECTED);
                status(cmd, "SP1b sent: row 3 by reference to the document the row imports.");
            }
            case "rowFour" -> {
                cmd.set(row("#SelRows", 3) + " #Select.Style", THEME_RED);
                status(cmd, "SP1c sent: row 4 by reference to the theme document nothing loaded.");
            }
            case "tone" -> {
                cmd.set("#ToneWord.Style", WORD_DONE);
                status(cmd, "SP1d sent: the word's label style by reference.");
            }
            case "marker" -> {
                markerStop = (markerStop + 1) % MARKER_STOPS;
                cmd.setObject(row("#MarkerTrack", 0) + ".Anchor", anchor(markerStop * MARKER_STEP, 0, 6, 20));
                status(cmd, "SP5 sent: the marker's whole Anchor.");
            }
            case "open" -> openSection(cmd, events);
            case "nested" -> status(cmd, "SP7: a nested row answered its click.", index);
            case "badPicture" -> {
                cmd.set("#BadPic #IcoTex.AssetPath", "UI/Custom/Common/Glyphs/NoSuchGlyph.png");
                cmd.set("#BadPic #IcoTex.Visible", true);
                status(cmd, "SP8 sent: a picture path that does not exist.");
            }
            case "ring" -> {
                cmd.set("#Ring.Value", 0.6f);
                status(cmd, "SP12 sent: the ring's value.");
            }
            case "tips" -> {
                cmd.set("#TipBtn.TooltipText", Msg.key("ziggfreedcommon.ui.hud.bar.gain", 1234));
                cmd.set("#TipBox.TooltipText", Msg.key("ziggfreedcommon.ui.menu.desc"));
                status(cmd, "SP13 sent: two tooltips, now hover the button and the box.");
            }
            case "compose" -> composePlate(cmd);
            case "collect" -> status(cmd, "SP15: Collect answered a click, so it is enabled.");
            case "disable" -> {
                collectDisabled = !collectDisabled;
                cmd.set("#CollectBtn.Disabled", collectDisabled);
                status(cmd, collectDisabled ? "SP15 sent: Collect disabled." : "SP15 sent: Collect enabled.");
            }
            default -> status(cmd, "That press carried no action the bench knows.");
        }
    }

    /** SP7: three rows appended under Section B in this partial, each bound in the same update. */
    private void openSection(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        if (sectionOpen) {
            status(cmd, "SP7: the section is already open; click its rows.");
            return;
        }
        sectionOpen = true;
        String rows = row("#SecList", 1) + " #Rows";
        for (int i = 0; i < NESTED_NAMES.length; i++) {
            cmd.append(rows, ROW);
            String nested = row(rows, i);
            cmd.set(nested + " #Select #Label.TextSpans", Msg.raw(NESTED_NAMES[i]));
            events.addEventBinding(CustomUIEventBindingType.Activating, nested + " #Select",
                    EventData.of("Action", "nested").append("Index", String.valueOf(i + 1)), false);
        }
        status(cmd, "SP7 sent: three rows appended under Section B and bound.");
    }

    /**
     * SP16: the plate's flat colour, the tinted sky, the tinted glow placed by a whole Anchor, then six item
     * pictures appended onto the plate, each placed and sized by a whole Anchor.
     */
    private void composePlate(@Nonnull UICommandBuilder cmd) {
        if (plateComposed) {
            status(cmd, "SP16: the plate is already composed.");
            return;
        }
        plateComposed = true;
        UiRetint.fill(cmd, "#GenPlate", PLATE_BOTTOM);
        UiRetint.retintColor(cmd, "#GenSky", PLATE_TOP);
        cmd.set("#GenSky.Visible", true);
        cmd.setObject("#GenGlow.Anchor", anchor(514, -78, 400, 400));
        UiRetint.retintColor(cmd, "#GenGlow", PLATE_GLOW);
        cmd.set("#GenGlow.Visible", true);
        for (int i = 0; i < PLATE_ITEMS.length; i++) {
            int[] slot = PLATE_SLOTS[i];
            String host = row("#GenIcons", i);
            cmd.appendInline("#GenIcons", PLATE_PICTURE);
            cmd.setObject(host + ".Anchor", anchor(slot[0], slot[1], slot[2], slot[2]));
            IconRenderer.applyPlainIcon(cmd, host, PLATE_ITEMS[i], null);
        }
        status(cmd, "SP16 sent: plate colour, tinted sky, tinted glow by Anchor, and six pictures by Anchor.");
    }

    @Nonnull
    private static Anchor anchor(int left, int top, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(left));
        anchor.setTop(Value.of(top));
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(height));
        return anchor;
    }

    @Nonnull
    private static String row(@Nonnull String list, int index) {
        return list + "[" + index + "]";
    }

    /** Echo a press on the orange line, numbered so two presses of one button read apart. */
    private void status(@Nonnull UICommandBuilder cmd, @Nonnull String line) {
        status(cmd, line, null);
    }

    private void status(@Nonnull UICommandBuilder cmd, @Nonnull String line, @Nullable String detail) {
        Message echo = Msg.cat(Msg.raw("Press "), Msg.num(presses), Msg.raw(": " + line));
        if (detail != null) {
            echo = Msg.cat(echo, Msg.raw(" Row " + detail + "."));
        }
        cmd.set("#Status.TextSpans", echo);
    }
}
