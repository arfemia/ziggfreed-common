package com.ziggfreed.common.world;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.weather.config.Weather;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Controls a world's time-of-day and forced weather: pin the clock to a fixed point
 * (optionally pausing it so it never advances) and/or force a whole-world weather.
 * Mod-agnostic: it takes only a {@link World}, a day-time fraction, and a weather id
 * (a plain {@code String}), never any consumer round / state type - the consumer owns
 * WHICH time + weather to set (e.g. its own dark-weather candidate list and choice).
 *
 * <p><b>Mechanism (verified against the 0.5.3 decompile):</b> day-of-day is a fraction
 * of the calendar day from hour 0 - {@code 0.0} = midnight (darkest), {@code 0.5} = noon
 * (brightest) - set via {@code WorldTimeResource.setDayTime(fraction, world, store)}.
 * {@code WorldConfig.setGameTimePaused(boolean)} + {@code markChanged()} freezes / unfreezes
 * the clock. A forced weather id is VALIDATED first ({@code Weather.getAssetMap().getIndex(id)
 * != Integer.MIN_VALUE}; an unknown id would blank the sky) then applied to BOTH the live
 * {@code WeatherResource.setForcedWeather(id)} and the persisted {@code WorldConfig.setForcedWeather(id)}.
 *
 * <p>Every call self-hops via {@code world.execute} (safe to call from any thread) and is
 * fully try-guarded, so a missing resource / unready asset map degrades to a no-op rather
 * than throwing into the caller.
 */
public final class AtmosphereService {

    private AtmosphereService() {
    }

    /**
     * Set the world's time-of-day to {@code dayTimeFraction} and optionally pause the clock
     * so it never advances. Self-hops to the world thread; try-guarded.
     *
     * @param world           the world
     * @param dayTimeFraction the day fraction from hour 0 ({@code 0.0} = midnight / darkest,
     *                        {@code 0.5} = noon / brightest)
     * @param pauseTime       {@code true} to freeze the clock at that time, {@code false} to
     *                        let it advance normally
     */
    public static void setDayTime(@Nonnull World world, double dayTimeFraction, boolean pauseTime) {
        world.execute(() -> {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
                time.setDayTime(dayTimeFraction, world, store);
                WorldConfig cfg = world.getWorldConfig();
                cfg.setGameTimePaused(pauseTime);
                cfg.markChanged();
            } catch (Throwable t) {
                warn("setDayTime failed: " + t.getMessage());
            }
        });
    }

    /**
     * Force a whole-world weather (or clear the forced weather). The id is VALIDATED before
     * applying - an unknown / not-yet-registered id is skipped (it would blank the sky), so a
     * non-resolving id is a safe no-op. Self-hops to the world thread; try-guarded.
     *
     * @param world     the world
     * @param weatherId the {@code Weather} asset id to force, or {@code null} to clear the
     *                  forced weather (lets the world's natural weather resume)
     */
    public static void setForcedWeather(@Nonnull World world, @Nullable String weatherId) {
        world.execute(() -> {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                WeatherResource weather = store.getResource(WeatherResource.getResourceType());
                WorldConfig cfg = world.getWorldConfig();
                if (weatherId == null) {
                    weather.setForcedWeather(null);
                    cfg.setForcedWeather(null);
                } else if (isValidWeather(weatherId)) {
                    weather.setForcedWeather(weatherId);
                    cfg.setForcedWeather(weatherId);
                } else {
                    // Unknown / unready weather id: skip rather than blank the sky.
                    return;
                }
                cfg.markChanged();
            } catch (Throwable t) {
                warn("setForcedWeather failed: " + t.getMessage());
            }
        });
    }

    /**
     * Convenience: pin the world to a fixed (paused) time-of-day AND force a weather in one
     * call - e.g. lock a round world into a frozen dark midnight under a dark weather. Both
     * legs self-hop to the world thread and are independently try-guarded.
     *
     * @param world           the world
     * @param dayTimeFraction the day fraction to pin ({@code 0.0} = midnight / darkest)
     * @param weatherId       the {@code Weather} asset id to force (validated), or {@code null}
     *                        to leave the weather untouched / cleared
     */
    public static void lock(@Nonnull World world, double dayTimeFraction, @Nullable String weatherId) {
        setDayTime(world, dayTimeFraction, true);
        setForcedWeather(world, weatherId);
    }

    /** True if the weather id resolves to a registered asset (not {@code Integer.MIN_VALUE}). */
    private static boolean isValidWeather(@Nonnull String weatherId) {
        try {
            return Weather.getAssetMap().getIndex(weatherId) != Integer.MIN_VALUE;
        } catch (Throwable ignored) {
            // asset map not ready / id missing - treat as invalid (skip it).
            return false;
        }
    }

    private static void warn(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("[ziggfreed-common][atmosphere] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
