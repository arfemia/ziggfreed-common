package com.ziggfreed.common.proc;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.landing.EffectLandings;
import com.ziggfreed.common.stats.condition.EffectFamilies;
import com.ziggfreed.common.stats.condition.Nearby;

/**
 * The EffectLanded and EffectEnded moments, as a reader of zc-effects' one landing watch ({@link EffectLandings}): the
 * engine says nothing when an effect goes on or comes off, and the watch looks at every living carrier (a player every
 * tick, a creature a few times a second, and every tick one a hit or a control landed on lately). It tracks only the
 * effects a live effect proc names ({@link ProcConfig#watched}); a new instance between two looks is a landing (a
 * re-apply that keeps its instance is not), and one gone an ending. A landing on a player answers that player's own
 * effect procs, about whoever put it on; a landing a player put on another answers that player's procs on foes, about
 * the one it landed on. A watched effect shorter than a look's gap can come and go unseen. World thread.
 */
final class EffectWatch {

    /** The effect moments as a reader of the landing watch, registered by {@link ProcBootstrap}. */
    static final EffectLandings.Watcher WATCHER = new EffectLandings.Watcher() {
        @Override
        public boolean listening() {
            return Procs.listens(ProcTrigger.EFFECT_LANDED) || Procs.listens(ProcTrigger.EFFECT_ENDED);
        }

        @Override
        public boolean tracks(int effectIndex) {
            return named(effectIndex, ProcConfig.getInstance().watched(false))
                    || named(effectIndex, ProcConfig.getInstance().watched(true));
        }

        @Override
        public void landed(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer,
                @Nonnull Ref<EntityStore> carrier, int effectIndex, @Nullable Ref<EntityStore> owner) {
            answer(store, buffer, ProcTrigger.EFFECT_LANDED, carrier, effectIndex, owner);
        }

        @Override
        public void ended(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer,
                @Nonnull Ref<EntityStore> carrier, int effectIndex, @Nullable Ref<EntityStore> owner) {
            answer(store, buffer, ProcTrigger.EFFECT_ENDED, carrier, effectIndex, owner);
        }
    };

    private EffectWatch() {
    }

    /**
     * One landing or ending: the carrier's own procs (a player's, about the owner) and the owner's procs on foes (a
     * player who put it on another, about the carrier).
     */
    private static void answer(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer,
            @Nonnull ProcTrigger trigger, @Nonnull Ref<EntityStore> carrier, int effectIndex,
            @Nullable Ref<EntityStore> owner) {
        if (!Procs.listens(trigger)) {
            return;
        }
        if (isPlayer(store, carrier) && named(effectIndex, ProcConfig.getInstance().watched(false))) {
            fire(store, buffer, carrier, trigger, owner, effectIndex, false);
        }
        if (owner != null && owner.isValid() && owner.getStore() == store && !Nearby.same(owner, carrier)
                && isPlayer(store, owner) && named(effectIndex, ProcConfig.getInstance().watched(true))) {
            fire(store, buffer, owner, trigger, carrier, effectIndex, true);
        }
    }

    private static void fire(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer,
            @Nonnull Ref<EntityStore> holder, @Nonnull ProcTrigger trigger, @Nullable Ref<EntityStore> target,
            int index, boolean onFoe) {
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(index);
        String id = effect == null ? null : effect.getId();
        if (id == null) {
            return;
        }
        Ref<EntityStore> about = target != null && target.isValid() && !Nearby.same(target, holder) ? target : null;
        Procs.fire(new ProcMoment(trigger, store, buffer, holder, about, null, 1.0, null, null, null,
                new ProcMoment.EffectSeen(id, onFoe)));
    }

    /** Is {@code ref} a player, whose procs a moment may answer? */
    private static boolean isPlayer(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            return ref.isValid() && store.getComponent(ref, PlayerRef.getComponentType()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Does any watched group name the effect at {@code index}, by id or family? */
    static boolean named(int index, @Nonnull List<ProcAsset.Effect> watched) {
        if (watched.isEmpty()) {
            return false;
        }
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(index);
        String id = effect == null ? null : effect.getId();
        for (ProcAsset.Effect group : watched) {
            if (matches(group, id, index)) {
                return true;
            }
        }
        return false;
    }

    /** Does {@code group} name the effect {@code id} at {@code index}, by id or family? */
    static boolean matches(@Nonnull ProcAsset.Effect group, @Nullable String id, int index) {
        if (id != null) {
            for (String wanted : group.effects()) {
                if (wanted.equalsIgnoreCase(id)) {
                    return true;
                }
            }
        }
        for (String family : group.families()) {
            if (EffectFamilies.contains(family, index)) {
                return true;
            }
        }
        return false;
    }
}
