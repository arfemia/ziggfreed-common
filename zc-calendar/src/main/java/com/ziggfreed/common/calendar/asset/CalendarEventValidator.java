package com.ziggfreed.common.calendar.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.calendar.AnnualWindow;
import com.ziggfreed.common.calendar.LiveRule;
import com.ziggfreed.common.calendar.moon.MoonPhases;
import com.ziggfreed.common.inventory.ItemIds;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.TextKeyAudit;

/**
 * The content audit over the folded calendar events and the spawn rules that ride them, filed under the
 * {@value #DOMAIN} domain: what an author got wrong in an event or spawn file, said while the file is
 * still open. An event switched off in its own file is skipped, and while the owner has every event off
 * ({@code $Enabled: false}) the engine walk reports nothing, since off means absent.
 *
 * <p>Split the way every validator in this family is: {@link #audit()} is the engine walk (it never
 * throws) and the second {@code audit} is the pure core a test drives with fakes for the item store and
 * the lang catalogue.
 *
 * <p>An event's own problems ({@link CalendarEventAsset#problems()}) keep their codes and the words the
 * load log already says them in. The codes, each a stable machine token a consumer may filter on:
 * <ul>
 *   <li>ERROR every problem that stops the event running ({@code ID_RESERVED}, {@code ID_UNSAVABLE},
 *       {@code WINDOW_MISSING}, {@code WINDOW_UNREADABLE}, {@code WINDOW_RUN_INVALID}, {@code FIRST_YEAR_MISSING},
 *       {@code FIRST_YEAR_OUT_OF_RANGE}) or being live ({@code MOON_RANGE_INVALID}, {@code LIVE_EMPTY}),
 *       {@link #SPAWN_NO_EVENT}, {@link #SPAWN_NO_RULE_BODY};</li>
 *   <li>WARNING every other problem ({@code CLOCK_UNKNOWN}: the event runs, on UTC; {@code YEARS_ENTRY_IGNORED}:
 *       the event runs, without that one Years entry; {@code RUN_SET_ASIDE}: the event runs without each run set
 *       aside, one finding per run, naming it; {@code SKIP_UNKNOWN_RUN}: the event runs, and a Skip number its
 *       year does not have leaves nothing out, one finding per number), {@link #UNKNOWN_ICON},
 *       {@link #HERALD_WITHOUT_TITLE}, {@link #SPAWN_UNKNOWN_EVENT}, {@link #SPAWN_RIDES_CONDITION},
 *       {@link #MOON_UNKNOWN_WORLD}, {@link #MOON_RANGE_PAST_PHASES}, {@link #LIVE_UNKNOWN_EVENT} (one per id),
 *       {@link #LIVE_CYCLE}, and {@link TextKeyAudit#UNKNOWN_TEXT_KEY} for a Presentation or Herald key no loaded
 *       lang file ships.</li>
 * </ul>
 */
public final class CalendarEventValidator {

    public static final String DOMAIN = "calendar";

    /** The label the calendar audit's lines carry when it is logged whole (zc's boot audit). */
    public static final String LOG_LABEL = "[calendar] audit";

    public static final String UNKNOWN_ICON = "UNKNOWN_ICON";
    public static final String HERALD_WITHOUT_TITLE = "HERALD_WITHOUT_TITLE";
    public static final String SPAWN_NO_EVENT = "SPAWN_NO_EVENT";
    public static final String SPAWN_NO_RULE_BODY = "SPAWN_NO_RULE_BODY";
    public static final String SPAWN_UNKNOWN_EVENT = "SPAWN_UNKNOWN_EVENT";
    /** A spawn rides an event live by a Moon or a Live, which has no runs, so it never writes its rule. */
    public static final String SPAWN_RIDES_CONDITION = "SPAWN_RIDES_CONDITION";
    /** A Moon names a World this server has not loaded; the event waits until that world loads. */
    public static final String MOON_UNKNOWN_WORLD = "MOON_UNKNOWN_WORLD";
    /** A MoonPhaseRange names a phase past the last its world's cycle has. */
    public static final String MOON_RANGE_PAST_PHASES = "MOON_RANGE_PAST_PHASES";
    /** A Live names an event this server has not loaded; it reads as not live. */
    public static final String LIVE_UNKNOWN_EVENT = "LIVE_UNKNOWN_EVENT";
    /** A Live leads round a loop back to an event on its path; the event is refused, never live. */
    public static final String LIVE_CYCLE = "LIVE_CYCLE";

    /** The problems that stop an event running or being live, so each is an error; any other is a warning. */
    private static final Set<String> NEVER_RUNS = Set.of(
            CalendarEventAsset.PROBLEM_ID_RESERVED,
            CalendarEventAsset.PROBLEM_ID_UNSAVABLE,
            CalendarEventAsset.PROBLEM_WINDOW_MISSING,
            CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE,
            CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID,
            CalendarEventAsset.PROBLEM_FIRST_YEAR_MISSING,
            CalendarEventAsset.PROBLEM_FIRST_YEAR_OUT_OF_RANGE,
            CalendarEventAsset.PROBLEM_MOON_RANGE_INVALID,
            CalendarEventAsset.PROBLEM_LIVE_EMPTY);

    /** What an audit that cannot ask the engine knows of a world: loaded, its phase count unknown. */
    private static final Function<String, Integer> ANY_WORLD = world -> 0;

    private CalendarEventValidator() {
    }

    /** The engine walk over every folded event and spawn, against the live item store and lang catalogue. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            CalendarEventConfig events = CalendarEventConfig.getInstance();
            if (!events.isGlobalEnabled()) {
                return List.of();
            }
            return audit(events.all().values(), CalendarSpawnConfig.getInstance().all().values(),
                    ItemIds::exists, TextKeyAudit.liveCatalogue(), CalendarEventValidator::liveWorldPhases);
        } catch (Throwable t) {
            SafeLog.warn("[calendar] the calendar audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /** The pure core with every world a Moon names read as loaded, its phase count unknown. */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<CalendarEventAsset> events,
            @Nonnull Collection<CalendarSpawnAsset> spawns, @Nonnull Predicate<String> itemKnown,
            @Nonnull Predicate<String> keyShipped) {
        return audit(events, spawns, itemKnown, keyShipped, ANY_WORLD);
    }

    /**
     * The pure core, in id order so a report reads the same twice running.
     *
     * @param events      every loaded event, switched on or off (a spawn riding a switched-off one is not
     *                    riding an unknown one, nor is a Live naming one)
     * @param itemKnown   whether an item id names a loaded item
     * @param keyShipped  whether a loaded lang file ships a key
     * @param worldPhases the phase count of the world a Moon names (null or blank: the default world): above 0 for a
     *                    loaded world, 0 for a loaded world whose count cannot be told, null for a world not loaded
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<CalendarEventAsset> events,
            @Nonnull Collection<CalendarSpawnAsset> spawns, @Nonnull Predicate<String> itemKnown,
            @Nonnull Predicate<String> keyShipped, @Nonnull Function<String, Integer> worldPhases) {
        List<Finding> out = new ArrayList<>();
        Set<String> loaded = new HashSet<>();
        Set<String> conditions = new HashSet<>();
        Map<String, CalendarEventAsset> indexed = new HashMap<>();
        List<CalendarEventAsset> sorted = byId(events, CalendarEventAsset::getId);
        for (CalendarEventAsset event : sorted) {
            loaded.add(event.getId());
            indexed.put(event.getId(), event);
            if (event.isCondition()) {
                conditions.add(event.getId());
            }
        }
        // The loops a Live leads round, through the events that follow other events and are switched on in their file.
        Function<String, LiveRule> rules = id -> {
            CalendarEventAsset event = indexed.get(id);
            return event == null || !event.isEnabled() || !event.canFollow() ? null : event.liveRule();
        };
        for (CalendarEventAsset event : sorted) {
            if (event.isEnabled()) {
                auditEvent(event, itemKnown, keyShipped, out);
                auditCondition(event, loaded, rules, worldPhases, out);
            }
        }
        for (CalendarSpawnAsset spawn : byId(spawns, CalendarSpawnAsset::getId)) {
            auditSpawn(spawn, loaded, conditions, out);
        }
        return out;
    }

    /**
     * What an event live by a condition gets wrong across the loaded set and the server's worlds: a Moon's unknown
     * world or a phase past its cycle's last, a Live's unknown event (one finding per id) or loop.
     */
    private static void auditCondition(@Nonnull CalendarEventAsset event, @Nonnull Set<String> loaded,
            @Nonnull Function<String, LiveRule> rules, @Nonnull Function<String, Integer> worldPhases,
            @Nonnull List<Finding> out) {
        if (!event.canFollow()) {
            return;
        }
        String id = event.getId();
        String where = "the calendar event '" + id + "'";
        LiveRule rule = event.liveRule();
        if (rule != null) {
            for (String member : rule.members()) {
                if (!loaded.contains(member)) {
                    out.add(Finding.warning(DOMAIN, LIVE_UNKNOWN_EVENT, where + " names '" + member + "' in its Live, "
                            + "which is no loaded calendar event, so it reads as not live", id));
                }
            }
            List<String> loop = LiveRule.loopFrom(id, rules);
            if (loop != null) {
                out.add(Finding.warning(DOMAIN, LIVE_CYCLE, where + " follows events in its Live that lead round a "
                        + "loop (" + String.join(" > ", loop) + "), so it is never live: an event can never be live "
                        + "by itself", id));
            }
            return;
        }
        int[] range = event.moonRange();
        String world = event.moonWorld();
        String named = world == null ? "the default world" : "the World '" + world + "'";
        Integer phases = worldPhases.apply(world);
        if (phases == null) {
            out.add(Finding.warning(DOMAIN, MOON_UNKNOWN_WORLD, where + " follows the moon of " + named
                    + ", which this server has not loaded, so it waits until that world loads", id));
        } else if (range != null && phases > 0 && (range[0] >= phases || range[1] >= phases)) {
            out.add(Finding.warning(DOMAIN, MOON_RANGE_PAST_PHASES, where + " has a MoonPhaseRange of [" + range[0]
                    + ", " + range[1] + "], but the moon of " + named + " has phases 0 to " + (phases - 1)
                    + ", so a phase past " + (phases - 1) + " never comes", id));
        }
    }

    /**
     * The phase count of {@code world}'s moon (null or blank: the default world): its gameplay config's
     * {@code TotalMoonPhases}; null when the server has not loaded it, 0 when it cannot be told (no universe yet).
     */
    @Nullable
    private static Integer liveWorldPhases(@Nullable String world) {
        try {
            Universe universe = Universe.get();
            String name = universe == null ? null : MoonPhases.resolve(world);
            if (name == null) {
                return 0;
            }
            World loaded = universe.getWorld(name);
            return loaded == null ? null : loaded.getGameplayConfig().getWorldConfig().getTotalMoonPhases();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void auditEvent(@Nonnull CalendarEventAsset event, @Nonnull Predicate<String> itemKnown,
            @Nonnull Predicate<String> keyShipped, @Nonnull List<Finding> out) {
        String id = event.getId();
        String where = "the calendar event '" + id + "'";
        for (String problem : event.problems()) {
            if (CalendarEventAsset.PROBLEM_RUN_SET_ASIDE.equals(problem)) {
                // One finding per run set aside, each naming its number, its days and the run it meets.
                for (AnnualWindow.SetAside aside : event.setAside()) {
                    out.add(Finding.warning(DOMAIN, problem, where + " sets aside its run " + aside.number() + " of "
                            + aside.year() + ", " + aside.run().first() + " to " + aside.run().last()
                            + ", which meets its run of " + aside.meets().first() + " to " + aside.meets().last()
                            + " (before it in the list, or the year before's): runs of one event never overlap, so "
                            + "the one written first is kept and this one does not run", id));
                }
                continue;
            }
            if (CalendarEventAsset.PROBLEM_SKIP_UNKNOWN_RUN.equals(problem)) {
                // One finding per number a year's Skip names that the year does not have.
                for (Map.Entry<Integer, List<Integer>> year : event.unknownSkips().entrySet()) {
                    for (int number : year.getValue()) {
                        out.add(Finding.warning(DOMAIN, problem, where + " skips run " + number + " of "
                                + year.getKey() + " in its Window Years, a run that year does not have (a run in a "
                                + "list is its place, a Monthly run its month, a Weekly run its calendar week), so "
                                + "the skip leaves nothing out", id));
                    }
                }
                continue;
            }
            String message = where + " " + CalendarEventConfig.sentence(problem);
            out.add(NEVER_RUNS.contains(problem)
                    ? Finding.error(DOMAIN, problem, message, id)
                    : Finding.warning(DOMAIN, problem, message, id));
        }
        CalendarEventAsset.Presentation presentation = event.presentation();
        if (presentation != null) {
            String icon = presentation.icon();
            if (icon != null && !itemKnown.test(icon)) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_ICON, where + " names the Presentation.Icon '" + icon
                        + "', which is no loaded item, so nothing stands for the event where events are listed", id));
            }
            TextKeyAudit.check(out, DOMAIN, id, where + " Presentation.TitleKey", presentation.titleKey(), keyShipped,
                    "wherever the event is named, players read a fallback or the raw key");
            TextKeyAudit.check(out, DOMAIN, id, where + " Presentation.FlavorKey", presentation.flavorKey(),
                    keyShipped, "wherever the line about the event shows, players read the raw key");
        }
        auditHerald(where + " Herald.Start", event.heraldStart(), id, keyShipped, out);
        auditHerald(where + " Herald.End", event.heraldEnd(), id, keyShipped, out);
    }

    private static void auditHerald(@Nonnull String where, @Nullable CalendarEventAsset.HeraldLine line,
            @Nonnull String id, @Nonnull Predicate<String> keyShipped, @Nonnull List<Finding> out) {
        if (line == null) {
            return;
        }
        if (line.titleKey() == null) {
            out.add(Finding.warning(DOMAIN, HERALD_WITHOUT_TITLE, where + " has no TitleKey, and a banner with no "
                    + "title is never shown, so its SubtitleKey and Major go unseen", id));
            return;
        }
        TextKeyAudit.check(out, DOMAIN, id, where + ".TitleKey", line.titleKey(), keyShipped,
                "the banner shows the raw key");
        TextKeyAudit.check(out, DOMAIN, id, where + ".SubtitleKey", line.subtitleKey(), keyShipped,
                "the banner's second line shows the raw key");
    }

    private static void auditSpawn(@Nonnull CalendarSpawnAsset spawn, @Nonnull Set<String> loadedEvents,
            @Nonnull Set<String> conditions, @Nonnull List<Finding> out) {
        String id = spawn.getId();
        String where = "the calendar spawn '" + id + "'";
        String event = spawn.eventId();
        if (event == null) {
            out.add(Finding.error(DOMAIN, SPAWN_NO_EVENT, where + " names no Event, so it never writes its rule", id));
        } else if (!loadedEvents.contains(event)) {
            out.add(Finding.warning(DOMAIN, SPAWN_UNKNOWN_EVENT, where + " rides the Event '" + event
                    + "', which names no loaded calendar event, so it never writes its rule", id));
        } else if (conditions.contains(event)) {
            out.add(Finding.warning(DOMAIN, SPAWN_RIDES_CONDITION, where + " rides the Event '" + event
                    + "', which is live by a Moon or a Live rather than by dates and so has no runs, so it never "
                    + "writes its rule; a world spawn's own MoonPhaseRange follows the moon", id));
        }
        if (spawn.spawnJson() == null) {
            out.add(Finding.error(DOMAIN, SPAWN_NO_RULE_BODY, where + " has no Spawn rule body (an object), so it "
                    + "never writes its rule", id));
        }
    }

    /** {@code assets} without nulls or id-less entries, sorted by id. */
    @Nonnull
    private static <T> List<T> byId(@Nonnull Collection<T> assets, @Nonnull Function<T, String> id) {
        List<T> out = new ArrayList<>();
        for (T asset : assets) {
            if (asset != null && id.apply(asset) != null) {
                out.add(asset);
            }
        }
        out.sort(Comparator.comparing(id));
        return out;
    }
}
