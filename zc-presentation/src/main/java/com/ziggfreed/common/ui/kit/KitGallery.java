package com.ziggfreed.common.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigFrame;
import com.ziggfreed.common.ui.ZigRichButton;

/**
 * What the kit gallery shows ({@link KitGalleryPage}, {@code /zigkit gallery}): the painting, kept apart from the page
 * so a test walks every selector it sends without building a page. The band view is
 * {@code Pages/ZigKitGalleryPage.ui} on {@code @ZigDecoratedFrame}, its hero filled here; the panel view is
 * {@code Pages/ZigKitGalleryPanel.ui} on {@code @ZigPanelFrame}. Every appended template is painted by its own kit
 * painter (rows, tabs, segments, tiles, figures, the reading page, the empty state), so the gallery shows each piece
 * as a real page draws it; the document names only its own hosts and buttons, and the frame's hero ids are
 * {@link ZigFrame}'s. Every word is a key of {@code ziggfreedcommon.ui.lang} ({@code gallery.*}) or the kit's own
 * ({@link KitText}), every number a typed one. Nothing is bound but the page's own three buttons: the rest is there to
 * be looked at and hovered.
 */
final class KitGallery {

    /** The band view and the panel view. */
    static final String PAGE_TEMPLATE = "Pages/ZigKitGalleryPage.ui";
    static final String PANEL_TEMPLATE = "Pages/ZigKitGalleryPanel.ui";

    /** The gallery's words. */
    static final String PREFIX = "ziggfreedcommon.ui.gallery.";

    /** The page's own buttons: the panel view, and back to the band. */
    static final String PANEL_BUTTON = "#GalleryPanelButton";
    static final String BAND_BUTTON = "#GalleryBandButton";

    /** The press each button sends. */
    static final String ACTION = "Action";
    static final String CLOSE = "close";
    static final String PANEL = "panel";
    static final String BAND = "band";

    /** The hero's emblem: the game's own Memories emblem the approved mocks use. */
    static final String EMBLEM = "UI/Custom/Pages/Memories/categories/VoidspawnComplete.png";

    private static final String GLYPHS = "UI/Custom/Common/Glyphs/";
    private static final Picture QUEST = Picture.texture(GLYPHS + "Quest.png");
    private static final Picture BOOK = Picture.texture(GLYPHS + "Book.png");
    private static final Picture STAR = Picture.texture(GLYPHS + "Star.png");
    private static final Picture TROPHY = Picture.texture(GLYPHS + "Trophy.png");
    private static final Picture BAG = Picture.texture(GLYPHS + "Bag.png");
    private static final Picture BOARD = Picture.texture(GLYPHS + "Board.png");
    private static final Picture LOCK = Picture.texture(GLYPHS + "Lock.png");

    /** The switched-off copies of the four action buttons. */
    private static final List<String> OFF_BUTTONS = List.of("#GalPrimaryOff", "#GalSecondaryOff", "#GalCollectOff",
            "#GalDangerOff");

    private static final String SELECTED_ROW = "tones/" + Tone.COLLECT.name();

    private static final LedgerBindings NO_ROW_BINDINGS = new LedgerBindings() {
        @Nullable
        @Override
        public EventData row(LedgerSection s, LedgerRow r) {
            return null;
        }

        @Nullable
        @Override
        public EventData section(LedgerSection s) {
            return null;
        }

        @Nullable
        @Override
        public EventData showMore(LedgerSection s) {
            return null;
        }
    };

    private static final DetailBindings NO_DETAIL_BINDINGS = new DetailBindings() {
        @Nullable
        @Override
        public EventData line(DetailBlock b, DetailLine l) {
            return null;
        }

        @Nullable
        @Override
        public EventData toggle(DetailToggle t) {
            return null;
        }
    };

    private KitGallery() {
    }

    /** The band view's bindings: the frame's close button and the switch to the panel view. */
    static void bindBand(@Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, ZigFrame.CLOSE, EventData.of(ACTION, CLOSE));
        events.addEventBinding(CustomUIEventBindingType.Activating, PANEL_BUTTON, EventData.of(ACTION, PANEL));
    }

    /** The panel view's bindings: the panel frame's close button and the switch back to the band. */
    static void bindPanel(@Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, ZigFrame.CLOSE, EventData.of(ACTION, CLOSE));
        events.addEventBinding(CustomUIEventBindingType.Activating, BAND_BUTTON, EventData.of(ACTION, BAND));
    }

    /** Paint the band view, appended already: the hero, then every section's painted pieces. */
    static void paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nullable PlayerRef viewer) {
        hero(cmd);
        tones(cmd);
        buttons(cmd);
        rows(cmd, events, viewer);
        tabs(cmd, events);
        tiles(cmd, events);
        cards(cmd);
        figures(cmd);
        reading(cmd, events, viewer);
        empty(cmd, events);
    }

    /** The frame's hero, which a page shows once a painter fills it: here the gallery fills it by the frame's ids. */
    private static void hero(@Nonnull UICommandBuilder cmd) {
        cmd.set(ZigFrame.HERO + ".Visible", true);
        cmd.set(ZigFrame.HERO_EMBLEM + ".Visible", true);
        cmd.set(ZigFrame.HERO_EMBLEM_ART + ".AssetPath", EMBLEM);
        cmd.set(ZigFrame.HERO_HEADLINE + ".TextSpans", text("title"));
        cmd.set(ZigFrame.HERO_TAGLINE + ".Visible", true);
        cmd.set(ZigFrame.HERO_TAGLINE + ".TextSpans", text("tagline"));
        cmd.set(ZigFrame.HERO_SUB + ".Visible", true);
        cmd.set(ZigFrame.HERO_SUB + ".TextSpans", text("sub"));
    }

    /** One pill per tone, its word in the tone's text colour on the scrim, its dot in the tone's fill. */
    private static void tones(@Nonnull UICommandBuilder cmd) {
        for (Tone tone : Tone.values()) {
            PillPainter.paint(cmd, "#GalPill" + pascal(tone), Pill.of(toneWord(tone), tone));
        }
    }

    /** The action buttons switched off, the two rich tabs chosen and not, the rich button and the two toggles. */
    private static void buttons(@Nonnull UICommandBuilder cmd) {
        for (String button : OFF_BUTTONS) {
            cmd.set(button + ".Disabled", true);
        }
        SettingsUiUtil.setTabActive(cmd, "#GalTabOn", true);
        SettingsUiUtil.setTabActive(cmd, "#GalTabOff", false);
        ZigRichButton.text(cmd, "#GalRich", text("button.rich"));
        SettingsUiUtil.setToggle(cmd, "#GalToggleOn", true, text("button.on"), text("button.off"));
        SettingsUiUtil.setToggle(cmd, "#GalToggleOff", false, text("button.on"), text("button.off"));
    }

    /**
     * The kit's list rows: a row in every tone (the Collect row selected, pinned and featured, the Active row tracked
     * with its progress, the Done row faint, each meta glyph once), a section shut, and a section capped at two with
     * its "Show more" row; then the tall rows and the compact rows.
     */
    private static void rows(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nullable PlayerRef viewer) {
        List<LedgerRow> every = new ArrayList<>();
        for (Tone tone : Tone.values()) {
            every.add(row("tones", tone));
        }
        LedgerSection open = new LedgerSection("tones", text("row.section"), every, true);
        LedgerSection shut = new LedgerSection("shut", text("row.shut"), List.of(row("shut", Tone.DONE),
                row("shut", Tone.LIVE)), false);
        LedgerSection capped = new LedgerSection("capped", text("row.capped"), List.of(row("capped", Tone.ACTIVE),
                row("capped", Tone.AVAILABLE), row("capped", Tone.WAITING), row("capped", Tone.BLOCKED)), true, 2);
        LedgerPainter.paint(cmd, events, "#GalRows", new LedgerModel(List.of(open, shut, capped), SELECTED_ROW),
                Set.of(), SELECTED_ROW, NO_ROW_BINDINGS, RowSize.STANDARD, viewer);

        LedgerSection tall = new LedgerSection("tall", text("row.section"), List.of(row("tall", Tone.ACTIVE),
                row("tall", Tone.DONE)), true);
        LedgerPainter.paint(cmd, events, "#GalRowsTall", new LedgerModel(List.of(tall), null), Set.of(), null,
                NO_ROW_BINDINGS, RowSize.TALL, viewer);

        LedgerSection compact = new LedgerSection("compact", text("row.section"), List.of(row("compact", Tone.COLLECT),
                row("compact", Tone.WAITING)), true);
        LedgerPainter.paint(cmd, events, "#GalRowsCompact", new LedgerModel(List.of(compact), null), Set.of(), null,
                NO_ROW_BINDINGS, RowSize.COMPACT, viewer);
    }

    /** One sample row in {@code tone}, each tone showing a different mix of the row's parts; its id is unique. */
    @Nonnull
    private static LedgerRow row(@Nonnull String section, @Nonnull Tone tone) {
        String id = section + "/" + tone.name();
        Message title = Msg.key(PREFIX + "row.title", toneWord(tone));
        Message meta = text("row.meta");
        return switch (tone) {
            case ACTIVE -> new LedgerRow(id, title, meta, QUEST, tone, toneWord(tone),
                    KitText.count(1, 4), new Progress(1, 4), Mark.TRACKED, false, MetaGlyph.CLOCK, RowAccent.TONE);
            case COLLECT -> new LedgerRow(id, title, meta, TROPHY, tone, toneWord(tone),
                    Msg.key(PREFIX + "row.points", 830), null, Mark.PINNED, false, MetaGlyph.STAR, RowAccent.FEATURED);
            case DONE -> new LedgerRow(id, title, meta, QUEST, tone, toneWord(tone), null, null, Mark.NONE,
                    true, MetaGlyph.NONE, RowAccent.TONE);
            case LIVE -> new LedgerRow(id, title, meta, STAR, tone, toneWord(tone), null, null, Mark.NONE,
                    false, MetaGlyph.SCROLL, RowAccent.TONE);
            case BLOCKED -> new LedgerRow(id, title, meta, LOCK, tone, toneWord(tone), null, null, Mark.NONE,
                    false, MetaGlyph.NONE, RowAccent.NONE);
            default -> new LedgerRow(id, title, meta, BOOK, tone, toneWord(tone), null, null, Mark.NONE,
                    false, MetaGlyph.NONE, RowAccent.TONE);
        };
    }

    /** Three view tabs (the first chosen), four segments (chosen, live, taken part in, switched off), two two-line. */
    private static void tabs(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.clear("#GalViews");
        ViewTabPainter.append(cmd, events, "#GalViews", text("tab.overview"), BOOK, true, null);
        ViewTabPainter.append(cmd, events, "#GalViews", text("tab.browse"), Picture.NONE, false, null);
        ViewTabPainter.append(cmd, events, "#GalViews", text("tab.stats"), Picture.NONE, false, null);

        cmd.clear("#GalSegments");
        SegmentPainter.append(cmd, events, "#GalSegments", text("segment.all"), true, false, false, null);
        SegmentPainter.append(cmd, events, "#GalSegments", text("segment.live"), false, false, true, null);
        SegmentPainter.append(cmd, events, "#GalSegments", text("segment.done"), false, true, false, null);
        String off = SegmentPainter.append(cmd, events, "#GalSegments", text("segment.off"), false, false, false,
                null);
        cmd.set(off + ".Disabled", true);

        cmd.clear("#GalSegmentsTwo");
        SegmentPainter.appendTwoLine(cmd, events, "#GalSegmentsTwo", text("segment.long"), true, false, false, null);
        SegmentPainter.appendTwoLine(cmd, events, "#GalSegmentsTwo", text("segment.long"), false, true, false, null);
    }

    /** The kit's painted tiles: stat tiles, collection tiles, keepsakes in each state, item slots, and wares. */
    private static void tiles(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        TilePainter.stats(cmd, "#GalStatTiles", List.of(
                new StatTile("found", TROPHY, Msg.num(42), text("tile.stat"), text("tile.statline"),
                        text("tile.statserver"), false),
                new StatTile("none", Picture.NONE, Msg.num(0), text("tile.statzero"), null, null, true)));
        TilePainter.collection(cmd, events, "#GalCollection", List.of(
                new CollectionTile("under", text("tile.collection"), KitText.count(3, 8), STAR, new Progress(3, 8),
                        false, Pill.of(toneWord(Tone.LIVE), Tone.LIVE), null, true),
                new CollectionTile("whole", text("tile.collectiondone"), KitText.count(8, 8), TROPHY,
                        new Progress(8, 8), true, null, ZigTokens.ACCENT, false)),
                tile -> null);
        TilePainter.keepsakes(cmd, "#GalKeepsakes", List.of(
                new KeepsakeTile("earned", text("tile.keepsakeearned"), text("tile.keepsakeearnedline"), TROPHY,
                        KeepsakeState.EARNED, true, text("tile.keepsaketip")),
                new KeepsakeTile("toearn", text("tile.keepsaketoearn"), text("tile.keepsaketoearnline"), TROPHY,
                        KeepsakeState.TO_EARN, false, null),
                new KeepsakeTile("missed", text("tile.keepsakemissed"), text("tile.keepsakemissedline"), TROPHY,
                        KeepsakeState.MISSED, false, null)));
        TilePainter.itemSlots(cmd, "#GalItemSlots", List.of(
                new ItemSlotTile("owned", BAG, true, false, null, text("tile.slotowned")),
                new ItemSlotTile("mystery", Picture.NONE, false, true, text("tile.slotmystery"), null)));

        cmd.clear("#GalWareSections");
        String grid = TilePainter.section(cmd, "#GalWareSections", 0, text("tile.wares"), text("tile.waresline"));
        TilePainter.wares(cmd, events, grid, List.of(
                new WareTile("ware", text("tile.ware"), BAG, STAR, Msg.num(12), false,
                        Pill.of(text("tile.warebadge"), Tone.AVAILABLE), false, false, null, false),
                new WareTile("chosen", text("tile.warechosen"), BOOK, STAR, Msg.num(250), true, null, false, false,
                        null, true),
                new WareTile("locked", text("tile.warelocked"), BOARD, STAR, Msg.num(900), false, null, true, true,
                        text("tile.warelockedtip"), false)),
                tile -> null);
    }

    /** The card's tile: a tile is filled by its ids, as its document says. */
    private static void cards(@Nonnull UICommandBuilder cmd) {
        cmd.set("#GalCardTile #TileTitle.TextSpans", text("card.tile"));
    }

    /** The type section's three number styles, then a header's figures: plain, gold for points, a two-line caption. */
    private static void figures(@Nonnull UICommandBuilder cmd) {
        cmd.set("#GalFigure.TextSpans", Msg.num(1250));
        cmd.set("#GalFigureAccent.TextSpans", Msg.num(830));
        cmd.set("#GalCount.TextSpans", KitText.count(12, 50));
        StatPainter.paint(cmd, "#GalStatPlain", new Stat(Msg.num(1250), text("figure.plain"), Tone.NEUTRAL));
        StatPainter.paint(cmd, "#GalStatGold", new Stat(Msg.num(830), text("figure.gold"), Tone.COLLECT));
        StatPainter.paint(cmd, "#GalStatTwo", new Stat(Msg.num(9), text("figure.twoline"), Tone.DONE));
    }

    /**
     * The reading page: its picture, title, meta and faint line, two badges, the header toggle, the progress block, the
     * lead, a block of lines in every tick under its section header, and the action bar with Collect, a Secondary with
     * a tooltip and a switched-off Danger, beside its hint.
     */
    private static void reading(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nullable PlayerRef viewer) {
        List<DetailLine> lines = new ArrayList<>();
        for (Tick tick : Tick.values()) {
            lines.add(new DetailLine(QUEST, text("detail.line." + tick.name().toLowerCase(Locale.ROOT)),
                    tick == Tick.NONE ? null : KitText.count(tick == Tick.DONE ? 2 : 0, 2),
                    tick == Tick.CURRENT ? Pill.of(toneWord(Tone.ACTIVE), Tone.ACTIVE) : null, tick, null,
                    tick == Tick.CURRENT));
        }
        DetailBlock block = new DetailBlock("steps", text("detail.block"), text("detail.blockline"), lines);
        List<DetailAction> actions = List.of(
                new DetailAction(ActionSlot.PRIMARY, toneWord(Tone.COLLECT), ActionLook.COLLECT, "collect", null,
                        true, null),
                new DetailAction(ActionSlot.SECONDARY, text("detail.track"), ActionLook.NORMAL, "track", null, true,
                        text("detail.tracktip")),
                new DetailAction(ActionSlot.DANGER, text("detail.abandon"), ActionLook.DANGER, "abandon", null, false,
                        null));
        DetailView view = new DetailView(QUEST, text("detail.title"), text("detail.meta"), text("detail.submeta"),
                List.of(Pill.of(text("detail.badge"), Tone.DONE), Pill.of(toneWord(Tone.LIVE), Tone.LIVE)),
                new DetailToggle(text("detail.toggle"), false, "pin", text("detail.toggletip")), new Progress(2, 3),
                null, text("detail.lead"), List.of(block), actions, text("detail.hint"));
        DetailPainter.paint(cmd, events, "#GalDetail", view, NO_DETAIL_BINDINGS, viewer);
    }

    /** An empty state with its picture, title, line and action. */
    private static void empty(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        DetailAction clear = new DetailAction(ActionSlot.PRIMARY, KitText.clearFilters(), ActionLook.NORMAL, "clear",
                null, true, null);
        EmptyStatePainter.paint(cmd, events, "#GalEmpty", new EmptyState(BOARD, text("empty.title"),
                text("empty.line"), clear), null);
    }

    /** A tone's word, as the tones section spells it. */
    @Nonnull
    private static Message toneWord(@Nonnull Tone tone) {
        return text("tone." + tone.name().toLowerCase(Locale.ROOT));
    }

    /** {@code ACTIVE} as {@code Active}, the document's id suffix. */
    @Nonnull
    private static String pascal(@Nonnull Tone tone) {
        String name = tone.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @Nonnull
    private static Message text(@Nonnull String key) {
        return Msg.key(PREFIX + key);
    }
}
