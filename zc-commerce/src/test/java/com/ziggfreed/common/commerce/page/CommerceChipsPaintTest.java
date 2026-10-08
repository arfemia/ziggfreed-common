package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.loot.reward.RewardChip;

/**
 * A wallet's picture is not the item it borrows: a currency's {@code Icon} is an item id, and an item
 * slot would name that ITEM on hover beside a price in that currency. So a chip or a reward line whose
 * picture only stands for a wallet draws it plain and names the wallet on hover, while an item price or
 * an item reward keeps the item slot, whose own tooltip is the point. Tagged {@code engine-items}: a
 * {@link UICommandBuilder}'s static init reaches the engine's item codec.
 */
@Tag("engine-items")
class CommerceChipsPaintTest {

    private static final String CHIP_DOC = "Common/UI/Custom/Pages/ZigCommerceChip.ui";

    @Test
    void aWalletChipDrawsItsPicturePlainAndNamesTheWalletOnHover() {
        UICommandBuilder cmd = new UICommandBuilder();
        CommerceChips.render(cmd, "#Price", List.of(
                new CommerceChips.Chip("No_Such_Item", Message.raw("12"), CommerceChips.COLOR_AFFORDABLE,
                        Message.raw("Hallow Sweets")),
                new CommerceChips.Chip("No_Such_Item", Message.raw("x2"), CommerceChips.COLOR_AFFORDABLE)), 0);
        Map<String, String> sets = sets(cmd);

        assertFalse(shown(sets, "#Price[0] #ChipIcon.Visible"), "a wallet never fills the item slot");
        assertFalse(sets.containsKey("#Price[0] #ChipIcon.Slots"));
        assertTrue(sets.containsKey("#Price[0] #ChipIconSlot #IcoTex.Visible"), "it draws as a plain picture");
        assertNotNull(sets.get("#Price[0] #ChipBox.TooltipText"), "hovering the chip's box names the wallet");
        assertTrue(sets.get("#Price[0] #ChipBox.TooltipText").contains("Hallow Sweets"));
        assertFalse(sets.containsKey("#Price[0].TooltipText"),
                "the root spans a stacked strip's whole line, so the name rides the box the player sees");

        assertFalse(sets.containsKey("#Price[1] #ChipBox.TooltipText"), "an item price keeps the item's own tooltip");
    }

    @Test
    void aRewardLineWhosePictureStandsForTheRewardDrawsItPlainWithTheRewardsNameOnHover() {
        UICommandBuilder cmd = new UICommandBuilder();
        CommerceChips.setRewardLine(cmd, "#R[0]", RewardChip.picture("No_Such_Item", Message.raw("+12 Hallow Sweets"),
                Message.raw("Hallow Sweets")), CommerceChips.COLOR_LINE);
        CommerceChips.setRewardLine(cmd, "#R[1]", RewardChip.of("No_Such_Item", Message.raw("x2 Stone")),
                CommerceChips.COLOR_LINE);
        Map<String, String> sets = sets(cmd);

        assertTrue(sets.get("#R[0] #LineText.TextSpans").contains("+12 Hallow Sweets"));
        assertFalse(shown(sets, "#R[0] #IcoItem.Visible"), "the wallet's picture is no item slot");
        assertTrue(sets.get("#R[0] #LineIconSlot.TooltipText").contains("Hallow Sweets"));

        assertFalse(sets.containsKey("#R[1] #LineIconSlot.TooltipText"), "an item reward's own tooltip is the point");
    }

    @Test
    void theChipCarriesAPlainPictureBesideItsItemSlotAndATooltipStyle() throws IOException {
        String ui = resource(CHIP_DOC);
        int slot = ui.indexOf("Group #ChipIconSlot");
        assertTrue(slot > 0);
        assertTrue(ui.indexOf("AssetImage #IcoTex", slot) > slot, "the plain picture sits in the chip's picture slot");
        assertTrue(ui.indexOf("ItemGrid #ChipIcon", slot) > slot, "the item slot stays for an item price");
        int box = ui.indexOf("Group #ChipBox");
        assertTrue(box > 0 && box < slot, "the drawn box holds the picture slot");
        assertTrue(ui.indexOf("TextTooltipStyle:", box) > box && ui.indexOf("TextTooltipStyle:", box) < slot,
                "a TooltipText with no TextTooltipStyle draws nothing, so the box carries the style");
    }

    @Test
    void theChipIsAsWideAsItsWords() throws IOException {
        String ui = resource(CHIP_DOC);
        int root = ui.indexOf("Group #ZigCommerceChip");
        int box = ui.indexOf("Group #ChipBox");
        int label = ui.indexOf("Label #ChipText");
        assertTrue(root >= 0 && box > root && label > box);
        assertFalse(ui.substring(root, box).contains("Width"), "the root fixes no width, so it hugs its box");
        String boxHead = ui.substring(box, ui.indexOf("Group #ChipIconSlot", box));
        assertFalse(boxHead.contains("Width"), "the box fixes no width, so it hugs its picture and words");
        String labelBody = ui.substring(label, ui.indexOf('}', label));
        assertFalse(labelBody.contains("FlexWeight"), "a flexing label would stretch the chip to its container");
        assertFalse(labelBody.contains("Width"), "the label is as wide as its line");
    }

    @Nonnull
    private static Map<String, String> sets(@Nonnull UICommandBuilder cmd) {
        Map<String, String> out = new HashMap<>();
        for (CustomUICommand command : cmd.getCommands()) {
            if (command.type == CustomUICommandType.Set) {
                out.put(command.selector, command.data);
            }
        }
        return out;
    }

    /** Whether a {@code .Visible} write said true; fails when it was never written. */
    private static boolean shown(@Nonnull Map<String, String> sets, @Nonnull String selector) {
        String data = sets.get(selector);
        assertNotNull(data, selector + " is painted");
        return data.contains("true");
    }

    @Nonnull
    private static String resource(@Nonnull String path) throws IOException {
        try (InputStream in = CommerceChipsPaintTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path + " ships");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
