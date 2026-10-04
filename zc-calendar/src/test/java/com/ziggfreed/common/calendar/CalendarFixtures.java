package com.ziggfreed.common.calendar;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.asset.CalendarEventConfig;

/**
 * Reads calendar files back the way the engine's asset loading does, and puts every calendar store
 * and switch back the way a bare server starts, so a test reads as what it proves.
 */
public final class CalendarFixtures {

    /** Hallow's Eve as the design dates it: October 1st through November 3rd, first run 2026. */
    public static final String HALLOWS_EVE = """
            { "Window": { "Start": "10-01", "End": "11-03" }, "FirstYear": 2026 }
            """;

    /** Harvest Moon: October 29th through 31st, first run 2026. */
    public static final String HARVEST_MOON = """
            { "Window": { "Start": "10-29", "End": "10-31" }, "FirstYear": 2026 }
            """;

    private CalendarFixtures() {
    }

    /** An ISO instant ({@code 2026-10-02T12:00:00Z}) in epoch milliseconds. */
    public static long at(@Nonnull String isoInstant) {
        return Instant.parse(isoInstant).toEpochMilli();
    }

    @Nonnull
    public static CalendarEventAsset event(@Nonnull String id, @Nonnull String json) {
        return event(id, json, null);
    }

    /** One event file, decoded over {@code parent} the way an owner entry or a Parent child is. */
    @Nonnull
    public static CalendarEventAsset event(@Nonnull String id, @Nonnull String json,
            @Nullable CalendarEventAsset parent) {
        try {
            return CalendarEventAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                    new AssetExtraInfo<>(new AssetExtraInfo.Data(CalendarEventAsset.class, id,
                            parent == null ? null : id)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replace the pack layer with {@code byId} (keys lower-cased, the way the store keys them). */
    public static void loadEvents(@Nonnull Map<String, CalendarEventAsset> byId) {
        CalendarEventConfig.getInstance().mergePackLayer(byId);
    }

    /** Hallows_Eve and Harvest_Moon in the pack layer. */
    public static void loadDesignEvents() {
        loadEvents(Map.of(
                "hallows_eve", event("Hallows_Eve", HALLOWS_EVE),
                "harvest_moon", event("Harvest_Moon", HARVEST_MOON)));
    }

    /** Every calendar store and switch back to a bare server's. */
    public static void reset() {
        CalendarEventConfig.getInstance().mergePackLayer(Map.of());
        CalendarEventConfig.getInstance().mergeOwnerLayer(Map.of());
        CalendarEventConfig.getInstance().setGlobalEnabled(true);
        CalendarForces.getInstance().clearAll();
    }

    /** The keys an en-US lang file of this module ships ({@code ziggfreedcommon.calendar.lang}, for one). */
    @Nonnull
    public static Set<String> englishKeys(@Nonnull String langFileName) {
        Path file = Path.of("src", "main", "resources", "Server", "Languages", "en-US", langFileName);
        Set<String> keys = new TreeSet<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                int eq = trimmed.indexOf('=');
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && eq > 0) {
                    keys.add(trimmed.substring(0, eq).trim());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return keys;
    }
}
