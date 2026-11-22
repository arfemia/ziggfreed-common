package com.ziggfreed.common.encounter.types;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.ziggfreed.common.effect.EffectAdds;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.stats.condition.BossRules;
import com.ziggfreed.common.util.SafeLog;

/**
 * Sets the fight's boss's standing crowd-control rule (R1122): every infinite rule effect it wears comes off (an effect
 * whose own Tags name {@code ZigControlLands} or {@code ZigControlShrugs}, zc-entity's {@code BossRules.ruleOf}) and
 * {@code Effect} goes on as its asset authors it, so a phase names which controls land on the boss until the next
 * rule. An empty {@code Effect} only takes the rules off, back to the boss's own default (its NPC groups' tags, then
 * the family's). A timed rule effect (a window the fight opened) is left to run out. {@code Zc_Phase_At_Health}'s
 * {@code SetControl} and {@code Control} run it at a phase change, and {@code Zc_Encounter_Base} runs it empty as the
 * fight engages, so a leashed boss never carries a phase into the next run.
 *
 * <p>Like every action this library registers, it ALWAYS answers finished: one with nothing to rule says so in the
 * log and lets the list move on.
 */
public class ActionZigControlRule extends ActionBase {

    /** The effect ids already named at WARN as not loaded or as carrying no rule, so a looping fight says it once. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    @Nullable private final String targetSlot;
    @Nullable private final String effect;

    public ActionZigControlRule(@Nonnull BuilderActionZigControlRule builder, @Nonnull BuilderSupport support) {
        super(builder);
        String slot = builder.getTargetSlot(support);
        this.targetSlot = slot == null || slot.isBlank() ? null : slot.trim();
        String id = builder.getEffect(support);
        this.effect = id == null || id.isBlank() ? null : id.trim();
    }

    @Override
    public boolean execute(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport executionSupport,
            @Nullable InfoProvider sensorInfo, double dt, @Nonnull Store<EntityStore> store) {
        super.execute(ref, executionSupport, sensorInfo, dt, store);
        try {
            String encounterId = EncounterRuns.encounterIdOn(store, ref);
            EncounterBindingAsset row = encounterId == null ? null
                    : EncounterBindingConfig.getInstance().forEncounter(encounterId);
            Ref<EntityStore> target = ActionTargets.resolve(store, ref, targetSlot, executionSupport, sensorInfo, row);
            EffectControllerComponent controller = target == null ? null
                    : store.getComponent(target, EffectControllerComponent.getComponentType());
            if (target == null || controller == null) {
                EncounterTypes.executed(EncounterTypes.CONTROL_RULE, "encounter=" + encounterId + ": nothing to rule");
                return true;
            }
            int index = effect == null ? Integer.MIN_VALUE : EntityEffect.getAssetMap().getIndex(effect);
            EntityEffect asset = index == Integer.MIN_VALUE ? null : EntityEffect.getAssetMap().getAsset(index);
            if (effect != null && asset == null) {
                warnOnce(effect, "is not loaded, so the boss keeps its own default");
            } else if (asset != null && BossRules.ruleOf(index) == null) {
                warnOnce(effect, "names no ZigControlLands or ZigControlShrugs tag, so it changes no rule");
            }
            int cleared = clearStanding(store, target, controller, asset == null ? Integer.MIN_VALUE : index);
            boolean on = asset != null && EffectAdds.asAuthored(controller, target, index, asset, store);
            EncounterTypes.executed(EncounterTypes.CONTROL_RULE, "encounter=" + encounterId + " cleared=" + cleared
                    + (effect == null ? "" : " effect=" + effect + (on ? "" : " (did not go on)")));
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " " + EncounterTypes.CONTROL_RULE + " failed", t);
        }
        return true;
    }

    /**
     * Take every infinite rule effect off {@code target} but the one at {@code keep} (the rule about to go on, so a
     * repeat sends its viewers no fresh add), and say how many came off.
     */
    private static int clearStanding(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> target,
            @Nonnull EffectControllerComponent controller, int keep) {
        int[] worn = controller.getActiveEffectIndexes();
        if (worn == null) {
            return 0;
        }
        int cleared = 0;
        for (int index : worn) {
            if (index == keep || BossRules.ruleOf(index) == null) {
                continue;
            }
            ActiveEntityEffect active = controller.getActiveEffects().get(index);
            if (active != null && active.isInfinite()) {
                // Off whole, whatever the effect's own RemovalBehavior says: an Infinite one would only lose its
                // infinity and stay a second as a timed rule, which reads as a window over the next phase's rule.
                controller.removeEffect(target, index, RemovalBehavior.COMPLETE, accessor);
                cleared++;
            }
        }
        return cleared;
    }

    private static void warnOnce(@Nonnull String id, @Nonnull String why) {
        if (WARNED.add(id.toLowerCase(Locale.ROOT) + "|" + why)) {
            SafeLog.warn(Encounters.LOG_PREFIX + " " + EncounterTypes.CONTROL_RULE + " effect '" + id + "' " + why);
        }
    }
}
