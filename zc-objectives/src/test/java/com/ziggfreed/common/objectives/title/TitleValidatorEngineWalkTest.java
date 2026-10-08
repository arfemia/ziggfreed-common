package com.ziggfreed.common.objectives.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.validation.Finding;

/**
 * The engine walk's two entry points against the shared runtime. {@code audit()} never builds the runtime,
 * so before anything has read the engines it asks no catalogue and builds nothing; {@code auditForcingBuild()},
 * the walk zc's boot audit runs at the boot event, builds the runtime on the spot and checks every
 * {@code Title} reward the catalogues pay, so a boot audit that runs before any engine read still sees a
 * reward naming a title no file defines.
 */
class TitleValidatorEngineWalkTest {

    private static final String CONSUMER = "yourmod";

    @BeforeEach
    @AfterEach
    void reset() {
        ProgressionRuntime.resetForTests();
        TitleConfig.getInstance().mergePackLayer(Map.of());
        LangCatalog.overrideForTests(null);
    }

    @Nonnull
    private static RewardSpec title(@Nonnull String id) {
        return RewardSpec.of(TitleRewardKind.KIND, "Title", id);
    }

    /** A catalogue that pays two titles no file defines, published before anything builds the runtime. */
    private static void publishTypos() {
        ProgressionRuntime.publishAchievements(CONSUMER,
                List.of(Achievement.builder("lantern_keeper").claimReward(title("Keepr")).build()));
        ProgressionRuntime.publishQuests(CONSUMER,
                List.of(Quest.builder("night_watch").claimRewards(List.of(title("Watchman"))).build()));
    }

    @Nonnull
    private static List<String> rewardFindings(@Nonnull List<Finding> findings) {
        return findings.stream()
                .filter(f -> f.code().equals(TitleValidator.UNKNOWN_TITLE_REWARD))
                .map(Finding::sourceId)
                .toList();
    }

    @Test
    void thePlainWalkNeverBuildsTheRuntimeSoBeforeTheBuildItAsksNoCatalogue() {
        publishTypos();

        List<Finding> found = TitleValidator.audit();

        assertEquals(List.of(), rewardFindings(found));
        assertFalse(ProgressionRuntime.isBuilt(), "a caller that may run during setup never seals the runtime early");
    }

    @Test
    void theBootWalkBuildsTheRuntimeAndChecksEveryTitleRewardTheCataloguesPay() {
        publishTypos();
        assertFalse(ProgressionRuntime.isBuilt(), "nothing has read the engines yet");

        List<Finding> found = TitleValidator.auditForcingBuild();

        assertTrue(ProgressionRuntime.isBuilt(), "the boot walk builds the runtime rather than skipping the catalogues");
        assertEquals(List.of("lantern_keeper", "night_watch"), rewardFindings(found),
                "a reward naming a title no file defines is reported at the content that pays it");
    }
}
