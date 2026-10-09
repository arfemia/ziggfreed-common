package com.ziggfreed.common.calendar.attendance;

import java.util.ArrayList;
import java.util.HashSet;
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
 * per-player history an almanac or a cross-event achievement reads ({@link #yearsAttended} and
 * {@link #runsAttended}).
 *
 * <p>Saved as one {@code |}-joined string of entries, sorted so a save is stable: {@code <eventid>@<year>} for
 * run 1 (the form every older save holds) and {@code <eventid>@<year>#<n>} for any other run. A run number is a
 * name, never a place in the year, and a run reads as itself however its entry is written (run 1 hand-written as
 * {@code #1}, {@code #02} beside {@code #2}).
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

    /** Joins the number of any run but run 1 to its year in a saved entry. */
    private static final String RUN_MARK = "#";

    /** {@code <eventid>@<year>} or {@code <eventid>@<year>#<n>} for every run attended. */
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

    /** Did the player attend any run of {@code eventId} that began in {@code year}? */
    public boolean hasAttended(@Nullable String eventId, int year) {
        return runsAttended(eventId, year) > 0;
    }

    /** Did the player attend run {@code number} of {@code eventId}'s {@code year}, however its entry is written? */
    public boolean hasAttended(@Nullable String eventId, int year, int number) {
        for (int[] run : runs(eventId)) {
            if (run[0] == year && run[1] == number) {
                return true;
            }
        }
        return false;
    }

    /** Record run 1: the one run an event that comes round once a year has. */
    public boolean markAttended(@Nullable String eventId, int year) {
        return markAttended(eventId, year, 1);
    }

    /**
     * Record run {@code number} of the year; true when it is new (an entry already naming that run, however it is
     * written, makes it old). An id the save format cannot hold is refused with one warning.
     */
    public boolean markAttended(@Nullable String eventId, int year, int number) {
        if (usesReservedCharacter(eventId)) {
            SafeLog.warn("[calendar] attendance at '" + eventId + "' is not recorded: an event id may not carry"
                    + " '|' or '@', which the per-player save format reserves");
            return false;
        }
        return number >= 1 && !hasAttended(eventId, year, number) && attended.add(entry(eventId, year, number));
    }

    /** How many different runs of {@code eventId} that began in {@code year} the player attended. */
    public int runsAttended(@Nullable String eventId, int year) {
        Set<Integer> numbers = new HashSet<>();
        for (int[] run : runs(eventId)) {
            if (run[0] == year) {
                numbers.add(run[1]);
            }
        }
        return numbers.size();
    }

    /** The years of {@code eventId}'s runs attended, oldest first, each once however many of its runs were. */
    @Nonnull
    public List<Integer> yearsAttended(@Nullable String eventId) {
        Set<Integer> years = new TreeSet<>();
        for (int[] run : runs(eventId)) {
            years.add(run[0]);
        }
        return List.copyOf(years);
    }

    /** Every run of {@code eventId} attended, as {year, number}; a hand-edited entry that is no run is skipped. */
    @Nonnull
    private List<int[]> runs(@Nullable String eventId) {
        if (usesReservedCharacter(eventId)) {
            return List.of();
        }
        String prefix = eventId.trim().toLowerCase(Locale.ROOT) + "@";
        List<int[]> out = new ArrayList<>();
        for (String entry : attended) {
            if (!entry.startsWith(prefix)) {
                continue;
            }
            String run = entry.substring(prefix.length());
            int mark = run.indexOf(RUN_MARK);
            try {
                int year = Integer.parseInt(mark < 0 ? run : run.substring(0, mark));
                int number = mark < 0 ? 1 : Integer.parseInt(run.substring(mark + 1));
                if (number >= 1) {
                    out.add(new int[] {year, number});
                }
            } catch (NumberFormatException ignored) {
                // A hand-edited save entry that is not a run is skipped rather than guessed at.
            }
        }
        return out;
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

    /** A run's saved entry: {@code <id>@<year>} for run 1, {@code <id>@<year>#<n>} for any other run. */
    @Nonnull
    private static String entry(@Nonnull String eventId, int year, int number) {
        String run = number == 1 ? Integer.toString(year) : year + RUN_MARK + number;
        return eventId.trim().toLowerCase(Locale.ROOT) + "@" + run;
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
