package com.ziggfreed.common.encounter;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.rewards.asset.RewardAsset;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.ziggfreed.common.encounter.command.ZigEncounterCommand;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.payout.ZigClaimReward;
import com.ziggfreed.common.encounter.run.ZigEncounterRest;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;
import com.ziggfreed.common.encounter.signal.EncounterSignalSystem;
import com.ziggfreed.common.encounter.system.EncounterAddSystem;
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
 * {@code setup()}: the run and rest components, the six encounter Types, the ZigClaim reward type,
 * the systems, the builder reload listener, the content audit, the command family and the boot
 * self-test. Registration
 * only; every decision lives in the module behind it.
 */
public final class EncounterBootstrap {

    private EncounterBootstrap() {
    }

    /** Install the whole module. */
    public static void install(@Nonnull JavaPlugin plugin) {
        registerComponents(plugin);
        EncounterTypes.register();
        registerRewardType(plugin);
        registerSystems(plugin);
        registerReloadListener();
        registerAudit(plugin);
        registerCommand(plugin);
        registerSelfTest(plugin);
    }

    /**
     * The run component, registered without a codec so a chunk save never carries it: the engine
     * rebuilds a fight from its script on every load, and a persisted run beside that would be a
     * second state machine. The rest component, registered WITH one: the rest between fights is
     * the one thing about a site that must outlive a reload, since the script restarts at its
     * start state on every load and a manual spawn marker never reads its own respawn gate.
     */
    private static void registerComponents(@Nonnull JavaPlugin plugin) {
        ZigEncounterRun.register(plugin.getEntityStoreRegistry());
        ZigEncounterRest.register(plugin.getEntityStoreRegistry());
    }

    /**
     * The boss Claim as a native reward type, {@code "Type": "ZigClaim"} under {@code Server/Rewards/},
     * registered into the engine's own reward codec before any reward file decodes, so a script's
     * {@code GrantReward} names one exactly as it names the game's own. The library manifest depends on
     * {@code Hytale:Rewards}, which registers that store in its own setup, ahead of this one.
     */
    private static void registerRewardType(@Nonnull JavaPlugin plugin) {
        try {
            plugin.getCodecRegistry(RewardAsset.CODEC).register(ZigClaimReward.TYPE, ZigClaimReward.class,
                    ZigClaimReward.CODEC);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " could not register the " + ZigClaimReward.TYPE + " reward type", t);
        }
    }

    /**
     * The signal bridge, the attach and remove lifecycle pair, the death latch, the observing
     * damage system, the add notice and the tick. Each registration guarded on its own, so one engine
     * refusal costs that system and not the module.
     */
    private static void registerSystems(@Nonnull JavaPlugin plugin) {
        register(plugin, "signal", new EncounterSignalSystem());
        register(plugin, "attach", new EncounterLifecycleSystems.Attach());
        register(plugin, "remove", new EncounterLifecycleSystems.Remove());
        register(plugin, "death", new EncounterDeathSystem());
        register(plugin, "damage", new EncounterDamageSystem());
        register(plugin, "adds", new EncounterAddSystem());
        register(plugin, "tick", new EncounterTickSystem());
    }

    private static void register(@Nonnull JavaPlugin plugin, @Nonnull String label, @Nonnull ISystem<EntityStore> system) {
        try {
            plugin.getEntityStoreRegistry().registerSystem(system);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " could not register the " + label + " system", t);
        }
    }

    /**
     * A builder hot-reload restarts every encounter built on it; note it so the removal reads as a
     * reload, and read the scripts again before the engine re-adds those encounters.
     */
    private static void registerReloadListener() {
        try {
            NPCPlugin.get().getBuilderManager().addBuilderReloadListener(info -> {
                EncounterLifecycleSystems.noteReload(info);
                EncounterScripts.rewarm();
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
