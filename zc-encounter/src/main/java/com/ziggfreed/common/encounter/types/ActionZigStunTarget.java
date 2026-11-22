package com.ziggfreed.common.encounter.types;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.ziggfreed.common.effect.control.StunControl;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.util.SafeLog;

/**
 * Stuns the fight's boss the moment the script asks: a breakdown, a stagger after its guard falls, any window the
 * fight opens on it (R683). The stun is the library's own ({@link StunControl}): its chains cancelled, its combat
 * action ended and its attacks paused for the stun, held in place by the stun effect, under {@code Effect} when the
 * script names one. A boss whose rules shrug a stun at that moment (a phase's {@code ZigControlRule} naming Stun under
 * {@code ZigControlShrugs}) takes none, this fight's own included: open a window that lets stuns land instead.
 *
 * <p>Like every action this library registers, it ALWAYS answers finished: an action with nothing to stun says so in
 * the log and lets the list move on.
 */
public class ActionZigStunTarget extends ActionBase {

    /** The stun's length when the script writes none, in seconds. */
    public static final double DEFAULT_DURATION = 5.0;

    @Nullable private final String targetSlot;
    private final float seconds;
    @Nullable private final String effect;

    public ActionZigStunTarget(@Nonnull BuilderActionZigStunTarget builder, @Nonnull BuilderSupport support) {
        super(builder);
        String slot = builder.getTargetSlot(support);
        this.targetSlot = slot == null || slot.isBlank() ? null : slot.trim();
        this.seconds = (float) Math.max(0.0, builder.getDuration(support));
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
            if (target == null) {
                EncounterTypes.executed(EncounterTypes.STUN_TARGET, "encounter=" + encounterId + ": nothing to stun");
                return true;
            }
            boolean landed = effect == null ? StunControl.stun(store, target, seconds, ref)
                    : StunControl.stun(store, target, seconds, ref, effect);
            EncounterTypes.executed(EncounterTypes.STUN_TARGET, "encounter=" + encounterId + " seconds=" + seconds
                    + (effect == null ? "" : " effect=" + effect) + (landed ? "" : " (did not land)"));
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " " + EncounterTypes.STUN_TARGET + " failed", t);
        }
        return true;
    }
}
