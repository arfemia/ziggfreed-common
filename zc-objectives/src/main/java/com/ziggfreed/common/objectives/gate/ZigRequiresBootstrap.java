package com.ziggfreed.common.objectives.gate;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.adventure.objectives.config.taskcondition.TaskConditionAsset;
import com.hypixel.hytale.builtin.adventure.objectives.config.triggercondition.ObjectiveLocationTriggerCondition;
import com.hypixel.hytale.builtin.adventure.worldevents.WorldEventsPlugin;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerCondition;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceRequirement;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers {@code ZigRequires}, the library's one {@code Requires} gate, as a Type in every open condition map
 * the engine reads, so a vanilla-shaped asset asks it inline under the one name:
 * <ul>
 *   <li>a trigger volume's {@code Conditions} ({@link ZigRequiresTriggerCondition});</li>
 *   <li>an entity condition, such as an effect's {@code ApplyConditions} ({@link ZigRequiresEntityCondition},
 *       key {@code Id});</li>
 *   <li>an NPC decision condition ({@link ZigRequiresNpcCondition});</li>
 *   <li>a choice page's {@code Requirements}, a shop's among them ({@link ZigRequiresChoiceRequirement});</li>
 *   <li>a world event stage's {@code Conditions} ({@link ZigRequiresEventCondition}), when the WorldEvents plugin
 *       is running;</li>
 *   <li>a native objective task's {@code TaskConditions} ({@link ZigRequiresTaskCondition}) and an objective
 *       location marker's {@code TriggerConditions} ({@link ZigRequiresMarkerCondition}), once the Objectives
 *       plugin has set up ({@link #registerObjectiveConditions}).</li>
 * </ul>
 *
 * <p>Each goes through the plugin's own codec registry, so the engine drops it again when the library shuts
 * down. None is guarded: a failed registration throws here, at setup, rather than surfacing later as an
 * unknown Type inside an owner's pack (R700). Registration only ({@code RootRegistrationOnlyTest}).
 */
public final class ZigRequiresBootstrap {

    private ZigRequiresBootstrap() {
    }

    /** Register every {@code ZigRequires} condition Type. From plugin {@code setup()}, before any asset decode. */
    public static void registerConditions(@Nonnull PluginBase plugin) {
        plugin.getCodecRegistry(TriggerCondition.CODEC).register(ZigRequiresTriggerCondition.TYPE_NAME,
                ZigRequiresTriggerCondition.class, ZigRequiresTriggerCondition.codec());
        plugin.getCodecRegistry(Condition.CODEC).register(ZigRequiresEntityCondition.TYPE_NAME,
                ZigRequiresEntityCondition.class, ZigRequiresEntityCondition.codec());
        // Two engine classes are named Condition: the entity one is imported, the NPC decision one named in full.
        plugin.getCodecRegistry(com.hypixel.hytale.server.npc.decisionmaker.core.conditions.base.Condition.CODEC)
                .register(ZigRequiresNpcCondition.TYPE_NAME, ZigRequiresNpcCondition.class,
                        ZigRequiresNpcCondition.codec());
        plugin.getCodecRegistry(ChoiceRequirement.CODEC).register(ZigRequiresChoiceRequirement.TYPE_NAME,
                ZigRequiresChoiceRequirement.class, ZigRequiresChoiceRequirement.codec());
        registerEventCondition(plugin);
    }

    /**
     * The two native objective maps: a task's {@code TaskConditions} and a location marker's
     * {@code TriggerConditions}. Called by the native objective bootstrap only once the Objectives plugin has set
     * up, never from {@link #registerConditions}: the trigger condition map's own class set-up builds the engine's
     * {@code Weather} and {@code HourRange} conditions, whose own set-up asks the Weather plugin and the Time module
     * for their resource types and throws where either is not loaded, so the map is touched only once the plugin
     * that owns it already has. A server without the plugin loads no objective, so it has nothing to gate.
     * Unguarded, like every other registration here.
     */
    public static void registerObjectiveConditions(@Nonnull PluginBase plugin) {
        plugin.getCodecRegistry(TaskConditionAsset.CODEC).register(ZigRequiresTaskCondition.TYPE_NAME,
                ZigRequiresTaskCondition.class, ZigRequiresTaskCondition.codec());
        plugin.getCodecRegistry(ObjectiveLocationTriggerCondition.CODEC).register(ZigRequiresMarkerCondition.TYPE_NAME,
                ZigRequiresMarkerCondition.class, ZigRequiresMarkerCondition.codec());
    }

    /**
     * The world event condition, through the WorldEvents plugin's own helper, which registers both halves (the
     * stage's config and the live condition) into their codec maps through the plugin's codec registry and files
     * the tick cadence the plugin's condition manager reads. The library's manifest names WorldEvents among its
     * {@code OptionalDependencies}, so the engine sets that plugin up first whenever it runs. A server without it
     * has no world events to gate, so this one is skipped with a line; either way the boot log says which.
     */
    private static void registerEventCondition(@Nonnull PluginBase plugin) {
        PluginManager plugins = PluginManager.get();
        if (plugins == null || plugins.getPlugin(WorldEventsPlugin.class) == null) {
            SafeLog.info("[gate] the WorldEvents plugin is not running, so no world event stage can ask "
                    + ZigRequiresEventCondition.TYPE_NAME);
            return;
        }
        WorldEventsPlugin.registerCondition(plugin, ZigRequiresEventCondition.TYPE, ZigRequiresEventCondition.codec(),
                ZigRequiresEventCondition.Config.codec());
        SafeLog.info("[gate] registered " + ZigRequiresEventCondition.TYPE_NAME
                + " as a world event stage condition on the WorldEvents plugin");
    }
}
