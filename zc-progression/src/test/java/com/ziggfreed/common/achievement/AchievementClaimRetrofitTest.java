package com.ziggfreed.common.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.subject.Subject;

/**
 * Claim rows added to an achievement a player already settled (R295). What bit: an achievement with no Claim
 * row settles to CLAIMED the moment it is earned, and a collect needs UNLOCKED, so a row added later could
 * never be collected. Paying a row twice is the failure that matters most, so every case here also proves a
 * settled row is never paid again.
 */
class AchievementClaimRetrofitTest {

    private static final Subject ALICE = new Subject(new UUID(0, 1), "Alice", null);
    private static final Subject BOB = new Subject(new UUID(0, 2), "Bob", null);

    private final List<String> paid = new ArrayList<>();
    private final List<String> moments = new ArrayList<>();
    private final InMemoryAchievementProgressStore store = new InMemoryAchievementProgressStore();

    @Nonnull
    private AchievementEngine engine() {
        ObjectiveKindRegistry kinds = new ObjectiveKindRegistry();
        kinds.register(null, ObjectiveKind.of("BREAK_BLOCK"));
        RewardKindRegistry rewards = new RewardKindRegistry();
        rewards.register("test:pay", (spec, subject) -> paid.add(spec.paramOr("Id", "?")));
        return AchievementEngine.builder()
                .objectiveKinds(kinds)
                .rewardKinds(rewards)
                .store(store)
                .feedbackHook((momentId, subject, args) -> moments.add(momentId))
                .nativeEvents(false)
                .build();
    }

    @Nonnull
    private static Achievement.Builder guest() {
        return Achievement.builder("jacks_guest_2026").points(5)
                .criterion(ObjectiveDef.builder("attend", "BREAK_BLOCK")
                        .target("Copper_Ore").matchMode(MatchMode.EXACT).amount(1).build())
                .autoReward(pay("token"));
    }

    @Nonnull
    private static RewardSpec pay(@Nonnull String id) {
        return RewardSpec.of("test:pay", "Id", id);
    }

    @Test
    void anAchievementSettledWithNothingToCollectOffersTheRowsAddedLaterOnce() {
        AchievementEngine engine = engine();
        engine.setAchievements(List.of(guest().build()));
        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 1L);
        assertEquals(AchievementStatus.CLAIMED, engine.status(ALICE, "jacks_guest_2026"),
                "nothing to collect, so it settled the moment it was earned");

        engine.setAchievements(List.of(guest().claimReward(pay("old_jack")).claimReward(pay("hallowed")).build()));
        paid.clear();
        moments.clear();
        engine.selfHeal(ALICE);

        assertEquals(AchievementStatus.UNLOCKED, engine.status(ALICE, "jacks_guest_2026"), "Collect is back");
        assertEquals(5, engine.points(ALICE), "points stay as they were");
        assertTrue(paid.isEmpty(), "nothing is paid without a collect");
        assertTrue(moments.isEmpty(), "and nothing is announced: no unlock, no claim");

        assertNotNull(engine.tryClaim(ALICE, engine.achievement("jacks_guest_2026")));
        assertEquals(List.of("old_jack", "hallowed"), paid, "the added rows, and not the token again");

        engine.selfHeal(ALICE);
        assertEquals(AchievementStatus.CLAIMED, engine.status(ALICE, "jacks_guest_2026"), "offered once");
        assertNull(engine.tryClaim(ALICE, engine.achievement("jacks_guest_2026")));
        assertEquals(List.of("old_jack", "hallowed"), paid, "never paid twice");
    }

    @Test
    void aCollectedAchievementReopensForTheAppendedRowAloneNeverTheOnesItPaid() {
        AchievementEngine engine = engine();
        engine.setAchievements(List.of(guest().claimReward(pay("first")).build()));
        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 1L);
        assertNotNull(engine.tryClaim(ALICE, engine.achievement("jacks_guest_2026")));

        engine.setAchievements(List.of(guest().claimReward(pay("first")).claimReward(pay("second")).build()));
        paid.clear();
        engine.selfHeal(ALICE);
        assertEquals(List.of("second"), engine.claimRewardsOwed(ALICE, engine.achievement("jacks_guest_2026"))
                .stream().map(row -> row.paramOr("Id", "")).toList(), "what Collect lists is what it pays");
        assertNotNull(engine.tryClaim(ALICE, engine.achievement("jacks_guest_2026")));

        assertEquals(List.of("second"), paid, "only the row added since the collect");
    }

    /**
     * A row that leaves the list (its mod missing, an owner override, a reload) while a player logs in, and comes
     * back, was paid once and is never paid again; a row appended once it is back is still owed.
     */
    @Test
    void aRowThatLeavesTheListAndComesBackIsNeverPaidTwice() {
        AchievementEngine engine = engine();
        engine.setAchievements(List.of(guest().claimReward(pay("x")).claimReward(pay("y")).build()));
        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 1L);
        assertNotNull(engine.tryClaim(ALICE, engine.achievement("jacks_guest_2026")));

        engine.setAchievements(List.of(guest().claimReward(pay("x")).build()));
        engine.selfHeal(ALICE);
        engine.setAchievements(List.of(guest().claimReward(pay("x")).claimReward(pay("y")).build()));
        paid.clear();
        engine.selfHeal(ALICE);

        assertEquals(AchievementStatus.CLAIMED, engine.status(ALICE, "jacks_guest_2026"), "y was settled once");
        assertNull(engine.tryClaim(ALICE, engine.achievement("jacks_guest_2026")));
        assertTrue(paid.isEmpty(), "never paid twice");

        engine.setAchievements(List.of(guest().claimReward(pay("x")).claimReward(pay("y")).claimReward(pay("z"))
                .build()));
        engine.selfHeal(ALICE);
        assertNotNull(engine.tryClaim(ALICE, engine.achievement("jacks_guest_2026")));
        assertEquals(List.of("z"), paid, "a row added since is still owed");
    }

    @Test
    void aFullySettledAchievementIsNeverReopened() {
        AchievementEngine engine = engine();
        engine.setAchievements(List.of(guest().claimReward(pay("first")).build()));
        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 1L);
        engine.tryClaim(ALICE, engine.achievement("jacks_guest_2026"));

        engine.selfHeal(ALICE);
        assertEquals(AchievementStatus.CLAIMED, engine.status(ALICE, "jacks_guest_2026"), "the same rows");

        engine.setAchievements(List.of(guest().claimReward(RewardSpec.of("test:pay",
                Map.of("Id", "first", "Amount", "600"))).build()));
        engine.selfHeal(ALICE);
        assertEquals(AchievementStatus.CLAIMED, engine.status(ALICE, "jacks_guest_2026"),
                "a retuned row is no added row");

        // A record from before the note, collected by hand under rows nobody can know now: covered as it stands.
        store.setStatus(BOB, "jacks_guest_2026", AchievementStatus.CLAIMED);
        store.setUnlockedAt(BOB, "jacks_guest_2026", 1_000L);
        engine.selfHeal(BOB);
        assertEquals(AchievementStatus.CLAIMED, engine.status(BOB, "jacks_guest_2026"));
        paid.clear();
        assertNull(engine.tryClaim(ALICE, engine.achievement("jacks_guest_2026")));
        assertNull(engine.tryClaim(BOB, engine.achievement("jacks_guest_2026")));
        assertTrue(paid.isEmpty());
    }

    /**
     * The Seasons of Orbis case: a record settled before the library noted collects, of an achievement that had
     * no Claim row when it was earned, which its file proves with {@code ClaimAddedOn}. Earned before that day,
     * every row is offered once; earned on or after it, the record is covered.
     */
    @Test
    void aRecordFromBeforeTheNoteIsOfferedEveryRowOnlyWhenEarnedBeforeTheRowsShipped() throws Exception {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(AchievementAsset.class, "jacks_guest_2026", null);
        AchievementAsset asset = AchievementAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString("""
                { "ClaimAddedOn": "2026-10-13",
                  "Criteria": { "attend": { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore", "Amount": 1 } },
                  "Rewards": { "Claim": [ { "Kind": "test:pay", "Params": { "Id": "old_jack" } },
                                          { "Kind": "test:pay", "Params": { "Id": "hallowed" } } ] } }
                """), null, new AssetExtraInfo<>(data));
        Achievement achievement = asset.toDefinition().achievement();
        assertEquals(LocalDate.of(2026, 10, 13), achievement.claimAddedOn());
        AchievementEngine engine = engine();
        engine.setAchievements(List.of(achievement));
        String id = achievement.id();

        store.setStatus(ALICE, id, AchievementStatus.CLAIMED);
        store.setUnlockedAt(ALICE, id, utc(2026, 10, 5));
        store.setStatus(BOB, id, AchievementStatus.CLAIMED);
        store.setUnlockedAt(BOB, id, utc(2026, 10, 13));
        engine.selfHeal(ALICE);
        engine.selfHeal(BOB);

        assertEquals(AchievementStatus.UNLOCKED, engine.status(ALICE, id), "earned before the rows shipped");
        assertEquals(AchievementStatus.CLAIMED, engine.status(BOB, id), "earned once they could be collected");
        assertNotNull(engine.tryClaim(ALICE, achievement));
        assertNull(engine.tryClaim(BOB, achievement));
        engine.selfHeal(ALICE);
        assertNull(engine.tryClaim(ALICE, achievement), "offered once");
        assertEquals(List.of("old_jack", "hallowed"), paid);
    }

    private static long utc(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
