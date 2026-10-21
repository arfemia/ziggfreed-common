package com.ziggfreed.common.proc;

import java.util.ArrayList;
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
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.condition.Nearby;

/**
 * The GuardBroke moment: a foe's guard broken under the holder. The engine breaks a guard a tick or two after the
 * blocked hit that drained it (its stamina step pays the block, the stamina stat's own floor effects then put the
 * combat config's guard-break effect on, and the guard comes down on its next wielding tick), and says nothing when
 * it does, so the one blocked hit of the holder's whose drain takes a guarding entity's stamina below nothing (the
 * engine's own reckoning, read just before it pays, so only the player whose hit broke the guard is credited) is
 * noted ({@link #struck}) and the holder pass looks, each tick for {@value #WINDOW_NANOS} ns, whether that entity now
 * wears the guard-break effect ({@code CombatConfig.getStaminaBrokenEffectIndex}). A guard a proc of the holder's broke ({@code BreakGuard}) is
 * noted as broken outright ({@link #broke}) and answered on their next pass, outside that proc's own actions. One note
 * per foe and holder; each answered once. World thread; the maps are concurrent, since a foe leaving another world is
 * forgotten from that world's thread.
 */
final class GuardBreaks {

    /** How long after a blocked hit the holder's pass still looks for that guard to break. */
    static final long WINDOW_NANOS = 1_500_000_000L;

    /** One foe's guard the holder struck or broke, and when. */
    private static final class Note {
        volatile long atNanos;
        volatile boolean broken;

        Note(long atNanos, boolean broken) {
            this.atNanos = atNanos;
            this.broken = broken;
        }
    }

    private static final Map<Ref<EntityStore>, Map<Ref<EntityStore>, Note>> NOTES = new ConcurrentHashMap<>();

    private GuardBreaks() {
    }

    /** The holder's blocked hit drains {@code foe}'s guard below nothing: watch it break a while. */
    static void struck(@Nonnull Ref<EntityStore> holder, @Nonnull Ref<EntityStore> foe, long nanos) {
        note(holder, foe, nanos, false);
    }

    /** A proc of the holder's broke {@code foe}'s guard outright: answer it on their next pass. */
    static void broke(@Nonnull Ref<EntityStore> holder, @Nonnull Ref<EntityStore> foe, long nanos) {
        if (Procs.listens(ProcTrigger.GUARD_BROKE)) {
            note(holder, foe, nanos, true);
        }
    }

    private static void note(@Nonnull Ref<EntityStore> holder, @Nonnull Ref<EntityStore> foe, long nanos,
            boolean broken) {
        if (!foe.isValid() || Nearby.same(holder, foe)) {
            return;
        }
        Map<Ref<EntityStore>, Note> notes = NOTES.computeIfAbsent(holder, h -> new ConcurrentHashMap<>());
        Note note = notes.get(foe);
        if (note == null) {
            notes.put(foe, new Note(nanos, broken));
        } else {
            note.atNanos = nanos;
            note.broken |= broken;
        }
    }

    /** Every tick, for the holder: answer each noted guard that broke, drop the ones past their window. */
    static void check(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> holder, long nanos) {
        Map<Ref<EntityStore>, Note> notes = NOTES.get(holder);
        if (notes == null || notes.isEmpty()) {
            return;
        }
        int brokenIndex = brokenEffectIndex(store);
        List<Ref<EntityStore>> fired = null;
        Iterator<Map.Entry<Ref<EntityStore>, Note>> it = notes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Ref<EntityStore>, Note> entry = it.next();
            Ref<EntityStore> foe = entry.getKey();
            Note note = entry.getValue();
            if (!foe.isValid() || nanos - note.atNanos > WINDOW_NANOS) {
                it.remove();
                continue;
            }
            if (note.broken || wears(commandBuffer, foe, brokenIndex)) {
                it.remove();
                if (fired == null) {
                    fired = new ArrayList<>(1);
                }
                fired.add(foe);
            }
        }
        if (fired == null || !Procs.listens(ProcTrigger.GUARD_BROKE)) {
            return;
        }
        for (Ref<EntityStore> foe : fired) {
            Procs.fire(new ProcMoment(ProcTrigger.GUARD_BROKE, store, commandBuffer, holder, foe, null, 1.0, null));
        }
    }

    /** Does {@code ref} wear the effect at {@code index}? False for none or when it cannot tell. */
    private static boolean wears(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            int index) {
        if (index < 0) {
            return false;
        }
        try {
            EffectControllerComponent controller =
                    accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            return controller != null && controller.getActiveEffects().containsKey(index);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The world's guard-break effect (its combat config's), or -1 when it cannot be read. */
    private static int brokenEffectIndex(@Nonnull Store<EntityStore> store) {
        try {
            World world = store.getExternalData().getWorld();
            return world == null ? -1 : world.getGameplayConfig().getCombatConfig().getStaminaBrokenEffectIndex();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Forget {@code ref}, as a holder and as a foe (it left its store). */
    static void forget(@Nullable Ref<EntityStore> ref) {
        if (ref == null) {
            return;
        }
        NOTES.remove(ref);
        for (Map<Ref<EntityStore>, Note> notes : NOTES.values()) {
            notes.remove(ref);
        }
    }

    /** Forget everything (a test). */
    static void clear() {
        NOTES.clear();
    }
}
