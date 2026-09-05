package com.ziggfreed.common.encounter.types;

import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.corecomponents.SensorBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.util.ComponentInfo;
import com.ziggfreed.common.encounter.run.EncounterRest;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.ZigEncounterRest;

/**
 * True while the executing encounter entity carries no rest, or the world's game time is past the
 * one it carries; false while the site rests. One log line each time the answer changes.
 */
public class SensorZigRested extends SensorBase {

    @Nullable private Boolean lastMatch;

    public SensorZigRested(@Nonnull BuilderSensorZigRested builder) {
        super(builder);
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport executionSupport, double dt,
            @Nonnull Store<EntityStore> store) {
        if (!super.matches(ref, executionSupport, dt, store)) {
            return false;
        }
        ZigEncounterRest rest = EncounterRest.restOn(store, ref);
        Instant now = EncounterRest.gameTime(store);
        boolean match = now == null || ZigEncounterRest.rested(rest, now);
        if (lastMatch == null || lastMatch != match) {
            long left = rest == null || now == null ? 0L : rest.secondsLeft(now);
            EncounterTypes.executed(EncounterTypes.RESTED, "encounter=" + EncounterRuns.encounterIdOn(store, ref)
                    + (match ? " rested" : " resting, " + left + "s of world time to go"));
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
        holder.addField("Rested since the last defeat");
    }
}
