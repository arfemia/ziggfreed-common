package com.ziggfreed.common.encounter.types;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.EncounterScaling;
import com.ziggfreed.common.encounter.run.EncounterSubjects;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;
import com.ziggfreed.common.encounter.seam.EncounterSeams;
import com.ziggfreed.common.util.SafeLog;

/**
 * Applies the binding row's health scale to the fight's subject the moment the script asks.
 *
 * <p>Like every action this library registers, it ALWAYS answers finished: to the engine a
 * {@code false} from {@code execute} means "still running, ask me again next tick", and a blocking
 * action list stays on that action until it says otherwise. An action with nothing to do (no
 * subject bound yet, no run on the entity) says so in the log and lets the list move on.
 */
public class ActionZigScaleTarget extends ActionBase {

    @Nullable private final String targetSlot;

    public ActionZigScaleTarget(@Nonnull BuilderActionZigScaleTarget builder, @Nonnull BuilderSupport support) {
        super(builder);
        String slot = builder.getTargetSlot(support);
        this.targetSlot = slot == null || slot.isBlank() ? null : slot;
    }

    @Override
    public boolean execute(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport executionSupport,
            @Nullable InfoProvider sensorInfo, double dt, @Nonnull Store<EntityStore> store) {
        super.execute(ref, executionSupport, sensorInfo, dt, store);
        try {
            ZigEncounterRun run = EncounterRuns.runOn(store, ref);
            String encounterId = EncounterRuns.encounterIdOn(store, ref);
            if (run == null || encounterId == null) {
                EncounterTypes.executed(EncounterTypes.SCALE_TARGET, "this entity carries no run, nothing to scale");
                return true;
            }
            EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(encounterId);
            Ref<EntityStore> subject = subject(store, ref, executionSupport, sensorInfo, row);
            if (subject == null) {
                EncounterTypes.executed(EncounterTypes.SCALE_TARGET, "run=" + EncounterRun.shortId(run.runId())
                        + " encounter=" + encounterId + ": no subject to scale");
                return true;
            }
            EncounterBindingAsset.Scale spec = row == null ? null : row.getScale();
            List<Ref<EntityStore>> members = EncounterRuns.memberRefs(store, ref);
            double perPower = spec == null ? EncounterBindingAsset.Scale.DEFAULT_HEALTH_PER_POWER_POINT
                    : spec.healthPerPowerPoint();
            double power = perPower != 0.0 ? EncounterSeams.aggregatedPower(store, subject, members) : 0.0;
            int memberCount = Math.max(members.size(), run.seedMembers().size());
            double factor = EncounterScaling.factor(spec, memberCount, power, run.healthMultiplier());
            boolean changed = EncounterScaling.apply(store, subject, factor, !run.isScaleApplied());
            run.noteScale(factor);
            EncounterTypes.executed(EncounterTypes.SCALE_TARGET, "run=" + EncounterRun.shortId(run.runId())
                    + " encounter=" + encounterId + " factor=" + Math.round(factor * 100.0) / 100.0
                    + " members=" + memberCount + (changed ? "" : " (already at that scale)"));
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " " + EncounterTypes.SCALE_TARGET + " failed", t);
        }
        return true;
    }

    @Nullable
    private Ref<EntityStore> subject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ExecutionSupport executionSupport, @Nullable InfoProvider sensorInfo,
            @Nullable EncounterBindingAsset row) {
        if (targetSlot != null) {
            MarkedEntitySupport slots = executionSupport.getMarkedEntitySupport();
            Ref<EntityStore> named = slots == null ? null : slots.getMarkedEntityRef(targetSlot);
            if (named != null && named.isValid()) {
                return named;
            }
        }
        IPositionProvider position = sensorInfo == null ? null : sensorInfo.getPositionProvider();
        Ref<EntityStore> sensed = position == null ? null : position.getTarget();
        if (sensed != null && sensed.isValid() && targetSlot == null) {
            return sensed;
        }
        return EncounterSubjects.resolve(store, ref, row == null ? null : row.getSubject(), row != null);
    }
}
