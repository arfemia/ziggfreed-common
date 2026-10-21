package com.ziggfreed.common.proc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.condition.EffectFamilies;
import com.ziggfreed.common.stats.condition.Nearby;

/**
 * The EffectLanded and EffectEnded moments. The engine says nothing when an effect goes on or comes off, so the
 * holder pass looks: at the holder's own effects every tick, and a few times a second at the effects the holder put
 * on the foes they struck in the last {@value #FOE_KEEP_NANOS} ns (a hit, or a proc of theirs putting an effect on
 * one), the newest {@value #FOE_CAP} of them. Only the effects a live effect proc names are watched
 * ({@link ProcConfig#watched}); between two looks a new instance is a landing (a re-apply that keeps its instance is
 * not), and one gone an ending. A watched effect shorter than a look's gap can come and go unseen. World thread; a
 * holder's own state is its world's thread's alone, and the foe maps are concurrent, since a foe leaving another world
 * is forgotten from that world's thread.
 */
final class EffectWatch {

    /** How long after the holder last struck a foe its effects there are still watched. */
    static final long FOE_KEEP_NANOS = 30_000_000_000L;

    /** The most foes one holder's watch follows. */
    static final int FOE_CAP = 16;

    /** One watched effect as a look saw it: its instance and who put it on. */
    private record Seen(@Nonnull Object instance, @Nullable Ref<EntityStore> owner) {
    }

    /** One struck foe: when it was last struck, and its watched effects at the last look. */
    private static final class Foe {
        volatile long lastNanos;
        final Map<Integer, Seen> seen = new HashMap<>();
    }

    /** One holder's watch. */
    private static final class HolderState {
        Map<Integer, Seen> own = new HashMap<>();
        final Map<Ref<EntityStore>, Foe> foes = new ConcurrentHashMap<>();
    }

    private static final Map<Ref<EntityStore>, HolderState> STATES = new ConcurrentHashMap<>();

    private EffectWatch() {
    }

    /** The holder struck (or a proc of theirs put an effect on) {@code foe}: follow its effects a while. */
    static void noteFoe(@Nonnull Ref<EntityStore> holder, @Nullable Ref<EntityStore> foe, long nanos) {
        if (foe == null || !foe.isValid() || Nearby.same(foe, holder)
                || ProcConfig.getInstance().watched(true).isEmpty()) {
            return;
        }
        HolderState state = STATES.computeIfAbsent(holder, h -> new HolderState());
        Foe followed = state.foes.computeIfAbsent(foe, f -> new Foe());
        followed.lastNanos = nanos;
        if (state.foes.size() > FOE_CAP) {
            dropOldest(state.foes);
        }
    }

    /** Every tick: the holder's own watched effects. */
    static void checkOwn(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> holder) {
        List<ProcAsset.Effect> watched = ProcConfig.getInstance().watched(false);
        HolderState state = STATES.get(holder);
        if (watched.isEmpty()) {
            if (state != null) {
                state.own = new HashMap<>();
            }
            return;
        }
        if (state == null) {
            state = STATES.computeIfAbsent(holder, h -> new HolderState());
        }
        Map<Integer, Seen> now = look(commandBuffer, holder, watched, null);
        Map<Integer, Seen> before = state.own;
        state.own = now;
        fireChanges(store, commandBuffer, holder, null, before, now);
    }

    /** A few times a second: the holder's watched effects on the foes they struck. */
    static void checkFoes(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> holder, long nanos) {
        HolderState state = STATES.get(holder);
        if (state == null || state.foes.isEmpty()) {
            return;
        }
        List<ProcAsset.Effect> watched = ProcConfig.getInstance().watched(true);
        if (watched.isEmpty()) {
            state.foes.clear();
            return;
        }
        Iterator<Map.Entry<Ref<EntityStore>, Foe>> it = state.foes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Ref<EntityStore>, Foe> entry = it.next();
            Ref<EntityStore> foe = entry.getKey();
            Foe followed = entry.getValue();
            if (!foe.isValid()) {
                it.remove();
                continue;
            }
            Map<Integer, Seen> now = look(commandBuffer, foe, watched, holder);
            Map<Integer, Seen> before = new HashMap<>(followed.seen);
            followed.seen.clear();
            followed.seen.putAll(now);
            fireChanges(store, commandBuffer, holder, foe, before, now);
            if (now.isEmpty() && nanos - followed.lastNanos > FOE_KEEP_NANOS) {
                it.remove();
            }
        }
    }

    /**
     * The watched effects on {@code carrier}: every active effect a watched group names, put on by {@code owner}
     * when one is given (the holder's own effects on a foe).
     */
    @Nonnull
    private static Map<Integer, Seen> look(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> carrier, @Nonnull List<ProcAsset.Effect> watched,
            @Nullable Ref<EntityStore> owner) {
        try {
            EffectControllerComponent controller =
                    accessor.getComponent(carrier, EffectControllerComponent.getComponentType());
            if (controller == null || controller.getActiveEffects().isEmpty()) {
                return new HashMap<>();
            }
            Map<Integer, Seen> out = new HashMap<>();
            for (ActiveEntityEffect active : controller.getActiveEffects().values()) {
                int index = active.getEntityEffectIndex();
                if (owner != null && !Nearby.same(owner, active.getOwner())) {
                    continue;
                }
                if (named(index, watched)) {
                    out.put(index, new Seen(active, active.getOwner()));
                }
            }
            return out;
        } catch (Throwable t) {
            return new HashMap<>();
        }
    }

    /** Fire a landing for each new instance and an ending for each one gone. */
    private static void fireChanges(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> holder, @Nullable Ref<EntityStore> foe, @Nonnull Map<Integer, Seen> before,
            @Nonnull Map<Integer, Seen> now) {
        if (before.isEmpty() && now.isEmpty()) {
            return;
        }
        List<Integer> landed = new ArrayList<>();
        List<Integer> ended = new ArrayList<>();
        for (Map.Entry<Integer, Seen> entry : now.entrySet()) {
            Seen was = before.get(entry.getKey());
            if (was == null || was.instance() != entry.getValue().instance()) {
                landed.add(entry.getKey());
                if (was != null) {
                    ended.add(entry.getKey());
                }
            }
        }
        for (Integer index : before.keySet()) {
            if (!now.containsKey(index)) {
                ended.add(index);
            }
        }
        boolean onFoe = foe != null;
        for (Integer index : ended) {
            Seen was = before.get(index);
            fire(store, commandBuffer, holder, ProcTrigger.EFFECT_ENDED, onFoe ? foe : was == null ? null : was.owner(),
                    index, onFoe);
        }
        for (Integer index : landed) {
            Seen is = now.get(index);
            fire(store, commandBuffer, holder, ProcTrigger.EFFECT_LANDED, onFoe ? foe : is == null ? null : is.owner(),
                    index, onFoe);
        }
    }

    private static void fire(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> holder, @Nonnull ProcTrigger trigger, @Nullable Ref<EntityStore> target,
            int index, boolean onFoe) {
        if (!Procs.listens(trigger)) {
            return;
        }
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(index);
        String id = effect == null ? null : effect.getId();
        if (id == null) {
            return;
        }
        Ref<EntityStore> about = target != null && target.isValid() && !Nearby.same(target, holder) ? target : null;
        Procs.fire(new ProcMoment(trigger, store, commandBuffer, holder, about, null, 1.0, null, null, null,
                new ProcMoment.EffectSeen(id, onFoe)));
    }

    /** Does any watched group name the effect at {@code index}, by id or family? */
    static boolean named(int index, @Nonnull List<ProcAsset.Effect> watched) {
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

    private static void dropOldest(@Nonnull Map<Ref<EntityStore>, Foe> foes) {
        Ref<EntityStore> oldest = null;
        long at = Long.MAX_VALUE;
        for (Map.Entry<Ref<EntityStore>, Foe> entry : foes.entrySet()) {
            if (entry.getValue().lastNanos < at) {
                at = entry.getValue().lastNanos;
                oldest = entry.getKey();
            }
        }
        if (oldest != null) {
            foes.remove(oldest);
        }
    }

    /** Forget a holder that left its store, and the same entity as a followed foe. */
    static void forget(@Nonnull Ref<EntityStore> ref) {
        STATES.remove(ref);
        for (HolderState state : STATES.values()) {
            state.foes.remove(ref);
        }
    }

    /** Forget everything (a test). */
    static void clear() {
        STATES.clear();
    }
}
