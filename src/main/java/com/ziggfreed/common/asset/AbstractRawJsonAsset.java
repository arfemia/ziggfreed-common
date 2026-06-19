package com.ziggfreed.common.asset;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * Base for "raw passthrough" (Pattern B) framework asset types - the generic,
 * mod-agnostic lift of the MMO Skill Tree mod's own raw-payload base. Each concrete
 * type (e.g. dialogues, dialogue templates) is its own class - Hytale's
 * {@code AssetRegistry} keys stores by asset class, so distinct types cannot share one
 * class - but they all carry the same shape: a String {@code Id} and a {@code Payload}
 * JSON object holding the entry in its authoring format.
 *
 * <p>The payload is parsed as a {@link BsonDocument} (so pack authors write a nested
 * JSON object directly, no escaping), then exposed as a Gson {@link JsonObject} via
 * {@link #getPayloadAsJsonObject()} for a consumer's parser / the dialogue
 * template-resolve + decode path. The asset store is purely a discovery / merge / reload
 * delivery mechanism; the engine never needs to understand the body schema, so no
 * validation logic is duplicated.
 *
 * <p>This is the Pattern-B base. Use it ONLY when a type needs the
 * {@code extends}/{@code params}/sugar template DSL (which rewrites raw JSON before
 * parse and so is incompatible with a typed-decode codec). A self-contained, fixed-field
 * type should be a structured {@code AssetBuilderCodec} instead.
 *
 * <p>Concrete subclasses declare {@code implements JsonAssetWithMap<String,
 * DefaultAssetMap<String, ThatClass>>} and a {@code static final CODEC} built via
 * {@link #rawCodec(Class, Supplier)}.
 *
 * <p>The pack JSON shape is:
 * <pre>{@code
 * { "Id": "<entry-id>", "Payload": { ...nested entry JSON... } }
 * }</pre>
 */
public abstract class AbstractRawJsonAsset implements JsonAsset<String> {

    /** Relaxed-mode JSON writer: emits regular JSON numbers/dates, not extended-JSON wrappers. */
    private static final JsonWriterSettings RELAXED_JSON =
            JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();

    protected String id;
    protected BsonDocument payload;
    protected AssetExtraInfo.Data data;

    @Override
    public String getId() {
        return id;
    }

    /** Raw decoded payload (a nested JSON object captured into BSON form). */
    @Nullable
    public BsonDocument getPayload() {
        return payload;
    }

    /**
     * Convert the BSON-decoded payload to a Gson {@link JsonObject} so a consumer's
     * parser / the dialogue resolver can consume it. Returns null when the payload is
     * missing or the JSON round-trip fails (unlikely - BSON RELAXED mode produces
     * standard JSON).
     */
    @Nullable
    public JsonObject getPayloadAsJsonObject() {
        if (payload == null) return null;
        try {
            return JsonParser.parseString(payload.toJson(RELAXED_JSON)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build the shared {@code Name} + {@code Payload} codec for a concrete raw asset
     * type. The {@code Payload} field captures a nested JSON object via
     * {@code Codec.BSON_DOCUMENT}. The {@code Name} field is an optional human-readable
     * echo of the asset key (Hytale already derives the authoritative key from the
     * filename) - consumed via a no-op setter so it does not trip the
     * {@code Unused key(s)} warning, and emitted on encode so an export round-trips it.
     */
    protected static <T extends AbstractRawJsonAsset> AssetBuilderCodec<String, T> rawCodec(
            Class<T> assetClass, Supplier<T> constructor) {
        return AssetBuilderCodec.builder(
                        assetClass,
                        constructor,
                        Codec.STRING,
                        (asset, id) -> asset.id = id,
                        asset -> asset.id,
                        (asset, extra) -> asset.data = extra,
                        asset -> asset.data)
                .append(new KeyedCodec<>("Name", Codec.STRING, false),
                        (asset, name) -> { /* no-op - id already comes from the filename */ },
                        asset -> asset.id)
                .add()
                .append(new KeyedCodec<>("Payload", Codec.BSON_DOCUMENT, true),
                        (asset, payload) -> asset.payload = payload,
                        asset -> asset.payload)
                .add()
                .build();
    }
}
