package com.ziggfreed.common.encounter.payout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;
import com.ziggfreed.common.encounter.ledger.ParticipationShares;
import com.ziggfreed.common.encounter.run.EncounterFactors;
import com.ziggfreed.common.encounter.run.EncounterLifecycle;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;
import com.ziggfreed.common.encounter.seam.EncounterSeams;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootCues;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.loot.diminish.DiminishSpec;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Every boss payout as a Claim the player collects (M428, R101): the {@code ZigClaim} reward, a binding
 * row's {@code Loot.OnDefeat} and the retired {@code ZigGrant} all roll here and write through
 * {@link ClaimStore#award}, never into the bag.
 *
 * <p>One payout is one roll per player: the loot is rolled against the player's own factor readings,
 * each roll they won is kept with a chance equal to their share ({@code rand < share}), and what the kept
 * rolls land is one Claim. A roll that lands nothing writes no Claim. A Claim waits for a player who is
 * offline; the Claims tab hands it over when they press Claim.
 *
 * <p>Naming: a fight's Claim has the id {@code boss:<script>:<run>:<reward>:<player>}, so a second grant of
 * the same reward in one run writes nothing, and is counted against its diminishing returns under
 * {@code boss:<script>:<reward>:<difficulty>} (an empty difficulty reads as normal), so every run of the
 * same boss on the same difficulty shares the count. A reward with no returns authored pays the first each
 * day in full and nothing after ({@link #FIRST_EACH_DAY}). A reward granted to a player in no run at all is
 * keyed {@code reward:<id>} instead.
 */
public final class EncounterClaims {

    /** What a fight's Claim source, id and returns key start with. */
    static final String BOSS_PREFIX = "boss:";

    /** What a Claim paid outside any run starts with: a world event's grant. */
    static final String REWARD_PREFIX = "reward:";

    /** The reward name a binding row's {@code Loot.OnDefeat} pays under. */
    public static final String ON_DEFEAT = "OnDefeat";

    /** The lang key of the defeat moment's receipt row: the player's reward waits on the Claims tab. */
    public static final String CLAIM_WAITS_KEY = "ziggfreedcommon.encounter.claim.waits";

    /** A boss's returns when it authors none: the first clear each day pays in full, the rest nothing (R260). */
    public static final DiminishSpec FIRST_EACH_DAY = DiminishSpec.of(DiminishSpec.Window.DAILY, 1.0, 0.0);

    /** The name a deferred command keeps for whoever presses Claim. */
    private static final String CLAIMER = "{player}";

    private EncounterClaims() {
    }

    /** How one Claim is named, titled and counted. */
    record ClaimShape(@Nonnull String id, @Nonnull String source, @Nullable String titleKey,
                      @Nullable String difficulty, @Nonnull DiminishSpec diminish, @Nonnull String diminishKey) {
    }

    /** What one player's payout did, for its log line. */
    record Outcome(int won, int kept, boolean written) {

        /** The log line's tail: rolls kept of rolls won, and whether a Claim was written. */
        @Nonnull
        String describe() {
            return "rolls=" + kept + "/" + won + (written ? " claim=written" : " claim=none");
        }
    }

    // ==================== the ZigClaim reward ====================

    /**
     * The engine's grant of {@code reward} to {@code playerRef}: the player's run is the one the member index
     * puts them in (the latest, when two runs hold them), else a live run whose roster took them this tick,
     * paid only once it is defeated, from the shares its defeat settled. A run that just reset keeps its members
     * indexed on the next run ({@link EncounterRuns#untrack(UUID, UUID, Ref)}), so a grant after a leash or a reset
     * refuses. Only a player no run indexes or rosters is paid at a full share. Never throws.
     */
    static void grant(@Nonnull ZigClaimReward reward, @Nonnull PlayerRef playerRef) {
        String rewardId = reward.getId() == null ? ZigClaimReward.TYPE : reward.getId();
        String name = playerRef.getUsername() == null ? String.valueOf(playerRef.getUuid()) : playerRef.getUsername();
        try {
            UUID player = playerRef.getUuid();
            Ref<EntityStore> ref = playerRef.getReference();
            if (player == null || ref == null || !ref.isValid()) {
                SafeLog.info(Encounters.LOG_PREFIX + " claim " + rewardId + " " + name
                        + ": the player is not in a world, nothing to pay");
                return;
            }
            String lootable = reward.lootable();
            UUID runId = EncounterRuns.runOfMember(ref);
            if (runId == null) {
                runId = EncounterRuns.runRostering(ref.getStore(), ref);
            }
            if (runId == null) {
                grantOutsideRun(reward, rewardId, lootable, player, name, ref);
                return;
            }
            EncounterRuns.Live live = EncounterRuns.live(runId);
            String head = Encounters.LOG_PREFIX + " claim " + rewardId + " run=" + EncounterRun.shortId(runId)
                    + (live == null ? "" : " encounter=" + live.encounterId()) + " " + name;
            if (live == null) {
                SafeLog.info(head + ": the run has ended, nothing to pay");
                return;
            }
            ZigEncounterRun run = live.run();
            if (!run.isDefeated()) {
                SafeLog.info(head + ": the run is not won, nothing to pay");
                return;
            }
            ParticipantShare mine = creditedShare(run.settledShares(), player);
            if (mine == null) {
                SafeLog.info(head + ": not credited in this run, nothing to pay");
                return;
            }
            if (lootable == null) {
                SafeLog.info(head + ": the reward names no Lootable, nothing to pay");
                return;
            }
            String encounterId = live.encounterId();
            EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(encounterId);
            String titleKey = reward.titleKey() != null ? reward.titleKey() : nameKeyOf(row);
            double share = reward.shareScaled() ? mine.share() : 1.0;
            ClaimShape shape = bossClaim(encounterId, run.runId(), rewardId, player, titleKey,
                    EncounterLifecycle.difficultyOf(run, row), reward.diminishOrDefault());
            Outcome outcome = award(ref.getStore(), ref, player, resolve(lootable, rewardId), share, shape,
                    placeholders(encounterId, run.runId(), share));
            SafeLog.info(head + " share=" + shareText(share) + " " + outcome.describe());
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " the " + rewardId + " Claim for " + name + " failed", t);
        }
    }

    /** A grant to a player no run holds (a world event's): one Claim at a full share, keyed on the reward. */
    private static void grantOutsideRun(@Nonnull ZigClaimReward reward, @Nonnull String rewardId,
            @Nullable String lootable, @Nonnull UUID player, @Nonnull String name, @Nonnull Ref<EntityStore> ref) {
        String head = Encounters.LOG_PREFIX + " claim " + rewardId + " " + name + " outside any run";
        if (lootable == null) {
            SafeLog.info(head + ": the reward names no Lootable, nothing to pay");
            return;
        }
        long now = System.currentTimeMillis();
        String source = REWARD_PREFIX + rewardId;
        ClaimShape shape = new ClaimShape(worldClaimId(rewardId, player, now), source, reward.titleKey(), null,
                reward.diminishOrDefault(), source);
        Outcome outcome = award(ref.getStore(), ref, player, resolve(lootable, rewardId), 1.0, shape, Map.of());
        SafeLog.info(head + " share=" + shareText(1.0) + " " + outcome.describe());
    }

    /** {@code player}'s credited share in {@code shares}, or null when they hold none. */
    @Nullable
    static ParticipantShare creditedShare(@Nonnull ParticipationShares shares, @Nonnull UUID player) {
        for (ParticipantShare share : shares.credited()) {
            if (player.equals(share.playerId())) {
                return share;
            }
        }
        return null;
    }

    /** The table {@code lootable} as what one roll evaluates; a table nothing answers to is warned and skipped. */
    @Nonnull
    private static LootEngine.Resolved resolve(@Nonnull String lootable, @Nonnull String rewardId) {
        return LootEngine.resolve(LootRef.of(new String[] {lootable}, null), unknown -> SafeLog.warn(
                Encounters.LOG_PREFIX + " the ZigClaim '" + rewardId + "' names no loot table called '" + unknown + "'"));
    }

    // ==================== one player's Claim ====================

    /**
     * Roll {@code resolved} for {@code player} and write what survives {@code share} as one Claim shaped by
     * {@code shape}. The roll reads the player's own factors when {@code store} and {@code ref} are theirs,
     * none when they are offline (both null). Each deferred command keeps {@code {player}} for whoever claims
     * it and has {@code placeholders} filled now. A Claim written for a player standing here plays the cues of
     * the rolls it kept.
     */
    @Nonnull
    static Outcome award(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull UUID player,
            @Nonnull LootEngine.Resolved resolved, double share, @Nonnull ClaimShape shape,
            @Nonnull Map<String, String> placeholders) {
        boolean here = store != null && ref != null && ref.isValid();
        FactorLookup lookup = FactorLookup.through(EncounterFactors.registry(),
                FactorContext.about(here ? store : null, here ? ref : null));
        DoubleSupplier sample = sampler();
        List<LootEngine.Selected> won = LootEngine.select(resolved.rolls(), resolved.pools(), null, lookup, sample);
        List<LootEngine.Selected> kept = keptAt(won, share, sample);
        List<InstanceReward> rewards = filled(InstanceReward.merge(DeferredRewards.fromSelection(kept,
                RewardKinds.shared(), Subject.of(player, CLAIMER), shape.source(),
                message -> SafeLog.warn(Encounters.LOG_PREFIX + " " + message))), placeholders);
        long now = System.currentTimeMillis();
        boolean written = !rewards.isEmpty() && ClaimStore.shared().award(player,
                new Claim(shape.id(), shape.source(), shape.titleKey(), List.of(), shape.difficulty(), rewards, now),
                shape.diminish(), shape.diminishKey(), now);
        if (written && here) {
            presentCues(store, ref, kept, shape.source());
        }
        return new Outcome(won.size(), kept.size(), written);
    }

    /** The cues the kept rolls carry, played to the player they were won for; nothing when nothing listens. */
    private static void presentCues(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull List<LootEngine.Selected> kept, @Nonnull String source) {
        List<String> cues = new ArrayList<>();
        for (LootEngine.Selected selected : kept) {
            if (selected.cue() != null && !selected.cue().isBlank()) {
                cues.add(selected.cue());
            }
        }
        if (cues.isEmpty()) {
            return;
        }
        Subject subject = EncounterSeams.subjectFor(store, ref);
        if (subject != null) {
            LootCues.presentAll(cues, subject, source);
        }
    }

    // ==================== the pure pieces ====================

    /** A fight's Claim: its id, its source and its returns key, all from the fight, the run and the reward. */
    @Nonnull
    static ClaimShape bossClaim(@Nonnull String encounterId, @Nonnull UUID runId, @Nonnull String reward,
            @Nonnull UUID player, @Nullable String titleKey, @Nullable String difficulty,
            @Nonnull DiminishSpec diminish) {
        return new ClaimShape(claimId(encounterId, runId, reward, player), BOSS_PREFIX + encounterId, titleKey,
                difficulty, diminish, diminishKey(encounterId, reward, difficulty));
    }

    /** {@code boss:<script>:<run>:<reward>:<player>}: one Claim per reward, per run, per player. */
    @Nonnull
    static String claimId(@Nonnull String encounterId, @Nonnull UUID runId, @Nonnull String reward,
            @Nonnull UUID player) {
        return BOSS_PREFIX + encounterId + ":" + runId + ":" + reward + ":" + player;
    }

    /** {@code boss:<script>:<reward>:<difficulty>}, the difficulty empty for normal: the count every run shares. */
    @Nonnull
    static String diminishKey(@Nonnull String encounterId, @Nonnull String reward, @Nullable String difficulty) {
        return BOSS_PREFIX + encounterId + ":" + reward + ":" + (difficulty == null ? "" : difficulty.trim());
    }

    /** A grant outside any run is its own Claim: {@code reward:<id>:<player>:<when>}. */
    @Nonnull
    static String worldClaimId(@Nonnull String rewardId, @Nonnull UUID player, long nowMs) {
        return REWARD_PREFIX + rewardId + ":" + player + ":" + nowMs;
    }

    /** The rolls of {@code won} that survive {@code share}: each kept while its own draw is under the share. */
    @Nonnull
    static List<LootEngine.Selected> keptAt(@Nonnull List<LootEngine.Selected> won, double share,
            @Nonnull DoubleSupplier sample) {
        List<LootEngine.Selected> kept = new ArrayList<>(won.size());
        for (LootEngine.Selected selected : won) {
            if (sample.getAsDouble() < share) {
                kept.add(selected);
            }
        }
        return kept;
    }

    /** The placeholders a fight's deferred commands fill now: {@code {encounter}}, {@code {run}}, {@code {share}}. */
    @Nonnull
    static Map<String, String> placeholders(@Nonnull String encounterId, @Nonnull UUID runId, double share) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("encounter", encounterId);
        out.put("run", EncounterRun.shortId(runId));
        out.put("share", shareText(share));
        return out;
    }

    /** {@code rewards} with each command's {@code placeholders} filled in, spec kept; every other reward as it was. */
    @Nonnull
    static List<InstanceReward> filled(@Nonnull List<InstanceReward> rewards, @Nonnull Map<String, String> placeholders) {
        if (placeholders.isEmpty()) {
            return rewards;
        }
        List<InstanceReward> out = new ArrayList<>(rewards.size());
        for (InstanceReward reward : rewards) {
            if (reward.kind() != InstanceReward.Kind.COMMAND) {
                out.add(reward);
                continue;
            }
            String command = reward.id();
            for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
                command = command.replace("{" + placeholder.getKey() + "}", placeholder.getValue());
            }
            out.add(reward.withId(command));
        }
        return out;
    }

    /** What a paid player's defeat moment lists: one row saying their reward waits on the Claims tab. */
    @Nonnull
    static List<RewardSpec> receipt() {
        return List.of(RewardSpec.of(ZigClaimReward.TYPE, DeferredRewards.PARAM_NAME_KEY, CLAIM_WAITS_KEY));
    }

    /** The fight's name key a binding row writes, or null. */
    @Nullable
    static String nameKeyOf(@Nullable EncounterBindingAsset row) {
        return row == null ? null : row.getNameKey();
    }

    @Nonnull
    static String shareText(double share) {
        return String.format(Locale.ROOT, "%.2f", share);
    }

    @Nonnull
    private static DoubleSupplier sampler() {
        return () -> ThreadLocalRandom.current().nextDouble();
    }
}
