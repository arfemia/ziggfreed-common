package com.ziggfreed.common.objectives.book.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.book.BookActions;
import com.ziggfreed.common.ui.kit.DetailToggle;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.Picture;

/**
 * The achievement page's Pin toggle says what it does on hover (smoke 80), in vanilla's attested shape: the kit's
 * toggle button carries the shared {@code TextTooltipStyle} (a tooltip with no style draws nothing), the tab's page is
 * that kit page, and the toggle the tab paints carries its tooltip from a shipped key, Pin or Unpin by its state.
 * Ported from the retired {@code BookTooltipsTest}, whose row glyph left with the row's second click target.
 */
class AchievementPinTooltipTest {

    private static final Path KIT = Path.of("..", "zc-presentation", "src", "main", "resources", "Common", "UI",
            "Custom", "Common", "ZigKit.ui");
    private static final Path DOCUMENT = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages",
            "ZigBookAchievements.ui");
    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.progression.lang");

    @Test
    void theKitsToggleCarriesTheSharedTooltipStyle() throws IOException {
        String kit = ZigBookAchievementsDocumentTest.code(KIT);
        assertTrue(kit.contains("$C = \"../Common.ui\";"), "ZigKit.ui imports Common.ui as $C");
        String toggle = ZigBookAchievementsDocumentTest.own(ZigBookAchievementsDocumentTest.block(kit,
                "Button #DToggle"));
        assertTrue(toggle.contains("TextTooltipStyle: $C.@DefaultTextTooltipStyle;"),
                "the page's toggle gives its tooltip the shared style");
    }

    @Test
    void theTabsPageIsTheKitsDetailPage() throws IOException {
        String ui = ZigBookAchievementsDocumentTest.code(DOCUMENT);
        assertTrue(ui.contains("$ZW = \"../Common/ZigKit.ui\";"));
        assertTrue(ui.contains("$ZW.@ZigDetailPage " + AchievementsTab.PAGE + " {"));
    }

    @Test
    void aPinToggleSaysPinOrUnpin() {
        DetailView off = AchievementsTab.withPinTooltip(page(new DetailToggle(Msg.raw("Pin"), false,
                BookActions.PIN, null)));
        assertNotNull(off);
        assertEquals("ziggfreedcommon.progression.book.tooltip.pin", tooltipKey(off));

        DetailView on = AchievementsTab.withPinTooltip(page(new DetailToggle(Msg.raw("Unpin"), true,
                BookActions.PIN, Msg.raw("stale words"))));
        assertNotNull(on);
        assertEquals("ziggfreedcommon.progression.book.tooltip.unpin", tooltipKey(on));
        assertEquals(Boolean.TRUE, on.toggle().on(), "the toggle keeps its state");
        assertEquals(BookActions.PIN, on.toggle().actionId());
    }

    @Test
    void anythingButAPinToggleIsLeftAlone() {
        DetailView none = page(null);
        assertSame(none, AchievementsTab.withPinTooltip(none));
        DetailView other = page(new DetailToggle(Msg.raw("Track"), false, BookActions.TRACK, null));
        assertSame(other, AchievementsTab.withPinTooltip(other));
        assertNull(AchievementsTab.withPinTooltip(null));
    }

    @Test
    void bothTooltipKeysShip() throws IOException {
        String english = Files.readString(ENGLISH, StandardCharsets.UTF_8);
        for (String key : List.of("book.tooltip.pin", "book.tooltip.unpin")) {
            assertTrue(english.lines().anyMatch(line -> line.startsWith(key + " =")),
                    "ziggfreedcommon.progression.lang must author '" + key + "'");
        }
    }

    @Nonnull
    private static DetailView page(@Nullable DetailToggle toggle) {
        return new DetailView(Picture.NONE, Msg.raw("Ghoul Breaker 2026"), null, null, List.of(), toggle, null, null,
                null, List.of(), List.of(), null);
    }

    @Nonnull
    private static String tooltipKey(@Nonnull DetailView view) {
        assertNotNull(view.toggle());
        assertNotNull(view.toggle().tooltip());
        return view.toggle().tooltip().getMessageId();
    }
}
