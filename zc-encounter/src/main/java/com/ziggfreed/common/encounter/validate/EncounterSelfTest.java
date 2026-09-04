package com.ziggfreed.common.encounter.validate;

import java.util.concurrent.atomic.AtomicBoolean;

import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.run.EncounterSpawner;
import com.ziggfreed.common.encounter.types.EncounterTypes;
import com.ziggfreed.common.util.SafeLog;

/**
 * The boot self-test: once the builders have loaded, the library's own shipped base script must
 * resolve by index and the encounter Types must be registered, or every pack script built on them
 * is silently dead. Says so at SEVERE, once; says the index at INFO when all is well.
 */
public final class EncounterSelfTest {

    /** The Abstract base every zc encounter script builds on, shipped in this library's own pack. */
    public static final String BASE_SCRIPT = "Zc_Encounter_Base";

    /** The spawnable example that walks every Type, shipped beside the base. */
    public static final String EXAMPLE_SCRIPT = "Zc_Encounter_Example";

    private static final AtomicBoolean RAN = new AtomicBoolean();

    private EncounterSelfTest() {
    }

    /** Run once; later calls do nothing. */
    public static void runOnce() {
        if (!RAN.compareAndSet(false, true)) {
            return;
        }
        try {
            BuilderInfo base = EncounterSpawner.encounterInfo(BASE_SCRIPT);
            if (base == null) {
                SafeLog.severe(Encounters.LOG_PREFIX + " the shipped base script '" + BASE_SCRIPT
                        + "' did not load: no pack script built on it can spawn");
            } else {
                SafeLog.info(Encounters.LOG_PREFIX + " base script '" + BASE_SCRIPT + "' loaded at builder index "
                        + base.getIndex());
            }
            BuilderInfo example = EncounterSpawner.spawnableInfo(EXAMPLE_SCRIPT);
            if (example == null) {
                SafeLog.severe(Encounters.LOG_PREFIX + " the shipped example '" + EXAMPLE_SCRIPT
                        + "' did not load or is not spawnable");
            } else {
                SafeLog.info(Encounters.LOG_PREFIX + " example '" + EXAMPLE_SCRIPT + "' loaded at builder index "
                        + example.getIndex() + "; spawn it with /zigencounter spawn " + EXAMPLE_SCRIPT);
            }
            if (!EncounterTypes.isRegistered()) {
                SafeLog.severe(Encounters.LOG_PREFIX + " the encounter Types are not registered: a script naming "
                        + String.join(", ", EncounterTypes.ALL) + " fails to load");
            }
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " the boot self-test itself failed", t);
        }
    }

    /** For a test starting from nothing. */
    public static void resetForTests() {
        RAN.set(false);
    }
}
