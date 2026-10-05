package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;
import com.ziggfreed.common.i18n.Msg;

/**
 * A boss fight's records read back from the listener's own board: the axes the binding row's split
 * asks for, the tabs the recorded rows hold, the keys a selection composes being exactly the ones the
 * listener wrote, a stale or foreign key left out, a layout named after the bucket naming the tabs,
 * and only fights with rows offered. Every row here is written by the listener itself.
 */
class EncounterBoardsTest {

    private static final EncounterLeaderboardMessages TEXT = new EncounterLeaderboardMessages(null);

    @AfterEach
    void tearDown() {
        LeaderboardLayoutConfig.getInstance().loadDefaults(Map.of());
    }

    @Nonnull
    private static EncounterBindingAsset row(@Nonnull String id, @Nonnull String json) throws IOException {
        return EncounterBindingAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(EncounterBindingAsset.class, id, null)));
    }

    @Nonnull
    private static EncounterBindingAsset.Leaderboard group(@Nonnull String json) throws IOException {
        EncounterBindingAsset.Leaderboard group = row("Boss", "{ \"Leaderboard\": " + json + " }").getLeaderboard();
        assertNotNull(group, "the fixture decodes a group");
        return group;
    }

    /** A defeat fought by {@code party} fresh players on {@code difficulty}, written by the listener. */
    private static void defeat(@Nonnull Leaderboard board, @Nonnull EncounterBindingAsset.Leaderboard group, int party,
            @Nullable String difficulty) {
        List<ParticipantShare> shares = new ArrayList<>();
        for (int i = 0; i < party; i++) {
            shares.add(new ParticipantShare(UUID.randomUUID(), "p" + i, 1.0, true, 100.0, 10.0, 60.0, false));
        }
        EncounterDefeatedEvent event = new EncounterDefeatedEvent(UUID.randomUUID(), "Boss_Script", null, null, "Boss",
                shares, shares.stream().map(ParticipantShare::playerId).toList(), Map.of(), Map.of(), 90.0, 0,
                difficulty, null);
        assertEquals(party, EncounterLeaderboardListener.record(board, event, group), "the listener wrote the rows");
    }

    @Nonnull
    private static List<String> keys(@Nonnull List<LeaderboardBucketTab> tabs) {
        return tabs.stream().map(LeaderboardBucketTab::bucketKey).toList();
    }

    @Test
    void everyRowTheListenerWritesReadsBackThroughTheTwoAxes() throws IOException {
        EncounterBindingAsset.Leaderboard group =
                group("{ \"Bucket\": \"bosses\", \"ByPartySize\": true, \"ByDifficulty\": true }");
        Leaderboard board = new Leaderboard("test");
        defeat(board, group, 1, "Hard");
        defeat(board, group, 2, "hard");
        defeat(board, group, 2, "normal");

        LeaderboardPageDeps deps = EncounterBoards.deps(board, group, null, TEXT);

        assertEquals(List.of("hard", "normal"), keys(deps.primaryTabs()), "difficulties, A to Z, as recorded");
        assertEquals(List.of("1", "2"), keys(deps.tabs()), "party sizes, ascending");
        assertEquals("bosses:2:hard", deps.bucketKey("hard", "2"), "the composer writes the listener's own key");
        List<String> everySelection = new ArrayList<>();
        for (LeaderboardBucketTab difficulty : deps.primaryTabs()) {
            for (LeaderboardBucketTab party : deps.tabs()) {
                everySelection.add(deps.bucketKey(difficulty.bucketKey(), party.bucketKey()));
            }
        }
        assertTrue(everySelection.containsAll(board.bucketKeys()),
                "All and All reads every bucket the listener wrote: " + everySelection);
        assertEquals(5, board.forBuckets(everySelection).size(), "every participant, once");
    }

    @Test
    void aPartySplitReadsItsTabsAsTheBucketsThemselves() throws IOException {
        EncounterBindingAsset.Leaderboard group = group("{ \"Bucket\": \"bosses\", \"ByPartySize\": true }");
        Leaderboard board = new Leaderboard("test");
        defeat(board, group, 3, "hard");
        defeat(board, group, 1, "hard");

        LeaderboardPageDeps deps = EncounterBoards.deps(board, group, null, TEXT);

        assertTrue(deps.primaryTabs().isEmpty(), "no difficulty axis");
        assertEquals(List.of("bosses:1", "bosses:3"), keys(deps.tabs()));
        assertEquals("1", deps.tabs().get(0).label().getRawText(), "an unnamed party size shows its own digits");
    }

    @Test
    void aDifficultySplitIsThePrimaryAxisAlone() throws IOException {
        EncounterBindingAsset.Leaderboard group = group("{ \"Bucket\": \"bosses\", \"ByDifficulty\": true }");
        Leaderboard board = new Leaderboard("test");
        defeat(board, group, 1, "hard");
        defeat(board, group, 1, null);

        LeaderboardPageDeps deps = EncounterBoards.deps(board, group, null, TEXT);

        assertEquals(List.of("bosses:any", "bosses:hard"), keys(deps.primaryTabs()));
        assertTrue(deps.tabs().isEmpty(), "no party axis");
        assertEquals("hard", deps.primaryTabs().get(1).label().getRawText(), "an unnamed difficulty shows its label");
    }

    @Test
    void anUnsplitBucketIsOneTab() throws IOException {
        EncounterBindingAsset.Leaderboard group = group("{ \"Bucket\": \"bosses\" }");
        Leaderboard board = new Leaderboard("test");
        defeat(board, group, 2, "hard");

        LeaderboardPageDeps deps = EncounterBoards.deps(board, group, null, TEXT);

        assertTrue(deps.primaryTabs().isEmpty());
        assertEquals(List.of("bosses"), keys(deps.tabs()));
        assertEquals(TEXT.filterAll().getMessageId(), deps.tabs().get(0).label().getMessageId());
    }

    @Test
    void aFightWithNoRowsOpensAnEmptyBoard() throws IOException {
        LeaderboardPageDeps deps = EncounterBoards.deps(new Leaderboard("test"),
                group("{ \"Bucket\": \"bosses\", \"ByPartySize\": true }"), null, TEXT);
        assertTrue(deps.primaryTabs().isEmpty());
        assertTrue(deps.tabs().isEmpty(), "nothing recorded, nothing to pick");
    }

    @Test
    void keysFromAnotherSplitAndAnotherBucketAreLeftOut() throws IOException {
        List<String> keys = List.of("bosses:2", "bosses:2:hard", "bosses", "bosses:", "bosses:x", "other:4", "bosses2:3");
        assertEquals(List.of(new EncounterBoards.Split(2, null)),
                EncounterBoards.splits(keys, group("{ \"Bucket\": \"bosses\", \"ByPartySize\": true }")));
        assertEquals(List.of(new EncounterBoards.Split(2, "hard")),
                EncounterBoards.splits(keys, group("{ \"Bucket\": \"bosses\", \"ByPartySize\": true, \"ByDifficulty\": true }")));
    }

    @Test
    void aLayoutNamedAfterTheBucketNamesTheTabsAndOrdersTheDifficulties() throws IOException {
        EncounterBindingAsset.Leaderboard group =
                group("{ \"Bucket\": \"bosses\", \"ByPartySize\": true, \"ByDifficulty\": true }");
        Leaderboard board = new Leaderboard("test");
        defeat(board, group, 2, "hard");
        defeat(board, group, 2, "normal");
        LeaderboardLayout layout = new LeaderboardLayout("bosses", "unused", null, null,
                List.of(new LeaderboardBucketTab("Normal", Msg.raw("Story")), new LeaderboardBucketTab("Hard", Msg.raw("Heroic"))),
                List.of(new LeaderboardBucketTab("2", Msg.raw("Duo"))),
                List.of(StatColumnDef.grouped("kills", Msg.raw("Kills"))));

        LeaderboardPageDeps deps = EncounterBoards.deps(board, group, layout, TEXT);

        assertEquals(List.of("normal", "hard"), keys(deps.primaryTabs()), "the layout's order, whatever its case");
        assertEquals("Story", deps.primaryTabs().get(0).label().getRawText());
        assertEquals("Duo", deps.tabs().get(0).label().getRawText());
        assertEquals(List.of("kills"), deps.statColumns().stream().map(StatColumnDef::statKey).toList());
    }

    @Test
    void withoutALayoutTheStatsShowTheDamageTheListenerKeeps() throws IOException {
        LeaderboardPageDeps deps = EncounterBoards.deps(new Leaderboard("test"), group("{ \"Bucket\": \"bosses\" }"),
                null, TEXT);
        assertEquals(List.of(EncounterLeaderboardListener.STAT_DAMAGE_DEALT, EncounterLeaderboardListener.STAT_DAMAGE_TAKEN),
                deps.statColumns().stream().map(StatColumnDef::statKey).toList());
    }

    @Test
    void theLayoutIsTheOneNamedAfterTheBucket() throws IOException {
        LeaderboardLayout layout = new LeaderboardLayout("bosses", "unused", null, null, List.of(), List.of(), List.of());
        LeaderboardLayoutConfig.getInstance().loadDefaults(Map.of("Bosses", layout));
        assertSame(layout, EncounterBoards.layoutFor(group("{ \"Bucket\": \"bosses\" }")));
        assertNull(EncounterBoards.layoutFor(group("{ \"Bucket\": \"others\" }")));
    }

    @Test
    void onlyFightsWithRowsAreRanked() throws IOException {
        EncounterBindingAsset kept = row("A_Boss", "{ \"Leaderboard\": { \"Bucket\": \"a\", \"ByPartySize\": true } }");
        EncounterBindingAsset empty = row("B_Boss", "{ \"Leaderboard\": { \"Bucket\": \"b\" } }");
        EncounterBindingAsset unranked = row("C_Boss", "{}");
        Leaderboard board = new Leaderboard("test");
        defeat(board, kept.getLeaderboard(), 2, null);
        assertEquals(List.of(kept), EncounterBoards.ranked(board, List.of(unranked, empty, kept)));
    }
}
