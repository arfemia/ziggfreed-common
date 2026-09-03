package com.ziggfreed.common.encounter.payout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
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
import com.ziggfreed.common.encounter.seam.EncounterSeams;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.instance.reward.NativeLootService;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootCues;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.reward.DroplistRewardKind;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;

/**
 * What a fight pays, through the library's own loot vocabulary and issuance pass and nothing new.
 *
 * <p><b>A defeat pays each credited participant separately, and the rolls SCALE with their share.</b>
 * The row's {@code Loot.OnDefeat} is evaluated once per participant against their own factor
 * readings, then every roll they won is KEPT with a chance equal to their share: the top contributor
 * (share 1) keeps everything, a participant at 0.3 keeps about a third of what they would have. What
 * is kept is issued as ordinary reward specs through the one grant pass, so an item lands in the
 * bag with the usual overflow rule, a command runs with the usual placeholders, and an OFFLINE
 * participant's whole payout is parked as replayable commands on the queue every other library
 * payout retries through.
 *
 * <p>A phase drop rolls once and spills on the ground at the subject, the way a mob's own death
 * drops do; it has no player to be about, so a registered reward kind in a phase drop is reported
 * and skipped rather than paid to nobody.
 */
public final class EncounterLoot {

    /** The source label every encounter payout carries in logs and retry commands. */
    private static final String SOURCE_PREFIX = "encounter:";

    private EncounterLoot() {
    }

    /**
     * Pay the defeat: {@code Loot.OnDefeat} per credited participant (plus the last hitter at a full
     * share when {@code ToKiller}), share-scaled, offline participants queued when the row allows.
     */
    public static void grantDefeat(@Nonnull Store<EntityStore> store, @Nonnull ZigEncounterRun run,
            @Nonnull String encounterId, @Nullable EncounterBindingAsset row, @Nonnull ParticipationSpec spec,
            @Nonnull ParticipationShares shares) {
        EncounterBindingAsset.Loot loot = row == null ? null : row.getLoot();
        LootRef ref = loot == null ? null : loot.getOnDefeat();
        if (ref == null || ref.isEmpty()) {
            return;
        }
        List<Recipient> recipients = recipients(run, shares, loot.toKiller());
        if (recipients.isEmpty()) {
            SafeLog.info(Encounters.LOG_PREFIX + " payout run=" + EncounterRun.shortId(run.runId()) + " encounter="
                    + encounterId + ": no credited participant to pay");
            return;
        }
        LootEngine.Resolved resolved = LootEngine.resolve(ref, unknown -> SafeLog.warn(Encounters.LOG_PREFIX
                + " '" + encounterId + "' names no loot table called '" + unknown + "'"));
        String sourceId = SOURCE_PREFIX + encounterId;
        for (Recipient recipient : recipients) {
            grantTo(store, run, encounterId, sourceId, resolved, recipient, spec, loot.queueIfOffline());
        }
    }

    /**
     * Pay everybody credited in {@code shares} the loot {@code ref}, share-scaled: the path the
     * {@code ZigGrant} action takes, with no row involved.
     */
    public static void grantShares(@Nonnull Store<EntityStore> store, @Nonnull ZigEncounterRun run,
            @Nonnull String encounterId, @Nonnull LootRef ref, @Nonnull ParticipationSpec spec,
            @Nonnull ParticipationShares shares, boolean toKiller, boolean queueIfOffline, @Nonnull String label) {
        List<Recipient> recipients = recipients(run, shares, toKiller);
        if (recipients.isEmpty()) {
            SafeLog.info(Encounters.LOG_PREFIX + " " + label + " run=" + EncounterRun.shortId(run.runId()) + " encounter="
                    + encounterId + ": nobody to pay (0 credited participants)");
            return;
        }
        LootEngine.Resolved resolved = LootEngine.resolve(ref, unknown -> SafeLog.warn(Encounters.LOG_PREFIX
                + " '" + encounterId + "' names no loot table called '" + unknown + "'"));
        String sourceId = SOURCE_PREFIX + encounterId;
        for (Recipient recipient : recipients) {
            grantTo(store, run, encounterId, sourceId, resolved, recipient, spec, queueIfOffline);
        }
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
            int commands = 0;
            for (LootEngine.Selected selected : LootEngine.select(resolved.rolls(), resolved.pools(), null, lookup,
                    sampler())) {
                LootGrants grants = selected.grants();
                if (grants == null) {
                    continue;
                }
                for (LootGrants.Item item : grants.itemsOrEmpty()) {
                    stacks.add(new ItemStack(item.getItem(), Math.max(1, item.effectiveCount())));
                }
                String[] dropLists = grants.getDropLists();
                if (dropLists != null) {
                    for (String dropList : dropLists) {
                        if (dropList != null && !dropList.isBlank()) {
                            stacks.addAll(NativeLootService.rollNative(dropList));
                        }
                    }
                }
                String[] cmds = grants.getCommands();
                if (cmds != null) {
                    commands += runPhaseCommands(cmds, run, encounterId, state);
                }
                if (!grants.rewardSpecs().isEmpty()) {
                    SafeLog.warn(Encounters.LOG_PREFIX + " phase '" + state + "' of '" + encounterId
                            + "' authors a registered reward kind, which needs a player; only items, drop lists "
                            + "and commands can drop in the world");
                }
            }
            if (!stacks.isEmpty()) {
                Vector3d at3 = new Vector3d(at.getPosition()).add(0.0, 1.0, 0.0);
                NativeLootService.spawnInWorld(store, at3, Rotation3f.IDENTITY, stacks);
            }
            SafeLog.info(Encounters.LOG_PREFIX + " phase drop run=" + EncounterRun.shortId(run.runId()) + " encounter="
                    + encounterId + " phase=" + state + " stacks=" + stacks.size() + " commands=" + commands);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " phase drop of '" + encounterId + "' failed", t);
        }
    }

    // ==================== one participant ====================

    /** Who is paid, at what share. */
    private record Recipient(@Nonnull UUID playerId, @Nonnull String name, double share, boolean killer) {
    }

    @Nonnull
    private static List<Recipient> recipients(@Nonnull ZigEncounterRun run, @Nonnull ParticipationShares shares,
            boolean toKiller) {
        List<Recipient> out = new ArrayList<>();
        boolean killerListed = false;
        UUID killer = run.lastHitter();
        for (ParticipantShare share : shares.credited()) {
            boolean isKiller = killer != null && killer.equals(share.playerId());
            killerListed |= isKiller;
            out.add(new Recipient(share.playerId(), share.playerName(), toKiller && isKiller ? 1.0 : share.share(),
                    isKiller));
        }
        if (toKiller && killer != null && !killerListed) {
            String name = run.memberName(killer);
            out.add(new Recipient(killer, name == null ? "" : name, 1.0, true));
        }
        return out;
    }

    private static void grantTo(@Nonnull Store<EntityStore> store, @Nonnull ZigEncounterRun run,
            @Nonnull String encounterId, @Nonnull String sourceId, @Nonnull LootEngine.Resolved resolved,
            @Nonnull Recipient recipient, @Nonnull ParticipationSpec spec, boolean queueIfOffline) {
        try {
            PlayerRef player = Universe.get().getPlayer(recipient.playerId());
            Ref<EntityStore> ref = player == null ? null : player.getReference();
            boolean online = ref != null && ref.isValid() && ref.getStore() == store;
            Subject subject;
            BiConsumer<Subject, String> queue = null;
            if (online) {
                subject = EncounterSeams.subjectFor(store, ref);
                if (subject == null) {
                    return;
                }
            } else {
                if (!spec.creditDisconnected() || !queueIfOffline) {
                    SafeLog.info(Encounters.LOG_PREFIX + " payout run=" + EncounterRun.shortId(run.runId())
                            + ": " + recipient.name() + " is offline and is credited but not paid");
                    return;
                }
                queue = EncounterSeams.rewardQueue();
                if (queue == null) {
                    return;
                }
                subject = Subject.of(recipient.playerId(), recipient.name());
            }
            FactorLookup lookup = FactorLookup.through(EncounterFactors.registry(),
                    FactorContext.about(online ? store : null, online ? ref : null));
            DoubleSupplier sample = sampler();
            List<LootEngine.Selected> won = LootEngine.select(resolved.rolls(), resolved.pools(), null, lookup, sample);
            List<RewardSpec> specs = new ArrayList<>();
            List<String> cues = new ArrayList<>();
            int kept = 0;
            for (LootEngine.Selected selected : won) {
                if (sample.getAsDouble() >= recipient.share()) {
                    continue; // this roll did not survive the share
                }
                kept++;
                specs.addAll(toSpecs(selected.grants(), run, encounterId, recipient));
                if (selected.cue() != null && !selected.cue().isBlank()) {
                    cues.add(selected.cue());
                }
            }
            RewardGrants.GrantOutcome outcome = specs.isEmpty() ? RewardGrants.GrantOutcome.EMPTY
                    : RewardGrants.grantAll(specs, subject, sourceId, RewardKinds.shared(), online, queue,
                            message -> SafeLog.warn(Encounters.LOG_PREFIX + " " + message));
            if (online && !cues.isEmpty()) {
                LootCues.presentAll(cues, subject, sourceId);
            }
            SafeLog.info(Encounters.LOG_PREFIX + " paid " + recipient.name() + " run=" + EncounterRun.shortId(run.runId())
                    + " share=" + String.format(Locale.ROOT, "%.2f", recipient.share())
                    + (recipient.killer() ? " (killer)" : "") + " rolls=" + kept + "/" + won.size()
                    + " granted=" + outcome.granted() + " queued=" + outcome.queued() + " lost=" + outcome.failed()
                    + (online ? "" : " (offline)"));
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " paying " + recipient.name() + " for '" + encounterId + "' failed", t);
        }
    }

    /** One selected grants group as reward specs, so the one grant pass issues (or queues) all of it. */
    @Nonnull
    private static List<RewardSpec> toSpecs(@Nullable LootGrants grants, @Nonnull ZigEncounterRun run,
            @Nonnull String encounterId, @Nonnull Recipient recipient) {
        List<RewardSpec> out = new ArrayList<>();
        if (grants == null) {
            return out;
        }
        for (LootGrants.Item item : grants.itemsOrEmpty()) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("item", item.getItem());
            params.put("count", Integer.toString(Math.max(1, item.effectiveCount())));
            out.add(RewardSpec.of(LootRewardKinds.KIND_ITEM, params));
        }
        String[] dropLists = grants.getDropLists();
        if (dropLists != null) {
            for (String dropList : dropLists) {
                if (dropList != null && !dropList.isBlank()) {
                    out.add(RewardSpec.of(DroplistRewardKind.KIND, "droplist", dropList));
                }
            }
        }
        String[] commands = grants.getCommands();
        if (commands != null) {
            for (String command : commands) {
                if (command != null && !command.isBlank()) {
                    Map<String, String> params = new LinkedHashMap<>();
                    params.put(LootRewardKinds.P_COMMAND, command);
                    params.put("encounter", encounterId);
                    params.put("run", EncounterRun.shortId(run.runId()));
                    params.put("share", String.format(Locale.ROOT, "%.2f", recipient.share()));
                    out.add(RewardSpec.of(LootRewardKinds.KIND_COMMAND, params));
                }
            }
        }
        out.addAll(grants.rewardSpecs());
        return out;
    }

    private static int runPhaseCommands(@Nonnull String[] commands, @Nonnull ZigEncounterRun run,
            @Nonnull String encounterId, @Nonnull String state) {
        int ran = 0;
        for (String command : commands) {
            if (command == null || command.isBlank()) {
                continue;
            }
            String line = command.replace("{encounter}", encounterId).replace("{phase}", state)
                    .replace("{run}", EncounterRun.shortId(run.runId()));
            try {
                CommandRunner.CONSOLE.dispatch(line);
                ran++;
            } catch (Throwable t) {
                SafeLog.warn(Encounters.LOG_PREFIX + " phase command '" + line + "' failed: " + t.getMessage());
            }
        }
        return ran;
    }

    @Nonnull
    private static DoubleSupplier sampler() {
        return () -> ThreadLocalRandom.current().nextDouble();
    }
}
