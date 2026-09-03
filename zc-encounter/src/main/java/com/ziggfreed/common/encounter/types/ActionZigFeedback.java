package com.ziggfreed.common.encounter.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.payout.EncounterFeedback;
import com.ziggfreed.common.encounter.run.EncounterLifecycle;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;
import com.ziggfreed.common.util.SafeLog;

/**
 * Draws an authored FeedbackMoment for the run's members (and, on request, the whole world).
 *
 * <p>Like every action this library registers, it ALWAYS answers finished: to the engine a
 * {@code false} from {@code execute} means "still running, ask me again next tick", and a blocking
 * action list stays on that action until it says otherwise. A beat with nobody to draw for, or no
 * run on the entity, says so in the log and lets the list move on.
 */
public class ActionZigFeedback extends ActionBase {

    private final String moment;
    private final boolean toMembers;
    private final boolean toWorld;
    private final Map<String, Object> authoredArgs;

    public ActionZigFeedback(@Nonnull BuilderActionZigFeedback builder, @Nonnull BuilderSupport support) {
        super(builder);
        this.moment = builder.getMoment(support);
        this.toMembers = builder.getToMembers(support);
        this.toWorld = builder.getToWorld(support);
        this.authoredArgs = argsOf(builder.getArgs());
    }

    @Override
    public boolean canExecute(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport executionSupport,
            @Nullable InfoProvider sensorInfo, double dt, @Nonnull Store<EntityStore> store) {
        return super.canExecute(ref, executionSupport, sensorInfo, dt, store) && moment != null && !moment.isBlank();
    }

    @Override
    public boolean execute(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport executionSupport,
            @Nullable InfoProvider sensorInfo, double dt, @Nonnull Store<EntityStore> store) {
        super.execute(ref, executionSupport, sensorInfo, dt, store);
        try {
            ZigEncounterRun run = EncounterRuns.runOn(store, ref);
            String encounterId = EncounterRuns.encounterIdOn(store, ref);
            if (run == null || encounterId == null) {
                EncounterTypes.executed(EncounterTypes.FEEDBACK, "this entity carries no run, nothing to draw");
                return true;
            }
            Set<UUID> audience = new LinkedHashSet<>();
            if (toMembers) {
                audience.addAll(EncounterRuns.memberIds(store, ref));
            }
            if (toWorld) {
                audience.addAll(worldPlayers(store));
            }
            EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(encounterId);
            long now = System.currentTimeMillis();
            Map<String, Object> args = new LinkedHashMap<>();
            args.put(EncounterFeedback.ENCOUNTER_ARG, encounterId);
            args.put(EncounterFeedback.TITLE_ARG, EncounterLifecycle.titleOf(encounterId, row));
            args.put(EncounterFeedback.MEMBERS_ARG, audience.size());
            args.put(EncounterFeedback.SECONDS_ARG, run.elapsedMs(now) / 1000L);
            if (run.phase() != null) {
                args.put(EncounterFeedback.PHASE_ARG, run.phase());
            }
            args.putAll(authoredArgs);
            EncounterTypes.executed(EncounterTypes.FEEDBACK, "run=" + EncounterRun.shortId(run.runId()) + " encounter="
                    + encounterId + " moment=" + moment + " audience=" + audience.size());
            EncounterFeedback.fire(store, moment, new ArrayList<>(audience), args);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " " + EncounterTypes.FEEDBACK + " failed", t);
        }
        return true;
    }

    /** Every player standing in the encounter's world, by uuid, read off the universe's own roster. */
    @Nonnull
    private static List<UUID> worldPlayers(@Nonnull Store<EntityStore> store) {
        List<UUID> out = new ArrayList<>();
        UUID worldUuid = EncounterLifecycle.worldUuid(store);
        if (worldUuid == null) {
            return out;
        }
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (ref != null && ref.getUuid() != null && worldUuid.equals(ref.getWorldUuid())) {
                out.add(ref.getUuid());
            }
        }
        return out;
    }

    /** The authored {@code Args} object as plain values: strings, numbers and booleans. */
    @Nonnull
    private static Map<String, Object> argsOf(@Nullable JsonElement raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null || !raw.isJsonObject()) {
            return out;
        }
        JsonObject object = raw.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive()) {
                continue;
            }
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                out.put(entry.getKey(), primitive.getAsBoolean());
            } else if (primitive.isNumber()) {
                double number = primitive.getAsDouble();
                out.put(entry.getKey(), number == Math.rint(number) ? (Object) (long) number : (Object) number);
            } else {
                out.put(entry.getKey(), primitive.getAsString());
            }
        }
        return out;
    }
}
