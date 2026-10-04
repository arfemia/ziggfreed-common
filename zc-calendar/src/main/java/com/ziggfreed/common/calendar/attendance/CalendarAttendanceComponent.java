package com.ziggfreed.common.calendar.attendance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.util.SafeLog;

/**
 * Which runs of which calendar events a player has been on the server for, persisted on the player: the
 * record that makes attendance count once per run and the start banner show once per run, and the
 * per-player history an almanac or a cross-event achievement reads ({@link #yearsAttended}).
 *
 * <p>Saved as one {@code |}-joined string of {@code <eventid>@<year>} entries, sorted so a save is stable.
 * An event id carrying {@code |} or {@code @} is refused, since the format reserves both. Registered at
 * library setup, BEFORE any world loads, and attached to every player on connect; a consumer PEEKS it
 * ({@link #TYPE} may be null when registration failed) and reads a missing component as no attendance.
 */
public class CalendarAttendanceComponent implements Component<EntityStore> {

    /** The engine registry id; the persisted save key. */
    public static final String REGISTRY_ID = "ZiggfreedCommon:CalendarAttendance";

    @Nullable
    public static ComponentType<EntityStore, CalendarAttendanceComponent> TYPE;

    public static final BuilderCodec<CalendarAttendanceComponent> CODEC;

    /** {@code <eventid>@<year>} for every run attended. */
    private final Set<String> attended = ConcurrentHashMap.newKeySet();

    static {
        var builder = BuilderCodec.builder(CalendarAttendanceComponent.class, CalendarAttendanceComponent::new);
        builder.append(new KeyedCodec<>("Attended", Codec.STRING, false),
                (c, v, info) -> c.load(v),
                (c, info) -> c.save()).add();
        CODEC = builder.build();
    }

    public CalendarAttendanceComponent() {
    }

    /**
     * True when {@code eventId} cannot be saved: blank, or carrying {@code |} or {@code @}. The event asset
     * refuses the second kind at load ({@link CalendarEventAsset#carriesAttendanceSeparator}), so no such
     * event runs.
     */
    public static boolean usesReservedCharacter(@Nullable String eventId) {
        return eventId == null || eventId.isBlank() || CalendarEventAsset.carriesAttendanceSeparator(eventId);
    }

    public boolean hasAttended(@Nullable String eventId, int year) {
        return !usesReservedCharacter(eventId) && attended.contains(entry(eventId, year));
    }

    /** Record the run; true when it is new. An id the save format cannot hold is refused with one warning. */
    public boolean markAttended(@Nullable String eventId, int year) {
        if (usesReservedCharacter(eventId)) {
            SafeLog.warn("[calendar] attendance at '" + eventId + "' is not recorded: an event id may not carry"
                    + " '|' or '@', which the per-player save format reserves");
            return false;
        }
        return attended.add(entry(eventId, year));
    }

    /** The years of {@code eventId}'s runs attended, oldest first. */
    @Nonnull
    public List<Integer> yearsAttended(@Nullable String eventId) {
        if (usesReservedCharacter(eventId)) {
            return List.of();
        }
        String prefix = eventId.trim().toLowerCase(Locale.ROOT) + "@";
        List<Integer> years = new ArrayList<>();
        for (String entry : attended) {
            if (!entry.startsWith(prefix)) {
                continue;
            }
            try {
                years.add(Integer.parseInt(entry.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // A hand-edited save entry that is not a year is skipped rather than guessed at.
            }
        }
        years.sort(null);
        return List.copyOf(years);
    }

    /** Every event attended at least once, lower-cased and sorted. */
    @Nonnull
    public List<String> eventsAttended() {
        Set<String> events = new TreeSet<>();
        for (String entry : attended) {
            int at = entry.indexOf('@');
            if (at > 0) {
                events.add(entry.substring(0, at));
            }
        }
        return List.copyOf(events);
    }

    @Nonnull
    String save() {
        return String.join("|", new TreeSet<>(attended));
    }

    void load(@Nullable String blob) {
        attended.clear();
        if (blob == null || blob.isEmpty()) {
            return;
        }
        for (String entry : blob.split("\\|")) {
            if (!entry.isBlank()) {
                // Folded like every write, so an entry edited by hand in another casing is still that run.
                attended.add(entry.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    @Nonnull
    private static String entry(@Nonnull String eventId, int year) {
        return eventId.trim().toLowerCase(Locale.ROOT) + "@" + year;
    }

    /** Register the type with the entity-store registry. Once, at library setup; never throws. */
    @Nullable
    public static ComponentType<EntityStore, CalendarAttendanceComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(CalendarAttendanceComponent.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn("[calendar] could not register the attendance component", t);
            return null;
        }
    }

    /** Hang the connect hook that attaches one of these to every player. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().register(PlayerConnectEvent.class, CalendarAttendanceComponent::onPlayerConnect);
    }

    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            if (TYPE == null) {
                return;
            }
            event.getHolder().ensureAndGetComponent(TYPE);
        } catch (Throwable t) {
            SafeLog.warn("[calendar] could not ensure the attendance component", t);
        }
    }

    @Override
    @SuppressWarnings("CloneDeclaresCloneNotSupported")
    public CalendarAttendanceComponent clone() {
        CalendarAttendanceComponent copy = new CalendarAttendanceComponent();
        copy.attended.addAll(this.attended);
        return copy;
    }
}
