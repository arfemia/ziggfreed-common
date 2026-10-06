package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.reputation.ReputationLadder.Rank;

/** A reputation is named by its key, else its id; a rank by the reputation's own name, the library's, its id. */
class ReputationTextTest {

    @AfterEach
    void clear() {
        ReputationFixtures.reset();
    }

    private static ReputationDef def(String json) {
        return new ReputationDef(ReputationFixtures.OLD_JACK, 0,
                json == null ? null : ReputationFixtures.companion(ReputationFixtures.OLD_JACK, json));
    }

    @Test
    void aReputationIsNamedByItsKeyElseByItsId() {
        assertEquals("test.jack.name",
                ReputationText.name(def("{ \"Text\": { \"TitleKey\": \"test.jack.name\" } }")).getMessageId());
        assertEquals("Test_Old_Jack", ReputationText.name(def(null)).getRawText());
        assertNull(ReputationText.description(def(null)));
        assertEquals("test.jack.desc",
                ReputationText.description(def("{ \"Text\": { \"FlavorKey\": \"test.jack.desc\" } }")).getMessageId());
    }

    @Test
    void aRankReadsTheReputationsOwnNameThenTheLibrarysThenItsId() {
        ReputationDef jack = def("{ \"Ranks\": { \"Friendly\": { \"Name\": \"test.jack.rank.regular\" } } }");
        assertEquals("test.jack.rank.regular",
                ReputationText.rankName(jack, new Rank("Friendly", 1_000, 3_000)).getMessageId());
        assertEquals("ziggfreedcommon.reputation.rank.honored",
                ReputationText.rankName(jack, new Rank("Honored", 3_000, 9_000)).getMessageId());
        assertEquals("Champion", ReputationText.rankName(jack, new Rank("Champion", 9_000, 10_000)).getRawText(),
                "another mod's rank reads as its id");
        assertEquals("ziggfreedcommon.reputation.rank.none", ReputationText.rankName(jack, null).getMessageId());
    }
}
