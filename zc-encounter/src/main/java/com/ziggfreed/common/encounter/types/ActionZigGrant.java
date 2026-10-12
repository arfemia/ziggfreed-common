package com.ziggfreed.common.encounter.types;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.asset.ParticipationSpec;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.ledger.ParticipationShares;
import com.ziggfreed.common.encounter.payout.EncounterLoot;
import com.ziggfreed.common.encounter.run.EncounterLifecycle;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.util.SafeLog;

/**
 * The retired script grant, kept so a script written for it still pays: it writes each of the run's
 * credited participants a Claim of its loot reference, share-scaled, through the same Claim path a
 * {@code ZigClaim} reward and a binding row's {@code Loot.OnDefeat} take, and never into the bag. After
 * the defeat it pays from the shares the defeat settled; before it, from the shares as they stand. Its
 * {@code ToKiller} and {@code QueueIfOffline} are read and ignored. The first time a script runs one it
 * says so once, naming the native {@code GrantReward} of a {@code ZigClaim} reward to move to.
 *
 * <p>Like every action this library registers, it ALWAYS answers finished: to the engine a
 * {@code false} from {@code execute} means "still running, ask me again next tick", and a blocking
 * action list stays on that action until it says otherwise. A grant with nothing to pay (no loot
 * authored, nobody credited, no run on the entity) says so in the log and lets the list move on.
 */
public class ActionZigGrant extends ActionBase {

    /** The scripts already told this action is retired, one warning per script. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final boolean toMembers;
    @Nullable private final LootRef loot;
    private final boolean lootUnreadable;

    public ActionZigGrant(@Nonnull BuilderActionZigGrant builder, @Nonnull BuilderSupport support) {
        super(builder);
        this.toMembers = builder.getToMembers(support);
        // Decoded once when the script loaded, in the script's own asset context (see the builder).
        this.loot = builder.getLootRef();
        this.lootUnreadable = builder.isLootUnreadable();
    }

    @Override
    public boolean execute(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport executionSupport,
            @Nullable InfoProvider sensorInfo, double dt, @Nonnull Store<EntityStore> store) {
        super.execute(ref, executionSupport, sensorInfo, dt, store);
        try {
            ZigEncounterRun run = EncounterRuns.runOn(store, ref);
            String encounterId = EncounterRuns.encounterIdOn(store, ref);
            if (run == null || encounterId == null) {
                EncounterTypes.executed(EncounterTypes.GRANT, "this entity carries no run, nothing to pay");
                return true;
            }
            warnRetiredOnce(encounterId);
            if (loot == null || loot.isEmpty()) {
                EncounterTypes.executed(EncounterTypes.GRANT, "run=" + EncounterRun.shortId(run.runId())
                        + " encounter=" + encounterId + (lootUnreadable
                                ? ": its Loot could not be read when the script loaded, nothing to pay"
                                : ": no Loot authored, nothing to pay"));
                return true;
            }
            EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(encounterId);
            ParticipationSpec spec = EncounterLifecycle.specFor(store, run, row);
            ParticipationShares shares = !toMembers ? ParticipationShares.EMPTY
                    : run.isDefeated() ? run.settledShares() : EncounterLifecycle.settle(store, run, spec);
            EncounterTypes.executed(EncounterTypes.GRANT, "run=" + EncounterRun.shortId(run.runId()) + " encounter="
                    + encounterId + " participants=" + shares.size() + " credited=" + shares.credited().size());
            EncounterLoot.grantShares(store, run, encounterId, row, loot, spec, shares, EncounterTypes.GRANT,
                    EncounterTypes.GRANT);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " " + EncounterTypes.GRANT + " failed", t);
        }
        return true;
    }

    /** Say once per script that this action is retired and what replaces it. */
    private static void warnRetiredOnce(@Nonnull String encounterId) {
        if (!WARNED.add(encounterId.toLowerCase(Locale.ROOT))) {
            return;
        }
        SafeLog.warn(Encounters.LOG_PREFIX + " '" + encounterId + "' pays through " + EncounterTypes.GRANT
                + ", which is retired: it now writes each credited player a Claim they collect from the Claims tab, "
                + "and its ToKiller and QueueIfOffline are ignored. Grant a ZigClaim reward with the engine's "
                + "GrantReward action instead.");
    }
}
