package com.ziggfreed.common.ui.kit;

import static com.ziggfreed.common.ui.kit.KitDocs.defines;
import static com.ziggfreed.common.ui.kit.KitDocs.document;
import static com.ziggfreed.common.ui.kit.KitDocs.style;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * Java swaps a row's, a segment's, a tile's, a button's or a state word's look by naming a style in a theme document
 * ({@code Value.ref(document, name)}), so every name it can ask for must exist in every theme document shipped: a
 * reference to a name the document lacks fails on the client. The default theme is {@code Common/ZigStyles.ui};
 * another theme is a document under {@code Common/Themes/} with the same names. Beside the names, the default's
 * styles are vanilla's own: its row states, its tertiary and active segment patches, the Memories tiles, a sound on
 * every button and the cancel sound vanilla's destructive buttons play on the danger button, and a tone's own text
 * colour on its state word.
 */
class ZigStylesDocumentTest {

    private static final String DEFAULT_THEME = "Common/ZigStyles.ui";

    /** Plan section 2.7's names, which {@code ZigStyles.Name} answers. */
    private static final List<String> NAMES = List.of("ZigRowStyle", "ZigRowSelectedStyle", "ZigSegmentStyle",
            "ZigSegmentOnStyle", "ZigTileStyle", "ZigTileCompleteStyle", "ZigButtonPrimaryStyle",
            "ZigButtonSecondaryStyle", "ZigButtonCollectStyle", "ZigButtonDangerStyle", "ZigStateStyle",
            "ZigStateActiveStyle", "ZigStateCollectStyle", "ZigStateDoneStyle", "ZigStateLiveStyle",
            "ZigStateAvailableStyle", "ZigStateWaitingStyle", "ZigStateBlockedStyle", "ZigStateDangerStyle",
            "ZigFigureAccentStyle", "ZigRowTitleOnSelectedStyle");

    /** The eight tones a state word takes (the neutral word is {@code ZigStateStyle}, in body ink). */
    private static final List<String> TONES = List.of("Active", "Collect", "Done", "Live", "Available", "Waiting",
            "Blocked", "Danger");

    @Test
    void everyNameJavaAsksForExistsInEveryThemeDocument() throws Exception {
        Set<String> names = new LinkedHashSet<>(NAMES);
        names.addAll(enumNames());
        List<String> missing = new ArrayList<>();
        for (String theme : themes()) {
            String ui = document(theme);
            for (String name : names) {
                if (!defines(ui, "@" + name)) {
                    missing.add(theme + " @" + name);
                }
            }
        }
        assertTrue(missing.isEmpty(), "every style Java swaps in by reference exists in every theme document, or the "
                + "client's lookup fails: " + missing);
    }

    @Test
    void theJavaNamesItsDefaultDocument() throws Exception {
        Class<?> styles = kitClass("ZigStyles");
        if (styles != null) {
            assertEquals(DEFAULT_THEME, styles.getField("DOCUMENT").get(null), "ZigStyles.DOCUMENT is the default theme");
        }
    }

    @Test
    void theRowStylesAreVanillasRowOnTheTokens() throws IOException {
        String ui = document(DEFAULT_THEME);
        String row = style(ui, "@ZigRowStyle");
        assertTrue(row.contains("Default: (Background: (Color: $ZK.@ZigSurfaceRow))")
                && row.contains("Hovered: (Background: (Color: $ZK.@ZigSurfaceRowHover))")
                && row.contains("Pressed: (Background: (Color: $ZK.@ZigSurfaceRowPressed))"), row);
        String selected = style(ui, "@ZigRowSelectedStyle");
        for (String state : List.of("Default", "Hovered", "Pressed")) {
            assertTrue(selected.contains(state + ": (Background: (Color: $ZK.@ZigSurfaceRowSelected))"),
                    "the selected row keeps the steel blue in every state: " + selected);
        }
        assertTrue(style(ui, "@ZigRowTitleOnSelectedStyle").contains("TextColor: $ZK.@ZigInkBright"),
                "white on the selected row (4.9:1)");
    }

    @Test
    void segmentsAreVanillasTertiaryPatchesAndTilesTheMemoriesTiles() throws IOException {
        String ui = document(DEFAULT_THEME);
        assertTrue(style(ui, "@ZigSegmentStyle").contains("$C.@TertiaryDefaultButtonBackground"));
        assertTrue(style(ui, "@ZigSegmentOnStyle").contains("$C.@TertiaryActiveButtonBackground"),
                "the chosen segment is vanilla's Tertiary_Active patch");
        assertTrue(style(ui, "@ZigTileStyle").contains("Memories/Tiles/TileDefault.png")
                && style(ui, "@ZigTileStyle").contains("Memories/Tiles/TileHovered.png"));
        assertTrue(style(ui, "@ZigTileCompleteStyle").contains("Memories/Tiles/TileComplete.png"));
    }

    @Test
    void everyButtonStyleClicksAndTheDangerButtonSoundsDestructive() throws IOException {
        String ui = document(DEFAULT_THEME);
        for (String name : List.of("@ZigRowStyle", "@ZigRowSelectedStyle", "@ZigSegmentStyle", "@ZigSegmentOnStyle",
                "@ZigTileStyle", "@ZigTileCompleteStyle", "@ZigButtonPrimaryStyle", "@ZigButtonSecondaryStyle",
                "@ZigButtonCollectStyle", "@ZigButtonDangerStyle")) {
            assertTrue(style(ui, name).contains("Sounds: $C.@"), name + " clicks with a sound");
        }
        assertTrue(style(ui, "@ZigButtonDangerStyle").contains("Sounds: $C.@ButtonsCancel"),
                "Abandon sounds like vanilla's destructive buttons, whose set is the cancel set; vanilla's "
                        + "$C.@ButtonDestructiveSounds alias dangles on the server and fails the client's parse");
        assertTrue(style(ui, "@ZigButtonCollectStyle").contains("$ZK.@ZigAccent"), "Collect is gold");
        for (String name : List.of("@ZigButtonPrimaryStyle", "@ZigButtonSecondaryStyle", "@ZigButtonCollectStyle",
                "@ZigButtonDangerStyle")) {
            assertTrue(style(ui, name).contains("Disabled:"), name + " greys out when Java disables it");
        }
    }

    @Test
    void eachStateWordIsItsTonesTextColour() throws IOException {
        String ui = document(DEFAULT_THEME);
        String base = style(ui, "@ZigStateStyle");
        assertTrue(base.contains("FontSize: $ZT.@ZigFontCaption") && base.contains("RenderBold: true")
                && base.contains("WrapMaxLines: 1") && base.contains("TextColor: $ZK.@ZigInkBody"),
                "a state word is 13, bold, one line, body ink when neutral: " + base);
        for (String tone : TONES) {
            String word = style(ui, "@ZigState" + tone + "Style");
            assertTrue(word.contains("...@ZigStateStyle"), tone + " spreads the base state word");
            assertTrue(word.contains("TextColor: $ZK.@ZigTone" + tone + "Text"), tone + " is its tone's text: " + word);
        }
        String figure = style(ui, "@ZigFigureAccentStyle");
        assertTrue(figure.contains("...$ZX.@ZigFigureStyle") && figure.contains("TextColor: $ZK.@ZigAccent"),
                "points read gold: " + figure);
    }

    @Test
    void aThemeImportsWhatItReads() throws IOException {
        for (String theme : themes()) {
            String ui = document(theme);
            assertFalse(ui.contains("@ZigFontMicro"), theme + ": no player text under the floor");
            String prefix = theme.startsWith("Common/Themes/") ? "../" : "";
            if (ui.contains("$ZK.@")) {
                assertTrue(ui.contains("$ZK = \"" + prefix + "ZigTokens.ui\";"), theme + " imports the tokens");
            }
            if (ui.contains("$C.@")) {
                assertTrue(ui.contains("$C = \"../" + prefix + "Common.ui\";"), theme + " imports vanilla's Common.ui");
            }
        }
    }

    /** The default theme and every document under {@code Common/Themes/}, as paths under {@code Common/UI/Custom/}. */
    @Nonnull
    private static List<String> themes() throws IOException {
        List<String> out = new ArrayList<>();
        out.add(DEFAULT_THEME);
        URL themes = ZigStylesDocumentTest.class.getResource("/Common/UI/Custom/Common/Themes");
        if (themes != null && "file".equals(themes.getProtocol())) {
            Path folder;
            try {
                folder = Paths.get(themes.toURI());
            } catch (URISyntaxException bad) {
                throw new IOException(bad);
            }
            try (Stream<Path> walk = Files.list(folder)) {
                walk.filter(p -> p.toString().endsWith(".ui")).sorted()
                        .forEach(p -> out.add("Common/Themes/" + p.getFileName()));
            }
        }
        return out;
    }

    /** {@code ZigStyles.Name}'s style names, once the Java kit ships it (read by reflection, so this compiles alone). */
    @Nonnull
    private static List<String> enumNames() throws Exception {
        Class<?> name = kitClass("ZigStyles$Name");
        List<String> out = new ArrayList<>();
        if (name == null) {
            return out;
        }
        Method styleName = name.getMethod("styleName");
        for (Object constant : name.getEnumConstants()) {
            String spelled = (String) styleName.invoke(constant);
            out.add(spelled.startsWith("@") ? spelled.substring(1) : spelled);
        }
        return out;
    }

    private static Class<?> kitClass(@Nonnull String simple) {
        try {
            return Class.forName("com.ziggfreed.common.ui.kit." + simple);
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }
}
