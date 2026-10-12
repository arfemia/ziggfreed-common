package com.ziggfreed.common.encounter.payout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.ParticipationSpec;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;
import com.ziggfreed.common.encounter.ledger.ParticipationShares;
import com.ziggfreed.common.encounter.run.EncounterFactors;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.encounter.run.EncounterSubjects;
import com.ziggfreed.common.encounter.run.EncounterLifecycle;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;
import com.ziggfreed.common.instance.reward.NativeLootService;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.GroundSpillSinks;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.util.SafeLog;

import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;

/**
 * What a fight pays, through the library's own loot vocabulary and nothing new.
 *
 * <p><b>A defeat pays each credited participant a Claim, and the rolls SCALE with their share.</b>
 * The row's {@code Loot.OnDefeat} is evaluated once per participant against their own factor
 * readings, then every roll they won is KEPT with a chance equal to their share: the top contributor
 * (share 1) keeps everything, a participant at 0.3 keeps about a third of what they would have. What
 * is kept is written as one Claim through {@link EncounterClaims}, which waits on the player's Claims
 * tab until they collect it (M428), online or not, and is counted against the boss's diminishing
 * returns; nothing goes into the bag. The row's {@code ToKiller} and {@code QueueIfOffline} are read
 * and ignored, with one warning per row: the killer is paid at the share they earned, and a Claim
 * already waits for a player who is offline.
 *
 * <p>A phase drop rolls once and spills on the ground at the subject, the way a mob's own death
 * drops do: everything it won goes through the same engine pass, its items and drop lists through
 * the library's ground-spill sinks gathered into ONE pile, and its commands through the engine's
 * command leaf as the console, resolved like every other loot command (placeholders
 * {@code {encounter}}, {@code {phase}} and {@code {run}}, a positional {@code /give} count read as
 * {@code --quantity=N}, a failed line warned). It has no player to be about, so a registered reward
 * kind in a phase drop is reported and skipped rather than paid to nobody.
 */
public final class EncounterLoot {

    /** The binding rows already warned about their retired payout leaves, one warning per row. */
    private static final Set<String> WARNED_RETIRED = ConcurrentHashMap.newKeySet();

    private EncounterLoot() {
    }

    /**
     * Pay the defeat: {@code Loot.OnDefeat} as one Claim per credited participant, share-scaled.
     *
     * @return each paid player's own RECEIPT, keyed by player id, so the defeat moment that follows
     *         tells a participant their reward waits and never tells it to another: one row for a
     *         player whose roll wrote them a Claim. A player whose roll landed nothing, or whose
     *         Claim the boss's diminishing returns took to nothing, has no entry.
     */
    @Nonnull
    public static Map<UUID, List<RewardSpec>> grantDefeat(@Nonnull Store<EntityStore> store,
            @Nonnull ZigEncounterRun run, @Nonnull String encounterId, @Nullable EncounterBindingAsset row,
            @Nonnull ParticipationSpec spec, @Nonnull ParticipationShares shares) {
        EncounterBindingAsset.Loot loot = row == null ? null : row.getLoot();
        LootRef ref = loot == null ? null : loot.getOnDefeat();
        if (ref == null || ref.isEmpty()) {
            return Map.of();
        }
        warnRetiredLeaves(encounterId, loot);
        return grantShares(store, run, encounterId, row, ref, spec, shares, EncounterClaims.ON_DEFEAT, "payout");
    }

    /**
     * Pay everybody credited in {@code shares} the loot {@code ref} as one Claim each, share-scaled, under
     * the reward name {@code reward} (the Claim's id and the boss's diminishing-returns count both carry it):
     * the defeat's path and the {@code ZigGrant} action's. Answers the same per-player receipts as
     * {@link #grantDefeat}.
     *
     * @param row   the fight's binding row, which titles the Claim with the fight's name; may be null
     * @param label what the log lines call this payout
     */
    @Nonnull
    public static Map<UUID, List<RewardSpec>> grantShares(@Nonnull Store<EntityStore> store,
            @Nonnull ZigEncounterRun run, @Nonnull String encounterId, @Nullable EncounterBindingAsset row,
            @Nonnull LootRef ref, @Nonnull ParticipationSpec spec, @Nonnull ParticipationShares shares,
            @Nonnull String reward, @Nonnull String label) {
        List<ParticipantShare> credited = shares.credited();
        if (credited.isEmpty()) {
            SafeLog.info(Encounters.LOG_PREFIX + " " + label + " run=" + EncounterRun.shortId(run.runId()) + " encounter="
                    + encounterId + ": nobody to pay (0 credited participants)");
            return Map.of();
        }
        LootEngine.Resolved resolved = LootEngine.resolve(ref, unknown -> SafeLog.warn(Encounters.LOG_PREFIX
                + " '" + encounterId + "' names no loot table called '" + unknown + "'"));
        String titleKey = EncounterClaims.nameKeyOf(row);
        String difficulty = EncounterLifecycle.difficultyOf(run, row);
        Map<UUID, List<RewardSpec>> receipts = new LinkedHashMap<>();
        for (ParticipantShare participant : credited) {
            if (claimFor(store, run, encounterId, resolved, reward, titleKey, difficulty, spec, participant, label)) {
                receipts.put(participant.playerId(), EncounterClaims.receipt());
            }
        }
        return receipts;
    }

    /** Say once per row that its {@code ToKiller} and {@code QueueIfOffline} no longer change the payout. */
    private static void warnRetiredLeaves(@Nonnull String encounterId, @Nonnull EncounterBindingAsset.Loot loot) {
        List<String> retired = loot.retiredLeaves();
        if (retired.isEmpty() || !WARNED_RETIRED.add(encounterId.toLowerCase(Locale.ROOT))) {
            return;
        }
        SafeLog.warn(Encounters.LOG_PREFIX + " the binding row for '" + encounterId + "' writes Loot."
                + String.join(" and Loot.", retired) + ", which no longer change the payout: every credited "
                + "player is paid a Claim at the share they earned, and it waits for a player who is offline. "
                + "Leave " + (retired.size() == 1 ? "it" : "them") + " out.");
    }

    /** Roll the row's {@code Loot.OnPhase[state]} once and spill it at the subject. */
    public static void dropPhase(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef,
            @Nonnull ZigEncounterRun run, @Nullable EncounterBindingAsset row, @Nonnull String state) {
        EncounterBindingAsset.Loot loot = row == null ? null : row.getLoot();
        Map<String, LootRef> onPhase = loot == null ? null : loot.getOnPhase();
        LootRef ref = onPhase == null ? null : onPhase.get(state);
        if (ref == null || ref.isEmpty()) {
            return;
        }
        String encounterId = row.encounterAsset();
        try {
            LootEngine.Resolved resolved = LootEngine.resolve(ref, unknown -> SafeLog.warn(Encounters.LOG_PREFIX
                    + " '" + encounterId + "' names no loot table called '" + unknown + "'"));
            Ref<EntityStore> subject = EncounterSubjects.resolve(store, encounterRef, row.getSubject(), true);
            TransformComponent at = EncounterLifecycle.anchorOf(store, encounterRef, subject);
            if (at == null) {
                SafeLog.warn(Encounters.LOG_PREFIX + " phase '" + state + "' of '" + encounterId
                        + "' has nowhere to drop its loot");
                return;
            }
            FactorLookup lookup = FactorLookup.through(EncounterFactors.registry(),
                    EncounterFactors.contextFor(store, subject,
                            new EncounterFactors.RunReading(run, run.knownMembers().size(), System.currentTimeMillis())));
            List<ItemStack> stacks = new ArrayList<>();
            LootEngine.Result result = handOverPhase(
                    LootEngine.select(resolved.rolls(), resolved.pools(), null, lookup, sampler()),
                    phaseSinks(stacks, message -> SafeLog.warn(Encounters.LOG_PREFIX + " phase '" + state
                            + "' of '" + encounterId + "': " + message), CommandRunner.CONSOLE,
                            encounterId, state, EncounterRun.shortId(run.runId())),
                    () -> SafeLog.warn(Encounters.LOG_PREFIX + " phase '" + state + "' of '" + encounterId
                            + "' authors a registered reward kind, which needs a player; only items, drop lists "
                            + "and commands can drop in the world"));
            if (!stacks.isEmpty()) {
                Vector3d at3 = new Vector3d(at.getPosition()).add(0.0, 1.0, 0.0);
                NativeLootService.spawnInWorld(store, at3, Rotation3f.IDENTITY, stacks);
            }
            SafeLog.info(Encounters.LOG_PREFIX + " phase drop run=" + EncounterRun.shortId(run.runId()) + " encounter="
                    + encounterId + " phase=" + state + " stacks=" + stacks.size() + " commands="
                    + result.getCommandsRun());
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " phase drop of '" + encounterId + "' failed", t);
        }
    }

    /**
     * Hand one phase drop over through the loot engine, answering the engine's tally of it.
     *
     * <p>Every payout goes through {@code sinks} by the engine's own {@link LootEngine#applyGrants}:
     * items, drop lists and commands alike, so nothing in a phase drop is applied by hand. A payout
     * that authors a registered reward kind is reported once through {@code refuseRewardKinds} and
     * never paid, since {@code sinks} carries no reward vocabulary and no subject (a dry run for that
     * leaf). Package-private so a test can drive it with fixture sinks.
     */
    @Nonnull
    static LootEngine.Result handOverPhase(@Nonnull List<LootEngine.Selected> won, @Nonnull LootEngine.Sinks sinks,
            @Nonnull Runnable refuseRewardKinds) {
        LootEngine.Result result = new LootEngine.Result();
        for (LootEngine.Selected selected : won) {
            LootGrants grants = selected.grants();
            if (grants == null) {
                continue;
            }
            LootEngine.applyGrants(selected, sinks, result);
            if (!grants.rewardSpecs().isEmpty()) {
                refuseRewardKinds.run();
            }
        }
        return result;
    }

    /**
     * A phase drop's sinks: the library's ground-spill items and drop-lists pair with no inventory,
     * whose ground gathers every hand-over into {@code pile}, the ONE pile the phase spawns at the
     * subject once everything is rolled; the commands leaf through {@code dispatcher} (the console on
     * a live server) with the phase's own placeholders, {@code {encounter}}, {@code {phase}} and
     * {@code {run}}; and no rewards leaf (a phase drop pays nobody). Every failure is warned through
     * {@code warn}.
     */
    @Nonnull
    static LootEngine.Sinks phaseSinks(@Nonnull List<ItemStack> pile, @Nonnull Consumer<String> warn,
            @Nonnull CommandRunner.Dispatcher dispatcher, @Nonnull String encounterId, @Nonnull String state,
            @Nonnull String runShortId) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("encounter", encounterId);
        placeholders.put("phase", state);
        placeholders.put("run", runShortId);
        return GroundSpillSinks.at(gatherInto(pile)).warn(warn).build()
                .into(LootEngine.Sinks.builder())
                .commands(dispatcher, placeholders)
                .warn(warn)
                .build();
    }

    /** The phase's ground: each hand-over joins {@code pile}, and counts as landed. */
    @Nonnull
    static GroundSpillSinks.Ground gatherInto(@Nonnull List<ItemStack> pile) {
        return stacks -> {
            pile.addAll(stacks);
            return true;
        };
    }

    // ==================== one participant ====================

    /**
     * Write one credited participant their share-scaled Claim, answering whether one was written. A
     * participant who has left the server is paid only when the fight's rule credits a disconnected
     * participant, and their Claim waits for their next login; one in another world rolls with no factor
     * readings about them, as an offline one does.
     */
    private static boolean claimFor(@Nonnull Store<EntityStore> store, @Nonnull ZigEncounterRun run,
            @Nonnull String encounterId, @Nonnull LootEngine.Resolved resolved, @Nonnull String reward,
            @Nullable String titleKey, @Nullable String difficulty, @Nonnull ParticipationSpec spec,
            @Nonnull ParticipantShare participant, @Nonnull String label) {
        String head = Encounters.LOG_PREFIX + " " + label + " " + participant.playerName() + " run="
                + EncounterRun.shortId(run.runId()) + " encounter=" + encounterId + " reward=" + reward;
        try {
            PlayerRef player = Universe.get().getPlayer(participant.playerId());
            Ref<EntityStore> ref = player == null ? null : player.getReference();
            boolean here = ref != null && ref.isValid() && ref.getStore() == store;
            if (player == null && !spec.creditDisconnected()) {
                SafeLog.info(head + ": offline, and this fight's rule pays nobody who left, so no Claim");
                return false;
            }
            double share = participant.share();
            EncounterClaims.Outcome outcome = EncounterClaims.award(here ? store : null, here ? ref : null,
                    participant.playerId(), resolved, share,
                    EncounterClaims.bossClaim(encounterId, run.runId(), reward, participant.playerId(), titleKey,
                            difficulty, EncounterClaims.FIRST_EACH_DAY),
                    EncounterClaims.placeholders(encounterId, run.runId(), share));
            SafeLog.info(head + " share=" + EncounterClaims.shareText(share) + " " + outcome.describe()
                    + (player == null ? " (offline, it waits)" : ""));
            return outcome.written();
        } catch (Throwable t) {
            SafeLog.warn(head + ": the Claim failed", t);
            return false;
        }
    }

    @Nonnull
    private static DoubleSupplier sampler() {
        return () -> ThreadLocalRandom.current().nextDouble();
    }
}
