package com.ziggfreed.common.dialogue.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.dialogue.DeferredCodec;
import com.ziggfreed.common.dialogue.DialogueOption;
import com.ziggfreed.common.dialogue.DialogueTypeTable;

/**
 * One group of lines several conversations repeat, at
 * {@code Server/ZiggfreedCommon/DialogueFragments/<name>.json}. The FILE NAME is the group's name.
 *
 * <pre>{@code
 * { "Options": [
 *     { "LabelKey": "dialogue.shared.open_menu", "Open": "Hub" },
 *     { "LabelKey": "dialogue.shared.farewell", "Close": true } ] }
 * }</pre>
 *
 * <p>Any screen pulls it in by name with {@code "IncludeOptions": ["<name>"]}, and the group's lines
 * are appended after that screen's own - a footer. Write a farewell, an "open the menu" row or a
 * "where was I again?" line once here instead of in every conversation that ends with it.
 *
 * <p>The options are the same rows a screen authors, read by the same codec, so every shorthand and
 * every condition works here exactly as it does inside a conversation.
 *
 * <p><b>A conversation's own group wins.</b> A conversation that declares a group of this name under
 * its own {@code Fragments} uses that one, so a single character can say goodbye differently without
 * anything having to be renamed.
 *
 * <p><b>To retune a group somebody else shipped</b>, override the file by name (a same-named file in
 * a later pack), or name another file with {@code "Parent"} and restate only the options.
 */
public final class DialogueFragmentAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, DialogueFragmentAsset>> {

    /** The content path this type is authored under. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/DialogueFragments";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private DialogueOption[] options;

    /**
     * An option row's codec cannot exist when this class loads: its shape is the shorthand, action
     * and condition vocabulary the installed mods register while they start up. This stands in for
     * it and resolves to the finished codec at the first read, which the server always performs
     * after every plugin has finished starting.
     */
    private static final DeferredCodec<DialogueOption[]> OPTIONS =
            new DeferredCodec<>(() -> DialogueTypeTable.get().optionsArray());

    public static final AssetBuilderCodec<String, DialogueFragmentAsset> CODEC = AssetBuilderCodec.builder(
                    DialogueFragmentAsset.class,
                    DialogueFragmentAsset::new,
                    Codec.STRING,
                    // The engine's asset key is the verbatim filename while a screen names a group in
                    // whatever case it likes; canonicalizing at the one decode authority keeps the two
                    // spellings the same name.
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // An optional human-readable echo of the asset key (the authoritative key is the
            // filename), consumed by a no-op setter and emitted on encode for round-trip.
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op: the name comes from the filename */ },
                    a -> a.id)
            .add()
            .appendInherited(new KeyedCodec<>("Options", OPTIONS, false),
                    (a, v) -> a.options = v, a -> a.options, (a, p) -> a.options = p.options)
            .documentation("The lines this group contributes, in the order they are shown. A screen that "
                    + "names this group shows its own options first and these after.")
            .add()
            .build();

    public DialogueFragmentAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The group's lines, or null when the file declared none. */
    @Nullable
    public DialogueOption[] getOptions() {
        return options;
    }
}
