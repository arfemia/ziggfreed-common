package com.ziggfreed.common.ui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBinding;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.theme.Palette;

/**
 * What one paint of the rail sends: one row template per row, a tab as its button with its label, picture and
 * click, the selected tab as the authored selected group with no click, a heading and the rule in the button's
 * place, the pane and every fill as a typed patch from the palette in force, and a theme's palette reaching
 * every colour. Tagged {@code engine-items}: a {@link UICommandBuilder}'s static init reaches the engine's item
 * codec, which needs the engine's log manager.
 *
 * <p>The rail painted: 0 the "Skills" heading, 1 tab a (no picture), 2 tab b (a picture), 3 the rule,
 * 4 Quests (a picture), 5 Achievements (no picture).
 */
@Tag("engine-items")
class ZigMenuPaintTest {

    private static final String TEXTURE = "UI/Custom/Pages/Memories/MissingIcon.png";

    @BeforeEach
    void seed() {
        ZigMenu.clearForTests();
    }

    @AfterEach
    void clear() {
        ZigMenu.clearForTests();
    }

    @Test
    void everyRowIsTheOneTemplateAppendedByIndex() {
        Painted p = paint(MenuSlot.QUESTS.id(), null);
        assertEquals(Collections.nCopies(6, MenuFrame.LIST + " <- " + ZigMenu.TAB_TEMPLATE), p.appends());
    }

    @Test
    void aTabIsItsButtonWithItsLabelItsPictureAndItsClick() {
        Painted p = paint(MenuSlot.QUESTS.id(), null);
        String b = "#MenuList[2] " + ZigMenu.BUTTON;
        assertTrue(p.sets().containsKey(b + " #Label.TextSpans"), "the label goes on .TextSpans");
        assertTrue(p.set(b + " #Label.Style.TextColor").contains(MenuPalette.TEXT_MUTED));
        assertTrue(p.shown(b + " " + ZigMenu.ICON_SLOT + ".Visible"), "a tab with a picture shows it");
        assertTrue(p.set(b + " " + ZigMenu.ICON_SLOT + " #IcoTex.AssetPath").contains(TEXTURE));
        assertTrue(p.set(b + ".Style.Hovered.Background").contains(MenuPalette.HEADER), "the hover fill");
        assertTrue(p.set(b + ".Style.Pressed.Background").contains(MenuPalette.HEADER));
        String click = p.bindings().get(b);
        assertNotNull(click, "an unselected tab is clickable");
        assertTrue(click.contains("\"" + ZigMenu.EVENT_KEY + "\"") && click.contains("\"2\""),
                "the click carries its row's index: " + click);
        assertFalse(p.sets().containsKey("#MenuList[2] " + ZigMenu.SELECTED + ".Visible"),
                "only the selected tab shows the selected group");
    }

    @Test
    void aTabWithNoPictureKeepsItsSlotHidden() {
        Painted p = paint(MenuSlot.QUESTS.id(), null);
        assertFalse(p.sets().containsKey("#MenuList[1] " + ZigMenu.BUTTON + " " + ZigMenu.ICON_SLOT + ".Visible"),
                "no picture: the authored hidden slot stays hidden, so no empty frame shows");
        assertFalse(p.sets().containsKey("#MenuList[5] " + ZigMenu.BUTTON + " " + ZigMenu.ICON_SLOT + ".Visible"));
    }

    @Test
    void theSelectedTabIsMarkedBoldMaskedAndUnbound() {
        Painted p = paint(MenuSlot.QUESTS.id(), null);
        String row = "#MenuList[4]";
        String selected = row + " " + ZigMenu.SELECTED;
        assertFalse(p.shown(row + " " + ZigMenu.BUTTON + ".Visible"), "the button gives way to the selected group");
        assertTrue(p.shown(selected + ".Visible"));
        assertTrue(p.set(selected + ".Background").contains(MenuPalette.HEADER), "the selected fill");
        assertTrue(p.set(selected + " " + ZigMenu.MARKER + ".Background").contains(MenuPalette.ACCENT), "the bar");
        assertTrue(p.sets().containsKey(selected + " " + ZigMenu.SELECTED_LABEL + ".TextSpans"));
        assertTrue(p.set(selected + " " + ZigMenu.SELECTED_LABEL + ".Style.TextColor").contains(MenuPalette.TEXT_PRIMARY));
        assertTrue(p.shown(selected + " " + ZigMenu.SELECTED_ICON_SLOT + ".Visible"));
        assertTrue(p.set(selected + " " + ZigMenu.SELECTED_ICON_SLOT + " #IcoTex.AssetPath").contains(TEXTURE));
        assertNull(p.bindings().get(row + " " + ZigMenu.BUTTON), "pressing the page you are on does nothing");
        assertEquals(3, p.bindings().size(), "the three other tabs are clickable; a heading and the rule are not");
    }

    @Test
    void withNothingSelectedEveryTabIsClickableAndNoneMarked() {
        Painted p = paint(null, null);
        assertEquals(4, p.bindings().size(), "four tabs, four clicks");
        for (String selector : p.sets().keySet()) {
            assertFalse(selector.endsWith(ZigMenu.SELECTED + ".Visible"), "no tab is marked: " + selector);
        }
    }

    @Test
    void aHeadingAndTheRuleTakeTheButtonsPlace() {
        Painted p = paint(MenuSlot.QUESTS.id(), null);
        assertFalse(p.shown("#MenuList[0] " + ZigMenu.BUTTON + ".Visible"));
        assertTrue(p.shown("#MenuList[0] " + ZigMenu.HEADER + ".Visible"));
        assertTrue(p.sets().containsKey("#MenuList[0] " + ZigMenu.HEADER + ".TextSpans"));
        assertTrue(p.set("#MenuList[0] " + ZigMenu.HEADER + ".Style.TextColor").contains(MenuPalette.TEXT_MUTED));
        assertFalse(p.shown("#MenuList[3] " + ZigMenu.BUTTON + ".Visible"));
        assertTrue(p.shown("#MenuList[3] " + ZigMenu.RULE + ".Visible"), "the gap between the sections is a rule");
        assertTrue(p.set("#MenuList[3] " + ZigMenu.RULE + ".Background").contains(MenuPalette.DIVIDER));
    }

    @Test
    void thePaneAndEveryFillAreTypedPatchesFromThePalette() {
        Painted p = paint(MenuSlot.QUESTS.id(), null);
        assertTrue(p.set(MenuFrame.RAIL + ".Background").contains(MenuPalette.BACKGROUND), "vanilla's pane");
        for (Map.Entry<String, String> set : p.sets().entrySet()) {
            if (set.getKey().endsWith(".Background")) {
                assertTrue(set.getValue().contains("\"Color\""),
                        set.getKey() + " is a typed PatchStyle, never a bare String: " + set.getValue());
            }
            assertFalse(set.getKey().contains(".Style.Default"),
                    "a resting tab's no-fill state is authored, never pushed: " + set.getKey());
        }
    }

    @Test
    void aThemesPaletteRepaintsTheWholeRail() {
        Palette molten = new Palette("#2a1410", "#ff5a2a", "#160a08");
        molten.header = "#2a1410";
        molten.textMuted = "#b89a88";
        molten.textPrimary = "#f5e0d0";
        molten.divider = "#6b2f17";
        Painted p = paint(MenuSlot.QUESTS.id(), molten);
        assertTrue(p.set(MenuFrame.RAIL + ".Background").contains("#160a08"));
        assertTrue(p.set("#MenuList[2] " + ZigMenu.BUTTON + ".Style.Hovered.Background").contains("#2a1410"));
        assertTrue(p.set("#MenuList[2] " + ZigMenu.BUTTON + " #Label.Style.TextColor").contains("#b89a88"));
        assertTrue(p.set("#MenuList[4] " + ZigMenu.SELECTED + " " + ZigMenu.MARKER + ".Background").contains("#ff5a2a"));
        assertTrue(p.set("#MenuList[4] " + ZigMenu.SELECTED + " " + ZigMenu.SELECTED_LABEL + ".Style.TextColor").contains("#f5e0d0"));
        assertTrue(p.set("#MenuList[3] " + ZigMenu.RULE + ".Background").contains("#6b2f17"));
    }

    @Test
    void aFramePaintBeforeTheRailDoesNotLeaveThePanelPatchUnderIt() {
        UICommandBuilder cmd = new UICommandBuilder();
        // A textured theme's frame paint reaching the rail, as ThemeRetint.applyTheme does with "#MenuRail".
        UiRetint.swapPatch(cmd, MenuFrame.RAIL, "Common/Molten/ContainerPanelPatch.png", 4, null);
        Painted p = paint(cmd, MenuSlot.QUESTS.id(), null);
        String pane = p.set(MenuFrame.RAIL + ".Background");
        assertTrue(pane.contains(MenuPalette.BACKGROUND) && !pane.contains("TexturePath"),
                "the rail paints its pane last: " + pane);
    }

    @Nonnull
    private static Painted paint(@Nullable String selectedId, @Nullable Palette palette) {
        return paint(new UICommandBuilder(), selectedId, palette);
    }

    @Nonnull
    private static Painted paint(@Nonnull UICommandBuilder cmd, @Nullable String selectedId, @Nullable Palette palette) {
        ZigMenu.fill(MenuSlot.QUESTS, MenuSlot.QUESTS.entry(Message.raw("q"), IconSpec.ofTexture(TEXTURE),
                new ZigMenuTest.Probe(), viewer -> true));
        ZigMenu.fill(MenuSlot.ACHIEVEMENTS, ZigMenuTest.slot(MenuSlot.ACHIEVEMENTS, true));
        MenuSection skills = new MenuSection(Message.raw("Skills"), List.of(ZigMenuTest.entry("a", true),
                new MenuEntry("b", Message.raw("b"), IconSpec.ofTexture(TEXTURE), new ZigMenuTest.Probe(), viewer -> true)));
        ZigMenu.consumer(() -> MenuDeps.builder().section(skills).palette(palette).build());
        UIEventBuilder events = new UIEventBuilder();
        ZigMenu.paint(cmd, events, ZigMenuTest.VIEWER, selectedId, false);
        return Painted.of(cmd, events);
    }

    /** The commands and bindings one paint produced, the last write per selector winning, as on the client. */
    private record Painted(Map<String, String> sets, List<String> appends, Map<String, String> bindings) {

        static Painted of(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
            Map<String, String> sets = new HashMap<>();
            List<String> appends = new ArrayList<>();
            for (CustomUICommand command : cmd.getCommands()) {
                if (command.type == CustomUICommandType.Set) {
                    sets.put(command.selector, command.data);
                } else if (command.type == CustomUICommandType.Append) {
                    appends.add(command.selector + " <- " + command.text);
                }
            }
            Map<String, String> bindings = new HashMap<>();
            for (CustomUIEventBinding binding : events.getEvents()) {
                bindings.put(binding.selector, binding.data);
            }
            return new Painted(sets, appends, bindings);
        }

        String set(@Nonnull String selector) {
            String data = sets.get(selector);
            assertNotNull(data, selector + " is painted");
            return data;
        }

        boolean shown(@Nonnull String selector) {
            return set(selector).contains("true");
        }
    }
}
