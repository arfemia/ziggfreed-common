package com.ziggfreed.common.objectives.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;
import com.ziggfreed.common.validation.TextKeyAudit;

/**
 * One case per code, each driven through the pure core with a fake lang catalogue and a handed-in
 * catalogue of achievements and quests; and the rules around the codes: a switched-off title is
 * skipped but still known to a reward, and every finding is filed under the {@code title} domain.
 */
class TitleValidatorTest {

    private static final Predicate<String> ALL_KEYS = key -> true;

    @Nonnull
    private static RewardSpec title(@Nonnull String id) {
        return RewardSpec.of(TitleRewardKind.KIND, "Title", id);
    }

    @Nonnull
    private static List<Finding> audit(@Nonnull List<TitleAsset> titles, @Nonnull List<Achievement> achievements,
            @Nonnull List<Quest> quests, @Nonnull Predicate<String> keyShipped) {
        return TitleValidator.audit(titles, achievements, quests, keyShipped);
    }

    @Nonnull
    private static List<Finding> audit(@Nonnull TitleAsset... titles) {
        return audit(List.of(titles), List.of(), List.of(), ALL_KEYS);
    }

    @Nonnull
    private static List<String> codes(@Nonnull List<Finding> findings) {
        List<String> out = new ArrayList<>();
        for (Finding finding : findings) {
            out.add(finding.code());
        }
        return out;
    }

    @Nonnull
    private static Finding only(@Nonnull List<Finding> findings, @Nonnull String code) {
        List<Finding> matching = findings.stream().filter(f -> f.code().equals(code)).toList();
        assertEquals(1, matching.size(), () -> "expected one " + code + " finding, got " + findings);
        return matching.get(0);
    }

    @Test
    void aNamedTitleTheCatalogueGrantsReportsNothing() throws IOException {
        TitleAsset keeper = TitleAssetTest.title(
                "{ \"Text\": { \"TitleKey\": \"title.keeper.name\", \"FlavorKey\": \"title.keeper.flavor\" } }",
                "Keeper_Of_Lanterns");
        Achievement lanterns = Achievement.builder("lantern_keeper").autoReward(title("Keeper_Of_Lanterns")).build();
        Quest watch = Quest.builder("night_watch").autoReward(RewardSpec.of("title", "TitleId", "keeper_of_lanterns"))
                .build();

        assertTrue(audit(List.of(keeper), List.of(lanterns), List.of(watch), ALL_KEYS).isEmpty());
    }

    @Test
    void anIdTheSaveFormatCannotHoldIsAnError() throws IOException {
        Finding finding = only(audit(TitleAssetTest.title("{}", "King|Of_Pumpkins")), TitleValidator.ID_UNSAVABLE);

        assertEquals(Severity.ERROR, finding.severity());
        assertEquals("king|of_pumpkins", finding.sourceId());
    }

    @Test
    void aTitleSwitchedOffIsSkipped() throws IOException {
        assertTrue(audit(TitleAssetTest.title("{ \"Enabled\": false }", "Old|Title")).isEmpty());
    }

    @Test
    void everyKeyTheFileNamesThatNoLangFileShipsIsAWarning() throws IOException {
        TitleAsset keeper = TitleAssetTest.title(
                "{ \"Text\": { \"TitleKey\": \"title.keepr.name\", \"FlavorKey\": \"title.keepr.flavor\" } }",
                "Keeper");

        List<Finding> findings = audit(List.of(keeper), List.of(), List.of(), "title.keeper.name"::equals);

        assertEquals(List.of(TextKeyAudit.UNKNOWN_TEXT_KEY, TextKeyAudit.UNKNOWN_TEXT_KEY), codes(findings),
                "the convention name key still names it, so the title is not unnamed");
        for (Finding finding : findings) {
            assertEquals(Severity.WARNING, finding.severity());
            assertEquals("keeper", finding.sourceId());
        }
    }

    @Test
    void aTitleNothingNamesReadsAsItsIdAndThatIsANote() throws IOException {
        TitleAsset bare = TitleAssetTest.title("{}", "Pumpkin_King");

        Finding unnamed = only(audit(List.of(bare), List.of(), List.of(), key -> false), TitleValidator.UNNAMED_TITLE);
        assertEquals(Severity.INFO, unnamed.severity());

        assertTrue(audit(List.of(bare), List.of(), List.of(), "title.pumpkin_king.name"::equals).isEmpty(),
                "the convention key names it");
        assertTrue(audit(List.of(TitleAssetTest.title("{ \"Text\": { \"DisplayName\": \"Pumpkin King\" } }",
                "Pumpkin_King")), List.of(), List.of(), key -> false).isEmpty(), "a typed name names it");
    }

    @Test
    void aRewardNamingATitleNoFileDefinesIsAWarningAtTheContentThatPaysIt() throws IOException {
        TitleAsset keeper = TitleAssetTest.title("{}", "Keeper");
        Achievement typo = Achievement.builder("lantern_keeper").claimReward(title("Keepr")).build();
        Quest alsoTypo = Quest.builder("night_watch").claimRewards(List.of(title("Watchman"))).build();

        List<Finding> findings = audit(List.of(keeper), List.of(typo), List.of(alsoTypo), ALL_KEYS);

        assertEquals(List.of(TitleValidator.UNKNOWN_TITLE_REWARD, TitleValidator.UNKNOWN_TITLE_REWARD),
                codes(findings));
        assertEquals("lantern_keeper", findings.get(0).sourceId());
        assertTrue(findings.get(0).message().contains("'Keepr'"), findings.get(0).message());
        assertEquals("night_watch", findings.get(1).sourceId());
        assertEquals(Severity.WARNING, findings.get(1).severity());
    }

    @Test
    void aRewardNamingASwitchedOffTitleIsNotUnknown() throws IOException {
        TitleAsset off = TitleAssetTest.title("{ \"Enabled\": false }", "Retired");
        Achievement grants = Achievement.builder("old_hand").autoReward(title("RETIRED")).build();

        assertTrue(audit(List.of(off), List.of(grants), List.of(), ALL_KEYS).isEmpty());
    }

    @Test
    void aRewardTheGrantWouldRefuseIsAnError() {
        Achievement blank = Achievement.builder("nameless").autoReward(RewardSpec.of(TitleRewardKind.KIND)).build();
        Quest reserved = Quest.builder("odd_one").autoReward(title("Odd:One")).build();

        List<Finding> findings = audit(List.of(), List.of(blank), List.of(reserved), ALL_KEYS);

        assertEquals(List.of(TitleValidator.UNUSABLE_TITLE_REWARD, TitleValidator.UNUSABLE_TITLE_REWARD),
                codes(findings));
        assertEquals(Severity.ERROR, findings.get(0).severity());
        assertEquals("nameless", findings.get(0).sourceId());
        assertEquals("odd_one", findings.get(1).sourceId());
    }

    @Test
    void everyFindingIsFiledUnderTheTitleDomain() throws IOException {
        List<Finding> findings = audit(List.of(TitleAssetTest.title("{ \"Text\": { \"TitleKey\": \"nope\" } }", "A:B")),
                List.of(Achievement.builder("x").autoReward(title("Missing")).build()), List.of(), key -> false);

        assertTrue(findings.size() >= 4, "plenty is wrong here: " + findings);
        for (Finding finding : findings) {
            assertEquals(TitleValidator.DOMAIN, finding.domain());
        }
    }
}
