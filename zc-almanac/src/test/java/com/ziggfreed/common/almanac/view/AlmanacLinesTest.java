package com.ziggfreed.common.almanac.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.time.MonthDay;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.view.AlmanacView.Scope;
import com.ziggfreed.common.almanac.view.AlmanacView.Season;
import com.ziggfreed.common.almanac.view.AlmanacView.Tally;
import com.ziggfreed.common.almanac.view.AlmanacView.Timing;

/**
 * What the Almanac says about a season's dates and numbers: which line a countdown reads ("On now - 27
 * days left", "Last day", "Returns in 23 days" up to 30 days, "Returns October 1" beyond), the window
 * line, the scope header and the tile captions. Every number binds typed and every year as text.
 */
class AlmanacLinesTest {

    private static final MonthDay OCT_1 = MonthDay.of(10, 1);
    private static final MonthDay NOV_3 = MonthDay.of(11, 3);

    private static Timing live(Integer daysLeft, boolean lastDay) {
        return new Timing(true, daysLeft, lastDay, null, false, OCT_1, NOV_3, LocalDate.of(2027, 10, 1), false);
    }

    private static Timing returning(Integer daysUntil, boolean startsLater) {
        return new Timing(false, null, false, daysUntil, daysUntil != null && daysUntil <= 14, OCT_1, NOV_3,
                LocalDate.of(2027, 10, 1), startsLater);
    }

    private static void assertKey(String key, Message message) {
        assertEquals(AlmanacText.PREFIX + key, message.getFormattedMessage().messageId);
    }

    private static void assertKey(String key, Message message, String why) {
        assertEquals(AlmanacText.PREFIX + key, message.getFormattedMessage().messageId, why);
    }

    private static long number(Message message, String param) {
        return ((LongParamValue) message.getFormattedMessage().params.get(param)).value;
    }

    @Test
    void onNowCountsDownTheDaysLeftAndSaysTheLastDay() {
        Message chip = AlmanacLines.chip(live(27, false));
        assertKey("chip.live", chip);
        assertEquals(27L, number(chip, "0"), "a typed number, so the plural and the grouping are the reader's");

        assertKey("chip.last_day", AlmanacLines.chip(live(1, true)));
        assertKey("status.live", AlmanacLines.chip(live(null, false)), "a forced run: on now, no count");
    }

    @Test
    void aReturnWithinThirtyDaysCountsDownAndOneFurtherOffNamesTheDay() {
        Message soon = AlmanacLines.chip(returning(23, false));
        assertKey("chip.returns_in", soon);
        assertEquals(23L, number(soon, "0"));
        assertKey("chip.returns_in", AlmanacLines.chip(returning(30, false)));

        Message far = AlmanacLines.chip(returning(31, false));
        assertKey("chip.returns_on", far);
        FormattedMessage formatted = far.getFormattedMessage();
        assertEquals(AlmanacText.PREFIX + "month.10", formatted.messageParams.get("0").messageId,
                "the month nests as a key, so each locale names it");
        assertEquals(1L, ((LongParamValue) formatted.params.get("1")).value);
    }

    @Test
    void aFirstRunAheadStartsAndASeasonWithNoNextRunIsBetweenSeasons() {
        Message starts = AlmanacLines.chip(returning(23, true));
        assertKey("chip.starts_in", starts);
        assertEquals(23L, number(starts, "0"));

        Timing nothingNext = new Timing(false, null, false, null, false, OCT_1, NOV_3, null, false);
        assertKey("chip.between", AlmanacLines.chip(nothingNext));
    }

    @Test
    void theWindowLineNamesBothDaysAndIsAbsentWithoutDates() {
        Message window = AlmanacLines.window(live(27, false));
        assertNotNull(window);
        assertKey("window", window);
        FormattedMessage formatted = window.getFormattedMessage();
        assertEquals(AlmanacText.PREFIX + "month.10", formatted.messageParams.get("0").messageId);
        assertEquals(1L, ((LongParamValue) formatted.params.get("1")).value);
        assertEquals(AlmanacText.PREFIX + "month.11", formatted.messageParams.get("2").messageId);
        assertEquals(3L, ((LongParamValue) formatted.params.get("3")).value);

        assertNull(AlmanacLines.window(new Timing(true, null, false, null, false, null, null, null, false)));
    }

    @Test
    void theScopeHeaderCarriesTheYearAsTextAndEverySeasonByItsOwnWords() {
        Message year = AlmanacLines.scopeHeader(new Scope(2026));
        assertKey("scope.year", year);
        assertEquals("2026", year.getFormattedMessage().messageParams.get("0").rawText,
                "a year is a label, never a quantity: no locale groups it");

        assertKey("scope.every", AlmanacLines.scopeHeader(Scope.EVERY));
    }

    @Test
    void theScopeMetaSaysWhetherThePlayerTookPart() {
        assertKey("scope.took_part", AlmanacLines.scopeMeta(new Scope(2026), true, 1L));
        assertKey("scope.not_yet", AlmanacLines.scopeMeta(new Scope(2026), false, 1L));
        Message every = AlmanacLines.scopeMeta(Scope.EVERY, true, 2L);
        assertKey("scope.taken_part_count", every);
        assertEquals(2L, number(every, "0"));
        assertKey("scope.not_yet", AlmanacLines.scopeMeta(Scope.EVERY, false, 0L));
    }

    @Test
    void aTilesCaptionAndServerLineAreTypedNumbersOrAbsent() {
        Tally year = new Tally("bombs_thrown", null, null, 5L, 1204L, 8431L);
        Message caption = AlmanacLines.tileCaption(year);
        assertNotNull(caption);
        assertKey("tile.in_all", caption);
        assertEquals(1204L, number(caption, "0"));
        Message server = AlmanacLines.tileServer(year);
        assertNotNull(server);
        assertKey("tile.server", server);
        assertEquals(8431L, number(server, "0"));

        Tally every = new Tally("bombs_thrown", null, null, 12L, null, null);
        assertNull(AlmanacLines.tileCaption(every));
        assertNull(AlmanacLines.tileServer(every));
    }

    @Test
    void theHeadlineNamesTheSeasonOnNow() {
        Message headline = AlmanacLines.headline(new Season("test_season", "almanac.test.title", null, null, true, 2026));

        assertKey("headline.live", headline);
        assertEquals("almanac.test.title", headline.getFormattedMessage().messageParams.get("0").messageId,
                "the season's own name nests, resolved in the reader's language");
    }

    @Test
    void monthsAreKeysLongAndShort() {
        assertKey("month.1", AlmanacLines.month(1));
        assertKey("month.short.12", AlmanacLines.shortMonth(12));
    }
}
