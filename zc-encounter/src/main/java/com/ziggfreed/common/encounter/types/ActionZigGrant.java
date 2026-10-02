package com.ziggfreed.common.encounter.types;

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
 * Pays the run's credited participants a loot reference, share-scaled, from inside the script. The
 * run is read off the executing encounter entity; the credit rules are the matched participation
 * rule under the binding row's own override, exactly as a defeat settles them.
 *
 * <p>Like every action this library registers, it ALWAYS answers finished: to the engine a
 * {@code false} from {@code execute} means "still running, ask me again next tick", and a blocking
 * action list stays on that action until it says otherwise. A grant with nothing to pay (no loot
 * authored, nobody credited, no run on the entity) says so in the log and lets the list move on.
 */
public class ActionZigGrant extends ActionBase {

    private final boolean toMembers;
    private final boolean toKiller;
    private final boolean queueIfOffline;
    @Nullable private final LootRef loot;
    private final boolean lootUnreadable;

    public ActionZigGrant(@Nonnull BuilderActionZigGrant builder, @Nonnull BuilderSupport support) {
        super(builder);
        this.toMembers = builder.getToMembers(support);
        this.toKiller = builder.getToKiller(support);
        this.queueIfOffline = builder.getQueueIfOffline(support);
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
            if (loot == null || loot.isEmpty()) {
                EncounterTypes.executed(EncounterTypes.GRANT, "run=" + EncounterRun.shortId(run.runId())
                        + " encounter=" + encounterId + (lootUnreadable
                                ? ": its Loot could not be read when the script loaded, nothing to pay"
                                : ": no Loot authored, nothing to pay"));
                return true;
            }
            EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(encounterId);
            ParticipationSpec spec = EncounterLifecycle.specFor(store, run, row);
            ParticipationShares shares = toMembers ? EncounterLifecycle.settle(store, run, spec)
                    : ParticipationShares.EMPTY;
            EncounterTypes.executed(EncounterTypes.GRANT, "run=" + EncounterRun.shortId(run.runId()) + " encounter="
                    + encounterId + " participants=" + shares.size() + " credited=" + shares.credited().size()
                    + (toKiller ? " +killer" : ""));
            EncounterLoot.grantShares(store, run, encounterId, loot, spec, shares, toKiller, queueIfOffline,
                    EncounterTypes.GRANT);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " " + EncounterTypes.GRANT + " failed", t);
        }
        return true;
    }
}
