package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/** Each kind's convention keys keep its shipped suffixes, and a name that resolves nowhere is reported. */
class ConventionKeysTest {

    @AfterEach
    void realCatalogue() {
        LangCatalog.overrideForTests(null);
    }

    @Test
    void eachKindKeepsItsShippedSuffixes() {
        assertEquals("achievement.lantern_keepsake.title", ConventionKeys.achievementTitle("lantern_keepsake"));
        assertEquals("achievement.lantern_keepsake.desc", ConventionKeys.achievementDescription("lantern_keepsake"),
                "an achievement's line under its title is .desc, never .flavor");
        assertEquals("quest.old_errand.title", ConventionKeys.questTitle("old_errand"));
        assertEquals("quest.old_errand.flavor", ConventionKeys.questFlavor("old_errand"));
        assertNull(ConventionKeys.questTitle(" "), "content with no id has no convention");
    }

    @Test
    void aNameThatResolvesNowhereIsAWarningOnlyOnceACatalogueIsLoaded() {
        ContentText text = ContentText.builder()
                .titleKey("quest.typo.title").titleConventionKey("quest.errand.title").build();
        assertNull(ConventionKeys.unresolvedTitle("quest", "errand", text),
                "no catalogue loaded: nothing can be judged");

        LangCatalog.overrideForTests(Map.of("somepack.unrelated.key", "x"));
        Finding finding = ConventionKeys.unresolvedTitle("quest", "errand", text);
        assertNotNull(finding);
        assertEquals(Severity.WARNING, finding.severity());
        assertEquals(ConventionKeys.UNRESOLVED_TITLE, finding.code());
        assertEquals("errand", finding.sourceId());
        assertTrue(finding.message().contains("quest.typo.title") && finding.message().contains("quest.errand.title"),
                finding.message());

        LangCatalog.overrideForTests(Map.of("somepack.quest.errand.title", "Errand"));
        assertNull(ConventionKeys.unresolvedTitle("quest", "errand", text), "the convention key ships");
        LangCatalog.overrideForTests(Map.of("somepack.quest.typo.title", "Typo"));
        assertNull(ConventionKeys.unresolvedTitle("quest", "errand", text), "the explicit key ships");
    }

    @Test
    void contentWithABlankIdIsReportedAsHavingNothingToBeNamedBy() {
        ContentText text = ContentText.builder().titleConventionKey(ConventionKeys.questTitle(" ")).build();
        assertNull(text.titleConventionKey(), "a blank id derives no convention key");

        LangCatalog.overrideForTests(Map.of("somepack.unrelated.key", "x"));
        Finding finding = ConventionKeys.unresolvedTitle("quest", " ", text);
        assertNotNull(finding);
        assertTrue(finding.message().contains("no id to be named by"), finding.message());
        assertTrue(finding.message().contains("ship a key"), "with no convention to name, it asks for any key");
    }

    @Test
    void anAuditThatFoldsToReadTheNameLosesOnlyThatFindingWhenTheFoldThrows() {
        ContentText unnamed = ContentText.builder().titleConventionKey("quest.errand.title").build();
        AtomicBoolean folded = new AtomicBoolean();
        assertNull(ConventionKeys.unresolvedTitle("board", "errand", () -> {
            folded.set(true);
            return unnamed;
        }), "no catalogue loaded: nothing can be judged");
        assertFalse(folded.get(), "so the fold is never even run");

        LangCatalog.overrideForTests(Map.of("somepack.unrelated.key", "x"));
        assertNull(ConventionKeys.unresolvedTitle("board", "broken", () -> {
            throw new IllegalStateException("a malformed contract");
        }), "a fold that throws costs its own name check, never the audit");
        Finding finding = ConventionKeys.unresolvedTitle("board", "errand", () -> unnamed);
        assertNotNull(finding, "a fold that answers is judged exactly as its text would be");
        assertEquals("errand", finding.sourceId());
    }
}
