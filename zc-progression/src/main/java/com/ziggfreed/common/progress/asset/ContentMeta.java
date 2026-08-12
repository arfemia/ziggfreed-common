package com.ziggfreed.common.progress.asset;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonValue;

import com.google.gson.JsonElement;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.codec.JsonTreeCodec;

/**
 * The {@code Meta} group every content asset here carries: a map from a NAMESPACE to whatever that
 * namespace's owner wants to say about this piece of content.
 *
 * <pre>{@code
 * "Meta": { "yourmod": { "Chain": { "Id": "mining", "Tier": 2 }, "ServerFirst": true } }
 * }</pre>
 *
 * <p><b>Why it exists.</b> A quest or an achievement usually means something extra to the mod that
 * runs it - which ladder it is a rung of, which conversation follows it, which server feature has to
 * be on - and none of that belongs in a schema meant to serve every mod at once. So the schema
 * carries a labelled box instead, and each mod reads its own.
 *
 * <p><b>Nothing here interprets a block.</b> Whatever is written under a namespace is kept exactly as
 * authored and handed on, so an asset authored for two mods still loads with only one of them
 * installed, and the absent mod's block simply rides along untouched. That is what makes this safe to
 * put in shared content: no reader can be broken by a namespace it has never heard of.
 *
 * <p><b>Inheritance is per namespace.</b> Under {@code Parent}, a child inherits every namespace it
 * does not mention, and a namespace it DOES mention replaces the parent's block for that namespace
 * whole. There is deliberately no deep merge inside a block: the block has no schema at this level,
 * so a merge would have to guess what an authored key means, and "state the block you want" is the
 * rule an author can hold in their head. Author the parent's keys again alongside your own when you
 * mean to keep them.
 *
 * <p><b>Reading one.</b> A consumer declares an ordinary {@code BuilderCodec} for its own block and
 * calls {@link #decode}, which hands back the typed value and reports any key the codec does not
 * know - so a typo surfaces as a named warning rather than a silently ignored knob.
 */
public final class ContentMeta {

    /**
     * The codec the {@code Meta} field uses on every content asset: a keyed map whose values are
     * captured verbatim. A map rather than a structured object because the keys are namespaces
     * nobody can enumerate ahead of time, and {@link InheritMapCodec} because the map has to overlay
     * per key under {@code Parent} rather than being replaced whole.
     */
    public static final Codec<Map<String, JsonElement>> CODEC =
            new InheritMapCodec<>(JsonTreeCodec.object());

    /** What the field is called in every asset that carries one. */
    public static final String KEY = "Meta";

    /** The one sentence every asset's {@code Meta} field documents itself with. */
    public static final String DOCUMENTATION =
            "Extra facts about this content, filed under the namespace of whichever mod they belong to. "
                    + "Nothing here is interpreted by this library: a mod reads its own namespace and every "
                    + "other one rides along untouched, so content authored for two mods still loads with "
                    + "one of them installed. Under Parent a namespace this file names replaces the "
                    + "inherited block for that namespace whole, and every namespace it does not name is "
                    + "inherited as it was.";

    private ContentMeta() {
    }

    /** The map with a null treated as an empty one, so a caller never branches on it. */
    @Nonnull
    public static Map<String, JsonElement> orEmpty(@Nullable Map<String, JsonElement> meta) {
        return meta == null ? Map.of() : meta;
    }

    /**
     * The block authored under {@code namespace}, exactly as written, or null when the content
     * carries none. Namespaces are matched case-insensitively, since a namespace is a mod id rather
     * than a value.
     */
    @Nullable
    public static JsonElement block(@Nullable Map<String, JsonElement> meta, @Nonnull String namespace) {
        Map<String, JsonElement> all = orEmpty(meta);
        JsonElement exact = all.get(namespace);
        if (exact != null) {
            return exact.isJsonNull() ? null : exact;
        }
        for (Map.Entry<String, JsonElement> entry : all.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(namespace)) {
                JsonElement value = entry.getValue();
                return value == null || value.isJsonNull() ? null : value;
            }
        }
        return null;
    }

    /**
     * Decode the block authored under {@code namespace} through {@code codec}, or null when the
     * content authored no such block.
     *
     * <p>{@code onUnknownKey} is handed every key the codec has no field for, so a consumer can say
     * so in its own words. Decoding continues past one: a key nobody claims is a typo or a knob from
     * a newer version, and neither is a reason to lose the block.
     *
     * @param codec the consumer's own block codec, which IS its schema for that namespace
     */
    @Nullable
    public static <T> T decode(@Nullable Map<String, JsonElement> meta, @Nonnull String namespace,
            @Nonnull Codec<T> codec, @Nullable Consumer<String> onUnknownKey) {
        JsonElement block = block(meta, namespace);
        if (block == null || !block.isJsonObject()) {
            return null;
        }
        ExtraInfo extraInfo = new ExtraInfo();
        BsonValue bson = JsonTreeCodec.object().encode(block, extraInfo);
        T decoded = codec.decode(bson, extraInfo);
        if (onUnknownKey != null) {
            List<String> unknown = extraInfo.getUnknownKeys();
            for (String key : unknown) {
                if (key != null && !key.isBlank()) {
                    onUnknownKey.accept(key);
                }
            }
        }
        return decoded;
    }
}
