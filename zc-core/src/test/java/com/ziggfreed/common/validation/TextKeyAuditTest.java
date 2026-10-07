package com.ziggfreed.common.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.LangCatalog;

/**
 * The one check every content validator makes of a key its file names: a key no loaded lang file
 * ships is a WARNING naming the leaf and what a player reads instead, a blank key is no key, and with
 * no catalogue loaded nothing can be told, so nothing is reported.
 */
class TextKeyAuditTest {

    @BeforeEach
    @AfterEach
    void realCatalogue() {
        LangCatalog.overrideForTests(null);
    }

    private static List<Finding> check(String key, Predicate<String> shipped) {
        List<Finding> out = new ArrayList<>();
        TextKeyAudit.check(out, "calendar", "spring_fair", "the calendar event 'spring_fair' Herald.Start.TitleKey",
                key, shipped, "the banner shows the raw key");
        return out;
    }

    @Test
    void aKeyNoLoadedFileShipsIsAWarningNamingTheLeafAndTheCost() {
        List<Finding> out = check("calendar.spring_fair.start", key -> false);

        assertEquals(1, out.size());
        Finding finding = out.get(0);
        assertEquals(Severity.WARNING, finding.severity());
        assertEquals(TextKeyAudit.UNKNOWN_TEXT_KEY, finding.code());
        assertEquals("calendar", finding.domain());
        assertEquals("spring_fair", finding.sourceId());
        assertTrue(finding.message().contains("Herald.Start.TitleKey"), finding.message());
        assertTrue(finding.message().contains("'calendar.spring_fair.start'"), finding.message());
        assertTrue(finding.message().endsWith("the banner shows the raw key"), finding.message());
    }

    @Test
    void aShippedKeyAndABlankKeyReportNothing() {
        assertTrue(check("calendar.spring_fair.start", key -> true).isEmpty());
        assertTrue(check(null, key -> false).isEmpty());
        assertTrue(check("   ", key -> false).isEmpty());
    }

    @Test
    void theKeyIsAskedTrimmedAndAThrowingAnswerIsNotMissing() {
        assertTrue(check("  calendar.spring_fair.start ", "calendar.spring_fair.start"::equals).isEmpty());
        assertTrue(check("calendar.spring_fair.start", key -> {
            throw new IllegalStateException("no catalogue");
        }).isEmpty());
    }

    @Test
    void theLiveCatalogueAnswersAKeyUnderAnyLoadedNamespace() {
        LangCatalog.overrideForTests(Map.of("springpack.calendar.spring_fair.name", "Spring Fair"));

        Predicate<String> shipped = TextKeyAudit.liveCatalogue();

        assertTrue(shipped.test("calendar.spring_fair.name"));
        assertTrue(shipped.test("springpack.calendar.spring_fair.name"));
        assertFalse(shipped.test("calendar.spring_fair.flavor"));
    }

    @Test
    void withNoCatalogueLoadedEveryKeyReadsAsShipped() {
        LangCatalog.overrideForTests(Map.of());

        assertTrue(TextKeyAudit.liveCatalogue().test("calendar.spring_fair.flavor"));
    }
}
