package com.ziggfreed.common.encounter;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.ziggfreed.common.encounter.command.ZigEncounterCommand;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;
import com.ziggfreed.common.encounter.signal.EncounterSignalSystem;
import com.ziggfreed.common.encounter.system.EncounterDamageSystem;
import com.ziggfreed.common.encounter.system.EncounterDeathSystem;
import com.ziggfreed.common.encounter.system.EncounterLifecycleSystems;
import com.ziggfreed.common.encounter.system.EncounterTickSystem;
import com.ziggfreed.common.encounter.types.EncounterTypes;
import com.ziggfreed.common.encounter.validate.EncounterAudit;
import com.ziggfreed.common.encounter.validate.EncounterScripts;
import com.ziggfreed.common.encounter.validate.EncounterSelfTest;
import com.ziggfreed.common.util.SafeLog;

/**
 * The encounter module's registration phase, called as one line from the wiring root's
 * {@code setup()}: the run component, the five encounter Types, the systems, the builder reload
 * listener, the content audit, the command family and the boot self-test. Registration only; every
 * decision lives in the module behind it.
 */
public final class EncounterBootstrap {

    private EncounterBootstrap() {
    }

    /** Install the whole module. */
    public static void install(@Nonnull JavaPlugin plugin) {
        registerRunComponent(plugin);
        EncounterTypes.register();
        registerSystems(plugin);
        registerReloadListener();
        registerAudit(plugin);
        registerCommand(plugin);
        registerSelfTest(plugin);
    }

    /**
     * The run component, registered without a codec so a chunk save never carries it: the engine
     * rebuilds a fight from its script on every load, and a persisted run beside that would be a
     * second state machine.
     */
    private static void registerRunComponent(@Nonnull JavaPlugin plugin) {
        ZigEncounterRun.register(plugin.getEntityStoreRegistry());
    }

    /**
     * The signal bridge, the attach and remove lifecycle pair, the death latch, the observing
     * damage system and the tick. Each registration guarded on its own, so one engine refusal costs
     * that system and not the module.
     */
    private static void registerSystems(@Nonnull JavaPlugin plugin) {
        register(plugin, "signal", new EncounterSignalSystem());
        register(plugin, "attach", new EncounterLifecycleSystems.Attach());
        register(plugin, "remove", new EncounterLifecycleSystems.Remove());
        register(plugin, "death", new EncounterDeathSystem());
        register(plugin, "damage", new EncounterDamageSystem());
        register(plugin, "tick", new EncounterTickSystem());
    }

    private static void register(@Nonnull JavaPlugin plugin, @Nonnull String label, @Nonnull ISystem<EntityStore> system) {
        try {
            plugin.getEntityStoreRegistry().registerSystem(system);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " could not register the " + label + " system", t);
        }
    }

    /** A builder hot-reload restarts every encounter built on it; note it so the removal reads as a reload. */
    private static void registerReloadListener() {
        try {
            NPCPlugin.get().getBuilderManager().addBuilderReloadListener(info -> {
                EncounterLifecycleSystems.noteReload(info);
                EncounterScripts.invalidate();
            });
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " could not hang the builder reload listener", t);
        }
    }

    /** The content audit, once per boot at first player ready, when every store and script has loaded. */
    private static void registerAudit(@Nonnull JavaPlugin plugin) {
        try {
            plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, event -> EncounterAudit.runLateAudit());
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " could not hang the content audit", t);
        }
    }

    private static void registerCommand(@Nonnull JavaPlugin plugin) {
        try {
            plugin.getCommandRegistry().registerCommand(new ZigEncounterCommand());
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " could not register /zigencounter", t);
        }
    }

    /** The boot self-test, on the first world to load, by which time every builder has been read. */
    private static void registerSelfTest(@Nonnull JavaPlugin plugin) {
        try {
            plugin.getEventRegistry().registerGlobal(AddWorldEvent.class, event -> EncounterSelfTest.runOnce());
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " could not hang the boot self-test", t);
        }
    }
}
