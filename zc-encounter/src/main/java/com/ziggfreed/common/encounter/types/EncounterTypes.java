package com.ziggfreed.common.encounter.types;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.npc.NPCPlugin;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.util.SafeLog;

/**
 * The five instruction {@code Type}s this library adds to the shared NPC builder vocabulary, each
 * legal only inside an encounter script: they are the ONLY place a script can reach the library,
 * and what lets a pack-only boss pay out, notify and scale with zero Java.
 *
 * <ul>
 *   <li>{@code ZigGrant} (action) - pay the run's credited participants a loot reference,
 *       share-scaled;</li>
 *   <li>{@code ZigFeedback} (action) - draw an authored FeedbackMoment for each member;</li>
 *   <li>{@code ZigScaleTarget} (action) - apply the binding row's health scale to the subject now;</li>
 *   <li>{@code ZigMembers} (sensor) - true while the live member count is inside a range;</li>
 *   <li>{@code ZigFactor} (sensor) - true while a factor reading for the fight is inside a range.</li>
 * </ul>
 *
 * <p>Registered from the library's setup, before any builder file is read; only the {@code Type}
 * key dispatches, case-sensitively, and a second registration of a name throws, so this runs once.
 *
 * <p>Every action here answers the engine FINISHED on every path, nothing-to-do and failure
 * included: a {@code false} from an action's {@code execute} is the engine's "still running", and
 * a blocking action list waits on it until it says otherwise. {@code ActionsAlwaysFinishTest} pins
 * that where it is written.
 */
public final class EncounterTypes {

    public static final String GRANT = "ZigGrant";
    public static final String FEEDBACK = "ZigFeedback";
    public static final String SCALE_TARGET = "ZigScaleTarget";
    public static final String MEMBERS = "ZigMembers";
    public static final String FACTOR = "ZigFactor";

    /** Every type name, in registration order. */
    public static final List<String> ALL = List.of(GRANT, FEEDBACK, SCALE_TARGET, MEMBERS, FACTOR);

    private static volatile boolean registered;

    private EncounterTypes() {
    }

    /** Register all five (idempotent, guarded). */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        try {
            NPCPlugin npc = NPCPlugin.get();
            if (npc == null) {
                SafeLog.warn(Encounters.LOG_PREFIX + " the NPC plugin is not up, so the encounter Types are not registered");
                return;
            }
            npc.registerCoreComponentType(GRANT, BuilderActionZigGrant::new)
                    .registerCoreComponentType(FEEDBACK, BuilderActionZigFeedback::new)
                    .registerCoreComponentType(SCALE_TARGET, BuilderActionZigScaleTarget::new)
                    .registerCoreComponentType(MEMBERS, BuilderSensorZigMembers::new)
                    .registerCoreComponentType(FACTOR, BuilderSensorZigFactor::new);
            registered = true;
            SafeLog.info(Encounters.LOG_PREFIX + " registered encounter Types: " + String.join(", ", ALL));
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " registering the encounter Types failed", t);
        }
    }

    public static boolean isRegistered() {
        return registered;
    }

    /** What an action or sensor writes when it runs, so a boot capture shows each Type executing. */
    static void executed(@Nonnull String type, @Nonnull String detail) {
        SafeLog.info(Encounters.LOG_PREFIX + " " + type + " " + detail);
    }
}
