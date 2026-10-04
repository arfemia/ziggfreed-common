package com.ziggfreed.common.calendar.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.JsonTreeCodec;

/**
 * A native world-spawn rule a calendar event writes while it runs, at
 * {@code Server/ZiggfreedCommon/CalendarSpawns/<Owner>/<Id>.json}. The FILE NAME is the id.
 *
 * <pre>{@code
 * { "Event": "Spring_Fair",
 *   "Rule": "Spring_Fair_Rabbits",
 *   "Spawn": { "Environments": ["Env_Portals_Hedera"],
 *              "NPCs": [ { "Id": "Spring_Fair_Rabbit", "Weight": 10, "SpawnBlockSet": "Soil" } ],
 *              "DayTimeRange": [6, 18] } }
 * }</pre>
 *
 * <p>{@code Spawn} is the engine's own {@code NPC/Spawn/World} rule body, kept verbatim and decoded by the
 * engine's codec when the event goes live, under the id {@code Rule} names (this file's id when
 * unauthored). Several files may name ONE Rule for different events: while more than one of their events
 * runs, the highest {@code Priority} owns the rule, so a short event inside a long one can rewrite the long
 * one's rule with higher weights for its days and hand it back after. When no running event owns a rule it
 * is rewritten with {@code MoonPhaseWeightModifiers [0]}, which stops every new spawn; creatures already
 * out are left to the engine's own population and despawn rules.
 *
 * <p>The engine lets ONE rule hold a role in an environment and refuses a second with a SEVERE line, so
 * give every rule its own roles or environments, and a Rule id no other file uses.
 */
public final class CalendarSpawnAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, CalendarSpawnAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/CalendarSpawns";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String event;
    @Nullable private String rule;
    @Nullable private Integer priority;
    @Nullable private JsonElement spawn;

    public static final AssetBuilderCodec<String, CalendarSpawnAsset> CODEC = AssetBuilderCodec.builder(
                    CalendarSpawnAsset.class,
                    CalendarSpawnAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Event", Codec.STRING, false),
                    (a, v) -> a.event = v, a -> a.event, (a, p) -> a.event = p.event)
            .documentation("The calendar event whose runs this rule rides, by its file name.")
            .add()
            .appendInherited(new KeyedCodec<>("Rule", Codec.STRING, false),
                    (a, v) -> a.rule = v, a -> a.rule, (a, p) -> a.rule = p.rule)
            .documentation("The id the rule is written under in NPC/Spawn/World; unauthored means this file's own "
                    + "id. Files naming one Rule share it, and the highest Priority among their running events owns "
                    + "it. Use an id no other file ships.")
            .add()
            .appendInherited(new KeyedCodec<>("Priority", Codec.INTEGER, false),
                    (a, v) -> a.priority = v, a -> a.priority, (a, p) -> a.priority = p.priority)
            .metadata(EditorSchema.defaultValue(0L))
            .documentation("Which file owns a shared Rule while more than one of their events runs: the highest "
                    + "wins, and a tie goes to the file whose id sorts first. Unauthored means 0.")
            .add()
            .appendInherited(new KeyedCodec<>("Spawn", JsonTreeCodec.object(), false),
                    (a, v) -> a.spawn = v, a -> a.spawn, (a, p) -> a.spawn = p.spawn)
            .documentation("The world-spawn rule itself, exactly as a file under Server/NPC/Spawn/World says it: "
                    + "Environments, NPCs, DayTimeRange and the rest. The engine reads it when the event goes live, "
                    + "so a mistake here shows in the server log then.")
            .add()
            .build();

    public CalendarSpawnAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The event this rule rides, lower-cased, or null when unauthored. */
    @Nullable
    public String eventId() {
        return event == null || event.isBlank() ? null : event.trim().toLowerCase(Locale.ROOT);
    }

    /** The rule id it is written under, lower-cased: Rule, else this file's id. */
    @Nonnull
    public String ruleId() {
        return rule == null || rule.isBlank() ? id : rule.trim().toLowerCase(Locale.ROOT);
    }

    /** Precedence over a shared Rule; 0 when unauthored. */
    public int priority() {
        return priority == null ? 0 : priority;
    }

    /** The rule body as JSON text, or null when Spawn is missing or not an object. */
    @Nullable
    public String spawnJson() {
        return spawn != null && spawn.isJsonObject() ? spawn.toString() : null;
    }
}
