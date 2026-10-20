package com.ziggfreed.common.proc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;
import com.ziggfreed.common.track.source.ExactAmountPayload;
import com.ziggfreed.common.track.source.TrackSources;
import com.ziggfreed.common.track.source.VictimPayload;

/**
 * The triggers that ride the library's own moments, a library-default {@link MomentListener}: a player's hit on a
 * creature ({@code DEAL_DAMAGE}: Hit, and SprintHit while they sprint), a kill ({@code KILL_ENTITY}), a block broken
 * ({@code BREAK_BLOCK}) and a harvest ({@code PICKUP_ITEM}). A hit counts for the player who struck it, never for an
 * owner a turret's hit was credited to. World thread, inside the producer's own dispatch.
 */
public final class ProcMoments implements MomentListener {

    /** The one instance, registered at library-default rank. */
    public static final ProcMoments INSTANCE = new ProcMoments();

    /** The library's kill, break and harvest kinds, as their producers fire them. */
    static final String KILL_KIND = "KILL_ENTITY";
    static final String BREAK_KIND = "BREAK_BLOCK";
    static final String HARVEST_KIND = "PICKUP_ITEM";

    private ProcMoments() {
    }

    @Override
    public void react(@Nonnull Moment moment) {
        String kind = moment.kindId();
        if (TrackSources.DEAL_DAMAGE.equalsIgnoreCase(kind)) {
            onHit(moment);
        } else if (KILL_KIND.equalsIgnoreCase(kind)) {
            if (Procs.listens(ProcTrigger.KILL)) {
                VictimPayload victim = moment.payload(VictimPayload.class);
                fire(moment, ProcTrigger.KILL, victim == null ? null : victim.victimRef(), null, 1.0, null);
            }
        } else if (BREAK_KIND.equalsIgnoreCase(kind)) {
            if (Procs.listens(ProcTrigger.BLOCK_BREAK)) {
                fire(moment, ProcTrigger.BLOCK_BREAK, null, null, Math.max(1L, moment.amount()), moment.target());
            }
        } else if (HARVEST_KIND.equalsIgnoreCase(kind) && Procs.listens(ProcTrigger.HARVEST)) {
            fire(moment, ProcTrigger.HARVEST, null, null, Math.max(1L, moment.amount()), null);
        }
    }

    private static void onHit(@Nonnull Moment moment) {
        boolean hit = Procs.listens(ProcTrigger.HIT);
        boolean sprint = Procs.listens(ProcTrigger.SPRINT_HIT);
        if (!hit && !sprint) {
            return;
        }
        HitPayload payload = moment.payload(HitPayload.class);
        if (payload == null) {
            return;
        }
        Damage damage = payload.damage();
        Ref<EntityStore> striker = damage.getSource() instanceof Damage.EntitySource source ? source.getRef() : null;
        if (striker == null || striker != moment.ref()) {
            return;
        }
        ExactAmountPayload exact = moment.payload(ExactAmountPayload.class);
        double amount = exact == null ? moment.amount() : exact.exactAmount();
        Ref<EntityStore> victim = payload.victimRef();
        if (hit) {
            fire(moment, ProcTrigger.HIT, victim, damage, amount, null);
        }
        if (sprint && sprinting(moment)) {
            fire(moment, ProcTrigger.SPRINT_HIT, victim, damage, amount, null);
        }
    }

    private static boolean sprinting(@Nonnull Moment moment) {
        try {
            MovementStatesComponent states = moment.store().getComponent(moment.ref(),
                    MovementStatesComponent.getComponentType());
            MovementStates now = states == null ? null : states.getMovementStates();
            return now != null && now.sprinting;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void fire(@Nonnull Moment moment, @Nonnull ProcTrigger trigger,
            @Nullable Ref<EntityStore> target, @Nullable Damage damage,
            double amount, @Nullable String blockId) {
        Procs.fire(new ProcMoment(trigger, moment.store(), moment.commandBuffer(), moment.ref(), target, damage, amount,
                blockId));
    }
}
