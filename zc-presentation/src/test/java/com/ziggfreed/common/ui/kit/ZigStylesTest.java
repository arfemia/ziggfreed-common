package com.ziggfreed.common.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.ui.UiRetint;

/**
 * The named state styles: the reference form is vanilla's {@code Value.ref} (a document path and a style name, no
 * value), the fallback form is the style's leaves from the tokens (three button-state fills, or a label colour),
 * both valid commands for every name; the names are the plan's, each once; the theme seam reads as the default
 * when it has nothing to say. Tagged {@code engine-items}: a {@link UICommandBuilder}'s static init reaches the
 * engine's item codec.
 */
@Tag("engine-items")
class ZigStylesTest {

    /**
     * The style names of plan section 2.7, plus the base state style for the neutral tone and the three words a
     * selected row turns white (the document's own additions: no tone or muted ink reads on the steel blue).
     */
    private static final List<String> PLAN_NAMES = List.of("ZigRowStyle", "ZigRowSelectedStyle", "ZigSegmentStyle",
            "ZigSegmentOnStyle", "ZigTileStyle", "ZigTileCompleteStyle", "ZigButtonPrimaryStyle",
            "ZigButtonSecondaryStyle", "ZigButtonCollectStyle", "ZigButtonDangerStyle", "ZigStateStyle",
            "ZigStateActiveStyle", "ZigStateCollectStyle", "ZigStateDoneStyle", "ZigStateLiveStyle",
            "ZigStateAvailableStyle", "ZigStateWaitingStyle", "ZigStateBlockedStyle", "ZigStateDangerStyle",
            "ZigFigureAccentStyle", "ZigRowTitleOnSelectedStyle", "ZigRowMetaOnSelectedStyle",
            "ZigRowValueOnSelectedStyle", "ZigStateOnSelectedStyle", "ZigViewTabStyle", "ZigViewTabOnStyle",
            "ZigViewTabLabelStyle", "ZigViewTabLabelOnStyle");

    @AfterEach
    void reset() {
        ZigStyles.resetForTests();
    }

    @Test
    void theReferenceFormIsVanillasValueRef() {
        UICommandBuilder cmd = new UICommandBuilder();
        ZigStyles.apply(cmd, "#R.Style", ZigStyles.Name.ROW_SELECTED, null, true);
        Painted p = Painted.of(cmd);
        assertEquals(1, p.sets().size());
        assertTrue(p.references("#R.Style", "Common/ZigStyles.ui", "ZigRowSelectedStyle"), p.set("#R.Style"));
    }

    @Test
    void everyNameGoesByReferenceToTheDefaultDocument() {
        for (ZigStyles.Name name : ZigStyles.Name.values()) {
            UICommandBuilder cmd = new UICommandBuilder();
            ZigStyles.apply(cmd, "#X.Style", name, null);
            assertTrue(Painted.of(cmd).references("#X.Style", ZigStyles.DOCUMENT, name.styleName()), name.name());
        }
    }

    @Test
    void theFallbackFormSendsAButtonsThreeStateFills() {
        UICommandBuilder cmd = new UICommandBuilder();
        ZigStyles.apply(cmd, "#R.Style", ZigStyles.Name.ROW, null, false);
        Painted p = Painted.of(cmd);
        assertEquals(ZigTokens.SURFACE_ROW, unwrap(p.set("#R.Style.Default.Background.Color")));
        assertEquals(ZigTokens.SURFACE_ROW_HOVER, unwrap(p.set("#R.Style.Hovered.Background.Color")));
        assertEquals(ZigTokens.SURFACE_ROW_PRESSED, unwrap(p.set("#R.Style.Pressed.Background.Color")));
        assertFalse(p.has("#R.Style"), "the fallback never sends the whole style");
    }

    @Test
    void theFallbackFormSendsALabelsColour() {
        UICommandBuilder cmd = new UICommandBuilder();
        ZigStyles.apply(cmd, "#W.Style", ZigStyles.Name.STATE_WAITING, null, false);
        assertEquals(ZigTokens.TONE_WAITING_TEXT, unwrap(Painted.of(cmd).set("#W.Style.TextColor")));
    }

    @Test
    void everyNameHasAValidFallback() {
        for (ZigStyles.Name name : ZigStyles.Name.values()) {
            UICommandBuilder cmd = new UICommandBuilder();
            ZigStyles.apply(cmd, "#X.Style", name, null, false);
            Map<String, String> sets = Painted.of(cmd).sets();
            assertFalse(sets.isEmpty(), name + " sends its leaves");
            for (Map.Entry<String, String> set : sets.entrySet()) {
                assertTrue(set.getKey().startsWith("#X.Style."), set.getKey());
                assertTrue(UiRetint.isHex(unwrap(set.getValue())), name + " sends a colour: " + set.getValue());
            }
        }
    }

    @Test
    void aRestingTextStyleGoesToTheTextDocument() {
        UICommandBuilder cmd = new UICommandBuilder();
        ZigStyles.applyText(cmd, "#T.Style", ZigStyles.Text.ROW_TITLE, true);
        ZigStyles.applyText(cmd, "#U.Style", ZigStyles.Text.FAINT, false);
        Painted p = Painted.of(cmd);
        assertTrue(p.references("#T.Style", ZigStyles.TEXT_DOCUMENT, "ZigRowTitleStyle"));
        assertEquals(ZigTokens.INK_FAINT, unwrap(p.set("#U.Style.TextColor")));
    }

    @Test
    void theNamesAreThePlansEachOnce() {
        Set<String> names = new HashSet<>();
        for (ZigStyles.Name name : ZigStyles.Name.values()) {
            assertTrue(names.add(name.styleName()), "one style per name: " + name.styleName());
            assertFalse(name.styleName().startsWith("@"), "Value.ref takes the name without its sigil");
        }
        assertEquals(new HashSet<>(PLAN_NAMES), names);
    }

    @Test
    void everyToneHasItsOwnStateStyle() {
        Set<ZigStyles.Name> seen = new HashSet<>();
        for (Tone tone : Tone.values()) {
            assertTrue(tone.stateStyle().styleName().startsWith("ZigState"), tone.name());
            assertTrue(tone.stateStyle() != ZigStyles.Name.STATE_ON_SELECTED, tone.name());
            assertTrue(seen.add(tone.stateStyle()), tone + " has a style of its own");
        }
    }

    @Test
    void theThemeSeamReadsAsTheDefaultWhenItHasNothingToSay() {
        assertEquals(ZigStyles.DOCUMENT, ZigStyles.document(null));
        ZigStyles.documents(viewer -> "Common/Themes/Hallowed.ui");
        assertEquals(ZigStyles.DOCUMENT, ZigStyles.document(null), "no viewer, no theme");
        ZigStyles.resetForTests();
        assertEquals(ZigStyles.DOCUMENT, ZigStyles.document(null));
    }

    @Test
    void theThemeSeamTakesOnlyTheDefaultOrADocumentUnderThemes() {
        assertEquals("Common/Themes/Hallowed.ui", ZigStyles.accept("Common/Themes/Hallowed.ui"));
        assertEquals(ZigStyles.DOCUMENT, ZigStyles.accept(ZigStyles.DOCUMENT));
        assertEquals("Common/Themes/Hallowed.ui", ZigStyles.themeDocument("Hallowed"));
        for (String refused : new String[] {null, "", "  ", "Pages/ZigBookAchievements.ui", "Common/Themes/../Evil.ui",
                "Common/Themes/Sub/Deep.ui", "Common/Themes/Bad_Name.ui", "Common/Themes/Hallowed",
                "../Common/Themes/Hallowed.ui", "common/themes/Hallowed.ui"}) {
            assertEquals(ZigStyles.DOCUMENT, ZigStyles.accept(refused),
                    "a chooser's answer that is not a theme document reads as the default: " + refused);
        }
    }

    /** A set command wraps its value as {@code {"0": value}}; the string value alone. */
    private static String unwrap(String data) {
        int colon = data.indexOf(':');
        String value = data.substring(colon + 1, data.lastIndexOf('}')).trim();
        return value.startsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }
}
