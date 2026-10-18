package com.ziggfreed.common.calendar.moon;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.StoreSystem;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.ecs.MoonPhaseChangeEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.calendar.CalendarRuntime;
import com.ziggfreed.common.util.SafeLog;

/**
 * The two systems that keep {@link MoonPhases} in step with each world. {@link Seed} reads a world's phase once,
 * on that world's own thread, when its store comes up (deferred through {@code World.execute}, as the engine's
 * {@code WorldTimeSystems.Init} defers its own first moon update), and forgets the world when its store goes.
 * {@link Changed} hears every {@code MoonPhaseChangeEvent} after that: the engine fires one when the phase moves at
 * midday, when {@code /time} or sleeping moves the clock, and never from a paused world's tick, so a paused world
 * keeps its phase until someone sets its time. The event fires before the engine stores the new phase, so the phase comes from the
 * event, never the resource. A change asks the calendar to look again at once, so a moon event rises without
 * waiting for the minute tick.
 */
public final class MoonPhaseSystems {

    private MoonPhaseSystems() {
    }

    /** A world's first reading, and its last when its store goes. */
    public static final class Seed extends StoreSystem<EntityStore> {

        @Override
        public void onSystemAddedToStore(@Nonnull Store<EntityStore> store) {
            try {
                World world = store.getExternalData().getWorld();
                if (world == null) {
                    return;
                }
                world.execute(() -> seed(world, store));
            } catch (Throwable t) {
                SafeLog.warn("[calendar] could not read a world's moon as it came up", t);
            }
        }

        @Override
        public void onSystemRemovedFromStore(@Nonnull Store<EntityStore> store) {
            try {
                World world = store.getExternalData().getWorld();
                if (world != null) {
                    MoonPhases.getInstance().forget(world.getName());
                }
            } catch (Throwable t) {
                SafeLog.warn("[calendar] could not forget a world's moon as it went", t);
            }
        }
    }

    /** Every moon phase change after the first reading. */
    public static final class Changed extends WorldEventSystem<EntityStore, MoonPhaseChangeEvent> {

        public Changed() {
            super(MoonPhaseChangeEvent.class);
        }

        @Override
        public void handle(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer,
                @Nonnull MoonPhaseChangeEvent event) {
            try {
                World world = store.getExternalData().getWorld();
                if (world != null) {
                    heard(world, event.getNewMoonPhase());
                }
            } catch (Throwable t) {
                SafeLog.warn("[calendar] could not take in a moon phase change", t);
            }
        }
    }

    /** The seed's read, on the world's thread: the phase the world holds now. */
    private static void seed(@Nonnull World world, @Nonnull Store<EntityStore> store) {
        try {
            WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
            if (time != null) {
                heard(world, time.getMoonPhase());
            }
        } catch (Throwable t) {
            SafeLog.warn("[calendar] could not read the moon of " + world.getName(), t);
        }
    }

    /** Keep {@code phase} for {@code world}, with its cycle's length, and have the calendar look again on news. */
    private static void heard(@Nonnull World world, int phase) {
        int total = world.getGameplayConfig().getWorldConfig().getTotalMoonPhases();
        if (MoonPhases.getInstance().record(world.getName(), phase, total)) {
            CalendarRuntime.ticker().requestEvaluation();
        }
    }
}
