package com.ziggfreed.common.calendar.spawn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.calendar.asset.CalendarSpawnAsset;

/**
 * What every calendar spawn rule should hold right now. Pure.
 *
 * <p>A rule is never removed, only RETIRED: removing a world-spawn rule makes the engine look its id up in a
 * table that answers 0 for an id it never set up, and it then removes spawn configuration 0 instead (a
 * vanilla rule). A retired rule is the same body with {@code MoonPhaseWeightModifiers [0]}: the engine reads
 * every moon phase at or past the end of that array as 0, so nothing new spawns, and the rewrite takes the
 * engine's own rebuild-in-place path.
 */
public final class CalendarSpawnPlan {

    /** The leaf a retired rule zeroes. */
    static final String MOON_PHASES = "MoonPhaseWeightModifiers";

    private CalendarSpawnPlan() {
    }

    /**
     * Which file owns each rule now: among the files whose event is running and which carry a body, the
     * highest Priority, a tie to the smaller file id. Keyed by lower-cased rule id, in order.
     */
    @Nonnull
    public static Map<String, CalendarSpawnAsset> owners(@Nonnull Collection<CalendarSpawnAsset> spawns,
            @Nonnull Set<String> liveEventIds) {
        Map<String, CalendarSpawnAsset> out = new TreeMap<>();
        for (CalendarSpawnAsset spawn : spawns) {
            String event = spawn.eventId();
            if (event == null || !liveEventIds.contains(event) || spawn.spawnJson() == null) {
                continue;
            }
            out.merge(spawn.ruleId(), spawn, CalendarSpawnPlan::stronger);
        }
        return out;
    }

    /** Each owned rule holds its owner's body; a rule written earlier and owned by nobody now holds it retired. */
    @Nonnull
    public static Map<String, String> target(@Nonnull Map<String, String> written,
            @Nonnull Map<String, CalendarSpawnAsset> owners) {
        Map<String, String> out = new TreeMap<>();
        owners.forEach((rule, spawn) -> out.put(rule, spawn.spawnJson()));
        written.forEach((rule, json) -> out.putIfAbsent(rule, retired(json)));
        return out;
    }

    /** The entries of {@code target} that differ from what is written. */
    @Nonnull
    public static Map<String, String> writes(@Nonnull Map<String, String> written,
            @Nonnull Map<String, String> target) {
        Map<String, String> out = new TreeMap<>();
        target.forEach((rule, json) -> {
            if (!json.equals(written.get(rule))) {
                out.put(rule, json);
            }
        });
        return out;
    }

    /** {@code json} with {@code MoonPhaseWeightModifiers [0]}; retiring a retired body changes nothing. */
    @Nonnull
    public static String retired(@Nonnull String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            return json;
        }
        JsonObject body = parsed.getAsJsonObject();
        JsonArray zero = new JsonArray();
        zero.add(0);
        body.add(MOON_PHASES, zero);
        return body.toString();
    }

    /**
     * Role and environment pairs ({@code role|environment}, lower-cased) that two owned rules both name:
     * the engine sets up the first rule it reaches and refuses the other with a SEVERE line. An NPC entry
     * whose Id is not plain text is stepped past, never thrown on: the engine reports it when it reads the
     * body, and one malformed file must not stop every other rule being written.
     */
    @Nonnull
    public static Map<String, List<String>> sharedPairs(@Nonnull Map<String, CalendarSpawnAsset> owners) {
        Map<String, List<String>> rulesByPair = new TreeMap<>();
        owners.forEach((rule, spawn) -> {
            for (String pair : pairs(spawn.spawnJson())) {
                rulesByPair.computeIfAbsent(pair, p -> new ArrayList<>()).add(rule);
            }
        });
        rulesByPair.values().removeIf(rules -> rules.size() < 2);
        return rulesByPair;
    }

    @Nonnull
    static Set<String> pairs(@Nullable String json) {
        Set<String> out = new TreeSet<>();
        if (json == null) {
            return out;
        }
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            return out;
        }
        JsonObject body = parsed.getAsJsonObject();
        List<String> environments = lowerStrings(body.get("Environments"));
        JsonElement npcs = body.get("NPCs");
        if (npcs == null || !npcs.isJsonArray()) {
            return out;
        }
        for (JsonElement npc : npcs.getAsJsonArray()) {
            JsonElement id = npc.isJsonObject() ? npc.getAsJsonObject().get("Id") : null;
            if (id == null || !id.isJsonPrimitive()) {
                continue;
            }
            String role = id.getAsString().trim().toLowerCase(Locale.ROOT);
            for (String environment : environments) {
                out.add(role + "|" + environment);
            }
        }
        return out;
    }

    @Nonnull
    private static List<String> lowerStrings(@Nullable JsonElement array) {
        List<String> out = new ArrayList<>();
        if (array == null || !array.isJsonArray()) {
            return out;
        }
        for (JsonElement value : array.getAsJsonArray()) {
            if (value.isJsonPrimitive()) {
                out.add(value.getAsString().trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    @Nonnull
    private static CalendarSpawnAsset stronger(@Nonnull CalendarSpawnAsset a, @Nonnull CalendarSpawnAsset b) {
        if (a.priority() != b.priority()) {
            return a.priority() > b.priority() ? a : b;
        }
        return a.getId().compareTo(b.getId()) <= 0 ? a : b;
    }
}
