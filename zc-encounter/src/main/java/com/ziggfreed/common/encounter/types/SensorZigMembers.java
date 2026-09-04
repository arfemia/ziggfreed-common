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
import com.ziggfreed.common.encounter.run.EncounterRuns;

/** True while the encounter's live member count is inside {@code [min, max]}. */
public class SensorZigMembers extends SensorBase {

    private final int min;
    private final int max;
    private boolean lastMatch;
    private int lastCount = -1;

    public SensorZigMembers(@Nonnull BuilderSensorZigMembers builder, @Nonnull BuilderSupport support) {
        super(builder);
        int[] range = builder.getCount(support);
        this.min = range[0];
        this.max = range[1];
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport executionSupport, double dt,
            @Nonnull Store<EntityStore> store) {
        if (!super.matches(ref, executionSupport, dt, store)) {
            return false;
        }
        int count = EncounterRuns.memberRefs(store, ref).size();
        boolean match = count >= min && count <= max;
        if (match && (!lastMatch || count != lastCount)) {
            EncounterTypes.executed(EncounterTypes.MEMBERS, "encounter=" + EncounterRuns.encounterIdOn(store, ref)
                    + " members=" + count + " in [" + min + ", " + max + "]");
        }
        lastMatch = match;
        lastCount = count;
        return match;
    }

    @Nullable
    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }

    @Override
    public void getInfo(@Nonnull ExecutionSupport executionSupport, @Nonnull ComponentInfo holder) {
        holder.addField("Members in [" + min + ", " + max + "]");
    }
}
