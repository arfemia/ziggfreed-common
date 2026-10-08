package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.style.DialogueOptionGlyphs;
import com.ziggfreed.common.dialogue.style.DialogueOptionStyle;
import com.ziggfreed.common.dialogue.style.DialogueOptionTheme;
import com.ziggfreed.common.dialogue.type.DialogueAction;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationKind;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The picture leading an answer row says what the answer DOES: a jump talks on, a close says goodbye,
 * an accept or a hand-in shows its check, and a line that opens a screen shows the kind of screen its
 * destination declared. An authored {@code Style} only colours the row, so a menu whose every answer is
 * {@code "Style": "neutral"} still reads as talk, shop, board and goodbye; and every glyph the helper
 * can name is a hidden child the row declares, since revealing an id the row lacks disconnects the player.
 */
class DialogueOptionGlyphsTest {

    private static final String ROW_DOC = "Common/UI/Custom/Pages/ZigDialogueOptionRow.ui";
    private static final String PAGE_DOC = "Common/UI/Custom/Pages/ZigDialoguePage.ui";

    /** A destination standing in for a shop. */
    static final class Shop extends Destination {
    }

    /** A destination that declared no kind. */
    static final class Elsewhere extends Destination {
    }

    @BeforeEach
    void reset() {
        DialogueTestSupport.reset();
        Destinations.register("test", DestinationType.of("Test_Shop", Shop.class,
                        BuilderCodec.builder(Shop.class, Shop::new).build(), (d, ctx) -> false)
                .withKind(DestinationKind.SHOP));
        Destinations.register("test", DestinationType.of("Test_Elsewhere", Elsewhere.class,
                BuilderCodec.builder(Elsewhere.class, Elsewhere::new).build(), (d, ctx) -> false));
    }

    // ==================== what an answer does, not how it is coloured ====================

    @Test
    void aNeutralStyledJumpTalksAndANeutralStyledCloseSaysGoodbye() {
        DialogueEngine engine = engine();
        DialogueOption[] rows = DialogueTestSupport.optionRows("["
                + "{\"Label\":\"who\",\"Style\":\"neutral\",\"Goto\":\"g\"},"
                + "{\"Label\":\"bye\",\"Style\":\"neutral\",\"Close\":true}]");

        assertEquals(DialogueOptionStyle.NEUTRAL, engine.classifyOption(rows[0]), "Style still colours the row");
        assertEquals("#IcoTalk", glyph(engine, rows[0]).elementId());
        assertEquals("#IcoFarewell", glyph(engine, rows[1]).elementId());
    }

    @Test
    void anAcceptKeepsItsCheckWhateverElseTheLineDoes() {
        DialogueEngine engine = engine();
        DialogueOption[] rows = DialogueTestSupport.optionRows("["
                + "{\"Label\":\"yes\",\"Style\":\"neutral\",\"Do\":[{\"Accept\":\"q\"},{\"Goto\":\"g\"}]}]");

        assertEquals(DialogueOptionStyle.ACCEPT, engine.actionStyle(rows[0]));
        assertEquals("#IcoAccept", glyph(engine, rows[0]).elementId());
    }

    @Test
    void aLineThatOpensAScreenShowsTheKindItsDestinationDeclared() {
        DialogueEngine engine = engine();
        DialogueOption[] rows = DialogueTestSupport.optionRows("["
                + "{\"Label\":\"stall\",\"Style\":\"neutral\",\"Open\":\"Test_Shop\"},"
                + "{\"Label\":\"there\",\"Style\":\"neutral\",\"Open\":\"Test_Elsewhere\"}]");

        assertTrue(engine.decisiveAction(rows[0]) instanceof DialogueAction.OpenPage);
        assertEquals("#IcoShop", glyph(engine, rows[0]).elementId());
        assertEquals("#IcoOpen", glyph(engine, rows[1]).elementId(),
                "a destination that declared no kind reads as a plain 'opens a screen'");
    }

    @Test
    void aLineWithNoDecidingActionTalksOn() {
        DialogueEngine engine = engine();
        DialogueOption[] rows = DialogueTestSupport.optionRows("[{\"Label\":\"hm\",\"Remember\":\"x\"}]");

        assertNull(engine.decisiveAction(rows[0]));
        assertEquals("#IcoTalk", glyph(engine, rows[0]).elementId());
    }

    // ==================== the order ====================

    @Test
    void aPresentationItemWinsAndKeepsAGlyphForWhenItHasNoPicture() {
        DialogueEngine engine = engine();
        DialogueOption[] rows = DialogueTestSupport.optionRows("["
                + "{\"Label\":\"knock\",\"Style\":\"neutral\","
                + "\"Presentation\":{\"Icon\":{\"Item\":\"Halloween_Basket_Pumpkin\"}},\"Close\":true}]");

        DialogueOptionGlyphs.Glyph glyph = glyph(engine, rows[0]);
        assertEquals("Halloween_Basket_Pumpkin", glyph.itemId());
        assertEquals("#IcoFarewell", glyph.elementId());
    }

    @Test
    void aPresentationGlyphBeatsTheDestinationsKindAndAnUnknownOneFallsThrough() {
        DialogueEngine engine = engine();
        DialogueOption[] rows = DialogueTestSupport.optionRows("["
                + "{\"Label\":\"a\",\"Presentation\":{\"Icon\":{\"Glyph\":\"Book\"}},\"Open\":\"Test_Shop\"},"
                + "{\"Label\":\"b\",\"Presentation\":{\"Icon\":{\"Glyph\":\"nonsense\"}},\"Open\":\"Test_Shop\"}]");

        assertEquals("#IcoBook", glyph(engine, rows[0]).elementId());
        assertEquals("#IcoShop", glyph(engine, rows[1]).elementId());
    }

    @Test
    void theDestinationsKindBeatsTheThemeAndTheThemeBeatsTheKindsOwnGlyph() {
        DialogueOptionTheme openTheme = new DialogueOptionTheme(null, null, null, "continue");

        assertEquals("#IcoQuest", DialogueOptionGlyphs.resolve(DialogueOptionStyle.NEUTRAL, DestinationKind.QUEST,
                openTheme, null).elementId());
        assertEquals("#IcoContinue", DialogueOptionGlyphs.resolve(DialogueOptionStyle.NEUTRAL, null,
                openTheme, null).elementId());
        assertEquals("#IcoOpen", DialogueOptionGlyphs.resolve(DialogueOptionStyle.NEUTRAL, null,
                null, null).elementId());
    }

    // ==================== the row and the page ====================

    @Test
    void everyGlyphTheHelperCanNameIsAHiddenChildOfTheRow() throws IOException {
        String row = resource(ROW_DOC);
        List<String> tokens = List.of("accept", "turnin", "continue", "open", "farewell", "talk");
        for (String token : tokens) {
            assertDeclaredHidden(row, DialogueOptionGlyphs.elementFor(token));
        }
        for (DestinationKind kind : DestinationKind.values()) {
            assertDeclaredHidden(row, DialogueOptionGlyphs.elementFor(kind.key()));
        }
        for (DialogueOptionStyle style : DialogueOptionStyle.values()) {
            assertDeclaredHidden(row, style.iconElementId());
        }
    }

    @Test
    void anItemPictureIsTheKitsPlainPictureNeverAnItemSlot() throws IOException {
        String row = resource(ROW_DOC);
        assertFalse(row.contains("ItemGrid"), "an item slot shows the item's own tooltip and rarity square");
        assertTrue(Pattern.compile("\\$ZW\\.@ZigPicture\\s+#OptPic\\s*\\{").matcher(row).find(),
                "the picture is the kit's plain slot, painted through IconRenderer.applyPlainIcon");
    }

    @Test
    void theTextAndTheAnswersShareOneScrollingBodyAndNeitherFixesAHeight() throws IOException {
        String page = resource(PAGE_DOC);
        String body = block(page, "Group #Body");
        assertTrue(body.contains("LayoutMode: TopScrolling"), "the body is the one scroller");
        String text = block(body, "Group #TextPanel");
        String options = block(body, "Group #OptionsList");
        assertFalse(head(text).contains("Height"), "the text panel is as tall as its words");
        assertFalse(options.contains("Height"), "the answers are as tall as their rows");
        assertFalse(options.contains("Scrolling"), "the answers do not scroll on their own");
        assertTrue(body.indexOf("#TextPanel") < body.indexOf("#OptionsList"));
        assertFalse(head(block(page, "Group #ActiveQuestHint")).contains("Height:"),
                "the note under the name wraps instead of clipping");
    }

    // ==================== helpers ====================

    @Nonnull
    private static DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).build();
    }

    /** The glyph the page would show, read the way the page reads it. */
    @Nonnull
    private static DialogueOptionGlyphs.Glyph glyph(@Nonnull DialogueEngine engine, @Nonnull DialogueOption option) {
        DialogueOptionStyle does = engine.actionStyle(option);
        DestinationKind kind = engine.decisiveAction(option) instanceof DialogueAction.OpenPage open
                ? Destinations.kindOf(open.getTarget()) : null;
        return DialogueOptionGlyphs.resolve(does, kind, null, option.getPresentation());
    }

    private static void assertDeclaredHidden(@Nonnull String row, String id) {
        assertNotNull(id, "every token names an element");
        Matcher declared = Pattern.compile("Group\\s+" + Pattern.quote(id) + "\\s*\\{[^}]*Visible:\\s*false")
                .matcher(row);
        assertTrue(declared.find(), id + " is a hidden glyph the row declares");
    }

    /** The braced body that follows {@code opener}. */
    @Nonnull
    private static String block(@Nonnull String ui, @Nonnull String opener) {
        int at = ui.indexOf(opener);
        assertTrue(at >= 0, opener + " is declared");
        int open = ui.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < ui.length(); i++) {
            char c = ui.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return ui.substring(open + 1, i);
            }
        }
        throw new AssertionError(opener + " never closes");
    }

    /** A block's own properties: everything before its first child. */
    @Nonnull
    private static String head(@Nonnull String body) {
        int child = body.indexOf('{');
        String own = child < 0 ? body : body.substring(0, child);
        int lastLine = own.lastIndexOf('\n');
        return lastLine < 0 ? own : own.substring(0, lastLine);
    }

    @Nonnull
    private static String resource(@Nonnull String path) throws IOException {
        try (InputStream in = DialogueOptionGlyphsTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path + " ships");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
