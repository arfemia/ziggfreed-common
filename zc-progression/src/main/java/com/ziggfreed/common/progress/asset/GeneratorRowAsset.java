package com.ziggfreed.common.progress.asset;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.codec.JsonTreeCodec;

/**
 * One member of a roster a generator walks, at {@code Server/ZiggfreedCommon/GeneratorRows/<Owner>/<Set>/<Id>.json}:
 * a row of named values in a {@code Set}, read by any generator's axis that names the library's rows source
 * ({@link GeneratorRows#SOURCE}) with that set in its {@code Filter}.
 *
 * <pre>{@code
 * { "Set": "Yours_Schools",
 *   "Order": 10,
 *   "Values": { "School": "Fire", "Line": "DamageBonus_{School}", "First": 5, "Second": 3 } }
 * }</pre>
 *
 * <p>The row is where a roster lives once, whatever generators fan out over it: a step generator and a rank
 * generator may both walk the same set, so a member added by one file (another mod's or pack's) reaches every
 * family written over the set, and an owner retunes or switches one member off in {@code generator-rows.json}
 * by the row's id. A value may itself hold {@code {token}} placeholders, filled from the same combination when
 * a generator expands ({@link GeneratorCore}'s second pass), so a step row can say {@code "Amount": "{First}"}
 * and each member's own number lands.
 *
 * <p>The file name is the row's id and the engine keys a file by its name alone, so a row id is unique across
 * every set: name it for its set and member ({@code Yours_School_Fire}).
 */
public final class GeneratorRowAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, GeneratorRowAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/GeneratorRows";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String set;
    @Nullable private Boolean enabled;
    @Nullable private Integer order;
    @Nullable private Map<String, JsonElement> values;

    public static final AssetBuilderCodec<String, GeneratorRowAsset> CODEC = AssetBuilderCodec.builder(
                    GeneratorRowAsset.class,
                    GeneratorRowAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .documentation("One member of a roster generators walk: a row of named values in a set. A generator's "
                    + "axis reads every switched-on row of a set with \"Source\": \"ziggfreedcommon:rows\" and "
                    + "\"Filter\": { \"Set\": \"<set>\" }, so a member is added with one file and reaches every "
                    + "generator over the set. The file name is the row's id, unique across every set; an owner "
                    + "retunes or switches one row off by that id in mods/ziggfreedcommon/generator-rows.json.")
            .appendInherited(new KeyedCodec<>("Set", Codec.STRING, false),
                    (a, v) -> a.set = v, a -> a.set, (a, p) -> a.set = p.set)
            .documentation("The set this row belongs to, matched without regard to case: the name a generator's "
                    + "axis writes in Filter.Set. A row with no Set is in no set and nothing reads it.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether the row is in its set; unauthored means true. Set false to take the member out "
                    + "of every generator over the set without deleting the file.").add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .metadata(EditorSchema.defaultValue(0L))
            .documentation("Where the row sits in its set, lowest first, the row id breaking a tie; unauthored "
                    + "means 0. A generator writes its members in this order. Leave gaps (10, 20, 30).").add()
            .appendInherited(new KeyedCodec<>("Values", new InheritMapCodec<>(JsonTreeCodec.scalar())
                            .documented("Each token this row fills, by name, to its value.",
                                    "Text, a number or true/false. A value written as exactly one {token} "
                                            + "takes that token's own type."), false),
                    (a, v) -> a.values = v, a -> a.values, (a, p) -> a.values = p.values)
            .documentation("The tokens this row fills, by name, each to one value: text, a number or "
                    + "true/false, a number staying a number where a generator's body writes it as exactly "
                    + "\"{Name}\". A value may hold {token} placeholders of its own, filled from the same "
                    + "combination when a generator expands. Merged per name under Parent and in an owner "
                    + "entry, so one value is retuned alone.").add()
            .build();

    public GeneratorRowAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The set this row belongs to, trimmed; null when unauthored. */
    @Nullable
    public String getSet() {
        return set == null || set.isBlank() ? null : set.trim();
    }

    /** Is the row in its set? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** Where the row sits in its set, lowest first; 0 when unauthored. */
    public int order() {
        return order == null ? 0 : order;
    }

    /** Does this row belong to {@code wanted}, matched without regard to case? */
    public boolean inSet(@Nullable String wanted) {
        String mine = getSet();
        return mine != null && wanted != null && mine.equalsIgnoreCase(wanted.trim());
    }

    /**
     * The tokens this row fills, name to value, in authored order: each scalar value, keeping its JSON type. A
     * value that is not a scalar (a null) fills nothing.
     */
    @Nonnull
    public Map<String, JsonPrimitive> bindings() {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, JsonPrimitive> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            JsonElement value = entry.getValue();
            if (!name.isEmpty() && value != null && value.isJsonPrimitive()) {
                out.put(name, value.getAsJsonPrimitive());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** This row's id lower-cased, the key every layer files it under. */
    @Nonnull
    public String key() {
        return id == null ? "" : id.toLowerCase(Locale.ROOT);
    }
}
