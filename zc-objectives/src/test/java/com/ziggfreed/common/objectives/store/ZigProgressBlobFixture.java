package com.ziggfreed.common.objectives.store;

import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.quest.QuestProgressStore.CompletionRecord;
import com.ziggfreed.common.quest.QuestStatus;

/**
 * Builds the ONE representative {@link ZigProgressComponent} shared by
 * {@link ZigProgressBlobFixtureGenerator} (which encoded it once into the checked-in golden blob at
 * {@link #RESOURCE_PATH}) and {@link ZigProgressBlobCompatTest} (which decodes that blob and asserts
 * it still reads back as this exact shape). Pointing both sides at one builder is what turns a
 * change here that the golden bytes do not agree with into a test FAILURE rather than silent drift.
 *
 * <p>Every timestamp below is a FIXED literal, never {@code System.currentTimeMillis()}, so the
 * bytes are reproducible and a diff on the fixture means the FORMAT moved.
 *
 * <p>All TWELVE codec leaves carry something, because the wire format is what a consumer's own
 * persistence backend stores this component as, and a leaf nothing exercised would be a leaf nothing
 * would notice breaking.
 */
final class ZigProgressBlobFixture {

    private ZigProgressBlobFixture() {
    }

    /** Classpath-relative location of the checked-in golden blob (test resources root). */
    static final String RESOURCE_PATH = "/fixtures/zig-progress-blob-1-6-0.bin";

    static final long DAILY_COOLDOWN_STAMP = 1_700_000_000_000L;
    static final long WEEKLY_COOLDOWN_STAMP = 1_699_800_000_000L;
    static final long BOUNTY_PARK_STAMP = 1_700_050_000_000L;
    static final long TRACK_PIN_STAMP_1 = 1_700_100_000_000L;
    static final long TRACK_PIN_STAMP_2 = 1_700_100_500_000L;
    static final long ACHIEVEMENT_UNLOCK_STAMP = 1_700_200_000_000L;
    static final long ACHIEVEMENT_CLAIM_STAMP = 1_700_300_000_000L;
    static final long ACHIEVEMENT_PIN_STAMP_1 = 1_700_400_000_000L;
    static final long ACHIEVEMENT_PIN_STAMP_2 = 1_700_400_500_000L;
    static final long COMPLETION_LAST_MS = 1_700_500_000_000L;

    /** A quest payload carrying BOTH characters the packing reserves, so the base64 leg is proven. */
    static final String RESERVED_PAYLOAD = "talk_npc=1/1|return_to_npc=0/1";

    /** The four-field completion record: last finish, this period, lifetime, collected. */
    static final CompletionRecord DAILY_COMPLETIONS =
            new CompletionRecord(COMPLETION_LAST_MS, 1, 9, 8);

    static ZigProgressComponent buildFixtureComponent() {
        ZigProgressComponent progress = new ZigProgressComponent();

        // --- QuestStates + QuestProgress: one active quest per progress flavour ---
        progress.setQuestStatus("quest_fixture_mining_intro", QuestStatus.ACTIVE);
        progress.putQuestPayload("quest_fixture_mining_intro", RESERVED_PAYLOAD);

        progress.setQuestStatus("quest_fixture_ore_gather", QuestStatus.ACTIVE);
        progress.putQuestPayload("quest_fixture_ore_gather", "mine_copper=7/10");

        // Finished and never repeatable: deliberately NO cooldown entry.
        progress.setQuestStatus("quest_fixture_completed_final", QuestStatus.COMPLETED);

        // --- QuestCooldowns: a daily and a weekly, plus a parked manual claim ---
        progress.setQuestStatus("quest_fixture_daily_ore", QuestStatus.COMPLETED);
        progress.setQuestCooldown("quest_fixture_daily_ore", DAILY_COOLDOWN_STAMP);

        progress.setQuestStatus("quest_fixture_weekly_raid", QuestStatus.COMPLETED);
        progress.setQuestCooldown("quest_fixture_weekly_raid", WEEKLY_COOLDOWN_STAMP);

        progress.setQuestStatus("quest_fixture_bounty_unclaimed", QuestStatus.COMPLETED_UNCLAIMED);
        progress.setQuestCooldown("quest_fixture_bounty_unclaimed", BOUNTY_PARK_STAMP);

        // --- TrackedQuests: two pins at fixed instants ---
        progress.setTrackedPin("quest_fixture_mining_intro", TRACK_PIN_STAMP_1);
        progress.setTrackedPin("quest_fixture_ore_gather", TRACK_PIN_STAMP_2);

        // --- QuestCompletions: a repeatable with one finish still uncollected ---
        progress.setQuestCompletions("quest_fixture_daily_ore", DAILY_COMPLETIONS);

        // --- AchievementProgress: composite "<id>#<criterionIndex>" keys, two criteria on one ---
        progress.putAchievementProgress("ach_fixture_miner#0", 250L);
        progress.putAchievementProgress("ach_fixture_miner#1", 4L);
        progress.putAchievementProgress("ach_fixture_slayer#0", 17L);

        // --- AchievementStates + AchievementUnlockedAt: one earned, one already collected ---
        progress.setAchievementStatus("ach_fixture_miner", AchievementStatus.UNLOCKED);
        progress.setAchievementUnlockedAt("ach_fixture_miner", ACHIEVEMENT_UNLOCK_STAMP);

        progress.setAchievementStatus("ach_fixture_explorer", AchievementStatus.CLAIMED);
        progress.setAchievementUnlockedAt("ach_fixture_explorer", ACHIEVEMENT_CLAIM_STAMP);

        // --- MilestoneStates: the points ladder, one rung earned and one collected ---
        progress.setMilestoneStatus(100, AchievementStatus.UNLOCKED);
        progress.setMilestoneStatus(50, AchievementStatus.CLAIMED);

        // --- AchievementPins: two pins at fixed instants ---
        progress.setAchievementPin("ach_fixture_miner", ACHIEVEMENT_PIN_STAMP_1);
        progress.setAchievementPin("ach_fixture_slayer", ACHIEVEMENT_PIN_STAMP_2);

        // --- DialogueMemories: a spent Once, a named memory, and one filed under a quest ---
        progress.setDialogueMemory("once:dlg_fixture_guide:greeting");
        progress.setDialogueMemory("mem:heard_the_rumour");
        progress.setDialogueMemory("q:quest_fixture_ore_gather:mem:promised_the_ore");

        // --- Migrations: one claimed one-time move ---
        progress.claimMigration("ziggfreedcommon:fixture_move");

        return progress;
    }
}
