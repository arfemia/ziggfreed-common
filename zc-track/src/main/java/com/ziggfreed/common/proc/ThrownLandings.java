package com.ziggfreed.common.proc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.condition.ConditionalStats;
import com.ziggfreed.common.stats.condition.Nearby;

/**
 * The ThrownLanding moment's producer: a creature the holder struck or pushed (a hit of theirs, a proc's {@code Push},
 * a consumer's own throw through {@link Procs#thrown}) is watched for a few seconds; once it has left the ground and
 * comes down again (on the ground, or into water), the holder's ThrownLanding fires about it, its amount the blocks it
 * dropped from the highest it flew. One that never leaves the ground soon after, or flies longer than the watch, is
 * let go. Noted only while a proc answers the moment, so it costs nothing otherwise.
 *
 * <p>Where it landed is read as it lands ({@link #inWater}, {@link #onCreature}), for a proc's {@code Thrown} group.
 * A creature's ground and water flags are the engine's own movement states, written by its role a tick behind.
 *
 * <p>World thread: a holder's watch is touched by its own world's thread, and a target leaving any world is forgotten
 * from every holder's watch ({@link #forget}), so both levels are concurrent maps.
 */
final class ThrownLandings {

    /** How long after a launch its landing still counts. */
    static final long WATCH_NANOS = 4_000_000_000L;

    /** How long a struck creature may stay on the ground before it is let go as never launched. */
    static final long LIFT_NANOS = 600_000_000L;

    /** The most creatures one holder's watch keeps; a new one past it is not noted. */
    static final int MAX_WATCHED = 16;

    /** How close, in blocks, another creature must stand to where one landed for it to have come down on it. */
    static final double ON_CREATURE_REACH = 1.5;

    /** One creature in the air, or about to be. */
    static final class Flight {
        final long sinceNanos;
        boolean airborne;
        double peakY = Double.NEGATIVE_INFINITY;

        Flight(long sinceNanos) {
            this.sinceNanos = sinceNanos;
        }
    }

    private static final Map<Ref<EntityStore>, Map<Ref<EntityStore>, Flight>> WATCHED = new ConcurrentHashMap<>();

    private ThrownLandings() {
    }

    /**
     * The holder struck or pushed {@code target}: watch it for its landing, afresh. Only while a proc answers
     * ThrownLanding; never the holder themselves.
     */
    static void launched(@Nonnull Ref<EntityStore> holder, @Nullable Ref<EntityStore> target, long nanos) {
        if (target == null || !Procs.listens(ProcTrigger.THROWN_LANDING) || Nearby.same(holder, target)) {
            return;
        }
        Map<Ref<EntityStore>, Flight> watch = WATCHED.computeIfAbsent(holder, h -> new ConcurrentHashMap<>());
        if (watch.size() >= MAX_WATCHED && !watch.containsKey(target)) {
            return;
        }
        Flight was = watch.get(target);
        if (was == null || !was.airborne) {
            watch.put(target, new Flight(nanos));
        }
    }

    /** Every tick: look at each creature the holder launched, and answer each that came down. Never throws. */
    static void check(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> holder, long nanos) {
        Map<Ref<EntityStore>, Flight> watch = WATCHED.get(holder);
        if (watch == null || watch.isEmpty()) {
            return;
        }
        List<Landed> landed = null;
        Iterator<Map.Entry<Ref<EntityStore>, Flight>> it = watch.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Ref<EntityStore>, Flight> entry = it.next();
            Ref<EntityStore> target = entry.getKey();
            Flight flight = entry.getValue();
            try {
                if (!target.isValid() || target.getStore() != store || nanos - flight.sinceNanos > WATCH_NANOS) {
                    it.remove();
                    continue;
                }
                MovementStatesComponent states =
                        commandBuffer.getComponent(target, MovementStatesComponent.getComponentType());
                MovementStates now = states == null ? null : states.getMovementStates();
                Vector3d at = Nearby.positionOf(commandBuffer, target);
                if (now == null || at == null) {
                    it.remove();
                    continue;
                }
                Verdict verdict = step(flight, down(now), at.y, nanos);
                if (verdict == Verdict.LANDED) {
                    if (landed == null) {
                        landed = new ArrayList<>(1);
                    }
                    landed.add(new Landed(target, Math.max(0.0, flight.peakY - at.y)));
                }
                if (verdict != Verdict.WAIT) {
                    it.remove();
                }
            } catch (Throwable t) {
                it.remove();
            }
        }
        if (landed != null && Procs.listens(ProcTrigger.THROWN_LANDING)) {
            for (Landed one : landed) {
                Procs.fire(new ProcMoment(ProcTrigger.THROWN_LANDING, store, commandBuffer, holder, one.target(),
                        null, one.dropped(), null));
            }
        }
    }

    /** One creature that came down, and how far it dropped. */
    private record Landed(@Nonnull Ref<EntityStore> target, double dropped) {
    }

    /** What one look decides about a creature in the watch. */
    enum Verdict {
        WAIT, LANDED, LET_GO
    }

    /**
     * One look at a watched creature: {@code down} (on the ground or in water) at height {@code y}. A creature that
     * left the ground and is down again landed; one still down {@value #LIFT_NANOS} ns after it was struck never
     * flew. Pure over the flight it updates.
     */
    @Nonnull
    static Verdict step(@Nonnull Flight flight, boolean down, double y, long nanos) {
        if (!flight.airborne) {
            if (!down) {
                flight.airborne = true;
                flight.peakY = y;
                return Verdict.WAIT;
            }
            return nanos - flight.sinceNanos > LIFT_NANOS ? Verdict.LET_GO : Verdict.WAIT;
        }
        if (y > flight.peakY) {
            flight.peakY = y;
        }
        return down ? Verdict.LANDED : Verdict.WAIT;
    }

    /** Is a creature in these movement states down: on the ground, or in water? */
    private static boolean down(@Nonnull MovementStates states) {
        return states.onGround || states.inFluid || states.swimming;
    }

    /** Is {@code ref} in water now? False when it cannot tell. */
    static boolean inWater(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        try {
            MovementStatesComponent states = accessor.getComponent(ref, MovementStatesComponent.getComponentType());
            MovementStates now = states == null ? null : states.getMovementStates();
            return now != null && (now.inFluid || now.swimming);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Did {@code landed} come down on another creature: one of the holder's foes (never the holder, never itself)
     * standing within {@value #ON_CREATURE_REACH} blocks of where it is? False when it cannot tell.
     */
    static boolean onCreature(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull Ref<EntityStore> landed) {
        try {
            Vector3d at = Nearby.positionOf(accessor, landed);
            if (at == null) {
                return false;
            }
            Instant now = ConditionalStats.now(accessor);
            for (Ref<EntityStore> other : Nearby.foesAround(accessor, holder, at, ON_CREATURE_REACH, null, now, 3)) {
                if (!Nearby.same(other, landed) && !Nearby.same(other, holder)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Forget {@code ref} (it left its store): as a holder, and from every holder's watch. */
    static void forget(@Nonnull Ref<EntityStore> ref) {
        WATCHED.remove(ref);
        for (Map<Ref<EntityStore>, Flight> watch : WATCHED.values()) {
            watch.remove(ref);
        }
    }

    /** Forget everything (a test). */
    static void clear() {
        WATCHED.clear();
    }
}
