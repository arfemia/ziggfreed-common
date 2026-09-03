package com.ziggfreed.common.asset;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * The ONE reader of a server owner's id-keyed override file ({@code mods/ziggfreedcommon/<x>.json})
 * into a keyed config's owner layer, shared by every domain that keeps such a file.
 *
 * <p>The shape every such file has is a map from an id to the leaves that id should read
 * differently, and every reader wants the same four rules: drop the previous owner layer FIRST so a
 * re-read never compounds one override on another; decode each entry AGAINST the pack layer's own
 * answer for that id, through the very same codec the pack file uses, so one leaf keeps the rest
 * and an author needs no second schema; treat a {@code $}-prefixed top-level key as file-level
 * documentation or marker rather than an entry ({@link OwnerFiles}); and refuse a newer
 * {@code $SchemaVersion} whole rather than misread it. A malformed entry costs that entry and one
 * line naming it, never the file; a malformed file costs its overrides, never the server.
 *
 * <p>Read AFTER the pack layer has merged: an owner entry has nothing to inherit from until the
 * packs have landed, which is why every caller runs from its store's own load event rather than
 * from setup.
 */
public final class OwnerLayerReader {

    private OwnerLayerReader() {
    }

    /**
     * Read one owner file and replace {@code config}'s owner layer with what it says.
     *
     * @param logTag   the calling domain's log prefix, e.g. {@code "commerce"} or {@code "encounter"}
     * @param file     the owner file to read; a missing file is the common case and says nothing
     * @param noun     what one entry is CALLED in a line written for the server owner reading the log
     */
    public static <T extends JsonAsset<String>> void apply(@Nonnull String logTag, @Nonnull Path file,
            @Nonnull Class<T> assetClass, @Nonnull AssetBuilderCodec<String, T> codec,
            @Nonnull AbstractKeyedAssetConfig<T> config, @Nonnull String noun) {

        // Drop the previous layer FIRST: every entry below resolves its own base out of the pack
        // layer, and leaving the last read's answers in place would stack one override on another.
        config.mergeOwnerLayer(Map.of());

        JsonObject root = readObject(logTag, file);
        if (root == null || !OwnerFiles.schemaReadable(root, logTag, file)) {
            return;
        }

        Map<String, T> layer = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            if (OwnerFiles.isReservedKey(key)) {
                continue; // $Comment, $SchemaVersion and friends are file-level, not entries
            }
            String id = key.trim().toLowerCase(Locale.ROOT);
            T decoded = decode(logTag, entry.getValue(), id, assetClass, codec, config, file, noun);
            if (decoded != null) {
                layer.put(id, decoded);
            }
        }

        config.mergeOwnerLayer(layer);
        if (!layer.isEmpty()) {
            SafeLog.info("[" + logTag + "] " + file + ": " + layer.size() + " " + noun
                    + " override(s) in force");
        }
    }

    /** One entry, decoded against whatever the packs already say about its id. */
    @Nullable
    public static <T extends JsonAsset<String>> T decode(@Nonnull String logTag, @Nullable JsonElement body,
            @Nonnull String id, @Nonnull Class<T> assetClass, @Nonnull AssetBuilderCodec<String, T> codec,
            @Nonnull AbstractKeyedAssetConfig<T> config, @Nonnull Path file, @Nonnull String noun) {

        if (body == null || !body.isJsonObject()) {
            SafeLog.warn("[" + logTag + "] " + file + ": the " + noun + " override '" + id
                    + "' is not a block of settings, so it was skipped");
            return null;
        }
        T base = config.resolve(id);
        try {
            AssetExtraInfo.Data data =
                    new AssetExtraInfo.Data(assetClass, id, base == null ? null : id);
            return codec.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(body.toString()), base,
                    new AssetExtraInfo<>(data));
        } catch (Exception e) {
            SafeLog.warn("[" + logTag + "] " + file + ": the " + noun + " override '" + id
                    + "' could not be read, so it was skipped: " + e.getMessage());
            return null;
        }
    }

    /**
     * The file as a JSON object, or null when there is nothing usable to read. A missing file is the
     * common case and says nothing; a malformed one warns and is left exactly as the owner wrote it.
     */
    @Nullable
    public static JsonObject readObject(@Nonnull String logTag, @Nonnull Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return null;
            }
            JsonElement root = JsonParser.parseString(body);
            if (root == null || !root.isJsonObject()) {
                SafeLog.warn("[" + logTag + "] " + file + " is not a block of entries keyed by id, so "
                        + "nothing in it is in force");
                return null;
            }
            return root.getAsJsonObject();
        } catch (Exception e) {
            SafeLog.warn("[" + logTag + "] could not read " + file + ", so nothing in it is in force: "
                    + e.getMessage());
            return null;
        }
    }
}
