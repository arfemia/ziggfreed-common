package com.ziggfreed.common.objectives.questlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * A character hosting boards, as the two pure rules read it: the boards naming the character in {@code Hosts} join
 * what it answers to (so what was taken at them lists, hands in and collects at the character), and a contract two
 * of its boards post lists once, under the board that lists first.
 */
class HostedBoardsTest {

    @Test
    void aHostAnswersToEveryBoardNamingItAndOnlyThose() {
        Map<String, List<String>> hostsByBoard = new LinkedHashMap<>();
        hostsByBoard.put("jack_daily", List.of("Old_Jack"));
        hostsByBoard.put("jack_errands", List.of("someone_else", "old_jack"));
        hostsByBoard.put("town_board", List.of());

        Set<String> answers = HostedBoards.answerSet(Set.of("Old_Jack"), hostsByBoard);

        assertEquals(Set.of("Old_Jack", "jack_daily", "jack_errands"), answers,
                "its own id, and each board whose Hosts names it however either is capitalized");
        assertEquals(Set.of("stranger"), HostedBoards.answerSet(Set.of("stranger"), hostsByBoard),
                "a character no board names answers to its own ids alone");
    }

    @Test
    void aContractTwoBoardsPostListsOnceUnderTheFirstBoard() {
        Map<String, List<String>> posted = new LinkedHashMap<>();
        posted.put("jack_daily", List.of("Souls", "pumpkins"));
        posted.put("jack_errands", List.of("souls", "geodes"));

        Map<String, String> first = HostedBoards.firstPoster(List.copyOf(posted.keySet()), posted::get);

        assertEquals(List.of("souls", "pumpkins", "geodes"), List.copyOf(first.keySet()),
                "each contract once, in the order the boards list them");
        assertEquals("jack_daily", first.get("souls"), "the twice-posted contract sits under the first board");
        assertTrue(first.values().containsAll(List.of("jack_daily", "jack_errands")));
    }
}
