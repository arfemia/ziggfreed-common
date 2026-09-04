package com.ziggfreed.common.encounter.types;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.SensorBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.util.ComponentInfo;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.run.EncounterFactors;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.EncounterSubjects;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;
import com.ziggfreed.common.factor.FactorContext;

/** True while a factor reading about the fight is inside {@code [min, max]}. */
public class SensorZigFactor extends SensorBase {

    private final String factor;
    @Nullable private final String param;
    private final double min;
    private final double max;
    private boolean lastMatch;

    public SensorZigFactor(@Nonnull BuilderSensorZigFactor builder, @Nonnull BuilderSupport support) {
        super(builder);
        this.factor = builder.getFactor(support);
        String p = builder.getParam(support);
        this.param = p == null || p.isBlank() ? null : p;
        double[] range = builder.getValue(support);
        this.min = range[0];
        this.max = range[1];
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport executionSupport, double dt,
            @Nonnull Store<EntityStore> store) {
        if (!super.matches(ref, executionSupport, dt, store)) {
            return false;
        }
        ZigEncounterRun run = EncounterRuns.runOn(store, ref);
        if (run == null) {
            return false;
        }
        String encounterId = EncounterRuns.encounterIdOn(store, ref);
        EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(encounterId);
        Ref<EntityStore> subject = EncounterSubjects.resolve(store, ref, row == null ? null : row.getSubject(),
                row != null);
        int members = EncounterRuns.memberRefs(store, ref).size();
        FactorContext ctx = EncounterFactors.contextFor(store, subject,
                new EncounterFactors.RunReading(run, members, System.currentTimeMillis()));
        Double reading = EncounterFactors.registry().resolve(factor, ctx.withParam(param));
        boolean match = reading != null && reading >= min && reading <= max;
        if (match && !lastMatch) {
            EncounterTypes.executed(EncounterTypes.FACTOR, "encounter=" + encounterId + " factor=" + factor
                    + (param == null ? "" : "/" + param) + " reads " + reading + " in [" + min + ", " + max + "]");
        }
        lastMatch = match;
        return match;
    }

    @Nullable
    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }

    @Override
    public void getInfo(@Nonnull ExecutionSupport executionSupport, @Nonnull ComponentInfo holder) {
        holder.addField("Factor " + factor + (param == null ? "" : "/" + param) + " in [" + min + ", " + max + "]");
    }
}
