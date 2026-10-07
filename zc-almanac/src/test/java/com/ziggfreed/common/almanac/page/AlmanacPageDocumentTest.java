package com.ziggfreed.common.almanac.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBinding;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.ziggfreed.common.almanac.page.AlmanacDestinations.Almanac;
import com.ziggfreed.common.almanac.view.AlmanacView.Banner;
import com.ziggfreed.common.almanac.view.AlmanacView.Feat;
import com.ziggfreed.common.almanac.view.AlmanacView.Hero;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroComposition;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroGlow;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroGradient;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroItem;
import com.ziggfreed.common.almanac.view.AlmanacView.MonthMarks;
import com.ziggfreed.common.almanac.view.AlmanacView.Record;
import com.ziggfreed.common.almanac.view.AlmanacView.Scope;
import com.ziggfreed.common.almanac.view.AlmanacView.Season;
import com.ziggfreed.common.almanac.view.AlmanacView.SeasonAchievements;
import com.ziggfreed.common.almanac.view.AlmanacView.SeasonLink;
import com.ziggfreed.common.almanac.view.AlmanacView.SeasonPage;
import com.ziggfreed.common.almanac.view.AlmanacView.Tally;
import com.ziggfreed.common.almanac.view.AlmanacView.Timing;
import com.ziggfreed.common.almanac.view.AlmanacView.YearChip;
import com.ziggfreed.common.almanac.view.AlmanacView.YearKeepsake;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.ui.kit.KeepsakeState;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.menu.MenuFrame;

/**
 * The page document against the Java that paints it. The page cannot run in a unit JVM (its build reaches the
 * player's store), but what it paints can: {@link AlmanacPage#paint} is driven here with full plans (under the
 * engine's log manager, in {@code engineItemTest}) and every selector it sends is held to the documents it lands
 * in, id by id through the templates it enters, since a command against an id a document lacks disconnects the
 * player. Beside the ids: every picture slot is an {@code AssetImage} (the season list's row
 * included), every text size is a {@code Common/ZigType.ui} step at the floor or above, every colour is a token,
 * and {@link AlmanacLayout} is the document's geometry.
 */
class AlmanacPageDocumentTest {

    private static final String KIT = "Common/ZigKit.ui";
    private static final String FRAMES = "Common/ZigFrames.ui";

    private static final Pattern FONT_SIZE = Pattern.compile("(?:MinShrinkTextToFitFontSize|FontSize)\\s*:\\s*([^,;)]+)");
    private static final Set<String> STEPS = Set.of("Caption", "Section", "Body", "Emphasis", "Heading", "Subtitle",
            "Title", "Display", "DisplayLarge", "Hero");

    /** A colour written as a literal ({@code #rgb}, {@code #rrggbb}, {@code #rrggbbaa}), not as a token. */
    private static final Pattern COLOUR_LITERAL = Pattern.compile("#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3})\\b");

    // ---- every id the page addresses exists ----

    /**
     * Every selector the page sends resolves id by id where it lands: its first id is declared in the page document
     * or in the frame it sits in, and each id after it is declared inside the element the path has reached so far,
     * that element's template included ({@code #Hero #HeroItems} inside {@code @ZigHeroPlate}). An id that exists
     * only somewhere else in the kit does not count.
     *
     * <p>Tagged {@code engine-items}: a {@link UICommandBuilder}'s static init reaches the engine's item codec.
     */
    @Test
    @Tag("engine-items")
    void everyIdThePageAddressesIsDeclaredWhereItLands() throws IOException {
        UiTree tree = new UiTree();
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (AlmanacPagePlan plan : List.of(fullPlan(composedHero()), fullPlan(artHero()), fullPlan(pictureHero()),
                emptyPlan())) {
            UICommandBuilder cmd = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            AlmanacPage.paint(cmd, events, plan, null);
            List<String> selectors = new ArrayList<>();
            for (CustomUICommand command : cmd.getCommands()) {
                if (command.selector != null) {
                    selectors.add(command.selector);
                }
            }
            for (CustomUIEventBinding binding : events.getEvents()) {
                if (binding.selector != null) {
                    selectors.add(binding.selector);
                }
            }
            for (String selector : selectors) {
                String wrong = tree.unresolved(AlmanacPage.PAGE_TEMPLATE, FRAMES, path(selector));
                if (wrong != null) {
                    missing.add(selector + " (" + wrong + ")");
                }
                checked++;
            }
        }
        assertTrue(checked > 0, "the plans paint something to check");
        assertTrue(missing.isEmpty(), "the page sends commands to ids not declared where they land: " + missing);
    }

    /** The resolver itself: a kit id in the wrong template, or a template id as a first id, does not resolve. */
    @Test
    void anIdDeclaredOnlyElsewhereInTheKitDoesNotResolve() throws IOException {
        UiTree tree = new UiTree();
        String page = AlmanacPage.PAGE_TEMPLATE;
        assertEquals(null, tree.unresolved(page, FRAMES, path("#Hero #HeroItems")), "the hero plate's own child");
        assertEquals(null, tree.unresolved(page, FRAMES, path("#Hero #HeroChip #Label.TextSpans")),
                "a pill inside the plate, through two templates");
        assertEquals(null, tree.unresolved(page, FRAMES, path("#Month3 #Marks")), "a page template's child");
        assertEquals(null, tree.unresolved(page, FRAMES, path("#Content #LeftColumn")),
                "the page's own columns, added to the frame's #Content");
        assertEquals(null, tree.unresolved(page, FRAMES, path("#MenuList")), "the frame's own ids");
        assertEquals(null, tree.unresolved(page, FRAMES, path("#SeasonList[0] #Anything")),
                "what follows an index is an appended template's");

        assertEquals("#DActions is not declared inside #Hero", tree.unresolved(page, FRAMES,
                path("#Hero #DActions")), "a detail page's id is not the hero plate's");
        assertEquals("#HeroItems is not declared inside #BannerPic", tree.unresolved(page, FRAMES,
                path("#BannerPic #HeroItems")));
        assertEquals("#HeroTitle is not declared in the page or its frame", tree.unresolved(page, FRAMES,
                path("#HeroTitle.TextSpans")), "a template's child is reached through its instance");
        assertEquals("#Marks is not declared in the page or its frame", tree.unresolved(page, FRAMES,
                path("#Marks")), "twelve months declare one: a first id is the page's own");
    }

    @Test
    void theHerosPicturesHangUnderItsFadeAndEachIsAnAssetImage() throws IOException {
        String plate = template(document(KIT), "@ZigHeroPlate");
        int glow = plate.indexOf("#HeroGlow");
        int items = plate.indexOf("#HeroItems");
        int fade = plate.indexOf("#HeroFade");
        assertTrue(glow > 0 && items > glow && fade > items,
                "@ZigHeroPlate holds #HeroItems between its glow and its fade, so the fade draws over the pictures");
        assertTrue(document(AlmanacPage.PAGE_TEMPLATE).contains("$ZW.@ZigHeroPlate #Hero"));
        assertTrue(AlmanacPage.HERO_PICTURE.contains("AssetImage " + IconRenderer.TEXTURE_ICON_ID),
                "a composed hero's picture is the one plain picture slot: no tooltip, no rarity square");
        assertFalse(AlmanacPage.HERO_PICTURE.contains("ItemGrid") || AlmanacPage.HERO_PICTURE.contains("ItemIcon"));
    }

    // ---- pictures ----

    @Test
    void eachListedSeasonsRowHasThePictureSlotThePagePaintsItsPictureInto() throws IOException {
        assertEquals("Pages/ZigLedgerRow.ui", RowSize.STANDARD.template(), "the season list paints the kit's row");
        String row = document(RowSize.STANDARD.template());
        assertTrue(Pattern.compile("\\$ZW\\.@ZigPicture\\s+#Pic\\s*\\{").matcher(row).find(),
                "the row declares the kit's picture slot #Pic, which the painter draws each season's picture into");
        assertFalse(row.contains("ItemGrid") || row.contains("ItemIcon"),
                "a row's picture shows no item tooltip and no rarity square");
        String picture = template(document(KIT), "@ZigPicture");
        assertTrue(picture.contains("AssetImage " + IconRenderer.TEXTURE_ICON_ID),
                "the slot is the AssetImage IconRenderer.applyPlainIcon paints an item's own icon into");
    }

    @Test
    void everyPictureSlotIsAnAssetImage() throws IOException {
        for (String doc : List.of(AlmanacPage.PAGE_TEMPLATE, AlmanacPage.MARK_TEMPLATE)) {
            String ui = document(doc);
            assertFalse(ui.contains("ItemGrid"), doc + " draws no item grid: a picture here only displays");
            assertFalse(ui.contains("ItemIcon"), doc + " declares no ItemIcon (it drew blank in game)");
        }
        String page = document(AlmanacPage.PAGE_TEMPLATE);
        for (String slot : List.of("#BannerPic")) {
            assertTrue(Pattern.compile("\\$ZW\\.@ZigPicture\\s+" + slot + "\\s*\\{").matcher(page).find(),
                    slot + " is the kit's picture slot");
        }
    }

    // ---- type and colour ----

    @Test
    void everyTextSizeIsATypeStepAtTheFloorOrAbove() throws IOException {
        for (String doc : List.of(AlmanacPage.PAGE_TEMPLATE, AlmanacPage.MARK_TEMPLATE)) {
            Matcher m = FONT_SIZE.matcher(document(doc));
            while (m.find()) {
                String value = m.group(1).trim();
                assertTrue(value.startsWith("$ZT.@ZigFont") && STEPS.contains(value.substring("$ZT.@ZigFont".length())),
                        doc + ": a size is a ZigType step of 13 or more, never a number: " + value);
            }
        }
    }

    @Test
    void everyColourIsAToken() throws IOException {
        for (String doc : List.of(AlmanacPage.PAGE_TEMPLATE, AlmanacPage.MARK_TEMPLATE)) {
            Matcher m = COLOUR_LITERAL.matcher(document(doc));
            String found = m.find() ? m.group() : null;
            assertEquals(null, found, doc + " spells a colour instead of naming a Common/ZigTokens.ui token");
        }
        for (String source : List.of("AlmanacPage.java", "AlmanacPagePlan.java")) {
            String java = Files.readString(Path.of("src", "main", "java", "com", "ziggfreed", "common", "almanac",
                    "page", source), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"#[0-9a-fA-F]{6}").matcher(java);
            assertFalse(m.find(), source + " pushes a hex literal; a colour is a ZigTokens constant or a clamped "
                    + "data accent");
        }
    }

    // ---- the layout ----

    @Test
    void theLayoutAddsUpAndTheDocumentSpellsIt() throws IOException {
        assertEquals(MenuFrame.BODY_WIDTH, AlmanacLayout.LEFT_WIDTH + AlmanacLayout.GAP + AlmanacLayout.RIGHT_WIDTH,
                "the two columns and the gap fill the frame's body");
        assertEquals(962, AlmanacLayout.RIGHT_WIDTH);
        assertEquals(AlmanacLayout.RIGHT_WIDTH, AlmanacLayout.HERO_WIDTH, "the hero spans the right column");
        assertEquals(276, AlmanacLayout.LEFT_INNER);
        assertEquals(906, AlmanacLayout.CONTENT_WIDTH, "the scrolling body's content");
        assertTrue(AlmanacLayout.STAT_TILES_PER_ROW * AlmanacLayout.STAT_TILE_STEP <= AlmanacLayout.CONTENT_WIDTH,
                "four stat tiles a row");
        assertTrue(AlmanacLayout.KEEPSAKES_PER_ROW * AlmanacLayout.KEEPSAKE_STEP <= AlmanacLayout.CONTENT_WIDTH,
                "seven keepsakes a row");
        assertTrue(AlmanacLayout.LINK_SLOTS * AlmanacLayout.LINK_STEP <= AlmanacLayout.CONTENT_WIDTH,
                "the links fit one line");
        assertTrue(AlmanacLayout.MONTH_MARKS_MAX * (AlmanacLayout.MONTH_MARK + 4) + 48 + 16 <= AlmanacLayout.LEFT_INNER,
                "a month row holds its label and its marks");
        int left = AlmanacLayout.BODY_HEIGHT - 2 * AlmanacLayout.LEFT_PADDING
                - (AlmanacLayout.TITLE_HEIGHT + AlmanacLayout.TITLE_GAP)
                - (AlmanacLayout.LEAD_HEIGHT + AlmanacLayout.LEAD_GAP)
                - (AlmanacLayout.BANNER_HEIGHT + AlmanacLayout.BANNER_GAP)
                - (AlmanacLayout.GLANCE_GAP + AlmanacLayout.GLANCE_HEIGHT)
                - (AlmanacLayout.RECORD_GAP + AlmanacLayout.RECORD_HEIGHT);
        assertTrue(left >= AlmanacLayout.LIST_MIN_HEIGHT, "the season list keeps room beside every fixed block: " + left);

        String ui = document(AlmanacPage.PAGE_TEMPLATE);
        assertEquals(AlmanacLayout.LEFT_WIDTH, anchor(ui, "#LeftColumn", "Width"));
        assertEquals(AlmanacLayout.LEFT_PADDING, leaf(property(block(ui, "#LeftColumn"), "Padding"), "Full"));
        assertEquals(AlmanacLayout.RIGHT_WIDTH, anchor(ui, "#RightColumn", "Width"));
        assertEquals(AlmanacLayout.GAP, anchor(ui, "#RightColumn", "Left"));
        assertEquals(AlmanacLayout.TITLE_HEIGHT, anchor(ui, "#AlmanacTitle", "Height"));
        assertEquals(AlmanacLayout.LEAD_HEIGHT, anchor(ui, "#AlmanacLead", "Height"));
        assertEquals(AlmanacLayout.BANNER_HEIGHT, anchor(ui, "#BannerCard", "Height"));
        assertEquals(AlmanacLayout.GLANCE_HEIGHT, anchor(ui, "#Glance", "Height"));
        assertEquals(AlmanacLayout.GLANCE_TITLE_HEIGHT, anchor(ui, "#GlanceTitle", "Height"));
        assertEquals(AlmanacLayout.RECORD_HEIGHT, anchor(ui, "#RecordCard", "Height"));
        String body = property(block(ui, "#SeasonBody"), "Padding");
        assertEquals(AlmanacLayout.BODY_PAD_LEFT, leaf(body, "Left"));
        assertEquals(AlmanacLayout.BODY_PAD_RIGHT + AlmanacLayout.SCROLL_GUTTER, leaf(body, "Right"));
        assertEquals(AlmanacLayout.BODY_PAD_TOP, leaf(body, "Top"));
        String month = template(ui, "@AlmanacMonth");
        assertEquals(AlmanacLayout.MONTH_HEIGHT, leaf(property(month, "Anchor"), "Height"));
        String link = template(ui, "@AlmanacLink");
        assertEquals(AlmanacLayout.LINK_WIDTH, leaf(property(link, "Anchor"), "Width"));
        String mark = block(document(AlmanacPage.MARK_TEMPLATE), "#ZigAlmanacMonthMark");
        assertEquals(AlmanacLayout.MONTH_MARK, leaf(property(mark, "Anchor"), "Width"));
        assertEquals(AlmanacLayout.MONTH_MARK, leaf(property(mark, "Anchor"), "Height"));
    }

    @Test
    void theYearAtAGlanceIsTwelveAuthoredMonthRowsEachAButtonWithItsMarks() throws IOException {
        String ui = document(AlmanacPage.PAGE_TEMPLATE);
        String month = template(ui, "@AlmanacMonth");
        assertTrue(month.trim().startsWith("Button"), "a month row is a button: a click selects its first season");
        assertTrue(month.contains("Label #Label"), "its label is the button's own #Label");
        assertTrue(month.contains("Group #Marks"), "its marks host");
        for (int m = 1; m <= AlmanacLayout.MONTHS; m++) {
            assertTrue(Pattern.compile("@AlmanacMonth\\s+#Month" + m + "\\s*\\{").matcher(ui).find(), "#Month" + m);
        }
        assertFalse(ui.contains("#Month13"));
        for (int i = 1; i <= AlmanacLayout.LINK_SLOTS; i++) {
            assertTrue(Pattern.compile("@AlmanacLink\\s+#Link" + i + "\\s*\\{").matcher(ui).find(), "#Link" + i);
        }
    }

    @Test
    void everyWrappingGridIsAVanillaWrap() throws IOException {
        String ui = document(AlmanacPage.PAGE_TEMPLATE);
        for (String grid : List.of("#YearChips", "#StatGrid", "#KeepsakeShelf", "#FeatList")) {
            assertEquals("LeftWrap", property(block(ui, grid), "LayoutMode"),
                    grid + " wraps left to right (vanilla TriggerVolumeBrowseVolumeRow.ui)");
        }
    }

    // ---- the handler ----

    @Test
    void theAlmanacSitsInTheSharedMenuAndAnswersTheRailFirst() throws IOException {
        String ui = document(AlmanacPage.PAGE_TEMPLATE);
        assertTrue(ui.contains("$F.@ZigMenuFrame"), "the Almanac carries the shared rail, so it is no dead end");
        assertFalse(ui.contains("@ZigDecoratedFrame"));

        String page = source();
        assertTrue(page.contains("ZigMenu.paint("), "the page paints the rail");
        int handler = page.indexOf("public void handleDataEvent(");
        int rail = page.indexOf("rail.handle(", handler);
        int action = page.indexOf("data.action", handler);
        assertTrue(handler > 0 && rail > handler && rail < action,
                "a rail click carries no Action, so the rail hears every event first");
    }

    @Test
    void everyExitOfTheHandlerAnswersTheNullPlayerIncluded() throws IOException {
        String page = source();
        int handler = page.indexOf("public void handleDataEvent(");
        int none = page.indexOf("player == null", handler);
        assertTrue(none > handler, "the handler checks for a missing player");
        String branch = page.substring(none, page.indexOf('}', none));
        assertTrue(branch.contains("answer()"), "a missing player still gets an update, so the client never locks: "
                + branch);
    }

    // ---- fixtures ----

    private static AlmanacPagePlan fullPlan(Hero hero) {
        MonthDay oct1 = MonthDay.of(10, 1);
        MonthDay nov3 = MonthDay.of(11, 3);
        Season live = new Season("hallows_eve", "almanac.test.title", "almanac.test.flavor", "Test_Icon", true, 2026);
        Season later = new Season("harvest_moon", null, null, null, false, 0);
        Timing timing = new Timing(true, 27, false, null, false, oct1, nov3, LocalDate.of(2027, 10, 1), false);
        Timing between = new Timing(false, null, false, 23, false, oct1, nov3, LocalDate.of(2026, 10, 29), false);
        List<YearChip> years = List.of(new YearChip(2025, false, true, true), new YearChip(2026, true, true, false));
        SeasonPage page = new SeasonPage(live, timing, years, new Scope(2026), true,
                List.of(new Tally("bombs_thrown", "almanac.test.bombs", "Test_Bomb", 5L, 12L, 40L)),
                List.of(new YearKeepsake(2025, KeepsakeState.EARNED, "Keepsake_2025", "Test_Keepsake"),
                        new YearKeepsake(2026, KeepsakeState.TO_EARN, "Keepsake_2026", "Test_Keepsake")),
                new SeasonAchievements(4, 9, List.of(new Feat("Feat_One", "Test_Icon"))), hero, "#E8752A",
                List.of(new SeasonLink("almanac.test.link", Almanac.of("harvest_moon"))));
        List<MonthMarks> months = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            months.add(new MonthMarks(m, m == 10 || m == 11 ? List.of("hallows_eve", "harvest_moon") : List.of()));
        }
        return AlmanacPagePlan.of(List.of(live, later), Map.of("hallows_eve", timing, "harvest_moon", between), page,
                new Record(2L, 1L), months, Map.of("hallows_eve", "#E8752A"), 10,
                new Banner("Seasons_Of_Orbis", false, 2, 4), "Deco_Scroll");
    }

    private static AlmanacPagePlan emptyPlan() {
        List<MonthMarks> months = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            months.add(new MonthMarks(m, List.of()));
        }
        return AlmanacPagePlan.of(List.of(), Map.of(), null, new Record(0L, 0L), months, Map.of(), 10, null, null);
    }

    private static Hero composedHero() {
        return new Hero(null, new HeroComposition("#121a2e", "UI/Custom/Almanac/Sky.png",
                new HeroGradient("#0a0f1e", "#2a1a2c"), new HeroGlow("#a0501a", 514, -78, 400),
                List.of(new HeroItem("Lantern", "Icons/ItemsGenerated/Lantern.png", 650, 58, 128),
                        new HeroItem("Bomb", "Icons/ItemsGenerated/Bomb.png", 562, 14, 64))), "Icons/ItemsGenerated/X.png");
    }

    private static Hero artHero() {
        return new Hero("UI/Custom/Almanac/Hallows_Eve.png", null, "Icons/ItemsGenerated/X.png");
    }

    private static Hero pictureHero() {
        return new Hero(null, null, "Icons/ItemsGenerated/X.png");
    }

    /**
     * The ids a selector names in documents the page declares, in order: everything before the first index (what
     * follows an index is an appended template's, which the kit's own tests pin), each property suffix dropped.
     */
    @Nonnull
    private static List<String> path(@Nonnull String selector) {
        int index = selector.indexOf('[');
        String head = index < 0 ? selector : selector.substring(0, index);
        List<String> ids = new ArrayList<>();
        for (String token : head.trim().split("\\s+")) {
            if (token.startsWith("#")) {
                ids.add(token.substring(1).split("\\.")[0]);
            }
        }
        return ids;
    }

    /**
     * The shipped documents as element trees, enough to follow a selector id by id: each element's type, id and
     * children, each document's imports and named templates. An element whose type names a template ({@code @Name}
     * from its own document, {@code $Alias.@Name} from an imported one) holds that template's children beside its
     * own, so a path that enters an instance finds what the template declares; a child written with no type
     * ({@code #Content { ... }}) is the instance adding to the template's child of that id. A document the classpath
     * does not ship (vanilla {@code Common.ui}) has no templates to enter.
     */
    private static final class UiTree {

        private static final Pattern IMPORT = Pattern.compile("\\$(\\w+)\\s*=\\s*\"([^\"]+)\"\\s*;");
        private static final Pattern TEMPLATE = Pattern.compile("@(\\w+)\\s*=\\s*([$\\w.@]+)");
        private static final Pattern STRING = Pattern.compile("\"[^\"\\n]*\"");

        /** One element, or a template's body: the document it is written in, its type, its id, its own children. */
        private record Node(@Nonnull String doc, @Nullable String type, @Nullable String id,
                @Nonnull List<Node> children) {
        }

        private record Doc(@Nonnull Map<String, String> imports, @Nonnull Map<String, Node> templates,
                @Nonnull List<Node> roots) {
        }

        private final Map<String, Doc> docs = new HashMap<>();
        private final Set<String> unshipped = new HashSet<>();

        /**
         * Why {@code ids} does not resolve on {@code page}, or null when it does: the first id among the page's own
         * elements and the frame's ({@code frames}' templates entered, no other), each next id inside the elements
         * the path has reached, every template entered.
         */
        @Nullable
        String unresolved(@Nonnull String page, @Nonnull String frames, @Nonnull List<String> ids) throws IOException {
            if (ids.isEmpty()) {
                return null;
            }
            Doc doc = doc(page);
            assertNotNull(doc, "the classpath ships " + page);
            List<Node> reached = under(List.of(new Node(page, null, null, doc.roots())), ids.get(0), frames);
            if (reached.isEmpty()) {
                return "#" + ids.get(0) + " is not declared in the page or its frame";
            }
            for (int i = 1; i < ids.size(); i++) {
                List<Node> next = under(reached, ids.get(i), null);
                if (next.isEmpty()) {
                    return "#" + ids.get(i) + " is not declared inside #" + ids.get(i - 1);
                }
                reached = next;
            }
            return null;
        }

        /** Every element with {@code id} below {@code from}, entering the templates of {@code onlyDoc} (all if null). */
        @Nonnull
        private List<Node> under(@Nonnull List<Node> from, @Nonnull String id, @Nullable String onlyDoc)
                throws IOException {
            List<Node> found = new ArrayList<>();
            Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Deque<Node> todo = new ArrayDeque<>();
            for (Node node : from) {
                todo.addAll(children(node, onlyDoc));
            }
            while (!todo.isEmpty()) {
                Node node = todo.pop();
                if (!seen.add(node)) {
                    continue;
                }
                if (id.equals(node.id())) {
                    found.add(node);
                }
                todo.addAll(children(node, onlyDoc));
            }
            return found;
        }

        /** An element's own children, then its template's (and that template's own template's). */
        @Nonnull
        private List<Node> children(@Nonnull Node node, @Nullable String onlyDoc) throws IOException {
            List<Node> out = new ArrayList<>(node.children());
            Node template = node;
            for (int depth = 0; depth < 8; depth++) {
                template = template(template);
                if (template == null || (onlyDoc != null && !onlyDoc.equals(template.doc()))) {
                    break;
                }
                out.addAll(template.children());
            }
            return out;
        }

        /** The template {@code node}'s type names, or null for a plain element or one the classpath lacks. */
        @Nullable
        private Node template(@Nonnull Node node) throws IOException {
            String type = node.type();
            int at = type == null ? -1 : type.indexOf('@');
            if (at < 0) {
                return null;
            }
            String where = node.doc();
            if (at > 0) {
                Doc from = doc(node.doc());
                where = from == null || !type.startsWith("$") ? null : from.imports().get(type.substring(1, at - 1));
            }
            Doc doc = where == null ? null : doc(where);
            return doc == null ? null : doc.templates().get(type.substring(at + 1));
        }

        /** {@code path} parsed, or null when the classpath does not ship it. */
        @Nullable
        private Doc doc(@Nonnull String path) throws IOException {
            if (unshipped.contains(path)) {
                return null;
            }
            Doc cached = docs.get(path);
            if (cached != null) {
                return cached;
            }
            String text;
            try (InputStream in = AlmanacPageDocumentTest.class.getResourceAsStream("/Common/UI/Custom/" + path)) {
                if (in == null) {
                    unshipped.add(path);
                    return null;
                }
                text = withoutComments(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            Map<String, String> imports = new HashMap<>();
            Matcher imported = IMPORT.matcher(text);
            while (imported.find()) {
                imports.put(imported.group(1), resolve(path, imported.group(2)));
            }
            Doc doc = parse(path, STRING.matcher(text).replaceAll("\"\""), imports);
            docs.put(path, doc);
            return doc;
        }

        /**
         * The document's element tree: at each opening brace, the text since the last {@code ;}, <code>{</code> or
         * <code>}</code> is the header, {@code @Name = Type} a template, {@code Type #Id}, {@code #Id} or
         * {@code Type} an element.
         */
        @Nonnull
        private static Doc parse(@Nonnull String path, @Nonnull String text, @Nonnull Map<String, String> imports) {
            List<Node> roots = new ArrayList<>();
            Map<String, Node> templates = new HashMap<>();
            Deque<List<Node>> open = new ArrayDeque<>();
            open.push(roots);
            int header = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == ';') {
                    header = i + 1;
                } else if (c == '{') {
                    String head = text.substring(header, i).trim();
                    List<Node> children = new ArrayList<>();
                    Matcher template = TEMPLATE.matcher(head);
                    if (template.matches()) {
                        templates.put(template.group(1), new Node(path, template.group(2), null, children));
                    } else {
                        String[] tokens = head.isEmpty() ? new String[0] : head.split("\\s+");
                        String last = tokens.length == 0 ? null : tokens[tokens.length - 1];
                        boolean named = last != null && last.startsWith("#");
                        String type = named ? (tokens.length > 1 ? tokens[0] : null) : last;
                        open.peek().add(new Node(path, type, named ? last.substring(1) : null, children));
                    }
                    open.push(children);
                    header = i + 1;
                } else if (c == '}') {
                    assertFalse(open.size() <= 1, path + " closes a block it never opened at " + i);
                    open.pop();
                    header = i + 1;
                }
            }
            assertEquals(1, open.size(), path + " leaves a block open");
            return new Doc(imports, templates, roots);
        }

        /** An import's path, relative to the document importing it, as the classpath names it. */
        @Nonnull
        private static String resolve(@Nonnull String from, @Nonnull String relative) {
            Deque<String> parts = new ArrayDeque<>(List.of(from.split("/")));
            parts.removeLast();
            for (String part : relative.split("/")) {
                if (part.equals("..")) {
                    if (!parts.isEmpty()) {
                        parts.removeLast();
                    }
                } else if (!part.isEmpty() && !part.equals(".")) {
                    parts.addLast(part);
                }
            }
            return String.join("/", parts);
        }
    }

    @Nonnull
    private static String source() throws IOException {
        return Files.readString(Path.of("src", "main", "java", "com", "ziggfreed", "common", "almanac", "page",
                "AlmanacPage.java"), StandardCharsets.UTF_8);
    }

    /** The shipped document with every {@code //} comment removed, so a sentence never satisfies a check. */
    @Nonnull
    private static String document(@Nonnull String template) throws IOException {
        try (InputStream in = AlmanacPageDocumentTest.class.getResourceAsStream("/Common/UI/Custom/" + template)) {
            assertNotNull(in, "the classpath ships " + template);
            return withoutComments(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** {@code ui} with every {@code //} comment removed. */
    @Nonnull
    private static String withoutComments(@Nonnull String ui) {
        StringBuilder out = new StringBuilder();
        for (String line : ui.split("\n")) {
            int at = line.indexOf("//");
            out.append(at < 0 ? line : line.substring(0, at)).append('\n');
        }
        return out.toString();
    }

    /** The block the element {@code id} declares, from its type to the brace that closes it; fails for none. */
    @Nonnull
    private static String block(@Nonnull String ui, @Nonnull String id) {
        Matcher declaration = Pattern.compile("[\\w@$.]+\\s+" + Pattern.quote(id) + "\\s*\\{").matcher(ui);
        assertTrue(declaration.find(), "the document declares " + id);
        return braces(ui, declaration.start(), declaration.end() - 1);
    }

    /** A named template's body, {@code @Name = Type { ... }}. */
    @Nonnull
    private static String template(@Nonnull String ui, @Nonnull String name) {
        Matcher declaration = Pattern.compile(Pattern.quote(name) + "\\s*=\\s*(\\w+)\\s*\\{").matcher(ui);
        assertTrue(declaration.find(), "the document defines " + name);
        return braces(ui, declaration.start(1), declaration.end() - 1);
    }

    @Nonnull
    private static String braces(@Nonnull String ui, int from, int open) {
        int depth = 0;
        for (int i = open; i < ui.length(); i++) {
            char c = ui.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return ui.substring(from, i + 1);
            }
        }
        throw new AssertionError("an unclosed block at " + from);
    }

    /** A property's value written on the block's own element (not in a nested block), or null. */
    @Nullable
    private static String property(@Nonnull String block, @Nonnull String name) {
        StringBuilder own = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < block.length(); i++) {
            char c = block.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (depth == 1) {
                own.append(c);
            }
        }
        Matcher m = Pattern.compile("(?<![\\w@])" + Pattern.quote(name) + "\\s*:\\s*([^;]+);").matcher(own);
        return m.find() ? m.group(1).trim() : null;
    }

    /** An integer leaf of a {@code (Key: value, ...)} object. */
    private static int leaf(@Nullable String object, @Nonnull String key) {
        assertNotNull(object, "no object holds " + key);
        Matcher m = Pattern.compile("\\b" + Pattern.quote(key) + "\\s*:\\s*(\\d+)").matcher(object);
        assertTrue(m.find(), object + " says " + key);
        return Integer.parseInt(m.group(1));
    }

    private static int anchor(@Nonnull String ui, @Nonnull String id, @Nonnull String key) {
        return leaf(property(block(ui, id), "Anchor"), key);
    }
}
