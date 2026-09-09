package com.ziggfreed.common.ui.hud.bar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.icon.IconSpec;

/**
 * How a row settles its look with nothing authored, how an override wins leaf by leaf over what the
 * reporting mod said, and how an item nobody has ever authored dresses its own row. The paint
 * itself is packets over the engine's {@code UICommandBuilder}, whose static init reaches the item
 * asset store and cannot stand in a unit JVM, so the two row shapes it draws are validated in game.
 */
class HudBarLookTest {

    @AfterEach
    void clearFold() {
        HudBarConfig.getInstance().mergePackLayer(Map.of());
        HudBarConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    @Test
    void withNothingAuthoredARowReadsExactlyWhatItsModSaid() {
        Message name = Msg.raw("Wood");
        IconSpec icon = IconSpec.ofItem("Tool_Hatchet_Crude");
        HudBarLook look = HudBarLook.resolve("mymod:wood", null, HudBarDisplay.of(name, icon, "#6fbf73", 20));

        assertSame(name, look.label());
        assertSame(icon, look.icon());
        assertEquals("#6fbf73", look.color());
        assertEquals(20, look.order());
        assertEquals(HudBarLook.DEFAULT_LINGER_MS, look.lingerMs(), "a display that named no linger reads the default");
    }

    @Test
    void aModThatSaidNothingGetsTheDefaultsAndItsOwnIdAsAName() {
        HudBarLook look = HudBarLook.resolve("mymod:wood", null, HudBarDisplay.NONE);

        assertEquals("mymod:wood", look.label().getRawText(), "an unnamed row is still a row");
        assertNull(look.icon());
        assertEquals(HudBarLook.DEFAULT_COLOR, look.color());
        assertEquals(HudBarLook.DEFAULT_ORDER, look.order());
        assertEquals(HudBarLook.DEFAULT_LINGER_MS, look.lingerMs());
    }

    @Test
    void anOverrideWinsLeafByLeafAndLeavesTheRestToTheMod() throws Exception {
        HudBarAsset override = HudBarAssetCodecTest.bar(
                "{ \"Source\": \"mymod:wood\", \"Color\": \"#ffffff\", \"LingerMs\": 9000 }", "wood", null, null);
        Message name = Msg.raw("Wood");
        IconSpec icon = IconSpec.ofItem("Tool_Hatchet_Crude");
        HudBarLook look = HudBarLook.resolve("mymod:wood", override, HudBarDisplay.of(name, icon, "#6fbf73", 20));

        assertEquals("#ffffff", look.color(), "the authored leaf wins");
        assertEquals(9000L, look.lingerMs());
        assertSame(name, look.label(), "an unauthored leaf keeps the mod's");
        assertSame(icon, look.icon());
        assertEquals(20, look.order());
    }

    @Test
    void aDisplayFoldsOverAnotherPartByPart() {
        HudBarDisplay under = HudBarDisplay.of(Msg.raw("Under"), IconSpec.ofItem("A"), "#111111", 5);
        HudBarDisplay over = new HudBarDisplay(null, null, "#222222", null, 700L);

        HudBarDisplay folded = over.over(under);
        assertEquals("Under", folded.label().getRawText());
        assertEquals("A", folded.icon().itemId());
        assertEquals("#222222", folded.color());
        assertEquals(5, folded.order());
        assertEquals(700L, folded.lingerMs());
    }

    @Test
    void anItemNobodyAuthoredDressesItsOwnRow() {
        // No asset store and no lang catalog stand in a unit JVM, so the name falls through every
        // rung to the prettified id; in game the same call lands on the item's own name key.
        HudBarDisplay display = HudBarDisplay.forItem("Weird_Unseen_Thing");
        HudBarLook look = HudBarLook.resolve(HudBars.itemRowId("Weird_Unseen_Thing"), null, display);

        assertEquals("Weird Unseen Thing", look.label().getRawText(), "named from the id itself");
        assertNotNull(look.icon());
        assertEquals("Weird_Unseen_Thing", look.icon().itemId(), "pictured by the id itself");
        assertEquals(HudBarLook.DEFAULT_ORDER, look.order(), "sorts after every row that named a place");
    }
}
