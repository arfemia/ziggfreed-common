package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.achievement.FirstClaims;
import com.ziggfreed.common.board.asset.BoardAssetStore;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.commerce.fold.CommerceDefaults;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.runtime.ProgressionGates;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTextSource;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestTurnInSite;
import com.ziggfreed.common.subject.Subject;

/**
 * A seam that SHIPS is a seam that is FILLED.
 *
 * <p>Every seam in this library degrades to "does nothing" when nobody fills it, which is right for
 * a consumer that genuinely has no opinion and wrong for a seam the library declared and then never
 * answered itself. Such a gap never fails a build and never fails a boot: it fails a player, later,
 * invisibly - an achievement nothing gates, a quest list that names nothing, an NPC with nothing to
 * say. This walks the registration state after the library's own defaults have run and names any
 * seam still standing empty.
 *
 * <p>The rule it enforces: an interface declared here ships with a production default in its own
 * module or a root fill in the SAME change. A seam deliberately kept empty for a future caller is
 * deleted, not kept.
 *
 * <p><b>What this cannot reach, and what covers it instead.</b> A seam the WIRING ROOT fills at
 * plugin setup rather than one these defaults register - the dialogue memory backend above all - is
 * out of scope here, because standing the plugin up is what would be needed to observe it and that
 * is not a unit test. Such a seam REPORTS ON ITSELF instead, from inside the module that declared
 * it, the first time it has to fall back: {@code DialogueMemories.persistentBackendOrWarn} says once
 * that a memory declared to outlive a restart is only going to outlive a login, and
 * {@code FirstClaims.store} says once that server-first winners are being kept in a table that dies
 * with the process. Both are pinned by tests in their own modules. Add a report of that shape for a
 * root-filled seam; never add an assertion here that cannot fail.
 */
class SeamsFilledTest {

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        NpcOfferProviders.clear();
        ProgressionDefaults.reset();
        ProgressionGates.resetForTests();
        FirstClaims.resetForTests();
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
        NpcOfferProviders.clear();
        ProgressionDefaults.reset();
        ProgressionGates.resetForTests();
        FirstClaims.resetForTests();
    }

    /**
     * Both engines, not one. An achievement gate is the seam this test exists for: the library
     * shipped a quest-side default for a year while the achievement side had none, so a server
     * without a consumer had every achievement on it completely ungated - no availability check, no
     * requirement block, no arbitration of a one-winner - and nothing anywhere said so.
     */
    @Test
    void bothProgressionEnginesHaveAGate() {
        ProgressionDefaults.register();

        assertFalse(ProgressionRuntime.questGateOwners().isEmpty(),
                "a quest's Requires block needs somebody to answer it");
        assertFalse(ProgressionRuntime.achievementGateOwners().isEmpty(),
                "an achievement's Requires block needs somebody to answer it too, or every"
                        + " achievement on a bare server is open to everyone");
    }

    /** A merged catalogue with no text source renders every row blank. */
    @Test
    void contentCanBeNamed() {
        ProgressionDefaults.register();

        List<ProgressionTextSource> sources = ProgressionRuntime.textSources();
        assertFalse(sources.isEmpty(),
                "a surface walking the shared catalogue has to be able to name what is in it");
    }

    /**
     * "Have you anything for me" was a question with a seam and no production answer: the only
     * registrations anywhere were test fixtures, so a grep said yes while every server said nothing.
     */
    @Test
    void aCharacterCanBeAskedWhatItIsHoldingOut() {
        ProgressionDefaults.register();

        assertTrue(NpcOfferProviders.hasAny(),
                "an offer provider registered only by a test fixture is not a filled seam");
    }

    /**
     * The library keeps its own claim table, so a server-first is arbitrated with no consumer.
     *
     * <p>It ARBITRATES rather than merely existing, which is the only version of this worth
     * asserting: a table nobody installed still has to hand the one-off to exactly one subject and
     * refuse the next, or a server-first is a server-first in name only.
     */
    @Test
    void aServerFirstIsArbitratedWithNoConsumerInstalled() {
        ProgressionDefaults.register();

        assertTrue(FirstClaims.isDefault(),
                "with no consumer installed, the library's own claim table must be the active one");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(FirstClaims.store().tryClaim("the_one_off", first, "first"),
                "a one-winner achievement needs a table even on a bare server");
        assertFalse(FirstClaims.store().tryClaim("the_one_off", second, "second"),
                "and a second claimant must be refused, or everybody who finishes it wins it");
        assertTrue(FirstClaims.store().tryClaim("the_one_off", first, "first"),
                "the winner asking again is still the winner, so a re-check cannot burn the claim");
    }

    /** Player state has somewhere to live, or every write on a bare server is dropped. */
    @Test
    void progressHasAStore() {
        ProgressionDefaults.register();

        assertTrue(ProgressionRuntime.usesDefaultStores(),
                "with no consumer registered, the library's own store must be the active one");
    }

    /**
     * "Is the place this quest is collected at still there" ({@code QuestGates.siteStillThere}) has a
     * library answer for boards: a contract is collected at the board it was taken from, so without
     * one, a board taken down or out of its season strands every contract still waiting there. Walked
     * through the live engine: taken at a board nothing loads, the contract collects anywhere, while a
     * quest no board posts still waits for its site.
     */
    @Test
    void aContractWhoseBoardIsGoneCollectsAnywhere() throws IOException {
        ProgressionDefaults.register();
        CommerceDefaults.registerQuestSites();
        ProgressionRuntime.registrar("seams-filled-test").questStore(new InMemoryQuestProgressStore());
        BoardAssetStore.getInstance().merge(Map.of("yourmod_hunt", BountyAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString("{ }"), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(BountyAsset.class, "yourmod_hunt", null)))));
        try {
            Quest contract = Quest.builder("yourmod_hunt").turnInAt(QuestTurnInSite.ACCEPT_SITE).build();
            Quest errand = Quest.builder("yourmod_errand").turnInAt(QuestTurnInSite.ACCEPT_SITE).build();
            ProgressionRuntime.publishQuests("seams-filled-test", List.of(contract, errand));
            QuestEngine engine = ProgressionRuntime.quests();
            Subject player = Subject.of(UUID.randomUUID(), "tester");
            engine.accept(player, contract, "yourmod_gone_board");
            engine.accept(player, errand, "yourmod_gone_board");

            assertTrue(engine.canCompleteAt(player, contract, null),
                    "a contract whose board is gone is collected anywhere");
            assertFalse(engine.canCompleteAt(player, errand, null),
                    "a quest no board posts still waits for the place it was taken");
        } finally {
            BoardAssetStore.getInstance().merge(Map.of());
        }
    }
}
