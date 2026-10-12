package com.ziggfreed.common.instance.dungeon;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.builtin.adventure.worldevents.WorldEventsConfig;
import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.hypixel.hytale.server.core.asset.type.portalworld.PortalType;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.ziggfreed.common.encounter.validate.EncounterScriptScan;
import com.ziggfreed.common.encounter.validate.EncounterScripts;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;
import com.ziggfreed.common.validation.TextKeyAudit;

/**
 * The content audit over the folded dungeons, filed under the {@code dungeon} domain, run once every pack
 * has loaded (the boot audit's pass). A switched-off dungeon is skipped.
 *
 * <p>Split the way every validator in this family is: {@link #audit()} is the thin engine walk (the loaded
 * packs' instance templates and prefabs, the server's view distance, the gameplay configs) and
 * {@link #audit(Collection, Predicate, Function, Predicate, Predicate, int, Predicate)} the pure core.
 *
 * <p>The codes:
 * <ul>
 *   <li>ERROR {@link #TEMPLATE_INSTANCE_KEY} (the engine converges every spawn and return that goes by a
 *       template's {@code InstanceKey} on one world, which a copy zc names must never share), {@link #SPACING_TOO_SMALL}
 *       (areas closer than twice the view distance see each other), {@link #PACKED_PORTAL_TIMER} and
 *       {@link #PACKED_WORLD_EVENTS} (a portal's timer and world events belong to the whole world, so a copy
 *       shared by several parties would share them);</li>
 *   <li>WARNING {@link #UNKNOWN_DUNGEON_TEMPLATE}, {@link #UNKNOWN_DUNGEON_PREFAB},
 *       {@link #NO_DUNGEON_PREFAB} and {@link #UNKNOWN_DUNGEON_BOSS} (an id may belong to a pack this server
 *       does not run), {@link #NO_DUNGEON_CLEAR} (nothing in {@code Complete} can clear a run, so every run
 *       ends failed), and the shared {@code UNKNOWN_SEASON} and {@code UNKNOWN_TEXT_KEY}.</li>
 * </ul>
 */
public final class DungeonValidator {

    public static final String DOMAIN = "dungeon";

    /** The label the dungeon audit's lines carry when it is logged whole (zc's boot audit). */
    public static final String LOG_LABEL = "[dungeon] audit";

    public static final String UNKNOWN_DUNGEON_TEMPLATE = "UNKNOWN_DUNGEON_TEMPLATE";
    public static final String UNKNOWN_DUNGEON_PREFAB = "UNKNOWN_DUNGEON_PREFAB";
    public static final String NO_DUNGEON_PREFAB = "NO_DUNGEON_PREFAB";
    public static final String TEMPLATE_INSTANCE_KEY = "TEMPLATE_INSTANCE_KEY";
    public static final String SPACING_TOO_SMALL = "SPACING_TOO_SMALL";
    public static final String PACKED_PORTAL_TIMER = "PACKED_PORTAL_TIMER";
    public static final String PACKED_WORLD_EVENTS = "PACKED_WORLD_EVENTS";
    public static final String NO_DUNGEON_CLEAR = "NO_DUNGEON_CLEAR";
    public static final String UNKNOWN_DUNGEON_BOSS = "UNKNOWN_DUNGEON_BOSS";

    /** The template's config file, inside its folder under {@code Server/Instances/}. */
    static final String TEMPLATE_CONFIG = "instance.bson";

    /** The gameplay config a template naming none runs (the engine's own default id). */
    static final String DEFAULT_GAMEPLAY_CONFIG = "Default";

    /** The view radius, in chunks, assumed when the server's cannot be read: the engine's own ceiling. */
    static final int FALLBACK_VIEW_RADIUS = 32;

    /**
     * What the audit reads off a template's {@code instance.bson}: the {@code InstanceKey} it pins (null for
     * none), whether it carries a portal's timer, and whether world events run in it.
     */
    public record TemplateFacts(@Nullable String instanceKey, boolean portalTimed, boolean worldEvents) {
    }

    /** A dungeon's entry verdict: the loaded asset it was read for, and its ERROR codes. */
    private record EntryVerdict(@Nonnull DungeonAsset dungeon, @Nonnull List<String> errors) {
    }

    /** Each dungeon's entry verdict, by the fold's lower-cased id. */
    private static final Map<String, EntryVerdict> ENTRY_VERDICTS = new ConcurrentHashMap<>();

    private DungeonValidator() {
    }

    /**
     * The ERROR codes the audit finds in {@code dungeon} as loaded now, filed under {@code id} (the fold's
     * lower-cased id); empty when it has none. The entry refuses a dungeon with any, since its copies would break
     * what shared areas rely on. The boot audit runs only when the process asks for it, so the entry asks here:
     * the same engine walk, once per loaded asset (a reload folds new ones), each ERROR's sentence logged once.
     */
    @Nonnull
    public static List<String> entryErrors(@Nonnull String id, @Nonnull DungeonAsset dungeon) {
        EntryVerdict known = ENTRY_VERDICTS.get(id);
        if (known != null && known.dungeon() == dungeon) {
            return known.errors();
        }
        List<String> errors = new ArrayList<>();
        try {
            for (Finding finding : audit(List.of(dungeon), DungeonValidator::templateKnown,
                    DungeonValidator::templateFacts, DungeonValidator::prefabKnown, script -> true, minimumSpacing(),
                    key -> true)) {
                if (finding.severity() == Severity.ERROR) {
                    errors.add(finding.code());
                    SafeLog.warn(LOG_LABEL + " '" + id + "' [" + finding.code() + "] " + finding.message()
                            + "; it cannot be entered until this is fixed");
                }
            }
        } catch (Throwable t) {
            // What could not be told is no ERROR, and the verdict is not kept: the next entry asks again.
            SafeLog.warn("[dungeon] the entry audit of '" + id + "' failed: " + t.getMessage(), t);
            return List.copyOf(errors);
        }
        List<String> verdict = List.copyOf(errors);
        ENTRY_VERDICTS.put(id, new EntryVerdict(dungeon, verdict));
        return verdict;
    }

    /** The engine walk over every folded dungeon. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            return audit(DungeonConfig.getInstance().all().values(),
                    DungeonValidator::templateKnown,
                    DungeonValidator::templateFacts,
                    DungeonValidator::prefabKnown,
                    loadedScripts(),
                    minimumSpacing(),
                    TextKeyAudit.liveCatalogue());
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] the dungeon audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /**
     * The pure core.
     *
     * @param templateKnown  whether a template id names a folder some loaded pack ships under {@code Server/Instances/}
     * @param templateFacts  what a known template's config says ({@link TemplateFacts}), null when it cannot be read
     * @param prefabKnown    whether a path under {@code Server/Prefabs/} names a prefab some loaded pack ships
     * @param scriptKnown    whether an id names an encounter script some loaded pack ships
     * @param minimumSpacing the least {@code Area.Spacing}, in blocks, that keeps areas out of each other's view
     * @param textShipped    whether a lang key is shipped ({@link TextKeyAudit#liveCatalogue})
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<DungeonAsset> dungeons,
            @Nonnull Predicate<String> templateKnown, @Nonnull Function<String, TemplateFacts> templateFacts,
            @Nonnull Predicate<String> prefabKnown, @Nonnull Predicate<String> scriptKnown, int minimumSpacing,
            @Nonnull Predicate<String> textShipped) {
        List<Finding> out = new ArrayList<>();
        for (DungeonAsset dungeon : dungeons) {
            if (dungeon == null || dungeon.getId() == null || !dungeon.isEnabled()) {
                continue;
            }
            auditOne(dungeon, templateKnown, templateFacts, prefabKnown, scriptKnown, minimumSpacing, textShipped,
                    out);
        }
        return out;
    }

    private static void auditOne(@Nonnull DungeonAsset dungeon, @Nonnull Predicate<String> templateKnown,
            @Nonnull Function<String, TemplateFacts> templateFacts, @Nonnull Predicate<String> prefabKnown,
            @Nonnull Predicate<String> scriptKnown, int minimumSpacing, @Nonnull Predicate<String> textShipped,
            @Nonnull List<Finding> out) {
        String id = dungeon.getId();
        String where = "dungeon '" + id + "'";
        boolean shared = dungeon.areasPerCopy() > 1;

        String template = dungeon.templateId();
        if (!asks(templateKnown, template)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_DUNGEON_TEMPLATE, where + " names the instance template '"
                    + template + "', which no loaded pack ships under Server/Instances/ (its pack may not be "
                    + "installed here), so no copy of it can open", id));
        } else {
            TemplateFacts facts = facts(templateFacts, template);
            if (facts != null && facts.instanceKey() != null) {
                out.add(Finding.error(DOMAIN, TEMPLATE_INSTANCE_KEY, where + " copies the template '" + template
                        + "', which pins its world with the InstanceKey '" + facts.instanceKey() + "': every other "
                        + "spawn or return that goes by that key converges on one world, which the dungeon's copies "
                        + "must not share. Name a template without an InstanceKey", id));
            }
            if (facts != null && shared && facts.portalTimed()) {
                out.add(Finding.error(DOMAIN, PACKED_PORTAL_TIMER, where + " shares each copy between "
                        + dungeon.areasPerCopy() + " parties, but its template '" + template + "' runs a portal "
                        + "timer, which belongs to the whole world and would close every party's run at once. Set "
                        + "Share.AreasPerCopy to 1, or name a template without one", id));
            }
            if (facts != null && shared && facts.worldEvents()) {
                out.add(Finding.error(DOMAIN, PACKED_WORLD_EVENTS, where + " shares each copy between "
                        + dungeon.areasPerCopy() + " parties, but world events run in its template '" + template
                        + "', and an event reaches every party in the world. Set Share.AreasPerCopy to 1, or "
                        + "name a template whose gameplay config runs no world events", id));
            }
        }

        String prefab = dungeon.prefabPath();
        if (prefab == null) {
            out.add(Finding.warning(DOMAIN, NO_DUNGEON_PREFAB, where + " names no Area.Prefab, so every area "
                    + "it opens is empty void", id));
        } else if (!asks(prefabKnown, prefab)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_DUNGEON_PREFAB, where + " names the prefab '" + prefab
                    + "', which no loaded pack ships under Server/Prefabs/ (write the path with its extension; "
                    + "its pack may not be installed here), so its areas stay empty", id));
        }

        // What clears a run: Complete.Bosses (a later Complete leaf joins this check when it can clear one).
        List<String> bosses = dungeon.completeBosses();
        if (bosses.isEmpty()) {
            out.add(Finding.warning(DOMAIN, NO_DUNGEON_CLEAR, where + " names no boss in Complete.Bosses, so a run "
                    + "there can never be cleared: every run ends failed, once its party leaves or its time limit passes. "
                    + "Name the encounter script of the fight that clears it", id));
        } else {
            for (String boss : bosses) {
                if (!asks(scriptKnown, boss)) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_DUNGEON_BOSS, where + " is cleared by beating the "
                            + "encounter script '" + boss + "', which no loaded pack ships (its pack may not be "
                            + "installed here; check the spelling otherwise), so that fight never clears a run", id));
                }
            }
        }

        if (shared && dungeon.spacing() < minimumSpacing) {
            out.add(Finding.error(DOMAIN, SPACING_TOO_SMALL, where + " sets Area.Spacing " + dungeon.spacing()
                    + ", under the " + minimumSpacing + " blocks this server's view distance needs (twice it), so "
                    + "parties in neighbouring areas see each other. Raise it, or leave it out for 2048", id));
        }

        SeasonGate.checkKnown(out, DOMAIN, dungeon.getSeason(), id);
        TextKeyAudit.check(out, DOMAIN, id, where + " Text.NameKey", dungeon.nameKey(), textShipped,
                "its name shows as the raw key");
        TextKeyAudit.check(out, DOMAIN, id, where + " Text.DescriptionKey", dungeon.descriptionKey(), textShipped,
                "its description shows as the raw key");
    }

    /**
     * What a template's config says, read off its JSON: the {@code InstanceKey} under {@code Plugin.Instance}
     * (or the top-level {@code Instance} of a version 2 or older file), a portal timer (read as the engine's
     * {@code PortalKey.getTimeLimitSeconds} reads it: {@code Plugin.PortalWorldConfig.Config.TimeLimitSeconds}
     * when written, else the named {@code PortalType}'s own limit, {@code portalTypeLimit}; above zero is a
     * timer), and world events: the {@code GameplayConfig} it names (the engine's {@code Default} when it
     * names none) runs some ({@code gameplayHasWorldEvents}), unless the template switches them off
     * ({@code Plugin.WorldEvents.EnableWorldEvents} false). Pure over its two engine questions.
     */
    @Nonnull
    public static TemplateFacts read(@Nonnull JsonObject root, @Nonnull Predicate<String> gameplayHasWorldEvents,
            @Nonnull Function<String, Integer> portalTypeLimit) {
        JsonObject plugin = object(root, "Plugin");
        String key = string(object(plugin, "Instance"), "InstanceKey");
        if (key == null) {
            key = string(object(root, "Instance"), "InstanceKey");
        }
        JsonObject portal = object(object(plugin, "PortalWorldConfig"), "Config");
        boolean portalTimed;
        JsonElement written = member(portal, "TimeLimitSeconds");
        if (written != null && !written.isJsonNull()) {
            portalTimed = number(portal, "TimeLimitSeconds") > 0;
        } else {
            String portalType = string(portal, "PortalType");
            Integer typeLimit = portalType == null ? null : limit(portalTypeLimit, portalType);
            portalTimed = typeLimit != null && typeLimit > 0;
        }
        JsonElement enabled = member(object(plugin, "WorldEvents"), "EnableWorldEvents");
        boolean eventsOff = enabled != null && enabled.isJsonPrimitive() && !enabled.getAsBoolean();
        String gameplay = string(root, "GameplayConfig");
        boolean worldEvents = !eventsOff
                && asks(gameplayHasWorldEvents, gameplay == null ? DEFAULT_GAMEPLAY_CONFIG : gameplay);
        return new TemplateFacts(key, portalTimed, worldEvents);
    }

    // ==================== the engine walk ====================

    /** Does a loaded pack ship this template? A name the engine refuses outright (a path escape) does not. */
    private static boolean templateKnown(@Nonnull String template) {
        try {
            return InstancesPlugin.doesInstanceAssetExist(template);
        } catch (IllegalArgumentException refused) {
            return false;
        } catch (Throwable t) {
            return true; // cannot tell is not "missing"
        }
    }

    /** The template's config, read through the engine's own lookup; null when it cannot be read. */
    @Nullable
    private static TemplateFacts templateFacts(@Nonnull String template) {
        try {
            Path config = InstancesPlugin.getInstanceAssetPath(template).resolve(TEMPLATE_CONFIG);
            JsonElement root = JsonParser.parseString(Files.readString(config, StandardCharsets.UTF_8));
            return root != null && root.isJsonObject()
                    ? read(root.getAsJsonObject(), DungeonValidator::gameplayHasWorldEvents,
                            DungeonValidator::portalTypeLimit) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Does the loaded gameplay config {@code id} list any world events, run at load or on a trigger? */
    private static boolean gameplayHasWorldEvents(@Nonnull String id) {
        try {
            GameplayConfig config = GameplayConfig.getAssetMap().getAsset(id);
            WorldEventsConfig events = config == null ? null : config.getPluginConfig().get(WorldEventsConfig.class);
            return events != null && (events.events().length > 0 || events.globalEvents().length > 0);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The loaded portal type's own time limit in seconds (-1 for none), or null when no such type is loaded. */
    @Nullable
    private static Integer portalTypeLimit(@Nonnull String id) {
        try {
            PortalType type = PortalType.getAssetMap().getAsset(id);
            return type == null ? null : type.getTimeLimitSeconds();
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code read}'s answer for {@code id}, or null when it throws. */
    @Nullable
    private static Integer limit(@Nonnull Function<String, Integer> read, @Nonnull String id) {
        try {
            return read.apply(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Does a loaded pack ship this prefab path (with its extension) under {@code Server/Prefabs/}? */
    private static boolean prefabKnown(@Nonnull String path) {
        try {
            return PrefabStore.get().findAssetPrefabPath(path) != null;
        } catch (IllegalArgumentException refused) {
            return false;
        } catch (Throwable t) {
            return true; // cannot tell is not "missing"
        }
    }

    /**
     * Whether an id names a loaded encounter script, read once for the whole pass through zc-encounter's
     * {@code EncounterScripts}; with none loaded (the engine not up) every id reads as known, since cannot tell
     * is not "missing".
     */
    @Nonnull
    private static Predicate<String> loadedScripts() {
        Map<String, EncounterScriptScan> loaded;
        try {
            loaded = EncounterScripts.scanLoaded();
        } catch (Throwable t) {
            return script -> true;
        }
        if (loaded.isEmpty()) {
            return script -> true;
        }
        Set<String> ids = new HashSet<>();
        for (String id : loaded.keySet()) {
            ids.add(id.toLowerCase(Locale.ROOT));
        }
        return script -> ids.contains(script.trim().toLowerCase(Locale.ROOT));
    }

    /** Twice the server's view radius, in blocks. */
    private static int minimumSpacing() {
        int radius;
        try {
            radius = HytaleServer.get().getConfig().getMaxViewRadius();
        } catch (Throwable t) {
            radius = FALLBACK_VIEW_RADIUS;
        }
        return 2 * radius * ChunkUtil.SIZE;
    }

    // ==================== helpers ====================

    /** {@code test}'s answer; one that throws reads as yes, since cannot tell is not "missing". */
    private static boolean asks(@Nonnull Predicate<String> test, @Nonnull String value) {
        try {
            return test.test(value);
        } catch (Throwable t) {
            return true;
        }
    }

    @Nullable
    private static TemplateFacts facts(@Nonnull Function<String, TemplateFacts> read, @Nonnull String template) {
        try {
            return read.apply(template);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static JsonElement member(@Nullable JsonObject object, @Nonnull String key) {
        return object == null ? null : object.get(key);
    }

    @Nullable
    private static JsonObject object(@Nullable JsonObject object, @Nonnull String key) {
        JsonElement value = member(object, key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    @Nullable
    private static String string(@Nullable JsonObject object, @Nonnull String key) {
        JsonElement value = member(object, key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = value.getAsString().trim();
        return text.isEmpty() ? null : text;
    }

    private static double number(@Nullable JsonObject object, @Nonnull String key) {
        JsonElement value = member(object, key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsDouble() : 0.0;
    }
}
