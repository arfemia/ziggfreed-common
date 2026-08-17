package com.ziggfreed.common.objectives.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nonnull;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.quest.QuestProgressStore.CompletionRecord;
import com.ziggfreed.common.quest.QuestStatus;

/**
 * The GOLDEN PIN on {@link ZigProgressComponent}'s wire format. This component is not just what a
 * saved world holds: it is what a consumer's own persistence backend - a fleet database behind a
 * mod's player-data seam - stores a player's whole progression as, encoded through this same codec.
 * That makes the format a published contract, and this test is the thing that says so.
 *
 * <p>The golden bytes live at {@value ZigProgressBlobFixture#RESOURCE_PATH}, written once by the
 * gated {@link ZigProgressBlobFixtureGenerator} from
 * {@link ZigProgressBlobFixture#buildFixtureComponent()}.
 *
 * <p><b>The file is NEVER regenerated.</b> A codec change has to keep DECODING it; regenerating it
 * to go green would be replacing the promise with a note saying the promise used to hold. That is
 * also why a new leaf is APPENDED rather than inserted: a blob written before the leaf existed
 * carries no value for it and decodes empty, which is a shape this fixture will keep having as the
 * component grows. If this test goes red, an upgraded server is about to read an existing player's
 * quests, achievements and remembered conversations back wrong.
 *
 * <p><b>Byte-equality is deliberately NOT asserted on re-encode.</b> The maps behind these leaves
 * are {@link java.util.concurrent.ConcurrentHashMap}s, whose iteration order is an unspecified
 * implementation detail rather than part of the Collections contract, so an encode is not obliged to
 * reproduce a byte layout even when the data is identical. The {@code ProgressBlob.ordered} wrapper
 * each leaf goes through does not change that: it SNAPSHOTS the map in whatever order the source
 * hands it over in rather than sorting it, which fixes the order within one encode and promises
 * nothing across two. What the contract actually requires is
 * that OLD bytes decode to the SAME state, which {@link #fixtureBytes_decodeToExpectedState()}
 * proves, plus a lossless decode-encode-decode loop, which
 * {@link #decodedFixture_reEncodesAndRedecodesToTheSameState()} proves without depending on layout.
 */
class ZigProgressBlobCompatTest {

    @Test
    void fixtureBytes_decodeToExpectedState() throws IOException {
        assertFixtureState(decodeFixture());
    }

    @Test
    void decodedFixture_reEncodesAndRedecodesToTheSameState() throws IOException {
        ZigProgressComponent restored = decodeFixture();

        BsonDocument reEncoded =
                ZigProgressComponent.CODEC.encode(restored, ExtraInfo.THREAD_LOCAL.get());
        ZigProgressComponent roundTripped = new ZigProgressComponent();
        ZigProgressComponent.CODEC.decode(BsonDocument.parse(reEncoded.toJson()), roundTripped,
                ExtraInfo.THREAD_LOCAL.get());

        assertFixtureState(roundTripped);
    }

    @Test
    void freshlyBuiltFixture_matchesTheExpectedShape() {
        // Guards the builder and these expectations from drifting apart silently: edit
        // buildFixtureComponent without the checked-in bytes agreeing and the two tests above go
        // red, but THIS one (encode + decode fresh, never touching the resource) is what still
        // catches a builder edited in step with the expectations and nothing else.
        ZigProgressComponent fresh = ZigProgressBlobFixture.buildFixtureComponent();
        BsonDocument encoded = ZigProgressComponent.CODEC.encode(fresh, ExtraInfo.THREAD_LOCAL.get());
        ZigProgressComponent restored = new ZigProgressComponent();
        ZigProgressComponent.CODEC.decode(BsonDocument.parse(encoded.toJson()), restored,
                ExtraInfo.THREAD_LOCAL.get());

        assertFixtureState(restored);
    }

    @Nonnull
    private static ZigProgressComponent decodeFixture() throws IOException {
        ZigProgressComponent restored = new ZigProgressComponent();
        ZigProgressComponent.CODEC.decode(BsonDocument.parse(readFixtureBlob()), restored,
                ExtraInfo.THREAD_LOCAL.get());
        return restored;
    }

    @Nonnull
    private static String readFixtureBlob() throws IOException {
        try (InputStream in = ZigProgressBlobCompatTest.class
                .getResourceAsStream(ZigProgressBlobFixture.RESOURCE_PATH)) {
            assertNotNull(in, ZigProgressBlobFixture.RESOURCE_PATH + " must be on the test classpath"
                    + " - it is checked in beside this test and is never regenerated");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Every one of the twelve leaves, read back through the component's own accessors. */
    private static void assertFixtureState(@Nonnull ZigProgressComponent progress) {
        // QuestStates.
        assertEquals(QuestStatus.ACTIVE, progress.questStatus("quest_fixture_mining_intro"));
        assertEquals(QuestStatus.ACTIVE, progress.questStatus("quest_fixture_ore_gather"));
        assertEquals(QuestStatus.COMPLETED, progress.questStatus("quest_fixture_completed_final"));
        assertEquals(QuestStatus.COMPLETED, progress.questStatus("quest_fixture_daily_ore"));
        assertEquals(QuestStatus.COMPLETED, progress.questStatus("quest_fixture_weekly_raid"));
        assertEquals(QuestStatus.COMPLETED_UNCLAIMED,
                progress.questStatus("quest_fixture_bounty_unclaimed"));
        assertEquals(QuestStatus.NOT_STARTED, progress.questStatus("quest_fixture_never_seen"));

        // QuestProgress - the payload carrying both reserved characters is the base64 leg.
        assertEquals(ZigProgressBlobFixture.RESERVED_PAYLOAD,
                progress.questPayload("quest_fixture_mining_intro"));
        assertEquals("mine_copper=7/10", progress.questPayload("quest_fixture_ore_gather"));

        // QuestCooldowns.
        assertEquals(ZigProgressBlobFixture.DAILY_COOLDOWN_STAMP,
                progress.questCooldown("quest_fixture_daily_ore"));
        assertEquals(ZigProgressBlobFixture.WEEKLY_COOLDOWN_STAMP,
                progress.questCooldown("quest_fixture_weekly_raid"));
        assertEquals(ZigProgressBlobFixture.BOUNTY_PARK_STAMP,
                progress.questCooldown("quest_fixture_bounty_unclaimed"));
        assertEquals(0L, progress.questCooldown("quest_fixture_completed_final"),
                "a finish that is not repeatable stamps no cooldown");

        // TrackedQuests.
        assertEquals(2, progress.trackedPins().size());
        assertEquals(Long.valueOf(ZigProgressBlobFixture.TRACK_PIN_STAMP_1),
                progress.trackedPins().get("quest_fixture_mining_intro"));
        assertEquals(Long.valueOf(ZigProgressBlobFixture.TRACK_PIN_STAMP_2),
                progress.trackedPins().get("quest_fixture_ore_gather"));

        // QuestCompletions - all FOUR fields, collected tally included.
        CompletionRecord completions = progress.questCompletions("quest_fixture_daily_ore");
        assertEquals(ZigProgressBlobFixture.DAILY_COMPLETIONS, completions);
        assertEquals(ZigProgressBlobFixture.COMPLETION_LAST_MS, completions.lastCompletionMs());
        assertEquals(1, completions.periodCount());
        assertEquals(9, completions.totalCount());
        assertEquals(8, completions.claimedCount(),
                "the collected tally is its own field and must not read back as the finished one");
        assertEquals(CompletionRecord.NONE, progress.questCompletions("quest_fixture_ore_gather"));

        // AchievementProgress - composite "<id>#<criterionIndex>" keys.
        assertEquals(250L, progress.achievementProgress("ach_fixture_miner#0"));
        assertEquals(4L, progress.achievementProgress("ach_fixture_miner#1"));
        assertEquals(17L, progress.achievementProgress("ach_fixture_slayer#0"));
        assertEquals(3, progress.achievementProgressKeys().size());

        // AchievementStates + AchievementUnlockedAt.
        assertEquals(AchievementStatus.UNLOCKED, progress.achievementStatus("ach_fixture_miner"));
        assertEquals(AchievementStatus.CLAIMED, progress.achievementStatus("ach_fixture_explorer"));
        assertEquals(AchievementStatus.LOCKED, progress.achievementStatus("ach_fixture_never_seen"));
        assertEquals(ZigProgressBlobFixture.ACHIEVEMENT_UNLOCK_STAMP,
                progress.achievementUnlockedAt("ach_fixture_miner"));
        assertEquals(ZigProgressBlobFixture.ACHIEVEMENT_CLAIM_STAMP,
                progress.achievementUnlockedAt("ach_fixture_explorer"));

        // MilestoneStates - the points ladder, keyed by threshold.
        assertEquals(AchievementStatus.UNLOCKED, progress.milestoneStatus(100));
        assertEquals(AchievementStatus.CLAIMED, progress.milestoneStatus(50));
        assertEquals(AchievementStatus.LOCKED, progress.milestoneStatus(500));
        assertEquals(2, progress.knownMilestones().size());

        // AchievementPins.
        assertEquals(2, progress.achievementPins().size());
        assertEquals(Long.valueOf(ZigProgressBlobFixture.ACHIEVEMENT_PIN_STAMP_1),
                progress.achievementPins().get("ach_fixture_miner"));
        assertEquals(Long.valueOf(ZigProgressBlobFixture.ACHIEVEMENT_PIN_STAMP_2),
                progress.achievementPins().get("ach_fixture_slayer"));

        // DialogueMemories - opaque keys, so what matters is that each comes back verbatim.
        assertEquals(3, progress.dialogueMemoryCount());
        assertTrue(progress.hasDialogueMemory("once:dlg_fixture_guide:greeting"));
        assertTrue(progress.hasDialogueMemory("mem:heard_the_rumour"));
        assertTrue(progress.hasDialogueMemory("q:quest_fixture_ore_gather:mem:promised_the_ore"));
        assertFalse(progress.hasDialogueMemory("mem:never_said"));

        // Migrations - a claimed one-time move stays claimed across a save.
        assertTrue(progress.hasMigrated("ziggfreedcommon:fixture_move"));
        assertFalse(progress.hasMigrated("ziggfreedcommon:never_run"));
    }
}
