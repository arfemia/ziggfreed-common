package com.ziggfreed.common.ui.icon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.ziggfreed.common.icon.IconSpec;

/**
 * A plain picture ({@link IconRenderer#applyPlainIcon}, a menu tab's) is the row's one {@code AssetImage}: an item
 * draws as its own icon texture, never through an item grid (whose tooltip and rarity square showed on the tab)
 * or an {@code ItemIcon} (which draws blank); an item this server does not ship falls back to the spec's texture,
 * and with neither the picture hides. No item store runs in a unit JVM, so every item id here reads as one the
 * server does not ship. Tagged {@code engine-items}: a {@link UICommandBuilder}'s static init reaches the engine's
 * item codec.
 */
@Tag("engine-items")
class IconRendererTest {

    private static final String ROW = "#Row";
    private static final String TEXTURE = "Icons/ItemsGenerated/Deco_Lever.png";

    @Test
    void anItemTheServerDoesNotShipFallsBackToTheTexture() {
        UICommandBuilder cmd = new UICommandBuilder();
        boolean drawn = IconRenderer.applyPlainIcon(cmd, ROW, "No_Such_Item", TEXTURE);

        Map<String, String> sets = sets(cmd);
        assertTrue(drawn);
        assertTrue(shown(sets, ROW + " #IcoTex.Visible"));
        assertTrue(sets.get(ROW + " #IcoTex.AssetPath").contains(TEXTURE));
        assertNoItemWidgetTouched(sets);
    }

    @Test
    void anItemTheServerDoesNotShipWithNoTextureDrawsNothing() {
        UICommandBuilder cmd = new UICommandBuilder();
        boolean drawn = IconRenderer.applyPlainIcon(cmd, ROW, IconSpec.ofItem("No_Such_Item"));

        Map<String, String> sets = sets(cmd);
        assertFalse(drawn, "the caller hides the slot, so no unknown-item picture and no empty frame");
        assertFalse(shown(sets, ROW + " #IcoTex.Visible"));
        assertFalse(sets.containsKey(ROW + " #IcoTex.AssetPath"));
        assertNoItemWidgetTouched(sets);
    }

    @Test
    void aTextureAloneIsDrawnAsTheTexture() {
        UICommandBuilder cmd = new UICommandBuilder();
        assertTrue(IconRenderer.applyPlainIcon(cmd, ROW, IconSpec.ofTexture(TEXTURE)));

        Map<String, String> sets = sets(cmd);
        assertTrue(shown(sets, ROW + " #IcoTex.Visible"));
        assertTrue(sets.get(ROW + " #IcoTex.AssetPath").contains(TEXTURE));
        assertNoItemWidgetTouched(sets);
    }

    @Test
    void nothingToDrawHidesThePicture() {
        UICommandBuilder cmd = new UICommandBuilder();
        assertFalse(IconRenderer.applyPlainIcon(cmd, ROW, null));

        Map<String, String> sets = sets(cmd);
        assertFalse(shown(sets, ROW + " #IcoTex.Visible"));
        assertNoItemWidgetTouched(sets);
    }

    /** A plain picture's row declares no item widget, so a command against one would disconnect the player. */
    private static void assertNoItemWidgetTouched(@Nonnull Map<String, String> sets) {
        for (String selector : sets.keySet()) {
            assertFalse(selector.contains("#IcoItem"), "a plain picture never addresses an item widget: " + selector);
        }
    }

    private static boolean shown(@Nonnull Map<String, String> sets, @Nonnull String selector) {
        String data = sets.get(selector);
        assertNotNull(data, selector + " is painted");
        return data.contains("true");
    }

    @Nonnull
    private static Map<String, String> sets(@Nonnull UICommandBuilder cmd) {
        Map<String, String> sets = new HashMap<>();
        for (CustomUICommand command : cmd.getCommands()) {
            if (command.type == CustomUICommandType.Set) {
                sets.put(command.selector, command.data);
            }
        }
        return sets;
    }
}
