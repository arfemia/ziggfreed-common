package com.ziggfreed.common.ui.toast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;

/**
 * Where a toast raised outside a page goes: drawn into the page the player has open, since the corner
 * feed sits hidden behind it, and otherwise into the corner feed, one notice per row (the feed has no
 * rows of its own), never more than the feed can show beside everything else.
 */
class ToastDeliveryTest {

    private static ToastSpec receipt(int rows) {
        List<ToastLine> lines = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            lines.add(i == 0 ? ToastLine.item("Test_Sweets", 1, Msg.raw("+12 Test Sweets"))
                    : ToastLine.text(Msg.raw("row " + i)));
        }
        return ToastSpec.of(ToastKind.REWARD, Msg.raw("Rewards received.")).withLines(lines);
    }

    @Test
    void withAPageOpenTheWholeToastIsDrawnIntoIt() {
        ToastSpec spec = receipt(2);
        List<ToastSpec> page = new ArrayList<>();
        List<ToastLine> corner = new ArrayList<>();

        ToastDelivery.route(true, spec, null, page::add, corner::add);

        assertEquals(1, page.size());
        assertSame(spec, page.get(0), "the page draws the headline and every row");
        assertTrue(corner.isEmpty(), "a page covers the corner feed, so nothing goes there");
    }

    @Test
    void withNoPageOpenEachRowBecomesItsOwnCornerNotice() {
        List<ToastSpec> page = new ArrayList<>();
        List<ToastLine> corner = new ArrayList<>();

        ToastDelivery.route(false, receipt(2), null, page::add, corner::add);

        assertTrue(page.isEmpty());
        assertEquals(2, corner.size(), "the feed has no rows, so each row is a notice of its own");
        assertEquals("Test_Sweets", corner.get(0).iconItemId(), "a row keeps its picture");
        assertEquals("row 1", corner.get(1).text().getFormattedMessage().rawText);
    }

    @Test
    void aToastWithNoRowsReachesTheCornerAsItsHeadline() {
        List<ToastLine> corner = new ArrayList<>();
        ToastDelivery.route(false, ToastSpec.of(ToastKind.INFO, Msg.raw("Done.")).withIcon("Test_Icon"), null,
                spec -> { }, corner::add);

        assertEquals(1, corner.size());
        assertEquals("Done.", corner.get(0).text().getFormattedMessage().rawText);
        assertEquals("Test_Icon", corner.get(0).iconItemId());
    }

    @Test
    void theCornerNeverFillsTheFeedAloneAndSaysHowManyMore() {
        int total = ToastDelivery.FEED_ROWS + 4;
        List<ToastLine> corner = new ArrayList<>();
        ToastDelivery.route(false, receipt(total), dropped -> Msg.raw("+" + dropped + " more"), spec -> { },
                corner::add);

        assertTrue(ToastDelivery.FEED_ROWS < 7, "the feed shows seven lines");
        assertEquals(ToastDelivery.FEED_ROWS, corner.size());
        assertEquals("+" + (total - (ToastDelivery.FEED_ROWS - 1)) + " more",
                corner.get(ToastDelivery.FEED_ROWS - 1).text().getFormattedMessage().rawText,
                "the last notice says what did not fit rather than dropping it unsaid");

        List<ToastLine> bare = new ArrayList<>();
        ToastDelivery.route(false, receipt(total), null, spec -> { }, bare::add);
        assertEquals(ToastDelivery.FEED_ROWS, bare.size(), "with no overflow line the rest is cut");
    }
}
